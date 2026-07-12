---
name: bootui
description: Install, configure, and use BootUI in Spring Boot or Quarkus applications; inspect and improve a running application with BootUI panels, APIs, advisor scans, diagnostics, and its MCP server. Use when asked to add BootUI, troubleshoot its activation or access, investigate runtime behavior, optimize or fix an application using BootUI findings, or connect an AI agent to BootUI.
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
`GET http://127.0.0.1:<port>/bootui/api/overview` returns JSON. Do not treat an unavailable optional panel as an
installation failure.

## Configure BootUI safely

Only add configuration required by the user's goal. Prefer these safe controls:

- `bootui.read-only=true` to block all actions.
- `bootui.panels.<panel-id>.enabled=false` to hide a panel and reject its API.
- `bootui.panels.<panel-id>.read-only=true` to keep reads while blocking its actions.
- `bootui.expose-values=MASKED` and `bootui.mask-secrets=true` as the normal disclosure posture.
- `bootui.mcp.enabled=ON` only when the user wants the MCP server enabled at startup.

Never set `bootui.allow-non-localhost=true`, `bootui.expose-values=FULL`, broad trusted proxy ranges, or permissive
allowed hosts merely to make a failing request work. Explain the risk and use the narrowest local alternative. For a
container, prefer a localhost-bound published port and `bootui.trust-container-gateway=AUTO`.

Spring supports runtime configuration overrides in `.bootui/application-bootui.properties`; already-bound configuration
may require a restart. The Quarkus Configuration panel is read-only. Do not edit `bootui.internal.*` properties.

Use the full property reference at
`https://github.com/jdubois/boot-ui/blob/main/docs/PROPERTIES.md` when a setting is not listed here.

## Use BootUI on a running application

Prefer BootUI's browser panels or MCP tools over raw framework internals because BootUI returns bounded, masked DTOs.

1. Confirm the process, port, framework, and BootUI availability.
2. Read Overview and Health first.
3. Use Live Activity to correlate recent requests, SQL, exceptions, security events, scheduled work, messaging, and mail.
4. Open the dedicated diagnostic panel for full detail.
5. Run only relevant advisor scans. Scans and network-backed actions must be explicit, not triggered just by opening a
   panel.
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

## Connect and use the MCP server

The MCP server is opt-in. Enable it with `bootui.mcp.enabled=ON` or the MCP Server panel toggle, then configure the agent
with the application's actual port:

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

Prefer `127.0.0.1` and replace `8080` when needed. No credentials are required. Verify
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
- **MCP endpoint disabled:** enable it in the MCP Server panel or set `bootui.mcp.enabled=ON`, then restart if the property
  changed.
- **Tool not advertised:** check the backing panel's availability and required application capability.
- **Empty diagnostics:** generate a controlled local request that reproduces the behavior, then query again.

Do not weaken safety controls to hide these symptoms. State clearly when BootUI is working but a capability is unavailable.
