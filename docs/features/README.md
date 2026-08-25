# Features

BootUI groups its panels exactly the way the application menu does. Pick a group to read its panels in detail, or use
the search box to jump straight to a panel by name.

| Group | Panels | What it answers |
| ----- | ------ | --------------- |
| [Overview](./overview.md) | Overview · Live Activity · GitHub | Is my app healthy, and what did it just do? |
| [Advisors](./advisors.md) | Architecture · REST API · Spring · Quarkus · Database · Hibernate · Memory · Security · Pentesting · Vulnerabilities | What is wrong with my app, and how do I fix it? |
| [Runtime](./runtime.md) | Health · HTTP Sessions · Metrics · Live Memory · JVM Tuning · Heap Dump · Threads · Startup Timeline · GraalVM · CRaC | How is the JVM behaving right now? |
| [Configuration](./configuration.md) | Configuration · Profile Diff · Loggers · Beans · Conditions · Mappings | What configuration and wiring is actually effective? |
| [Database](./database.md) | Connection Pools · SQL Trace · Hibernate Statistics · Transactions · Spring Data · Flyway · Liquibase | What is my app doing to the database? |
| [Security](./security.md) | Spring Security · Security Logs | How is access actually enforced? |
| [Services](./services.md) | Scheduled Tasks · REST Client · Fault Tolerance · WebSockets · AI Framework · Cache · Email · Kafka · RabbitMQ · JMS | What is my app talking to? |
| [Diagnostics](./diagnostics.md) | Traces · Log Tail · Exceptions · HTTP Exchanges · HTTP Probe | Why did that request fail? |
| [Developer tools](./developer-tools.md) | MCP Server · DevTools · Dev Services · Copilot · Claude Code | What is my toolchain doing locally? |

## Rules that apply to every panel

**Unavailable panels are visible, not hidden.** When a panel's backing infrastructure is missing, the sidebar moves it
into a collapsed *Disabled / unavailable* group, and opening it shows the reason at the top of the page.

**Every panel can be turned off.** Use `bootui.panels.<panel-id>.enabled=false`. Panels with browser-triggered actions
also support `bootui.panels.<panel-id>.read-only=true`, and `bootui.read-only=true` makes all of BootUI read-only. See
the [property reference](../PROPERTIES.md) for the complete list.

**Changing server state always asks first.** Restarting a dev service, capturing or deleting a heap dump, writing
GraalVM or CRaC artifacts, running a migration, clearing a cache or trace buffer, or destroying an HTTP session opens a
confirmation dialog naming the affected resource. Read-only scans and reversible toggles never prompt.

::: details How the confirmation dialog behaves
The dialog flags irreversible operations, defaults focus to Cancel, dismisses on Escape or a backdrop click, and honors
`prefers-reduced-motion`.
:::

**BootUI hides itself by default.** Beans, Conditions, Mappings, Loggers, Metrics, Startup Timeline, Scheduled Tasks,
Cache, Spring Security, Security Logs, and Traces exclude BootUI's own runtime data so they stay focused on the host
application. Set `bootui.monitoring.exclude-self=false` to include BootUI internals while debugging the console itself.

## Availability per stack

Spring Boot servlet is the reference stack. For the authoritative per-panel availability on the other two, see
[Framework support](../FRAMEWORK-SUPPORT.md).
