# BootUI v2 workshop facilitator guide

Teach from [the participant course](../docs/workshop/README.md), keep [the frozen manifest](exercise-manifest.md)
beside you, and preserve [the curriculum plan](PLAN.md) as planning history. This document and answer files are
repository-only: VuePress builds `docs/`, not `workshop/`.

## Before the event

Send pre-work several days ahead: release-tagged clone, warmed Maven build, JDK, editor, authenticated coding agent,
consumer skill, reviewed CLI installer, and data-sharing policy. Docker is unnecessary.
Require Chapter 01's complete pre-work, including a successful actual transport read, before arrival. The live
15-minute slot verifies readiness rather than installing/configuring clients. Bind the host app explicitly to
`127.0.0.1`; BootUI's console guards do not secure its intentionally unsafe application fixtures.
Docker/Podman is an additional requirement only for the optional Quarkus sample's PostgreSQL Dev Services.

Freeze the actual **2.0.0** tag SHA/artifact checksums when published. Verify the immutable release includes these
materials and all used v2 surfaces. Do not claim published artifacts were checked from a development checkout.
Rehearse from a fresh clone, not the helper's prebuilt workspace.

Have one helper for about 15-20 participants and pair where useful. Keep spare power/network, a prepared local clone,
and reviewed reference excerpts. Never exchange employer code or private credentials to repair an account.

Rehearse on the event OS/JDK/client combinations, including Windows commands and a real MCP tool read. Test CLI
independently. Allocate download time outside the session. Use dedicated ports for reference and participant apps.

## Run of show

| Time | Teaching focus | Stop/advance criterion |
| --- | --- | --- |
| 00:00 | Show one captured request; distinguish two agents and score/observations | Participants can explain evidence vs source |
| 00:10 | Confirm app identity, journal, transport, absent Java agent | Real read succeeds; pair blocked setups |
| 00:25 | Route/bean/property provenance and one Hibernate scan | Prediction recorded, no broad cleanup |
| 00:40 | Original/control requests, import failure behind 200 | Request ids and transaction/ERROR result |
| 01:05 | Six samples, repeated SELECTs, transaction control | Observation with sample/coverage and counterexample |
| 01:30 | Ten-minute break/catch-up | Instrumentation artifacts warm |
| 01:40 | Attach Java agent; code path/baseline; facilitator async example | Sensor/self-tests read and baseline exercised; process/probe optional |
| 02:05 | Bounded assessment, one separate impact read, prepare approval | Stable action id and plan version; approval not sent yet |
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

The repeated-SELECT recommendation is omitted from the default shortlist: use Show all routes/search/CLI. One
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

## Validation and release rehearsal

Implementation validation can prove site routing/links, the original fixture, the supplied test's red/green result,
and a same-JVM change loop using current checkout artifacts. It cannot establish a future release download or a
full three-hour pilot with unfamiliar participants.

Before publishing with v2:

1. Run `npm run docs:build`, `node --test docs/.vuepress/workshop.test.mjs`, and
   `python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'`.
2. Inspect Workshop after Features in desktop/mobile navbar, its ordered sidebar after Get started, the homepage
   row, and all clean `/boot-ui/workshop/...` routes.
3. From **v2.0.0**, run warm build/launch, baseline fixture test, supplied test red/green, live reload and comparison.
4. Verify the pinned published CLI/agent/consumer skill, real MCP client, default sensors/self-tests, and optional
   unavailable states.
5. Pilot the exact 180-minute agenda including questions, break, pairing, Windows, provider latency, and recovery.

Deployment uses the existing VuePress/release-line gates; do not add a separate site or bypass the publication
policy. The target is `https://www.julien-dubois.com/boot-ui/workshop`, **not** `/bootui/workshop`.

### Implementation evidence and remaining rehearsal

The workshop generation was checked against the development checkout, not the future immutable release:

| Check | Result |
| --- | --- |
| `npm run docs:build` | Passed; all 15 participant pages generated |
| `node --test docs/.vuepress/workshop.test.mjs` | Passed; 11 checks covering navigation, links, exact 180-minute agenda, Bash syntax, loopback launches, Windows failure handling, approval/reload ordering, and answer-patch applicability |
| Existing VuePress analytics tests | Passed; 10 checks, unchanged behavior |
| `python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'` | Passed; generated targets and fragments |
| `bash .github/scripts/check-docs-downloads.sh` | Passed; installer downloads and shellcheck |
| Sample `EagerDemoOrdersTest` on original source | Passed |
| Supplied `WorkshopEagerOrdersTest` on original source | Expected query-budget failure: 17 statements |
| Same supplied test after reference service change | Passed; response fields/order/equality and query budgets |
| Live trigger-file DevTools loop | Compared two exercised runs; exactly one changed method, not exercised then executed, 17 -> 1 statements/request, identical JSON |
| Core fixture traffic and default sensors | Routes answered as specified; required sensors installed with passing self-tests |
| Metadata-only method probe | Captured exactly two invocations with 20-invocation/60-second bounds |
| Five-persona audit: `EagerDemoOrdersTest,RuntimeInsightsSeedsTest` | Passed; 18 tests, no failures/skips, with JDK 26 and isolated `.m2` |
| Audited loopback launch | Passed with the Java agent: listener restricted to `127.0.0.1`, 16 order summaries, required sensors installed with passing self-tests |
| Audit design detector | No deterministic findings; browser/Windows execution still not established |

The reference service edit and copied test were temporary rehearsal changes, not changes to delivered sample
behavior. Final-release downloads, an authenticated external coding-agent session, Windows execution, desktop/mobile
visual inspection, and an unfamiliar-participant three-hour pilot still require event/release rehearsal. Browser
canvas inspection was unavailable during generation; generated navigation/link checks are not a substitute for that
visual check.

## Completion and feedback

Accept a worksheet only when it distinguishes source/test/live evidence and documents uncovered work. Partial
completion is valid but must be labeled. No perfect score or total-application-health claim is required.

Collect setup time, client friction, usefulness of observations, instrumentation clarity/overhead, approval quality,
and whether participants independently proved their changed code ran. Revise optional labs/timing from a pilot,
not by deleting verification.
