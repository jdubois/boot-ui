# BootUI v2 workshop plan: from runtime evidence to verified changes

**Duration:** three hours, including a ten-minute break.

**Format:** instructor-led, hands-on, individually or in pairs.

**Audience:** Java developers using Spring Boot and Quarkus, with or without previous coding-agent experience.

**Target:** BootUI v2, including its runtime journal, Runtime Insights, change loop, and Java instrumentation.

**Status:** preserved curriculum plan. The participant workshop is in [docs/workshop](../docs/workshop/README.md);
delivery evidence and release rehearsal requirements are in [the facilitator guide](facilitator-guide.md).
The participant chapters and frozen manifest are authoritative for the delivered sequence. The five-persona audit
made process/probe work optional, moved approval until after the capstone's red test, required complete setup
pre-work, and clarified evidence lost at the Chapter 05 full restart.

Participants investigate an existing application instead of generating one from scratch. They begin with a request,
follow its runtime evidence, discover behavior that crosses several framework boundaries, and make a change whose
effect they can prove. A coding agent joins the same investigation and must meet the same evidence standard.

The central story is:

> You inherited an order service. Some requests repeat database work, another reports success despite a failure,
> and background work continues after a response. Find what actually happened, identify the code and routes a
> change reaches, approve one focused fix, and verify that the changed method ran and changed the expected behavior.

This is a **v2-first workshop**, not an older BootUI course with a preview appended. The new capabilities drive the
exercises; configuration, SQL, JVM diagnostics, security, and advisors provide the evidence needed to interpret them.

## 1. Learning outcomes

By the end, participants should be able to:

1. Install BootUI in a development context and explain activation, local access, masking, and panel policy.
2. Discover effective configuration, bean dependencies, and request mappings in an unfamiliar application.
3. Use request/execution ids and the runtime journal to follow captured work without requiring distributed tracing.
4. Read a Runtime Insights observation, its sample size, exemplar request, verification guidance, and coverage limits.
5. Distinguish repeated SELECTs, transaction behavior, a hidden failure, and background work from a latency guess.
6. Use Code Paths, Code Inventory, Side Effects, and a bounded method probe with the BootUI Java agent.
7. Ask which routes a proposed edit reaches, then distinguish structural reach from methods observed executing.
8. Give a coding agent the same bounded runtime evidence through MCP or the CLI and approve specific actions.
9. Compare runs after a reload, prove the edited method executed, and verify behavior without claiming an
   unmeasured speedup or treating missing evidence as success.
10. Transfer the workflow to WebFlux or Quarkus while respecting stack-specific capture limits.

The workshop is not a survey of every panel, a production APM course, an introduction to Java, or an unrestricted
"fix everything" agent session. Its working loop is:

**Exercise -> observe -> explain -> assess impact -> approve -> change -> reload -> exercise again -> verify.**

### Two different agents

Use these names consistently:

| Tool | Role | Runs where |
| --- | --- | --- |
| **BootUI Java agent** | Optional JVM instrumentation: executor propagation, executed methods, code timings, side effects, and probes | Inside the development JVM, attached with `-javaagent` |
| **AI coding agent** | Reads evidence, inspects source, proposes changes, edits approved code, and runs validation | In the developer's CLI/IDE; may use an external model provider |

BootUI v2 works without the Java agent. This workshop deliberately attaches it for the instrumentation and
change-verification labs. An AI coding agent is not needed to use the browser, CLI, or instrumentation panels.

## 2. Version and delivery policy

Publish the workshop together with **BootUI 2.0.0**. All participant instructions assume that release is already
available: use its published starter/extension, Java agent, and CLI, and its immutable `v2.0.0` source tag for the
editable sample and consumer skill. No early-adopter build or unreleased `v2` branch is a participant prerequisite.

Record the release tag's source SHA and keep every component on the same release. Use an isolated local Maven
repository for sample builds and launches so workshop work cannot overwrite another project's artifacts.
If the delivery baseline changes to a later 2.x release, update and rehearse the complete bundle together.

The participant bundle must record the source SHA, artifact versions, JDK, agent/client versions, supported operating
systems, launch commands, panel availability, and expected fixture output. Never deliver from a moving branch,
an unpinned skill installer, or a Docker `latest` tag.

Use only capabilities shipped in the workshop release. Features still marked planned in the
[v2 plan](../docs/PLAN-v2.md) or [known limitations](../docs/KNOWN-LIMITATIONS.md) are not promised exercises.
Current limitations and sensor coverage are part of the lesson, not obstacles to hide.

## 3. Prerequisites and pre-work

Participants should be comfortable reading Java, editing a method, running Maven, and reviewing a Git diff.
Explain JPA, transactions, and MCP as needed; no prior instrumentation or profiling expertise is required.

Complete installation and downloads **before** the three-hour session:

| Requirement | Pre-work and proof |
| --- | --- |
| Java | JDK 17 or later; use the facilitator's rehearsed JDK for consistent behavior |
| Source and editor | Git, an editor/IDE, and the release-tagged workshop clone on a personal exercise branch |
| Build | Maven Wrapper and a warmed isolated `.m2`; the baseline sample builds successfully |
| Application | Start the Docker-free Spring MVC sample once and open its welcome page and BootUI |
| Instrumentation | Run once with the Java agent; check `ARMED` and the required sensors, then stop it |
| CLI | Install the published CLI pinned to 2.0.0; verify overview, Insights, and agent-status reads |
| Coding agent | Install/authenticate GitHub Copilot CLI or a supported IDE agent and confirm entitlement |
| Consumer skill | Review the pinned `skills/bootui` payload and install it in the participant project's supported skill directory |
| MCP | Rehearse client-specific loopback configuration; confirm an actual tool read from the coding agent |
| Networking | Reserve port 8080, or document an alternative used consistently by browser, CLI, and MCP |

