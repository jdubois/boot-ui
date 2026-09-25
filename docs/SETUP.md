# Setup

This page installs BootUI in a Spring Boot servlet application. On WebFlux, follow
[BootUI on Spring WebFlux](setup/webflux.md). On Quarkus, follow [BootUI on Quarkus](setup/quarkus.md). All three serve
the same console and the same JSON contract.

## Prerequisites

- Java 17 or later
- Spring Boot 4.x application
- Maven or Gradle (or their local wrappers)

## Add the starter dependency

Adding the starter is the whole install. BootUI ships dormant: it activates in the `dev` and `local` profiles, or when
`spring-boot-devtools` is on the classpath, and stays off in `prod` and `production`.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>1.19.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.19.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.19.0")
```

:::

To keep the starter jar out of your production artifact, declare it in a dev-only build profile instead. See
[Activation and safety](setup/activation.md#scope-bootui-to-a-dev-only-profile).

## Run your app in development mode

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

BootUI also activates when `spring-boot-devtools` is on the classpath. To decide explicitly instead of relying on the
`AUTO` default, set `bootui.enabled`:

```properties
bootui.enabled=ON
```

The `prod` and `production` profiles disable BootUI unless `bootui.enabled=ON` is set. An unrecognized value fails
closed and leaves BootUI disabled.

::: tip YAML parses `ON` as a boolean
In `application.yml`, `ON`, `OFF`, `yes`, `no`, `true`, and `false` are booleans, so `bootui.enabled: ON` arrives as
`true`. BootUI treats `ON`, `true`, and `yes` as enabled and `OFF`, `false`, and `no` as disabled, so
`bootui.enabled: ON` works unquoted.
:::

## Open the console

BootUI is now available at <http://localhost:8080/bootui>.

### Use a custom path

To move the console, set `bootui.path`:

```properties
bootui.path=/dev-console
```

The UI is then served at `/dev-console`, and the API, streams, downloads, writes, and MCP endpoint move to
`/dev-console/api/**`. The old `/bootui` mount returns 404; it is not kept as an alias.

To serve the API from a separate mount, set `bootui.api-path` as well:

```properties
bootui.path=/dev-console
bootui.api-path=/internal/bootui-api
```

Both paths are application-relative. A servlet context path, a WebFlux base path, or a Quarkus HTTP root path is
composed automatically, so `server.servlet.context-path=/host` with `bootui.path=/dev-console` produces
`/host/dev-console`. The UI receives the browser-visible paths at runtime, so no frontend rebuild is needed.

::: details Path validation rules
A path must be absolute and may contain only letters, digits, `-`, `_`, `.`, `~`, and `/`. Trailing slashes are
stripped first, so `/dev-console/` is accepted. BootUI then fails startup for `/`, blank values, `.` and `..` segments,
query and fragment components, encoded separators, consecutive interior slashes, and routing patterns. A custom
`bootui.path` may not live under the reserved `/bootui/**` namespace. See the [property reference](PROPERTIES.md#custom-ui-and-api-paths).
:::

## What to read next

Everything below is optional.

| If you want to                                                     | Read                                                             |
| ------------------------------------------------------------------ | ---------------------------------------------------------------- |
| Understand when BootUI turns on, or keep it out of your prod build | [Activation and safety](setup/activation.md)                     |
| Run BootUI on WebFlux or Quarkus                                   | [Spring WebFlux](setup/webflux.md) · [Quarkus](setup/quarkus.md) |
| Run inside Docker, or in a command-line app                        | [Non-standard runtimes](setup/environments.md)                   |
| Fix something that is not working                                  | [Troubleshooting](setup/troubleshooting.md)                      |
| Look up a property                                                 | [Property reference](PROPERTIES.md)                              |
| See what each panel does                                           | [Features](features/README.md)                                   |

::: tip Using the MySQL panel
The [MySQL panel](features/database.md#mysql) reuses a configured application JDBC datasource and MySQL Connector/J. It
creates no monitoring pool, requires no Hibernate, and starts no database or workload. Keep credentials in the
application's existing secure configuration. Oracle MySQL 8.4 LTS is the tested server line; MariaDB and R2DBC-only
access are not covered.
:::
