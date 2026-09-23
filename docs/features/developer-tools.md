# Developer tools

## MCP Server

![BootUI MCP Server panel](../images/bootui-mcp-server.webp)

BootUI can expose its advisors and read-only diagnostics to local AI coding agents, such as GitHub Copilot or Claude
Code, through an opt-in [Model Context Protocol](https://modelcontextprotocol.io) server. An agent can then consult the
advisors before proposing a fix, and pull runtime diagnostics while investigating an issue.

The server is a JSON-RPC 2.0 endpoint at `POST /bootui/api/mcp`. Status and the advertised tool list are available from
`GET /bootui/api/mcp-server`. It is disabled by default and, like the rest of the BootUI API, is reachable only over
loopback unless non-loopback access is explicitly enabled, which requires authentication.

Enable it with `bootui.mcp.enabled=ON`, or use the toggle at the top of the panel. The toggle overrides the configured
property for the lifetime of the running application, and the panel shows when the live state is an override.

### Connecting a client

Point your client at the loopback HTTP endpoint of the running application:

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

The panel shows the transport, the protocol revision, and the `bootui.mcp.max-results` cap, alongside a ready-to-use
configuration for this running application. There is one tab per client, because clients do not agree on a shape:
**VS Code** uses a `servers` block in `.vscode/mcp.json`, **Claude Code** uses a
`claude mcp add --transport http` command, **Cursor** uses an `mcpServers` entry keyed on `url` with no `type` in
`~/.cursor/mcp.json`, and **Other clients** use the `mcpServers` shape with an explicit type. Claude Code users can
skip this with the [BootUI plugin](../AI-AGENTS.md#install-the-bootui-claude-code-plugin), which registers the server
for them.

A loopback agent needs no credentials. An agent reaching the application from anywhere else, most often a container
reached through a published port, is a remote API caller: every MCP call answers `401` until it sends BootUI's token in
the `Authorization` header. Tick **Agent connects from another host or container** to add that header to every snippet.
The panel never prints the token. It is the value of `bootui.authentication.token`, and when that is blank BootUI
generates one at each start and logs it once.

### Available tools

Tools reuse the existing controllers and DTOs, so every tool returns the same masked, bounded shape as the REST API.
Tools whose backing panel or controller is absent are not advertised. The live panel is the authoritative catalog for
your stack.

**Advisor scans** — `architecture_scan`, `spring_scan`, `hibernate_scan`, `database_advisor_scan`, `memory_scan`,
`security_scan`, `pentest_scan`, `rest_api_scan`, `graalvm_scan`, `crac_scan`, and `vulnerabilities_scan`, which also
makes outbound calls to OSV.dev.

**Cached advisor reports** — `get_architecture_report`, `get_spring_report`, `get_hibernate_report`,
`get_database_advisor_report`, `get_memory_report`, `get_security_report`, `get_pentest_report`, `get_rest_api_report`,
`get_graalvm_report`, `get_crac_report`, and `get_vulnerabilities_report`.

**Diagnostics reads** — `get_live_activity`, `get_exceptions`, `get_exception_detail`, `get_security_logs`,
`get_sql_traces`, `get_transactions` (Spring MVC and WebFlux only), `get_traces`, `get_log_tail`,
`get_http_exchanges`, and
`get_rest_client_traces`. `get_live_activity` returns the correlated feed of HTTP requests, SQL statements, exceptions,
security events, scheduled-task runs, and, on Spring, cache accesses, grouped by request or trace.
`get_exception_detail` returns a group's stack trace, causes, and occurrences.

**Runtime and integration reads** — `get_overview`, `get_health`, `get_config`, `get_beans`, `get_mappings`,
`get_loggers`, `get_conditions`, `get_http_sessions`, `get_scheduled_tasks`, `get_fault_tolerance`, `get_cache_stats`,
`get_database_connection_pools`, `get_postgresql_report`, `get_mysql_report`, `get_metrics`, `get_live_memory`,
`get_jvm_tuning`, `get_heap_dump_report`, `get_threads`, `get_startup_timeline`, `get_profile_diff`,
`get_spring_data_repositories`, `get_flyway_migrations`, `get_liquibase_changesets`, `get_spring_security`,
`get_ai_overview`, `get_emails`, `get_kafka_activity`, `get_rabbitmq_activity`, `get_jms_activity`,
`get_devtools_status`, `get_dev_services`, `get_github_dashboard`, `get_copilot_sessions`, and
`get_claude_code_sessions`.

**Bounded controls** — `clear_exceptions`, `clear_sql_traces`, `pause_sql_trace_recording`, `resume_sql_trace_recording`,
`clear_transactions`, `pause_transaction_recording`, `resume_transaction_recording`, `clear_traces`,
`clear_rest_client_traces`, `pause_rest_client_recording`, `resume_rest_client_recording`, `postgresql_read`,
`mysql_read`, `analyze_heap_dump`, and `trigger_devtools_livereload`.

Destructive, database-mutating, arbitrary-command, heap-capture and download, HTTP-probe, GitHub-write, and
dev-service-restart operations are not exposed.

Clients with prompt support can also select `diagnose_runtime_issue`, `review_application`, or `assess_application`.
These are instructions for the external agent, not new scan tools. The assessment workflow collects bounded evidence,
reports coverage, and proposes a versioned action plan before stopping for approval of specific action IDs. See
[assess an application and approve an action plan](../AI-AGENTS.md#assess-an-application-and-approve-an-action-plan).

::: details Safety model

The server inherits BootUI's full safety model:

- It is live only while BootUI itself is active. On Quarkus that means it is absent from a production build, with no
  flag that turns it on. On Spring the `prod` and `production` profiles disable BootUI, and only an explicit
  `bootui.enabled=ON` overrides that.
- The endpoint sits behind `LocalhostOnlyFilter`, with its loopback source check, `Host` allow-list, and cross-site
  write protection. It is exempt from BootUI's SPA CSRF token, which only browsers can present, so non-browser clients
  connect on loopback with no credentials while the cross-site defenses still block browser-driven writes. When
  non-loopback access is enabled, the client must send the BootUI bearer token like any other remote caller.
- Read tools require their backing panel to be enabled. Action tools are additionally refused when the panel is
  read-only or `bootui.read-only=true`, returning a clear tool error rather than running.
- Values pass through the same secret masking and `bootui.expose-values` mode as the REST API, and paginated reads are
  capped by `bootui.mcp.max-results`.
- Request bytes, concurrent calls, tool execution time, and rendered response bytes have configurable hard limits.
  Capacity, timeout, and response-limit refusals use explicit JSON-RPC errors, and the status endpoint exposes call
  count, aggregate latency, and each refusal count.
- Unexpected server failures return only JSON-RPC `-32603` with the message `Internal error`. Exception messages, stack
  traces, paths, queries, and credentials are never included, and BootUI logs the original throwable once on the
  server. Expected protocol, disabled-server, and panel-policy errors keep their actionable messages.
- A tool that refuses a request because of the request itself — an unknown resource id, an unsupported value, a
  conflicting state — reports that in-band with `isError: true` and the same reason the REST API returns, and is not
  logged as a server failure. Only genuine server faults become `-32603`.

:::

See the [`bootui.mcp.*` settings](../PROPERTIES.md) and [AI agents](../AI-AGENTS.md) for an end-to-end workflow and how
BootUI pairs with [Coffilot](https://github.com/jdubois/coffilot).

::: details Differences on Quarkus and WebFlux

The protocol core — method routing, per-panel gating, tool lookup, and the `max-results` cap — lives in the shared
engine. Each adapter supplies only a thin Jackson envelope codec, Jackson 2 on Quarkus, and its own tool catalog, so
requests and responses are byte-identical across backends.

**Quarkus** runs the same JSON-RPC bridge at the same endpoint with the same runtime toggle, reading the `bootui.mcp.*`
keys from MicroProfile Config. Its catalog declares 73 tools against Spring MVC's 89, because the tools behind
Spring-only panels are withheld: the GraalVM and CRaC scans and reports, Conditions, Startup Timeline, HTTP Sessions,
Spring Data, Spring Security, JMS, DevTools, and every transaction tool. `get_overview` is offered, and `spring_scan`
runs the Quarkus-native idiom advisor.

**Spring Boot WebFlux** uses a reactive tool catalog that binds the WebFlux Live Activity, Exceptions, Security Logs,
SQL Trace, and Log Tail controllers and reuses the shared controllers elsewhere, including `security_scan` through the
shared reactive advisor service.

:::

## Command Line

![BootUI Command Line panel](../images/bootui-cli.webp)

The Command Line panel reports the `/bootui/api/cli` endpoint that backs the [`bootui` CLI](../CLI.md). The CLI
projects the same tool registry the MCP server exposes onto terminal subcommands, so a developer or a CI job can ask
one diagnostic question without an MCP client or a hand-written `curl`. The command table is generated from that
registry at build time, so the CLI can neither offer a diagnostic the MCP server lacks nor miss one it has.

Unlike the MCP server, the endpoint is enabled by default, and the panel is read-only: it reports state rather than
switching it, so a CI job never depends on someone leaving a browser in the right state. Turn it off with
`bootui.cli.enabled=false`, which the panel reports along with the `503` the endpoint then answers.

The panel shows this instance's endpoint URL, three ready-to-paste installs — the one-command installer, a JBang one,
and a plain `curl` with `java -jar` versioned to match this application — and an example command already pointed at it,
including `--api-path` when `bootui.api-path` is customised.

It then lists every command this instance advertises, split into action and read commands. Each row shows the command
to type, such as `bootui architecture scan` rather than `architecture_scan`, the arguments it accepts, the MCP tool it
maps to, its backing panel, and whether that panel is disabled or read-only. That last column explains an exit code of
`2`: BootUI declining to run a tool is a statement about how the target is configured, not a failed request.

The spellings come from the running application through `GET /bootui/api/cli`, not from the CLI's own build, so the
panel shows what *this* instance answers to even when the CLI on your path was built against another BootUI version.

Call counters — calls, mean latency, capacity refusals, and timeouts — are tracked separately from the MCP server's, so
this panel reports what terminals and CI jobs did. There is no response-limit counter, because the command-line facade
applies no response byte budget.

## Spring DevTools

![BootUI Spring DevTools panel](../images/bootui-devtools.webp)

The Spring DevTools panel reports DevTools availability, LiveReload status, and restart support. Restart actions appear
only when available and require confirmation.

Spring Boot 4 disables LiveReload by default, so when DevTools is on the classpath and the LiveReload server is not
running, the panel suggests `spring.devtools.livereload.enabled=true`.

The LiveReload card reports how many browsers are connected. Triggering a reload reaches only those clients, and Spring
Boot does not inject `livereload.js`, so a browser needs the LiveReload extension to connect on port 35729. With no
clients connected, the panel warns that triggering has no visible effect, and the action returns that warning rather
than a misleading success.

This panel is not applicable on Quarkus, which has its own dev-mode live reload.

## Dev Services

![BootUI Dev Services panel](../images/bootui-dev-services.webp)

The Dev Services panel shows local development services discovered from Docker Compose snapshots, Testcontainers beans,
and service connection metadata. It masks sensitive connection information and can show bounded logs for supported
services. Restart controls appear only for supported Testcontainers services, and only when
`bootui.dev-services.restart-enabled=true`.

Opening the panel is side-effect free: BootUI skips lazy, prototype, and otherwise uninitialized service beans that
would have to be created just for inspection, and reports those skips as warnings.

::: warning Masking covers connection details, not log output
BootUI masks discovered connection details, such as credentials embedded in a JDBC URL, before they reach the browser.
Raw container log output is streamed verbatim, bounded by `bootui.dev-services.log-tail-bytes`, and is not scanned for
secrets. A service that prints credentials to its own logs surfaces them here.
:::

On Quarkus the panel reports the framework's native Dev Services, the containers it auto-starts for dev and test. The
list is captured from the build-time `DevServicesResultBuildItem` snapshot, and each entry shows the service name,
container id, and injected configuration, with secret-bearing values masked. Quarkus manages live logs and restarts
itself, so those controls are unavailable there.

## Copilot

![BootUI Copilot panel](../images/bootui-copilot.webp)

The Copilot panel shows sanitized signals from local [GitHub Copilot CLI](https://github.com/github/copilot-cli)
sessions. It reads the session directories and `events.jsonl` files under `~/.copilot/session-state/`, configurable
with `bootui.copilot.session-state-dir`, and aggregates recent activity: active sessions, total sanitized events, token
usage when the local logs include it, failures, 24-hour and 7-day activity, event category mix, top tools, model usage,
and recent sessions.

Each event row shows an allowlisted summary only. Raw prompts, tool arguments, command output, and diffs are excluded.
The per-event **Reveal raw** action is an explicit local-only escape hatch that returns the source JSON; disable it with
`bootui.copilot.allow-raw-reveal=false`. It is also blocked under `bootui.expose-values=METADATA_ONLY`.

The sidebar dims the panel when no session-state directory is found. Data is read-only: BootUI never modifies anything
under `~/.copilot/`.

::: details Explorer, limits, and charts

The session explorer drills into tool calls, edits, reads, searches, shell commands, web and docs lookups, MCP tool
calls, hook callbacks, skills, sub-agents, and ASK, intent, and plan calls. To keep large histories responsive, it
returns the most recent `bootui.copilot.max-sessions` sessions, while `bootui.copilot.max-parsed-sessions` caps how many
session files are parsed and retained in heap.

The activity charts default to token usage, with input tokens in blue and output tokens in red, and toggle back to
sanitized events and failures. Selecting a chart hour or day filters the explorer to sessions active in that window.
Failure lists use retained failure events and include sanitized tool and type context.

The panel uses the same header refresh button and visibility-aware auto-refresh toggle as the other live panels, and
the backend watches the directory through a Java NIO `WatchService` thread. Inspired by
[copilot-mission-control](https://github.com/DanWahlin/copilot-mission-control), which pioneered this dashboarding of
Copilot CLI session state.

:::

## Claude Code

![BootUI Claude Code panel](../images/bootui-claude-code.webp)

The Claude Code panel mirrors the Copilot dashboard for local [Claude Code](https://www.anthropic.com/claude-code)
project logs. It reads the JSONL session files under `~/.claude/projects/`, configurable with
`bootui.claude-code.session-state-dir`, and shows sanitized activity trends, tool usage, model usage, token usage,
failures, recent sessions, and per-session drill-downs. The charts use the same token-by-default view as the Copilot
panel.

BootUI treats these logs as especially sensitive. Prompts, assistant text, tool inputs, file contents, command output,
and tool-result content are excluded from normal responses, and the raw JSONL reveal endpoint is disabled by default
with `bootui.claude-code.allow-raw-reveal=false`. Enabling it is an explicit local-only escape hatch, still blocked
under `bootui.expose-values=METADATA_ONLY`.

`bootui.claude-code.max-parsed-sessions` caps how many JSONL files are parsed and retained in heap. The sidebar dims
the panel when no projects directory is found. Data is read-only: BootUI never modifies anything under `~/.claude/`.

Because Claude Code writes sessions inside per-project subdirectories, this panel refreshes through the shared
visibility-aware polling used by the other live panels rather than a directory watch.
