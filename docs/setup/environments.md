# Non-standard runtimes

BootUI assumes a locally reachable web application. Command-line applications and containers each need one small
adjustment.

## Command-line (non-web) applications

The starter brings Spring MVC and an embedded servlet container. When BootUI is active, it therefore starts a servlet
web server even if your application declares `spring.main.web-application-type=none`, or calls
`SpringApplication#setWebApplicationType(NONE)`. Your `CommandLineRunner` and `ApplicationRunner` beans still run as
usual, and the application then keeps running so the console stays reachable.

Set `bootui.force-web=false` to opt out and keep your declared web-application type. Applications that are already
servlet web applications, or that are explicitly reactive, are left untouched, and because BootUI activates only in
development by default, production is unaffected.

::: tip Spring Cloud bootstrap contexts are never forced
The transient bootstrap context created by `spring-cloud-starter-bootstrap` has no embedded web server, so forcing it
would fail startup with `MissingWebServerFactoryBeanException`. BootUI detects that context, leaves it alone, and
forces the servlet web type on your main application as usual.
:::

## Running inside a Docker container

When you publish a port and browse to `http://localhost:8080/bootui`, the request reaches the application from the
Docker gateway, which is not a loopback address. BootUI fails closed and rejects it. Two settings fix that:

1. **Activate BootUI inside the container.** A repackaged jar strips DevTools, and activation reads the *active*
   profiles rather than `spring.profiles.default`. Set `SPRING_PROFILES_ACTIVE=dev` or `BOOTUI_ENABLED=ON`. Without
   this you get a 404 on `/bootui`, not a rejection.
2. **Trust the container gateway.** Set `bootui.trust-container-gateway=AUTO`. BootUI then detects the gateway
   addresses that published-port traffic arrives from and trusts only those `/32` or `/128` hosts as
   loopback-equivalent, on any Docker flavor.

```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  your-image
```

Then open <http://localhost:8080/bootui> from the host. Use `ON` instead of `AUTO` to trust a detected gateway even
when the container heuristics are inconclusive.

::: details How detection works, and what stays enforced

On Linux Docker Engine, BootUI reads the bridge default gateway from `/proc/net/route`, typically `172.17.0.1`. On
Docker Desktop the SNAT source is `192.168.65.1`, which is not the route-table gateway, so BootUI resolves the
`gateway.docker.internal` name that Docker Desktop injects into every container. The lookup is resolved once and
cached.

Detection relaxes only the source-address check. The `Host` allow-list and cross-site-write protection stay in force,
and sibling containers are not trusted, because their traffic carries their own address rather than the gateway's.

It also fails closed. On Linux Docker Engine and on bare metal, `gateway.docker.internal` does not resolve, which
means no extra gateway. If that name is unavailable on Docker Desktop, set `bootui.trusted-proxies=192.168.65.0/24`
instead.

:::

::: warning The published-port bind address matters
`-p 8080:8080` binds `0.0.0.0:8080` on the host, so a LAN client reaching `hostLanIP:8080` is SNAT'd to the same
gateway. Trusting the gateway `/32` therefore trusts anything that can reach the published port, which in this bind
mode includes the LAN. That is why the feature is off by default. For strict loopback equivalence, bind the port to
localhost: `docker run -p 127.0.0.1:8080:8080 …`.
:::

### Custom proxies, bridges, or LAN setups

If you front the application with a reverse proxy, use a custom Docker network, or otherwise reach BootUI from a
source other than the detected gateway, use `bootui.trusted-proxies`. It trusts additional source ranges in CIDR
notation while keeping the same Host and cross-site-write defenses. Pick the range that matches your Docker flavor:

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

Scope `bootui.trusted-proxies` as narrowly as you can. For a user-defined Docker network, prefer that network's own
subnet over the broad `172.16.0.0/12`, and keep it limited to trusted local networks. Check your own setup with
`docker network inspect bridge`, under `IPAM.Config.Gateway`, or read the source address from the BootUI rejection log
line. Reserve `bootui.allow-non-localhost=true` as a last resort.

### Persisting console state across image rebuilds

BootUI keeps two developer-local files under `.bootui/` in the application's working directory:

| File                            | Holds                                                                                    |
| ------------------------------- | ---------------------------------------------------------------------------------------- |
| `application-bootui.properties` | Runtime overrides created from the Configuration panel. |
| `boot-ui.yml`                   | Advisor findings you dismissed, under a `dismissedRules:` node.                          |

Inside a container that directory belongs to the image, so a rebuild starts from a clean slate: toggles return to
their configured value and dismissed findings reappear. `bootui.overrides-file` fixes both at once, because BootUI
resolves `boot-ui.yml` in the same directory as the configured overrides file on all three stacks. Point it at a
mounted path:

```yaml
services:
  app:
    environment:
      SPRING_PROFILES_ACTIVE: dev
      BOOTUI_TRUST_CONTAINER_GATEWAY: AUTO
      BOOTUI_OVERRIDES_FILE: /var/bootui/application-bootui.properties
    volumes:
      - bootui-state:/var/bootui

volumes:
  bootui-state:
```

BootUI creates the directory if it does not exist. Both files now survive `docker compose up --build`.

::: warning Set it from the environment, not from `application.properties`
An `EnvironmentPostProcessor` reads the overrides file before your configuration files are loaded. A
`bootui.overrides-file` declared in `application.properties` would relocate the dismissed-findings file but not the
overrides the console writes. Use the environment variable or a `-D` system property, as shown above, so both files
agree on one directory.
:::

A value you want to hold across every environment belongs in configuration rather than in a file the console rewrites.
`BOOTUI_MCP_ENABLED=ON` states that intent explicitly and is reapplied at every start, so the MCP Server panel's
toggle — which is in-memory only and is never written to either file — cannot quietly become the new default.

The volume is the right tool for what a developer discovers while using the console, dismissals above all.

::: details Committing a baseline of accepted findings
Because `boot-ui.yml` is a small, stable file, a team can commit it next to the application configuration and copy it
into the image, so every rebuild starts from the same "these findings are known and accepted here" baseline:

```yaml
# .bootui/boot-ui.yml
dismissedRules:
  - RAPI-MAP-004
  - HIB-FETCH-001
```

```dockerfile
COPY .bootui/boot-ui.yml /var/bootui/boot-ui.yml
```

Two things to know. Dismissing from the console rewrites the whole file, so a read-only mount makes the *Dismiss*
button fail. Either keep the directory writable, since a rebuild still restores the committed baseline, or set the
advisor panels read-only with `bootui.panels.<id>.read-only=true`, which disables the dismiss and restore controls.
Vulnerability dismissals are also keyed `<vulnerability id>::<group:artifact>` rather than by a bare rule id. See
[dismissing a vulnerability](../features/advisors.md#dismissing-a-vulnerability).

Any other top-level section in the file is preserved when BootUI rewrites it.
:::
