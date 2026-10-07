# AI agents

BootUI can expose its advisor findings and runtime diagnostics to a local AI coding agent, so the agent can consult
your running application before proposing a fix and verify the fix afterwards. It works with GitHub Copilot, Claude
Code, and any other client that speaks the [Model Context Protocol](https://modelcontextprotocol.io) (MCP).

This page covers installing the agent [skill](#install-the-bootui-agent-skill) or the
[Claude Code plugin](#install-the-bootui-claude-code-plugin), connecting an agent to the MCP server, choosing between
that and the [CLI](CLI.md), a worked example that fixes Hibernate findings, and how BootUI pairs with
[Coffilot](https://www.julien-dubois.com/coffilot/).

## Why use BootUI from an agent

An agent reading your source code can only guess at runtime behavior. BootUI gives it machine-readable context from
the running application instead:

- **Advisor scans** — architecture, REST API, Spring, Hibernate, JVM memory, Spring Security, pentesting, GraalVM and
  CRaC readiness. The agent gets the same prioritized, severity-ranked findings the panels show, with remediation hints.
- **Runtime diagnostics** — a correlated live activity feed (recent HTTP requests, SQL statements, exceptions, and
  security events grouped by request/trace), full exception detail (stack trace, causes, occurrences) by id, security
  audit events, SQL traces, distributed traces, log tail, and HTTP exchanges, so the agent can correlate a failure with
  what the app actually did.
- **Core context** — application overview, health, effective configuration (secrets masked), beans, and request
  mappings.

Every tool reuses the same controllers and immutable DTOs as the browser UI, so the agent sees the same masked,
bounded shape a human would, never raw internals.

::: details Dismissed Pentesting findings
`pentest_scan`, `get_pentest_report`, their CLI equivalents, and the REST API retain accepted findings with
`dismissed: true`, while finding totals and severity counts include only active findings. Dismissals use the exact
`PT-*` check ID from the shared local store and do not carry over from Security rule IDs. Reading the cached report
reflects a dismissal or restoration without rescanning, and scan evidence and coverage limits are unchanged.
:::

## MCP server or CLI?

Both surfaces give the agent identical data: the same registry, the same panel policy, the same masked, bounded DTOs.
The CLI can neither offer a diagnostic the MCP server lacks nor miss one it has, because its command table is generated
from the tool registry at build time.

Use the [MCP server](#connect-an-agent-to-the-bootui-mcp-server) when your agent or IDE speaks MCP natively. The agent
discovers tools, schemas, and descriptions automatically and calls them as native tool calls, with no shell commands
and no JSON parsing glue. This is the path the rest of this page follows, and what the agent skill, the Claude Code
plugin, and Coffilot wire up for you.

Use the [CLI](CLI.md) when the agent's host can only run shell commands: a sandboxed or cloud agent with no MCP wiring,
a CI job, or a human running one-off checks. The agent skill falls back to `bootui` commands whenever its host does not
already expose BootUI's MCP tools.

## Install the BootUI agent skill

BootUI ships an agent skill that teaches GitHub Copilot how to install and configure BootUI, inspect a running
application from the [command line](CLI.md) or the
[MCP server](#connect-an-agent-to-the-bootui-mcp-server), turn advisor findings into focused fixes, and verify
those fixes.

With GitHub CLI 2.90 or later, inspect the skill before installing it:

```bash
gh skill preview jdubois/boot-ui bootui
```

Then install it for the current project:

```bash
gh skill install jdubois/boot-ui bootui
```

The skill works with Copilot cloud agent, Copilot CLI, the GitHub Copilot app, Copilot code review, and agent mode in
supported IDEs. Like any third-party skill, review its instructions before installation. You can also copy
`skills/bootui` into a project's `.github/skills` directory manually.

Agents that read skills from a project directory rather than from GitHub can install the same skill with:

```bash
npx skills add jdubois/boot-ui
```

Claude Code users should prefer the [plugin](#install-the-bootui-claude-code-plugin), which installs this skill and
wires up the MCP server in one step.

## Install the BootUI Claude Code plugin

For [Claude Code](https://claude.com/claude-code), BootUI ships a plugin that bundles the same agent skill **and** the
MCP server connection, so there is no separate `claude mcp add` step. Add the marketplace and install it:

```
/plugin marketplace add jdubois/boot-ui
/plugin install bootui@bootui
```

The plugin registers the `bootui` skill and an HTTP MCP server pointing at `http://127.0.0.1:8080/bootui/api/mcp`.

Two things to know before your first call:

1. **The MCP server is off by default.** The plugin cannot turn it on for you — it lives in *your* application. Set
   `bootui.mcp.enabled=ON`, or flip the toggle in the **MCP Server** panel (`/bootui/#/mcp-server`), as described in
   [Connect an agent to the BootUI MCP server](#connect-an-agent-to-the-bootui-mcp-server). Until then every tool call
   answers that the server is disabled.
2. **If your application does not listen on port 8080**, set `BOOTUI_MCP_URL` before starting Claude Code. The plugin
   reads it and falls back to the address above when it is unset, so this also covers a custom
   [`bootui.api-path`](PROPERTIES.md) or an application reached from a container:

   ```bash
   export BOOTUI_MCP_URL=http://127.0.0.1:8081/bootui/api/mcp
   ```

An agent reaching BootUI from anywhere other than loopback must also present the bearer token; the plugin's
configuration carries no header, so register that server yourself with the
[`claude mcp add` form below](#connect-an-agent-to-the-bootui-mcp-server) instead.

Because BootUI is loopback-only by default, the plugin asks for no credentials and stores nothing. It is published from
this repository, so `/plugin marketplace update bootui` picks up every change to the skill. The plugin changes no
policy of its own: the [safety model](#safety-model) below — panel availability, read-only flags, and confirmation for
mutating actions — applies exactly as it does to any other MCP client.

## Connect an agent to the BootUI MCP server

The BootUI MCP server is a local, opt-in JSON-RPC 2.0 endpoint at `POST /bootui/api/mcp`. It is **disabled by default**
(fail-closed) and, like the rest of BootUI, only reachable over the loopback interface unless non-loopback access is
explicitly enabled, which requires authentication.

The endpoint speaks both MCP eras. A client that opens with `initialize` gets MCP 2025-06-18 exactly as before; a client
that sends MCP 2026-07-28 per-request `_meta` (with the matching `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name`
headers) can call `server/discover` and gets `resultType`, the server's identity, and cache hints on every result. Both
eras see the same tools, prompts, policies, and limits, and every response is a single JSON object. There is no `GET`
stream in either era (`405`). See [Protocol eras](#protocol-eras) for the details a client implementer needs.

1. **Run your app locally with BootUI active** (the `dev` / `local` profiles, or `spring-boot-devtools` on the
   classpath). See [Setup](SETUP.md).
2. **Enable the server.** Set `bootui.mcp.enabled=ON`, or flip the toggle at the top of the **MCP Server** panel
   (`/bootui/#/mcp-server`). The panel toggle overrides the property at runtime for the life of the process, so you can
   turn the server on only while you are pairing with an agent.
3. **Point your agent at the endpoint.** The MCP Server panel shows a ready-to-copy client configuration, with one tab
   per client because they do not agree on a shape. Replace `8080` with your application's port.

   VS Code (`.vscode/mcp.json`) uses a `servers` block:

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

   Claude Code registers the server from a terminal in your project — though the
   [plugin](#install-the-bootui-claude-code-plugin) does this for you:

   ```bash
   claude mcp add --transport http bootui http://127.0.0.1:8080/bootui/api/mcp
   ```

   Cursor (`~/.cursor/mcp.json`) keys a remote server on `url` and takes no `type`:

   ```json
   {
     "mcpServers": {
       "bootui": {
         "url": "http://127.0.0.1:8080/bootui/api/mcp"
       }
     }
   }
   ```

   Most other clients — including the `.mcp.json` Claude Code writes — accept the `mcpServers` shape with an
   explicit type:

   ```json
   {
     "mcpServers": {
       "bootui": {
         "type": "http",
         "url": "http://127.0.0.1:8080/bootui/api/mcp"
       }
     }
   }
   ```

   No credentials are needed on loopback — the endpoint is exempt from BootUI's browser-only CSRF token so a local
   non-browser MCP client connects with a plain HTTP config, while the loopback, `Host` allow-list, and cross-site write
   defenses still apply.

4. **If the agent is not on loopback, send the bearer token.** An agent that reaches the app from anywhere other than
   loopback — the common case being an app in a container reached through a published port — is a remote API caller
   like any other, and every MCP call answers `401` until it presents BootUI's token in the standard `Authorization`
   header.
   Tick **Agent connects from another host or container** in the panel and the snippets gain the header. For Claude Code
   that is:

   ```bash
   claude mcp add --transport http bootui http://localhost:8080/bootui/api/mcp \
     --header "Authorization: Bearer <BootUI authentication token>"
   ```

   and for a JSON client, a `headers` entry beside `url`:

   ```json
   {
     "mcpServers": {
       "bootui": {
         "type": "http",
         "url": "http://localhost:8080/bootui/api/mcp",
         "headers": {
           "Authorization": "Bearer <BootUI authentication token>"
         }
       }
     }
   }
   ```

   The token is the value of `bootui.authentication.token`. When that property is blank, BootUI generates a new token at
   every start and logs it once — set the property to a stable value if you do not want to re-edit the client
   configuration after each restart. The browser console does not need this because it authenticates once and keeps an
   HTTP-only session cookie; a non-browser MCP client has no such fallback.

A `GET /bootui/api/mcp-server` status request returns the advertised tool list, which is handy for inspecting what an
agent will see before you wire it up.

### Tools the agent can call

Tools whose backing panel/controller is absent (for example Hibernate or Spring Security when those libraries are not on
the classpath) are not advertised. Calling one anyway answers JSON-RPC `-32602` with the reason in the message, such as
`Tool not available in this application: get_kafka_activity. Its Kafka panel is unavailable: …`, and in `error.data`
(`tool`, `panel`, and `reason` when the panel gives one). A name BootUI does not know at all still answers
`Unknown tool: <name>`. A tool of a disabled panel is refused in-band as disabled, whether or not it is advertised.

Each `tools/list` entry carries MCP `annotations` derived from the tool's kind: a read has `readOnlyHint: true` and
`idempotentHint: true`; an action has `readOnlyHint: false`, the `clear_*` actions `destructiveHint: true` (they discard
buffered evidence), clearing, pausing, and resuming `idempotentHint: true`, and `vulnerabilities_scan`
`openWorldHint: true` (it contacts OSV.dev). Its `inputSchema` describes each argument for that tool: where an `id`
comes from, the words a `query` understands, one example, and the `default` a call gets when it omits `limit` (a
compacted tool's short page, otherwise `bootui.mcp.max-results`). The hints help an agent host decide when to ask the
user; BootUI's own panel and read-only gates still apply.

- **Advisor scans (actions):** `architecture_scan`, `spring_scan`, `hibernate_scan`, `database_advisor_scan`,
  `memory_scan`, `security_scan`, `pentest_scan`, `rest_api_scan`, `graalvm_scan`, `crac_scan`, and
  `vulnerabilities_scan`. Each runs the same scan as the panel's action button and returns the report DTO;
  `vulnerabilities_scan` additionally sends package names/versions to OSV.dev and, when EPSS is enabled, CVE ids to FIRST.
  Run it only with approval. Inspect `scan.status`, `scan.message`, `coverage`, and `scan.packagesSkipped`; partial or
  unknown evidence is not a clean result. `coverage.archivesFirstParty`/`firstPartyArchives` name the application's own
  module JARs, which are not a coverage gap. Fix candidates need compatibility checks, and EPSS is the highest available
  per-CVE probability, not a combined probability or severity. See [Vulnerabilities checks](VULNERABILITIES-CHECKS.md).
- **Cached advisor reports:** `get_architecture_report`, `get_spring_report`, `get_hibernate_report`,
  `get_database_advisor_report`, `get_memory_report`, `get_security_report`, `get_pentest_report`,
  `get_rest_api_report`, `get_graalvm_report`, `get_crac_report`, and `get_vulnerabilities_report` return the last
  completed report without starting another scan. With the BootUI agent, the vulnerabilities report's `runtimeReach`
  says whether each dependency's classes, or a class its advisory names, loaded in this JVM; it is read when answered,
  never changes a severity, and `NOT_LOADED` means not loaded yet, not unreachable
  ([Runtime reach](features/advisors.md#runtime-reach)).
- **Cached per-rule violations (reads):** `get_architecture_rule_violations`, `get_hibernate_rule_violations`,
  `get_spring_rule_violations`, `get_rest_api_rule_violations`, `get_memory_rule_violations`,
  `get_security_rule_violations`, and `get_database_advisor_rule_violations` page the retained details from that
  advisor's latest completed scan. Each takes required `id` (rule ID) and `scanId`, with optional `offset` and
  `limit`. They have the same stack/capability availability as their report, and remain usable in read-only mode.
- **Diagnostics reads:** `get_live_activity`, `get_request_profile`, `get_exceptions`, `get_exception_detail`,
  `get_security_logs`,
  `get_sql_traces`, `get_transactions` (Spring MVC/WebFlux only), `get_traces`, `get_log_tail`, `get_http_exchanges`,
  `get_http_routes`, and `get_rest_client_traces`.
  `get_http_routes` returns the [HTTP Exchanges route rankings](features/diagnostics.md#route-rankings): per method and
  route template, request and status-class counts, p50/p95/p99 and maximum duration, share of request time, and the
  evidence window they cover; its optional `limit` is the number of routes each ranking criterion contributes.
  `get_live_activity` returns the correlated feed the [Live Activity panel](features/overview.md#live-activity) shows (HTTP requests, SQL
  statements, exceptions, and security events grouped by request/trace); `get_request_profile` takes the required `id`
  of a profileable request, scheduled run, or consumed message (also an insight exemplar). It returns a selection
  with `source: "journal"`, `"buffers"`, or `"none"`; a retained journal `journal` profile is preferred,
  and a retained HTTP-exchange `buffers` profile accompanies it for HTTP requests (or is returned alone as a
  fallback). It is not the single legacy REST DTO — see
  [Investigate one request](#investigate-one-request); `get_exception_detail` takes a required `id`
  (from `get_exceptions`, an `EXCEPTION` entry's `exceptionGroupId` in `get_live_activity`, never the entry's own `id`, or a profile exception's `exceptionGroupId`) and returns that exception
  group's full stack trace, causes, and individual occurrences. With the BootUI agent's opt-in `caught-exceptions`
  sensor, `get_exceptions` also returns `caughtInCode`: counts and the top findings of the exceptions application code
  caught. A finding was not seen rethrown or logged at `WARN` or above while the evidence was complete; `unknown`
  counts incomplete evidence, never a swallowed exception
  ([Caught in application code](features/diagnostics.md#caught-in-application-code)). `get_http_exchanges`, `get_sql_traces`, and `get_rest_client_traces` read bounded buffers
  that keep recent failed and slow records longer than routine ones; each includes a `retention` object with the
  capacity and the retained, reserved, and evicted counts, so an agent can tell a partial window from "it never
  happened". See [Failure-preserving retention](features/diagnostics.md#failure-preserving-retention).
- **Runtime Insights reads:** `get_runtime_insights`, `get_runtime_insight`, `get_runtime_impact`, and
  `get_runtime_run_comparison`, compact facts an agent can refuse to act on; see
  [Runtime Insights for agents](#runtime-insights-for-agents).
- **Core context and integration reads:** `get_overview`, `get_health`, `get_config` (masked), `get_beans`,
  `get_mappings`, `get_loggers`, `get_conditions`, `get_http_sessions`, `get_scheduled_tasks`, `get_fault_tolerance`,
  `get_cache_stats`,
  `get_database_connection_pools`, `get_postgresql_report`, `get_mysql_report`, `get_metrics`, `get_live_memory`, `get_jvm_tuning`, `get_heap_dump_report`,
  `get_threads`, `get_startup_timeline`, `get_profile_diff`, `get_spring_data_repositories`,
  `get_flyway_migrations`, `get_liquibase_changesets`, `get_spring_security`, `get_ai_overview`, `get_emails`,
  `get_kafka_activity`, `get_rabbitmq_activity`, `get_jms_activity`, `get_agent_status`, `get_devtools_status`,
  `get_dev_services`, `get_github_dashboard`, `get_copilot_sessions`, `get_claude_code_sessions`,
  `get_hibernate_statistics` (Hibernate ORM counters; it never enables statistics, and reports `available: false` with
  the reason while they are off), and `get_websockets` (endpoints, sessions, subscriptions, and frame metadata, never a
  payload). Stack-specific or unavailable capabilities are omitted.
- **Code Inventory read:** `get_code_inventory`, whether the code changed since the previous run executed in this run;
  see [Did my change run?](#did-my-change-run).
- **Code Paths read:** `get_code_paths`, which application methods each route spends its time in; see
  [Where does the handler's time go?](#where-does-the-handler-s-time-go).
- **Method probes:** `start_method_probe`, an action that needs the user's separate approval, and `get_method_probe`;
  see [Did this method run, and how?](#did-this-method-run-and-how).
- **Side Effects read:** `get_side_effects`, which processes, hosts, and other side-effect sensor rows each route or background
  execution produced; see [What side effects did the application start?](#what-side-effects-did-the-application-start).
- **Bounded controls (actions):** `clear_exceptions`, `clear_sql_traces`, `pause_sql_trace_recording`,
  `resume_sql_trace_recording`, `clear_transactions`, `pause_transaction_recording`, `resume_transaction_recording`,
  `clear_traces`, `clear_rest_client_traces`, `pause_rest_client_recording`, `resume_rest_client_recording`,
  `postgresql_read`, `mysql_read`, `analyze_heap_dump`, `trigger_devtools_livereload`, and `start_method_probe`. They never capture or download a heap dump,
  execute an HTTP probe, mutate a database, clear a cache, write GitHub state, restart a dev service, or run an agent
  command.
- **Browser only, by design:** HTTP Probe (it sends requests the user composes), Profile resources and its JDK Flight
  Recorder results in Runtime Insights, enabling Hibernate statistics, the WebSockets capture switch, the Java agent's
  sensor switches, changing a logger level, and the other panel controls not listed above have no MCP tool or CLI
  command. An agent should point the user to the panel rather than work around it.

### Investigate one request

`get_live_activity` says which execution was slow or failed; `get_request_profile` opens its retained evidence. The workflow is the same
through MCP and the CLI:

1. **List activity.** Call `get_live_activity` (`bootui activity --limit 50 --json`) and pick the `REQUEST` entry in
   question (or a scheduled/message execution). Only entries with `profileable: true` have a profile, and `sqlNPlusOneSuspected` or an `ERROR` or `SLOW`
   severity marks the ones worth opening.
2. **Fetch its profile.** Call `get_request_profile` with that entry's `id` (`bootui request-profile <id> --json`). The
   `source: "journal"` carries the `journal` profile's timeline, route or execution label, resources and touched
   metadata. `source: "buffers"` carries the `buffers` HTTP-exchange profile's normalized SQL groups with N+1 flags,
   application call sites, exceptions, security events, REST client calls, cache accesses, timing, correlation tiers
   and truncation counts. A child tiered `PROPAGATED` ran in a task the BootUI
   agent propagated from the request to a JDK executor, as exact as `REQUEST_ID`; `correlationTiers` reports
   `PROPAGATED` unavailable, with the reason, unless the agent's `executors` sensor propagates for the application. It
   follows the same panel policy and masking as the browser. When both are retained, `buffers` also accompanies the
   journal result so its SQL grouping and exception ids remain available. The buffer profile includes a trace whose
   values follow [Trace value exposure](features/diagnostics.md#trace-value-exposure). An unknown or evicted id returns
   `source: "none"` and `available: false` with an `unavailableReason` naming both retention windows; that is an answer,
   not a failure to retry.
3. **Follow each exception.** When the `buffers` profile is present, its exceptions carry an `exceptionGroupId`; pass it to
   `get_exception_detail` (`bootui exceptions show <id> --json`) for the stack trace, cause chain, and recent
   occurrences.

The tool belongs to the Live Activity panel, so it is unavailable when that panel is disabled. In the browser,
**Copy for AI** in the profile drawer and in an Exceptions detail renders the same evidence as one Markdown document,
previewed with what it omits before anything reaches the clipboard. It sends nothing to any AI provider.

### Runtime Insights for agents

[Runtime Insights](features/overview.md#runtime-insights) answers what this run did that no single panel shows. Its four
read tools return short, stable facts rather than a dashboard:

| Tool | CLI | Returns |
| --- | --- | --- |
| `get_runtime_insights` | `bootui insights list [--query Q] [--limit N]` | The completed HTTP exchanges in `requests`, coverage, the checks that did not fully run, then at most `limit` (8) observations: id, status, one sentence, eligible and affected counts, tier, one exemplar request id, a `verify` line, and `listed`. Past the limit listed rows come first and every kind is listed once before any kind twice, and a limitation names what was left out. Then at most 8 `notExercised` routes. `query` is empty (the default list: what the panel lists by default, which leaves several kinds out, for example time breakdowns, exception hotspots, repeated SELECTs, and garbage collection and heap rows; a limitation names each kind it left out with its count), `all` (every observation), `latency`, `repeated-selects`, `new`, `security`, `diff`, an observation kind such as `proxy-bypass`, or a route, table, bean, or class. Every query but the empty one also matches rows the default list leaves out |
| `get_runtime_insight` | `bootui insights show <id>` | One observation with every check and at most 20 evidence rows; open its exemplar with `get_request_profile`. A row the default list leaves out says where its evidence is shown first among its limitations |
| `get_runtime_impact` | `bootui insights impact <id>` | For a route, bean, class, method (`Class#method`, with parameter types such as `Class#method(String)` for one overload), repository, table, cache, host, or event type: the routes that ran through it, those that did not, and those sharing a resource, at most 8 each with their totals and a limitation naming the rest, or `AMBIGUOUS` with candidates. A bare method name, such as `applyDiscount`, resolves through Code Inventory, `AMBIGUOUS` when several classes declare it; parameters that match no overload return `NOT_FOUND` with the real overloads as `candidates`. With the BootUI agent, a method's `observed` routes are those whose requests' own call trees ran it (`observedFrom: ROUTE_TREES`, each with `executedRequests`), and `notObserved` routes ran without showing it, which proves nothing |
| `get_runtime_run_comparison` | `bootui insights compare [<id>]` | Omitted `id` or `previous` selects the newest kept run, including listener-only or idle runs. A run id from `runs` selects another. Comparability first, then `codeChanges` (with the BootUI agent: at most 8 changed or added methods, not run yet first, each with its status and the routes that ran it), then `sideEffects` (with the agent: hosts, file patterns, processes, and variable names new, gone, or whose owner was not exercised, per sensor `COMPARED`, `PARTIAL`, or `NOT_COMPARED` with the reason; at most 8), then at most 8 route/execution behavior rows and edges; latency is left out |

**Every answer names the next call.** Each of the four answers carries `next`: at most three follow-up calls, each
with the `bootui` `command` line, the MCP `tool` and its `arguments`, and `why`. The list names the lead observation's
evidence (`get_runtime_insight`, call sites included), one request that shows it (`get_request_profile`), the kind a
`limit` left out, and for an anonymous-access observation the security rules (`get_spring_security`, on Spring) and
the route's mapping. An unknown or evicted observation id names `get_runtime_insights`, an unknown run id names
`previous` and the runs still kept, and an `AMBIGUOUS` or `NOT_FOUND` impact names the candidates, `get_beans`, or
`get_mappings`. Only tools the application advertises are named, so Quarkus is never told to call a Spring-only tool;
a named tool whose panel is disabled is still refused like any other call.
Calling a tool without its required `id` fails with the message naming where the id comes from, such as `Missing
required argument: id (an observation id from get_runtime_insights)`. In the panel, the change impact's `next` is
always empty.

`INSUFFICIENT`, `PARTIAL`, `NOT_APPLICABLE`, `UNAVAILABLE`, and `NOT_COMPARABLE` are not successes, and an empty list
never means healthy: read `requests`, `checksNotRun`, and `limitations` first. `requests` counts completed HTTP
exchanges only. `requests: 0` means not exercised only when the limitations say so: an observation that names a
request or execution, a limitation naming retained scheduled runs or consumed messages, or evicted events mean work
ran that `requests` does not count. A run-level observation with no exemplar does not. If comparison's limitations
say HTTP Exchanges is disabled or unavailable, that comparison's run references retain `requests: 0` as a hidden
count, not evidence of zero traffic. Source-panel policy also hides disabled sources' counters, fingerprints,
exception classes, execution names, and edges, with a “not compared because &lt;panel&gt; is disabled” limitation;
configuration comparability and restart timings are independent facts. The `diagnose_runtime_issue` prompt starts
with `get_runtime_insights`, calls it again with `all` or the route when nothing listed explains the issue, then one
`get_request_profile`, and for a slow route whose time is in its handler,
`get_code_paths` when the agent is attached, and it words a dependency reached or request input matched verbatim as a
check to verify against source and configuration, never as a vulnerability verdict; the
`verify_after_change` prompt starts with `get_code_inventory` and `changed` (see [Did my change run?](#did-my-change-run)),
names `start_method_probe` as the next step when the edited method still did not run after the test that should reach
it, calls `get_runtime_impact` on each changed method it names (`Class#method`), or on the changed symbol when it is
known, runs the tests, calls
`get_runtime_insights` with `query=repeated-selects`, then `get_runtime_run_comparison` with `previous`, and stops.

**Change, then verify.** An agent editing code uses the four tools as one loop:

1. Before editing `OrderService`, `bootui insights impact OrderService --json` lists the routes this run exercised
   through it, the mapped routes it reaches that no request did, and the routes sharing its tables. Those are what the
   tests must reach. With the BootUI agent, `bootui insights impact 'OrderService#total' --json` narrows that to the
   routes whose requests ran the method itself; a route under `notObserved` ran without showing it, which is not proof
   it never does.
2. After the edit and a DevTools restart or Quarkus live reload, run the tests, then
   `bootui insights list --query repeated-selects --json`. The default list leaves repeated SELECTs out, so that
   query shows whether a repeat is gone; a statement repeated after the handler
   returned is reported by `lazy-sql-after-handler` instead, with its cause. Absence is evidence only when the
   route it named ran again: check `requests` and `notExercised`.
3. Then `bootui insights compare --json` (or `compare previous`), and stop. With the BootUI agent, its `codeChanges`
   come first: the methods changed since the previous run, which ran, and on which routes; its `sideEffects` then name
   a new host, file, process, or variable a route uses, only for sensors marked `COMPARED`. Omitted `id` or `previous`
   selects the newest kept run, including listener-only and idle runs. A new statement fingerprint or a higher statement count
   per request on a route is a behavior change the agent caused; `INSUFFICIENT` means the tests did not reach the
   route 3 times in both runs, not that nothing changed. Do not edit from a latency row.

**Analyze after tests.** Tests are where realistic traffic comes from: run the application's integration or browser
tests against the running application, then `bootui insights list --json`. In the browser, **Copy for AI** on an
observation renders the same evidence as one Markdown document, previewed before anything reaches the clipboard.

### Did my change run?

With the [BootUI agent](features/java-agent.md) attached, [Code Inventory](features/java-agent.md#code-inventory)
answers the question an agent most needs after an edit: did the method it changed execute, and on which route?

| Tool | CLI | Returns |
| --- | --- | --- |
| `get_code_inventory` | `bootui code inventory [--query Q] [--limit N]` | The summary first (this run, N of M tracked methods executed, changed, added, and removed counts, dependency counts, limitations), then at most `limit` (25) rows of `query`: `changed` (the default; the methods changed or added since the previous DevTools restart or Quarkus live reload, not executed first, each with its status and the first request id and route that ran it), `never-executed`, `not-tracked`, `executed`, `dependencies` (declared jars not loaded in this run first), or a package, class, or method name (`applyDiscount`, `OrderService#applyDiscount`) |

The tool is advertised only while the agent's inventory sensor records this run, like every tool of an unavailable
panel; read `get_agent_status` once to learn whether it can be, and why not. `summary.available: false`, from a run
that stopped recording since, carries the reason too. `NEVER_EXECUTED` on a changed method means the change has not run yet:
run the test or send the request that reaches it, then call the tool again before reading any latency. `NOT_TRACKED`
is not evidence either way, and a jar `NOT_LOADED` in this run is not proof it is unused. Runtime Insights reports the
same gap as `changed-code-not-executed`.

**Verify, then probe.** When a changed method is still `NEVER_EXECUTED` after the test or request that should reach
it, the next step is a [method probe](#did-this-method-run-and-how): with the user's separate approval, start one on the
method as Code Inventory names it, rerun the same test or request, then read `get_method_probe`. No invocation is
evidence that path never reaches the method: the wrong route, the wrong bean, or never wired; `get_code_paths` on the
route shows what it did run. The `verify_after_change` prompt and the [agent skill](#install-the-bootui-agent-skill)
follow this workflow. [Set up the Java agent](setup/java-agent.md) walks through it from a fresh application.

### Before relying on the BootUI agent

The server's instructions tell an agent to call `get_agent_status` once before relying on any tool or observation that
needs the BootUI agent, so it learns up front whether Code Paths, Code Inventory, Side Effects, and method probes can
answer, rather than one refused or unadvertised tool at a time. An observation `NOT_APPLICABLE` because it requires the
BootUI agent (or one of its sensors) was **not measured**: it is never healthy, never "nothing to worry about", and
never a passed check. The agent benchmark's sixth refusal fixture checks exactly that.

### Where does the handler's time go?

With the [BootUI agent](features/java-agent.md) attached, [Code Paths](features/java-agent.md#code-paths) names the
application methods a route spends its time in, from the agent's `code-paths` sensor.

| Tool | CLI | Returns |
| --- | --- | --- |
| `get_code_paths` | `bootui code paths [--query Q] [--limit N]` | At most `limit` (10) routes matching `query` (blank for every route; else a route, or part of a route or method), slowest warm median first, each with its warm requests, median and 95th percentile, `assemblyOnly`, and top methods by self time per request; for a single route, its method nodes with the most self time, each with `calls`: the SQL, REST client, cache, and AI calls it issued per request, by kind; then the excluded methods and limitations |

Like `get_code_inventory`, it is advertised only while the sensor records this run. Times are per warm request, each
route's first recorded request kept apart. A node's `calls` are the recorded calls stamped with it: it was the innermost
instrumented method open on their thread when they ran. Their time is part of the node's self time. A statement
Hibernate flushes at commit runs after the `@Transactional` method returned, in the transaction interceptor around it,
so it shows under the method that called the `@Transactional` one. A call issued while no instrumented method was open,
as in a filter or while the response is written, and a call recorded on another thread than the one that issued it,
such as a streaming AI call, show under no node, and the limitations count each apart. `route-time-breakdown` splits
only the handler's other work by each method's own time, its self time minus its stamped calls, and not at all when
calls without a stamp take a tenth of the handler or none is stamped; `repeated-selects` names the method that issued
a repeated statement, past an application repository or DAO method to the method that called it. An `assemblyOnly` route's handler ran on an event loop, returned a reactive or asynchronous
result, or BootUI could not tell where its work ran, so its tree times assembly, not the work. Node percentiles are
approximate (≈), interpolated within log2 buckets. The `diagnose_runtime_issue` prompt calls it for a slow route whose time is in its handler.

### Did this method run, and how?

A [method probe](features/java-agent.md#method-probes) records one application method's next invocations: metadata
only, in every exposure mode (D24). A probe the user started in the Code Paths panel with argument and return shapes
says so (`recordShapes`), but `get_method_probe` never returns those shapes, in any exposure mode: its
`shapesHiddenReason` says they are shown in the panel only.

| Tool | CLI | Returns |
| --- | --- | --- |
| `start_method_probe` | `bootui probe start <method>` | An action: starts a probe on `id`, the method as `binary.Class#name`, with its JVM descriptor for an overloaded one (`com.example.PriceService#quote(I)J`), as Code Paths and Code Inventory name it. Returns the probe, `starting`. Refused (an in-band tool error with the REST status) for a method the agent never instrumented or outside the application's packages (400), and while BootUI or the Code Paths panel is read-only, without the agent, or when five probes run (409) |
| `get_method_probe` | `bootui probe show <id>` | The probe `id`: its state (`starting`, `active`, `ending`, `ended`, `failed`), `endReason` or `failure`, `waitingForClass`, `async`, and each recorded invocation's duration, thread kind, request id, outcome or exception type, and calling frame |

A probe records at most 20 invocations, for at most 60 seconds, five at once, and ends with the run; the agent enforces
those bounds where the method runs and then removes its instrumentation. It never records an argument or a return value.
**Ask the user before starting one**, as for `memory_scan` or `pentest_scan`: it changes the running application's code
for its window, even though read-only policy refuses it. The loop it serves: start a probe on the method an edit
changed, run the test or send the request that should reach it, then read `get_method_probe`. No invocation after the
code ran is evidence the path never reaches the method (the wrong route, the wrong bean, never wired). A probe
`waitingForClass` has not seen this run load its class yet; an `async` method's durations time the assembly of its
reactive or asynchronous result, not the work that runs later.

### What side effects did the application start?

With the [BootUI agent](features/java-agent.md) attached, [Side Effects](features/java-agent.md#side-effects) lists the
side-effect sensors and, in this version, the processes application code starts from the agent's `processes` sensor,
its network from the `network` sensor: hosts and ports it connects to, datagrams it sends, and names the JVM resolves,
each with the recognized client and whether any panel captured the work, and, opt-in, the files it opens, deletes, moves,
and copies from the `files` sensor and the environment variables and system properties it reads from the `environment`
sensor, the blocking calls started on an event loop from the `blocking` sensor, and, opt-in, the threads it starts and
the executors it creates from the `thread-activity` sensor, with those a request left running when it ended
(`leftRunning`) and how many a request starts (`count` / `requests`), and, opt-in, the thread locals a request or a job
left set on its pooled thread from the `thread-locals` sensor, named by the static field holding them, never their
values, and, by default, the sockets, and with `files` the file streams, from the `resources` sensor: those the collector reclaimed without
`close()` (`failed`, the leak), and those still open after their request (`leftRunning`) or closed after it
(`completed`), a pool's or a cache's hand-off. Ask with `query` `not captured` for the outbound calls no panel shows (an SDK's own socket, say).

| Tool | CLI | Returns |
| --- | --- | --- |
| `get_side_effects` | `bootui side-effects [--query Q] [--limit N]` | Every sensor's coverage, then at most `limit` (20) rows matching `query`: a sensor id such as `processes`, `network`, `files`, `blocking`, `thread-activity`, `thread-locals`, or `resources`, `not captured`, part of a route, target, client, or call site, or a request id among a row's exemplars, most frequent first; process rows name only the sanitized command name (cut at whitespace or `=`, basename-only, non-safe characters as `?`); network rows a host and port or a looked-up name, the client, and `capture` (`captured` with `capturedBy`, `not-captured`, `infrastructure`), never a byte; file rows a path pattern with its kind, location, and origin, never contents; environment rows a name, never a value; blocking rows the operation (`sleep`, `wait`, `park`, `network`, `file`) and the event loop's thread family, with how long it blocked; each with counts, failures, exits or connections, durations, call site, bean method stamp, and up to three request ids |

Like `get_code_paths`, it is advertised only while the agent is armed for this run. Rows are per run and bounded by
the agent evidence contract. When HTTP Exchanges is disabled, route rows merge under
`(route hidden: HTTP Exchanges is disabled)` and expose no request ids. When Code Paths is disabled, rows lose their
inside bean method. A disabled Side Effects panel shows no rows. Sensor groups this version does not ship are listed as
`not-available` with reason `Not available in this version.` `blocking` is
`not-applicable` on Spring MVC until a WebClient's event loop is registered.

### MySQL operational evidence

MySQL exposes two argument-free tools on MVC, WebFlux with JDBC, and Quarkus with JDBC:

| Tool | Behavior |
| --- | --- |
| `get_mysql_report` | Read the latest sanitized in-memory report without opening a connection or executing SQL. |
| `mysql_read` | Explicitly collect bounded operational evidence through the application's existing JDBC datasources. |

Oracle MySQL 8.4 LTS and 9.7 LTS are the tested server lines, with live coverage on 8.4.6 and 9.7.2. MariaDB reached
through MySQL Connector/J is read but unsupported (`serverFlavor` is `MARIADB`, with an INFO diagnostic naming the
gaps); reactive-client-only or R2DBC-only applications are outside this scope.
Check the running catalog for the application's actual capability.

Read the cache first. Ask for approval before `mysql_read`, naming the database collection even though it is
read-only: it performs external work and is blocked by global/panel read-only policy. Do not automatically repeat
a busy, failed, partial, or stale read. A change in exposure policy invalidates the cache without SQL; an explained
`NOT_READ` is not authorization to collect again.

These are observations, not advisor findings or scores. Inspect report status, per-section reasons, capabilities,
timestamps, and limitations. `PARTIAL` can mean retained top-N rows, disabled/unknown instrumentation, denied
permissions, or a timeout; `truncated` identifies row omissions only. Failed replication evidence is not "no
replication." Server-wide counters and default-schema-associated sessions/digests are not this JVM's workload.
Unknown values are `null`; large/unsigned counters, byte sizes, and numeric IDs are exact decimal strings and must
retain precision.
Do not request raw session/sample SQL or lock values, infer recommendations from absent metrics, grant privileges,
or enable Performance Schema automatically. See [MySQL](features/database.md#mysql) for the eight areas and
capability-specific permissions, and [CLI equivalents](CLI.md#mysql-reads).

### Reading retained advisor violations

The seven rule-based advisors keep compact `sampleViolations` previews: at most ten per result, or twenty for the
Quarkus application and Security advisors. `violationCount` is the actual counted total, not the preview size.
First read the cached report, then use its `violationDetails.scanId` with the matching detail tool:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_architecture_report","arguments":{}}}
```

For example, if the report identifies scan `scan-opaque-1` and rule `ARCH-SPRING-004`:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_architecture_rule_violations","arguments":{"id":"ARCH-SPRING-004","scanId":"scan-opaque-1","offset":0,"limit":100}}}
```

Each page contains `scanId`, `ruleId`, `violationCount`, `retainedCount`, `truncated`, `violations`, `page`, and
`locations`. Advance `offset` by `page.returned` until `page.hasMore` is false, keeping the same rule and scan ID.
Both `page.total` and `page.matched` count **retained entries for that rule**, not `violationCount`.
An offset at or past the retained end returns an empty terminal page.

The default offset is zero and page size is 100, capped at `min(1000, bootui.mcp.max-results)` (or
`bootui.cli.max-results` on the CLI facade). `id` and `scanId` must be nonblank strings; offsets must be
nonnegative integers and limits positive integers. Nulls, fractional/overflowing numbers, and undeclared arguments
are refused, not silently normalized.

Report-level `violationDetails` carries `scanId`, `total`, `retained`, `retentionLimit`, `truncated`, and
`locationNotes`.
Only the latest scan is retained, by default up to 10,000 sanitized details across that advisor's rules, configurable
with `bootui.advisors.max-retained-violations`. A truncated report or rule is not a complete retained list, even when
`page.hasMore` becomes false; a rule can have a positive count and zero retained details. Increasing the retention
limit cannot recover discarded details without an explicitly authorized new scan. This retrieval completeness is
separate from the report's `evidence` coverage. Dismissal does not change the scan ID or remove retained details.
Some upstream observations supply a count but not every affected identity; their diagnostics and truncation remain
visible rather than inventing missing detail strings. Raising the retention limit cannot repair that observation gap.
Existing observation bounds also remain: for example, Memory rules that inspect only their top-five inputs do not
inspect more inputs when details are paged. The count is the rule's existing counted sequence, not proof of full coverage.
Always verify each finding against source and effective configuration before editing.

These reads never rerun checks, import classes, query the database, or start a scan. A missing or replaced snapshot
returns a known client failure (REST 409): **reread the cached report, not the scan tool**, then restart paging that
report's scan ID. An unknown/non-finding rule returns REST 404. MCP exposes these as in-band `isError: true` failures
with actionable messages, not internal errors. The CLI facade retains its existing tool-error mapping: unknown
rules are HTTP 400 (HTTP 404 is reserved for an unadvertised tool), and stale snapshots remain HTTP 409.

MCP still refuses an oversized rendered response with JSON-RPC `-32003`. Retry the **same scan ID and offset**
with a smaller `limit`; a byte-budget refusal is neither an empty page nor proof of completion. Do not advance
the offset on any error. Keep pages bounded and stop rather than looping if even one detail exceeds the byte budget.

### Going straight to the code

Architecture, REST API, and Hibernate findings that name exactly one code element carry a structured location, so an
agent can open the file instead of parsing the violation text. In `get_architecture_report`, `get_rest_api_report`,
and `get_hibernate_report`, each result's `sampleLocations` is aligned index-for-index with `sampleViolations`; in the
matching `get_*_rule_violations` page, `locations` is aligned with `violations`. A `null` entry means that violation
has no location, and an empty list means none of them has one. Each location has `className`, `memberName`, `kind`
(`CLASS`, `METHOD`, `CONSTRUCTOR`, or `FIELD`), `sourceFile`, `line`, `sourcePath`, and `precision` (`LINE`,
`MEMBER`, or `CLASS`):

```json
{"className":"com.example.OrderService","memberName":"place","kind":"METHOD","sourceFile":"OrderService.java",
 "line":42,"sourcePath":"/work/shop/src/main/java/com/example/OrderService.java","precision":"LINE"}
```

Open `sourcePath` at `line` when both are present. A `null` `sourcePath` means the class came from an archive, an
unsupported layout, an ambiguous match, or an exhausted lookup budget; `violationDetails.locationNotes` says which.
Hibernate locations never carry a line, and a line BootUI cannot verify, such as Kotlin code inlined from another
file, is dropped rather than guessed. Findings that span several elements, such as package cycles, have no location.
Paths are resolved only by an explicit scan, so reading a cached report or a detail page never touches the disk.

### Reading a bounded result

Advisor tool success means a report was returned, not that its assessment is complete or healthy. Inspect
`scan.status`, `evidence`, and the retained diagnostics. `SCANNED` and `PARTIAL` reports can establish a UI score
when `evidence.usable` is true and the severity data is valid. The additive `evidence` object contains boolean
`usable`, boolean `coverageComplete`, and immutable, bounded, sanitized `limitations`. The Database and Hibernate
advisor reports list the specific gaps in a `diagnostics` array; a Hibernate finding from a partly evaluated rule
also carries a `coverageNote`.
Usability means at least one applicable check completed or a genuine known-severity finding was observed before
filtering or dismissal; missing-evidence notices cannot establish it. Missing metadata, failed checks, and unknown
evidence must not be interpreted as passes. `ERROR`, `DISABLED`, and `NOT_SCANNED` remain unscored.
Incomplete usable evidence retains scan notes, even at 100. UI numbers are **Known-findings scores**, not app-health
grades. Dismissals alter penalties, not application safety. Valid explicit backend evidence alone establishes eligibility;
legacy reports without it remain unscored, with their findings still visible.

For Vulnerabilities also inspect inventory `coverage` and each dependency's `assessment.queryComplete` and
`assessment.detailAssessmentComplete`. Both flags and a genuinely empty retained advisory list establish a completed
no-finding dependency. Known findings (including NONE) can establish eligibility despite other gaps. UNKNOWN incurs
no penalty but UNKNOWN-only findings stay unscored after dismissal unless independent usable evidence exists.
Dismissals remove penalties, not coverage gaps; optional EPSS availability does not determine eligibility.
See [Score eligibility](features/advisors.md#score-eligibility). Existing MCP/CLI commands return the additive facts;
no new tool or backend numeric scorer is introduced.

Advisor scores and the Overall score are calculated in the browser, not by a separate MCP/CLI scorer. The Scorecard
averages eligible visible advisor scores and GitHub's eligible security-alert score; missing signals never supply a fake
100.
`get_overview` (`bootui overview`) returns application context, not that aggregate. CLI transport success and JSON output
must not be interpreted as a passing assessment.

Every search- or list-style tool returns its rows next to the same `page` envelope, so one reading applies to all of
them:

| Field | Meaning |
| --- | --- |
| `total` | Every item the panel can see, **before** the query and filters are applied |
| `matched` | How many of those the query and filters kept |
| `offset` | Where this page starts within the matched items |
| `limit` | The page size actually used, capped by `bootui.mcp.max-results` (`bootui.cli.max-results` from the CLI) |
| `returned` | How many rows this response carries |
| `hasMore` | Whether matched items remain past this page: narrow the `query`, or raise `limit` up to the server's maximum |

List and search tools take no `offset`; only the advisor violation reads page with one (see
[Reading retained advisor violations](#reading-retained-advisor-violations)). The `offset` in the envelope is where the
page starts, `0` for these tools.

The distinction that matters is `total` versus `matched`. A large `total` beside `matched: 0` does **not** mean the
data is missing; it means the query matched none of it. Retry with a shorter query before concluding a property,
bean, or mapping does not exist.

`get_config` additionally matches names through relaxed binding — case is ignored and `_` and `-` are treated as `.`
— so `bootui.mcp.enabled` finds a value supplied as the environment variable `BOOTUI_MCP_ENABLED`, which every
property source enumerates under that literal name. Values are matched literally, and each row still reports the exact
name and source its property source published. See the [Configuration panel](features/configuration.md#configuration)
for the panel-side behavior.

### Safety model

The MCP server inherits BootUI's full safety posture, so handing it to an agent stays safe by construction:

- It is only ever live while BootUI itself is active. On Quarkus that means it is **absent from a production build**,
  with no flag that turns it on. On Spring the `prod` and `production` profiles disable BootUI, and only an explicit
  `bootui.enabled=ON` overrides that.
- Read tools require the backing panel to be enabled; all action tools are additionally refused when the panel is
  read-only or `bootui.read-only=true`, returning a clear tool error instead of running.
- Values pass through the same secret masking and `bootui.expose-values` mode as the REST API, and paginated reads are
  capped by `bootui.mcp.max-results` (default `200`). `get_log_tail` and `get_exceptions` mask secret-like assignments
  and authorization credentials in messages by default and omit messages under `METADATA_ONLY`, flagging a log line's
  `messageOmitted`.
- MCP request size, concurrency, tool execution time, and rendered response size are independently bounded through
  `bootui.mcp.*`; capacity, timeout, and response-limit failures are explicit rather than silently truncated.

See [Properties](PROPERTIES.md) for the `bootui.mcp.*` settings and [Features](features/developer-tools.md#mcp-server) for the full MCP Server panel
description. `get_agent_status` is read-only and reports only the local BootUI Java agent state, setup snippets, and
claim metadata.


## Protocol eras

BootUI selects the era of each `POST /bootui/api/mcp` from the request itself, as MCP 2026-07-28's backward
compatibility rules describe:

- **Legacy (MCP 2025-06-18).** A request without `_meta["io.modelcontextprotocol/protocolVersion"]`, and every
  `initialize`, is served as in BootUI 1.x: `initialize`, `ping`, the same result shapes, and error codes
  `-32000` (disabled), `-32001` (at capacity), `-32002` (timeout), and `-32003` (response too large). An
  `MCP-Protocol-Version` header other than `2025-06-18` is refused with `400` and `-32600`.
- **Modern (MCP 2026-07-28).** A request whose `_meta` names a protocol version is validated in this order, each failure
  being `400`: the version must be a string (`-32602`); `MCP-Protocol-Version` must be sent once and equal it
  (`-32020`); an unsupported version answers `-32022` with `data.supported` (`["2026-07-28", "2025-06-18"]`) and
  `data.requested`; `io.modelcontextprotocol/clientCapabilities` must be an object (`-32602`); `Mcp-Method` must be sent
  once and equal the method (`-32020`); for `tools/call` and `prompts/get`, `Mcp-Name` must be sent once and equal
  `params.name`, after decoding the `=?base64?…?=` form (`-32020`); a `progressToken` must be a string or an integer
  (`-32602`). A request whose `_meta` names `2025-06-18` is served as legacy.
- **Modern results.** Every result carries `resultType: "complete"` and `_meta["io.modelcontextprotocol/serverInfo"]`.
  `server/discover`, `tools/list`, and `prompts/list` also carry `ttlMs: 60000` and `cacheScope: "private"`; tools stay
  in catalog order. Modern clients have no `initialize` or `ping`; an unknown method answers `404` with `-32601`.
  BootUI's own server errors move out of the JSON-RPC reserved range for modern clients: `-31000` (disabled), `-31001`
  (at capacity), `-31002` (timeout), and `-31003` (response too large), with the same messages.
- **Unchanged in both eras.** Loopback, Host, cross-site write, token, panel enable and read-only, masking,
  payload/response limits, concurrency, and `bootui.mcp.execution-timeout` apply exactly the same way. Notifications
  answer `202`. Responses are single JSON objects; request-scoped progress over `text/event-stream` is planned
  ([#1340](https://github.com/jdubois/boot-ui/issues/1340)).
## Assess an application and approve an action plan

When you do not know which panel to investigate first, ask your coding agent for an application assessment:

> Assess this application using BootUI. Start with existing evidence and ask before fresh scans. Give me a prioritized,
> evidence-backed action plan, and do not modify anything until I approve specific actions.

The BootUI skill — installed [on its own](#install-the-bootui-agent-skill) or through the
[Claude Code plugin](#install-the-bootui-claude-code-plugin) — teaches this workflow through MCP, the CLI, or the plain
HTTP command-line endpoint. MCP clients with prompt support can select **`assess_application`** instead. BootUI advertises
four argument-free prompts: `diagnose_runtime_issue` for a focused runtime failure, `verify_after_change` to run the tests,
compare with the previous run, and stop, `review_application` for a focused
advisor review, and `assess_application` for a broader assessment and approval-gated plan. Clients without prompt support
can use the skill and the request above; there is no `bootui assess` command or new assessment tool.

### What the assessment does

The agent identifies the running application and its source repository, discovers the actual tool catalog and panel
policies, and starts with Overview, Health, cached reports, and bounded diagnostic summaries. It accounts for relevant
capabilities rather than blindly invoking every tool. Spring MVC, WebFlux, and Quarkus share the workflow but may expose
different capabilities.

Fresh scans require an explicitly approved scope. The agent names the scans before asking and requests separate approval
for memory scans that may trigger a full GC, pentest loopback probes, OSV.dev vulnerability queries, and Database Advisor
metadata inspection of the configured database. An assessment never authorizes clearing data, generating traffic,
installing integrations, weakening panel policy, or changing source code.

Collection has a declared time and tool-call budget; approved scans run sequentially. Busy, failed, and timed-out calls
remain visible instead of causing endless retries or discarding other evidence. The agent records coverage as
`assessed`, `unavailable`, `skipped`, `failed`, or `insufficient-evidence`, including stale reports and partial/paged
results. An idle application with no SQL traces is not evidence that database access is efficient.

### What the plan contains

Plans use four sections:

| Section | Contents |
| --- | --- |
| Context | Plan ID/version, goal, application identity, repository revision and working-tree state, collection window, budgets, approved scan scope, and missing context. |
| Coverage | Relevant capabilities, status and reason, evidence references, report timestamps, freshness, and collection limits. |
| Actions | Stable action IDs, priority and impact, observed evidence, confidence and uncertainties, proposed changes, dependencies, risk, and concrete acceptance criteria. |
| Approval | Selected action IDs proposed for this plan version, awaiting your explicit approval. |

The agent inspects source where available, distinguishes facts from hypotheses, respects dismissed findings, and groups
related findings only when evidence supports the relationship. Actions cite advisor/rule IDs **and affected targets**,
exception/trace identifiers, timestamps, and links back to the appropriate panels. Missing evidence can produce an
investigation action instead of a speculative fix. Native-image or CRaC readiness remains optional unless relevant to
your goal.

The agent stops after presenting the plan. For example, after reviewing plan `P1` version `1`, you can say:

> Approve A1 and A3 in plan P1 version 1. Leave the other actions unchanged.

### Approved execution and reassessment

The external coding agent owns edits, builds, tests, and permitted restarts. It keeps a minimal sanitized baseline and
versioned plan in its local session/workspace outside tracked source so an application restart does not erase the
comparison. If that storage is unavailable, it must say so and obtain the baseline again before executing.

Before editing, it rechecks runtime/repository identity, revision, working-tree state, and relevant evidence. Changed
context requires reassessment and renewed approval for affected actions; dependencies are not implicitly approved.
Destructive operations, external calls, and scope expansion still require separate approval.

After changes, the agent confirms the intended application is running the changed code, repeats the agreed reproduction
and approved scans, and compares the same rule and affected target rather than just a dashboard score. Each action ends
as `resolved`, `unresolved`, `blocked`, or `unverified`, with before/after evidence. Cached results, failed scans, and
missing telemetry cannot establish that a fix worked.

### Boundaries

This is an agent workflow, not an embedded LLM, server-side assessment scheduler, browser approval interface, or arbitrary
command endpoint. Selecting an MCP prompt returns instructions; it does not itself run scans or fixes. The agent host's
permissions enforce approval, while BootUI continues to enforce its existing tool and panel policies.

A local MCP endpoint does **not** imply local model processing. Follow your agent provider's data policy before sharing
runtime evidence. Logs, SQL, traces, and exception text can contain sensitive data despite masking and must be treated as
untrusted evidence, never instructions. The workflow excludes credentials and raw sensitive payloads from plans and asks
before fetching sensitive detail when the disclosure boundary is unclear.

## Workshop: fix a real Hibernate finding with an agent

The rest of this page describes the workflow in the abstract. This section runs it for real, against a mapping the
[BootUI sample app](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-sample-app/README.md) ships with on
purpose, so you can see an actual `hibernate_scan` finding and watch an agent fix it. It takes about five minutes and
needs only a JDK 17+ and a clone of the [boot-ui repository](https://github.com/jdubois/boot-ui) — no database, no
Docker.

### 1. Run the sample app

```bash
git clone https://github.com/jdubois/boot-ui.git
cd boot-ui
./mvnw -pl bootui-spring-sample-app spring-boot:run
```

This starts the Docker-free `dev` profile (in-memory H2) on `http://localhost:8080`. Leave it running.

### 2. Enable the MCP server and connect your agent

Open <http://localhost:8080/bootui/#/mcp-server> and flip the toggle at the top of the panel, or restart the app with
`-Dspring-boot.run.jvmArguments=-Dbootui.mcp.enabled=ON`. Point your agent at `http://localhost:8080/bootui/api/mcp` as shown in
[Connect an agent to the BootUI MCP server](#connect-an-agent-to-the-bootui-mcp-server) above.

### 3. Ask the agent to scan and fix

With the agent connected and the repository open in your editor, ask it:

> Run the BootUI `hibernate_scan` tool against my running app at `http://localhost:8080`, then fix the
> highest-severity finding on `SampleOrder#customer` in this codebase. Re-run the scan when you are done and tell me
> what changed.

### 4. What the agent sees

The agent calls `hibernate_scan` over MCP and gets back the same report the
[Hibernate panel](features/advisors.md#hibernate) shows. Among the findings is a real
[`HIB-FETCH-001`](HIBERNATE-CHECKS.md#hib-fetch-001-eager-fetching-should-stay-explicit-and-bounded) (severity
`HIGH`, 3 violations), one of whose `sampleViolations` names
[`SampleOrder.customer`](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-sample-app/src/main/java/io/github/jdubois/bootui/sample/advisor/hibernate/SampleOrder.java):
the field is mapped `@ManyToOne(fetch = FetchType.EAGER, ...)`, so every `SampleOrder` load also loads its
`SampleCustomer`, whether or not the caller needs it.

### 5. What the agent changes

Reading the finding's remediation hint, the agent edits `SampleOrder.java` and changes the association to
`@ManyToOne(fetch = FetchType.LAZY, ...)`, keeping the other annotations on the field untouched. That is the whole
fix — `SampleCustomer` is now loaded only when `order.getCustomer()` is actually called, or fetched explicitly with a
join or entity graph where a use case needs it up front.

### 6. Verify

The agent re-runs `hibernate_scan`. `HIB-FETCH-001`'s `violationCount` drops from 3 to 2, and its `sampleViolations`
no longer mention `SampleOrder#customer` — confirmed against the actually running app, not by re-reading the source.
`HIB-FETCH-001` itself does **not** disappear from the report: `SampleAppPreferences#enabledFeatures` and
`SampleOrder#details` are separate, intentional eager-fetch fixtures the same rule also catches, so the rule keeps
firing until those are fixed too. Confirm just the one violation is gone from a terminal with:

```bash
bootui hibernate scan --json \
  | jq '.results[] | select(.id == "HIB-FETCH-001") | .sampleViolations[] | select(contains("SampleOrder#customer"))'
```

An empty result means the fix held.

`SampleOrder` intentionally ships with several other mappings that trip other Hibernate checks (see the comments in
the source file), so a fresh clone always has this same finding to practice on. Discard the change afterwards
(`git checkout -- bootui-spring-sample-app`) if you want to leave the fixture as-is for next time, or keep it if
you're using the sample app as a personal scratch pad.

### The same pattern for every advisor

The agent reads grounded findings from the running app, applies a targeted fix in source, and re-scans to verify —
instead of guessing from static code alone. Repeat the loop with `spring_scan`, `security_scan`, and the other
advisors against your own application. The advisor rulesets are documented under the *Diagnostic checks* section (for
example [Hibernate checks](HIBERNATE-CHECKS.md) and [Spring checks](SPRING-CHECKS.md)).

## The same tools from a terminal

Every tool on this page is also a `bootui` command — see [MCP server or CLI?](#mcp-server-or-cli) above for when to
reach for the [command-line guide](CLI.md) instead of the MCP server.

## Coffilot: BootUI in the GitHub Copilot App's side panel

[Coffilot](https://www.julien-dubois.com/coffilot/) is a GitHub Copilot **canvas extension** that turns a Maven- or
Gradle-based Java / Spring Boot / Quarkus project into an interactive console inside the GitHub Copilot App's side panel.
You can **build, test, package, and run** your app, watch **live JVM metrics**, and — when something breaks — push the
failure straight back to the agent with **Fix with Copilot**, all without leaving the chat.

Coffilot and BootUI are designed to work together: Coffilot is the cockpit that launches and watches your app, and BootUI
is the rich data source and advisor engine behind it.

### How they work together

- **Richest metrics tier.** Coffilot sources live metrics from the best endpoint available, degrading gracefully:
  BootUI → Spring Boot Actuator → Quarkus Micrometer/health → coarse process metrics. When your running app exposes
  BootUI at `/bootui/api/**`, Coffilot uses BootUI's sanitized DTOs and shows a `BootUI` badge on the metrics panel.
- **One-click advisor scans.** With BootUI present, Coffilot adds an advisor-scan panel. A toggle enables BootUI's MCP
  server, and you can run the scans (architecture, Spring, security, Hibernate, …) and send findings to the agent with a
  single click.
- **Native MCP tools in the agent.** Coffilot's **Register with Copilot** button wires the running BootUI MCP server into
  your Copilot CLI configuration, so the agent can call the BootUI scan tools directly as native MCP tools — the same
  tools described above, without editing `mcp.json` by hand.

### A typical Coffilot + BootUI loop

1. Add the [BootUI starter](SETUP.md) to your app and install Coffilot from its website
   (`https://www.julien-dubois.com/coffilot/`).
2. In a Copilot session, open the **Coffilot** canvas and **Run** your app (pick a module and run profile).
3. Once the app is up, Coffilot detects BootUI and shows rich metrics plus the advisor-scan panel.
4. Enable the MCP server from Coffilot and click **Register with Copilot** so the agent can call the BootUI scan tools.
5. Run a scan (or ask the agent to), let the agent fix the findings, then re-run and re-scan from the same panel to
   verify.

See the [Coffilot website](https://www.julien-dubois.com/coffilot/) for installation details and the full capability
matrix.
