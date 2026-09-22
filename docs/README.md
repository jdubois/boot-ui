---
home: true
heroText: BootUI
tagline: A local-only developer console for Spring Boot 4 and Quarkus applications.
actions:
  - text: Explore features
    link: /features
    type: primary
  - text: Set up BootUI
    link: /setup
    type: secondary
features:
  - title: Runtime observability
    details: Inspect health, metrics, memory, threads, heap dumps, startup timing, and JVM sizing from the running Spring Boot or Quarkus app.
    link: /features/runtime
    linkText: Runtime panels
  - title: Advisors dashboard
    details: Analyze and score your application with advanced advisors for architecture, REST API, Spring, Hibernate, JVM memory, Spring Security, pentesting, and vulnerabilities.
    link: /features/advisors
    linkText: Advisor catalog
  - title: Diagnostics toolbox
    details: Review traces, log tail, HTTP exchanges, local probes, architecture checks, GraalVM readiness, and dependency vulnerabilities.
    link: /features/diagnostics
    linkText: Diagnostics panels
  - title: Database insight
    details: Inspect connection pools, PostgreSQL vital signs, SQL traces, Hibernate statistics, transactions, Spring Data repositories, Flyway, and Liquibase.
    link: /features/database
    linkText: Database panels
  - title: Services and integrations
    details: Follow scheduled tasks, REST clients, fault tolerance, WebSockets, caches, email, Kafka, RabbitMQ, and JMS.
    link: /features/services
    linkText: Services panels
  - title: Local safety model
    details: Stay loopback-only by default with secret masking, fail-closed activation, read-only controls, and explicit confirmation for mutating actions.
    link: /setup/activation
    linkText: Activation and safety
footer: Apache-2.0 Licensed | BootUI
---

<ScreenshotCarousel />

## Start here

| Goal | Documentation |
| ---- | ------------- |
| Run the full demo locally | [Try the sample app](TRY-SAMPLE-APP.md) |
| Add BootUI to a Spring Boot 4 or Quarkus app | [Setup](SETUP.md) |
| Explore every panel | [Features](features/README.md) |
| Configure activation, safety, panels, and actions | [Properties](PROPERTIES.md) |
| Drive BootUI from an AI coding agent | [AI agents](AI-AGENTS.md) |
| Ask a running application from a terminal or CI | [Command line](CLI.md) |

## How BootUI works

Your application serves the console at `/bootui/` and its JSON API at `/bootui/api/**`. The Vue UI is packaged inside
the jar, so your build needs no Node.js and no npm.

The same console runs on Spring Boot 4 (servlet or WebFlux) and on Quarkus. A shared, framework-neutral engine serves
an identical REST contract on all three.

BootUI is a development tool and stays one by default. It activates only in development, accepts only loopback callers,
masks secret-like values, and disables itself in production profiles.

Panels that depend on optional Spring, Actuator, or development infrastructure stay visible when that infrastructure is
missing. They explain what is unavailable instead of disappearing.
