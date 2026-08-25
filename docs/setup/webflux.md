# BootUI on Spring WebFlux

BootUI also ships a **reactive starter** for Spring Boot WebFlux (Netty / `DispatcherHandler`) applications. It serves
the same Vue console and JSON contract as the servlet starter above (`/bootui` and `/bootui/api/**` by default), backed by
the same framework-neutral BootUI engine — only the request/response binding differs underneath.

## Prerequisites

- Java 17 or later
- Spring Boot 4.x application configured for WebFlux (`spring-boot-starter-webflux`, not `spring-boot-starter-web`)
- Maven or Gradle (or their local wrappers)

## Add the reactive starter dependency

Use `bootui-spring-boot-starter-reactive` instead of `bootui-spring-boot-starter`. It depends on
`spring-boot-starter-webflux` (not `-web`), so it will not pull in Tomcat or force a servlet `WebApplicationType`. The
same activation rule applies: BootUI ships dormant and only wakes up in local development.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter-reactive</artifactId>
  <version>1.14.1</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter-reactive:1.14.1'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter-reactive:1.14.1")
```

:::

Do not add both `bootui-spring-boot-starter` and `bootui-spring-boot-starter-reactive` to the same application — Spring
Boot picks one `WebApplicationType` (servlet or reactive), so only the matching autoconfiguration ever activates.

## Run your app in development mode

Same as the servlet starter — start with the `dev` profile active (or rely on `spring-boot-devtools` /
`bootui.enabled=ON`):

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

::: tip Profile activation, not just a default
`BootUiActivationCondition` checks the *active* profiles, not `spring.profiles.default`. If your
`application.properties` only sets a default profile (`spring.profiles.default=dev`), a bare `java -jar` launch with
no explicit profile leaves BootUI disabled (404 on `/bootui`) even though `spring-boot:run`/your IDE's run
configuration may set an active profile for you. Pass `--spring.profiles.active=dev` (or `SPRING_PROFILES_ACTIVE=dev`)
explicitly when running a packaged jar by hand. This applies identically to the servlet starter.
:::

## Open BootUI

Nice job! BootUI is now configured 🚀

Visit: <http://localhost:8080/bootui>

## Activation and safety on Spring WebFlux

Activation uses the exact same `BootUiActivationCondition` as the servlet starter (`bootui.enabled=ON|OFF`,
`bootui.enabled-profiles` / `bootui.disabled-profiles`, or `spring-boot-devtools` on the classpath) — there is no
separate reactive-specific flag. The request-time safety model is **identical to the servlet starter and to Quarkus**:
the same shared `LocalhostGuard` (loopback-source trust, a `Host` allow-list as a DNS-rebinding defense, and
cross-site-write / CSRF protection), ported to a `WebFilter` instead of a servlet `Filter`. The same configuration keys
apply:

```properties
bootui.allow-non-localhost=false        # default: reject non-loopback callers
bootui.allowed-hosts=localhost          # extra Host header values to accept
bootui.trusted-proxies=172.16.0.0/12    # extra source ranges (e.g. a Docker gateway)
bootui.trust-container-gateway=AUTO     # auto-trust the container gateway in dev containers
```

The [Running inside a Docker container](environments.md#running-inside-a-docker-container) guidance applies unchanged. Per-panel
`bootui.panels.*` enable / read-only toggles and the `bootui.read-only` master switch are enforced identically as well.
Accepted BootUI API requests also cross a centralized bounded-elastic execution boundary after those checks, keeping
blocking scans, diagnostics, downloads, filesystem operations, and bounded network calls off Reactor Netty event-loop
threads. This applies automatically at custom `bootui.api-path` mounts and does not affect application routes.

## Which panels are available on Spring WebFlux

The large majority of BootUI's panels are live on the reactive adapter, including every advisor scan,
plus Flyway/Liquibase, Database Connection Pools, Cache, SQL Trace, Log Tail, Security Logs, Exceptions, and Live
Activity (over a rebuilt reactive streaming/capture layer). The raw **Spring Security** panel and the 26-rule
**Security advisor** are live whenever the application contributes a `SecurityWebFilterChain`; the raw panel's
path/method-only explanations are clearly marked as best effort. The **REST Client** panel is live after the application
builds a `WebClient` from Spring Boot's auto-configured `WebClient.Builder`; it provides the same report and actions as the
servlet panel over a reactive SSE stream.
The following panel is not available:

- **HTTP Sessions** — not applicable: it is the servlet container's `HttpSession` API, with no reactive equivalent.

For the authoritative, per-panel detail and the reasoning behind each gap, see [Features](../features/README.md) and
[Framework support](../FRAMEWORK-SUPPORT.md).