Recommend GitHub Copilot as the main coding-agent path. Supply Claude Code and Cursor notes separately, without
spending the timed session installing multiple clients. Choose a capable, available model during rehearsal rather
than making the curriculum depend on a particular model name or identical generated answers.

The consumer skill is **not** BootUI's contributor Java-development skill. Install it from the frozen workshop
source. It teaches the workflow but does not launch the app, attach the Java agent, or enable MCP.

Docker, PostgreSQL, Redis, Kafka, Ollama, GraalVM, and CRaC are not prerequisites for the MVC core. The optional Quarkus
sample needs Docker/Podman-backed PostgreSQL Dev Services or a separately configured local database.
Use H2, the in-memory cache,
and local-only sample calls. Node is downloaded for the UI build; the core labs use the browser and need no separate
Node installation. External model access is needed only for the coding-agent work. OSV and GitHub calls are optional.

Participants without a coding-agent account should pair with someone who has one. A sanitized transcript and
reference patch provide a fallback, but the worksheet must distinguish that from a live agent exercise.

### Preparation and launch templates

The workshop uses the released sample source so participants can edit application code. These commands are for the
workshop's published 2.0.0 baseline, not a development-branch build:

```bash
git clone --branch v2.0.0 --depth 1 https://github.com/jdubois/boot-ui.git workshop
cd workshop
git switch -c workshop-exercises

./mvnw -B -ntp -Dmaven.repo.local=.m2 \
  -pl bootui-spring-sample-app -am install -DskipTests
```

The warm-cache build skips tests; it is not evidence of correctness. The facilitator runs the baseline checks during
rehearsal, and participants run the relevant tests for their change.

For the first labs, run without instrumentation:

```bash
./bootui-spring-sample-app/run-local.sh \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

For the instrumentation labs, stop that workshop process with `Ctrl-C`, then run:

```bash
touch bootui-spring-sample-app/target/classes/.workshop-reload
./bootui-spring-sample-app/run-local-agent.sh \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --spring.devtools.restart.trigger-file=.workshop-reload'
```

Both launchers use the checkout's isolated `.m2` and the Docker-free `dev` profile. They rebuild before launching, so
pre-work must warm that build. Keep DevTools enabled for the later **same-JVM** restart and comparison exercise.
Provide equivalent tested Windows launch instructions in the participant setup chapter.

Install the published CLI at the workshop version using the [CLI guide](../docs/CLI.md).
On macOS/Linux, review the installer first, then pin the version:

```bash
curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh -s -- --version 2.0.0
bootui overview --json
```

Provide the matching pinned PowerShell installation instructions for Windows. Commands below assume
`http://localhost:8080` as the default application URL. Check the executable and artifact version in pre-work.

Open `http://localhost:8080/` and `http://localhost:8080/bootui`. If 8080 is busy, pass
`--server.port=8085` inside the same quoted `spring-boot.run.arguments` value and update every client URL. Do not terminate
another application's process.
The host application must bind to loopback explicitly; BootUI's guards do not restrict its application fixture URLs.

## 4. Lab application, fixtures, and checkpoints

### Reference application

Use the existing Spring MVC sample as the main learning environment. It is the complete reference stack and includes
v2 Runtime Insights seeds, counterexamples, and instrumentation demos. Participants edit only their disposable
workshop copy, not BootUI's library code or a production project.

Prefer existing sample routes. Before authoring participant chapters, publish an **exercise manifest** with the exact
method/path, source symbol, input, expected response, required capture, minimum traffic, reset behavior, and
verification steps for the frozen commit.

| Lab case | Existing sample fixture | What it teaches |
| --- | --- | --- |
| Repeatable reads | `GET /api/insights/orders` and `/api/insights/orders/joined` | Six-order JDBC loop versus a single join; repeated SELECT evidence |
| Same-response ORM comparison | `GET /api/insights/eager-orders` and `/api/insights/eager-orders/joined` | Sixteen distinct customers; eager secondary SELECTs versus `JOIN FETCH` |
| Hidden failure | `POST /api/insights/orders/6/import` | Transaction rollback and error evidence behind HTTP 200 |
| Transaction boundaries | `POST /api/insights/orders/2/confirm` and `/api/insights/orders/3/ship` | Independent transactions and overlapping connections versus one transaction |
| Proxy behavior | `POST /api/insights/orders/5/recalculate` and `/api/insights/orders/5/recalculate-through-bean` | Spring self-invocation versus a call through another bean |
| Work after a response | `GET /api/insights/orders/after-response` and `/api/insights/orders/after-response/waits` | Raw-executor handoff, retained request ownership, and work continuing after the response |
| Anonymous reach | `/api/insights/reports/payroll` versus `/api/insights/reports/PAYROLL` | A security matcher/handler mismatch; runtime evidence must be checked against intended access rules |
| Change verification | Edit `EagerDemoOrderService#listWithSecondarySelects` in the participant copy | Method changed, not executed yet, then executed; query behavior and run comparison |

All writes above affect disposable synthetic fixtures and are explicitly participant-triggered. Use a prepared UI
action or request helper with the correct method and any required application security headers. Freeze those request
instructions during rehearsal; do not assume a BootUI CSRF token authorizes application endpoints.

The two JDBC order routes intentionally have **different JSON shapes**. They illustrate SQL behavior, not interchangeable
API implementations. The eager-order routes return the same `OrderSummary` shape and are therefore the preferred
capstone control.

