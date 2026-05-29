# BootUI properties

BootUI binds Spring Boot configuration under the `bootui.*` prefix. It is local-only by default: it activates only in
development contexts, rejects non-loopback callers, masks secret-like values, and disables itself for production profiles
unless explicitly forced on.

Panel settings are consistent across the UI and API:

- Every visible panel has `bootui.panels.<panel-id>.enabled` with default `true`.
- Panels with browser-triggered actions also have `bootui.panels.<panel-id>.read-only` with default `false`.
- `bootui.read-only=true` makes every action-capable panel read-only, even when the per-panel read-only flag is `false`.
- Disabled panels are moved to the Disabled / unavailable sidebar group and their panel API routes return `403`.
- Read-only panels keep read endpoints visible but block mutating API requests. Safe methods (`GET`, `HEAD`, `OPTIONS`)
  remain allowed.

## Global settings

| Property | Default | Description |
| --- | --- | --- |
| `bootui.enabled` | `AUTO` | Activation mode. `AUTO` activates only for configured local profiles or DevTools; `ON` forces BootUI on; `OFF` forces it off. |
| `bootui.enabled-profiles` | `dev,local` | Profiles that activate BootUI when `bootui.enabled=AUTO`. |
| `bootui.disabled-profiles` | `prod,production` | Profiles that force BootUI off unless `bootui.enabled=ON`. |
| `bootui.path` | `/bootui` | UI base path. `/bootui` is the supported route for v0.1. |
| `bootui.api-path` | `/bootui/api` | Internal API base path used by the UI and safety filters. |
| `bootui.allow-non-localhost` | `false` | Explicitly opt out of loopback-only protection. Keep this `false` unless the local network is trusted. |
| `bootui.mask-secrets` | `true` | Enables secret-like value masking helpers. |
| `bootui.expose-values` | `MASKED` | Configuration value exposure mode: `MASKED`, `METADATA_ONLY`, or `FULL`. `FULL` can disclose secrets. |
| `bootui.show-banner` | `true` | Print the BootUI URL on application startup. |
| `bootui.read-only` | `false` | Disable every browser-triggered action while keeping read-only panel data visible. |
| `bootui.overrides-file` | `.bootui/application-bootui.properties` | File used by the Configuration panel to persist local runtime overrides. |

## Panel access settings

| Group | Panel | Panel id | Enable property | Read-only property |
| --- | --- | --- | --- | --- |
| Overview | Overview | `overview` | `bootui.panels.overview.enabled` | Not applicable; view-only. |
| Runtime | Health | `health` | `bootui.panels.health.enabled` | Not applicable; view-only. |
| Runtime | Metrics | `metrics` | `bootui.panels.metrics.enabled` | Not applicable; view-only. |
| Runtime | Memory | `memory` | `bootui.panels.memory.enabled` | Not applicable; view-only. |
| Runtime | Startup Timeline | `startup` | `bootui.panels.startup.enabled` | Not applicable; view-only. |
| Runtime | Scheduled Tasks | `scheduled` | `bootui.panels.scheduled.enabled` | Not applicable; view-only. |
| Configuration | Configuration | `config` | `bootui.panels.config.enabled` | `bootui.panels.config.read-only` |
| Configuration | Profile Diff | `profiles` | `bootui.panels.profiles.enabled` | Not applicable; view-only. |
| Configuration | Loggers | `loggers` | `bootui.panels.loggers.enabled` | `bootui.panels.loggers.read-only` |
| Configuration | Beans | `beans` | `bootui.panels.beans.enabled` | Not applicable; view-only. |
| Configuration | Conditions | `conditions` | `bootui.panels.conditions.enabled` | Not applicable; view-only. |
| Configuration | Mappings | `mappings` | `bootui.panels.mappings.enabled` | Not applicable; view-only. |
| Services | Data | `data` | `bootui.panels.data.enabled` | Not applicable; view-only. |
| Services | Cache | `cache` | `bootui.panels.cache.enabled` | `bootui.panels.cache.read-only` |
| Services | Security | `security` | `bootui.panels.security.enabled` | Not applicable; view-only. |
| Services | AI Usage | `ai` | `bootui.panels.ai.enabled` | Not applicable; view-only. |
| Diagnostics | Traces | `traces` | `bootui.panels.traces.enabled` | `bootui.panels.traces.read-only` |
| Diagnostics | Log Tail | `log-tail` | `bootui.panels.log-tail.enabled` | Not applicable; view-only. |
| Diagnostics | HTTP Probe | `http-probe` | `bootui.panels.http-probe.enabled` | `bootui.panels.http-probe.read-only` |
| Diagnostics | Vulnerabilities | `vulnerabilities` | `bootui.panels.vulnerabilities.enabled` | `bootui.panels.vulnerabilities.read-only` |
| Developer tools | DevTools | `devtools` | `bootui.panels.devtools.enabled` | `bootui.panels.devtools.read-only` |
| Developer tools | Dev Services | `dev-services` | `bootui.panels.dev-services.enabled` | `bootui.panels.dev-services.read-only` |
| Developer tools | Copilot | `copilot` | `bootui.panels.copilot.enabled` | Not applicable; view-only. |
| Developer tools | Claude Code | `claude-code` | `bootui.panels.claude-code.enabled` | Not applicable; view-only. |

