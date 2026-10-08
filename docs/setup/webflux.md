# BootUI on Spring WebFlux

BootUI's one Spring Boot starter serves Spring WebFlux applications too. It serves the same console and the same JSON
contract as on Spring MVC, backed by the same framework-neutral engine. Only the request binding differs.

## Prerequisites

- Java 17 or later
- A Spring Boot 4.x application on `spring-boot-starter-webflux`, not `spring-boot-starter-web`
- Maven or Gradle (or their local wrappers)

## Add the starter dependency

Add `bootui-spring-boot-starter`, the same starter a Spring MVC application uses. It brings no web stack: your
application's own `spring-boot-starter-webflux` keeps it a reactive application on Netty, and BootUI binds to WebFlux.
The starter's build fails if Tomcat, the Servlet API, or Spring MVC ever reaches its dependencies, so it cannot turn a
WebFlux application into a servlet one.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>1.21.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.21.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.21.0")
```

:::

::: tip Upgrading from BootUI 1.x
Replace `bootui-spring-boot-starter-reactive` with `bootui-spring-boot-starter`; nothing else changes. Keep
`spring-boot-starter-webflux`, which your application already declares. If it also declares `spring-boot-starter-web`
(for `RestClient`, say), Spring Boot picks Spring MVC, as it would without BootUI.
:::

## Run your app in development mode

Start the application with the `dev` profile active, exactly as on Spring MVC:

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
Spring MVC applications too.
:::

## Activation and safety

Activation uses the same condition as on Spring MVC, with no reactive-specific flag: `bootui.enabled`,
`bootui.enabled-profiles` and `bootui.disabled-profiles`, or `spring-boot-devtools` on the classpath.

The request-time safety model is identical to Spring MVC and to Quarkus. The same `LocalhostGuard` applies
loopback-source trust, a `Host` allow-list against DNS rebinding, and cross-site-write protection, ported to a
`WebFilter` rather than a servlet `Filter`. The same keys apply:

```properties
# Default: reject non-loopback callers.
bootui.allow-non-localhost=false
# Extra Host header values to accept.
bootui.allowed-hosts=localhost
# Extra source ranges, for example a Docker gateway.
bootui.trusted-proxies=172.16.0.0/12
# Auto-trust the container gateway in dev containers.
bootui.trust-container-gateway=AUTO
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
