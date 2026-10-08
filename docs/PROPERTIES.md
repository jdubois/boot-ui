# BootUI properties

BootUI reads its `bootui.*` configuration from the host application's own configuration — Spring Boot property sources
on the Spring adapter, MicroProfile Config on the Quarkus adapter. It is local-only by default: it activates only in
development contexts, rejects non-loopback callers, masks secret-like values, and disables itself for production profiles
unless explicitly forced on.

Panel settings are consistent across the UI and API:

- Every visible panel has `bootui.panels.<panel-id>.enabled` with default `true`.
- Panels with browser-triggered actions also have `bootui.panels.<panel-id>.read-only` with default `false`.
- `bootui.read-only=true` makes every action-capable panel read-only, even when the per-panel read-only flag is `false`.
- Disabled panels are moved to the Disabled / unavailable sidebar group and their panel API routes return `403`.
- Read-only panels keep read endpoints visible but block mutating API requests. Safe methods (`GET`, `HEAD`, `OPTIONS`)
  remain allowed.

## Spring vs Quarkus (cross-adapter parity)

BootUI targets Spring Boot and Quarkus from one codebase, and its `bootui.*` keys are **largely the
same by name on both adapters** — but they are read by different configuration engines, and a few
keys are platform-specific.

**How keys are read.** On Spring, `bootui.*` keys are bound once into a `@ConfigurationProperties`
object, so Spring's relaxed binding applies (camelCase, kebab-case, and underscores are all
accepted). On Quarkus, keys are read through MicroProfile Config and must be
written in **exact kebab-case**. Safety policy is read **live, per request**; a missing or invalid value **fails closed** (for example, masking
stays on and non-loopback access stays denied). Most keys below are honored identically on both
adapters. Static bounds such as the PostgreSQL row limits require an application restart on every adapter.

The [MySQL properties](#mysql) follow the same JDBC-backed cross-adapter configuration contract and static-limit
restart requirements.

**Activation.** Spring decides activation at runtime from `bootui.enabled` and the
`enabled-profiles` / `disabled-profiles` lists (plus DevTools). Quarkus decides activation at
**build time from the launch mode**: the console is wired in `dev` and `test` and is completely
absent (prod-dark) in a production build. The three Spring activation keys therefore **have no effect
on Quarkus**.

**Host application namespace.** The host application itself is configured with its own framework's
properties — `spring.*` on Spring, **`quarkus.*` on Quarkus**. BootUI does **not** read `spring.*`
keys on Quarkus; its advisors bridge the two namespaces internally (for example, the Hibernate
advisor maps the Spring property names its rules expect onto their `quarkus.hibernate-orm.*`
equivalents).

### Keys that are not shared

| Key(s)                                                                       | Scope                      | Notes                                                                                                                                      |
| ---------------------------------------------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.enabled`, `bootui.enabled-profiles`, `bootui.disabled-profiles`      | Spring only                | Quarkus activates by build-time launch mode.                                                                                             |
| `bootui.force-web`, `bootui.startup.enabled`, `bootui.startup.capacity`      | Spring only                | Driven by Spring `EnvironmentPostProcessor`s with no Quarkus analogue.                                                                   |
| `bootui.free-on-idle.enabled` / `.timeout`                                   | Spring only                | The idle-buffer-release optimization is Spring-only.                                                                                     |
| `bootui.dev-services.restart-enabled` / `.log-tail-bytes`                    | Spring only                | Quarkus Dev Services are build-time; the panel has no log-tail or restart controls.                                                      |
| `bootui.graalvm.*`                                                           | Spring only                | The GraalVM panel is not applicable on Quarkus.                                                                                          |
| `bootui.http-sessions.max-sessions`                                          | Spring only                | The HTTP Sessions panel is not applicable on Quarkus.                                                                                    |
| `bootui.activity.max-entries`, `bootui.activity.n-plus-one-threshold` | Spring only | Stream cap and N+1 detection threshold apply only to Spring's richer tiered-correlation profiler; Quarkus's reduced trace-id-only profiler has no equivalent config. `bootui.activity.request-slow-threshold-ms` and `bootui.activity.max-scheduled-task-runs` are shared by both adapters (see below). The optional durable-persistence backend (`bootui.activity.persistence.*`) is **shared** — see below. |
| `bootui.telemetry.max-request-bytes`                                         | Spring only                | Sizes the embedded OTLP receiver, which Quarkus does not run (it captures spans in-process).                                             |
| `bootui.cache.activity-capture-enabled`, `bootui.cache.activity-max-events`  | Spring only                | Feeds the Live Activity `CACHE` events and cache hit ratio KPI, captured by decorating Spring `CacheManager` beans; Quarkus has no comparable runtime interception seam for `quarkus-cache`'s build-time-woven annotations. |
| `bootui.internal.*`                                                          | **Quarkus only, internal** | Build-time facts (base packages, dependency inventory, capability-present flags) emitted by build steps. Not a user setting — never set by hand. |

### Keys with a shared name but platform-specific behavior

| Key                     | Spring                                                                                                              | Quarkus                                                                                                                            |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.overrides-file` | The Configuration panel persists runtime overrides here, and the key also locates the advisor dismissed-rules file. | The Configuration panel is read-only on Quarkus, so the key only locates the advisor dismissed-rules file (`.bootui/boot-ui.yml`). |
| `bootui.agent.*` | Read at startup, before the context exists, to decide whether and how the run claims an attached BootUI agent. | Build-time configuration read when the application is built (augmented); dev mode rebuilds when it changes. |

Everything not listed in the two tables above is honored under the same key — and with the same
default — on both adapters. This includes the safety keys (`bootui.allow-non-localhost`,
`bootui.allowed-hosts`, `bootui.trusted-proxies`, `bootui.trust-container-gateway`,
`bootui.authentication.token`),
`bootui.expose-values`, `bootui.mask-secrets`, `bootui.path` / `bootui.api-path`,
`bootui.monitoring.exclude-self`, `bootui.http-exchanges.max-exchanges` (default `200`),
`bootui.http-exchanges.reserved-share-percent` (default `25`), `bootui.activity.request-slow-threshold-ms` (default
`1000`),
`bootui.log-tail.max-bytes` (default `0`, meaning unbounded), and the `bootui.github.*`,
`bootui.vulnerabilities.*` (including `osv-base-uri`, default `https://api.osv.dev`),
`bootui.sql-trace.*`, `bootui.runtime-journal.*`, `bootui.postgresql.*`, `bootui.transactions.*`, `bootui.telemetry.*` (except `max-request-bytes`), `bootui.heap-dump.*`,
`bootui.exceptions.*`, `bootui.security-logs.*`, `bootui.cache.*` (except `.activity-capture-enabled` and
`.activity-max-events`, Spring only — see above), `bootui.mcp.*`, `bootui.cli.*`, `bootui.ai.*`,
`bootui.copilot.*`, and `bootui.claude-code.*` families. It also includes the per-panel access keys —
`bootui.panels.<id>.enabled` / `.read-only` and the global `bootui.read-only` — which are enforced on
Quarkus by `QuarkusPanelAccessFilter` at full behavioral parity with Spring's `PanelAccessFilter` (same
config keys, same `BootUiPanels` path resolution, same canonical JSON 403 body); see "Panel access
settings" below.

## Global settings