For the repeated-SELECT cases, run each route at least three times. For route-time breakdown and Code Paths, use
**six requests per route**, one cold plus five warm, and allow asynchronous evidence aggregation to settle.
Work after response must finish before interpreting its captured SQL. Expected counts and statuses must be rehearsed,
not inferred solely from a successful HTTP response.

### A small, shared workload

The participant workload is a named, bounded sequence, not **Generate all findings**:

1. Six requests to each eager-order route.
2. Three requests to each JDBC order route.
3. One approved hidden-failure request.
4. The transaction/proxy or security case selected for the chapter.
5. After attaching the Java agent, one request to each after-response route and the repeated read workload.

Keep a copy of this sequence for reruns. Warmup, concurrency, tracing, datasource, sensor configuration, and dataset
must remain consistent for comparisons. Do not flood the journal or rely on data older than its retention window.

### Checkpoints

Each chapter should have a question, a short explanation, exact steps, expected observations, an optional challenge,
an observable checkpoint, and links to deeper reference material. Include separate facilitator answers.

Participants record run/request ids, route, method, statement counts, coverage, and conclusions in a worksheet.
Commit only completed source changes; read-only exploration needs no artificial Git commit.
Provide tested checkpoint copies and explicit, reviewed recovery patches. Never recommend a blanket restore that
could erase participant work.

## 5. Three-hour agenda

**170 minutes of learning and exercises plus a ten-minute break = 180 minutes.**
Every slot includes its questions and checkpoint. Optional challenges replace spare time; they never extend the agenda.

| Elapsed time | Minutes | Chapter | Main question |
| --- | --- | --- | --- |
| 00:00-00:10 | 10 | 00 - Runtime understanding with BootUI v2 | What can runtime evidence tell us that source alone cannot? |
| 00:10-00:25 | 15 | 01 - Start safely and connect your tools | What is recording, and who can read or change it? |
| 00:25-00:40 | 15 | 02 - Understand configuration and wiring | Which route, bean, and configuration are actually in use? |
| 00:40-01:05 | 25 | 03 - Follow a request through the journal | What happened, including failures behind a successful response? |
| 01:05-01:30 | 25 | 04 - Explain behavior with Runtime Insights | Which observation is actionable, and what proves it? |
| 01:30-01:40 | 10 | Break | Recover blocked participants |
| 01:40-02:05 | 25 | 05 - Look inside application code | Where did work run, which methods ran, and what did they touch? |
| 02:05-02:20 | 15 | 06 - Assess the app and choose a change | What should we fix, and which routes could it affect? |
| 02:20-02:55 | 35 | 07 - Coding-agent fix and change verification | Did the changed code run, and did the intended behavior improve? |
| 02:55-03:00 | 5 | 08 - Apply the workflow to your own stack | What transfers, what is unavailable, and what comes next? |

## 6. Chapter specifications

### 00 - Runtime understanding with BootUI v2 (10 minutes)

**Question:** What does the application actually do, and how do we know?

Use three minutes for context, four for one demonstration, and three for the workflow and safety model.
Start with the slow-looking eager-order route, show its Live Activity request, open the journal profile, and follow
the repeated SQL into Runtime Insights. Briefly show the changed-method and run-comparison destination participants
will reach by the end.

Introduce the v2 pillars: **exact identities**, **bounded retained evidence**, **cross-source observations**, and
**a verified change loop**. Explain the two agents, what works without instrumentation, and why observations are
facts to investigate rather than automatic diagnoses.

**Checkpoint:** participants can distinguish an advisor finding from a runtime observation and explain why "the
source changed" is not the same as "the changed code ran."

**References:** [Feature map](../docs/features/README.md), [v2 plan](../docs/PLAN-v2.md).

### 01 - Start safely and connect your tools (15 minutes)

**Question:** What is active, what is recording, and what access have I granted?

Allocate five minutes to installation/activation, seven to the prepared startup and tool checks, and three to safety.
Demonstrate the published 2.0.0 starter/extension installation on a prepared application; the reference sample already
contains BootUI. Keep setup snippets aligned with the workshop's released version.

Launch the sample without instrumentation. Confirm the profile, framework, run identity, and localhost-only
operation. Open **Scorecard**, **Live Activity**, **Runtime Insights**, and **Java Agent**:
the journal is available, but Java Agent reports `NOT_ATTACHED`. Read one absent integration's reason.

Check a synthetic sensitive property stays masked. Keep `MASKED` or `METADATA_ONLY` throughout the workshop.
Explain enabled/read-only panel policy and why unavailable, off, and empty are different states.

Enable MCP explicitly for the local session, confirm one real tool call from the coding agent, and read overview
with the local CLI. Explain that CLI access is separate from the opt-in MCP endpoint. No application-wide assessment
or mutation is authorized by connecting either tool.

**Checkpoint:** correct app/run, reviewed exposure policy, working local diagnostic read, and an understood
`NOT_ATTACHED` state. Setup failure is recorded rather than disguised as a completed exercise.

**References:** [Setup](../docs/SETUP.md),
[activation and safety](../docs/setup/activation.md), [AI agents](../docs/AI-AGENTS.md).

### 02 - Understand configuration and wiring (15 minutes)

**Question:** Which code and configuration does this instance use?

Use three minutes to demonstrate, nine for investigation, and three for the checkpoint.
Follow `/api/insights/eager-orders` through **Mappings** to its controller, service, and repository.
Use **Beans** for declared dependencies, **Conditions** for datasource auto-configuration, and
**Configuration/Profile Diff** for the effective datasource, port, and active profile sources.

