# BootUI v2 workshop facilitator guide

Teach from [the participant course](../docs/workshop/README.md), keep [the frozen manifest](exercise-manifest.md)
beside you, and preserve [the curriculum plan](PLAN.md) as planning history. This document and answer files are
repository-only: VuePress builds `docs/`, not `workshop/`.

## Teach one investigation

Open with the participant's mission: **the order endpoint returns correct data, but does unnecessary database
work; find the cause, make one safe change, and prove the running app improved without changing its response**.
Keep the original route on screen and connect every core checkpoint to that question, not to a panel tour.

Let participants predict from the original query/mapping in Chapter 02, then explain the measured 1 + 16 SELECTs
in Chapter 03. Use repeated samples and the control to challenge their hypothesis. Save the control query's
implementation and the join-fetch solution for Chapter 06; the control's measured output/cost is evidence earlier,
not an answer to copy. Ask for the participant's explanation before the coding agent's proposal.

The import, transaction, and asynchronous fixtures are short side investigations about evidence limits.
They are not additional fix assignments. Cache inspection and the extension labs are optional.
Close with the original report: unchanged response, captured SQL reduction, changed-method execution, and limits.
Do not substitute a score or an unmeasured latency claim for that outcome.

## Before the event

Send pre-work several days ahead: release-tagged clone, warmed Maven build, JDK, editor, authenticated coding agent,
consumer skill, reviewed CLI installer, and data-sharing policy. Docker is unnecessary.
Require Chapter 01's complete pre-work, including a successful actual transport read, before arrival. The live
15-minute slot verifies readiness rather than installing/configuring clients. Bind the host app explicitly to
`127.0.0.1`; BootUI's console guards do not secure its intentionally unsafe application fixtures.
Docker/Podman is an additional requirement only for the optional Quarkus sample's PostgreSQL Dev Services.

Record the actual **2.4.0** tag SHA/artifact checksums. Verify the immutable release includes these
materials and all used v2 surfaces. Do not claim published artifacts were checked from a development checkout.
Rehearse from a fresh clone, not the helper's prebuilt workspace.

Have one helper for about 15-20 participants and pair where useful. Keep spare power/network, a prepared local clone,
and reviewed reference excerpts. Never exchange employer code or private credentials to repair an account.

Rehearse on the event OS/JDK/client combinations, including Windows commands and a real MCP tool read. Test CLI
independently. Allocate download time outside the session. Use dedicated ports for reference and participant apps.

## Run of show

| Time | Teaching focus | Stop/advance criterion |
| --- | --- | --- |
| 00:00 | Set the order-query mission; show one request; distinguish two agents and evidence | Participants can state success without a score |
| 00:10 | Confirm app identity, journal, transport, absent Java agent | Real read succeeds; pair blocked setups |
| 00:25 | Trace original route/bean/datasource and one Hibernate scan; defer control query | Participant prediction recorded, no broad cleanup |
| 00:40 | Measure original/control SQL; explain 1 + N; short import side investigation | Request ids, query counts, cause hypothesis |
| 01:05 | Challenge the hypothesis with six samples and the control; short transaction comparison | Observation with sample/coverage and counterexample |
| 01:30 | Ten-minute break/catch-up | Instrumentation artifacts warm |
| 01:40 | Attach Java agent; code path/baseline; facilitator async example | Sensor/self-tests read and baseline exercised; process/probe optional |
| 02:05 | Participant diagnosis, bounded assessment, one impact read, then control-query review | Cause explained; stable plan/action; approval not sent yet |
| 02:20 | Test red, approved patch, green, same-JVM reload, live verification | Four capstone evidence items |
| 02:55 | Partner handoff and stack limitations | One unsupported claim identified |
| 03:00 | End | Extensions are take-home, not overruns |

At each slot, protect the next checkpoint. Chapter 05 budgets 10 minutes for attachment, 10 for the baseline/path,
and 5 for a facilitator-led async example. Leave process/probe labs optional or demonstrate them only with spare time.
Drop optional transaction self-invocation/JFR/service tours before
compressing capstone verification. Use the break for pairing, not for a mandatory extra lab.

## Answer notes

### Wiring, journal, and Insights

