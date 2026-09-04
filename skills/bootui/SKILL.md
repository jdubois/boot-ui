---
name: bootui
description: Install, configure, and use BootUI in Spring Boot 4 or Quarkus applications; inspect and improve a running application through BootUI's command-line endpoint, MCP tools, or browser panels. Use when asked to add or troubleshoot BootUI, and when a question is about what a locally running application is actually doing — a slow or failing endpoint, an exception, SQL or Hibernate behavior, beans, mappings, configuration, health, metrics, logs, traces — or when asked for an architecture, security, memory, database, REST, pentest, GraalVM, CRaC, or vulnerability scan, or to connect an AI agent to BootUI.
license: Apache-2.0
---

# BootUI

Use BootUI as a local, runtime-grounded source of information for Spring Boot 4 and Quarkus 3 applications. Keep it
local-only, preserve its fail-closed defaults, and make the smallest application change that addresses the user's request.

## Establish the application context

Before changing anything:

1. Identify the build tool and use its wrapper when present.
2. Identify the framework and web stack:
   - Spring Boot servlet
   - Spring Boot WebFlux
   - Quarkus
3. Confirm Java 17 or later and a supported framework version.
4. Find the runnable module, active development profile, configured HTTP port, and existing BootUI dependency.
5. Run the project's existing focused tests before and after changes when practical.

Do not add both Spring starters. Do not add a Spring starter to Quarkus or the Quarkus extension to Spring.

## Install BootUI

Add the dependency only when the user asked for BootUI or approves the change. If a diagnostic request arrives and
BootUI is not on the classpath, say so first rather than installing it silently.

