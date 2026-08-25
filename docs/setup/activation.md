# Activation and safety

BootUI ships dormant and stays that way outside local development. This page covers how that default works, how to
tighten it, and how to keep the starter out of a production build entirely.

## Safety defaults

BootUI is intended for local development only. By default it:

- Activates in `AUTO` mode only for the `dev` / `local` profiles or DevTools.
- Rejects non-loopback requests.
- Requires bearer-token authentication for non-loopback API requests whenever remote access is explicitly enabled;
  localhost remains authentication-free.
- Applies one cross-framework security-header policy to the configured BootUI surface, with no-store caching for APIs,
  streams, and downloads and immutable caching only for successfully served content-hashed assets.
- Permits the configured UI/API paths through Spring Security when Spring Security is present, with a startup warning, so the local
  console remains directly reachable while the loopback-only filter still applies.
- Masks secret-like configuration values.
- Exposes the local Actuator endpoints used by BootUI panels when BootUI is active.
- Captures local application spans for the Traces panel when telemetry and the panel are enabled.
- Disables itself for `prod` / `production` profiles.
- Stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files.

Every visible panel can be disabled with `bootui.panels.<panel-id>.enabled=false`. Panels with mutating browser actions
can also be made read-only with `bootui.panels.<panel-id>.read-only=true`, and `bootui.read-only=true` makes the whole
BootUI application read-only. See the [property reference](../PROPERTIES.md) for the full panel list.

## Scope BootUI to a dev-only profile

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
        <version>1.14.1</version>
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
        runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.14.1'
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
        "runtimeOnly"("com.julien-dubois.bootui:bootui-spring-boot-starter:1.14.1")
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

## Runtime overrides

The Configuration panel can create, update, and delete local runtime overrides. Overrides are stored in
`.bootui/application-bootui.properties` by default, loaded at high precedence on the next startup, and never modify your
application source configuration. Already-bound `@ConfigurationProperties` beans may keep their previous value until the
app restarts; BootUI returns that warning with every override mutation.