`EagerDemoOrderService` calls a repository query that does not fetch its eager customer explicitly. Sixteen distinct
customers lead to secondary SELECTs. The joined control fetches the relationship while preserving `OrderSummary`.
Both methods remain read-only transactional.

The import throws/rolls back, is caught, logs ERROR, and responds accepted/200. Query/log/transaction evidence is
stronger than HTTP status alone. Rich exception detail can expire before the structural journal row.

Open the **Repeated SELECTs** group and select the route in **Observation**, or search for it. The CLI's compact
default still omits this kind: use `bootui insights list --query repeated-selects --json`. One
request does not meet the observation threshold. The joined control's lack of a recommendation is not sufficient
without its query evidence and coverage.

Confirm uses a separate audit transaction; ship is the same-transaction control. Self-invocation crosses no proxy.
Do not imply every nested transaction or after-response task is wrong; intended semantics determine the fix.

### Instrumentation

`ARMED` is attachment/claim state, not proof each sensor passed. Raw-pool ownership requires executors instrumentation.
The full JVM stop at Chapter 05 also discards cached advisor reports and journal observations. Keep Chapter 02/03
worksheet notes historical. Chapter 06 assesses the new run's eager-order evidence; it cannot retrieve earlier
advisor findings without a separately approved fresh scan.
The waited-for control path is `/api/insights/orders/after-response/waits`, not `/api/insights/orders/waits`.

The subprocess route starts Java; the runtime-version route does not. Their text formatting can differ.
Probe activation must precede traffic. Metadata-only bounds are 20 invocations/60 seconds/five live probes.

Preserve the Chapter 05 JVM and its `.workshop-reload` trigger. Compile/test can update classes/resources in
multiple batches, creating idle intermediate runs with ordinary automatic DevTools polling. The trigger holds
restart until all work finishes, then one timestamp update releases it. The comparison uses the most recent
retained previous run; it does **not** skip idle or incompatible runs. This is essential for deterministic
code-change and behavior evidence.

Source coverage can still make the automated behavior comparison **PARTIAL**, even with the correct baseline
and six requests. Have participants read that reason rather than repeat traffic indefinitely.
Retained request profiles can support the observed query reduction and Code Inventory can support execution,
while the automated comparison remains explicitly incomplete. Do not promise every reference sample yields a
fully comparable run or clear recording to hide a coverage gap.

### Capstone

Apply the service-only answer in a disposable exercise clone:

```bash
git apply --check workshop/answers/eager-orders.patch
git apply workshop/answers/eager-orders.patch
```

Install the supplied fixture test using Chapter 07's copy command. Before the patch it fails only the 1-2 statement
budget with 17 statements; after the patch it passes. Query count normally becomes one. Check JSON equality/fields/
order independently, and inspect the live executed method and comparable runs.
Prepare approval in Chapter 06 but send it only **after** installing the test and checking red in Chapter 07.
The agent may run the installed test and compile, not release the live restart. The participant reviews the diff/
result and updates the trigger after success. Check `git status --short` too: the copied test and consumer skill
are untracked, so they are invisible in a tracked-only diff.

The upstream bad-fixture test is intentionally incompatible afterward. Do not edit normal BootUI fixtures in the
published workshop PR or advertise a passing full suite after this educational change.

If your answer patch fails to apply, inspect the release source and workshop baseline; do not force it onto changed
participant work. Restore only their approved service edit after review when repeating the exercise.

## Reference transcript for provider outages

**Illustrative review exercise, not a captured live tool transcript.** Identifiers and timings are deliberately
absent. Use this to critique permissions/evidence; it cannot satisfy the live completion checkpoint.

```text
Participant: Existing evidence only; <=8 BootUI reads/3 minutes; eager-order pair; no actions.
  Earlier advisor notes are historical, not a current cached report.
Coding agent: Context: original route has repeated SELECT evidence; control uses a join.
  Coverage: local MVC fixture; supported journal/SQL and installed inventory/code-path sensors.
  Not a complete application assessment. Unexercised routes remain unknown.
  Plan workshop-sql-v1, action SQL-1: original service calls existing join-fetch finder.
  Verify equal 16-summary response, 1-2 prepared statements, live changed-method execution.
Participant: Installs the acceptance test and verifies the expected red query-budget result.
  Approve only SQL-1 from workshop-sql-v1, run installed test and compile; no traffic/scans or trigger change.
Coding agent: Shows service-only diff and targeted acceptance result; requests live verification.
Participant: Reviews diff/result, releases one trigger-file reload, and generates six original/control requests.
  Records current/prior run ids.
Coding agent: Reads changed inventory/profile/comparison; separates observed SQL reduction from
  unmeasured effects and refuses a blanket healthy/no-effects statement.
```

