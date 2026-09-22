# BootUI on Quarkus

BootUI ships as a Quarkus extension. It serves the same console and the same JSON contract as the Spring starters,
backed by the Quarkus build of the framework-neutral engine.

## Prerequisites

- Java 17 or later
- A Quarkus application, built and tested against the platform version pinned by the root `pom.xml`
  (`quarkus.platform.version`, currently the `3.33.3.2` LTS release)
- Maven or Gradle (or their local wrappers)

## Add the extension

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-quarkus</artifactId>
  <version>1.18.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
implementation 'com.julien-dubois.bootui:bootui-quarkus:1.18.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
implementation("com.julien-dubois.bootui:bootui-quarkus:1.18.0")
```

:::

Declare only `bootui-quarkus`. The Quarkus extension mechanism resolves the matching `bootui-quarkus-deployment`
artifact for you.

## Run your app in development mode

Start Quarkus in dev mode. BootUI activates automatically, with no profile or flag to set:

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

BootUI is then available at <http://localhost:8080/bootui>.

The `bootui.path` and `bootui.api-path` settings described in [Use a custom path](../SETUP.md#use-a-custom-path) work
in dev and test mode and compose with `quarkus.http.root-path`.

## Activation and safety

The Quarkus launch mode decides activation. There is no Spring-style profile and no `bootui.enabled` flag:

| Launch mode | Behavior |
| ----------- | -------- |
| `dev` (`quarkus:dev`) and `test` (`@QuarkusTest`) | The console, its `/bootui/api/**` endpoints, the CDI beans, and the safety filter are wired. |
| `NORMAL` (a packaged `quarkus-run.jar` or a native image) | Nothing is wired. A build-time guard answers a plain 404 for `/bootui` and every `/bootui/**` path, including the packaged UI shell. |

This is fail-closed by design: no flag turns BootUI on in a production build.

The request-time safety model matches Spring Boot. The shared `LocalhostGuard` applies loopback-source trust, a `Host`
allow-list against DNS rebinding, and cross-site-write protection. Non-loopback API callers must also present the
BootUI bearer token. The same keys apply, read from MicroProfile `Config`:

```properties
bootui.allow-non-localhost=false        # default: reject non-loopback callers
bootui.allowed-hosts=localhost          # extra Host header values to accept
bootui.trusted-proxies=172.16.0.0/12    # extra source ranges (for example, a Docker gateway)
bootui.trust-container-gateway=AUTO     # auto-trust the container gateway in dev containers
# bootui.authentication.token=...       # optional stable token; otherwise generated at startup
```

The [Docker container guidance](environments.md#running-inside-a-docker-container) applies to Quarkus too, since dev
mode is already active there. Per-panel `bootui.panels.*` toggles and the `bootui.read-only` master switch behave
identically on both frameworks.

Runtime configuration overrides are Spring-only today, so the Configuration panel is read-only on Quarkus.

## Panel availability

Most panels are live. Nine target Spring-specific runtime concepts and are permanently marked *not applicable*, and
JMS is not available yet. See [what is not on Quarkus](../FRAMEWORK-SUPPORT.md#what-is-not-on-quarkus) for the list and
the reason for each. To try a fully wired application, see
[Try the sample app](../TRY-SAMPLE-APP.md#quarkus-image).

The [MySQL panel](../features/database.md#mysql) needs an existing default or named JDBC datasource with the
`io.quarkus:quarkus-jdbc-mysql` extension and `quarkus.datasource.db-kind=mysql`. A reactive MySQL client alone is not
enough, and BootUI creates no monitoring datasource of its own.
