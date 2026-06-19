# The BootUI family

BootUI is one of three projects by [Julien Dubois](https://www.julien-dubois.com) that share a single Java workflow —
from scaffolding an application, to observing it from the inside, to driving its build and run lifecycle from Copilot.
Each owns one colour in a shared **circle of color**:

| Colour        | Project                                               | Role                                                                                        |
| ------------- | ----------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| 🟢 Green      | BootUI (this site)                                    | An in-app developer console served by the running Spring Boot app over `/bootui/`.          |
| 🔵 Blue       | [Coffilot](https://www.julien-dubois.com/coffilot/)   | A Copilot canvas extension that builds, tests, runs and debugs the app from the side panel. |
| 🟤 Terracotta | [Dr JSkill](https://www.julien-dubois.com/dr-jskill/) | Generates a Spring Boot application to start from.                                          |

## How they work together

1. **Generate the app with Dr JSkill.** Start from a freshly scaffolded Spring Boot 4 application instead of a blank
   directory, so the project layout, build, and dependencies are in place from the first commit.
2. **Add the BootUI starter.** Drop in the BootUI starter to get a local-only, in-app developer console at `/bootui/`,
   backed by its REST API at `/bootui/api/**`. It activates automatically in the `dev` / `local` profiles and stays
   silent and disabled in production.
3. **Drive build/run/test/debug from the Copilot side panel with Coffilot.** Build, test, run, and debug the same app
   from the GitHub Copilot App's side panel, and let Coffilot read BootUI's endpoints to surface richer runtime insight
   without leaving your editor.

## Where BootUI fits

BootUI is the project that observes the app **from the inside**. While it is active, the running Spring Boot application
serves its own developer console — health, metrics, live memory, threads, startup timeline, configuration, beans,
mappings, and advisor scans for architecture, Spring, security, Hibernate, REST API, JVM memory, GraalVM and CRaC
readiness, and more. Every panel reuses the same controllers and immutable DTOs that back the loopback-only REST API at
`/bootui/api/**`, so the data is already sanitized and secret-masked before it leaves the process.

That REST surface is also what lets [Coffilot](https://www.julien-dubois.com/coffilot/) reach its richest tier. When
Coffilot detects BootUI on a running app, it sources live JVM metrics from BootUI's sanitized DTOs (and shows a `BootUI`
badge), and it adds a REST advisor-scan panel that runs BootUI's scans and hands the findings straight back to the agent
for a fix-and-rescan loop. Both BootUI and Coffilot stay strictly loopback-only, so this richer integration never widens
your app's exposure.

## Learn more

- [Coffilot](https://www.julien-dubois.com/coffilot/) — build, run, test and debug the app from the Copilot side panel.
- [Dr JSkill](https://www.julien-dubois.com/dr-jskill/) — generate the Spring Boot application to start from.
- [AI agents](AI-AGENTS.md) — connect Copilot, Claude Code, or any MCP client to BootUI.
- [Features](FEATURES.md) — the full list of BootUI panels and what each one shows.