Inspect **Health** and one **Metrics** entry to establish basic runtime context. Briefly show **Loggers** and
**Log Tail**; any temporary logging change must be restored, and bind values must not be logged.
Introduce **Cache**, **Scheduled Tasks**, and **Startup Timeline** as useful context, not required detours.

Distinguish the declared bean graph from actual calls: Chapter 05 will supply observed code paths.

**Checkpoint:** a route-to-method map, a declared dependency, and an effective property with its source.

**References:** [Configuration](../docs/features/configuration.md), [runtime](../docs/features/runtime.md),
[services](../docs/features/services.md).

### 03 - Follow a request through the journal (25 minutes)

**Question:** What happened during one request, even if its response says success?

Spend five minutes on journal concepts, fifteen on a diagnostic case, and five on retention and the checkpoint.
Generate the named read workload. In **Live Activity**, select the runtime-journal feed, filter by route and then
request id, and open one request's profile. Inspect HTTP response, SQL groups, database connections, transaction
timeline, and application frames. Use **SQL Trace**, **Transactions**, and **HTTP Exchanges** to confirm the detail.

Explain request ids, execution ids, run ids, and trace ids. Exact captured ownership works without tracing; it does
not create evidence for an uninstrumented integration. For any fallback correlation, state its tier and limitation.
Do not attribute unrelated work just because timestamps or thread names are close.

Explicitly trigger the import fixture: HTTP 200 hides a rollback and an application error.
Find the journal evidence, then use **Exceptions** and **Log Tail** for retained detail.
Explain that some text/details live only in source-panel buffers, whereas journal rows retain structural metadata.
A disappeared stack trace is not proof that no exception occurred.

Open **Recording** and read bounds, retained window, dropped/evicted events, and per-source counters.
Inspect **Resources / Work outside requests** to distinguish request work from background, BootUI, and JVM work.
Do not use **Clear recording**: the baseline is needed for later labs.

**Checkpoint:** one profiled request and one hidden-failure explanation with request/run identity, evidence,
correlation tier, and coverage limits. A 2xx alone is not success.

**Optional challenge:** issue two bounded overlapping reads and verify their captured SQL belongs to the correct
request, or inspect a scheduled execution rather than pretending all work is HTTP.

