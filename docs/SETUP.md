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
  <version>1.1.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.1.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.1.0")
```

:::

Prefer to keep the starter jar out of your production artifact entirely? Use the
[dev-only profile setup](#advanced-scope-bootui-to-a-dev-only-profile) instead.

## 3) Run your app in development mode

Start the application with the `dev` profile active so BootUI turns on:

:::: code-tabs#build

@tab Maven

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

@tab Gradle

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

::::

BootUI also activates automatically when `spring-boot-devtools` is on the classpath. To force it on or off:

```properties
bootui.enabled=AUTO
bootui.enabled=ON
bootui.enabled=OFF
```

`prod` and `production` profiles disable BootUI unless `bootui.enabled=ON` is set. Invalid `bootui.enabled` values fail
closed and keep BootUI disabled.

### Command-line (non-web) applications

BootUI also works in non-web applications, such as command-line apps. The starter brings Spring MVC and an embedded
servlet container, so when BootUI is active it automatically starts a servlet web server even if your application is
configured as non-web (`spring.main.web-application-type=none` or `SpringApplication#setWebApplicationType(NONE)`). Your
`CommandLineRunner` / `ApplicationRunner` beans still run as usual; the application simply keeps running so the console
stays reachable.

Because BootUI only activates in development contexts by default, this never affects production. Applications that are
already servlet web apps, or that are explicitly configured as reactive, are left untouched. To opt out and keep your
application's web-application type exactly as declared, set `bootui.force-web=false`.

## 4) Open BootUI

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
        <version>1.1.0</version>
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
        runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.1.0'
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
        "runtimeOnly"("com.julien-dubois.bootui:bootui-spring-boot-starter:1.1.0")
    }
    tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
        systemProperty("spring.profiles.active", "dev")
    }
}
```

:::

Then activate the profile when you start the app. This both adds the BootUI starter and turns on the `dev`
Spring Boot profile:

:::: code-tabs#build

@tab Maven

```bash
./mvnw spring-boot:run -Pdev
```

@tab Gradle

```bash
./gradlew bootRun -Pdev
```

::::

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

## Troubleshooting

| Symptom                      | Check                                                                                                                                   |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `/bootui` returns 404        | Use the `dev` or `local` profile, add DevTools, or set `bootui.enabled=ON`.                                                             |
| BootUI is disabled in `prod` | This is intentional; only `bootui.enabled=ON` can force activation with a disabled profile.                                             |
| Command-line app now stays up | Expected: BootUI starts a servlet server so the console is reachable. Set `bootui.force-web=false` to keep the app non-web.              |
| Browser is rejected          | BootUI accepts loopback callers by default. Use `bootui.allow-non-localhost=true` only for a trusted local network.                     |
| Spring Security blocks UI    | BootUI auto-registers a `/bootui/**` permit-all chain when Spring Security is active; check for a custom higher-priority chain.         |
| A panel is empty             | Enable the relevant Actuator endpoint or optional Spring module; BootUI degrades to stable empty DTOs when data is unavailable.         |
| Startup Timeline is empty    | Leave `bootui.startup.enabled=true` and `bootui.startup.capacity` greater than zero, or provide your own `BufferingApplicationStartup`. |
| Secrets are hidden           | Default exposure is `MASKED`; use `METADATA_ONLY` to hide all values or `FULL` only in trusted local sessions.                          |
| Static resources disabled    | `spring.web.resources.add-mappings=false` is bypassed by BootUI: it registers its own `/bootui/**` handler for its Web-based dashboard assets and logs a WARN line; the host's other static resources stay disabled. |
