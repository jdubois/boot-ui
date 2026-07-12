# AI agents

BootUI is built to be driven by local AI coding agents — GitHub Copilot, Claude Code, and any other client that speaks
the [Model Context Protocol](https://modelcontextprotocol.io) (MCP). Instead of only showing a human the advisor findings
and runtime diagnostics in the browser, BootUI can expose the very same, already-sanitized data to an agent so it can
**consult your running application before proposing a fix** and **verify the fix afterwards** — all without leaving your
editor or chat.

This page explains how to connect an agent to BootUI's MCP server, walks through a concrete example (fixing Hibernate
findings), and shows how BootUI pairs with [Coffilot](https://www.julien-dubois.com/coffilot/) to build, run, and scan your
app from the GitHub Copilot App's side panel.

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

## Install the BootUI agent skill

BootUI ships an agent skill that teaches GitHub Copilot how to install and configure BootUI, inspect a running
application, turn advisor findings into focused fixes, verify those fixes, and connect to the MCP server.

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

- **Advisor scans (actions):** `architecture_scan`, `spring_scan`, `hibernate_scan`, `memory_scan`, `security_scan`,
  `pentest_scan`, `rest_api_scan`, `graalvm_scan`, `crac_scan`. Each runs the same scan as the panel's action button and
  returns the report DTO.
- **Diagnostics reads:** `get_live_activity`, `get_exceptions`, `get_exception_detail`, `get_security_logs`,
  `get_sql_traces`, `get_traces`, `get_log_tail`, `get_http_exchanges`. `get_live_activity` returns the correlated feed
  the [Live Activity panel](FEATURES.md) shows (HTTP requests, SQL statements, exceptions, and security events grouped
  by request/trace); `get_exception_detail` takes a required `id` (from `get_exceptions` or `get_live_activity`) and
  returns that exception group's full stack trace, causes, and individual occurrences.
- **Core context reads:** `get_overview`, `get_health`, `get_config` (masked), `get_beans`, `get_mappings`.

### Safety model

The MCP server inherits BootUI's full safety posture, so handing it to an agent stays safe by construction:

- It is only ever live while BootUI is active, so it is **never reachable in production**.
- Read tools require the backing panel to be enabled; action (`*_scan`) tools are additionally refused when the panel is
  read-only or `bootui.read-only=true`, returning a clear tool error instead of running.
- Values pass through the same secret masking and `bootui.expose-values` mode as the REST API, and paginated reads are
  capped by `bootui.mcp.max-results` (default `200`).

See [Properties](PROPERTIES.md) for the `bootui.mcp.*` settings and [Features](FEATURES.md) for the full MCP Server panel
description.

## Example: fixing Hibernate findings with an agent

A common workflow is to let the agent run an advisor scan, fix the highest-severity findings, and re-scan to confirm.
Here it is end to end with the Hibernate advisor.

1. **Run the app** with BootUI active and the MCP server enabled, with your agent connected as above.
2. **Ask the agent to scan and fix.** For example:

   > Run the BootUI `hibernate_scan` tool against my running app, then fix the highest-severity findings in this
   > codebase. Re-run the scan when you are done and tell me what changed.

3. **The agent calls `hibernate_scan`** over MCP and receives the same report the
   [Hibernate panel](FEATURES.md) shows — a severity-ranked list of findings such as `HIB-FETCH-001` (eager associations
   that should be `LAZY`), each with the offending mapped members and a remediation hint.
4. **The agent edits your code.** Reading the finding above, it changes an eagerly-fetched association to
   `@ManyToOne(fetch = FetchType.LAZY)` and adds an explicit fetch join or entity graph where the data is actually
   needed — exactly the remediation the check recommends.
5. **The agent re-runs `hibernate_scan`** to confirm the finding is gone and the Hibernate score improved. You can repeat
   the loop for `spring_scan`, `security_scan`, and the other advisors.

The same pattern applies to every advisor: the agent reads grounded findings from the running app, applies a targeted
fix in source, and re-scans to verify — instead of guessing from static code alone. The advisor rulesets are documented
under the *Diagnostic checks* section (for example [Hibernate checks](HIBERNATE-CHECKS.md) and
[Spring checks](SPRING-CHECKS.md)).

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
