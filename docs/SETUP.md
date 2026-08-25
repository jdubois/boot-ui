# Setup

BootUI runs on **Spring Boot 4** (servlet or WebFlux) and **Quarkus**, serving the same console and the same
JSON contract (at `/bootui/api/**` by default) from a shared, framework-neutral engine.

The four steps below are the whole servlet install. If you are on **WebFlux**, follow
[BootUI on Spring WebFlux](setup/webflux.md) instead; on **Quarkus**, follow [BootUI on Quarkus](setup/quarkus.md).

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
  <version>1.14.1</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.14.1'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.14.1")
```

:::

Prefer to keep the starter jar out of your production artifact entirely? Use the
[dev-only profile setup](setup/activation.md#scope-bootui-to-a-dev-only-profile) instead.

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

### Use a custom path

`/bootui` is the backward-compatible default. To move the whole console, set:

```properties
bootui.path=/dev-console
```

The shell/assets are then served at `/dev-console`, and the API, streams, downloads, writes, and MCP endpoint move to
`/dev-console/api/**`. If the API must use a separate mount:

```properties
bootui.path=/dev-console
bootui.api-path=/internal/bootui-api
```

These are application-relative paths. A servlet context path, WebFlux base path, or Quarkus HTTP root path is composed
automatically; for example, `server.servlet.context-path=/host` plus `bootui.path=/dev-console` produces
`/host/dev-console`. The shell publishes the browser-visible paths to the SPA, so no frontend rebuild is needed.

Paths may use only absolute RFC 3986 unreserved segments. Trailing slashes are removed. Root, empty, encoded,
query/fragment, duplicate interior separator, routing-pattern, and `.` / `..` segment values fail startup. Custom UI
paths below `/bootui/**` are reserved and rejected. When a custom path is active, the old `/bootui` mount returns 404;
it is not a compatibility alias. See the [property reference](PROPERTIES.md#custom-ui-and-api-paths) for the complete
contract.

## Next steps

You are done — everything below is optional, and only when you need it.

| If you want to                                                     | Read                                                             |
| ------------------------------------------------------------------ | ---------------------------------------------------------------- |
| Understand when BootUI turns on, or keep it out of your prod build | [Activation and safety](setup/activation.md)                     |
| Run BootUI on WebFlux or Quarkus                                   | [Spring WebFlux](setup/webflux.md) · [Quarkus](setup/quarkus.md) |
| Run inside Docker, or in a command-line app                        | [Non-standard runtimes](setup/environments.md)                   |
| Fix something that is not working                                  | [Troubleshooting](setup/troubleshooting.md)                      |
| Look up a property                                                 | [Property reference](PROPERTIES.md)                              |
| See what each panel does                                           | [Features](features/README.md)                                   |