Have pairs identify which permission covers each step. Replace this with a reviewed, sanitized actual-client
transcript for the event if available, clearly labeling client/version/date and remaining omissions.

## Reference and checkpoint preparation

Prepare these from the final release during rehearsal, not from invented data:

- A baseline profile/Insights screenshot with route, sample count, journal/sensor state.
- An after-fix profile/comparison screenshot with response/test evidence and executed method.
- A short labeled JFR example only for the optional lab.
- A clean baseline clone and a separate answer clone, with distinct ports and run ids.

Participant pages do not depend on screenshots or slides. The facilitator can use Chapter 00's explanations as the
opening visual and Chapter 07's four-item checkpoint as the closing slide.

Keep code files, test outputs, and reviewed screenshots local/offline for account/provider outages. Label
pre-recorded/reference evidence; do not hand participants a completed worksheet with fake “their run” identifiers.

### Maintaining the reference illustrations

The published workshop illustrations use the Features screenshot mechanism with deterministic workshop fixtures.
They show where to look, not evidence that a participant completed an exercise. Keep them separate from the actual
release-rehearsal captures above.

Point `BOOTUI_SCREENSHOT_FRONTEND_DIR` at the pinned release's frontend checkout, with its locked dependencies
prepared, then run from the Spring E2E directory:

```bash
cd bootui-spring-sample-app/e2e
BOOTUI_SCREENSHOT_FRONTEND_DIR="/absolute/path/to/pinned-release/bootui-ui/src/main/frontend" \
  BOOTUI_SCREENSHOT_PLATFORM=workshop \
  BOOTUI_SCREENSHOT_PORT=18113 \
  node scripts/capture-docs-screenshots.mjs
```

Use the released frontend, not newer UI relabeled as the release. Review fixture metadata and the overview's
immutable screenshot-provenance commit when regenerating. Visually inspect every output: preserve the
investigation's sequence, frame the evidence legibly, and show incomplete coverage honestly. Do not turn a PARTIAL
comparison into a successful comparison for the illustration.
Workshop mode reads version metadata from that checkout and requires its own frontend server: leave
`BOOTUI_SCREENSHOT_BASE_URL` unset and choose an unused capture port.
After each release, regenerate from the new tag and update the overview's provenance commit; the version helper
updates text pins, not image pixels.
Keep immutable rehearsal records and legacy-release caveats inside paired
`bootui-workshop:history:start` / `bootui-workshop:history:end` HTML comments so version bumps preserve them.

## Validation and release rehearsal

Implementation validation can prove site routing/links, the original fixture, the supplied test's red/green result,
and a same-JVM change loop using current checkout artifacts. It cannot establish a future release download or a
full three-hour pilot with unfamiliar participants.

Before each workshop delivery:

1. Run `npm run docs:build`, `node --test docs/.vuepress/workshop.test.mjs`, and
   `python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'`.
2. Inspect Workshop after Features in desktop/mobile navbar, its ordered sidebar after Get started, the homepage
   row, and all clean `/boot-ui/workshop/...` routes.
3. From **v2.4.0**, run warm build/launch, baseline fixture test, supplied test red/green, live reload and comparison.
4. Verify the pinned published CLI/agent/consumer skill, real MCP client, default sensors/self-tests, and optional
   unavailable states.
5. Pilot the exact 180-minute agenda including questions, break, pairing, Windows, provider latency, and recovery.

Deployment uses the existing VuePress/release-line gates; do not add a separate site or bypass the publication
policy. The target is `https://www.julien-dubois.com/boot-ui/workshop`, **not** `/bootui/workshop`.

### Validation evidence and remaining rehearsal

<!-- bootui-workshop:history:start -->
The initial generation checks used the development checkout. A subsequent five-model rehearsal preceded the first
published workshop edition: two personas used the tag at `7d4bf29eb48e2016e17ec13087a2618eedb74416`, and three used the tag at
`ddfcb243615fae417cea2c46e11797bffbc43231`. This is historical evidence, not proof that all five ran that edition
or its final revision. That edition's separate rehearsal is recorded below.

