# BootUI on Quarkus

BootUI also ships as a **Quarkus extension**. It serves the same Vue console and JSON contract as Spring
(`/bootui` and `/bootui/api/**` by default), backed by the Quarkus build of the framework-neutral
BootUI engine.

## Prerequisites

- Java 17 or later
- A Quarkus application (built and tested against the version pinned by the root `pom.xml`'s
  `quarkus.platform.version` property; currently the `3.33.3.1` LTS release)
- Maven or Gradle (or their local wrappers)

## Add the extension

Add the BootUI Quarkus extension to your build — nothing else is required. BootUI wires itself up only in Quarkus'
**dev** and **test** launch modes and stays completely dark in production, so it is safe to leave on the classpath.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-quarkus</artifactId>
  <version>1.14.1</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
implementation 'com.julien-dubois.bootui:bootui-quarkus:1.14.1'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
implementation("com.julien-dubois.bootui:bootui-quarkus:1.14.1")
```

:::

You only declare `bootui-quarkus`; the matching `bootui-quarkus-deployment` artifact is resolved automatically by the
Quarkus extension mechanism.

## Run your app in development mode

Start Quarkus in dev mode. BootUI activates automatically — there is no profile or flag to set:

::: tabs#build

@tab Maven

```bash
./mvnw quarkus:dev
```

@tab Gradle

```bash
./gradlew quarkusDev
```

:::

## Open BootUI

Nice job! BootUI is now configured 🚀

Visit: <http://localhost:8080/bootui>

The same `bootui.path` / `bootui.api-path` settings shown in
[Use a custom path](../SETUP.md#use-a-custom-path) work in Quarkus dev/test mode and compose with `quarkus.http.root-path`.

## Activation and safety on Quarkus

Activation is governed entirely by the **Quarkus launch mode**, not by a Spring-style profile or a `bootui.enabled`
flag:

- **`dev` (`quarkus:dev`) and `test` (`@QuarkusTest`)** — the console, its `/bootui/api/**` endpoints, the CDI beans,
  and the safety filter are all wired up.
- **Production (`NORMAL` launch mode — a packaged `quarkus-run.jar` or a native image)** — BootUI is **not wired at
  all**. The API, beans, and safety filter are absent, so the console has no data to serve. This is fail-closed by
  design: there is no flag that turns BootUI on in a production build.

The request-time safety model is **identical to Spring Boot**: BootUI is loopback-only by default and shares the same
`LocalhostGuard` (loopback-source trust, a `Host` allow-list as a DNS-rebinding defense, and cross-site-write / CSRF
protection). Non-loopback API callers must additionally authenticate with the BootUI bearer token. The same opt-in keys
apply, read live from MicroProfile `Config`:

```properties
bootui.allow-non-localhost=false        # default: reject non-loopback callers
bootui.allowed-hosts=localhost          # extra Host header values to accept
bootui.trusted-proxies=172.16.0.0/12    # extra source ranges (e.g. a Docker gateway)
bootui.trust-container-gateway=AUTO     # auto-trust the container gateway in dev containers
# bootui.authentication.token=...       # optional stable token; otherwise generated at startup
```

The [Running inside a Docker container](environments.md#running-inside-a-docker-container) guidance applies to Quarkus too —
use the same keys (only the Spring-specific activation note differs; on Quarkus, dev mode is already active).

A few capabilities are **Spring-only today**: runtime configuration overrides (the Configuration panel is read-only
on Quarkus — there is no write path yet). Per-panel `bootui.panels.*` enable / read-only toggles and the
`bootui.read-only` master switch are enforced identically on both frameworks. Everything else behaves the same across
both frameworks.

## Which panels are available on Quarkus

Most of BootUI's panels are live on Quarkus. A handful target Spring-specific runtime concepts and are clearly marked
*not applicable* on Quarkus — for example GraalVM and CRaC readiness, Conditions, Startup Timeline, HTTP Sessions,
Spring Data, Spring Security, and DevTools. (Quarkus builds native images and generates reachability metadata itself,
and the others have no Quarkus equivalent.)

For the authoritative, per-panel availability, see [Features](../features/README.md) and
[Framework support](../FRAMEWORK-SUPPORT.md). To try a fully wired Quarkus app, see
[Try the sample app](../TRY-SAMPLE-APP.md#bootui-on-quarkus).
