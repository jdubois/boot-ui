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
accepted). On Quarkus, each key is read **live, per request** through MicroProfile Config and must be
written in **exact kebab-case**; a missing or invalid value **fails closed** (for example, masking
stays on and non-loopback access stays denied). Most keys below are honored identically on both
adapters.

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
| `bootui.activity.max-entries`, `bootui.activity.n-plus-one-threshold`, `bootui.activity.request-slow-threshold-ms` | Spring only | Stream cap, N+1 detection threshold, and slow-request threshold apply only to Spring's richer tiered-correlation profiler; Quarkus's reduced trace-id-only profiler has no equivalent config. The optional durable-persistence backend (`bootui.activity.persistence.*`) is **shared** — see below. |
| `bootui.telemetry.max-request-bytes`                                         | Spring only                | Sizes the embedded OTLP receiver, which Quarkus does not run (it captures spans in-process).                                             |
| `bootui.internal.*`                                                          | **Quarkus only, internal** | Build-time facts (base packages, dependency inventory, capability-present flags) emitted by build steps. Not a user setting — never set by hand. |

### Keys with a shared name but platform-specific behavior

| Key                     | Spring                                                                                                              | Quarkus                                                                                                                            |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.overrides-file` | The Configuration panel persists runtime overrides here, and the key also locates the advisor dismissed-rules file. | The Configuration panel is read-only on Quarkus, so the key only locates the advisor dismissed-rules file (`.bootui/boot-ui.yml`). |

Everything not listed in the two tables above is honored under the same key — and with the same
default — on both adapters. This includes the safety keys (`bootui.allow-non-localhost`,
`bootui.allowed-hosts`, `bootui.trusted-proxies`, `bootui.trust-container-gateway`),
`bootui.expose-values`, `bootui.mask-secrets`, `bootui.path` / `bootui.api-path`,
`bootui.monitoring.exclude-self`, `bootui.http-exchanges.max-exchanges` (default `200`),
`bootui.log-tail.max-bytes` (default `0`, meaning unbounded), and the `bootui.github.*`,
`bootui.vulnerabilities.*` (including `osv-base-uri`, default `https://api.osv.dev`),
`bootui.sql-trace.*`, `bootui.telemetry.*` (except `max-request-bytes`), `bootui.heap-dump.*`,
`bootui.exceptions.*`, `bootui.security-logs.*`, `bootui.cache.*`, `bootui.mcp.*`, `bootui.ai.*`,
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
| `bootui.path`                    | `/bootui`                               | UI base path. `/bootui` is the supported route.                                                                                 |
| `bootui.api-path`                | `/bootui/api`                           | Internal API base path used by the UI and safety filters.                                                                       |
| `bootui.allow-non-localhost`     | `false`                                 | Explicitly opt out of loopback-only protection. Keep this `false` unless the local network is trusted.                          |
| `bootui.allowed-hosts`           | _(empty)_                               | Extra `Host` header values accepted by the loopback filter, in addition to the built-in loopback names (`localhost`, `127.0.0.1`, `::1`). Use this for custom local hostnames while keeping DNS-rebinding protection. |
| `bootui.trusted-proxies`         | _(empty)_                               | Source IP ranges in CIDR notation (e.g. `172.16.0.0/12` for the Linux Docker bridge, or `192.168.65.0/24` for the Docker Desktop gateway) trusted in addition to loopback. A narrow opt-in for local Docker-bridge callers: it relaxes only the source-address check while keeping the `Host` allow-list (DNS-rebinding) and cross-site write (CSRF) protections in force. Prefer this over `bootui.allow-non-localhost`, and pair it with `bootui.allowed-hosts` for the hostname the browser uses. |
| `bootui.trust-container-gateway` | `OFF`                                   | One-flag opt-in to trust the auto-detected container gateway as a single `/32`, so BootUI can be reached inside a container with a published port (host→container traffic is SNAT'd to the gateway) without knowing the subnet or setting a broad `bootui.trusted-proxies` CIDR. Detection works on both flavors: the bridge default gateway from `/proc/net/route` on Linux Docker Engine (e.g. `172.17.0.1`), and the `gateway.docker.internal` DNS name on Docker Desktop (`192.168.65.1`, which is _not_ the route-table gateway). `OFF` (default, fail closed) never trusts it; `AUTO` auto-detects and trusts the gateway only when running inside a container; `ON` trusts a detected gateway even if container heuristics are inconclusive. Relaxes only the source-address check — the `Host` allow-list (DNS-rebinding) and cross-site write (CSRF) protections stay in force. Note: with the common `-p 8080:8080` bind, LAN clients reaching the published port are also SNAT'd to the gateway; use `-p 127.0.0.1:8080:8080` for strict loopback equivalence. |
| `bootui.mask-secrets`            | `true`                                  | Enables secret-like value masking helpers.                                                                                      |
| `bootui.expose-values`           | `MASKED`                                | Configuration value exposure mode: `MASKED`, `METADATA_ONLY`, or `FULL`. `FULL` can disclose secrets.                           |
| `bootui.show-banner`             | `true`                                  | Print the BootUI URL on application startup.                                                                                    |
| `bootui.startup.enabled`         | `true`                                  | Install a `BufferingApplicationStartup` automatically while BootUI is active so the Startup Timeline panel has data.            |
| `bootui.startup.capacity`        | `4096`                                  | Maximum startup steps retained by BootUI's auto-installed startup buffer. Values less than or equal to zero disable the buffer. |
| `bootui.free-on-idle.enabled`    | `true`                                  | Release BootUI's live in-memory diagnostic buffers (captured SQL, ingested traces, and the request/security correlation windows) and pause recording into them after the console has been idle for `bootui.free-on-idle.timeout`, refilling them from live traffic once the console is used again. Dev-only (BootUI is inactive in production); the Exceptions and Log Tail buffers are always retained. Set to `false` to keep all buffers recording continuously. |
| `bootui.free-on-idle.timeout`    | `5m`                                    | How long the console may go without any BootUI request (UI load, API poll, or stream open) before its live buffers are released. The timer resets on every BootUI request, so an open console never reclaims. Clamped to a minimum of one second. |
| `bootui.read-only`               | `false`                                 | Disable every browser-triggered action while keeping read-only panel data visible.                                              |
| `bootui.overrides-file`          | `.bootui/application-bootui.properties` | File used by the Configuration panel to persist local runtime overrides.                                                        |
| `bootui.monitoring.exclude-self` | `true`                                  | Hide BootUI's own beans, mappings, loggers, metrics, traces, and related runtime data from monitoring panels.                   |

## Panel access settings

Enforced identically on Spring and Quarkus (`PanelAccessFilter` / `QuarkusPanelAccessFilter`).

| Group           | Panel                     | Panel id                    | Enable property                                   | Read-only property                        |
| --------------- | ------------------------- | --------------------------- | ------------------------------------------------- | ----------------------------------------- |
| Overview        | Overview                  | `overview`                  | `bootui.panels.overview.enabled`                  | Not applicable; view-only.                |
| Overview        | Live Activity             | `activity`                  | `bootui.panels.activity.enabled`                  | Not applicable; view-only.                |
| Overview        | GitHub                    | `github`                    | `bootui.panels.github.enabled`                    | `bootui.panels.github.read-only`          |
| Advisors        | Architecture              | `architecture`              | `bootui.panels.architecture.enabled`              | `bootui.panels.architecture.read-only`    |
| Advisors        | REST API                  | `rest-api`                  | `bootui.panels.rest-api.enabled`                  | `bootui.panels.rest-api.read-only`        |
| Advisors        | Spring                    | `spring`                    | `bootui.panels.spring.enabled`                    | `bootui.panels.spring.read-only`          |
| Advisors        | Hibernate                 | `hibernate`                 | `bootui.panels.hibernate.enabled`                 | `bootui.panels.hibernate.read-only`       |
| Advisors        | Memory                    | `memory`                    | `bootui.panels.memory.enabled`                    | `bootui.panels.memory.read-only`          |
| Advisors        | Security                  | `security`                  | `bootui.panels.security.enabled`                  | `bootui.panels.security.read-only`        |
| Advisors        | Pentesting                | `pentesting`                | `bootui.panels.pentesting.enabled`                | `bootui.panels.pentesting.read-only`      |
| Advisors        | Vulnerabilities           | `vulnerabilities`           | `bootui.panels.vulnerabilities.enabled`           | `bootui.panels.vulnerabilities.read-only` |
| Runtime         | Health                    | `health`                    | `bootui.panels.health.enabled`                    | Not applicable; view-only.                |
| Runtime         | HTTP Sessions             | `http-sessions`             | `bootui.panels.http-sessions.enabled`             | `bootui.panels.http-sessions.read-only`   |
| Runtime         | Metrics                   | `metrics`                   | `bootui.panels.metrics.enabled`                   | Not applicable; view-only.                |
| Runtime         | Live Memory               | `live-memory`               | `bootui.panels.live-memory.enabled`               | Not applicable; view-only.                |
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
| Database        | SQL Trace                 | `sql-trace`                 | `bootui.panels.sql-trace.enabled`                 | `bootui.panels.sql-trace.read-only`       |
| Database        | Spring Data               | `data`                      | `bootui.panels.data.enabled`                      | Not applicable; view-only.                |
| Database        | Flyway                    | `flyway`                    | `bootui.panels.flyway.enabled`                    | `bootui.panels.flyway.read-only`          |
| Database        | Liquibase                 | `liquibase`                 | `bootui.panels.liquibase.enabled`                 | `bootui.panels.liquibase.read-only`       |
| Security        | Spring Security           | `spring-security`           | `bootui.panels.spring-security.enabled`           | Not applicable; view-only.                |
| Security        | Security Logs             | `security-logs`             | `bootui.panels.security-logs.enabled`             | Not applicable; view-only.                |
| Services        | Scheduled Tasks           | `scheduled`                 | `bootui.panels.scheduled.enabled`                 | Not applicable; view-only.                |
| Services        | Cache                     | `cache`                     | `bootui.panels.cache.enabled`                     | `bootui.panels.cache.read-only`           |
| Services        | AI Usage                  | `ai`                        | `bootui.panels.ai.enabled`                        | Not applicable; view-only.                |
| Diagnostics     | Traces                    | `traces`                    | `bootui.panels.traces.enabled`                    | `bootui.panels.traces.read-only`          |
| Diagnostics     | Log Tail                  | `log-tail`                  | `bootui.panels.log-tail.enabled`                  | Not applicable; view-only.                |
| Diagnostics     | Exceptions                | `exceptions`                | `bootui.panels.exceptions.enabled`                | `bootui.panels.exceptions.read-only`      |
| Diagnostics     | HTTP Exchanges            | `http-exchanges`            | `bootui.panels.http-exchanges.enabled`            | Not applicable; view-only.                |
| Diagnostics     | HTTP Probe                | `http-probe`                | `bootui.panels.http-probe.enabled`                | `bootui.panels.http-probe.read-only`      |
| Developer tools | MCP Server                | `mcp-server`                | `bootui.panels.mcp-server.enabled`                | `bootui.panels.mcp-server.read-only`      |
| Developer tools | DevTools                  | `devtools`                  | `bootui.panels.devtools.enabled`                  | `bootui.panels.devtools.read-only`        |
| Developer tools | Dev Services              | `dev-services`              | `bootui.panels.dev-services.enabled`              | `bootui.panels.dev-services.read-only`    |
| Developer tools | Copilot                   | `copilot`                   | `bootui.panels.copilot.enabled`                   | Not applicable; view-only.                |
| Developer tools | Claude Code               | `claude-code`               | `bootui.panels.claude-code.enabled`               | Not applicable; view-only.                |

## Per-panel action details

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
| `bootui.overrides-file`          | `.bootui/application-bootui.properties` | Local file where runtime overrides are persisted.                      |
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

| Property                               | Default | Description                                                                                       |
| -------------------------------------- | ------- | ------------------------------------------------------------------------------------------------- |
| `bootui.panels.cache.enabled`          | `true`  | Show cache managers, caches, metrics, and cache annotations.                                      |
| `bootui.panels.cache.read-only`        | `false` | Disable cache clear actions.                                                                      |
| `bootui.cache.clear-enabled`           | `true`  | Additional action gate for cache clearing. Both this and the read-only state must allow clearing. |

### Hibernate

| Property                            | Default | Description                                                                       |
| ----------------------------------- | ------- | --------------------------------------------------------------------------------- |
| `bootui.panels.hibernate.enabled`   | `true`  | Show Hibernate/JPA mapping and configuration advisor findings.                    |
| `bootui.panels.hibernate.read-only` | `false` | Disable the explicit Hibernate Advisor scan action while keeping results visible. |

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
| `bootui.sql-trace.capture-parameters`     | `false` | Capture bound statement parameters alongside the SQL text. Off by default because values may be sensitive; metadata-only exposure suppresses them even when enabled. |
| `bootui.sql-trace.capture-call-site`      | `true`  | Capture the call site (class, method, line) in your own application code that triggered each statement, via a small, bounded stack walk. A call site carries no bound values, so — unlike parameter capture — it is not privacy-gated and defaults on; set `false` to skip the stack walk entirely. |
| `bootui.sql-trace.max-entries`            | `200`   | Maximum number of executed statements retained in the in-memory ring buffer.                                                                 |
| `bootui.sql-trace.slow-query-threshold-millis` | `100` | Executions at or above this many milliseconds are flagged as slow. Set to `0` to disable slow-query flagging.                              |
| `bootui.sql-trace.max-sql-length`         | `2000`  | Maximum retained SQL text length; longer statements are truncated.                                                                           |
| `bootui.sql-trace.max-parameter-length`   | `200`   | Maximum retained length of a single captured parameter value.                                                                                |
| `bootui.sql-trace.n-plus-one-threshold`   | `5`     | Number of times an identical `SELECT` must repeat within the buffer before it is flagged as a likely N+1 access pattern (minimum `2`).       |

### Live Activity

The Live Activity panel reuses the HTTP Exchanges, SQL Trace, Exceptions, and Security Logs sources, so disabling any of
those panels through their own `bootui.panels.*` toggles also removes them from the stream. The panel itself is
read-only. A request whose correlated SQL trips `bootui.activity.n-plus-one-threshold` is flagged with a red **N+1**
badge both in the main stream row and in its profile drawer (the same threshold, so the two views never disagree); the
drawer additionally lists the flagged group's call site(s) whenever `bootui.sql-trace.capture-call-site` is enabled.

| Property                                      | Default | Description                                                                                                       |
| ---------------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------- |
| `bootui.panels.activity.enabled`              | `true`  | Show the Live Activity panel (merged stream and per-request profiler).                                           |
| `bootui.activity.max-entries`                 | `200`   | Maximum number of merged stream entries returned per page after merging and sorting all sources.                 |
| `bootui.activity.request-slow-threshold-ms`   | `1000`  | Duration in milliseconds above which a request is flagged as slow in the stream and KPI strip.                   |
| `bootui.activity.n-plus-one-threshold`        | `5`     | Number of identical correlated `SELECT` statements above which a request is flagged with a potential N+1 pattern, both as a list-level badge and in its profile drawer. |

#### Live Activity durable persistence

Off by default: the merged stream stays in-memory-only, exactly as above. Setting
`bootui.activity.persistence.enabled=true` additionally buffers captured entries and flushes them to a SQL database
over direct JDBC, so history survives a restart and the dashboard can page back further than fits in memory. Available
on both adapters with an identical config surface and wire contract; on Quarkus a `QuarkusActivityCapture` CDI bean
(`@Observes StartupEvent`/`ShutdownEvent`) owns the capture-poller lifecycle instead of Spring's controller-inline
wiring. See [SPECIFICATION.md §5.14.2](./SPECIFICATION.md) for the full design (the `ActivityStore` abstraction,
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
| `bootui.activity.persistence.capture-interval`          | `2s`                | How often the capture coordinator polls the merged Live Activity feed for new entries to buffer.                                  |

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
| `management.httpexchanges.recording.enabled` | `true`  | Spring Boot recorder switch. Set to `false` to disable capture while leaving the panel visible. |

### HTTP Probe

| Property                             | Default | Description                                    |
| ------------------------------------ | ------- | ---------------------------------------------- |
| `bootui.panels.http-probe.enabled`   | `true`  | Show the HTTP Probe panel.                     |
| `bootui.panels.http-probe.read-only` | `false` | Disable sending probe requests through BootUI. |

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
| `bootui.vulnerabilities.max-packages`        | `250`   | Maximum packages included in one OSV batch query.       |
| `bootui.vulnerabilities.max-advisories`      | `200`   | Maximum advisory details fetched after a package query. |
| `bootui.vulnerabilities.osv-base-uri`        | `https://api.osv.dev` | Base URI of the OSV.dev API queried during a scan. Mainly useful for pointing scans at a local stub in tests. |

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

### DevTools

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

### AI Usage

| Property                                | Default | Description                                                     |
| --------------------------------------- | ------- | --------------------------------------------------------------- |
| `bootui.panels.ai.enabled`              | `true`  | Show the AI Usage panel.                                        |
| `bootui.ai.token-series-minutes`        | `60`    | Number of minutes retained in the AI Usage token series.        |
| `bootui.ai.max-recent-chats`            | `100`   | Maximum recent chat completions surfaced by the AI Usage panel. |
| `bootui.ai.show-content-capture-banner` | `true`  | Show the AI content-capture explanation banner.                 |

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
a loopback-only Model Context Protocol endpoint at `POST /bootui/api/mcp`. It is **off by default** and only ever active
while BootUI itself is active, so it is never reachable in production. Tools inherit the same safety model as the panels:
read tools require the backing panel to be enabled, action (`*_scan`) tools are additionally refused when the panel is
read-only, and all values flow through the same secret masking as the REST API.

| Property                | Default | Description                                                                                                                       |
| ----------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `bootui.mcp.enabled`    | `OFF`   | Enable the local MCP server. `OFF` (default) and `AUTO` keep it disabled so it is never silently exposed; `ON` exposes the endpoint. |
| `bootui.mcp.max-results` | `200`   | Maximum number of items returned by paginated read tools (config, beans, mappings, security logs, traces, HTTP exchanges) per call. |



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
