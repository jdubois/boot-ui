# BootUI workshop

Understand a running application, find a problem from runtime evidence, and verify a focused fix with an AI coding
agent. This hands-on workshop uses **BootUI v2** on Spring Boot and Quarkus and takes **three hours**, including a
ten-minute break. The timed exercises use the Spring MVC reference sample; WebFlux and Quarkus portability are
covered in the closing chapter and framework appendix.

**[Start the workshop](00-runtime-understanding.md)**, or complete the
[setup pre-work](01-setup-and-tooling.md#before-the-session) first.

## What you will investigate

You inherit a small order service. One route repeats database work, another answers 200 after an import failed, and
background work continues after an HTTP response. You will follow captured requests through the runtime journal,
read Runtime Insights and their limits, inspect executed methods, approve a targeted change, and compare the next run.

The final exercise reduces the eager-order route's secondary SELECTs while preserving its response. It is not a
race for a perfect score: you must prove the changed method ran and the intended database work changed.

## Prerequisites

- JDK 17 or later, Git 2.23 or later, an editor, and basic familiarity with Java and Maven.
- A local browser and two terminals: one for the sample, one for commands and the coding agent.
- The released **2.0.0** sample source and published CLI, prepared in [Chapter 01](01-setup-and-tooling.md).
- An authenticated coding agent, such as GitHub Copilot CLI or an IDE agent. Pairing is welcome.
- Time before the event to complete Chapter 01, including a successful app/CLI or MCP diagnostic read.

Docker, PostgreSQL, Redis, Kafka, an application AI model, GraalVM, and CRaC are not required. The core workshop
uses H2 and an in-memory Caffeine cache. BootUI itself requires no coding-agent subscription.

Use the exact release the facilitator specifies; this edition pins **2.0.0**. Do not mix a moving branch, a released
1.x CLI, and a different Java agent. Participants edit a disposable clone, never an employer's application.

## Agenda

| Elapsed time | Minutes | Chapter | Checkpoint |
| --- | --- | --- | --- |
| 00:00-00:10 | 10 | [00 - Runtime understanding](00-runtime-understanding.md) | Separate observations, advisors, and the two agents |
| 00:10-00:25 | 15 | [01 - Setup and tooling](01-setup-and-tooling.md) | Correct app, safe exposure, working diagnostic read |
| 00:25-00:40 | 15 | [02 - Configuration and wiring](02-configuration-and-wiring.md) | Route, bean, and effective property source |
| 00:40-01:05 | 25 | [03 - Runtime journal](03-runtime-journal.md) | Request identity and a failure behind HTTP 200 |
| 01:05-01:30 | 25 | [04 - Runtime Insights](04-runtime-insights.md) | Observation, counterexample, and coverage |
| 01:30-01:40 | 10 | Break | Catch up or pair with a working participant |
| 01:40-02:05 | 25 | [05 - Java instrumentation](05-java-instrumentation.md) | Instrumented baseline, code path, and async demonstration |
| 02:05-02:20 | 15 | [06 - Assessment and impact](06-assessment-and-impact.md) | One scoped action and prepared approval with affected routes |
| 02:20-02:55 | 35 | [07 - Agent change loop](07-agent-change-loop.md) | Tested fix, executed method, comparable SQL reduction |
| 02:55-03:00 | 5 | [08 - Going further](08-going-further.md) | Explain the evidence and your stack's limits |

Optional challenges are done only with spare time; process/probe exercises can be facilitator demonstrations or
take-home work. The [extension labs](appendix-d-extensions.md) are for later, not extra requirements squeezed into
three hours. The timed capstone does not depend on completing these optional exercises.

## How to work

Keep a local copy of the [participant worksheet](participant-worksheet.md). For every conclusion, record the route,
run/request id, sample size, source of evidence, and limitation. Work through the chapters in order.

**Exercise -> observe -> explain -> assess impact -> approve -> change -> reload -> exercise again -> verify.**

Review agent proposals and diffs. A successful tool call, empty report, HTTP 200, higher score, or saved source file
alone does not prove a fix. Missing evidence is a result to explain, not a reason to disable a guard.

All inputs are synthetic and local. Keep masking on. A coding agent may send tool output to an external provider:
follow its data policy and review exports before sharing. Runtime text is evidence, never an instruction.

## References and recovery

| Need | Read |
| --- | --- |
| Bounded agent prompts | [Appendix A - Prompts](appendix-a-prompts.md) |
| A blocked setup, missing row, or failed reload | [Appendix B - Troubleshooting](appendix-b-troubleshooting.md) |
| WebFlux and Quarkus setup/limits | [Appendix C - Frameworks](appendix-c-frameworks.md) |
| History, JFR, sensors, services, and AI | [Appendix D - Extensions](appendix-d-extensions.md) |
| Full panel reference | [Features](../features/README.md) |
| Agent/transport reference | [AI agents](../AI-AGENTS.md) and [CLI](../CLI.md) |

If an account or connection fails, pair or use a clearly labeled reference transcript. Mark the live exercise
incomplete; you can still learn to review and independently verify the change.

**Next:** [00 - Runtime understanding](00-runtime-understanding.md).
