# Developer tools

## MCP Server

![BootUI MCP Server panel](../images/bootui-mcp-server.webp)

BootUI can expose its advisors and read-only diagnostics to local AI coding agents (such as GitHub Copilot or Claude
Code) through a local, opt-in [Model Context Protocol](https://modelcontextprotocol.io) server. An agent can consult the
advisors before proposing a fix and pull runtime diagnostics — a correlated live activity feed, exception detail,
security logs, SQL traces, HTTP exchanges — while investigating an issue. The server is a JSON-RPC 2.0 endpoint at
`POST /bootui/api/mcp`; human-readable status and the advertised tool list are available from
`GET /bootui/api/mcp-server`. It is disabled by default (fail-closed) and, like the rest of the BootUI API, only
reachable over the loopback interface unless non-loopback access is explicitly enabled, which requires authentication.

Enable it headlessly with `bootui.mcp.enabled=ON`, or use the prominent toggle at the top of this panel to turn it on or
off **at runtime, overriding the `bootui.mcp.enabled` Spring Boot property** for the lifetime of the running application.
The configured mode only sets the initial state, and the panel shows when the live state is an override.

The panel explains what the server does and lists every tool it exposes. Tools reuse the existing controllers and DTOs
rather than reimplementing anything, so every tool returns the same masked, bounded shape as the REST API, in three
groups:

- **Advisor scans and cached reports:** action tools include `architecture_scan`, `spring_scan`, `hibernate_scan`,
  `database_advisor_scan`, `memory_scan`, `security_scan`, `pentest_scan`, `rest_api_scan`, `graalvm_scan`,
  `crac_scan`, and `vulnerabilities_scan`. Cached reads are `get_architecture_report`, `get_spring_report`,
  `get_hibernate_report`, `get_database_advisor_report`, `get_memory_report`, `get_security_report`,
  `get_pentest_report`, `get_rest_api_report`, `get_graalvm_report`, `get_crac_report`, and
  `get_vulnerabilities_report`. `vulnerabilities_scan` additionally makes outbound calls to OSV.dev.
- **Diagnostics reads:** `get_live_activity`, `get_exceptions`, `get_exception_detail`, `get_security_logs`,
  `get_sql_traces`, `get_transactions` (Spring MVC/WebFlux only), `get_traces`, `get_log_tail`, `get_http_exchanges`,
  and `get_rest_client_traces`. `get_live_activity` returns the correlated feed this panel shows, including HTTP
  requests, SQL statements, exceptions, security events, scheduled-task runs, and, on Spring, cache accesses grouped by
  request or trace. `get_exception_detail` returns a selected exception group's stack trace, causes, and occurrences.
- **Runtime and integration reads:** `get_overview`, `get_health`, `get_config`, `get_beans`, `get_mappings`,
  `get_loggers`, `get_conditions`, `get_http_sessions`, `get_scheduled_tasks`, `get_fault_tolerance`, `get_cache_stats`,
  `get_database_connection_pools`, `get_metrics`, `get_live_memory`, `get_jvm_tuning`, `get_heap_dump_report`,
  `get_threads`, `get_startup_timeline`, `get_profile_diff`, `get_spring_data_repositories`,
  `get_flyway_migrations`, `get_liquibase_changesets`, `get_spring_security`, `get_ai_overview`, `get_emails`,
  `get_kafka_activity`, `get_rabbitmq_activity`, `get_jms_activity`, `get_devtools_status`, `get_dev_services`,
  `get_github_dashboard`, `get_copilot_sessions`, and `get_claude_code_sessions`. The live status response and MCP
  panel are the authoritative catalog for the running stack.
- **Bounded controls:** `clear_exceptions`, `clear_sql_traces`, `pause_sql_trace_recording`,
  `resume_sql_trace_recording`, `clear_transactions`, `pause_transaction_recording`, `resume_transaction_recording`,
  `clear_traces`, `clear_rest_client_traces`, `pause_rest_client_recording`, `resume_rest_client_recording`,
  `analyze_heap_dump`, and `trigger_devtools_livereload`. Destructive, database-mutating, arbitrary-command,
  heap-capture/download, HTTP-probe, GitHub-write, and dev-service-restart operations are deliberately not exposed.

Tools whose backing panel/controller is not present (for example Hibernate or Spring Security when those libraries are
absent) are simply not advertised.

::: details Safety model

The server inherits BootUI's full safety model:

- It is only ever live while BootUI is active, so it is never reachable in production.
- The endpoint sits behind `LocalhostOnlyFilter` (loopback source, `Host` allow-list, cross-site write protection). It
  is exempt from BootUI's SPA CSRF token (which only browsers can present) so non-browser MCP clients connect with a
  plain HTTP config and no credentials on loopback, while `LocalhostOnlyFilter`'s cross-site defenses still block
  browser-driven writes. If non-loopback access is explicitly enabled, the client must send the configured or generated
  BootUI bearer token like every other remote API caller.
- Read tools require the backing panel to be enabled; all action tools are additionally refused when the panel is
  read-only or `bootui.read-only=true`, returning a clear tool error instead of running.
- Values pass through the same secret masking and `bootui.expose-values` mode as the REST API, and paginated reads are
  capped by `bootui.mcp.max-results`.
- Request bytes, concurrent calls, tool execution time, and rendered response bytes have configurable hard limits.
  Capacity, timeout, and response-limit refusals use explicit server-defined JSON-RPC errors, and the status endpoint
  exposes call count, aggregate latency, capacity refusals, timeouts, and response-limit refusals.
- Unexpected server failures return only JSON-RPC `-32603` with the message `Internal error`; exception messages, stack
  traces, paths, queries, and credentials are never included. BootUI logs the original throwable once on the server,
  while expected protocol, disabled-server, and panel-policy errors keep their actionable messages.

:::

Connection details (transport, protocol revision, and the `bootui.mcp.max-results` cap) are shown alongside a
ready-to-use, copyable MCP client configuration JSON pointing at this running app — the `servers` block a GitHub Copilot
or Claude Code `mcp.json` expects. To wire it into an agent, point the client at the loopback HTTP endpoint of your
running app:

```json
{
  "servers": {
    "bootui": {
      "type": "http",
      "url": "http://127.0.0.1:8080/bootui/api/mcp"
    }
  }
}
```

See [docs/PROPERTIES.md](../PROPERTIES.md) for the `bootui.mcp.*` settings, and [AI agents](../AI-AGENTS.md) for an
end-to-end agent workflow and how BootUI pairs with [Coffilot](https://github.com/jdubois/coffilot).

On Quarkus the panel is identical, running the same live JSON-RPC bridge over the same `POST /bootui/api/mcp` endpoint
and the same working enable/disable toggle (the `bootui.mcp.*` keys are read from MicroProfile Config). The protocol
core — method routing, per-panel gating, tool lookup, and the `max-results` cap — lives in the shared framework-neutral
engine; each adapter only supplies a thin Jackson envelope codec (Jackson 2 on Quarkus) and its own tool catalog, so
requests and responses are byte-identical across the two backends. The advertised tools track which panels are actually
live on Quarkus: `graalvm_scan`, `crac_scan`, and `get_conditions` (all deliberately not applicable on Quarkus) are not
offered, `get_overview` is advertised (the Overview panel is available, its dashboard rendering client-side), and
`spring_scan` runs the Quarkus-native idiom advisor.

On Spring Boot WebFlux the panel is available too. A reactive tool catalog binds the WebFlux-specific Live Activity,
Exceptions, Security Logs, SQL Trace, and Log Tail controllers while reusing the shared controllers for the rest of the
surface, including `security_scan` through the shared reactive advisor service. The JSON-RPC transport, runtime toggle,
panel/read-only gating, payload/concurrency limits, and response envelopes are otherwise identical across all three
adapters.

## DevTools

![BootUI DevTools panel](../images/bootui-devtools.webp)

The DevTools panel reports Spring Boot DevTools availability, LiveReload status, and restart support. Restart actions
are shown only when available and require explicit confirmation before execution. When DevTools is on the classpath but
the LiveReload server is not running, the panel shows a tip to set `spring.devtools.livereload.enabled=true` (Spring
Boot 4 disables LiveReload by default).

The LiveReload card also reports how many browsers are currently connected to the LiveReload server. Triggering a reload
only reaches those connected clients — Spring Boot does not inject `livereload.js`, so a browser needs the LiveReload
extension (or the script) to connect on port 35729. When no clients are connected the panel warns that triggering has no
visible effect, and the trigger action returns that warning instead of a misleading success.

## Dev Services

![BootUI Dev Services panel](../images/bootui-dev-services.webp)

The Dev Services panel surfaces local development services discovered from Docker Compose snapshots, Testcontainers
beans, and service connection metadata. It masks sensitive connection information, can show bounded logs for supported
services, and shows restart controls only for supported Testcontainers services when
`bootui.dev-services.restart-enabled=true`. To keep opening the panel side-effect free, BootUI skips lazy, prototype, or
otherwise uninitialized service beans that would need to be created just for inspection and reports those skips as
warnings in the panel.

> **Masking scope:** BootUI masks discovered _connection details_ (for example credentials embedded in a JDBC URL or
> connection properties) before they reach the browser. Raw container **log output** is streamed verbatim, bounded by
> `bootui.dev-services.log-tail-bytes`, and is **not** scanned for secrets — a service that prints credentials to its
> own logs will surface them in this panel. This is consistent with BootUI being a local-only, loopback-restricted
> developer console.

On Quarkus, the Dev Services panel reports the framework's native Dev Services (auto-started dev/test containers such as
databases, Kafka, or Redis). The list is captured from the build-time `DevServicesResultBuildItem` snapshot via a
recorder + synthetic bean: each entry shows the service name, container id, and configuration injected by the
container, with secret-bearing config values masked. Live logs and restart are managed by Quarkus itself, so those
controls are unavailable on Quarkus. DevTools is reported *not applicable* on Quarkus, which uses built-in dev-mode
live reload instead of a Spring Boot DevTools restart bridge.

## Copilot

![BootUI Copilot panel](../images/bootui-copilot.webp)

The Copilot panel surfaces sanitized signals from local
[GitHub Copilot CLI](https://github.com/github/copilot-cli) sessions. It reads the session directories and `events.jsonl`
files Copilot CLI writes under `~/.copilot/session-state/` (configurable via `bootui.copilot.session-state-dir`) and
aggregates recent activity into a clean dashboard: active sessions, total sanitized events, input/output token usage when
the local session logs include it, failures, 24-hour activity, 7-day activity, event category mix, top tools, model usage,
and recent sessions.

::: details Explorer, limits, and charts

The session explorer remains available
for drilling into tool calls, edits, reads, searches, shell commands, web/docs lookups, MCP tool calls, hook callbacks,
skills, sub-agents, and ASK/intent/plan calls. To keep large local histories responsive, the session explorer returns
the most recent `bootui.copilot.max-sessions` sessions by default, while `bootui.copilot.max-parsed-sessions` caps how
many recent session files are parsed and retained in JVM heap. The activity charts default to token usage, with input
tokens shown in blue and output tokens shown in red, and can be toggled back to sanitized events/failures. Selecting a
chart hour or day filters the explorer to sessions active during that window. Failure lists use retained failure events
and include sanitized tool/type context.

:::

Each event row shows only an allowlisted summary — raw prompts, tool arguments, command output, and diffs are deliberately
excluded. The per-event "Reveal raw" action is an explicit, local-only escape hatch that returns the source JSON; it can
be disabled with `bootui.copilot.allow-raw-reveal=false` and is also blocked when `bootui.expose-values=METADATA_ONLY`.
The sidebar dims the panel when no session-state directory is found. Data is read-only — BootUI never modifies anything
under `~/.copilot/`.

::: details Refresh behavior and attribution

The panel uses the same header refresh button and visibility-aware auto-refresh toggle as the other
live data panels, while the backend watches the directory through a Java NIO `WatchService` thread. Inspired by
[copilot-mission-control](https://github.com/DanWahlin/copilot-mission-control), which pioneered this dashboarding of
Copilot CLI session state.

:::

## Claude Code

![BootUI Claude Code panel](../images/bootui-claude-code.webp)

The Claude Code panel mirrors the Copilot dashboard for local
[Claude Code](https://www.anthropic.com/claude-code) project logs. It reads JSONL session files under
`~/.claude/projects/` (configurable via `bootui.claude-code.session-state-dir`) and surfaces sanitized activity trends,
tool usage, model usage, input/output token usage, failures, recent sessions, and per-session event drill-downs. Its
activity charts use the same token-by-default view as the Copilot panel, with an events toggle for sanitized activity and
failures. BootUI treats Claude Code logs as
especially sensitive: prompts, assistant text, tool inputs, file contents, command output, and tool-result content are
excluded from normal responses. `bootui.claude-code.max-parsed-sessions` caps how many recent JSONL files are parsed and
retained in JVM heap. The raw JSONL reveal endpoint is disabled by default with `bootui.claude-code.allow-raw-reveal=false`;
enabling it is an explicit local-only escape hatch and is still blocked when `bootui.expose-values=METADATA_ONLY`. The
sidebar dims the panel when no Claude Code projects directory is found. Data is read-only - BootUI never modifies anything
under `~/.claude/`. Because Claude Code writes sessions inside per-project subdirectories, BootUI refreshes this panel
through the shared visibility-aware auto-refresh polling used by the other live data panels.
