# Activation and safety

BootUI ships dormant and stays that way outside local development. This page describes that default, how to tighten
it, and how to keep the starter out of a production build.

## Safety defaults

BootUI is a local development tool. By default, it does the following:

- Activates in `AUTO` mode only for the `dev` / `local` profiles or DevTools.
- Rejects non-loopback requests.
- Requires bearer-token authentication for API requests from untrusted sources whenever remote access is explicitly
  enabled. Loopback callers, and any source you have explicitly trusted through `bootui.trusted-proxies` or
  `bootui.trust-container-gateway`, are treated as trusted and stay authentication-free.
- Applies one cross-framework security-header policy to the configured BootUI surface, with no-store caching for APIs,
  streams, and downloads and immutable caching only for successfully served content-hashed assets.
- Permits the configured UI/API paths through Spring Security when Spring Security is present, with a startup warning, so the local
  console remains directly reachable while the loopback-only filter still applies.
- Masks secret-like configuration values.
- Contributes local Actuator defaults on Spring, guarding only the exposure and health details BootUI adds.
- Captures local application spans for the Traces panel when telemetry and the panel are enabled.
- Disables itself for `prod` / `production` profiles.
- Stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files.

You can disable any panel with `bootui.panels.<panel-id>.enabled=false`. Panels with mutating browser actions also
accept `bootui.panels.<panel-id>.read-only=true`, and `bootui.read-only=true` makes all of BootUI read-only. The
[property reference](../PROPERTIES.md) lists every panel.

On Spring, BootUI-added raw Actuator routes retain source/Host/write protection on the application or separate management
listener, including mapped endpoints and health descendants/aliases. Host-owned management exposure/authentication is
unchanged. Even with `bootui.allow-non-localhost=true`, raw BootUI-added Actuator requires a trusted source; remote
token users use the in-process panels. Set `bootui.actuator.expose-defaults=false` to leave all exposure/detail defaults
to the host. This does not remove endpoint beans; panel availability follows the native endpoint configuration.

## Scope BootUI to a dev-only profile

The install in [Setup](../SETUP.md) leaves the starter jar on the classpath in every build, where it stays disabled
outside development. To keep it out of your production build entirely, declare it in a dedicated `dev` build profile.
The same profile can activate the `dev` Spring Boot profile, so one flag both adds the starter and turns BootUI on.

::: tabs#build

@tab Maven

Declare the starter in a `dev` profile and tell the Spring Boot plugin to run with the `dev` Spring Boot profile:

```xml
<profiles>
  <profile>
    <id>dev</id>
    <dependencies>
      <dependency>
        <groupId>com.julien-dubois.bootui</groupId>
        <artifactId>bootui-spring-boot-starter</artifactId>
        <version>2.5.0</version>
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

Guard the starter behind the `-Pdev` project property and set the `dev` Spring Boot profile on `bootRun`:

```groovy
// Groovy DSL (build.gradle)
if (project.hasProperty('dev')) {
    dependencies {
        runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:2.5.0'
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
        "runtimeOnly"("com.julien-dubois.bootui:bootui-spring-boot-starter:2.5.0")
    }
    tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
        systemProperty("spring.profiles.active", "dev")
    }
}
```

:::

Then activate the profile when you start the application:

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

The Configuration panel can create, update, and delete local runtime overrides. They are stored in
`.bootui/application-bootui.properties`, loaded at high precedence on the next startup, and never modify your source
configuration. Already-bound `@ConfigurationProperties` beans can keep their previous value until the application
restarts, and BootUI returns that warning with every override mutation.

On Spring these application overrides deliberately load **before configuration data, even when BootUI is inactive**.
Startup logs their path and key count at INFO while active, or WARN while inactive; values are never logged.
An unreadable or malformed file is ignored with a warning. Removing a late property source would not undo settings
already consumed during bootstrap, so inactive loading is not presented as an activation gate.

BootUI safety settings cannot be written through runtime overrides and existing persisted entries are ignored before
bootstrap: activation and profile lists, source/Host trust, authentication, global/panel read-only and panel enablement,
value exposure/masking, UI/API mounts and the overrides-file location. Relaxed-binding spellings are covered too.
Move such entries to host-owned configuration, system properties or environment variables.
The same restriction covers indirect controls: Spring profile activation/default/include/groups, ConfigData
name/location/additional-location/import selectors, and management HTTP exposure/access/mappings, health/value-display
and listener policy. Ordinary application settings such as datasource configuration and `server.port` still apply.

Writes replace the file with owner-only permissions (POSIX or owner ACL). A first write creates an adjacent
`.gitignore` for the overrides file and heap dumps, without ignoring the team's `boot-ui.yml` dismissal file.
Existing ignore rules are preserved; keep the overrides file ignored when maintaining your own `.gitignore`.

Set `bootui.overrides-file` to move that file. BootUI resolves the advisor dismissals file, `boot-ui.yml` (dismissed rules and findings), in
the same directory.

::: warning Set it from the environment
`application.properties` is loaded too late for the overrides file to be read at startup. Use `BOOTUI_OVERRIDES_FILE`
or a `-D` system property instead. In a container, point it at a mounted volume so both files survive an image
rebuild. See [persisting console state](environments.md#persisting-console-state-across-image-rebuilds).
:::
