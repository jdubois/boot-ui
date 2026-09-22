# BootUI on Spring WebFlux

BootUI ships a reactive starter for Spring Boot WebFlux applications. It serves the same console and the same JSON
contract as the servlet starter, backed by the same framework-neutral engine. Only the request binding differs.

## Prerequisites

- Java 17 or later
- A Spring Boot 4.x application on `spring-boot-starter-webflux`, not `spring-boot-starter-web`
- Maven or Gradle (or their local wrappers)

## Add the reactive starter dependency

Use `bootui-spring-boot-starter-reactive` instead of `bootui-spring-boot-starter`. It depends on
`spring-boot-starter-webflux`, so it pulls in neither Tomcat nor a servlet `WebApplicationType`.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter-reactive</artifactId>
  <version>1.18.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter-reactive:1.18.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter-reactive:1.18.0")
```

:::

Do not declare both starters in the same application. Spring Boot picks one `WebApplicationType`, so only the matching
autoconfiguration ever activates.

## Run your app in development mode

Start the application with the `dev` profile active, exactly as on the servlet starter:

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

BootUI is then available at <http://localhost:8080/bootui>.

::: tip Activation reads active profiles, not the default profile
If `application.properties` only sets `spring.profiles.default=dev`, a bare `java -jar` launch leaves BootUI disabled
and `/bootui` returns 404, even though `spring-boot:run` or your IDE may set an active profile for you. Pass
`--spring.profiles.active=dev` or `SPRING_PROFILES_ACTIVE=dev` explicitly when you run a packaged jar. This applies to
the servlet starter too.
:::

## Activation and safety

Activation uses the same condition as the servlet starter, with no reactive-specific flag: `bootui.enabled`,
`bootui.enabled-profiles` and `bootui.disabled-profiles`, or `spring-boot-devtools` on the classpath.

The request-time safety model is identical to the servlet starter and to Quarkus. The same `LocalhostGuard` applies
loopback-source trust, a `Host` allow-list against DNS rebinding, and cross-site-write protection, ported to a
`WebFilter` rather than a servlet `Filter`. The same keys apply:

```properties
bootui.allow-non-localhost=false        # default: reject non-loopback callers
bootui.allowed-hosts=localhost          # extra Host header values to accept
bootui.trusted-proxies=172.16.0.0/12    # extra source ranges (for example, a Docker gateway)
bootui.trust-container-gateway=AUTO     # auto-trust the container gateway in dev containers
```

The [Docker container guidance](environments.md#running-inside-a-docker-container) applies unchanged, as do the
per-panel `bootui.panels.*` toggles and the `bootui.read-only` master switch.

Accepted API requests then cross a bounded-elastic execution boundary, which keeps blocking scans, diagnostics,
downloads, filesystem operations, and bounded network calls off the Reactor Netty event-loop threads. This applies
automatically at a custom `bootui.api-path` and does not affect your application's routes.

## Panel availability

Every panel works except **HTTP Sessions**, which inventories servlet sessions and has no reactive equivalent. That
includes every advisor scan and every action. See [Framework support](../FRAMEWORK-SUPPORT.md#spring-webflux).

The [MySQL panel](../features/database.md#mysql) needs a default or named JDBC `DataSource` and MySQL Connector/J. An
R2DBC `ConnectionFactory` alone is not enough, and BootUI adds no second pool to compensate.
