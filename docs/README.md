---
home: true
heroText: BootUI
tagline: A local-only developer console for Spring Boot 4 and Quarkus applications.
actions:
  - text: Explore features
    link: /features
    type: primary
  - text: Super easy setup
    link: /setup
    type: secondary
features:
  - title: Runtime observability
    details: Inspect health, metrics, memory, threads, heap dumps, startup timing, and JVM sizing from the running Spring Boot or Quarkus app.
  - title: Advisors dashboard
    details: Analyze and score your application with advanced advisors for architecture, REST API, Spring, Hibernate, JVM memory, Spring Security, pentesting, and vulnerabilities.
  - title: Diagnostics toolbox
    details: Review traces, log tail, HTTP exchanges, local probes, architecture checks, GraalVM readiness, and dependency vulnerabilities.
  - title: Data and services visibility
    details: Explore database pools, Spring Data repositories, Hibernate checks, Flyway, Liquibase, scheduled tasks, caches, and dev services.
  - title: Local safety model
    details: Stay loopback-only by default with secret masking, fail-closed activation, read-only controls, and explicit confirmation for mutating actions.
  - title: Packaged developer console
    details: Add one dependency — the Spring Boot starter or the Quarkus extension — and get the bundled Vue UI, REST API, and docs-backed workflow without a separate frontend deployment.
footer: Apache-2.0 Licensed | BootUI
---

<ScreenshotCarousel />

## Start here

| Goal | Documentation |
| ---- | ------------- |
| Run the full demo locally | [Try the sample app](TRY-SAMPLE-APP.md) |
| Add BootUI to a Spring Boot 4 or Quarkus app | [Setup](SETUP.md) |
| Explore every panel | [Features](FEATURES.md) |
| Configure activation, safety, panels, and actions | [Properties](PROPERTIES.md) |
| Drive BootUI from an AI coding agent | [AI agents](AI-AGENTS.md) |

## How BootUI works

BootUI is served by the host application at `/bootui/`, uses internal `/bootui/api/**` endpoints, and packages the Vue UI
so consuming applications do not need Node.js or npm. The same console runs on **Spring Boot 4** (servlet or WebFlux)
and **Quarkus**, backed by a shared, framework-neutral engine that serves an identical REST contract on any of the
three.

It stays local by default: development-profile activation, loopback-only access, secret masking, read-only controls, and
production-profile disablement. Some panels depend on optional Spring, Actuator, or development infrastructure. When data
is unavailable, BootUI returns stable empty responses or shows an actionable empty state.

## What BootUI includes

- Runtime views for health, metrics, memory, threads, heap dumps, startup timing, and JVM tuning.
- Configuration tools for masked properties, profile diffs, runtime overrides, loggers, beans, conditions, and mappings.
- Data and service panels for database pools, Spring Data, Hibernate, Flyway, Liquibase, caches, scheduled tasks, and dev
  services.
- Diagnostics and security panels for traces, logs, HTTP exchanges, local probes, architecture checks, GraalVM readiness,
  dependency vulnerabilities, Spring Security, and security advisors.
- Developer tooling dashboards for DevTools, GitHub, Copilot, and Claude Code local activity.