| Property                         | Default                                 | Description                                                                                                                     |
| -------------------------------- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.enabled`                 | `AUTO`                                  | Activation mode. `AUTO` activates only for configured local profiles or DevTools; `ON` forces BootUI on; `OFF` forces it off. In YAML, `ON`/`OFF` are parsed as booleans, so `true`/`yes` and `false`/`no` are accepted as `ON`/`OFF`. |
| `bootui.enabled-profiles`        | `dev,local`                             | Profiles that activate BootUI when `bootui.enabled=AUTO`.                                                                       |
| `bootui.disabled-profiles`       | `prod,production`                       | Profiles that force BootUI off unless `bootui.enabled=ON`.                                                                      |
| `bootui.force-web`               | `true`                                  | While BootUI is active, force a non-web (command-line) application into a servlet web application so the console can be served. No effect on apps that are already servlet web apps or explicitly reactive. Set to `false` to leave the host's web-application type untouched. |
| `bootui.path`                    | `/bootui`                               | Application-relative UI base path used by the shell, assets, filters, and startup banner. Normalized and validated as described below. |
| `bootui.api-path`                | `<bootui.path>/api`                     | Optional application-relative API base path used by the UI, controllers, filters, MCP, OTLP, streams, and downloads.             |
| `bootui.allow-non-localhost`     | `false`                                 | Explicitly relax only the source-address check. Host/DNS-rebinding, cross-site-write, and bearer-authentication protections remain active. Keep this `false` unless remote access is required and the network is trusted. |
| `bootui.allowed-hosts`           | _(empty)_                               | Extra `Host` header values accepted by the loopback filter, in addition to the built-in loopback names (`localhost`, `127.0.0.1`, `::1`). Use this for custom local hostnames while keeping DNS-rebinding protection. Entries must be well-formed hostnames or IP literals: a request whose `Host` header cannot be parsed as an authority is rejected. |
| `bootui.authentication.token`    | _(generated)_                           | Access token required for every non-loopback `/bootui/api/**` request. When remote access is configured and this property is blank, BootUI generates a 256-bit token and logs it once at startup. Supply a stable token through an environment-backed property when logs are shared; configured tokens are never printed. |
| `bootui.trusted-proxies`         | _(empty)_                               | Source IP ranges in CIDR notation (e.g. `172.16.0.0/12` for the Linux Docker bridge, or `192.168.65.0/24` for the Docker Desktop gateway) trusted in addition to loopback. A narrow opt-in for local Docker-bridge callers: it relaxes only the source-address check while keeping the `Host` allow-list (DNS-rebinding) and cross-site write (CSRF) protections in force. Prefer this over `bootui.allow-non-localhost`, and pair it with `bootui.allowed-hosts` for the hostname the browser uses. |
| `bootui.trust-container-gateway` | `OFF`                                   | One-flag opt-in to trust the auto-detected container gateway as a single `/32`, so BootUI can be reached inside a container with a published port (host→container traffic is SNAT'd to the gateway) without knowing the subnet or setting a broad `bootui.trusted-proxies` CIDR. Detection works on both flavors: the bridge default gateway from `/proc/net/route` on Linux Docker Engine (e.g. `172.17.0.1`), and the `gateway.docker.internal` DNS name on Docker Desktop (`192.168.65.1`, which is _not_ the route-table gateway). `OFF` (default, fail closed) never trusts it; `AUTO` auto-detects and trusts the gateway only when running inside a container; `ON` trusts a detected gateway even if container heuristics are inconclusive. Relaxes only the source-address check — the `Host` allow-list (DNS-rebinding) and cross-site write (CSRF) protections stay in force. Note: with the common `-p 8080:8080` bind, LAN clients reaching the published port are also SNAT'd to the gateway; use `-p 127.0.0.1:8080:8080` for strict loopback equivalence. |
| `bootui.mask-secrets`            | `true`                                  | Enables secret-like value masking helpers.                                                                                      |
| `bootui.expose-values`           | `MASKED`                                | Configuration value exposure mode: `MASKED`, `METADATA_ONLY`, or `FULL`. It also governs exception, Log Tail, and Dev Services container log text, and the span values of the Traces detail, request profile, and AI Framework chat detail. `FULL` can disclose secrets. |
| `bootui.show-banner`             | `true`                                  | Print the BootUI URL on application startup.                                                                                    |
| `bootui.startup.enabled`         | `true`                                  | Install a `BufferingApplicationStartup` automatically while BootUI is active so the Startup Timeline panel has data.            |
| `bootui.startup.capacity`        | `4096`                                  | Maximum startup steps retained by BootUI's auto-installed startup buffer. Values less than or equal to zero disable the buffer. |
| `bootui.free-on-idle.enabled`    | `true`                                  | Release BootUI's live in-memory diagnostic buffers (captured SQL, ingested traces, and the request/security correlation windows) and pause recording into them after the console has been idle for `bootui.free-on-idle.timeout`, refilling them from live traffic once the console is used again. Dev-only (BootUI is inactive in production); the Exceptions and Log Tail buffers are always retained, and the [runtime journal](#runtime-journal) keeps recording while the buffers are released. Set to `false` to keep all buffers recording continuously. |
| `bootui.free-on-idle.timeout`    | `5m`                                    | How long the console may go without any BootUI request (UI load, API poll, or stream open) before its live buffers are released. The timer resets on every BootUI request, so an open console never reclaims. Clamped to a minimum of one second. |
| `bootui.read-only`               | `false`                                 | Disable every browser-triggered action while keeping read-only panel data visible.                                              |
| `bootui.overrides-file`          | `.bootui/application-bootui.properties` | File used by the Configuration panel to persist local runtime overrides. BootUI resolves the advisor dismissed-findings file (`boot-ui.yml`) in the same directory. Set it from the environment, not from `application.properties`. |
| `bootui.monitoring.exclude-self` | `true`                                  | Hide BootUI's own beans, mappings, loggers, metrics, traces, and related runtime data from monitoring panels.                   |

### Remote API authentication

The configured static SPA remains available after non-loopback access is explicitly enabled, but
the configured API surface requires authentication for every caller whose raw TCP peer is not
loopback. Paste the startup token into the unlock screen; BootUI exchanges it for an HTTP-only,
`SameSite=Strict` session cookie scoped to the browser-visible API path (including the host application's context/root
path), which also authenticates SSE streams and
downloads. CLI, MCP, and OTLP clients should send the token using the standard HTTP bearer authorization scheme.

Localhost requests remain frictionless and do not require a token. Authentication is an additional
layer: activation, source trust, Host validation, cross-site-write protection, panel access, and
read-only checks still apply. Use HTTPS for direct remote access because bearer credentials sent over
plain HTTP can be intercepted.

### Custom UI and API paths

`bootui.path` is configurable on Spring MVC, Spring WebFlux, and Quarkus. `/bootui` remains the exact
backward-compatible default. When only the UI path is set, the API path is derived after normalization:

```properties
bootui.path=/dev-console/
# Effective API path: /dev-console/api
```

Use `bootui.api-path` only when the API must live elsewhere:

```properties
bootui.path=/dev-console
bootui.api-path=/internal/bootui-api
```

Both values are relative to the application root. `server.servlet.context-path`, `spring.webflux.base-path`, or
`quarkus.http.root-path` is prepended automatically and exactly once. Do not include that framework root in the
`bootui.*` values.

BootUI trims surrounding whitespace and trailing slashes. It rejects blank or root paths, `.` / `..` path segments,
duplicate interior slashes, query/fragment content, percent encoding, backslashes, routing metacharacters, and any
character outside RFC 3986's unreserved path-segment set. Invalid active configuration fails startup with the property
name in the error. The UI path also cannot be nested below `/bootui/**`, which is reserved for Quarkus' private internal
classpath mount; the exact `/bootui` default is valid, and the API default `/bootui/api` remains valid.

With a custom UI path, the old packaged `/bootui` mount is answered with 404 rather than retained as a compatibility
alias. The generated SPA shell receives the fully composed UI/API locations at runtime, so assets, API calls, SSE,
downloads, MCP configuration, and OTLP setup copy all follow the configured paths.

## Panel access settings

Enforced identically on Spring and Quarkus (`PanelAccessFilter` / `QuarkusPanelAccessFilter`).

| Group           | Panel                     | Panel id                    | Enable property                                   | Read-only property                        |
| --------------- | ------------------------- | --------------------------- | ------------------------------------------------- | ----------------------------------------- |
| Home            | Scorecard                 | `overview`                  | `bootui.panels.overview.enabled`                  | Not applicable; view-only.                |
| Home            | Live Activity             | `activity`                  | `bootui.panels.activity.enabled`                  | `bootui.panels.activity.read-only`         |
| Home            | Runtime Insights          | `runtime-insights`          | `bootui.panels.runtime-insights.enabled`          | `bootui.panels.runtime-insights.read-only` |
| Advisors        | Architecture              | `architecture`              | `bootui.panels.architecture.enabled`              | `bootui.panels.architecture.read-only`    |
| Advisors        | REST API                  | `rest-api`                  | `bootui.panels.rest-api.enabled`                  | `bootui.panels.rest-api.read-only`        |
| Advisors        | Spring                    | `spring`                    | `bootui.panels.spring.enabled`                    | `bootui.panels.spring.read-only`          |
| Advisors        | Database                  | `database-advisor`          | `bootui.panels.database-advisor.enabled`          | `bootui.panels.database-advisor.read-only` |
| Advisors        | Hibernate                 | `hibernate`                 | `bootui.panels.hibernate.enabled`                 | `bootui.panels.hibernate.read-only`       |
| Advisors        | Memory                    | `memory`                    | `bootui.panels.memory.enabled`                    | `bootui.panels.memory.read-only`          |
| Advisors        | Security                  | `security`                  | `bootui.panels.security.enabled`                  | `bootui.panels.security.read-only`        |
| Advisors        | Pentesting                | `pentesting`                | `bootui.panels.pentesting.enabled`                | `bootui.panels.pentesting.read-only`      |
| Advisors        | Vulnerabilities           | `vulnerabilities`           | `bootui.panels.vulnerabilities.enabled`           | `bootui.panels.vulnerabilities.read-only` |
| Runtime         | Health                    | `health`                    | `bootui.panels.health.enabled`                    | Not applicable; view-only.                |
| Runtime         | HTTP Sessions             | `http-sessions`             | `bootui.panels.http-sessions.enabled`             | `bootui.panels.http-sessions.read-only`   |
| Runtime         | Metrics                   | `metrics`                   | `bootui.panels.metrics.enabled`                   | Not applicable; view-only.                |
| Runtime         | Live Memory               | `live-memory`               | `bootui.panels.live-memory.enabled`               | `bootui.panels.live-memory.read-only`     |
| Runtime         | JVM Tuning                | `jvm-tuning`                | `bootui.panels.jvm-tuning.enabled`                | Not applicable; view-only.                |
| Runtime         | Heap Dump                 | `heap-dump`                 | `bootui.panels.heap-dump.enabled`                 | `bootui.panels.heap-dump.read-only`       |
| Runtime         | Threads                   | `threads`                   | `bootui.panels.threads.enabled`                   | `bootui.panels.threads.read-only`         |
| Runtime         | Startup Timeline          | `startup`                   | `bootui.panels.startup.enabled`                   | Not applicable; view-only.                |
| Runtime         | GraalVM                   | `graalvm`                   | `bootui.panels.graalvm.enabled`                   | `bootui.panels.graalvm.read-only`         |
| Runtime         | CRaC                      | `crac`                      | `bootui.panels.crac.enabled`                      | `bootui.panels.crac.read-only`            |
| Configuration   | Configuration             | `config`                    | `bootui.panels.config.enabled`                    | `bootui.panels.config.read-only`          |
| Configuration   | Profile Diff              | `profile-diff`              | `bootui.panels.profile-diff.enabled`              | Not applicable; view-only.                |
| Configuration   | Loggers                   | `loggers`                   | `bootui.panels.loggers.enabled`                   | `bootui.panels.loggers.read-only`         |
| Configuration   | Beans                     | `beans`                     | `bootui.panels.beans.enabled`                     | Not applicable; view-only.                |
| Configuration   | Conditions                | `conditions`                | `bootui.panels.conditions.enabled`                | Not applicable; view-only.                |
| Configuration   | Mappings                  | `mappings`                  | `bootui.panels.mappings.enabled`                  | Not applicable; view-only.                |
| Database        | Database Connection Pools | `database-connection-pools` | `bootui.panels.database-connection-pools.enabled` | Not applicable; view-only.                |
| Database        | PostgreSQL                | `postgresql`                | `bootui.panels.postgresql.enabled`                | `bootui.panels.postgresql.read-only`      |
| Database        | MySQL                    | `mysql`                     | `bootui.panels.mysql.enabled`                     | `bootui.panels.mysql.read-only`           |
| Database        | Transactions              | `transactions`              | `bootui.panels.transactions.enabled`              | `bootui.panels.transactions.read-only`    |
| Database        | SQL Trace                 | `sql-trace`                 | `bootui.panels.sql-trace.enabled`                 | `bootui.panels.sql-trace.read-only`       |
| Database        | Hibernate Statistics      | `hibernate-statistics`      | `bootui.panels.hibernate-statistics.enabled`      | `bootui.panels.hibernate-statistics.read-only` |
| Database        | Spring Data               | `data`                      | `bootui.panels.data.enabled`                      | Not applicable; view-only.                |
| Database        | Flyway                    | `flyway`                    | `bootui.panels.flyway.enabled`                    | `bootui.panels.flyway.read-only`          |
| Database        | Liquibase                 | `liquibase`                 | `bootui.panels.liquibase.enabled`                 | `bootui.panels.liquibase.read-only`       |
| Security        | Spring Security           | `spring-security`           | `bootui.panels.spring-security.enabled`           | Not applicable; view-only.                |
| Security        | Security Logs             | `security-logs`             | `bootui.panels.security-logs.enabled`             | Not applicable; view-only.                |
| Services        | Scheduled Tasks           | `scheduled`                 | `bootui.panels.scheduled.enabled`                 | Not applicable; view-only.                |
| Services        | Fault Tolerance           | `fault-tolerance`           | `bootui.panels.fault-tolerance.enabled`           | Not applicable; view-only.                |
| Services        | REST Client               | `rest-client-trace`         | `bootui.panels.rest-client-trace.enabled`         | `bootui.panels.rest-client-trace.read-only` |
| Services        | WebSockets                | `websockets`                | `bootui.panels.websockets.enabled`                | `bootui.panels.websockets.read-only`      |
| Services        | AI Framework              | `ai`                        | `bootui.panels.ai.enabled`                        | Not applicable; view-only.                |
| Services        | Cache                     | `cache`                     | `bootui.panels.cache.enabled`                     | `bootui.panels.cache.read-only`           |
| Services        | Email                     | `email`                     | `bootui.panels.email.enabled`                     | `bootui.panels.email.read-only`           |
| Services        | Kafka                     | `kafka`                     | `bootui.panels.kafka.enabled`                     | `bootui.panels.kafka.read-only`           |
| Services        | RabbitMQ                  | `rabbitmq`                  | `bootui.panels.rabbitmq.enabled`                  | `bootui.panels.rabbitmq.read-only`    |
| Services        | JMS                       | `jms`                       | `bootui.panels.jms.enabled`                       | `bootui.panels.jms.read-only`              |
| Diagnostics     | Traces                    | `traces`                    | `bootui.panels.traces.enabled`                    | `bootui.panels.traces.read-only`          |
| Diagnostics     | Log Tail                  | `log-tail`                  | `bootui.panels.log-tail.enabled`                  | Not applicable; view-only.                |
| Diagnostics     | Exceptions                | `exceptions`                | `bootui.panels.exceptions.enabled`                | `bootui.panels.exceptions.read-only`      |
| Diagnostics     | HTTP Exchanges            | `http-exchanges`            | `bootui.panels.http-exchanges.enabled`            | Not applicable; view-only.                |
| Diagnostics     | HTTP Probe                | `http-probe`                | `bootui.panels.http-probe.enabled`                | `bootui.panels.http-probe.read-only`      |
| Java agent      | Java Agent                | `java-agent`                | `bootui.panels.java-agent.enabled`                | `bootui.panels.java-agent.read-only`      |
| Java agent      | Code Paths                | `code-paths`                | `bootui.panels.code-paths.enabled`                | `bootui.panels.code-paths.read-only`      |
| Java agent      | Code Inventory            | `code-inventory`            | `bootui.panels.code-inventory.enabled`            | Not applicable; view-only.                |
| Java agent      | Side Effects              | `side-effects`              | `bootui.panels.side-effects.enabled`              | Not applicable; view-only.                |
| Developer tools | MCP Server                | `mcp-server`                | `bootui.panels.mcp-server.enabled`                | `bootui.panels.mcp-server.read-only`      |
| Developer tools | Command Line              | `cli`                       | `bootui.panels.cli.enabled`                       | Not applicable; view-only.                |
| Developer tools | Spring DevTools           | `devtools`                  | `bootui.panels.devtools.enabled`                  | `bootui.panels.devtools.read-only`        |
| Developer tools | Dev Services              | `dev-services`              | `bootui.panels.dev-services.enabled`              | `bootui.panels.dev-services.read-only`    |
| Developer tools | Copilot                   | `copilot`                   | `bootui.panels.copilot.enabled`                   | Not applicable; view-only.                |
| Developer tools | Claude Code               | `claude-code`               | `bootui.panels.claude-code.enabled`               | Not applicable; view-only.                |
| Developer tools | GitHub                    | `github`                    | `bootui.panels.github.enabled`                    | `bootui.panels.github.read-only`          |

## Per-panel action details

### Advisor violation retention

| Property | Default | Description |
| --- | --- | --- |
| `bootui.advisors.max-retained-violations` | `10000` | Positive maximum sanitized violation details retained across all rules in each advisor's latest scan. |

Shared by Architecture, Hibernate, Spring/Quarkus application, REST API, Memory, Security, and Database Advisor on
their supported stacks. The limit is captured when a scan starts, including across Hibernate persistence units;
zero, negative, and invalid values are not an unlimited mode. Only the latest snapshot is retained. Counts and
existing ten-/twenty-entry summary samples remain unchanged when retention is exhausted; report
`violationDetails.truncated` and each detail page's `truncated` explicitly disclose missing entries.

Raising this setting requires a new explicit scan to retain previously omitted details; it cannot fill an existing
snapshot. Detail pages default to 100 entries, at most 1000, and MCP/CLI also enforce their transport budgets.
Reads remain available with `bootui.read-only=true` or a panel's read-only setting; disabled/unavailable panels
remain gated. Retrieval completeness does not affect evidence coverage or scores. See
[reading every retained violation](features/advisors.md#reading-every-retained-violation).

### Startup Timeline

| Property                        | Default | Description                                                                   |
| ------------------------------- | ------- | ----------------------------------------------------------------------------- |
| `bootui.panels.startup.enabled` | `true`  | Show the Startup Timeline panel.                                              |
| `bootui.startup.enabled`        | `true`  | Install a `BufferingApplicationStartup` automatically while BootUI is active. |
| `bootui.startup.capacity`       | `4096`  | Maximum startup steps retained by the auto-installed startup buffer.          |

### HTTP Sessions

| Property                                | Default | Description                                                                  |
| --------------------------------------- | ------- | ---------------------------------------------------------------------------- |
| `bootui.panels.http-sessions.enabled`   | `true`  | Show local embedded Tomcat HTTP sessions when a live session manager exists. |
| `bootui.panels.http-sessions.read-only` | `false` | Disable HTTP session clear and destroy actions.                              |
| `bootui.http-sessions.max-sessions`     | `50`    | Maximum HTTP sessions returned in one panel response.                        |

### GitHub

| Property                               | Default          | Description                                                                          |
| -------------------------------------- | ---------------- | ------------------------------------------------------------------------------------ |
| `bootui.panels.github.enabled`         | `true`           | Show the GitHub panel when the local working tree has a GitHub origin.               |
| `bootui.panels.github.read-only`       | `false`          | Disable live refresh calls to GitHub while keeping local repository metadata.        |
| `bootui.github.api-enabled`            | `true`           | Additional action gate for outbound GitHub API calls during live refresh.            |
| `bootui.github.request-timeout`        | `5s`             | Timeout for each GitHub API request and local `gh auth token` lookup.                |
| `bootui.github.max-pull-requests`      | `10`             | Maximum open pull requests returned in one refresh.                                  |
| `bootui.github.max-issues`             | `25`             | Maximum open issues fetched for the issue buckets and open issue list in one refresh. |
| `bootui.github.max-security-alerts`    | `50`             | Maximum Dependabot alert details listed per refresh (count stays exact; metadata only). |
| `bootui.github.max-workflow-runs`      | `20`             | Maximum recent workflow runs returned in one refresh.                                |
| `bootui.github.quota-safety-threshold` | `10`             | Skip optional API calls when remaining core quota is at or below this value.         |
| `bootui.github.max-api-calls`          | `17`             | Maximum GitHub API requests issued by one refresh.                                   |
| `bootui.github.allowed-api-hosts`      | `api.github.com` | Allowed GitHub API hosts. Add a GitHub Enterprise host to enable enterprise remotes. |

### Configuration

| Property                         | Default                                 | Description                                                            |
| -------------------------------- | --------------------------------------- | ---------------------------------------------------------------------- |
| `bootui.panels.config.enabled`   | `true`                                  | Show the Configuration panel and allow its read APIs.                  |
| `bootui.panels.config.read-only` | `false`                                 | Disable creating, updating, and deleting runtime property overrides.   |
| `bootui.overrides-file`          | `.bootui/application-bootui.properties` | Local file where runtime overrides are persisted. Also locates `boot-ui.yml` (advisor dismissed findings), which BootUI resolves in the same directory. |
| `bootui.expose-values`           | `MASKED`                                | Controls whether property values are masked, hidden, or fully exposed. |

### Loggers

| Property                          | Default | Description                                          |
| --------------------------------- | ------- | ---------------------------------------------------- |
| `bootui.panels.loggers.enabled`   | `true`  | Show logger data from the Actuator loggers endpoint. |
| `bootui.panels.loggers.read-only` | `false` | Disable runtime logger level updates and resets.     |

### REST API

| Property                           | Default | Description                                          |
| ---------------------------------- | ------- | ---------------------------------------------------- |
| `bootui.panels.rest-api.enabled`   | `true`  | Show read-only REST API design best-practice checks. |
| `bootui.panels.rest-api.read-only` | `false` | Disable the explicit REST API Advisor scan action.   |

### Spring

| Property                         | Default | Description                                                     |
| -------------------------------- | ------- | --------------------------------------------------------------- |
| `bootui.panels.spring.enabled`   | `true`  | Show read-only Spring application-context best-practice checks. |
| `bootui.panels.spring.read-only` | `false` | Disable the explicit Spring Advisor scan action.                |

### Spring Security

| Property                                | Default | Description                                                                    |
| --------------------------------------- | ------- | ------------------------------------------------------------------------------ |
| `bootui.panels.spring-security.enabled` | `true`  | Show Spring Security filter chains and best-effort endpoint rule explanations. |

### Security Logs

| Property                              | Default | Description                                                                                                            |
| ------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.security-logs.enabled` | `true`  | Show Spring Boot audit/security events and auto-contribute an in-memory `AuditEventRepository` when the host has none. |
| `bootui.security-logs.max-logs`       | `500`   | Maximum recent audit events returned in one Security Logs response.                                                    |

### Security

| Property                           | Default | Description                                               |
| ---------------------------------- | ------- | --------------------------------------------------------- |
| `bootui.panels.security.enabled`   | `true`  | Show read-only Spring Security hardening checks.          |
| `bootui.panels.security.read-only` | `false` | Disable the explicit Spring Security Advisor scan action. |

### Pentesting

| Property                             | Default | Description                                                          |
| ------------------------------------ | ------- | ------------------------------------------------------------------- |
| `bootui.panels.pentesting.enabled`   | `true`  | Show the host-application OWASP hygiene panel and its latest report. |
| `bootui.panels.pentesting.read-only` | `false` | Disable the explicit local scan action.                              |

### Cache

| Property                                 | Default | Description                                                                                                        |
| ----------------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------ |
| `bootui.panels.cache.enabled`            | `true`  | Show cache managers, caches, metrics, and cache annotations.                                                        |
| `bootui.panels.cache.read-only`          | `false` | Disable cache clear actions.                                                                                        |
| `bootui.cache.clear-enabled`             | `true`  | Additional action gate for cache clearing. Both this and the read-only state must allow clearing.                   |
| `bootui.cache.activity-capture-enabled` | `true`  | Spring only. Feed cache hits/misses/puts/evictions/clears into the Live Activity stream and its cache hit ratio KPI. |
| `bootui.cache.activity-max-events`      | `500`   | Spring only. Bounded ring-buffer size for captured cache-activity events.                                           |

### Hibernate

| Property                            | Default | Description                                                                       |
| ----------------------------------- | ------- | --------------------------------------------------------------------------------- |
| `bootui.panels.hibernate.enabled`   | `true`  | Show Hibernate/JPA mapping and configuration advisor findings.                    |
| `bootui.panels.hibernate.read-only` | `false` | Disable the explicit Hibernate Advisor scan action while keeping results visible. |

### Hibernate Statistics

| Property                                       | Default | Description                                                                                         |
| ---------------------------------------------- | ------- | --------------------------------------------------------------------------------------------------- |
| `bootui.panels.hibernate-statistics.enabled`   | `true`  | Show live Hibernate `SessionFactory` statistics when Hibernate ORM is available.                    |
| `bootui.panels.hibernate-statistics.read-only` | `false` | Disable **Enable for this runtime** while keeping already-enabled statistics visible and readable. |

**Enable for this runtime** starts statistics collection for the current application process only. It does not rewrite
application configuration or reset existing counters, and it does not require confirmation. The action is blocked when
either `bootui.read-only=true` or `bootui.panels.hibernate-statistics.read-only=true`. To enable collection persistently
from startup, set `hibernate.generate_statistics=true` on Spring or
`quarkus.hibernate-orm.statistics=true` on Quarkus, as recommended by
[HIB-CONFIG-007](HIBERNATE-CHECKS.md#hib-config-007-hibernate-statistics-should-be-enabled-when-tuning).

### Database

| Property                                  | Default | Description                                                                                   |
| ----------------------------------------- | ------- | --------------------------------------------------------------------------------------------- |
| `bootui.panels.database-advisor.enabled`   | `true`  | Show read-only JDBC schema checks and the latest Database Advisor report.                     |
| `bootui.panels.database-advisor.read-only` | `false` | Disable the explicit Database Advisor scan action while keeping the latest report visible.   |

**Run Database checks** performs bounded, read-only JDBC schema introspection. It does not require confirmation and is
blocked when either `bootui.read-only=true` or `bootui.panels.database-advisor.read-only=true`.

### PostgreSQL

These properties apply to Spring MVC, Spring WebFlux, and Quarkus. Each row limit applies independently to each
datasource and is shared by reads from the browser, REST API, MCP, and CLI.

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.postgresql.enabled` | `true` | Show the PostgreSQL panel when a PostgreSQL datasource is configured. |
| `bootui.panels.postgresql.read-only` | `false` | Disable the explicit read action while keeping the last report visible. |
| `bootui.postgresql.max-sessions` | `100` | Maximum client-session rows from `pg_stat_activity` for the connected database. |
| `bootui.postgresql.max-statements` | `100` | Maximum normalized statement entries, ranked by total execution time. Requires `pg_stat_statements`. |
| `bootui.postgresql.max-indexes` | `500` | Maximum index entries, ordered by least usage first, then largest size. |
| `bootui.postgresql.max-tables` | `200` | Maximum relation entries, ordered by total size descending. |
| `bootui.postgresql.max-vacuum-tables` | `200` | Maximum autovacuum table entries, ordered by dead tuples descending. |
| `bootui.postgresql.max-replicas` | `10` | Maximum connected-replica entries; does not limit aggregate checkpoint or replication-slot counters. |
| `bootui.postgresql.max-settings` | `40` | Maximum entries from the curated settings allow-list. Raising it does not expose additional setting names. Lowering it may omit settings used for autovacuum estimates. |

Row limits are **static: restart the application after changing them**, including when saved through Spring's
Configuration panel. Values must be integers from `1` to `2147483646`, inclusive. Zero, negative values, invalid
integers, and `2147483647` are rejected rather than clamped or treated as unlimited. The upper endpoint leaves room
for the one extra row used to detect truncation; it is not a recommended operating limit.

For example, to retain a deeper statement ranking and inspect more relations:

```properties
bootui.postgresql.max-statements=250
bootui.postgresql.max-tables=500
bootui.postgresql.max-vacuum-tables=500
```

The existing safety bounds stay fixed: a 5-second statement timeout, a 2-second lock timeout, a 15-second overall
read budget, and a 400-character statement-text limit. Raising row limits can increase read cost and does not
guarantee a complete result before these deadlines. Row-limit omissions retain `PARTIAL` / `truncated=true` and name
the affected sections; timeouts and permission failures remain distinct explanations.
In the UI, a statement-ranking cap alone is an informational note inside that section, not a page-wide warning.
Other capped sections and actual read problems still produce visible warnings.
See [PostgreSQL](features/database.md#postgresql) for read behavior and availability.

### MySQL

These defaults apply to Spring MVC, Spring WebFlux with JDBC, and Quarkus with JDBC.
They do not add R2DBC or reactive-client-only support. Browser, REST, MCP, and CLI reads share the same limits.

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.mysql.enabled` | `true` | Show the MySQL panel when a supported JDBC datasource is configured; disabling it blocks cached reads and collection. |
| `bootui.panels.mysql.read-only` | `false` | Block explicit collection while keeping cached evidence readable. Global `bootui.read-only=true` also blocks collection. |
| `bootui.mysql.max-sessions` | `100` | Maximum retained session rows per datasource; session association follows the selected default schema. |
| `bootui.mysql.max-statements` | `100` | Maximum retained normalized statement-digest rows per datasource, ranked by total execution time where timing is available. |
| `bootui.mysql.max-indexes` | `500` | Maximum retained logical index entries per datasource; a composite definition must not be silently cut by a raw-row cap. |
| `bootui.mysql.max-tables` | `200` | Maximum retained table entries in the datasource's selected schema. |
| `bootui.mysql.max-lock-waits` | `100` | Maximum retained lock-wait entries per datasource; related lock data is also bounded. |
| `bootui.mysql.max-replication-channels` | `10` | Maximum retained local replication-channel entries per datasource, not downstream topology or an unbounded worker list. |
| `bootui.mysql.max-settings` | `40` | Maximum retained entries from the fixed safe settings allow-list per datasource. Does not restrict mandatory safety/capability probes or expose new names when raised. |

The seven row limits are **static: restart the application after changing them**, including changes saved through
Spring's Configuration panel. Every value must be an integer from `1` to `2147483646`, inclusive. Invalid,
non-positive, and overflowing values fail startup with a property-specific error; zero is not unlimited.
The upper endpoint reserves one extra row for truncation detection, not a recommended operating size.

Current timing/text bounds are 15 seconds for the cooperative whole read, 5 seconds per server-bounded SELECT,
2 seconds for metadata-lock waiting, and 400 displayed statement characters.
The inspection connection's JDBC network guard is at most 7 seconds, retaining a tighter existing positive timeout;
it covers control/SHOW I/O and is restored after collection. The `SHOW GLOBAL STATUS` fallback uses 17 fixed safe
names, not the SELECT execution-time guarantee or `max-settings`. These are not extra configuration properties
or hard end-to-end deadlines. Pool acquisition can exceed the cooperative budget; application connection/socket
configuration remains separate. Increasing row caps changes neither these bounds nor the settings/InnoDB allow-lists.
Row omissions set `truncated`; timeouts, denied sources, and disabled instrumentation remain distinct limitations.
See [MySQL](features/database.md#mysql) for permissions, evidence scopes, and safety.

### Memory

| Property                         | Default | Description                                                    |
| -------------------------------- | ------- | -------------------------------------------------------------- |
| `bootui.panels.memory.enabled`   | `true`  | Show read-only JVM memory configuration best-practice checks. |
| `bootui.panels.memory.read-only` | `false` | Disable the explicit Memory Advisor scan action.              |

### Flyway

| Property                         | Default | Description                                                                         |
| -------------------------------- | ------- | ----------------------------------------------------------------------------------- |
| `bootui.panels.flyway.enabled`   | `true`  | Show Flyway migration state and allow its read APIs.                                |
| `bootui.panels.flyway.read-only` | `false` | Disable Flyway `migrate` and `clean` actions while keeping migration state visible. |

### Liquibase

| Property                            | Default | Description                                                                  |
| ----------------------------------- | ------- | ---------------------------------------------------------------------------- |
| `bootui.panels.liquibase.enabled`   | `true`  | Show Liquibase change-set history and allow its read APIs.                   |
| `bootui.panels.liquibase.read-only` | `false` | Disable Liquibase `update` actions while keeping change-set history visible. |

### SQL Trace

| Property                                  | Default | Description                                                                                                                                  |
| ----------------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.sql-trace.enabled`         | `true`  | Show the SQL Trace panel and its captured executions.                                                                                        |
| `bootui.panels.sql-trace.read-only`       | `false` | Disable the Pause/Resume and Clear actions while keeping captured executions visible.                                                        |
| `bootui.sql-trace.enabled`                | `true`  | Wrap `DataSource` beans with BootUI's hand-written JDBC tracing proxy. When `false`, no data source is wrapped.                              |
| `bootui.sql-trace.recording`              | `true`  | Initial recording state. Recording can be paused and resumed at runtime from the panel without unwrapping data sources.                      |
| `bootui.sql-trace.capture-parameters`     | `false` | Capture bound statement parameters alongside the SQL text. Off by default because values may be sensitive; metadata-only exposure suppresses them even when enabled. Prepared batches preview at most five parameter sets and five values per set, with omitted counts shown explicitly. |
| `bootui.sql-trace.capture-call-site`      | `true`  | Capture the call site (class, method, line) in your own application code that triggered each statement, via a small, bounded stack walk. A call site carries no bound values, so — unlike parameter capture — it is not privacy-gated and defaults on; set `false` to skip the stack walk entirely, which also records the runtime journal's statements without application frames. |
| `bootui.sql-trace.max-entries`            | `200`   | Maximum number of executed statements retained in the in-memory ring buffer.                                                                 |
| `bootui.sql-trace.slow-query-threshold-millis` | `100` | Executions at or above this many milliseconds are flagged as slow and eligible for the reserved share. Set to `0` to disable slow-query flagging, so only failed executions are reserved. |
| `bootui.sql-trace.reserved-share-percent` | `25` | Percentage of `max-entries` reserved for the most recent failed and slow executions, so routine executions are evicted first. Taken out of the buffer, never added to it; `0` evicts strictly oldest first. See [Failure-preserving retention](features/diagnostics.md#failure-preserving-retention). |
| `bootui.sql-trace.max-sql-length`         | `2000`  | Maximum retained SQL text length; longer statements are truncated. Plain `Statement` batches preview at most five statements and 256 characters per statement before this report-level limit is applied. Runtime Insights treats captures containing a truncation marker as lexical write candidates only, including later batch previews recovered after a truncated literal. |
| `bootui.sql-trace.max-parameter-length`   | `200`   | Maximum retained length of a single captured parameter value.                                                                                |
| `bootui.sql-trace.n-plus-one-threshold`   | `5`     | Number of times an identical `SELECT` must repeat within the buffer before it is flagged as a likely N+1 access pattern (minimum `2`).       |

### Transactions

| Property                                                | Default | Description |
| ------------------------------------------------------- | ------- | ----------- |
| `bootui.panels.transactions.enabled`                    | `true`  | Show the Transactions panel and register transaction capture. |
| `bootui.panels.transactions.read-only`                  | `false` | Disable Pause/Resume and Clear while keeping captured transactions visible. |
| `bootui.transactions.enabled`                           | `true`  | Contribute BootUI's listener to configurable blocking Spring transaction managers. |
| `bootui.transactions.recording`                         | `true`  | Initial recording state; the panel can pause or resume it at runtime. |
| `bootui.transactions.max-entries`                       | `200`   | Maximum completed transaction boundaries retained in the bounded in-memory buffer. |
| `bootui.transactions.slow-transaction-threshold-millis` | `200`   | Transactions at or above this duration are flagged as slow; `0` disables the flag. |
| `bootui.transactions.connection-hold-threshold-millis`  | `500`   | Transactions at or above this duration are flagged as holding a connection; `0` disables the flag. |

### REST Client

| Property                                              | Default | Description |
| ----------------------------------------------------- | ------- | ----------- |
| `bootui.panels.rest-client-trace.enabled`             | `true`  | Show the REST Client panel and its captured outbound HTTP calls. |
| `bootui.panels.rest-client-trace.read-only`           | `false` | Disable the Pause/Resume and Clear actions while keeping captured calls visible. |
| `bootui.rest-client-trace.enabled`                    | `true`  | Capture outbound calls from Spring's `RestClient`/`RestTemplate`/`WebClient` or Quarkus REST Client Reactive. When `false`, the recorder remains unavailable and retains no calls. |
| `bootui.rest-client-trace.recording`                  | `true`  | Initial recording state. Recording can be paused and resumed at runtime from the panel without removing the client instrumentation. |
| `bootui.rest-client-trace.capture-headers`            | `false` | **Spring only:** capture bounded request headers and mask them at report time. Quarkus ignores this property and never reads or retains arbitrary headers, credentials, cookies, or tokens. |
| `bootui.rest-client-trace.capture-call-site`          | `true`  | Capture the first application stack frame that triggered each outbound call, when available; set `false` to skip the stack walk, which also records the runtime journal's calls without application frames. Attribution is best-effort on Quarkus because reactive callbacks may run after the issuing stack has unwound. |
| `bootui.rest-client-trace.max-entries`                | `200`   | Maximum number of outbound calls retained in the in-memory ring buffer. |
| `bootui.rest-client-trace.slow-call-threshold-millis` | `1000`  | Calls at or above this many milliseconds are flagged as slow and eligible for the reserved share. Set to `0` to disable slow-call flagging, so only failed and error-response calls are reserved. |
| `bootui.rest-client-trace.reserved-share-percent`     | `25`    | Percentage of `max-entries` reserved for the most recent failed, error-response (`4xx`/`5xx`), and slow calls, so routine calls are evicted first. Taken out of the buffer, never added to it; `0` evicts strictly oldest first. |
| `bootui.rest-client-trace.max-uri-length`             | `2000`  | Maximum retained length of the request URI and path; longer values are truncated. |
| `bootui.rest-client-trace.max-header-value-length`    | `200`   | **Spring only:** maximum retained length of a captured header value. Quarkus captures no headers. |
| `bootui.rest-client-trace.chatty-call-threshold`      | `5`     | Number of calls to the same method/host/path (with numeric and UUID path segments normalized) within the buffer before the group is flagged as a likely repeated-call access pattern (minimum `2`). |

### WebSockets

| Property                                    | Default | Description |
| ------------------------------------------- | ------- | ----------- |
| `bootui.panels.websockets.enabled`          | `true`  | Show the WebSockets panel with its endpoints, live sessions, subscriptions, and captured frame metadata. |
| `bootui.panels.websockets.read-only`        | `false` | Disable the Pause/Resume and Clear actions while keeping the endpoint topology and captured metadata visible. |
| `bootui.websockets.enabled`                 | `true`  | Install the frame-capture seam where the stack supports one (Spring MVC + STOMP). When `false`, the panel still reports endpoints, sessions, and subscriptions but captures no frame metadata. |
| `bootui.websockets.capturing`               | `true`  | Initial frame-capture state. Capture can be paused and resumed at runtime from the panel without removing the instrumentation. |
| `bootui.websockets.max-endpoints`           | `200`   | Maximum number of declared endpoints reported; the panel says when the list was truncated. |
| `bootui.websockets.max-sessions`            | `200`   | Maximum number of sessions reported; the panel says when the list was truncated. |
| `bootui.websockets.max-subscriptions`       | `500`   | Maximum number of STOMP subscriptions reported; the panel says when the list was truncated. |
| `bootui.websockets.max-activity-entries`    | `500`   | Maximum frame-metadata entries retained in the bounded in-memory ring buffer. |
| `bootui.websockets.max-tracked-sessions`    | `2000`  | Maximum number of sessions for which per-session frame and byte counters are retained. |

BootUI never reads, decodes, or stores a WebSocket message payload on any stack; only frame size, direction, type, and
destination are recorded, and provider session ids are replaced by a short one-way hash before they leave the process.

### AI Framework

| Property                                | Default | Description                                                        |
| --------------------------------------- | ------- | ------------------------------------------------------------------ |
| `bootui.panels.ai.enabled`              | `true`  | Show the AI Framework panel.                                       |
| `bootui.ai.token-series-minutes`        | `60`    | Number of minutes retained in the AI Framework token series.       |
| `bootui.ai.max-recent-chats`            | `100`   | Maximum recent chat completions surfaced by the AI Framework panel. |
| `bootui.ai.show-content-capture-banner` | `true`  | Show the AI content-capture explanation banner.                    |

### Live Activity

The Live Activity panel reuses the HTTP Exchanges, SQL Trace, REST Client, Exceptions, Security Logs, Cache,
Scheduled Tasks, and Email sources, so disabling any of those panels through their own `bootui.panels.*` toggles also
removes them from the stream. Kafka, RabbitMQ, and JMS capture additionally have their own `bootui.kafka.*`,
`bootui.rabbitmq.*`, and `bootui.jms.*` toggles—see below—and each stops feeding Live Activity when its dedicated panel is
disabled. The panel itself is
read-only. A request whose correlated SQL trips `bootui.activity.n-plus-one-threshold` is flagged with a red **N+1**
badge both in the main stream row and in its profile drawer (the same threshold, so the two views never disagree); the
drawer additionally lists the flagged group's call site(s) whenever `bootui.sql-trace.capture-call-site` is enabled.

| Property                                      | Default | Description                                                                                                       |
| ---------------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.activity.enabled`              | `true`  | Show the Live Activity panel (merged stream and per-request profiler).                                           |
| `bootui.activity.max-entries`                 | `200`   | Maximum number of merged stream entries returned per page after merging and sorting all sources.                 |
| `bootui.activity.request-slow-threshold-ms`   | `1000`  | Duration in milliseconds at or above which a request is slow, on every stack: it sets the `SLOW` severity of `REQUEST` and `SCHEDULED` entries and decides which HTTP exchanges are kept in the reserved share, and so which requests Live Activity durable persistence remembers as reserved. Set to `0` to disable slow classification. |
| `bootui.activity.n-plus-one-threshold`        | `5`     | Number of identical correlated `SELECT` statements above which a request is flagged with a potential N+1 pattern, both as a list-level badge and in its profile drawer. |
| `bootui.activity.max-scheduled-task-runs`     | `200`   | Maximum number of captured `@Scheduled` method executions retained for `SCHEDULED` stream entries. Shared by both adapters: Spring feeds it from Micrometer's `ScheduledTaskObservationContext`, Quarkus from the CDI `SuccessfulExecution`/`FailedExecution` events (see [Live Activity](features/overview.md#feed-types)). |
| `bootui.activity.feed-source`                 | `journal` | Where the stream comes from, on every stack: `journal` renders the runtime journal's retained events, so its history reaches as far back as the journal retains and every child nests under its request or execution by id ([PLAN-v2.md](PLAN-v2.md) §5.3); `buffers` merges each panel's own buffer as in 1.x, until 2.0.0 removes it. A request may override it with `?source=`, and with the journal disabled the buffers serve the feed. The journal keeps no exception or log messages, principals, or email subjects; the live feed adds them back, already masked, from the panels while they still hold the event, and otherwise shows metadata only. It shows each exception occurrence rather than one row per group and adds `TRANSACTION` and `LOG` rows. With durable persistence on, `journal` also writes the history from the journal as it records each batch, instead of polling the merged feed, so a burst is kept as completely as the journal records it; persisted rows carry metadata only. An unknown value fails startup. |

#### Live Activity Kafka capture

When Kafka support is present, BootUI captures producer/consumer outcomes into the Live Activity stream as `MESSAGING`
entries. Spring does this by wrapping application-owned `KafkaTemplate` and `@KafkaListener` container factory beans;
Quarkus does it through SmallRye Reactive Messaging Kafka interceptors. Only metadata is captured — topic, partition,
offset, a hash of the key, timing, success/failure, consumer group id when exposed, and listener id — the message
value/payload is never captured, and failure text is generic so exception messages cannot leak payload or credentials.
On Spring, the consumer group is available and the listener-id field currently carries the listener container factory
bean name (not the resolved per-`@KafkaListener` id); on Quarkus the group is unavailable and the channel name is used
as the listener id. Quarkus outgoing capture requires `OutgoingKafkaRecordMetadata` to be attached before the interceptor;
payload-only emissions that rely solely on channel configuration are not recorded. See
[SPECIFICATION.md §5.14.2](./SPECIFICATION.md).

| Property                             | Default | Description                                                                                                    |
| ------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------- |
| `bootui.kafka.enabled`               | `true`  | Capture Kafka producer/consumer activity when the framework's Kafka integration is present.                    |
| `bootui.kafka.capture-key`           | `true`  | Capture a SHA-256 hash of the record key alongside each entry (the raw key is never stored). Disable if even a hash of the key is unwanted. |
| `bootui.kafka.max-entries`           | `200`   | Maximum number of captured Kafka messages retained in the in-memory ring buffer.                               |
| `bootui.kafka.max-key-length`        | `16`    | Maximum retained hex characters from the key hash (minimum `8`, maximum `64`).                                 |

#### Live Activity JMS capture

On Spring MVC and WebFlux, `spring-jms` activates metadata-only capture for application-owned `JmsTemplate` and
`@JmsListener` container factories. JMS has its own bounded recorder and settings, independent from Kafka and RabbitMQ,
so one transport's traffic cannot evict another transport's history. Destination names are control-character-stripped,
length-bounded, and credential-like URI/user-info or `password`/`secret`/`token`/API-key assignments are masked before
storage. Unknown provider-specific `Destination` implementations are not rendered because their `toString()` output can
expose broker metadata. Raw message IDs, payloads, arbitrary headers/properties, and exception messages are never stored;
when a `MessageCreator` or `MessagePostProcessor` exposes the provider-assigned message ID, only its SHA-256 hash is
retained. Quarkus does not claim a JMS capture integration.

| Property                                   | Default | Description                                                                                                           |
| ------------------------------------------ | ------- | --------------------------------------------------------------------------------------------------------------------- |
| `bootui.jms.enabled`                       | `true`  | Capture Spring-managed JMS publish/consume activity for the JMS panel and Live Activity when `spring-jms` and the JMS API are present. |
| `bootui.jms.capture-message-id`            | `true`  | Retain a truncated SHA-256 hash of the provider-assigned message ID; the raw ID is never stored.                      |
| `bootui.jms.max-entries`                   | `200`   | Maximum captured JMS messages retained in the independent bounded in-memory buffer.                                  |
| `bootui.jms.max-message-id-length`         | `16`    | Maximum retained hex characters from the message-ID hash (minimum `8`, maximum `64`).                                |

#### Live Activity RabbitMQ capture

When RabbitMQ support is present, BootUI captures publish/consume activity as `MESSAGING` entries. Spring composes with
application-owned `RabbitTemplate` before-publish processors and listener-factory advice; Quarkus uses SmallRye Reactive
Messaging RabbitMQ interceptors. Message bodies and arbitrary headers are never captured. Routing metadata is bounded,
correlation IDs are omitted by default and stored only as a SHA-256 hash when explicitly enabled, and failure details are
generic so exception messages cannot leak payload or credential data. On Quarkus, producer exchange, consumer queue, and
producer duration are unavailable because SmallRye's callbacks do not expose them; outgoing capture also requires
`OutgoingRabbitMQMetadata` to be attached before the interceptor.

| Property                                         | Default | Description |
| ------------------------------------------------ | ------- | ----------- |
| `bootui.rabbitmq.enabled`                        | `true`  | Capture RabbitMQ publish/consume activity when the framework integration is present. |
| `bootui.rabbitmq.capture-correlation-id`         | `false` | Capture a SHA-256 hash of the AMQP correlation ID; the raw value is never retained. |
| `bootui.rabbitmq.max-entries`                    | `200`   | Maximum captured messages retained in the bounded in-memory buffer. |
| `bootui.rabbitmq.max-correlation-id-length`      | `16`    | Maximum retained hex characters from the correlation-ID hash (minimum `8`, maximum `64`). |

#### Live Activity durable persistence

Off by default: the merged stream stays in-memory-only, exactly as above. Setting
`bootui.activity.persistence.enabled=true` additionally buffers captured entries and flushes them to a SQL database
over direct JDBC, so history survives a restart and the dashboard can page back further than fits in memory. Available
on both adapters with an identical config surface and wire contract; on Quarkus a `QuarkusActivityCapture` CDI bean
(`@Observes StartupEvent`/`ShutdownEvent`) owns the journal capture's lifecycle instead of Spring's controller-inline
wiring. Durable history is written by the runtime journal's subscriber, whatever source the feed reads; with the
journal disabled, persistence logs a warning and writes nothing. See [SPECIFICATION.md §5.14.2](./SPECIFICATION.md) for the full design (the `ActivityStore` abstraction,
buffering/flush, merge-for-reads, re-queue-on-failure, the flush guard, and multi-tenancy).

| Property                                                | Default            | Description                                                                                                                        |
| -------------------------------------------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.activity.persistence.enabled`                   | `false`             | Enable durable persistence for captured Live Activity entries, in addition to the in-memory default.                             |
| `bootui.activity.persistence.data-source-mode`          | `SHARED`            | `SHARED` reuses the host application's own `DataSource` bean; `DEDICATED` opens a small, non-pooled connection of BootUI's own using the `dedicated-*` properties below. |
| `bootui.activity.persistence.dedicated-jdbc-url`        | _(none)_            | JDBC URL used when `data-source-mode=DEDICATED`; ignored otherwise.                                                               |
| `bootui.activity.persistence.dedicated-username`        | _(none)_            | Username used when `data-source-mode=DEDICATED`; ignored otherwise.                                                               |
| `bootui.activity.persistence.dedicated-password`        | _(none)_            | Password used when `data-source-mode=DEDICATED`; ignored otherwise.                                                               |
| `bootui.activity.persistence.dedicated-driver-class-name` | _(none)_          | Optional explicit JDBC driver class for `data-source-mode=DEDICATED`; blank lets a modern JDBC 4+ driver auto-register itself.    |
| `bootui.activity.persistence.table-name`                | `bootui_activity`   | Table name every BootUI instance pointed at the same database shares. Created automatically on first use if absent.               |
| `bootui.activity.persistence.flush-interval`            | `5s`                | How often buffered entries are flushed to durable storage.                                                                        |
| `bootui.activity.persistence.buffer-max-entries`        | `500`               | Capacity of both the in-memory hot read cache (entries visible before their scheduled flush) and the pending-flush queue.         |
| `bootui.activity.persistence.retention`                 | `7d`                | How long persisted rows are kept before this instance prunes its own rows older than this on a periodic pass.                     |
| `bootui.activity.persistence.instance-id`               | _(auto)_            | Multi-tenant partition key this instance writes/reads its rows under. Defaults to the `HOSTNAME` environment variable, or else a generated `<app-name>-<random>` id. |

### Runtime journal

BootUI 2.0's runtime journal records every runtime event once, in a bounded in-memory structure, and keeps running
aggregates per route, statement, exception group, transactional method, and thread family
([PLAN-v2.md](PLAN-v2.md) §5.2). Recording never blocks a request: events wait in a bounded queue for one BootUI
daemon thread, and an event the queue cannot take is dropped and counted. HTTP requests, SQL statements, exception
occurrences, security events, REST client calls, cache accesses, messages, scheduled runs, transactions (Spring),
logical database connections (how long each was waited for and held), application `WARN` and `ERROR` log events,
outgoing emails (their recipient and attachment counts only), fault-tolerance outcomes, AI operations (`ai`: operation, provider, model, tokens, and finish reason, never prompts or answers, from
Spring AI's model observation or Quarkus LangChain4j's chat listener, stamped with their request, and otherwise from
recognized GenAI spans linked to their request by trace id), garbage collections (`gc`), authorization decisions (`authorization`: what was checked, the rule when Spring Security
names it, whether the caller was anonymous or authenticated, whether access was granted, and a count of authorities,
from Spring Security's authorization observations or Quarkus's authorization events), and the run's start (`lifecycle`: the time to ready and the slowest
bean instantiations on Spring, and the facts that decide whether two runs can be compared: active profiles, each data
source's URL shape such as `jdbc:postgresql://localhost` without credentials, database, or parameters, the cache in
use, whether tracing is on, and the recorded sources; then markers for BootUI's own successful actions, naming the panel, method, and path
without query or body, availability changes once the application is ready, Spring Cloud configuration refreshes with the
changed key names, and shutdown), and application events (`app-event`: each event type published and each listener's run,
with its transaction phase and whether it ran, was deferred, failed, or was skipped for lack of a transaction, never the
event's fields; framework events are left out), and WebSocket messages (`websocket`: each inbound application message a
handler ran, as an execution of its own, with its endpoint, its destination as the mapping's template such as
`/app/chat/{room}`, its size when known, and whether its handler failed, never its content, headers, or session), and Hibernate sessions
(`orm`: per session, its statements and their time, connection acquisitions, full flushes and the auto-flushes that wrote
before a query, with Hibernate's own flush time, the most entities its persistence context held at a flush, and
second-level cache hits, misses, and puts, never an entity, a parameter, or a statement) are recorded. On Spring, BootUI
names its session listener in `spring.jpa.properties.hibernate.session.events.auto`; on Quarkus, in each persistence unit's
`unsupported-properties."hibernate.session.events.auto"`, such as
`quarkus.hibernate-orm.unsupported-properties."hibernate.session.events.auto"` for the default unit, in dev and test mode
only, which Quarkus reports at startup as an unsupported property. Either way, an application's own listener wins and the source
then records nothing. The `resources` source measures each request's CPU time, allocated bytes, and
the collections that completed while it ran, summed over every thread its work ran on ([PLAN-v2.md](PLAN-v2.md)
§5.11). The JVM does not measure virtual threads, so a request served on one reports its CPU time and allocated bytes
as unavailable or partial, never as zero. Payloads hold no bind values, keys, message bodies, exception or log messages, or principals: a log event keeps its unformatted
template only. Nothing is written to disk unless `bootui.runtime-journal.baseline-file` is set, and that file holds a run
summary only: route templates, statement fingerprints, call sites, exception-group ids, thread families, observed
edges, counts, histograms, and, with the BootUI agent, the run's side-effect keys (hosts and ports, masked file
patterns, process file names, and variable names), never principals, literals, SQL text, values, arguments, or file
contents. The same keys and defaults apply on
Spring and Quarkus.

| Property                               | Default                                  | Description |
| -------------------------------------- | ---------------------------------------- | ----------- |
| `bootui.runtime-journal.enabled`       | `true`                                   | Record runtime events in the journal. |
| `bootui.runtime-journal.max-events`    | `50000`                                  | Maximum number of events retained as evidence. A tenth of it is kept for failed and slow events. The aggregates count every event, retained or not. |
| `bootui.runtime-journal.max-bytes`     | The smaller of 32 MB and 5 % of the heap | Maximum memory the retained events may use, estimated per event, such as `16MB`. Whichever bound is reached first evicts the oldest routine events. |
| `bootui.runtime-journal.agent-evidence-max-bytes` | About 68 MB, the sum of the stores' fixed caps | Maximum memory the [BootUI agent](features/java-agent.md)'s evidence kept outside the journal may use, estimated: Code Paths' request and route trees and method probes, Code Inventory's first calls, and Side Effects rows and waiting records, reported beside the journal's bytes in its status. A smaller value, such as `16MB`, shrinks Code Paths' trees and Side Effects' rows and waiting records in proportion, never below 5 % of their caps, so the status reports the bound the stores actually hold to, which a very small value cannot go below; Code Inventory's first calls, bounded by the agent's method limit (about 9 MB with their loads and routes), are only counted. Side Effects contributes `sideEffectRows` and `sideEffectsWaiting`, about 5.3 MB at the default cap. The method names each store keeps beside its evidence are reported apart and kept through a clear. |
| `bootui.runtime-journal.queue-capacity` | `10000`                                 | Maximum number of events waiting to be recorded. The last 10 % admits only failed or slow events, so a burst drops routine events first. |
| `bootui.runtime-journal.sources`       | Every source                             | Comma-separated sources to record: `http`, `sql`, `transaction`, `connection`, `exception`, `security`, `authorization`, `rest-client`, `cache`, `messaging`, `scheduled`, `log`, `mail`, `fault-tolerance`, `ai`, `lifecycle`, `gc`, `resources`, `app-event`, `websocket`, `orm`, `agent.executors` (the tasks the BootUI agent propagated, recorded only when it is attached), and `agent.caught-exceptions` (the exceptions application code caught, recorded only by the agent's opt-in `caught-exceptions` sensor). An unknown name fails startup. |
| `bootui.runtime-journal.baseline-file` | Unset                                    | File that keeps the last run's summary across a full JVM restart, such as `target/bootui-baseline.bin` or `build/bootui-baseline.bin`. Written atomically when a run ends, and read back at the next start as the previous run when the JVM keeps none; a file from another BootUI version, format, or application is ignored and the reason logged. With the BootUI agent, it also holds the run's side-effect keys for the comparison: host and port, masked file pattern, process file name, or variable name, with its route, job, or startup, never a value, an argument, or a file's contents. Relative to the working directory; its directory must exist, as it is never created. Unset writes and reads nothing. |
| `bootui.runtime-insights.ai-token-threshold` | `8000`                                | Tokens of one model call above which Runtime Insights' AI usage by route reports the route from that call alone, rather than from three AI operations. Must be positive. |

Recording a source is not enough to see it: each source belongs to the panel that publishes it, and disabling that panel
leaves its events out of Live Activity, request profiles, Runtime Insights, and the MCP tools and CLI commands over them,
with the panel named as the reason. `http` belongs to HTTP Exchanges, `sql` and `connection` to SQL Trace, `transaction`
to Transactions, `exception` to Exceptions, `security` and `authorization` to Security Logs, `rest-client` to REST Client
Trace, `cache` to Cache, `messaging` to its broker's panel (Kafka, RabbitMQ, or JMS, so disabling one broker leaves the
others), `scheduled` to Scheduled Tasks, `log` to Log Tail, `mail` to Email, `fault-tolerance` to Fault Tolerance, `ai`
to AI, `websocket` to WebSockets, `orm` to Hibernate, `agent.executors` to Java Agent, and `agent.caught-exceptions` to Exceptions. `lifecycle`, `gc`,
`resources`, and `app-event` belong to no panel and are always recorded when the journal is on.

### Resource correlation

While the runtime journal records the `resources` source, one BootUI daemon thread sweeps the JVM once per interval
([PLAN-v2.md](PLAN-v2.md) §5.11). Its CPU ledger splits the process's CPU time three ways: the share credited to
requests, the rest of each thread family's share (BootUI's own threads as one family), and the JVM's own work (GC,
JIT, VM threads, and threads the sweep did not read), which together sum to the process's CPU time. Its resource track
keeps heap use, heap after collections, allocation, and thread counts. The 900 most recent points are kept in memory,
outside the journal's byte bound.

| Property                           | Default | Description |
| ---------------------------------- | ------- | ----------- |
| `bootui.resources.sample-interval` | `1s`    | How often the sampler sweeps the JVM. At least `100ms`. |
| `bootui.resources.max-threads`     | `500`   | Most platform threads one sweep reads. The CPU time of the others counts as the JVM's own work. |
| `bootui.resources.jfr.max-duration` | `30s` | How long a Runtime Insights **Profile resources** JFR session records once the developer starts it. Between `1s` and `10m`. No session ever starts on its own. |

### Traces

| Property                                     | Default   | Description                                                                                            |
| -------------------------------------------- | --------- | ----------------------------------------------------------------------------------------------------- |
| `bootui.panels.traces.read-only`             | `false`   | Disable clearing retained traces. OTLP ingestion remains controlled by `bootui.telemetry.enabled`.    |
| `bootui.telemetry.enabled`                   | `true`    | Enables local in-memory trace capture and accepts OTLP/HTTP trace payloads at BootUI's OTLP endpoint. |
| `bootui.telemetry.max-traces`                | `500`     | Maximum distinct traces retained in memory.                                                           |
| `bootui.telemetry.max-spans-per-trace`       | `500`     | Maximum spans retained per trace.                                                                     |
| `bootui.telemetry.max-attribute-value-bytes` | `4096`    | Maximum attribute string length before truncation.                                                    |
| `bootui.telemetry.exclude-self-spans`        | `true`    | Drop ingested spans whose route/path targets BootUI before they enter the local trace store.          |
| `bootui.telemetry.enrich`                    | `true`    | Stamp BootUI `bootui.*` span attributes (service identity, SQL query count / suspected N+1, exceptions) on the active span at BootUI's capture points. Effective only while `bootui.telemetry.enabled` is on. |
| `bootui.telemetry.max-request-bytes`         | `8388608` | Maximum accepted OTLP request body size.                                                              |

### HTTP Exchanges

| Property                                     | Default | Description                                                                                     |
| -------------------------------------------- | ------- | ----------------------------------------------------------------------------------------------- |
| `bootui.panels.http-exchanges.enabled`       | `true`  | Show recent inbound HTTP exchanges and create a bounded in-memory recorder when none exists.    |
| `bootui.http-exchanges.max-exchanges`        | `200`   | Maximum recent HTTP exchanges retained in memory. Requires restart because it sizes the buffer. |
| `bootui.http-exchanges.reserved-share-percent` | `25`  | Percentage of `max-exchanges` reserved for the most recent `5xx` and slow exchanges (at or above `bootui.activity.request-slow-threshold-ms`), so routine requests are evicted first. `0` evicts strictly oldest first. Applies only while BootUI owns the recorder. On Spring, slow exchanges are reserved only while `management.httpexchanges.recording.include` records `time-taken`, which it does by default. Requires restart. |
| `management.httpexchanges.recording.enabled` | `true`  | Spring Boot recorder switch. Set to `false` to disable capture while leaving the panel visible. |

### HTTP Probe

| Property                             | Default | Description                                    |
| ------------------------------------ | ------- | ---------------------------------------------- |
| `bootui.panels.http-probe.enabled`   | `true`  | Show the HTTP Probe panel.                     |
| `bootui.panels.http-probe.read-only` | `false` | Disable sending probe requests through BootUI. |

### Email

| Property                        | Default | Description                                                                                                          |
| -------------------------------- | ------- | --------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.email.enabled`     | `true`  | Show the Email Viewer panel when a supported mail sender is present (`JavaMailSender` on Spring or `quarkus-mailer` on Quarkus). |
| `bootui.panels.email.read-only`   | `false` | Disable the clear action while keeping captured messages visible.                                                     |
| `bootui.email.max-entries`        | `100`   | Maximum number of captured messages retained; the oldest is evicted once full.                                        |
| `bootui.email.max-body-length`    | `200000` | Maximum number of characters retained per captured text/HTML body; a longer body is truncated at capture time so one oversized message cannot spike memory before `max-entries` would evict it. |
| `bootui.email.dev-trap`           | `false` | On Spring, when `true`, captured messages are recorded but never actually handed to the real mail transport. On Quarkus, sent/not-sent instead reflects `quarkus.mailer.mock` because capture happens after send. |
| `bootui.email.mask-content`       | `false` | When `true`, mask recipients/subject/body (like Configuration's secret masking) unless `bootui.expose-values=FULL`. Email content is not a config secret, so BootUI reveals it by default; enable this for teams that route real customer PII through a shared dev environment. |

### Kafka

The Kafka panel is a dedicated, filterable view over the same producer/consumer capture that feeds Live Activity's
`MESSAGING` entries — see "Live Activity Kafka capture" above for the shared `bootui.kafka.*` capture properties
(`enabled`, `capture-key`, `max-entries`, `max-key-length`), which tune both surfaces identically.

| Property                       | Default | Description                                                                                                                                        |
| -------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.kafka.enabled`   | `true`  | Show the Kafka panel when a Kafka integration is present (`KafkaTemplate` on Spring, or `quarkus-messaging-kafka` in a non-production Quarkus launch). Configured channels determine whether activity is captured, not panel availability. |
| `bootui.panels.kafka.read-only` | `false` | Disable the clear action while keeping captured messages visible.                                                                                  |

### RabbitMQ

The RabbitMQ panel is a dedicated view over the same bounded capture that feeds Live Activity. On Spring it is available
when a `RabbitTemplate` bean exists; on Quarkus it is available when `quarkus-messaging-rabbitmq` is present in dev/test
mode. The `bootui.rabbitmq.*` properties above tune both surfaces.

| Property                          | Default | Description |
| --------------------------------- | ------- | ----------- |
| `bootui.panels.rabbitmq.enabled`  | `true`  | Show the RabbitMQ panel when the adapter detects RabbitMQ support. |
| `bootui.panels.rabbitmq.read-only`| `false` | Disable the clear action while keeping captured messages visible. |

### Fault Tolerance

The Fault Tolerance panel reads protective policies live from Resilience4j registries, Spring Retry `@Retryable`
metadata, and SmallRye Fault Tolerance annotations captured at build time on Quarkus. It is strictly capture-only: BootUI
never opens, closes, resets, or otherwise mutates a circuit breaker, retry, rate limiter, bulkhead or time limiter.
Captured events are metadata only — policy name, outcome, attempt number, duration, exception simple name, and circuit
breaker state — never method arguments, return values or exception messages.

| Property                                 | Default | Description                                                                                                                      |
| ---------------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.fault-tolerance.enabled`  | `true`  | Show the Fault Tolerance panel when a supported fault tolerance library is present.                                              |
| `bootui.fault-tolerance.enabled`         | `true`  | Capture bounded fault tolerance events (retries, rejections, timeouts, short circuits, and circuit breaker state transitions) for the panel and Live Activity. Setting it to `false` keeps the live policy inventory but stops recording events. |
| `bootui.fault-tolerance.max-events`      | `200`   | Maximum captured fault tolerance events retained in the bounded in-memory buffer (hard-capped at `2000`).                        |

### JMS

The JMS panel is a dedicated view over the same bounded Spring JMS capture that feeds Live Activity. The
`bootui.jms.*` properties above tune both surfaces. Quarkus reports this panel not yet available.

| Property                         | Default | Description |
| -------------------------------- | ------- | ----------- |
| `bootui.panels.jms.enabled`      | `true`  | Show the JMS panel when a `JmsTemplate` bean is available. |
| `bootui.panels.jms.read-only`    | `false` | Disable the clear action while keeping captured messages visible. |

### Exceptions

| Property                                    | Default | Description                                                                                                |
| ------------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------- |
| `bootui.panels.exceptions.enabled`          | `true`  | Show the Exceptions panel and its captured exception groups.                                               |
| `bootui.panels.exceptions.read-only`        | `false` | Disable the clear action while keeping captured exceptions visible.                                        |
| `bootui.exceptions.max-groups`              | `100`   | Maximum number of distinct exception groups retained. The group with the oldest most-recent occurrence is evicted first. |
| `bootui.exceptions.max-occurrences-per-group` | `25`  | Maximum number of recent occurrences retained per exception group.                                         |
| `bootui.exceptions.max-stack-frames`        | `50`    | Maximum number of stack-trace frames retained per exception (and per cause).                               |

### Log Tail

| Property                         | Default | Description                                                                                                                      |
| -------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.log-tail.enabled` | `true`  | Show the Log Tail panel and its live log stream.                                                                                 |
| `bootui.log-tail.max-bytes`      | `0`     | Approximate retained-byte budget for the in-memory log-tail ring buffer, bounding it alongside its fixed 500-line cap (oldest evicted first). `0` (the default) means unbounded. |

### Vulnerabilities

| Property                                  | Default | Description                                             |
| ----------------------------------------- | ------- | ------------------------------------------------------- |
| `bootui.panels.vulnerabilities.enabled`   | `true`  | Show dependency inventory and local scan results.       |
| `bootui.panels.vulnerabilities.read-only` | `false` | Disable on-demand OSV scan requests.                    |
| `bootui.vulnerabilities.osv-enabled`         | `true`  | Additional action gate for OSV.dev scans.               |
| `bootui.vulnerabilities.request-timeout`     | `10s`   | Timeout for each OSV request.                           |
| `bootui.vulnerabilities.max-packages`        | `500`   | Maximum packages included in one OSV batch query; the excess is reported as `scan.packagesSkipped`. |
| `bootui.vulnerabilities.max-advisories`      | `200`   | Maximum advisory details fetched after a package query. |
| `bootui.vulnerabilities.osv-base-uri`        | `https://api.osv.dev` | Base URI of the OSV.dev API queried during a scan. Mainly useful for pointing scans at a local stub in tests. |
| `bootui.vulnerabilities.epss-enabled`        | `true`  | Enrich CVE-aliased advisories with FIRST.org EPSS probability and percentile data during the user-initiated scan. EPSS failure never discards OSV results. |
| `bootui.vulnerabilities.epss-base-uri`       | `https://api.first.org` | Base URI of the FIRST.org EPSS API queried during a scan. Mainly useful for pointing scans at a local stub in tests. |

### Heap Dump

| Property                              | Default              | Description                                                                                                                                                                                 |
| ------------------------------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.heap-dump.enabled`     | `true`               | Show the Heap Dump panel when running on a HotSpot JVM.                                                                                                                                     |
| `bootui.panels.heap-dump.read-only`   | `false`              | Disable on-demand capture, analyze, and delete actions.                                                                                                                                     |
| `bootui.heap-dump.capture-enabled`    | `true`               | Additional action gate for capturing new heap dumps.                                                                                                                                        |
| `bootui.heap-dump.allow-raw-download` | `false`              | Allow downloading the raw `.hprof` file. Disabled by default because dumps contain plaintext secrets.                                                                                       |
| `bootui.heap-dump.output-dir`         | `.bootui/heap-dumps` | Directory where captured heap dumps are written.                                                                                                                                            |
| `bootui.heap-dump.max-dumps`          | `5`                  | Maximum number of heap dump files retained on disk. Oldest dumps are deleted first.                                                                                                         |
| `bootui.heap-dump.max-classes`        | `1000`               | Maximum number of classes retained in memory after a histogram analysis, ordered by retained bytes. Capping this prevents very large heaps from exhausting memory. Must be ≥ `top-classes`. |
| `bootui.heap-dump.top-classes`        | `25`                 | Number of top classes shown in the value-free class histogram.                                                                                                                              |

### Threads

| Property                          | Default | Description                                                     |
| --------------------------------- | ------- | --------------------------------------------------------------- |
| `bootui.panels.threads.enabled`   | `true`  | Show the Threads panel when a `ThreadMXBean` is available.      |
| `bootui.panels.threads.read-only` | `false` | Disable the confirmation-gated raw thread-dump download action. |

### Architecture

| Property                               | Default | Description                                                         |
| -------------------------------------- | ------- | ------------------------------------------------------------------- |
| `bootui.panels.architecture.enabled`   | `true`  | Show the ArchUnit architecture hygiene panel and its latest report. |
| `bootui.panels.architecture.read-only` | `false` | Disable the on-demand architecture scan action.                     |

### GraalVM

| Property                                  | Default | Description                                                                          |
| ----------------------------------------- | ------- | ------------------------------------------------------------------------------------ |
| `bootui.panels.graalvm.enabled`           | `true`  | Show the GraalVM native-image readiness panel and its latest report.                 |
| `bootui.panels.graalvm.read-only`         | `false` | Disable the on-demand readiness scan action (the metadata download stays available). |
| `bootui.graalvm.repository-lookup-enabled` | `true` | Allow the dependency survey to query Oracle's GraalVM reachability-metadata repository. This is the panel's only outbound network call and runs only during a user-initiated scan. |
| `bootui.graalvm.repository-lookup-timeout` | `2s`   | Timeout applied to each reachability-metadata repository request.                    |
| `bootui.graalvm.max-repository-lookups`   | `500`   | Maximum number of distinct dependency coordinates looked up against the reachability-metadata repository in a single scan. |

### CRaC

| Property                       | Default | Description                                                                                                    |
| ------------------------------ | ------- | -------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.crac.enabled`   | `true`  | Show the CRaC (Coordinated Restore at Checkpoint) readiness panel and its latest report.                       |
| `bootui.panels.crac.read-only` | `false` | Disable the on-demand readiness scan and the Dockerfile/entrypoint install actions (downloads stay available). |

### Java agent

The Java Agent panel is view-only. On Spring, `bootui.agent.*` is read at startup, before the application context exists,
so a change applies at the next start or DevTools restart. On Quarkus these keys are build-time properties, read when the
application is built (augmented); dev mode rebuilds when they change.

The main application package on Spring and the application archive packages on Quarkus are always included.
`bootui.agent.packages` only adds extra prefixes.

| Property                  | Default | Description |
| ------------------------- | ------- | ----------- |
| `bootui.agent.enabled`    | `true`  | Claim the BootUI agent when it is attached. When false, BootUI releases the claim and the agent removes its transformers, unless another application's armed claim holds it. |
| `bootui.agent.packages`   | empty   | Additional application package prefixes to include in the claim, alongside adapter-discovered packages. |
| `bootui.agent.mode`       | `auto`  | Claim mode: `auto`, `dev`, or `test`. `auto` chooses `test` under test frameworks / Quarkus test launch mode, otherwise `dev`. |
| `bootui.agent.sensors`    | `executors`, `inventory`, `code-paths`, `processes`, `network`, `files`, `environment`, `blocking` | The agent sensors this application asks for. `executors` propagates a request's correlation through the JDK's executors, so work handed to a raw thread pool or `CompletableFuture` is owned by its request. `inventory` records which application methods ran in this run and which jars and class directories loaded classes. `code-paths` times the application's bean methods per request, as call trees. `processes` records process starts for Side Effects: sanitized command name only, never arguments or environment. `network` records connects, datagram sends, and the names the JVM resolves for Side Effects: a host and port and the recognized client, never a byte sent or received. `blocking` records `Thread.sleep`, `Object.wait`, `LockSupport.park`, and the network and files sensors' blocking operations started on an event loop, for Side Effects. `files` records the files application code opens, deletes, moves, and copies for Side Effects, as path patterns, never contents. `threads`, opt-in, also propagates a request's correlation into threads started from application code and into virtual threads. `environment` records the names of the environment variables and system properties application code reads directly, never their values; it advises `System.getProperty`, about 23–28 ns per read instead of 5–6 ns. `thread-activity`, opt-in, records the threads application code starts and the executors it creates per route, and those a request left running when it ended, never what a thread holds; it is distinct from `threads`. `thread-locals`, opt-in, reports the thread locals a request or a job left set on its pooled platform thread, found by scanning the thread's thread-local maps when its scope closes, named by the static field that holds them, never their values. `threads`, `files`, `environment`, `thread-activity`, and `thread-locals` can also be switched on and off at run time from the Java Agent and Side Effects panels (`POST {api}/java-agent/sensors/{id}`): a runtime override, never written to a file, kept across DevTools restarts and Quarkus live reloads, forgotten when the JVM ends, and dropped once this property agrees with it. An empty list asks for none. `security-sinks`, opt-in, checks with `bootui.agent.security-sinks.request-values` whether request input reaches SQL text, a command, a file path, or an outbound URL unchanged. The Side Effects sensors this version does not ship (`resources`) are accepted with a warning, and the Side Effects panel reports them not available; any other id fails the application's start, on Spring and Quarkus alike, while the BootUI agent is attached, with a message naming the accepted ids. `caught-exceptions`, opt-in, reports the exceptions application code catches and which of them are thrown again, with their handler and owner, never their message, to the runtime journal's `agent.caught-exceptions` source. |
| `bootui.agent.executors.skip-tasks` | `io.github.jdubois.bootui.engine.correlation.ManagedTasks`, `io.micrometer.context.`, `org.springframework.core.task.support.ContextPropagatingTaskDecorator`, `jdk.internal.`, `sun.`, `java.lang.ProcessHandleImpl`, `com.zaxxer.hikari.`, `com.github.benmanes.caffeine.` | Task class-name prefixes the executors sensor never propagates, because they already carry their context or belong to the JDK (its process reaper included), the connection pool, or the cache. Setting it replaces the defaults. |
| `bootui.agent.executors.skip-threads` | `vert.x-`, `bootui-` | Worker thread-name prefixes the executors sensor never propagates to. On Spring, Reactor's `parallel-`, `boundedElastic-`, and `single-` are added when Reactor's automatic context propagation is on (`spring.reactor.context-propagation=auto`), since it carries BootUI's context itself. Setting it replaces the defaults. |
| `bootui.agent.security-sinks.request-values` | `false` | With the `security-sinks` sensor, holds the current request's query and path parameter values of 4 to 256 characters (at most 32) while it runs, so that SQL text, a command, a file path, or an outbound URL it reaches can be checked for one appearing verbatim: a Security sinks row of Side Effects names the parameter and the redacted sink, never the value. Values are compared, never stored, logged, or displayed, and forgotten when the response completes. Form values, headers, and bodies are never read. Opt-in. |
| `bootui.agent.ring-capacity` | `65536` | The records the agent's transport ring holds before it drops new ones (64 bytes each), clamped to 1,024–4,194,304 and rounded up to a power of two. The first claim in a JVM sizes the ring, which then lasts for the JVM's life. |
| `bootui.agent.executors.max-handoff` | `5m` | The handoff window of a propagated task, counted from its start: a task belongs to the request that handed it over when it starts no later than this after the request ended (a later one is only counted in the request profile), its work recorded more than this after it started is not attributed, and a task running longer is published `capped`. |

### Code Inventory

The Code Inventory panel is view-only and needs the BootUI agent's `inventory` sensor. Once per run, off the request
path, BootUI scans the application's own class files in the claimed packages and hashes their methods; these keys bound
that scan. On Spring they are read when the context starts, on Quarkus at runtime.

| Property                              | Default  | Description |
| ------------------------------------- | -------- | ----------- |
| `bootui.code-inventory.max-classes`   | `20000`  | The most application classes the scan hashes. Past it the scan is partial and says so, and classes it did not reach are neither counted nor compared with the previous run. Must be positive. |
| `bootui.code-inventory.scan-timeout`  | `30s`    | The scan's deadline. Past it the scan is partial and says so. Must be positive. |

### Code Paths

The Code Paths panel needs the BootUI agent's `code-paths` sensor (`bootui.agent.sensors`) and has no property of its
own: its route trees are bounded in code, at 2,000 nodes a route, 100,000 nodes and 500 routes a run, and its method
probes at 20 invocations, 60 seconds, and five probes at once. Method probes are its only actions:
`bootui.panels.code-paths.read-only=true`, or the global `bootui.read-only=true`, refuses starting and stopping them,
in the browser, the API, MCP, and the CLI. Disabling it with `bootui.panels.code-paths.enabled=false` also stops
`route-time-breakdown` from splitting the handler by method.

### Side Effects

The Side Effects panel is view-only, needs the BootUI agent's side-effect bridge and its sensors (`processes`,
`network`, `files`, `environment`, and `blocking` by default, `thread-activity`, `thread-locals`, and `security-sinks`
opt-in, in `bootui.agent.sensors`), and has no property of its own. `bootui.panels.side-effects.enabled=false` hides the panel and
rejects its reads.

In this version `processes`, `network`, `files`, `environment`, `blocking`, and the opt-in `thread-activity`,
`thread-locals`, and `security-sinks` record; `security-sinks` also needs
`bootui.agent.security-sinks.request-values=true` to hold the request values it matches. Only the `resources` sensor
id in the panel reports `not-available` with reason `Not available in this version.` `blocking` is `not-applicable` on
Spring MVC until a WebClient's event loop is registered. A network target is a host string and port, never
resolved, without user information, with characters other than letters, digits, and `. _ - : [ ] / ~` as `?`, at most
128 characters, and at most 1,024 distinct ones a run; a looked-up name is the host name alone. A file row shows a path
pattern (`./` for the working directory, `$TMPDIR`, `~`, ids and digits collapsed), never contents; an environment row
shows a name, never a value. The process target is the sanitized command name only: for a process that started,
the file name of its executable after the last path separator; for a failed start, the first command element up to
whitespace or `=`, then after the last path separator; an element that opens with a quote, as Windows starts
`"C:\Tools\app.exe" --token x`, keeps only its quoted text, and nothing past a quote inside an element is kept. Only
letters, digits, `.`, `_`, `+`, and `-` are kept; other characters become `?`, and the result is at most 128
characters. Arguments and environment variables are never recorded.
While HTTP Exchanges is disabled, route-attributed rows merge under `(route hidden: HTTP Exchanges is disabled)` and do
not expose request ids. While Code Paths is disabled, rows lose their **inside** bean method and merge without it. Clear
recording and Free BootUI memory clear Side Effects rows through the agent evidence contract, and journal status counts
`sideEffectRows` and `sideEffectsWaiting` under **Agent evidence**.

### Spring DevTools

| Property                           | Default | Description                                                         |
| ---------------------------------- | ------- | ------------------------------------------------------------------- |
| `bootui.panels.devtools.enabled`   | `true`  | Show Spring Boot DevTools status when DevTools is on the classpath. |
| `bootui.panels.devtools.read-only` | `false` | Disable LiveReload trigger and application restart actions.         |

### Dev Services

| Property                               | Default | Description                                                                                     |
| -------------------------------------- | ------- | ----------------------------------------------------------------------------------------------- |
| `bootui.panels.dev-services.enabled`   | `true`  | Show Docker Compose snapshots, Testcontainers beans, and service connection metadata.           |
| `bootui.panels.dev-services.read-only` | `false` | Disable service restart actions. Bounded log reads remain available.                            |
| `bootui.dev-services.restart-enabled`  | `false` | Additional action gate for restarting bean-backed Testcontainers services. Disabled by default. |
| `bootui.dev-services.log-tail-bytes`   | `65536` | Maximum bytes returned by a single Dev Services log request.                                    |

### Copilot

| Property                                | Default                    | Description                                                                                                            |
| --------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.copilot.enabled`         | `true`                     | Show the Copilot panel in the sidebar.                                                                                 |
| `bootui.copilot.enabled`                | `AUTO`                     | Activate the Copilot integration. `AUTO` enables it only when the session-state directory exists; `ON`/`OFF` force it. |
| `bootui.copilot.session-state-dir`      | `~/.copilot/session-state` | Directory scanned for Copilot CLI sessions.                                                                            |
| `bootui.copilot.max-events-per-session` | `2000`                     | Maximum Copilot events retained per parsed session.                                                                    |
| `bootui.copilot.max-sessions`           | `100`                      | Maximum recent Copilot sessions returned by the explorer.                                                              |
| `bootui.copilot.max-parsed-sessions`    | `100`                      | Maximum recent Copilot session files parsed and retained in memory.                                                    |
| `bootui.copilot.stream-debounce`        | `400ms`                    | Debounce window before refreshing parsed Copilot sessions and notifying stream subscribers.                            |
| `bootui.copilot.allow-raw-reveal`       | `true`                     | Allow explicit raw event reveal when value exposure is not `METADATA_ONLY`.                                            |

### Claude Code

| Property                                    | Default              | Description                                                                                                              |
| ------------------------------------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `bootui.panels.claude-code.enabled`         | `true`               | Show the Claude Code panel in the sidebar.                                                                               |
| `bootui.claude-code.enabled`                | `AUTO`               | Activate the Claude Code integration. `AUTO` enables it only when the project log directory exists; `ON`/`OFF` force it. |
| `bootui.claude-code.session-state-dir`      | `~/.claude/projects` | Directory scanned for Claude Code project JSONL logs.                                                                    |
| `bootui.claude-code.max-events-per-session` | `2000`               | Maximum Claude Code events retained per parsed session.                                                                  |
| `bootui.claude-code.max-sessions`           | `100`                | Maximum recent Claude Code sessions returned by the explorer.                                                            |
| `bootui.claude-code.max-parsed-sessions`    | `100`                | Maximum recent Claude Code JSONL files parsed and retained in memory.                                                    |
| `bootui.claude-code.stream-debounce`        | `400ms`              | Debounce window before refreshing parsed Claude Code sessions and notifying stream subscribers.                          |
| `bootui.claude-code.allow-raw-reveal`       | `false`              | Allow explicit raw Claude Code JSONL reveal; disabled by default because logs can include prompts and outputs.           |

### MCP server

The MCP server exposes BootUI's advisors and read-only diagnostics to local AI agents (GitHub Copilot, Claude Code) over
a loopback-only Model Context Protocol endpoint at `POST <bootui.api-path>/mcp` (default
`POST /bootui/api/mcp`). It is **off by default** and only ever active
while BootUI itself is active: absent from a Quarkus production build, and disabled by the Spring `prod` and
`production` profiles unless an explicit `bootui.enabled=ON` overrides them. Tools inherit the same safety model as
the panels:
read tools require the backing panel to be enabled, action (`*_scan`) tools are additionally refused when the panel is
read-only, and all values flow through the same secret masking as the REST API.

| Property                       | Default   | Description                                                                                                                       |
| ------------------------------ | --------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.mcp.enabled`           | `OFF`     | Enable the local MCP server. `OFF` (default) and `AUTO` keep it disabled so it is never silently exposed; `ON` exposes the endpoint. |
| `bootui.mcp.max-results`       | `200`     | Maximum number of items returned by paginated read tools (config, beans, mappings, security logs, traces, HTTP exchanges) per call. |
| `bootui.mcp.max-payload-bytes` | `1048576` | Maximum size (in bytes) of an incoming JSON-RPC request body; larger requests are rejected before parsing. |
| `bootui.mcp.max-concurrent-calls` | `20`   | Maximum number of `tools/call` invocations the server executes concurrently; excess calls are refused with a rate-limited error. A progress stream holds its slot until its tool and its writer are both done. |
| `bootui.mcp.execution-timeout` | `30s`     | Maximum wall-clock duration of one tool invocation; timed-out calls are interrupted and return JSON-RPC `-32002` (`-31002` for MCP 2026-07-28 clients). It stays the absolute bound when a call streams progress. A scan that reports progress (`architecture_scan`, `vulnerabilities_scan`) stops at its next step and keeps its previous report. |
| `bootui.mcp.max-response-bytes` | `4194304` | Maximum size of a rendered JSON-RPC response, and of each event of a progress stream; oversized results are replaced by JSON-RPC `-32003` (`-31003` for MCP 2026-07-28 clients), and an oversized progress notification is dropped and counted in the MCP Server status's `progressDropped`. |

### Command-line endpoint

The command-line endpoint projects the same tool registry the MCP server exposes onto plain REST, so a terminal or a CI
job can ask a running application one diagnostic question without an MCP client. `GET <bootui.api-path>/cli` (default
`GET /bootui/api/cli`) describes the tools this instance advertises, and `POST <bootui.api-path>/cli/tools/{name}`
invokes one, returning its payload directly with the outcome in the HTTP status (`403` panel disabled or read-only,
`404` unknown tool, `400` bad argument, `409` action already running, `429` at capacity, `504` timeout).

It is **on by default**, because it is a different spelling of data the panel endpoints already serve rather than a new
capability: every tool remains gated by its panel's enable/read-only settings, and the endpoint sits under
`bootui.api-path`, so the loopback, Host allow-list, cross-site-write, and `bootui.authentication.token` protections
apply unchanged. It does **not** require `bootui.mcp.enabled`, and its calls are counted separately so the MCP Server
panel keeps reporting only what agents did.

| Property                          | Default | Description                                                                                                            |
| --------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------- |
| `bootui.cli.enabled`              | `true`  | Whether the command-line endpoint answers. When `false`, the catalog still reports itself as disabled and tool invocation returns `503`. |
| `bootui.cli.max-results`          | `200`   | Maximum number of items returned by paginated read tools per call, tracked separately from `bootui.mcp.max-results`.     |
| `bootui.cli.max-concurrent-calls` | `20`    | Maximum number of concurrent tool invocations; excess calls are refused with `429`.                                      |
| `bootui.cli.execution-timeout`    | `30s`   | Maximum wall-clock duration of one tool invocation; timed-out calls are interrupted and return `504`. A scan that reports progress stops at its next step and keeps its previous report. |



Make the whole application read-only:

```properties
bootui.read-only=true
```

Hide one panel entirely:

```properties
bootui.panels.devtools.enabled=false
```

Keep one panel visible but disable its actions:

```properties
bootui.panels.config.read-only=true
```

Require both an action gate and panel read-only state to allow an action:

```properties
bootui.panels.dev-services.read-only=false
bootui.dev-services.restart-enabled=true
```
