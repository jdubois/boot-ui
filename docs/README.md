---
home: true
heroText: BootUI
tagline: A local-only developer console for Spring Boot and Quarkus applications.
actions:
  - text: Explore features
    link: /features
    type: primary
  - text: Watch the video
    link: '#showcase-video'
    type: secondary
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
    details: Follow Live Activity, inspect Runtime Insights, compare application runs, and drill into traces, log tail, exceptions, and HTTP exchanges.
    link: /features/overview
    linkText: Activity and Runtime Insights
  - title: Database insight
    details: Inspect connection pools, PostgreSQL and MySQL vital signs, SQL traces, Hibernate statistics, transactions, Spring Data repositories, Flyway, and Liquibase.
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
showcaseVideo: true
---

## Start here

| Goal | Documentation |
| ---- | ------------- |
| Run the full demo locally | [Try the sample app](TRY-SAMPLE-APP.md) |
| Add BootUI to a Spring Boot or Quarkus app | [Setup](SETUP.md) |
| Explore every panel | [Features](features/README.md) |
| Learn the runtime-to-fix workflow in three hours | [Workshop](workshop/README.md) |
| Configure activation, safety, panels, and actions | [Properties](PROPERTIES.md) |
| Drive BootUI from an AI coding agent | [AI agents](AI-AGENTS.md) |
| Ask a running application from a terminal or CI | [Command line](CLI.md) |

## Install the BootUI agent skill

Give your coding agent BootUI's installation, runtime-diagnostics, advisor, and safety guidance:

```bash
npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui
```

The interactive installer detects Agent Skills-compatible coding agents and installs the canonical BootUI skill.
Native integrations are also available:

| Agent          | Native installation                                                             |
| -------------- | ------------------------------------------------------------------------------- |
| GitHub Copilot | `gh skill install jdubois/boot-ui skills/bootui`                                |
| Claude Code    | `/plugin marketplace add jdubois/boot-ui`, then `/plugin install bootui@bootui` |
| Cursor         | Find **BootUI** on [Cursor Directory](https://cursor.directory/plugins/bootui)  |

The Cursor and Claude Code plugins also configure BootUI's local MCP server. Read [AI agents](AI-AGENTS.md) to preview
the skill, connect another MCP client, or configure a non-default application port.

## How BootUI works

Your application serves the console at `/bootui/` and its JSON API at `/bootui/api/**`. The Vue UI is packaged inside
the jar, so your build needs no Node.js and no npm.

The same console runs on Spring Boot (servlet or WebFlux) and on Quarkus. A shared, framework-neutral engine serves
an identical REST contract on all three.

BootUI is a development tool and stays one by default. It activates only in development, rejects non-loopback callers,
masks secret-like values, and disables itself in production profiles. Every state-changing action is user-triggered,
and the destructive ones ask for confirmation first.

Panels that depend on optional framework or development infrastructure generally stay visible when it is missing and
explain what is unavailable. PostgreSQL and MySQL are vendor-specific exceptions: without the corresponding JDBC
driver they are absent from the sidebar and manifest. See [panel availability](FRAMEWORK-SUPPORT.md#your-app-is-the-real-answer).
