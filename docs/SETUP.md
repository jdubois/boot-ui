# Setup

## 1) Prerequisites

- Java 17 or later
- Spring Boot 4.x application
- Maven or Gradle (or their local wrappers)

## 2) Add the starter dependency

The simplest setup is to drop the starter into your build — nothing else is required. BootUI ships dormant and only
wakes up in local development (the `dev` / `local` profiles, or when `spring-boot-devtools` is on the classpath), and
it force-disables itself in `prod` / `production`.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>1.6.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.6.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.6.0")
```

:::

Prefer to keep the starter jar out of your production artifact entirely? Use the
[dev-only profile setup](#advanced-scope-bootui-to-a-dev-only-profile) instead.

## 3) Run your app in development mode

Start the application with the `dev` profile active so BootUI turns on:

::: tabs#build

@tab Maven

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

@tab Gradle

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

:::

BootUI also activates automatically when `spring-boot-devtools` is on the classpath. To force it on or off:

```properties
bootui.enabled=AUTO
bootui.enabled=ON
bootui.enabled=OFF
```

`prod` and `production` profiles disable BootUI unless `bootui.enabled=ON` is set. Invalid `bootui.enabled` values fail
closed and keep BootUI disabled.

::: tip YAML users
In `application.yml`, YAML parses `ON`/`OFF` (and `yes`/`no`/`true`/`false`) as booleans, so
`bootui.enabled: ON` arrives as `true`. BootUI accepts these: `ON`/`true`/`yes` enable it and
`OFF`/`false`/`no` disable it, so the documented `bootui.enabled: ON` works unquoted in YAML.
:::

## 4) Open BootUI

Nice job! BootUI is now configured 🚀

Visit: <http://localhost:8080/bootui>

## Advanced: scope BootUI to a dev-only profile

The simple install above leaves the starter jar on the classpath in every build — BootUI just stays disabled outside
development. If you would rather keep the starter out of your production build entirely, declare it in a dedicated
`dev` build profile instead. The same profile can switch on the `dev` Spring Boot profile for you, so a single flag
both adds the starter and activates BootUI.

::: tabs#build

@tab Maven

Add a `dev` Maven profile that declares the starter and tells the Spring Boot plugin to run with the `dev`
Spring Boot profile:

```xml
<profiles>
  <profile>
    <id>dev</id>
    <dependencies>
      <dependency>
        <groupId>com.julien-dubois.bootui</groupId>
        <artifactId>bootui-spring-boot-starter</artifactId>
        <version>1.6.0</version>
      </dependency>
    </dependencies>
    <build>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <configuration>
            <profiles>
              <profile>dev</profile>
            </profiles>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

@tab Gradle

Add a `dev` Gradle profile that is activated with the `-Pdev` project property. It pulls in the starter and
sets the `dev` Spring Boot profile on `bootRun`:

```groovy
// Groovy DSL (build.gradle)
if (project.hasProperty('dev')) {
    dependencies {
        runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.6.0'
    }
    tasks.named('bootRun') {
        systemProperty 'spring.profiles.active', 'dev'
    }
}
```

```kotlin
// Kotlin DSL (build.gradle.kts)
if (project.hasProperty("dev")) {
    dependencies {
        "runtimeOnly"("com.julien-dubois.bootui:bootui-spring-boot-starter:1.6.0")
    }
    tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
        systemProperty("spring.profiles.active", "dev")
    }
}
```

:::

Then activate the profile when you start the app. This both adds the BootUI starter and turns on the `dev`
Spring Boot profile:

::: tabs#build

@tab Maven

```bash
./mvnw spring-boot:run -Pdev
```

@tab Gradle

```bash
./gradlew bootRun -Pdev
```

:::

## Safety defaults

BootUI is intended for local development only. By default it:

- Activates in `AUTO` mode only for the `dev` / `local` profiles or DevTools.
- Rejects non-loopback requests.
- Permits `/bootui/**` through Spring Security when Spring Security is present, with a startup warning, so the local
  console remains directly reachable while the loopback-only filter still applies.
