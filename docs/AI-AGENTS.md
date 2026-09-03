# AI agents

BootUI is built to be driven by local AI coding agents — GitHub Copilot, Claude Code, and any other client that speaks
the [Model Context Protocol](https://modelcontextprotocol.io) (MCP). Instead of only showing a human the advisor findings
and runtime diagnostics in the browser, BootUI can expose the very same, already-sanitized data to an agent so it can
**consult your running application before proposing a fix** and **verify the fix afterwards** — all without leaving your
editor or chat.

This page explains how to connect an agent to BootUI's MCP server, when to reach for the [CLI](CLI.md) instead, walks
through a concrete example (fixing Hibernate findings), and shows how BootUI pairs with
[Coffilot](https://www.julien-dubois.com/coffilot/) to build, run, and scan your app from the GitHub Copilot App's side
panel.

## Why use BootUI from an agent

An AI agent reading your source code can only guess at runtime behavior. BootUI closes that gap by giving the agent
grounded, machine-readable context from the *actually running* application:

- **Advisor scans** — architecture, REST API, Spring, Hibernate, JVM memory, Spring Security, pentesting, GraalVM and
  CRaC readiness. The agent gets the same prioritized, severity-ranked findings the panels show, with remediation hints.
- **Runtime diagnostics** — a correlated live activity feed (recent HTTP requests, SQL statements, exceptions, and
  security events grouped by request/trace), full exception detail (stack trace, causes, occurrences) by id, security
  audit events, SQL traces, distributed traces, log tail, and HTTP exchanges, so the agent can correlate a failure with
  what the app actually did.
- **Core context** — application overview, health, effective configuration (secrets masked), beans, and request
  mappings.

Because every tool reuses the same controllers and immutable DTOs as the browser UI, the agent sees exactly the masked,
bounded shape a human would — never raw, unfiltered internals.

## MCP server or CLI?

Every BootUI tool is available two ways, and both give the agent identical data: the same registry, the same panel
policy, the same masked, bounded DTOs. The CLI cannot offer a diagnostic the MCP server does not, and cannot lack one
it does — the command table is generated from the tool registry at build time. Pick whichever fits how your agent
talks to the world:

- **Use the [MCP server](#connect-an-agent-to-the-bootui-mcp-server)** when your agent or IDE speaks MCP natively
  (GitHub Copilot, Claude Code, and other MCP-aware clients). The agent discovers tools, schemas, and descriptions
  automatically and calls them as native tool calls — no shell commands, no JSON parsing glue code. This is the
  primary path this page walks through, and what the [BootUI agent skill](#install-the-bootui-agent-skill) and
  [Coffilot](#coffilot-bootui-in-the-github-copilot-apps-side-panel) wire up automatically.
- **Use the [CLI](CLI.md)** when the agent's host can only run shell commands — a sandboxed or cloud agent without MCP
  wiring, a CI job, or a human running one-off checks in a terminal or script. The BootUI agent skill falls back to
  calling `bootui` commands directly whenever its host doesn't already expose BootUI's MCP tools natively.

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

## Connect an agent to the BootUI MCP server

The BootUI MCP server is a local, opt-in JSON-RPC 2.0 endpoint at `POST /bootui/api/mcp`. It is **disabled by default**
(fail-closed) and, like the rest of BootUI, only reachable over the loopback interface unless non-loopback access is
explicitly enabled, which requires authentication.

1. **Run your app locally with BootUI active** (the `dev` / `local` profiles, or `spring-boot-devtools` on the
   classpath). See [Setup](SETUP.md).
2. **Enable the server.** Set `bootui.mcp.enabled=ON`, or flip the toggle at the top of the **MCP Server** panel
   (`/bootui/#/mcp-server`). The panel toggle overrides the property at runtime for the life of the process, so you can
   turn the server on only while you are pairing with an agent.
3. **Point your agent at the endpoint.** The MCP Server panel shows a ready-to-copy client configuration. It is the
   `servers` block a GitHub Copilot or Claude Code `mcp.json` expects:

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

   Replace `8080` with your application's port. No credentials are needed on loopback — the endpoint is exempt from
   BootUI's browser-only CSRF token so a local non-browser MCP client connects with a plain HTTP config, while the
   loopback, `Host` allow-list, and cross-site write defenses still apply. If you explicitly enable non-loopback access,
   configure the MCP client to send the value from `bootui.authentication.token` (or the token BootUI generated at
   startup) in the standard `Authorization` bearer header.

A `GET /bootui/api/mcp-server` status request returns the advertised tool list, which is handy for inspecting what an
agent will see before you wire it up.

### Tools the agent can call

Tools whose backing panel/controller is absent (for example Hibernate or Spring Security when those libraries are not on
the classpath) are simply not advertised.

- **Advisor scans (actions):** `architecture_scan`, `spring_scan`, `hibernate_scan`, `database_advisor_scan`,
  `memory_scan`, `security_scan`, `pentest_scan`, `rest_api_scan`, `graalvm_scan`, `crac_scan`, and
  `vulnerabilities_scan`. Each runs the same scan as the panel's action button and returns the report DTO;
  `vulnerabilities_scan` additionally makes outbound calls to OSV.dev.
- **Cached advisor reports:** `get_architecture_report`, `get_spring_report`, `get_hibernate_report`,
  `get_database_advisor_report`, `get_memory_report`, `get_security_report`, `get_pentest_report`,
  `get_rest_api_report`, `get_graalvm_report`, `get_crac_report`, and `get_vulnerabilities_report` return the last
  completed report without starting another scan.
- **Diagnostics reads:** `get_live_activity`, `get_exceptions`, `get_exception_detail`, `get_security_logs`,
  `get_sql_traces`, `get_transactions` (Spring MVC/WebFlux only), `get_traces`, `get_log_tail`, `get_http_exchanges`,
  and `get_rest_client_traces`.
  `get_live_activity` returns the correlated feed the [Live Activity panel](features/overview.md#live-activity) shows (HTTP requests, SQL
  statements, exceptions, and security events grouped by request/trace); `get_exception_detail` takes a required `id`
  (from `get_exceptions` or `get_live_activity`) and returns that exception group's full stack trace, causes, and
  individual occurrences.
- **Core context and integration reads:** `get_overview`, `get_health`, `get_config` (masked), `get_beans`,
  `get_mappings`, `get_loggers`, `get_conditions`, `get_http_sessions`, `get_scheduled_tasks`, `get_fault_tolerance`,
  `get_cache_stats`,
  `get_database_connection_pools`, `get_metrics`, `get_live_memory`, `get_jvm_tuning`, `get_heap_dump_report`,
  `get_threads`, `get_startup_timeline`, `get_profile_diff`, `get_spring_data_repositories`,
  `get_flyway_migrations`, `get_liquibase_changesets`, `get_spring_security`, `get_ai_overview`, `get_emails`,
  `get_kafka_activity`, `get_rabbitmq_activity`, `get_jms_activity`, `get_devtools_status`, `get_dev_services`,
  `get_github_dashboard`, `get_copilot_sessions`, and `get_claude_code_sessions`. Stack-specific or unavailable
  capabilities are omitted.
- **Bounded controls (actions):** `clear_exceptions`, `clear_sql_traces`, `pause_sql_trace_recording`,
  `resume_sql_trace_recording`, `clear_transactions`, `pause_transaction_recording`, `resume_transaction_recording`,
  `clear_traces`, `clear_rest_client_traces`, `pause_rest_client_recording`, `resume_rest_client_recording`,
  `analyze_heap_dump`, and `trigger_devtools_livereload`. They never capture or download a heap dump, execute an HTTP
  probe, mutate a database, clear a cache, write GitHub state, restart a dev service, or run an agent command.

### Safety model

The MCP server inherits BootUI's full safety posture, so handing it to an agent stays safe by construction:

- It is only ever live while BootUI is active, so it is **never reachable in production**.
- Read tools require the backing panel to be enabled; all action tools are additionally refused when the panel is
  read-only or `bootui.read-only=true`, returning a clear tool error instead of running.
- Values pass through the same secret masking and `bootui.expose-values` mode as the REST API, and paginated reads are
  capped by `bootui.mcp.max-results` (default `200`).
- MCP request size, concurrency, tool execution time, and rendered response size are independently bounded through
  `bootui.mcp.*`; capacity, timeout, and response-limit failures are explicit rather than silently truncated.

See [Properties](PROPERTIES.md) for the `bootui.mcp.*` settings and [Features](features/developer-tools.md#mcp-server) for the full MCP Server panel
description.

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