## Per-panel action details

### Configuration

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.config.enabled` | `true` | Show the Configuration panel and allow its read APIs. |
| `bootui.panels.config.read-only` | `false` | Disable creating, updating, and deleting runtime property overrides. |
| `bootui.overrides-file` | `.bootui/application-bootui.properties` | Local file where runtime overrides are persisted. |
| `bootui.expose-values` | `MASKED` | Controls whether property values are masked, hidden, or fully exposed. |

### Loggers

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.loggers.enabled` | `true` | Show logger data from the Actuator loggers endpoint. |
| `bootui.panels.loggers.read-only` | `false` | Disable runtime logger level updates and resets. |

### Cache

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.cache.enabled` | `true` | Show Spring Cache managers, caches, metrics, and cache annotations. |
| `bootui.panels.cache.read-only` | `false` | Disable cache clear actions. |
| `bootui.cache.clear-enabled` | `true` | Additional action gate for cache clearing. Both this and the read-only state must allow clearing. |

### Traces

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.traces.enabled` | `true` | Show the Traces panel and its retained trace data. |
| `bootui.panels.traces.read-only` | `false` | Disable clearing retained traces. OTLP ingestion remains controlled by `bootui.telemetry.enabled`. |
| `bootui.telemetry.enabled` | `true` | Accept OTLP/HTTP trace payloads at BootUI's OTLP endpoint. |
| `bootui.telemetry.max-traces` | `500` | Maximum distinct traces retained in memory. |
| `bootui.telemetry.max-spans-per-trace` | `500` | Maximum spans retained per trace. |
| `bootui.telemetry.max-attribute-value-bytes` | `4096` | Maximum attribute string length before truncation. |
| `bootui.telemetry.exclude-self-spans` | `true` | Drop spans whose route/path starts with the BootUI API path. |
| `bootui.telemetry.max-request-bytes` | `8388608` | Maximum accepted OTLP request body size. |

### HTTP Probe

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.http-probe.enabled` | `true` | Show the HTTP Probe panel. |
| `bootui.panels.http-probe.read-only` | `false` | Disable sending probe requests through BootUI. |

### Vulnerabilities

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.vulnerabilities.enabled` | `true` | Show dependency inventory and local scan results. |
| `bootui.panels.vulnerabilities.read-only` | `false` | Disable on-demand OSV scan requests. |
| `bootui.dependencies.osv-enabled` | `true` | Additional action gate for OSV.dev scans. |
| `bootui.dependencies.request-timeout` | `10s` | Timeout for each OSV request. |
| `bootui.dependencies.max-packages` | `250` | Maximum packages included in one OSV batch query. |
| `bootui.dependencies.max-advisories` | `200` | Maximum advisory details fetched after a package query. |

### DevTools

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.devtools.enabled` | `true` | Show Spring Boot DevTools status when DevTools is on the classpath. |
| `bootui.panels.devtools.read-only` | `false` | Disable LiveReload trigger and application restart actions. |

### Dev Services

| Property | Default | Description |
| --- | --- | --- |
| `bootui.panels.dev-services.enabled` | `true` | Show Docker Compose snapshots, Testcontainers beans, and service connection metadata. |
| `bootui.panels.dev-services.read-only` | `false` | Disable service restart actions. Bounded log reads remain available. |
| `bootui.dev-services.restart-enabled` | `false` | Additional action gate for restarting bean-backed Testcontainers services. Disabled by default. |
| `bootui.dev-services.log-tail-bytes` | `65536` | Maximum bytes returned by a single Dev Services log request. |

## Read-only examples

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

## Other panel-specific settings

| Property | Default | Description |
| --- | --- | --- |
| `bootui.ai.token-series-minutes` | `60` | Number of minutes retained in the AI Usage token series. |
| `bootui.ai.max-recent-chats` | `100` | Maximum recent chat completions surfaced by the AI Usage panel. |
| `bootui.ai.show-content-capture-banner` | `true` | Show the AI content-capture explanation banner. |
| `bootui.copilot.enabled` | `AUTO` | Enable the Copilot panel. `AUTO` activates when the session-state directory exists. |
| `bootui.copilot.session-state-dir` | `~/.copilot/session-state` | Directory scanned for Copilot CLI sessions. |
| `bootui.copilot.max-sessions` | `100` | Maximum recent Copilot sessions returned by the explorer. |
| `bootui.copilot.allow-raw-reveal` | `true` | Allow explicit raw event reveal when value exposure is not `METADATA_ONLY`. |
| `bootui.claude-code.enabled` | `AUTO` | Enable the Claude Code panel. `AUTO` activates when the project log directory exists. |
| `bootui.claude-code.session-state-dir` | `~/.claude/projects` | Directory scanned for Claude Code project JSONL logs. |
| `bootui.claude-code.max-sessions` | `100` | Maximum recent Claude Code sessions returned by the explorer. |
| `bootui.claude-code.allow-raw-reveal` | `false` | Allow explicit raw Claude Code JSONL reveal; disabled by default because logs can include prompts and outputs. |