- Masks secret-like configuration values.
- Exposes the local Actuator endpoints used by BootUI panels when BootUI is active.
- Captures local application spans for the Traces panel when telemetry and the panel are enabled.
- Disables itself for `prod` / `production` profiles.
- Stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files.

Every visible panel can be disabled with `bootui.panels.<panel-id>.enabled=false`. Panels with mutating browser actions
can also be made read-only with `bootui.panels.<panel-id>.read-only=true`, and `bootui.read-only=true` makes the whole
BootUI application read-only. See the [property reference](PROPERTIES.md) for the full panel list.

## Runtime overrides

The Configuration panel can create, update, and delete local runtime overrides. Overrides are stored in
`.bootui/application-bootui.properties` by default, loaded at high precedence on the next startup, and never modify your
application source configuration. Already-bound `@ConfigurationProperties` beans may keep their previous value until the
app restarts; BootUI returns that warning with every override mutation.

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
2. **Trust the container gateway.** The simplest opt-in is `bootui.trust-container-gateway=AUTO`: while running inside a
   container BootUI auto-detects the gateway address(es) that published-port traffic arrives from and trusts just those
   `/32` (or `/128`) hosts as loopback-equivalent — no need to know the gateway IP or subnet, on any Docker flavor.
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

## Troubleshooting

| Symptom                      | Check                                                                                                                                   |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `/bootui` returns 404        | Use the `dev` or `local` profile, add DevTools, or set `bootui.enabled=ON`. In `application.yml`, `bootui.enabled: ON` is valid — YAML parses it as a boolean, which BootUI accepts as `ON`. |
| BootUI is disabled in `prod` | This is intentional; only `bootui.enabled=ON` can force activation with a disabled profile.                                             |
| Command-line app now stays up | Expected: BootUI starts a servlet server so the console is reachable. Set `bootui.force-web=false` to keep the app non-web.              |
| Browser is rejected          | BootUI accepts loopback callers and fails closed for everything else. Inside a container, set `bootui.trust-container-gateway=AUTO` to auto-detect and trust the default gateway `/32` (the SNAT source of published-port traffic) — no subnet needed on any Docker flavor, and the Host + CSRF protections stay on. For a custom proxy/bridge or LAN access, add that source range to `bootui.trusted-proxies` instead — `172.16.0.0/12` on Linux Docker Engine, `192.168.65.0/24` on Docker Desktop (macOS/Windows) — plus the hostname you browse with to `bootui.allowed-hosts`. Use `bootui.allow-non-localhost=true` only as a blunt last resort on a trusted local network. |
| Spring Security blocks UI    | BootUI auto-registers a `/bootui/**` permit-all chain when Spring Security is active; check for a custom higher-priority chain.         |
| `localhost redirected you too many times` | BootUI serves the console at both `/bootui` and `/bootui/` with no redirect, so a host trailing-slash–stripping filter or proxy (e.g. Spring's `UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest()`, a standard Boot 4 idiom) can't loop on it. If you still hit this on an older BootUI, upgrade or open `/bootui/` (with the trailing slash) directly. |
| A panel is empty             | Enable the relevant Actuator endpoint or optional Spring module; BootUI degrades to stable empty DTOs when data is unavailable.         |
| Startup Timeline is empty    | Leave `bootui.startup.enabled=true` and `bootui.startup.capacity` greater than zero, or provide your own `BufferingApplicationStartup`. |
| Secrets are hidden           | Default exposure is `MASKED`; use `METADATA_ONLY` to hide all values or `FULL` only in trusted local sessions.                          |
| Static resources disabled    | `spring.web.resources.add-mappings=false` is bypassed by BootUI: it registers its own `/bootui/**` handler for its Web-based dashboard assets and logs a WARN line; the host's other static resources stay disabled. |