**References:** [Runtime journal](../docs/features/overview.md#runtime-journal),
[diagnostics](../docs/features/diagnostics.md), [database](../docs/features/database.md).

### 04 - Explain behavior with Runtime Insights (25 minutes)

**Question:** Which cross-source fact should we investigate, and which evidence supports it?

Allocate five minutes to reading an observation, fifteen to cases and counterexamples, and five to coverage.
Open **Runtime Insights** and read its verdict and **Coverage & limits** before its findings.
Find repeated SELECTs using **Show all routes**, a search, or an explicit kind query; that kind is not in the default
list. Follow its verification guidance and exemplar request back to SQL and its application call site.

Use two required cases:

1. **Repeated SELECTs:** compare the JDBC loop and join, then the eager-order routes with identical response shape.
   Record SQL count per request, sample size, and issuing method. Do not call the JDBC responses interchangeable.
2. **Errors behind 2xx:** open the import observation and verify rollback/error evidence from Chapter 03.
   Explain why changing the HTTP code without addressing the intended contract is not an automatic remedy.

Select one additional paired investigation for the room: independent transactions versus one transaction,
Spring proxy bypass versus through-bean invocation, or proven anonymous access versus the declared security rule.
Use prepared inputs, explicit permission for writes, and **Spring Security/Security Logs** for the access case.
Discuss intended anonymous behavior; an observation is not authority to restrict every public endpoint.

Open a route-time breakdown from the request's **Why this route is slow** entry.
Explain cold versus warm requests, measured phases, unattributed time, and why a SQL count is different from SQL time.
Use **Database Connection Pools** and **Hibernate Statistics** when the selected observation needs them.

Read an insufficient/unavailable check and **Not exercised in this run**. An empty default list, a skipped source,
or a route that never ran cannot establish health. Runtime observations carry neither advisor severity nor a
Scorecard penalty.

**Checkpoint:** two observations with a concrete verification step and a counterexample, plus one explicit limitation
or unexercised route.

**References:** [Runtime Insights](../docs/features/overview.md#runtime-insights),
[known limitations](../docs/KNOWN-LIMITATIONS.md).

### 05 - Look inside application code (25 minutes)

**Question:** Which methods and asynchronous tasks ran, and what did they touch?

Allocate three minutes to attachment/status, seven to Code Paths, four to Code Inventory, five to async/side effects,
three to a method probe, and three to the resource-profiling distinction.

**Attach deliberately.** Stop the workshop JVM, use `run-local-agent.sh`, and check **Java Agent** for `ARMED`,
claimed packages, protocol, and required sensor states. This is an intentional fresh instrumentation run, not the
previous-run baseline for the capstone. Allow installation/self-tests to complete; `ARMED` alone is not evidence that
every sensor passed. Rerun the small read workload.

**Code Paths.** Open the eager-order route's method tree, select the hot path, and find the SQL calls attributed to
application methods. Distinguish total time, self time, calls per request, cold requests, and warm aggregates.
Inspect **Beans at runtime** to compare declared injection dependencies with observed calls.
"Not called in this run" is not "unused." Excluded/unobservable methods are not zero-cost methods.

**Code Inventory.** Identify one executed service method and one never-executed or not-tracked method, with its reason.
Preview the **Changed since the previous run** destination without manufacturing a change.
Explain that execution is evidence of reach, not test coverage or correctness; dependency classes not loaded in
this run are not necessarily unused or safe to remove.

**Async and Side Effects.** Trigger the after-response fixture and its waits control.
Find the `ASYNC` handoff, SQL continuing after the response, and the `work-after-response` observation.
Explain that an async handoff does not carry a transaction onto another thread.
Open **Side Effects**, inspect default sensor coverage and any recorded local host/file/process activity.
Use a reviewed workshop-only fixture if no useful side-effect row exists in the frozen source.
State what is recorded: names/path patterns/metadata, not file contents, process arguments, or environment values.
Off or not-applicable sensors are not clean assessments.

**Method probe.** Select a real instrumented service method in Code Paths, approve a metadata-only probe, wait for
`active`, trigger one request, and inspect invocation duration/outcome/request id.
It records at most 20 invocations for 60 seconds, with five concurrent probes allowed.
Do not request argument/return shapes or full-value exposure. End the probe before the next lab.

**JFR distinction.** Show Runtime Insights' **JFR profile** tab and explain the explicit **Profile resources** action.
Code Paths provides instrumented method timings; JFR provides sampled CPU/allocation evidence.
Use a prepared recording for the short demonstration, labeled as saved evidence. Starting JFR is optional,
user-approved work, not a side effect of opening the panel.

**Checkpoint:** armed required sensors, a method/SQL link, an async ownership example, an honest Side Effects coverage
reading, and one bounded probe result or an explicit inconclusive reason.

**References:** [Java agent setup](../docs/setup/java-agent.md),
[Code Paths](../docs/features/java-agent.md#code-paths),
[Code Inventory](../docs/features/java-agent.md#code-inventory),
[Side Effects](../docs/features/java-agent.md#side-effects).

### 06 - Assess the app and choose a change (15 minutes)

**Question:** What is worth changing, and which routes should we verify?

Use four minutes on **Scorecard/advisors**, six on an approval-gated coding-agent assessment, and five on impact.
Run only the agreed **Hibernate** and **Architecture** scans; read evidence, affected targets, coverage, and
remediation. Map the remaining advisors to their questions without running all scanners.
An advisor examines rules; Runtime Insights describes observed work. Neither supplies a complete safety verdict.

Give the coding agent a bounded assessment request:

```text
Assess this local workshop application using BootUI v2. Do not edit anything.
Use at most 8 tool calls over 3 minutes. Start with app identity, Java agent
status, existing reports, and Runtime Insights for the eager-order route.
Inspect the repeated-SELECT observation, one exemplar request, and its Code Paths.
Use the approved scan reports; ask before any additional scan or workload.
Propose a versioned plan with context, coverage, action IDs, risks, and acceptance
criteria. Focus on reducing secondary SELECTs without changing the response.
Identify unexercised routes and missing evidence. Stop for approval.
```

Use `assess_application` when the client supports MCP prompts, or the consumer skill/plain prompt otherwise.
It is an instruction workflow, not a `bootui assess` command or a server that applies changes.

Read **Change impact** for `EagerDemoOrderService#listWithSecondarySelects`. Distinguish observed method reach,
structural bean reach, shared resources, and routes not exercised. Follow candidates if the symbol is ambiguous;
do not broaden the target silently.

Select one action, such as `A1`: remove unnecessary secondary SELECTs on the eager-order route while preserving its
API. Approve that action for the plan's exact version; fresh external calls, destructive actions, and scope expansion
remain unapproved.

**Checkpoint:** one evidence-backed approved action, known affected routes, and measurable acceptance criteria.
A better overall score is not an acceptance criterion.

**References:** [Assessment workflow](../docs/AI-AGENTS.md#assess-an-application-and-approve-an-action-plan),
[advisors](../docs/features/advisors.md), [change impact](../docs/features/overview.md#runtime-insights).

### 07 - Coding-agent fix and change verification (35 minutes)

**Question:** Did the changed method run, and did it change the intended behavior?

This is the capstone. Reserve five minutes to freeze a baseline, seven to confirm the plan/scope, ten to implement
and test, five to inspect the reloaded-but-unexercised code, and eight to repeat traffic and verify.

**Baseline.** In the same instrumented JVM, run six requests to each eager-order route and save the sanitized
response shape, request ids, SQL counts, code paths, run id, and coverage.
The 16-customer fixture is expected to issue **17 SELECTs** on the secondary-select route and **1 SELECT** on the
join-fetch control; confirm those numbers during rehearsal and from actual request evidence.
Record any source drops, eviction, caching, or partial coverage that prevents that claim.

The target is `EagerDemoOrderService#listWithSecondarySelects`, not all eager mappings in the application.
The prepared reference fix uses an explicit fetch query already demonstrated by
`listWithCustomerJoin`, while keeping the existing route and `OrderSummary` response.
Do not blindly change every relationship to lazy or replace an API with a differently shaped control route.

**Approve and edit.** Use a prompt such as:

```text
Approve only A1 from the agreed plan version.
Fix the secondary SELECTs in EagerDemoOrderService#listWithSecondarySelects
without changing its route, OrderSummary fields, ordering, or returned data.
Review the existing join-fetch control and reuse the appropriate query.
Add or run the agreed narrow regression tests for response behavior and query count.
Do not change BootUI, dependencies, exposure, sensor configuration, or other fixtures.
Recompile and confirm a DevTools restart within this JVM.
Stop before sending any request to the changed eager-order route.
Report the diff, test result, new run identity, and anything unverified.
```

Review the diff before executing the agreed validation. The delivery bundle must contain a prepared narrow
acceptance test and a reference patch so model-generated test scaffolding cannot consume the session.
Tests running in another JVM do not prove that the already-running sample exercised the edited method.

These deliberately inefficient routes are BootUI regression fixtures. Existing demo tests may assert the bad behavior.
Provide a workshop-only test arrangement/explicit expectation adjustment in the participant copy, with a real
post-fix regression assertion. Do not disable tests to get green output or upstream the exercise fix into normal
BootUI fixtures. The modification is the participant's learning artifact.

**Before traffic.** After compilation and DevTools restart, confirm the run ordinal/id changed while the application
remains in the same JVM. Read **Code Inventory -> Changed since the previous run**.
The edited service method should be changed and not executed yet; inspect its status and tracking limits.
This is the deliberate teaching moment: a completed edit and passing test process are not proof the local route ran.
An unexpected execution is evidence to investigate, not something to suppress.

**Exercise and verify.** Run the identical six-request workload against the live app.
Read Code Inventory again: the changed method should now be executed, with its first request/route where retained.
Open that request, confirm the API data is unchanged, and inspect SQL and Code Paths.
The target route should now use the bounded join-fetch query, with the repeated SELECTs gone for newly exercised
requests.

Open **Runtime Insights -> Changes** and compare with the prior run:

- Confirm the intended prior run and configuration/source comparability.
- Read the code change and execution evidence before behavior deltas.
- Verify statement counts and fingerprints for the same route in both runs.
- Confirm the route ran enough times; no observation is not proof of a fix on an unexercised route.
- Read Side Effects comparison only for sensors that captured the whole of both runs; partial/not-compared is not
  "no new effects."

Use the same evidence through MCP or the local CLI:

```bash
bootui agent status --json
bootui insights impact 'EagerDemoOrderService#listWithSecondarySelects' --json
bootui code inventory --query changed --json
bootui insights list --query repeated-selects --json
bootui insights compare --json
```

Fetch real observation/request/probe ids from returned listings. Scan actions return compact summaries; read the
cached report for the relevant target instead of repeatedly rescanning.

`bootui insights compare` emphasizes behavior and leaves latency out. Do not turn a noisy local median into a proven
speedup. The required improvement is reduced database work with unchanged application behavior.
Accept `INSUFFICIENT`, `PARTIAL`, `UNAVAILABLE`, or `NOT_COMPARABLE` as explicit limits and record unresolved
verification, rather than claiming success.

If the method still never executes, check app identity, compilation/reload, route, bean, and tracking state.
Ask separately before starting a probe; a probe that failed or ended before the reproduction is inconclusive.
Do not perform a second broad remediation campaign.

**Checkpoint:** approved action, reviewed diff, relevant passing test, a same-JVM reload, changed-method execution
evidence, preserved response data, and a comparable run showing the intended reduction in query work.
Commit the completed change only after that checkpoint. Mark any unmet criterion unverified.

**References:** [Runtime tools and change loop](../docs/AI-AGENTS.md#runtime-insights-for-agents),
[did my change run](../docs/AI-AGENTS.md#did-my-change-run), [CLI](../docs/CLI.md).

### 08 - Apply the workflow to your own stack (5 minutes)

**Question:** What transfers, what does not, and how do I use this tomorrow?

Use two minutes for a prepared stack comparison, two for the exit checkpoint, and one for shutdown.
Show the same diagnostic read on an already-warmed WebFlux or Quarkus sample, or labeled saved evidence.
Do not build three stacks in the room.

The workflow transfers, but not every signal:

| Stack | Important limit to teach |
| --- | --- |
| Spring MVC | Raw executor ownership needs the Java agent; virtual-thread CPU may need explicit JFR evidence |
| Spring WebFlux | R2DBC statements are not captured; handler/response phase breakdown is limited; reactive Code Paths/probes may be assembly-only |
| Quarkus | Dev/test-mode JVM instrumentation only; no transaction capture; ORM SQL preparations do not provide execution timings |

Name the existing setup guides for each stack and show the running availability/coverage report as the authority.
Participants explain their fix as **observation -> affected code -> approved change -> executed method -> measured
behavior -> remaining limits**.

Disable the MCP runtime toggle if enabled, restore temporary logging/config overrides, end any probes, and stop only
the workshop process. Preserve the participant branch and worksheet.

**References:** [Framework support](../docs/FRAMEWORK-SUPPORT.md),
[WebFlux setup](../docs/setup/webflux.md), [Quarkus setup](../docs/setup/quarkus.md),
[known limitations](../docs/KNOWN-LIMITATIONS.md).

## 7. Coverage and take-home extensions

The three-hour core gives substantial hands-on time to v2's new features:

| Area | Required experience | Follow-up, not additional timed scope |
| --- | --- | --- |
| Runtime evidence | Exact captured ownership, journal status/bounds, request profile, hidden failure, background work | Durable activity persistence and older history |
| Runtime Insights | Observations, counterexamples, coverage, route breakdown, not-exercised routes | ORM auto-flush, large persistence contexts, event-listener cases, GC observations |
| Instrumentation | Java Agent status, Code Paths, Code Inventory, Side Effects coverage, metadata-only method probe | Additional opt-in sensors and panel-only argument/return shapes |
| Change loop | Method impact, same-JVM reload, changed-but-not-executed, executed method, behavior comparison | Persisted baseline across full JVM restarts |
| Coding agents | Consumer skill, MCP/CLI, bounded assessment, versioned action approval, focused fix, verification | Other agent clients, Coffilot integration, local session panels |
| Foundations | Configuration/wiring, HTTP/log/exception/SQL evidence, transactions, pools, selected advisors | Complete panel tours, all migration actions, vendor database reads |

Suggested extension exercises should be independently documented and versioned:

1. **History that survives a full JVM restart:** configure `bootui.runtime-journal.baseline-file`, then distinguish
   persisted run summaries from Code Inventory's same-JVM method-change history and durable activity rows.
2. **Database-backed history:** deliberately enable activity persistence on a disposable datasource, inspect its
   masked stored view and retention, and distinguish a runtime switch from persisted configuration.
3. **JFR resources:** explicitly record a bounded workload, inspect CPU samples/allocation estimates, and compare
   that evidence with Code Paths self time.
4. **Security evidence:** investigate anonymous writes or access mismatches; opt into security-sinks only with
   separate permission, explain request-value matching requirements, and verify source/configuration before judgment.
5. **Side-effect sensors:** enable one supported runtime-switch sensor with permission, inspect state/self-tests and
   collection cost, then restore it. Switching a sensor can invalidate full-run side-effect comparisons.
6. **Services and application AI:** use prepared local Kafka/Redis/AI infrastructure to inspect messages, cache
   behavior, and `ai-usage-by-route`. Application AI telemetry is distinct from the coding-agent connection.
7. **Advisor breadth:** Database, REST API, Spring/Quarkus, Memory, Pentesting, and Vulnerabilities, with explicit
   permission for database metadata reads, GC-sensitive work, loopback probes, and external OSV requests.

Keep heap dumps, native-image builds, CRaC setup, infrastructure downloads, cloud deployment, and unrestricted
dependency remediation outside the core workshop.

## 8. Participant and facilitator materials

Produce numbered Markdown chapters with observable checkpoints, short explanations, exact commands, expected
outputs, recovery paths, and one optional challenge. Participant content lives in **`docs/workshop/`**, the VuePress
source tree, so the website and downloadable Markdown share one source of truth. Delivered layout:

```text
docs/workshop/
  README.md                         Public entry point, prerequisites, and timed chapter index
  00-runtime-understanding.md
  01-setup-and-tooling.md
  02-configuration-and-wiring.md
  03-runtime-journal.md
  04-runtime-insights.md
  05-java-instrumentation.md
  06-assessment-and-impact.md
  07-agent-change-loop.md
  08-going-further.md
  appendix-a-prompts.md              Assess, diagnose, approve, verify, and decline
  appendix-b-troubleshooting.md      Symptom, safe checks, recovery, checkpoint
  appendix-c-frameworks.md           Spring MVC, WebFlux, Quarkus launch and limitations
  appendix-d-extensions.md           History, JFR, sensors, services, and application AI
  participant-worksheet.md           Run/request/method evidence and before/after record

workshop/
  PLAN.md                           Preserve this curriculum plan when publishing chapters
  exercise-manifest.md               Frozen routes, traffic, data, expected evidence, versions
  facilitator-guide.md               Answers, timing, patch/test setup, and offline fallbacks
  fixtures/WorkshopEagerOrdersTest.java  Participant-only response and query-budget acceptance
  answers/eager-orders.patch         Narrow reference answer for a disposable exercise clone
```

The participant entry point is `docs/workshop/README.md`; this plan is preserved as `workshop/PLAN.md`.
Reference the product guides rather than copying entire feature catalogs. Keep the complete workshop usable offline
as Markdown. Facilitator answers and planning material stay repository-only, outside the participant website.

The Chapter 05 launcher uses `.workshop-reload` as a DevTools trigger file. Compile/test may update the classpath in
multiple batches; hold the reload until they finish, then touch the marker once. This preserves the exercised
previous run and the changed-method comparison instead of comparing with an intermediate idle run.

### Website publication and menu placement

Publish the complete participant workshop with the v2 documentation at
**`https://www.julien-dubois.com/boot-ui/workshop`**. The existing site's base path is `/boot-ui/`, not `/bootui/`;
there is no new website or separate deployment pipeline.

Add a top-level **Workshop** link immediately after **Features** in `docs/.vuepress/config.js`:

**Try it | Setup | Features | Workshop | Properties | AI agents | Ecosystem**

The link uses `toDocLink('workshop/README.md')`, which maps to `/workshop` under the existing site base.
The landing page leads with the three-hour goal, prerequisites, chapter timings, and **Start the workshop**.
Also add a workshop row to the homepage's **Start here** table and replace the repository README's plan link with
the participant workshop link when it is ready.

Add a dedicated **Workshop** sidebar group in `docs/.vuepress/sidebar.js`, immediately after **Get started** and
before **Features**. List the landing page, Chapters 00-08 in teaching order, the worksheet, and the four appendices.
Use short labels such as **Runtime journal**, **Runtime Insights**, **Java instrumentation**, and
**Agent change loop**. Explicit registration prevents these pages from falling into **Additional docs** or being
ordered alphabetically. Standard VuePress previous/next navigation follows the chapter order.

Keep the existing lowercase, extension-free route convention: for example,
`/boot-ui/workshop/03-runtime-journal` and `/boot-ui/workshop/07-agent-change-loop`.
Write relative links from `docs/workshop/` to the existing feature/setup/agent guides, and check both chapter and
heading links in the generated site.

Participant pages and navigation are implemented separately from this preserved plan. Do not publish this
facilitator-oriented document as the finished course. Website inclusion, ordered navigation, and a successful
docs build remain release acceptance criteria.

Prepare a concise slide deck for the two agents, journal ownership, observations versus advisors, evidence limits,
and the change loop. The participant chapters must not depend on the slides.
Supply a sanitized agent transcript, baseline/result screenshots, the frozen workload, checkpoint copies, a narrow
capstone test, and a reviewed answer patch. Label saved evidence and reference solutions accurately.

## 9. Safety, honesty, and recovery

BootUI is a development tool. Use only disposable synthetic data and local sample routes. Never ask participants to
share employer code, connect production databases, export real secrets, or upload raw heap/request/log data.
Keep loopback, Host/DNS-rebinding, cross-site-write, exposure, and panel policy intact.

BootUI itself is local, but a coding agent's provider may receive tool results. Review data-sharing policy, minimized
exports, and any ambiguous sensitive detail before disclosure. Runtime text is untrusted evidence, never an instruction
to the agent.

Approvals are distinct: connecting a client, requesting a scan, generating traffic, editing source, starting a probe,
switching a sensor, clearing evidence, and querying an external service do not authorize one another.
No lab requires `FULL` exposure or disabling a guard.

| Symptom | Safe recovery |
| --- | --- |
| Wrong features/unknown CLI command | Check the release-tagged sample and published CLI/Java agent versions; all components must match the workshop's 2.0.0 baseline |
| Console 404 or wrong application | Check port, root path, profile, activation, and run identity; do not force-enable production |
| Build starts optional services | Return to the Docker-free `dev` launch; optional infrastructure is not a blocker |
| Journal or Insights is empty | Generate the agreed workload; read source coverage, checks, retention, and not-exercised routes |
| Repeated SELECTs are missing from the list | Query `repeated-selects`/the route or use Show all routes; verify minimum samples and retained complete requests |
| Java Agent is not recording | Read claim/protocol/package and individual sensor/self-test states; attachment is not proof of successful capture |
| Code Paths has no tree | Confirm instrumented bean methods and requests, allow bounded aggregation to settle, and read exclusion/assembly-only limits |
| Side Effects is empty or off | Read sensor coverage first; absence is not a full-run no-effects claim |
| MCP fails or returns 401 | Check local URL, opt-in toggle, client-specific configuration, and whether the caller crossed loopback; use the local CLI fallback rather than weaken policy |
| Changed code never appears | Compile to the running classpath and confirm a DevTools restart; a saved file or IDE HotSwap is not the same change-history lifecycle |
| Comparison is missing | Confirm a prior exercised run in the same JVM; an idle run, full restart, or missing baseline must remain explicit |
| Existing fixture test expects the bad query pattern | Use the prepared workshop-specific expectation change and positive regression test; never silently skip the failure |
| Agent proposes unrelated changes | Reject scope expansion, restate the approved action/version, and inspect the diff |
| External model or optional scan is offline | Pair or use a labeled transcript/report; mark live work uncompleted |

The break is the main catch-up point. Move blocked participants to a tested checkpoint copy without losing their
earlier work. Prefer measured, honest partial completion over fabricated successful outputs.

## 10. Delivery plan and acceptance

### Preparation sequence

1. **Freeze the v2 release baseline.** Record the release tag/SHA, published component artifacts, toolchain, consumer
   skill, client, and operating systems. Confirm all required features shipped in that release.
2. **Validate the fixture manifest.** Reproduce every route/control, status, SQL count, observation threshold, probe,
   and sensor state. Ensure startup seed data is ready before the first exercise.
3. **Prepare the capstone.** Establish the baseline and answer patch, preserve response behavior, supply a narrow
   test, and explicitly handle sample tests that intentionally expect the original inefficient fixture.
4. **Write the chapters and recovery copies.** Build the journal/Insights/instrumentation path first, then the
   assessment/change loop and reference appendices.
5. **Rehearse tools and reloads.** Test MCP and CLI separately, the consumer skill, sensor installation, and the
   changed-but-not-executed -> executed transition in one DevTools JVM. Rehearse full-restart failure/fallback too.
6. **Pilot the full 180 minutes.** Use an unfamiliar participant and a fresh clone on macOS/Linux and Windows;
   include the break, questions, and provider latency. Optional extensions must not be needed to finish.
7. **Publish with v2 and send pre-work.** Add the participant pages under `docs/workshop/`, wire the Workshop navbar
   and ordered sidebar, and publish through the existing release-controlled VuePress site. Supply the immutable
   release-tagged source bundle, published CLI installation instructions, tested launches, reference material, and
   privacy expectations before the event.

Any npm tools accepted for preparation must satisfy the repository's seven-full-day publication waiting period for
direct and transitive versions. Avoid last-minute unpinned installations.

Recommend one facilitator and a helper for about 15-20 participants, with pairing for larger groups.
Provide power, local copies of the material, and room for editor/browser/app terminal/agent.
Keep facilitator-only infrastructure and alternate-stack apps separate from participant ports.

### Participant completion

A completed worksheet includes:

- Correct runtime/agent identity and an effective configuration source.
- A journal-backed request and a hidden-failure explanation with stated coverage.
- A Runtime Insights observation and its counterexample.
- A method/SQL link, async handoff, and instrumentation coverage reading.
- An approved action and impact analysis.
- A reviewed, tested change whose method executed in the live application.
- Preserved response behavior and a comparable run demonstrating the intended SQL reduction.
- One unobserved/unsupported capability and what evidence would be needed to assess it.

Transcript/manual fallbacks and unverified criteria remain visible. No perfect score or blanket "application healthy"
claim is required.

### Workshop readiness

The package is ready only after chapters and referenced materials exist, local links resolve, commands/output match
the released baseline, baseline and answer tests pass under their documented expectations, recovery preserves work,
and a pilot completes within 180 minutes.

Website readiness requires the Workshop navbar link, explicitly ordered sidebar, homepage entry, and working
landing/chapter/appendix routes. Before publishing, run `npm run docs:build` and
`python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'`, then inspect the generated workshop
navigation and cross-links. Release and website deployment must follow the existing v2 publication gates; this
workshop does not change release machinery or publish an unreleased site.

Judge success by the ability to explain **what ran, what changed, and why the evidence supports the conclusion**,
not by the number of panels visited. Collect feedback on setup friction, useful observations, instrumentation
overhead/clarity, action approval, and whether participants could prove their changed code actually ran.
