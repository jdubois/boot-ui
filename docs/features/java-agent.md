# Java Agent

The Java Agent panel explains whether the optional BootUI `-javaagent` is attached to the current JVM, whether this
application has claimed it, and how to attach it when it is missing. The panel is in **Developer tools**, immediately
after **Command Line**, and is view-only on Spring MVC, Spring WebFlux, and Quarkus.

The agent is a development-time helper for BootUI's later executor propagation and runtime instrumentation work. It is
local-only, exports nothing, and stays dormant until BootUI claims it. A Spring or Quarkus application that starts
without `-javaagent` behaves exactly as before and reports `NOT_ATTACHED` with setup snippets.

## Attaching the agent

Add the published `bootui-agent` jar to the JVM with an explicit `-javaagent:` option. When the agent is already
attached, BootUI reports the jar path from the agent itself. Otherwise the setup card points at the expected local Maven
repository path:

```text
~/.m2/repository/com/julien-dubois/bootui/bootui-agent/<version>/bootui-agent-<version>.jar
```

If the application was started with `-Dmaven.repo.local=...`, that repository is honored. When the jar is not present,
the first tab is **Download the agent**:

```bash
mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:<version>
```

The panel detects `pom.xml`, `build.gradle`, or `build.gradle.kts` in the working directory and orders the matching
snippets first. Available snippets include:

- Spring Boot Maven plugin `<agents>` configuration.
- Gradle Kotlin and Groovy `bootRun { jvmArgs(...) }` examples for Spring.
- Quarkus dev mode: `./mvnw quarkus:dev -Djvm.args="-javaagent:..."`.
- Surefire/Failsafe `<argLine>@{argLine} -javaagent:...</argLine>`, which preserves JaCoCo's own `argLine`.
- IntelliJ IDEA VM options.
- `JAVA_TOOL_OPTIONS` scoped to one run command, such as `JAVA_TOOL_OPTIONS="-javaagent:..." ./mvnw spring-boot:run`.
  Never export it in a shell: every JVM started there (Maven, the Gradle daemon, IDE tooling) would load the agent and
  print HotSpot's class-data-sharing warning.

Every snippet tab has a **Copy** button. Snippet ids and labels are stable: `maven-download` (**Download the agent**),
`maven-plugin` (**Spring Boot Maven plugin**), `gradle-kotlin`, `gradle-groovy`, `quarkus-dev`, `surefire`,
`intellij`, and `java-tool-options`. The agent is not for production JVMs or AOT/native-image runs.

## Status states

`GET /bootui/api/java-agent`, `get_agent_status`, and `bootui agent status` return the same `JavaAgentReport` shown by
the panel. The report includes `state`, `reason`, `agentVersion`, `bootUiVersion`, `protocol`, `expectedProtocol`, `jdk`,
`loadMode`, `jarPath`, `startupMicros`, `claim`, `heldBy`, `sensors`, `retransformation`, `counters`, `messages`,
`warnings`, and `setup` (with `jarPath`, `jarFound`, `buildTool`, and `snippets`).

| State | Meaning |
| --- | --- |
| `NOT_ATTACHED` | The bridge is not on the bootstrap class path; the JVM started without `-javaagent`. |
| `DORMANT` | The bridge is present, but this application has not claimed it, for example because `bootui.agent.enabled=false`. |
| `ARMED` | This application owns an armed claim. |
| `HELD` | Another application in the same JVM owns the agent; `heldBy` names it. |
| `DISARMED` | This run ended its claim. |
| `UNAVAILABLE` | The bridge is present but the agent did not start, the protocol differs, or the runtime cannot use it. |
| `FAILED` | The agent rejected or failed this application's claim. |
| `DISABLED` | Agent support is disabled, including Quarkus production mode. |

A different agent version on the same protocol is shown as a warning. Protocol mismatches are unavailable rather than
best-effort.

## Claims and lifecycle

Spring claims the agent from `BootUiAgentClaimEnvironmentPostProcessor`, registered in `META-INF/spring.factories`, as
soon as BootUI's activation is resolved, with the main application class's package and `bootui.agent.packages`. The claim
is refined with the auto-configuration packages when the application context refreshes, disarmed on close or startup
failure, and released when BootUI is inactive or `bootui.agent.enabled=false`, so the agent removes its transformers.

Quarkus claims from a `STATIC_INIT` recorder in dev and test launch modes, refines on startup, and disarms on shutdown.
Production launch mode never claims the agent and reports `DISABLED` with reason `Quarkus production mode`.

`bootui.agent.mode=auto` resolves to `test` under JUnit, TestNG, Cucumber, Spring Boot test, or Quarkus test launch
mode; otherwise it resolves to `dev`. A dev application can take over from a test application, and DevTools restarts or
Quarkus live reloads replace the same application slot.

The engine looks up the bridge only from the bootstrap class loader, so an accidental application-classpath copy is
ignored.

## Sensors and current limits

M5-1 ships the foundation and status surface. The sensors table is intentionally empty today and says:

> No sensors yet: executor propagation arrives with the next agent release

The retransformation, counters, messages, warnings, claim, and setup sections are still useful for validating that the
agent is attached and claimed before those sensors arrive.

## Coexistence and class data sharing

The BootUI agent coexists with the OpenTelemetry Java agent and with JaCoCo. Put JaCoCo's Surefire/Failsafe placeholder
first and append BootUI with `@{argLine} -javaagent:...` so coverage keeps working.

Because the agent appends itself to the bootstrap class path, HotSpot prints:

```text
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

That warning is expected. It means CDS, AppCDS, and AOT caches no longer apply outside boot-loader classes for that JVM.
This is why the agent is a development-time tool and should not be placed on production or AOT-cached JVM launches.

## Privacy and dependency inventory

The agent is local-only. It does not export telemetry, open a network connection, or record anything until a running
BootUI instance claims it. The dependency catalog and Vulnerabilities panel ignore the BootUI agent jar as an
application library when its manifest contains `BootUI-Agent-Protocol`.

## Configuration

See [BootUI properties](../PROPERTIES.md#java-agent) for:

| Property | Default | Purpose |
| --- | --- | --- |
| `bootui.agent.enabled` | `true` | Claim the agent when it is attached. |
| `bootui.agent.packages` | empty | Extra application package prefixes; the adapter-discovered packages are always included. |
| `bootui.agent.mode` | `auto` | `auto`, `dev`, or `test`. |

On Quarkus these are build-time properties, read when the application is built (augmented); dev mode rebuilds when
they change.
