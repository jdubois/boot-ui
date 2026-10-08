# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.40.1_LTS-4695EB?logo=quarkus&logoColor=white)](https://quarkus.io/)

BootUI adds an embedded, local-only developer console to your application. It runs on **Spring Boot 4** (servlet or
WebFlux) and **Quarkus**, serving the same Vue UI and REST contract (`/bootui` and `/bootui/api/**` by default,
configurable with `bootui.path` / `bootui.api-path`) from a shared,
framework-neutral engine — add the Spring Boot starter (one for both stacks) or the Quarkus extension and BootUI
activates only in local development.

Read the documentation at <https://www.julien-dubois.com/boot-ui/>.

> **BootUI 2.0 is in progress on the `v2` branch**, with exact correlation, a runtime journal, Runtime Insights, and run
> comparison. It is not published yet: [build it locally to try it](docs/V2-EARLY-ADOPTERS.md), read its
> [known limitations](docs/KNOWN-LIMITATIONS.md), and share feedback in
> [GitHub Discussions](https://github.com/jdubois/boot-ui/discussions).

## Quick links

| Topic | Link |
| ----- | ---- |
| Setup | <https://www.julien-dubois.com/boot-ui/setup> |
| Features | <https://www.julien-dubois.com/boot-ui/features> |
| MySQL operational view | [Feature guide](docs/features/database.md#mysql) |
| Properties | <https://www.julien-dubois.com/boot-ui/properties> |
| AI agents | <https://www.julien-dubois.com/boot-ui/ai-agents> |
| Command line | <https://www.julien-dubois.com/boot-ui/cli> |
| Sample app | <https://www.julien-dubois.com/boot-ui/try-sample-app> |
| Repository docs | <https://www.julien-dubois.com/boot-ui/repository> |
| Known limitations of 2.0 | [Known limitations](docs/KNOWN-LIMITATIONS.md) |

## Use with AI agents

BootUI exposes a local, opt-in [Model Context Protocol](https://modelcontextprotocol.io) server so AI coding agents
(GitHub Copilot, Claude Code, …) can run its advisors and read runtime diagnostics while fixing your code.

Install the BootUI skill in any [Agent Skills](https://agentskills.io)-compatible coding agent:

```bash
npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui
```

The interactive installer detects supported agents and installs the canonical consumer skill. Native integrations are
also available:

| Agent | Native installation |
| ----- | ------------------- |
| GitHub Copilot | `gh skill install jdubois/boot-ui skills/bootui` |
| Claude Code | `/plugin marketplace add jdubois/boot-ui`, then `/plugin install bootui@bootui` |
| Cursor | Find **BootUI** on [Cursor Directory](https://cursor.directory/plugins/bootui) |

The Cursor and Claude Code plugins install the skill and configure the MCP server together. BootUI also pairs with
[Coffilot](https://github.com/jdubois/coffilot), a GitHub Copilot canvas extension that builds, runs, and scans your
app from the GitHub Copilot App's side panel. See the [AI agents guide](https://www.julien-dubois.com/boot-ui/ai-agents)
for preview, configuration, and safety details.

## Use from a terminal

The `bootui` CLI asks a running application one question and prints the answer, with one command per BootUI
diagnostic — the same set the MCP server exposes, projected mechanically from the same registry.

```bash
curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh
```

```bash
bootui beans --query dataSource
bootui hibernate scan --json | jq '.topFindings[]'
```

It needs no MCP client and no agent, prints exact JSON when piped, and exits `2` when a panel's own policy
declines the call, so CI can branch on it. Windows has a PowerShell installer, and JBang works too — see the
[command-line guide](https://www.julien-dubois.com/boot-ui/cli).

## Project resources

- [CHANGELOG.md](CHANGELOG.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md)

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