| Check | Result |
| --- | --- |
| `npm run docs:build` | Passed; all 15 participant pages generated |
| `node --test docs/.vuepress/workshop.test.mjs` | Passed; checks cover navigation, links, exact 180-minute agenda, delayed-solution story order, Bash syntax, loopback launches, Windows failure handling, approval/reload ordering, and answer-patch applicability |
| Existing VuePress analytics tests | Passed; unchanged behavior |
| `python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'` | Passed; generated targets and fragments |
| `bash .github/scripts/check-docs-downloads.sh` | Passed; installer downloads and shellcheck |
| Sample `EagerDemoOrdersTest` on original source | Passed |
| Supplied `WorkshopEagerOrdersTest` on original source | Expected query-budget failure: 17 statements |
| Same supplied test after reference service change | Passed; response fields/order/equality and query budgets |
| Live trigger-file DevTools loop | Recorded two exercised runs; exactly one changed method, not exercised then executed, 17 -> 1 statements/request in separate profiles, identical JSON; aggregate SQL comparison can remain PARTIAL |
| Core fixture traffic and default sensors | Routes answered as specified; required sensors installed with passing self-tests |
| Metadata-only method probe | Captured exactly two invocations with 20-invocation/60-second bounds |
| Supplementary fixture check: `EagerDemoOrdersTest,RuntimeInsightsSeedsTest` | Passed; no failures/skips, with JDK 26 and isolated `.m2` |
| Audited loopback launch | Passed with the Java agent: listener restricted to `127.0.0.1`, 16 order summaries, required sensors installed with passing self-tests |
| Five distinct model personas | All completed the core RED/GREEN and live capstone; all reported PARTIAL SQL comparison because startup events were missed before aggregate registration |
| Built-site browser inspection | All participant routes and reference images loaded; desktop and narrow-viewport layouts inspected |

Release rehearsal on 2026-11-04 used a fresh **v2.3.0** clone at
`7c89042c6a0a47e1cb22d2df7412d2177d7416d1`, macOS, and JDK 17. The public installer downloaded the pinned CLI,
verified its published checksum, and returned `bootui 2.3.0`. The tagged warm build, loopback agent launch,
overview/agent/Insights CLI reads, and original fixture test passed. The acceptance test failed at 17 statements
before the reference patch and passed afterward. One trigger-file reload marked exactly one method changed,
first unexecuted and then executed; the live fixed request captured one SQL statement and returned unchanged JSON.
The run comparison reported **PARTIAL** because startup SQL preceded aggregate registration; it could compare
the code change, but could not establish complete SQL capture across both runs.

A subsequent development-checkout regression for #1594 fixes aggregate registration before Spring exposes the
journal. The supplied acceptance test again failed at 17 statements and passed after the exact finder change.
An agent-attached, same-JVM test with one DevTools reload returned identical JSON, marked the one changed method
unexecuted then executed, and reported `COMPARED` with 17 -> 1 statements/request and the secondary customer SELECT
removed through REST and MCP. The checksum-verified published 2.0.0 CLI and the browser's **Changes** tab also
displayed those SQL rows against the fixed checkout using the saved original-run baseline. This is development
evidence, not a correction to the immutable v2.3.0 rehearsal above.

The reference service edit and copied test were temporary rehearsal changes, not changes to delivered sample
behavior. The later rehearsal added published CLI installation and tagged-source core runs; the workshop illustrations
were separately rendered from the released frontend and visually inspected. Those synthetic reference images are not
participant runtime proof. Future-release downloads, an authenticated external coding-agent session, Windows execution,
and an unfamiliar-participant three-hour pilot still require event/release rehearsal.
<!-- bootui-workshop:history:end -->

## Completion and feedback

Accept a worksheet only when it distinguishes source/test/live evidence and documents uncovered work. Partial
completion is valid but must be labeled. No perfect score or total-application-health claim is required.

Collect setup time, client friction, usefulness of observations, instrumentation clarity/overhead, approval quality,
and whether participants independently proved their changed code ran. Revise optional labs/timing from a pilot,
not by deleting verification.
