# Non-standard runtimes

BootUI assumes a locally reachable web application. Command-line apps and containers each need one small adjustment.

## Command-line (non-web) applications

BootUI also works in non-web applications, such as command-line apps. The starter brings Spring MVC and an embedded
servlet container, so when BootUI is active it automatically starts a servlet web server even if your application is
configured as non-web (`spring.main.web-application-type=none` or `SpringApplication#setWebApplicationType(NONE)`). Your
`CommandLineRunner` / `ApplicationRunner` beans still run as usual; the application simply keeps running so the console
stays reachable.

Because BootUI only activates in development contexts by default, this never affects production. Applications that are
already servlet web apps, or that are explicitly configured as reactive, are left untouched. To opt out and keep your
application's web-application type exactly as declared, set `bootui.force-web=false`.

BootUI never forces the web type on Spring Cloud's transient **bootstrap** application context (the early, non-web
context created by `spring-cloud-starter-bootstrap` for Spring Cloud Config). That context has no embedded web server,
so forcing it would crash startup with `MissingWebServerFactoryBeanException`; BootUI detects it and leaves it alone,
then forces the servlet web type on your main application as usual.

## Running inside a Docker container

BootUI works when your application runs inside a container, but its loopback-only safety filter needs a small opt-in
first. When you publish a port (for example `docker run -p 8080:8080 …`) and browse to `http://localhost:8080/bootui`,
the request reaches the application from the **Docker gateway** (a non-loopback address), so BootUI rejects it by
default — it fails closed for non-loopback callers. The gateway address depends on the Docker flavor:

- **Linux Docker Engine** uses the default bridge gateway, typically `172.17.0.1` (inside `172.16.0.0/12`).
- **Docker Desktop** (macOS and Windows) routes published-port traffic through its gateway VM, so the request arrives
  from `192.168.65.1` (inside `192.168.65.0/24`). This is the address you will see in a `LocalhostOnlyFilter` rejection
  log line such as `BootUI rejected non-loopback request from 192.168.65.1 to /bootui/api/health`.

Check your own setup with `docker network inspect bridge` (look at `IPAM.Config.Gateway`) or the source address in the
BootUI rejection log line, and trust that range.

Two things have to be in place:

1. **Activate BootUI inside the container.** A repackaged jar strips DevTools, and activation checks the _active_
   profiles (not `spring.profiles.default`), so set one explicitly — `SPRING_PROFILES_ACTIVE=dev` or `BOOTUI_ENABLED=ON`.
   Without this you get a `404` on `/bootui`, not a rejection.
2. **Trust the container gateway.** Set `bootui.trust-container-gateway=AUTO`. While running inside a container BootUI
   auto-detects the gateway address(es) that published-port traffic arrives from and trusts just those `/32` (or `/128`)
   hosts as loopback-equivalent — no need to know the gateway IP or subnet, on any Docker flavor.

   ::: details How detection works, and what stays enforced

   Detection covers both runtimes: on **Linux Docker Engine** it reads the bridge default gateway from
   `/proc/net/route` (the SNAT source, e.g. `172.17.0.1`); on **Docker Desktop** (macOS/Windows) the SNAT source
   (`192.168.65.1`) is _not_ the route-table gateway, so BootUI resolves the `gateway.docker.internal` DNS name that
   Docker Desktop injects into every container. This relaxes only the source-address check; the `Host` allow-list
   (DNS-rebinding defense) and cross-site write (CSRF) protection stay in force, and sibling containers are **not**
   trusted (their traffic carries their own IP, not the gateway). The lookup is resolved once and cached, and fails
   closed: on Linux Docker Engine and bare metal `gateway.docker.internal` does not resolve, which simply means "no
   extra gateway" (the route-table detection still applies). On Docker Desktop the Docker-Desktop branch therefore
   relies on Docker's embedded DNS resolving `gateway.docker.internal`; if that name is unavailable (for example you
   have disabled it), set `bootui.trusted-proxies=192.168.65.0/24` instead.

   :::

```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  your-image
```

Then open <http://localhost:8080/bootui> from the host. Use `ON` instead of `AUTO` to trust a detected gateway even when
the container heuristics are inconclusive.

> **Security caveat — published-port bind address.** `-p 8080:8080` binds `0.0.0.0:8080` on the host, so a remote LAN
> client hitting `hostLanIP:8080` is **also** SNAT'd to the same gateway. Trusting the gateway `/32` therefore trusts
> "anything that can reach the published port", which in this bind mode includes the LAN — not strictly loopback. This is
> acceptable for a dev tool (BootUI is dev/local-gated and the Host + CSRF defenses remain in force) and is why the
> feature is **off by default**. For strict loopback equivalence, bind the port to localhost only:
> `docker run -p 127.0.0.1:8080:8080 …`.

### Custom proxies, bridges, or LAN setups

If you front the app with a reverse proxy, use a custom Docker network, or otherwise reach BootUI from a source other
than the auto-detected gateway, use `bootui.trusted-proxies` instead. It trusts additional source IP ranges (CIDR
notation) while keeping the same Host and CSRF defenses — pick the range that matches your Docker flavor:

```properties
# Linux Docker Engine: the default bridge gateway 172.17.x lives inside 172.16.0.0/12
bootui.trusted-proxies=172.16.0.0/12
# Docker Desktop (macOS/Windows): the gateway is 192.168.65.1, so trust 192.168.65.0/24 instead
#bootui.trusted-proxies=192.168.65.0/24
# Accept the hostname you browse with (localhost is already a built-in loopback name)
bootui.allowed-hosts=localhost
```

Or as environment variables on the container:

```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e BOOTUI_TRUSTED_PROXIES=172.16.0.0/12 \
  your-image
```

On Docker Desktop, use `-e BOOTUI_TRUSTED_PROXIES=192.168.65.0/24` instead.

Scope `bootui.trusted-proxies` as narrowly as you can: for a user-defined Docker network, prefer that network's specific
subnet over the broad `172.16.0.0/12`, and keep it limited to trusted local/dev networks. Reserve
`bootui.allow-non-localhost=true` as a blunt last resort.
