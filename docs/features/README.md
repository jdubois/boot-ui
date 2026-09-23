# Features

These groups match the console's own menu. Pick one to read its panels in detail, or use the search box to jump
straight to a panel by name.

| Group | Panels | What it answers |
| ----- | ------ | --------------- |
| [Overview](./overview.md) | Overview · Live Activity · GitHub | Is my app healthy, and what did it just do? |
| [Advisors](./advisors.md) | Architecture · REST API · Spring · Quarkus · Database · Hibernate · Memory · Security · Pentesting · Vulnerabilities | What is wrong with my app, and how do I fix it? |
| [Runtime](./runtime.md) | Health · HTTP Sessions · Metrics · Live Memory · JVM Tuning · Heap Dump · Threads · Startup Timeline · GraalVM · CRaC | How is the JVM behaving right now? |
| [Configuration](./configuration.md) | Configuration · Profile Diff · Loggers · Beans · Conditions · Mappings | What configuration and wiring is actually effective? |
| [Database](./database.md) | Connection Pools · PostgreSQL · MySQL · SQL Trace · Hibernate Statistics · Transactions · Spring Data · Flyway · Liquibase | What is my app doing to the database? |
| [Security](./security.md) | Spring Security · Security Logs | How is access actually enforced? |
| [Services](./services.md) | Scheduled Tasks · REST Client · Fault Tolerance · WebSockets · AI Framework · Cache · Email · Kafka · RabbitMQ · JMS | What is my app talking to? |
| [Diagnostics](./diagnostics.md) | Traces · Log Tail · Exceptions · HTTP Exchanges · HTTP Probe | Why did that request fail? |
| [Developer tools](./developer-tools.md) | MCP Server · Command Line · Spring DevTools · Dev Services · Copilot · Claude Code | What is my toolchain doing locally? |

## Rules that apply to every panel

**An old tab can recover after a UI rebuild.** When a panel's JavaScript or stylesheet is no longer available, BootUI
keeps the current panel and offers **Reload BootUI**, which fetches the current UI and opens the intended panel,
including its route query and hash. Custom console mounts are preserved.

Reloading discards unsaved input, so BootUI never does it for you. If loading still fails, the alert stays available.
There is no automatic retry, reload loop, or background connection probe, and ordinary panel and API errors keep their
existing handling.

**Unavailable panels are visible, not hidden.** When a panel's backing infrastructure is missing, the sidebar moves it
into a collapsed *Disabled / unavailable* group, and opening it shows the reason at the top of the page.

The vendor-specific database panels are the exception. PostgreSQL and MySQL appear only when that vendor's JDBC driver
is on the runtime classpath. With the driver present but no matching datasource configured they are listed as
unavailable, as above; with no driver at all they are absent from the sidebar and from `/bootui/api/panels`, and their
API path returns 404.

**Every panel can be turned off.** Use `bootui.panels.<panel-id>.enabled=false`. Panels with browser-triggered actions
also support `bootui.panels.<panel-id>.read-only=true`, and `bootui.read-only=true` makes all of BootUI read-only. See
the [property reference](../PROPERTIES.md) for the complete list.

**Changing server state always asks first.** Restarting a dev service, capturing or deleting a heap dump, writing a
GraalVM or CRaC artifact, running a migration, clearing a cache or trace buffer, and destroying an HTTP session each
open a confirmation dialog naming the affected resource. Read-only scans and reversible toggles never prompt.

::: details How the confirmation dialog behaves
The dialog flags irreversible operations, defaults focus to Cancel, dismisses on Escape or a backdrop click, and honors
`prefers-reduced-motion`.
:::

**BootUI hides itself by default.** Beans, Conditions, Mappings, Loggers, Metrics, Startup Timeline, Scheduled Tasks,
Cache, Spring Security, HTTP Exchanges, and Traces exclude BootUI's own runtime data, so they stay focused on your
application. Set `bootui.monitoring.exclude-self=false` to include BootUI internals while debugging the console.

## Availability per stack

Spring MVC is the reference stack. For per-panel availability on the other two, see
[Framework support](../FRAMEWORK-SUPPORT.md). The running console is always the better answer: every panel that cannot
run says so in the sidebar and explains why when you open it.
