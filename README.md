# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.37.x-4695EB?logo=quarkus&logoColor=white)](https://quarkus.io/)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)

BootUI adds an embedded, local-only developer console to your application. It runs on **Spring Boot 4** (servlet or
WebFlux) and **Quarkus**, serving the same Vue UI and the same `/bootui/api/**` REST contract from a shared,
framework-neutral engine — add the matching Spring Boot starter or the Quarkus extension and BootUI activates only in
local development.

Read the documentation at <https://www.julien-dubois.com/boot-ui/>.

## Quick links

| Topic | Link |
| ----- | ---- |
| Setup | <https://www.julien-dubois.com/boot-ui/setup> |
| Features | <https://www.julien-dubois.com/boot-ui/features> |
| Properties | <https://www.julien-dubois.com/boot-ui/properties> |
| AI agents | <https://www.julien-dubois.com/boot-ui/ai-agents> |
| Sample app | <https://www.julien-dubois.com/boot-ui/try-sample-app> |
| Repository docs | <https://www.julien-dubois.com/boot-ui/repository> |

## Use with AI agents

BootUI exposes a local, opt-in [Model Context Protocol](https://modelcontextprotocol.io) server so AI coding agents
(GitHub Copilot, Claude Code, …) can run its advisors and read runtime diagnostics while fixing your code. It also pairs
with [Coffilot](https://github.com/jdubois/coffilot), a GitHub Copilot canvas extension that builds, runs, and scans your
app from the GitHub Copilot App's side panel. See the [AI agents guide](https://www.julien-dubois.com/boot-ui/ai-agents).

## Project resources

- [CHANGELOG.md](CHANGELOG.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md)

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