Determine the latest stable BootUI version from Maven Central or the
[BootUI releases](https://github.com/jdubois/boot-ui/releases); do not guess a version or use a snapshot unless requested.
Use the project's existing dependency-management and formatting conventions.

Choose exactly one dependency:

| Application | Maven coordinates |
| --- | --- |
| Spring Boot servlet | `com.julien-dubois.bootui:bootui-spring-boot-starter` |
| Spring Boot WebFlux | `com.julien-dubois.bootui:bootui-spring-boot-starter-reactive` |
| Quarkus | `com.julien-dubois.bootui:bootui-quarkus` |

For Spring, prefer a runtime-only Gradle configuration when that matches the build. The Quarkus extension may remain an
implementation dependency. Do not add `bootui-quarkus-deployment` directly.

Activate and run the application in development:

- Spring Maven: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- Spring Gradle: `./gradlew bootRun --args='--spring.profiles.active=dev'`
- Quarkus Maven: `./mvnw quarkus:dev`
- Quarkus Gradle: `./gradlew quarkusDev`

BootUI normally opens at `http://localhost:<port>/bootui`. On Spring, `dev` or `local`, DevTools, or
`bootui.enabled=ON` activates it. On Quarkus, dev and test launch modes activate it; production builds remain dark and
cannot be forced on.

After installation, verify the application starts, the BootUI banner or URL appears, and
`GET http://127.0.0.1:<port>/bootui/api/overview` returns JSON — `bootui overview --url http://127.0.0.1:<port>` does
the same check when the CLI is installed. Do not treat an unavailable optional panel as an installation failure.

## Configure BootUI safely

Only add configuration required by the user's goal. Prefer these safe controls:

- `bootui.read-only=true` to block all actions.
- `bootui.panels.<panel-id>.enabled=false` to hide a panel and reject its API.
- `bootui.panels.<panel-id>.read-only=true` to keep reads while blocking its actions.
- `bootui.expose-values=MASKED` and `bootui.mask-secrets=true` as the normal disclosure posture.
- `bootui.cli.enabled=false` only when the user wants the command-line endpoint off; it is enabled by default.
- `bootui.mcp.enabled=ON` only when the user wants the MCP server enabled at startup.

Never set `bootui.allow-non-localhost=true`, `bootui.expose-values=FULL`, broad trusted proxy ranges, or permissive
allowed hosts merely to make a failing request work. Explain the risk and use the narrowest local alternative. For a
container, prefer a localhost-bound published port and `bootui.trust-container-gateway=AUTO`.

Spring supports runtime configuration overrides in `.bootui/application-bootui.properties`; already-bound configuration
may require a restart. The Quarkus Configuration panel is read-only. Do not edit `bootui.internal.*` properties.

Use the full property reference at
`https://github.com/jdubois/boot-ui/blob/main/docs/PROPERTIES.md` when a setting is not listed here.

## Choose how to reach BootUI

BootUI answers the same questions from one tool registry, under the same per-panel enable and read-only policy. The CLI
and the MCP server are two spellings of that registry; the browser panels are the human-facing view of the same data.
Pick the route already available instead of setting up another:

1. **If your tool list already contains BootUI MCP tools**, call them directly. Nothing to install or enable. If one
   answers that the MCP server is disabled, that is the one case where enabling MCP is the right move: tell the user, or
   set `bootui.mcp.enabled=ON`, rather than abandoning the request.
2. **Otherwise use the `bootui` command line.** Check for it with `command -v bootui` (`Get-Command bootui` in
   PowerShell). Its endpoint is enabled by default, needs no client configuration and no restart, and returns the same
   masked JSON.
3. **If the CLI is not installed, call the endpoint over plain HTTP** rather than installing anything. It is ordinary
   REST, so `curl` is enough.
4. **Point the user at the browser panels** when a human should look, or for a screenshot.

Do not enable the MCP server, edit a client configuration, or restart the application merely to run a diagnostic that
options 2 and 3 already answer. Set MCP up as a connection when the user asks to connect an agent or MCP client to
BootUI.

## Use the command-line endpoint

The CLI asks a running application one question and prints the answer. It talks to `GET /bootui/api/cli` and
`POST /bootui/api/cli/tools/{name}`, which are enabled by default and independent of `bootui.mcp.enabled`.

**Do not install anything to answer a one-off question.** The endpoint is plain REST with no JSON-RPC envelope and no
token on loopback, so `curl` reaches the same tools under the same policy:

```bash
curl -fsS http://127.0.0.1:8080/bootui/api/cli                       # the catalog
curl -fsS -X POST -H 'Content-Type: application/json' -d '{}' \
  http://127.0.0.1:8080/bootui/api/cli/tools/get_overview
curl -fsS -X POST -H 'Content-Type: application/json' -d '{"limit": 20}' \
  http://127.0.0.1:8080/bootui/api/cli/tools/get_http_exchanges
```

Always send `Content-Type: application/json` and a body, `{}` when the tool takes no argument, exactly as the CLI does.
Add `-H "Authorization: Bearer $BOOTUI_TOKEN"` when `bootui.authentication.token` is set. The tool names are the MCP
tool names; `bootui tools` and the catalog both list them. On this path the outcome is the HTTP status rather than an
exit code: `403` is the panel refusing, the same condition the CLI reports as `2`.

Install the `bootui` command only when the user wants it, or when repeated calls make it worth it — and ask first,
because it writes an executable to their machine. It needs a JDK 17 or later. Prefer a source the user can verify:
[JBang](https://www.jbang.dev) resolves it from Maven Central with `jbang app install bootui@jdubois/boot-ui`, and the
jar can be downloaded from `repo1.maven.org` and run with `java -jar`. The
[installer scripts](https://github.com/jdubois/boot-ui/blob/main/docs/CLI.md#install) are a third option; do not pipe
one into a shell on the user's behalf without their explicit agreement.

Once it is on the `PATH`:

```bash
bootui tools                                   # what this application actually exposes
bootui --url http://127.0.0.1:8080 overview
bootui hibernate scan --json | jq '.severityCounts'
bootui exceptions show <id> --json
```

- `--url` (or `BOOTUI_URL`) defaults to `http://localhost:8080`; pass the application's real port.
- `--api-path` is only needed when `bootui.api-path` is customised, `--token` only when
  `bootui.authentication.token` is set, and `--timeout` raises the 60-second wait for a slow scan.
- **Always pass `--json` when parsing.** The human table rendering is not a contract, and terminal auto-detection is
  unreliable on JDK 22 or later.
- `bootui tools` prints a human table whose `status` column reads `ready`, `action`, `read-only`, or `panel disabled`.
  With `--json` it prints the endpoint's own document instead, where each entry in `tools` carries `name`, `panel`,
  `action`, `arguments`, `panelEnabled`, and `panelReadOnly` — but no `status` or `command` field. Derive availability
  from those: `panelEnabled: false` means unavailable, `action: true` with `panelReadOnly: true` means the call would be
  refused. Read this before concluding a stack or panel lacks a capability.
- Exit codes: `0` answered, `1` usage error or unreachable application or a request the tool rejected, `2` BootUI
  declined because the panel is disabled or read-only. Treat `2` as "not available here", not as a failure to work
  around by loosening configuration.
- Prefer the `BOOTUI_TOKEN` environment variable over `--token`, which exposes the token to shell history and process
  listings. Never echo a token or copy it into a report.
- Scan payloads differ: `pentest scan` names its array `findings`, the rule-based advisors name it `results`. Every scan
  shares `severityCounts`, so prefer that for thresholds, and check the shape with `--json | jq keys` first.

In CI, capture the exit code (`bootui … --json > report.json || status=$?`) so a non-zero exit does not abort the step
before the application is stopped.

The full command table is at `https://github.com/jdubois/boot-ui/blob/main/docs/CLI.md`; each command maps to the MCP
tool of the same behavior.

## Use BootUI on a running application

Prefer BootUI's CLI, MCP tools, or browser panels over raw framework internals because BootUI returns bounded, masked
DTOs.

1. Confirm the process, port, framework, and BootUI availability, then run `bootui tools` to see what this application
   really exposes.
2. Read Overview and Health when the application's identity or overall state matters (`bootui overview`,
   `bootui health`, or `get_overview` and `get_health`); go straight to the relevant read when the question is specific.
3. Use Live Activity to correlate recent requests, SQL, exceptions, security events, scheduled work, messaging, and mail.
4. Open the dedicated diagnostic command or panel for full detail.
5. Answer with read commands where you can. Do not run a tool the catalog marks as an action — every `… scan`,
   `clear`, `pause`, `resume`, and heap analysis — unless the user asked for it or approves after you name it. Prefer
   an existing `… report` over a fresh scan, and treat `vulnerabilities scan` as always requiring approval because it
   queries OSV.dev over the network.
6. Record a baseline: finding identifiers and severities, health, failing request, exception, and relevant metrics.

Treat unavailable panels honestly. Their backing library, capability, configuration, or adapter support may be absent.
Do not install unrelated infrastructure solely to light up a panel unless the user asks.

## Optimize or fix the application

Use an evidence-driven loop:

1. Reproduce the issue against the running local application.
2. Collect the smallest useful BootUI evidence set. Prefer finding IDs, exception IDs, trace IDs, request paths, and
   timestamps over large unfiltered dumps.
3. Rank findings by severity and relation to the reported symptom. Advisor suggestions are evidence, not permission for a
   broad refactor.
4. Locate the corresponding application source and configuration.
5. Apply the smallest safe fix, preserving existing architecture and framework conventions.
6. Run focused tests and restart or hot-reload the application as appropriate.
7. Reproduce the request and rerun the same BootUI read or scan.
8. Compare before and after results and report both fixed and remaining findings.

Ask before destructive or state-changing actions such as clearing caches, changing logger levels, writing configuration,
running migrations, deleting data, or capturing heap dumps. Never run vulnerability or external-network scans on page
load; use their explicit action only when requested.

## Connect an agent to the MCP server

Set this up only when the user asks to connect an agent or MCP client to BootUI. If BootUI MCP tools are already in
your tool list, just call them — there is nothing to configure. For a one-off diagnostic, use the CLI instead. The full
guide is at `https://github.com/jdubois/boot-ui/blob/main/docs/AI-AGENTS.md`.

The MCP server is opt-in. Enable it with `bootui.mcp.enabled=ON` or the MCP Server panel toggle, then configure the agent
with the application's actual port. VS Code uses a `servers` block:

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

Claude Code, Cursor, and most other clients use `mcpServers` instead, and Claude Code can register the server directly
with `claude mcp add --transport http bootui http://127.0.0.1:8080/bootui/api/mcp`.

Prefer `127.0.0.1` and replace `8080` when needed. A loopback agent needs no credentials. An agent that reaches the app
from anywhere else — typically an app in a container reached through a published port — must send BootUI's token as
`Authorization: Bearer <token>` on every call or receive `401`; the token is `bootui.authentication.token`, or the value
BootUI generated and logged once at startup. Verify
`GET /bootui/api/mcp-server` before debugging the client; it reports enabled state and advertised tools. Tools are
availability-driven, so do not assume every framework exposes every tool.

When BootUI MCP tools are available:

1. Call core reads such as `get_overview` and `get_health` first.
2. Use targeted diagnostic reads such as `get_live_activity`, `get_exceptions`, `get_exception_detail`,
   `get_sql_traces`, `get_traces`, `get_log_tail`, and `get_http_exchanges`.
3. Run only the advisor relevant to the task, such as `architecture_scan`, `spring_scan`, `hibernate_scan`,
   `memory_scan`, `security_scan`, `pentest_scan`, or `rest_api_scan`.
4. Use identifiers returned by summary tools to request detail rather than repeatedly fetching broad result sets.
5. After making and testing a fix, rerun the same tool and compare results.

Read tools honor panel enablement. Scan tools also honor panel and global read-only settings. Results are masked and
paginated reads are capped by `bootui.mcp.max-results`.

## Troubleshoot

- **Connection refused:** verify that the application is running and use its actual port.
- **404 on Spring:** activate a real `dev` or `local` profile, include DevTools, or deliberately set
  `bootui.enabled=ON`. A default profile alone may not be active.
- **404 on Quarkus:** run in dev or test mode. A production build intentionally has no BootUI API.
- **403 non-loopback/host rejection:** use `127.0.0.1` or `localhost`; inspect the rejection reason before changing safety
  configuration.
- **403 panel access:** check the panel's `enabled` and `read-only` settings and the global `bootui.read-only` setting.
- **`bootui` command not found:** do not install it to answer one question — `curl` the same endpoint as shown above.
  Install it only with the user's agreement.
- **CLI cannot reach the application:** it defaults to `http://localhost:8080`; pass the real port with `--url`, and
  `--api-path` when `bootui.api-path` is customised. Add `-v` to see the underlying failure.
- **CLI exits `2`, or `curl` returns `403`:** the panel is disabled or read-only on that application. Report it; do not
  loosen configuration.
- **`curl` returns `503`:** `bootui.cli.enabled=false` is set in configuration. It is not revocable or restorable from
  the browser; the property has to change.
- **MCP endpoint disabled:** enable it in the MCP Server panel or set `bootui.mcp.enabled=ON`, then restart if the property
  changed.
- **Tool not advertised:** check the backing panel's availability and required application capability.
- **Empty diagnostics:** generate a controlled local request that reproduces the behavior, then query again.

Do not weaken safety controls to hide these symptoms. State clearly when BootUI is working but a capability is unavailable.
