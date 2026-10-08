# BootUI 2.0 validation report

This report checks whether Runtime Insights is worth reading on applications that were not written for BootUI
([v2 plan](PLAN-v2.md) §2.2 and §2.4). It is filled in from an [early-adopter build](V2-EARLY-ADOPTERS.md) and rerun
before 2.0.0.

**Draft, first run (2026-10-03), for the maintainer's review.** Built from `v2` at `fb4cc07e5` (version `1.19.0`).
Every application ran with its own Maven repository seeded with that build, and each run proved it used it: the
`runtime-insights` endpoint answered, and the resolved `bootui-engine` jar's SHA-256 matched the local build. Two
reviewers on different models judged every observation independently, with the application's source at hand; the
operator who ran each application gathered facts and code pointers but judged nothing.

**Rerun (2026-10-05), scored.** M4-20's rerun ran under the registered protocol (`m4-20-protocol-2`) on one build of
`5bd7cb76e`; two reviewers judged it, the maintainer adjudicated it, recall was marked under his rule with his approval, and the registered scorer
computed the result. See [Rerun results](#rerun-results) and the
[adjudication file](V2-VALIDATION-ADJUDICATION.md). The first run's sections below are kept as they were.

## Release gates, first run

| Measure | Target | Result |
| --- | --- | --- |
| External validity | On the five applications, ≥ 70 % of observations judged actionable or informative by two reviewers, and none misleading | **Not met.** 16 of 116 observations (14 %) judged actionable or informative by both reviewers; 19 judged misleading by at least one, 6 by both (corrected on 2026-10-05, see below) |
| Time to first observation | ≤ 5 minutes from adding the dependency to reading a first observation, with tracing off and no extra property | **Not met on Quarkus.** 0.4 min (PetClinic), 0.8 min (Kafka), 1.9 min (WebFlux), 2.4 min (JHipster, mostly the traffic script), 8.5 min (Quarkus Super Heroes, which needed a workaround to start, see Findings). Several first observations were only `INSUFFICIENT` or latency rows |
| Agent effectiveness | Ten scripted investigations answered correctly from tool output alone, with fewer tool calls than with 1.x tools; five refusal fixtures where the right answer is not to edit | **Not met.** 7 correct and 3 partial with 2.0, against 5 correct, 2 partial, and 3 wrong with 1.x tools; 123 calls against 130 (65 against 73 without `--help`), the saving coming only from the two run-comparison questions; no agent edited code in the five refusal fixtures, but one gave the wrong reason |

The external-validity result also trips §2.3's gate after M3: fewer than 50 % of external-application observations
were judged useful.

## Release sign-off

**Not signed off.** This section is the release decision for 2.0.0 ([v2 plan](PLAN-v2.md) §4.3). M4-20's rerun fills
every pending cell below under its registered protocol, and the maintainer signs it off. Done is not passed: a measure that
was run but missed its target is recorded as **Not met**, with its exception, never left out or rounded up. 2.0.0 is
not released while any cell is still pending.

| Field | Value |
| --- | --- |
| Release candidate | TODO: `v2` commit and its BootUI version. The rerun's build: `5bd7cb76e` (version `1.19.0`, engine SHA-256 `e913e36b…`), not yet a release candidate |
| Rerun date | 2026-10-05 (runs 09:38–10:29, time to first observation 10:53–11:23, Europe/Paris) |
| Registered protocol | [Protocol for the rerun](#protocol-for-the-rerun), registered on 2026-10-05 (M4-20); `m4-20-protocol-2`, which superseded `m4-20-protocol-1` (see the amendment below): tag object `5f38a51affaeb11a8c35f9a1b0185d964dd23f7d`, commit `5bd7cb76eb532f1a72bf3a1ab8b018190c6214a2` |
| Applications | Tuned: Spring PetClinic `500158f`, JHipster sample `6b000b5d`, Quarkus Super Heroes `d472e71d6`, WebFlux gateway `73b700b`, Kafka saga `a76daeb`. Holdouts: bookstore `2933f5f`, Timeless `0a90516` (pins and patches in [`validation/apps/`](https://github.com/jdubois/boot-ui/tree/v2/validation/apps)) |
| Reviewers and adjudicator | r1 on claude-opus-5.5 and r2 on gpt-6-sol, independent and one after the other; Julien Dubois, the maintainer, adjudicated the 26 disputed rows, advised by three blind models; his coordinator agent set the recall marks under his rule, and he approved them (see [Adjudication](#adjudication)) |
| Known limitations | TODO: [Known limitations](KNOWN-LIMITATIONS.md) updated to the shipped scope |
| Decision | TODO: release 2.0.0, or not, and why |

### Success measures

One row per §2.2 measure. **Result** is the measured value; **Status** is **Met**, **Not met**, or **Not measured**.

| Measure | Target | How it is verified | Result | Status |
| --- | --- | --- | --- | --- |
| Exact correlation | ≥ 99 % of request-thread events carry their request id on Spring MVC and Quarkus, with and without tracing; WebFlux reports its measured coverage | The concurrency scenario on the sample apps, in CI | TODO: per stack, with and without tracing | TODO |
| Capture overhead, application thread | < 2 µs p99 for the full application-thread path on a reference machine | The timed engine test | TODO | TODO |
| Capture overhead, throughput | Sample-app throughput within 5 % with the journal on versus off | The sample-app benchmark scenario, BootUI on in both runs | TODO | TODO |
| Java agent overhead | Sample-app throughput within 10 % with the default sensors claimed | The `agent-overhead` job | TODO | TODO |
| External validity, tuned applications | ≥ 70 % of default-visible distinct facts judged actionable or informative by both reviewers, none misleading | The rerun on the five tuned applications | 7 of 20 facts (35 %) useful to both reviewers; 0 misleading after adjudication (4 judged misleading by one reviewer, all adjudicated otherwise) | **Not met** (35 % < 70 %) |
| External validity, holdout applications | The same target, scored apart | The rerun on the two holdout applications | 3 of 11 facts (27.3 %) useful to both; 2 misleading after adjudication: the bookstore's `connections-per-request` (both reviewers) and Timeless's `ai-usage-by-route` (adjudicated) | **Not met** |
| Agent effectiveness | Ten scripted investigations answered correctly from tool output alone, with fewer tool calls than with 1.x tools; five refusal fixtures where the right answer is not to edit | The local agent benchmark, 1.x baseline measured first | 2.0: 6 correct, 3 partial, 1 wrong, 73 calls (10 `--help`); 1.x: 6 correct, 2 partial, 2 wrong, 138 calls (20 `--help`). The refusal fixtures are not part of the registered rerun and were not rerun | **Not met** (not all ten correct) |
| Agent effectiveness, with the agent | An eleventh investigation ("did my change run?") and a sixth refusal fixture, once M5-10 delivers them | The same benchmark with the agent attached | TODO, or **Not measured** if M5-10 is not in 2.0 | TODO |
| Time to first observation | ≤ 5 minutes from adding the dependency to reading a first observation, with tracing off and no extra property | A scripted walkthrough on each stack | Spring MVC 0.2–0.3 min (PetClinic, JHipster, bookstore), but Kafka listed no row within the 20-minute limit; Spring WebFlux 0.3 min; Quarkus 0.6 min (Super Heroes) and 0.4 min (Timeless) | **Not met on Kafka**, met on every other application |
| Honesty | No observation on any counterexample fixture; "not enough evidence" never reads as "no change" | Fixture tests per observation, and the rerun's `INSUFFICIENT` and `NOT_APPLICABLE` rows judged apart | The rerun's part: 100 of 100 honesty rows honest after adjudication (95 by both reviewers, 5 adjudicated), none misleading; every no-change comparison reported 0 behavior changes, and the two that could not compare said so. Counterexamples: 21 of 22 respected; TL-C2 is marked violated only through the scorer's subject-level rule (see [Recall](#recall)). Fixture tests: TODO | TODO: met for the rerun's honesty rows; the fixture tests are not part of the rerun |

### Gates

§2.3's gates and D35's escalation, applied to the rerun.

| Gate | Rule | Result | Outcome |
| --- | --- | --- | --- |
| After M1 | Exact correlation ≥ 95 % on Spring MVC and Quarkus | Passed: 100 % on every stack (M1-6f) | Recorded |
| After M3, global | Default-visible score ≥ 30 %, otherwise every kind missing its per-kind gate folds and Runtime Insights is presented as a Live Activity view | 10 of 31 default-visible facts on the seven applications (32.3 %) useful to both reviewers | Passed: no escalation |
| After M3, holdouts | Holdout applications no more than 20 points below the tuned ones, with the same consequence | Tuned 35 % (7 of 20), holdouts 27.3 % (3 of 11): 7.7 points below; neither holdout is empty | Passed: no escalation |
| Before 2.0.0, overhead | Journal throughput within 5 %; if missed, the journal ships disabled by default and the release notes say so | TODO | TODO |

### Per-kind gates

A kind passes with at least 3 default-visible facts on at least 2 applications, at least 50 % of them useful to both
reviewers, and nothing misleading still listed by default. Below that it folds into its panel or stays hidden; a kind
that stays silent on every application stays listed, marked as not externally validated. **Outcome** is one of
**Listed**, **Folded into** a named panel, **Hidden**, or **Listed, not externally validated**.

| Kind | Facts | Applications | Useful to both | Misleading | Gate | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| `route-time-breakdown` | 6 | 3 | 2 of 6 (33.3 %); tuned 0/2, holdout 2/4 | 0 | Fail | **Folded into** its panel, or hidden |
| `repeated-selects` | 4 | 1 | 0 of 4 (0 %); tuned 0/4, holdout 0/0 | 0 | Under-sampled | **Hidden**, not externally validated: too few facts |
| `lazy-sql-after-handler` | 4 | 1 | 4 of 4 (100 %); tuned 4/4, holdout 0/0 | 0 | Under-sampled | **Hidden**, not externally validated: too few facts |
| `exception-hotspots` | 9 | 6 | 1 of 9 (11.1 %); tuned 0/6, holdout 1/3 | 0 | Fail | **Folded into** its panel, or hidden |
| `errors-behind-2xx` | 3 | 2 | 3 of 3 (100 %); tuned 3/3, holdout 0/0 | 0 | Pass | **Listed** |
| `connections-per-request` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 1 | Fail | **Folded into** its panel, or hidden |
| `safe-method-dml` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `transaction-across-remote-call` | 0 | 0 | — | 0 | Not exercised | **Listed, not externally validated**: its check never ran |
| `split-transaction-writes` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 0 | Under-sampled | **Hidden**, not externally validated: too few facts |
| `after-commit-writes` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `transactional-listener-skipped` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `proxy-bypass` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `framework-warnings-by-route` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 0 | Under-sampled | **Hidden**, not externally validated: too few facts |
| `event-loop-blocking` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `ai-usage-by-route` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 1 | Fail | **Folded into** its panel, or hidden |
| `anonymous-data-reach` | 1 | 1 | 0 of 1 (0 %); tuned 0/1, holdout 0/0 | 0 | Under-sampled | **Hidden**, not externally validated: too few facts |
| `anonymous-success-on-restricted-route` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `orm-auto-flush` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `large-persistence-context` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `gc-inflated-latency` | 0 | 0 | — (15 hidden rows, 6 sampled, 0 useful to both) | 0 | Not listed by design | Not listed by default; judged through the hidden sample |
| `heap-growth-after-gc` | 0 | 0 | — (4 hidden rows, 4 sampled, 0 useful to both) | 0 | Not listed by design | Not listed by default; judged through the hidden sample |
| `work-after-response` | 0 | 0 | — | 0 | Silent | **Listed, not externally validated** |
| `changed-code-not-executed` | 4 | 2 | 2 of 4 (50 %), all four from the agent runs | 0 | Pass | **Listed** |

**Applied** by M4-24 ([v2 plan](PLAN-v2.md)): each outcome above is recorded once in the engine's
`ExternalValidation` registry, which decides the default list on Spring MVC, Spring WebFlux, and Quarkus. The outcomes
stay in this report and the plan: the panel, the JSON, and agent answers never show them, and a row left out of the
default list says only where its evidence is shown (maintainer decision, 2026-10-07). The four failed kinds
are folded: `route-time-breakdown` into Live Activity's **Why this route is slow**, `exception-hotspots` into the
Exceptions panel, `connections-per-request` into Database Connection Pools, and `ai-usage-by-route` into the AI
Framework panel, each of which links to the kind's rows; every row stays in the full report. Applying a gate as
registered is not an exception.

### Exceptions

Every measure, gate, or kind that ships without meeting its target, and every deliberate deviation from the registered
protocol, with who accepted it and what the release notes say.

| # | Measure, gate, or kind | Deviation | Reason | Accepted by | Release note |
| --- | --- | --- | --- | --- | --- |
| TODO | | | | | |

### Sign-off

| Role | Name | Date |
| --- | --- | --- |
| Maintainer | TODO | TODO |

<a id="rerun-results-provisional"></a>

## Rerun results

**Scored.** Run on 2026-10-05 under `m4-20-protocol-2`, exactly as registered: no registered file was changed. The
maintainer adjudicated the 26 disputed rows and approved the recall marks set under his rule (see [Adjudication](#adjudication)), and the registered
scorer, run from a detached checkout of the tag (`5bd7cb76e`, tag object `5f38a51af`, the one origin publishes), found no
problem and wrote [`score.md`](https://github.com/jdubois/boot-ui/blob/v2/docs/validation/m4-20-rerun/score/score.md) and `score.json`. Every number below comes from them. Nothing
is pending in this section; the release sign-off's remaining `TODO`s are measures outside the rerun. The data that reproduces every number is in
[`docs/validation/m4-20-rerun/`](https://github.com/jdubois/boot-ui/blob/v2/docs/validation/m4-20-rerun/README.md) (kept in the repository, not published on the documentation site): the evidence, the worksheet, both reviewers' files, the
investigations, and the time to first observation.

What the adjudication could and could not change: the default-visible score, the tuned and holdout scores, and so the
global and holdout gates and escalation count facts **useful to both reviewers**, which no adjudication changes. The
adjudication settled which disputed rows are Misleading, which decides the "none misleading" part of each target and of
the per-kind gates, and the honesty rows judged as hiding something by one reviewer.

### Adjudication

**Who decided, and how.** The maintainer filled in every adjudication row himself, in
[`adjudication.csv`](https://github.com/jdubois/boot-ui/blob/v2/docs/validation/m4-20-rerun/adjudication.csv). Unsure of his first answers, he had three advisory models (claude-opus-5, gpt-6.1-sol, and grok-4.7) judge the same 26 rows blind, with the
reviewers' names anonymized, and applied one rule: if the majority of the advisors agree, they know better than he does.
That rule changed 18 of his 26 first answers; the other 8 rulings are his first answers, which a majority of the advisors
confirmed. His first answers stay in his own workbook, which is not committed, and each committed reason says which way
the advisors went ("Followed 3 of 3 advisory models (Informative, against my first Misleading): …"). The rulings remain
the maintainer's: the protocol makes him the adjudicator, and the advisors are his means, not additional reviewers.

The recall marks in [`recall.csv`](https://github.com/jdubois/boot-ui/blob/v2/docs/validation/m4-20-rerun/recall.csv) were set by the maintainer's coordinator agent under his rule,
the advisors' two-of-three majority, and he approved them; two marks were set by the protocol rather than by the
majority:

- **PC-C1** (`GET /oups` throws on purpose) stays **respected**, against a two-of-three "violated": a counterexample is
  violated only by a fact adjudicated Misleading, and both reviewers judged the `/oups` hotspot Noise.
- **TL-C2** (no transaction is held across Timeless's AI call) is marked **violated**, against the advisors' three of
  three "respected", because the registered scorer counts any fact adjudicated Misleading on the counterexample's
  subject (`POST /api/messages`). That fact is `timeless/fact/54ac7ea2`, the `ai-usage-by-route` row, ruled Misleading
  for its token wording ("grew up to 1.0 times"), not for anything about transactions; no row says a transaction is held
  across the call. This subject-level rule is coarse: it is a protocol issue to revisit before any future protocol
  (see [Known harness issues](#known-harness-issues-under-m4-20-protocol-2)).

**What was public before the rulings.** The provisional per-kind table below was made public with the reviewers'
judgments, before the maintainer adjudicated.
It shows which pending rows can move a gate: the two Super Heroes `changed-code-not-executed` rows (r1 Informative,
r2 Misleading) decide that kind's gate, which passes unless either is adjudicated Misleading. No other pending row
decides whether a kind is listed: the other kinds with a pending Misleading row (`repeated-selects`,
`split-transaction-writes`, `ai-usage-by-route`) are under-sampled, and a Misleading ruling only turns that into a
failed gate, so they stay off the default list either way; the global and holdout gates do not depend on any ruling.
The maintainer adjudicated knowing this. The table is kept below as published; the final per-kind table is in
[Per-kind gates](#per-kind-gates).

<details>
<summary>The provisional per-kind table, as published before adjudication</summary>

| Kind | Facts | Applications | Useful to both | Misleading | Gate | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| `route-time-breakdown` | 6 | 3 | 2 of 6 (33.3 %); tuned 0/2, holdout 2/4 | 0 | Fail | Folded into its panel, or hidden (provisional) |
| `repeated-selects` | 4 | 1 | 0 of 4 (0 %); tuned 0/4, holdout 0/0 | 0, 4 pending | Under-sampled unless a pending row is adjudicated Misleading | Hidden, not externally validated: too few facts (provisional) |
| `lazy-sql-after-handler` | 4 | 1 | 4 of 4 (100 %); tuned 4/4, holdout 0/0 | 0 | Under-sampled | Hidden, not externally validated: too few facts (provisional) |
| `exception-hotspots` | 9 | 6 | 1 of 9 (11.1 %); tuned 0/6, holdout 1/3 | 0 | Fail | Folded into its panel, or hidden (provisional) |
| `errors-behind-2xx` | 3 | 2 | 3 of 3 (100 %); tuned 3/3, holdout 0/0 | 0 | Pass | Listed (provisional) |
| `connections-per-request` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 1 | Fail | Folded into its panel, or hidden (provisional) |
| `safe-method-dml` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `transaction-across-remote-call` | 0 | 0 | — | 0 | Not exercised | Listed, not externally validated: its check never ran (provisional) |
| `split-transaction-writes` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 0, 1 pending | Under-sampled unless a pending row is adjudicated Misleading | Hidden, not externally validated: too few facts (provisional) |
| `after-commit-writes` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `transactional-listener-skipped` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `proxy-bypass` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `framework-warnings-by-route` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 0 | Under-sampled | Hidden, not externally validated: too few facts (provisional) |
| `event-loop-blocking` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `ai-usage-by-route` | 1 | 1 | 0 of 1 (0 %); tuned 0/0, holdout 0/1 | 0, 1 pending | Under-sampled unless a pending row is adjudicated Misleading | Hidden, not externally validated: too few facts (provisional) |
| `anonymous-data-reach` | 1 | 1 | 0 of 1 (0 %); tuned 0/1, holdout 0/0 | 0 | Under-sampled | Hidden, not externally validated: too few facts (provisional) |
| `anonymous-success-on-restricted-route` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `orm-auto-flush` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `large-persistence-context` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `gc-inflated-latency` | 0 | 0 | — (15 hidden rows, 6 sampled, 0 judged Actionable by either) | 0 | Not listed by design | Not listed by default; judged through the hidden sample (provisional) |
| `heap-growth-after-gc` | 0 | 0 | — (4 hidden rows, 4 sampled, 0 judged Actionable by either) | 0 | Not listed by design | Not listed by default; judged through the hidden sample (provisional) |
| `work-after-response` | 0 | 0 | — | 0 | Silent | Listed, not externally validated (provisional) |
| `changed-code-not-executed` | 4 | 2 | 2 of 4 (50 %), all four from the agent runs | 0, 2 pending | Pass unless a pending row is adjudicated Misleading | Listed (provisional) |

</details>

### Integrity

- One build: `v2` at `5bd7cb76e` (the `m4-20-protocol-2` commit), checked out clean in a dedicated worktree and built
  once with `build-v2.sh` into an isolated Maven repository (`BOOTUI_TREE_CLEAN=true`, 2026-10-05 07:37 UTC); engine
  SHA-256 `e913e36b5bc024761081f3133367374f98b7a83ebce581c4e2449cf815e3b9fe`. All 18 runs and comparisons proved they
  resolved that engine jar and recorded the same BootUI commit, engine SHA-256, and harness hash (`939f58d7…`); each time
  to first observation recorded that commit and proved the same engine jar. `v2` moved on during the rerun; no run used
  a later commit.
- The worksheet builder checked the run set, roles, commit, engine, harness, tag (annotated, the one origin publishes),
  the slowest-route stratum, and the unlisted-by-design kinds, and found no problem.
- The two reviewers ran as separate agents one after the other, on the registered prompt with only its bracketed paths
  filled in and their reviewer id given. r1's file was moved out of the work area before r2 started. Each judged all 198
  rows, with a `file:line` in every note, and changed no other cell.

### Runs

Every run served its traffic with 0 unexpected statuses. Rows are the full report (`query=all`) and the default list.

| Run | Role | Requests | Traffic, seconds | Rows (all / default) | Notes |
| --- | --- | --- | --- | --- | --- |
| PetClinic | Tuned | 8,400 | 85.7 | 39 / 9 | |
| JHipster | Tuned | 572 | 37.8 | 44 / 6 | |
| Super Heroes | Tuned | 395 | 25.0 | 17 / 1 | |
| WebFlux gateway | Tuned | 624 | 130.5 | 27 / 6 | |
| Kafka | Tuned | 241 | 177.8 | 3 / 0 | Order service 2 rows, stock service 1, payment service 0 |
| Bookstore | Holdout | 1,682 | 45.6 | 37 / 6 | |
| Timeless | Holdout | 879 | 62.4 | 20 / 6 | |
| JHipster, agent | Agent | 572 | 34.1 | 44 / 6 | `work-after-response` evaluated on 573 requests: 0 findings |
| Bookstore, agent | Agent | 1,682 | 43.4 | 39 / 6 | `work-after-response` evaluated on 1,683 requests: 0 findings |
| Super Heroes, agent, code change | Agent | 395 + 395 | 24.6 + 23.5 | 18 / 3 | `changed-code-not-executed`: `VillainResource` and `VillainService` |
| PetClinic, agent, code change | Agent | 8,400 + 8,400 | 90.0 + 86.2 | 38 / 11 | One DevTools restart, stable for 15 s; `changed-code-not-executed`: `Owner` and `Vet` |

No-change comparisons (two runs sharing a baseline file, same traffic): PetClinic, JHipster, Super Heroes, WebFlux
gateway, the bookstore, Timeless, and Kafka's order service answered `COMPARED` with **0 behavior changes and 0 edge
changes**. Kafka's payment and stock services answered `NO_PREVIOUS_RUN` and said why: the harness gives the three
services one baseline file, written last by the order service, so the other two ignore it as another application's.
That is a limit of the harness's Kafka configuration, reported honestly by BootUI, not a false "no change" (see
[Known harness issues](#known-harness-issues-under-m4-20-protocol-2)).

### Scores

| Group | Facts | Useful to both | Score | Misleading, adjudicated | Misleading, either reviewer |
| --- | --- | --- | --- | --- | --- |
| Seven applications (pooled) | 31 | 10 | 32.3 % | 2 | 7 |
| Tuned | 20 | 7 | 35.0 % | 0 | 4 |
| Holdouts | 11 | 3 | 27.3 % | 2 | 3 |
| Agent runs (scored apart) | 4 | 2 | 50.0 % | 0 | 2 |

| Application | Role | Facts | Useful to both | Misleading, adjudicated |
| --- | --- | --- | --- | --- |
| PetClinic | Tuned | 9 | 4 | 0 |
| JHipster | Tuned | 6 | 2 | 0 |
| Super Heroes | Tuned | 1 | 0 | 0 |
| WebFlux gateway | Tuned | 4 | 1 | 0 |
| Kafka | Tuned | 0 | — | 0 |
| Bookstore | Holdout | 5 | 1 | 1 |
| Timeless | Holdout | 6 | 2 | 1 |
| PetClinic, agent | Agent | 2 | 2 | 0 |
| Super Heroes, agent | Agent | 2 | 0 | 0 |

The two misleading facts are the bookstore's `connections-per-request` on `POST /orders`, which both reviewers judged
Misleading (its three connections come from Spring Modulith's asynchronous listeners, not from a nested transaction in the
request), and Timeless's `ai-usage-by-route` on `POST /api/messages`, adjudicated Misleading for saying the tokens "grew up
to 1.0 times" and advising a shorter prompt. Against the first run (16 of 116 observations, 14 %, useful to both), the
default list now shows 31 facts, of which 32.3 % are useful to both reviewers. The global gate (30 %) passes and the
holdouts are 7.7 points below the tuned applications, within the 20-point limit, so **escalation is not triggered**. The
70 % target is not met on either group.

### Hidden-row sample

63 hidden rows sampled (10 per application, 3 on Kafka, which has only 3), stratified as registered. One was judged
Actionable by one reviewer: WebFlux gateway's `EmailAlreadyUsedException` on `PUT /api/admin/users/{login}` (r1: every
update without an id is rejected as "email already used", and the default list shows it only inside the 4xx summary
row; r2: Noise). The maintainer adjudicated it Actionable, following all three advisors: `updateUser` rejects every update sent
without an id, so the route fails on every request while the default list folds it into the 4xx summary (a follow-up
below). None was useful to both reviewers, and none was judged Misleading. Among sampled rows, the actionable share is
at most 8.5 % at 95 % confidence; since the sample is stratified, that does not bound all hidden rows.
Per kind: `gc-inflated-latency` (15 hidden rows, 6 sampled) and `heap-growth-after-gc` (4, 4 sampled), unlisted by
design, had no row judged useful.

### Honesty

100 `INSUFFICIENT`, `PARTIAL`, and `NOT_APPLICABLE` rows: **all 100 honest** after adjudication, none misleading. 95
were judged honest by both reviewers; the other 5 were judged as hiding something by one reviewer and adjudicated
Honest: the bookstore's `after-commit-writes` check (Spring Modulith replaces the event multicaster, which the check
says) and `POST /login` timing, WebFlux gateway's `event-loop-blocking` check (it covers JDBC on the event loop, and
says so; r1 pointed at the mail service's blocking send), and its `POST /api/admin/users` and `POST /api/authenticate`
timing (WebFlux marks no phases, which the rows disclose).

### Recall

The maintainer marked the 52 registered items (see [Adjudication](#adjudication) for how), from the operator's
[recall evidence](https://github.com/jdubois/boot-ui/blob/v2/docs/validation/m4-20-rerun/recall-evidence.md): for each item, the worksheet rows and every row of the full report
naming its subject, with the checks and coverage lines.

| Application | Known misses | Found, default list | Found, hidden only | Honest gap | Missed | Recall (default list) | Counterexamples violated |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PetClinic | 6 | 3 | 0 | 1 | 2 | 50 % | none |
| JHipster | 4 | 2 | 1 | 0 | 1 | 50 % | none |
| Super Heroes | 5 | 2 | 0 | 1 | 2 | 40 % | none |
| WebFlux gateway | 5 | 2 | 1 | 2 | 0 | 40 % | none |
| Kafka | 4 | 0 | 0 | 1 | 3 | 0 % | none |
| Bookstore | 2 | 0 | 0 | 1 | 1 | 0 % | none |
| Timeless | 4 | 2 | 0 | 0 | 2 | 50 % | TL-C2 |
| **Total** | **30** | **11** | **2** | **6** | **11** | **36.7 %** | **1 of 22** |

Every known miss was exercised by the traffic. **Regressions**, items the first run found that the rerun's default list
does not show: PC-2 (`GET /owners`'s heavy tail, missed), JH-3 (the reset-password validation failure, found only in a
hidden row), SH-2 and SH-3 (Super Heroes' unpaginated villain list and random-villain queries, missed), WF-3 (every
`PUT /api/admin/users/{login}` failing, found only in a hidden row), and K-4 (Kafka's full store scan on `GET /orders`,
missed). Counterexamples: 21 of 22 respected; TL-C2 is marked violated by the scorer's subject-level rule described in
[Adjudication](#adjudication), not by any row about transactions.

### Agent runs

`work-after-response` was evaluated with 0 findings on JHipster (573 requests) and the bookstore (1,683), as in the
start checks: silent, so it stays listed, not externally validated (see the per-kind table). `changed-code-not-executed`
reported one class per run whose changed method the traffic never reached, as registered: `VillainResource` and
`VillainService` on Super Heroes, `Owner` (for `getPet(String)`) and `Vet` on PetClinic; it did not report the changed
methods the traffic runs. Both reviewers judged the two PetClinic rows useful. r2 judged the two Super Heroes rows
Misleading and r1 Informative; the maintainer adjudicated them Informative, following all three advisors (the classes
did not run; the row's hint to send `GET /api/villains` names the wrong HTTP method, a follow-up below), so the kind
passes its gate with 2 of 4 facts useful to both.

### Time to first observation

Measured once per application by `ttfo.sh`, after the maintainer paused the other sessions, each started when the
one-minute load average was below 10.

| Application | Stack | Load average at start (1, 5, 15 min) | First default-visible row | Seconds | ≤ 5 minutes |
| --- | --- | --- | --- | --- | --- |
| PetClinic | Spring MVC | 9.25, 20.39, 21.46 | `exception-hotspots` OBSERVED | 14.8 | Yes |
| JHipster | Spring MVC | 8.97, 17.64, 20.30 | `anonymous-data-reach` OBSERVED | 15.0 | Yes |
| Super Heroes | Quarkus (dev mode) | 8.14, 16.74, 19.89 | `exception-hotspots` OBSERVED | 34.0 | Yes |
| WebFlux gateway | Spring WebFlux | 9.97, 15.69, 19.23 | `errors-behind-2xx` OBSERVED | 19.3 | Yes |
| Kafka | Spring MVC | 9.86, 15.09, 18.83 | None within the 20-minute limit | — | No |
| Bookstore | Spring MVC | 9.02, 17.88, 16.09 | `connections-per-request` OBSERVED | 20.3 | Yes |
| Timeless | Quarkus (dev mode) | 9.38, 16.97, 15.82 | `ai-usage-by-route` OBSERVED | 26.1 | Yes |

Kafka's services list no row by default on its traffic (the measured runs too), so its first observation never comes.
The value recorded in `ttfo.jsonl` is `null`, which this table gives; the scorer's own table does not (see
[Known harness issues](#known-harness-issues-under-m4-20-protocol-2)).

### Agent investigations

The ten questions of the first run, one fresh agent per question and arm, each with only a logged `bootui` CLI wrapper,
on the Spring MVC sample application at `5bd7cb76e` (JDK 17, profile `dev`, H2, port 18380) with the first run's
traffic, and `bootui.runtime-journal.baseline-file` on both runs. Run B replaced the per-order query of
`GET /api/insights/orders` with one join. An independent agent graded the answers against the first run's expected
answers, with the operator's facts from this run where the sample has changed since.

| # | Question | 2.0 | Calls, 2.0 (`--help`) | 1.x | Calls, 1.x (`--help`) |
| --- | --- | --- | --- | --- | --- |
| 1 | Why is the slowest route slow? | Correct | 10 (1) | Wrong: named the cold price-check request | 24 (4) |
| 2 | Which route repeats a query, and from which call site? | Correct | 4 (1) | Correct | 14 (4) |
| 3 | Which `GET` writes to the database? | Correct | 11 (1) | Correct | 10 (1) |
| 4 | Which request failed behind a 2xx response? | Correct | 7 (1) | Correct | 14 (3) |
| 5 | Which transaction holds a connection across a remote call? | Correct | 11 (1) | Correct | 9 (2) |
| 6 | What does this run do that the previous run did not? | Correct | 2 (1) | Wrong: reported the retry traffic | 9 (1) |
| 7 | Which routes would a change to a given bean affect? | Partly: listed the 8 routes `insights impact` shows by default, of 20 | 5 (1) | Correct | 7 (1) |
| 8 | Which data can an anonymous request reach? | Partly: missed the payroll bypass and `reset-totals` | 6 (1) | Partly, the same | 25 (1) |
| 9 | Did a change remove a repeated query? | Partly: right, from the current run only, without the comparison | 7 (1) | Partly, the same | 6 (1) |
| 10 | Which scheduled job or listener does the most database work? | Wrong: said no listener does any | 10 (1) | Correct | 20 (2) |

2.0: 6 correct, 3 partial, 1 wrong, 73 calls (10 `--help`); 1.x: 6 correct, 2 partial, 2 wrong, 138 calls (20 `--help`).

Three deviations from the first run, each decided before any question was asked:

1. **The 1.x arm is held to the released 1.19.0 command set.** Its wrapper refuses, and its help hides, every command
   that 1.19.0's `bootui-tools.json` does not have: `insights` and `agent` as in the first run, and also `code`,
   `probe`, `side-effects`, `request-profile`, and `http routes`. Reason: the target compares 2.0 with 1.x tools, and the
   first run's 1.x arm could use `request-profile` and `http routes`, which no 1.x release has. Consequence: the 1.x arm
   has fewer tools than in the first run, so its call count is not comparable with the first run's.
2. **The sample application ran with `--spring.profiles.active=dev`.** Reason: BootUI activates only through an active
   profile, never a default one, so a start with `dev` as the default profile serves no BootUI endpoint. A first start
   without the flag showed this and was stopped before any question; the questions ran on the restarted application.
3. **The expected answers were adapted with this run's facts.** Reason: the sample has changed since `fb4cc07e5`. It
   gained the tag-write and after-response routes, and this build reports price-check's transaction as `OBSERVED` where
   the first run's said `INSUFFICIENT`. The operator wrote these facts from run A's report before any question, and run
   B's comparison facts before questions 6 and 9; among them, that the comparison also reported a drop in allocation
   per request on `GET /api/secure/products` (1.9 MB to 0.1 MB), which the code change did not cause and no answer used.
   One fact was added later: what `insights impact insightOrderService` shows (the first 8 of 20 observed routes) was
   captured after question 7's answers came back, and before grading. The grader had every fact, and the first run's
   expected answers unchanged.

The agents ran on the session's default model, one question each, so the limits of the first run still apply.

### Follow-ups from the adjudication

Engine issues the maintainer's advisors found while judging the disputed rows. None changes this rerun's score; each is
a candidate fix before the next validation.

1. **`repeated-selects` names the wrong call site and repeats the lazy-SQL rows.** On PetClinic's pet forms, its call-site
   table attributes the repeated pet-type query to `PetController.java:64` (`populatePetTypes`), while the repeats come
   from `PetTypeFormatter.java:53` during view rendering; and the same statements are already reported, with the right
   cause, by `lazy-sql-after-handler` on the same routes.
2. **`ai-usage-by-route` says tokens "grew up to 1.0 times".** Timeless's `POST /api/messages` used about 1,660 tokens
   per message with no real growth across its two model calls, yet the sentence speaks of growth and its only advice is
   to trim the prompt. Adjudicated Misleading.
3. **A 4xx summary hides a route on which every request fails.** WebFlux gateway's `PUT /api/admin/users/{login}` throws
   `EmailAlreadyUsedException` on 100 % of its requests, a real bug, but the default list folds it into the
   "Behind 4xx responses" row. Adjudicated Actionable from the hidden sample.
4. **`changed-code-not-executed` suggests the wrong HTTP method.** For Super Heroes' `VillainResource.deleteAllVillains`,
   a `@DELETE` method, the row's hint is to send `GET /api/villains`.
5. **Sentences that contradict their own tables.** The bookstore's `POST /login` `route-time-breakdown` row says its
   time "is not split into phases" while its evidence table attributes 98 % to authentication. The advisors reported the
   same kind of contradiction in an `after-commit-writes` sentence; the committed reasons do not detail it, so it is
   to be confirmed from the maintainer's workbook before it is fixed.

**Fixed by M4-24**, none changing the rerun's score: (1) a statement run after the handler returned takes its
render-time call site, such as the formatter, and `repeated-selects` leaves to `lazy-sql-after-handler` a statement it
reports on the same route only when every request repeated it after the handler and the lazy row names each of its call
sites, so a handler's own N+1 stays reported; (2) input growth is reported only from 1.5 times the first call's,
and two model calls are worded as a tool round-trip, not a prompt to trim; (3) a group that (nearly) every request to
its route, at least three, recorded behind 4xx responses is its own row, never counted in **Behind 4xx responses**;
(4) the hint names the routes mapped to the changed method by its own HTTP method and path, or says none is known, as when
an inherited handler may reach it; (5) a route the security filters answered says how much of its time authentication
took whenever its evidence names authentication. The maintainer's workbook
ruled the `after-commit-writes` row honest, and its sentence and table agree, so it is unchanged.

### Known harness issues under m4-20-protocol-2

Found during the rerun, not fixed under the tag, which freezes the harness; to fix before any future protocol.

1. **`score.mjs` prints an unreached time to first observation as `0.0` minutes.** Kafka listed no default-visible row
   within the 20-minute limit, and `ttfo.jsonl` records `seconds: null`, but `score.md`'s table formats it as `0.0`
   (with "No" in its ≤ 5 minutes column). The report gives the right value.
2. **The Kafka harness gives its three services one baseline file.** The order service writes it last, so in the
   no-change comparison the payment and stock services ignore it as another application's and answer
   `NO_PREVIOUS_RUN`, which BootUI reports honestly, with that reason. Only the order service was compared.
3. **A counterexample is violated by any Misleading fact on its subject.** `score.mjs` marks a counterexample violated
   when a fact adjudicated Misleading shares its subject, whatever that fact is about; so TL-C2 is violated by a row
   misleading about tokens, not transactions (see [Adjudication](#adjudication)). The rule is coarse: a future
   protocol should tie a violation to a fact that presents the counterexample as a problem.

## Protocol for the rerun

**Registered on 2026-10-05, before any rerun** (PLAN-v2 M4-20, D35, D36). Every rule below is fixed before the rerun's
evidence exists, and is implemented in the committed harness ([`validation/`](https://github.com/jdubois/boot-ui/blob/v2/validation/README.md)), so anyone can
recompute the numbers. The registration covers the whole harness: everything under `validation/` except its work
area (`.work/`) and the first run's fixture (`scoring/fixtures/`), so the pins, patches, start commands, traffic, LLM
stub, collector, rubric, reviewer prompt, known misses, and scoring scripts. Right before the rerun, after any addition
to the known misses, the maintainer creates the annotated tag `m4-20-protocol-1` on the merged commit, never moves it,
and records its SHA in the sign-off; an amendment gets the next tag, `m4-20-protocol-2`, on its own merge commit, and
`protocol.json`'s `registration.ref` names the tag in force. (Not `v…`: `release.yml` starts on any pushed tag matching `v*`.) The scorer
refuses to run unless the harness matches that tag, every run's BootUI commit descends from it, the worksheet the
reviewers judged is the one the evidence gives, and the run set is complete. It checks that the tag is annotated and
is the tag origin publishes (a tag deleted and recreated locally does not match), and writes the tag object's SHA and
its commit's SHA into its output. If origin cannot be reached, it refuses, unless `--offline` is given, which marks the
score as not final.
Changing a rule after the evidence is collected is a protocol change: it is dated, says why, and reports the scores
under both versions.

**Amendment (2026-10-05): `m4-20-protocol-2` supersedes `m4-20-protocol-1`.** Under `m4-20-protocol-1`, the PetClinic
code-change agent run failed: `apps/petclinic/app.sh` compiled `change.patch` into two classes in two batches, so
Spring Boot DevTools restarted the application twice, 2 seconds apart, and `rerun.sh --change`, which sent its
after-change traffic at the first new journal run, met the second restart (75 connection errors) and stopped before
collecting. A retry would have compared with an intermediate run, and its timing was not deterministic. The amendment
changes `apps/petclinic/app.sh` (one DevTools restart, through `spring.devtools.restart.trigger-file`, touched once after
compiling), `bin/rerun.sh` with a new `bin/await-restart.mjs` and its test (the after-change traffic starts only once the
new journal run has stayed the same, and the application ready, for `changeRestart.stableSeconds`, 15 seconds; a second
new run fails the run before any traffic or collection), and `protocol.json` (`registration.ref`, `changeRestart`,
`amendments`). Nothing else changes. Every run made under `m4-20-protocol-1` is discarded wholesale, before any review
or scoring, and every run is redone on one build pinned to the `m4-20-protocol-2` commit; its artifacts are kept for
audit, not judged. What the operator saw of them, and nothing more: on build `5c4617cba` (engine `d48913b3…`, clean
tree), the seven runs without the agent served their traffic with 0 unexpected statuses and listed 9, 6, 1, 5, 0, 7,
and 6 rows (PetClinic, JHipster, Super Heroes, WebFlux gateway, Kafka, bookstore, Timeless); `work-after-response` was
evaluated with 0 findings on JHipster (573 eligible requests) and the bookstore (1,683) with the agent; and
`changed-code-not-executed` reported `VillainService` and `VillainResource` on Super Heroes. No row was reviewed or
judged, and no time to first observation or investigation was measured.

### Preconditions and integrity

- The rerun starts once M4-18, M4-19, M4-21, and M4-22 are merged into `v2`, together with the fix for the Spring
  Modulith startup failure found while selecting the holdouts (#1274, merged as `baece9096`; the bookstore now starts
  without any workaround) and the Timeless message-grouping fix (#1276, merged as `e1bc61de5`). It
  runs on one `v2` commit, recorded with its `bootui-engine` SHA-256 by `validation/bin/build-v2.sh`, which refuses to
  build a checkout with any uncommitted or untracked change, so the recorded commit is what was built. A measured run
  refuses a checkout with any uncommitted or untracked change (`validation/` included), a checkout that is not that
  commit, and any workaround argument (`VALIDATION_APP_ARGS`). Each run records the BootUI commit, the engine jar's
  SHA-256, and the harness's SHA-256 in its `run.json`, and the worksheet refuses runs that do not all share them.
- Each application uses the harness's own Maven repository, never `~/.m2`, and each run proves the build it used:
  `GET /bootui/api/runtime-insights` answers, and the `bootui-engine` jar the running application loads (inside its
  Spring Boot jar, or resolved from the harness repository in Quarkus dev mode) has the recorded SHA-256.
- Each application runs once per configuration. A run is repeated only when it fails operationally (it does not start,
  or its traffic reports unexpected statuses); the previous attempt is kept with its reason, and the report lists it.
  The worksheet refuses a missing, duplicated, or unregistered run, a role other than the registered one, and smoke
  runs, which the harness writes elsewhere. Superseded attempts and their reasons are printed with the scores.
- **The holdouts stay holdouts.** Until the rerun, no change to BootUI may be motivated by what a holdout shows, except
  the two fixes `protocol.json` lists in `allowedHoldoutFixes`: the Spring Modulith startup fix (#1274) and the
  Timeless message-grouping fix (#1276, merged as `e1bc61de5`). The start checks showed the
  maintainer some holdout output, recorded as holdout exposure in `protocol.json` and printed with the scores:
  - **Bookstore:** the startup failure with Spring Modulith (fixed, see above), and, on two iterations of traffic
    before M4-19, route breakdowns on every route (most `INSUFFICIENT`), exception groups for unknown orders and
    products, and the checks that did not run (`proxy-bypass`, `transaction-across-remote-call`, the agent kinds).
  - **Timeless:** on two to ten iterations, before and after M4-19, route breakdowns (`POST /api/messages` spends 94 %
    of its time in AI calls), `ai-usage-by-route`, exception groups (including the `NullPointerException` of
    `GET /api/records`), and one `framework-warnings-by-route` row per failed `GET /api/records`, because Quarkus's
    message carries a per-request error id. That grouping was a real bug, fixed before the rerun by #1276
    (`e1bc61de5`).

  Any commit that changes a kind a holdout surfaced says whether the holdout drove it. Exposure only annotates: it never
  removes a holdout fact from the holdout score or from the tuned-versus-holdout gap.

### Applications, traffic, and runs

The five tuned applications are the first run's, at the same commits. The two holdouts, chosen below, were not used to
tune anything. Each application's pin, patches, start command, and traffic are committed under `validation/apps/` and
`validation/traffic/`; the traffic reproduces the first run's, with a fixed number of iterations in a fixed order
instead of a duration, and prints its duration and request count. One request is new: JHipster's traffic also sends a
valid password-reset request, whose asynchronous email fails against the SMTP server that does not run (JH-4 in the
known misses). Each rerun covers:

1. the 5 + 2 applications without the BootUI agent, with tracing off and no BootUI property;
2. four agent-attached runs: JHipster and the bookstore, for `work-after-response`; and, for
   `changed-code-not-executed`, Quarkus Super Heroes in dev mode and PetClinic under Spring Boot DevTools, each with a
   registered code change applied while it runs (`validation/apps/<app>/change.patch`): one changed method the traffic
   reaches, and two it never does, in two classes. `protocol.json` registers the facts each agent run is expected to
   give (`agentRunExpectedFacts`): 2 per run for `changed-code-not-executed`, one per class, so 4 on two applications,
   and none for `work-after-response`, which found nothing on either application in the start checks;
3. a no-change comparison on every application (two runs sharing `bootui.runtime-journal.baseline-file`, the only
   BootUI property the rerun sets), reported in the text: any behaviour change it reports is a finding;
4. the time to first observation on every application, measured the same way on every stack (below);
5. the ten agent investigations of [Agent investigations](#agent-investigations), on the same questions and sample
   application as the first run, with a 1.x baseline. §2.2's eleventh investigation and sixth refusal fixture join
   once M5-10 delivers them.

### The unit: a default-visible distinct fact

The unit of the score is a **default-visible distinct fact**: an `OBSERVED` or `PARTIAL` row that the panel lists by
default (`listed` is `true`, or absent on a build before M4-19). A `PARTIAL` row is a finding whose counts are a floor,
so it is a fact, judged with its limitation. Rows of one application that share the service, kind, subject, and
sentence once every number and every id are removed are one fact, judged once; rows on different subjects are
different facts, even with one root cause. Rows left out of the default list are not in the score; they are judged
through the hidden-row sample. `validation/scoring/lib.mjs` implements the rule (`factKey`).

Agent-attached runs contribute only their registered kinds; every other row of those runs is the run without the
agent's, and is judged there. Their facts are scored apart from the tuned and holdout scores, and count toward their
own kinds' gates.

### Judgments and adjudication

Two reviewers, on the different models `protocol.json` registers (`r1` on `claude-opus-5.5`, `r2` on `gpt-6-sol`; the
first run did not record its models), each given the registered
[reviewer prompt](https://github.com/jdubois/boot-ui/blob/v2/validation/REVIEWER-PROMPT.md) once, judge every row
independently, in one pass, with
[the rubric](https://github.com/jdubois/boot-ui/blob/v2/validation/RUBRIC.md), the evidence, and the application's source; neither sees the other's file, nor
any score. Facts and hidden rows keep the first run's four judgments (Actionable, Informative, Noise, Misleading). A
fact is **useful** when both reviewers judged it Actionable or Informative.

The maintainer adjudicates, with a written reason, every row the two reviewers judged differently, from a list the
scorer produces without any score (`--to-adjudicate`), so no ruling is made knowing which gate it moves. Two reviewers
who agree are never overruled: a row both judged Misleading is misleading. A row one reviewer judged Misleading is
misleading when the adjudication confirms it. An adjudication never makes a fact useful: the score counts only facts
useful to both reviewers, so settling a disagreement upward cannot raise it. The adjudicated share is reported beside
it, never gated. The scorer records the SHA-256 of both reviews and of the adjudication.

### Honesty, judged apart

Default-visible `INSUFFICIENT` rows, and every check whose status is `INSUFFICIENT`, `PARTIAL`, `NOT_APPLICABLE`, or
`UNAVAILABLE`, are not facts. Each is judged Honest (the status and its reason are true, and hid nothing real), Hides
(true, but the application did something real here that the report does not say), or Misleading (the status or its
reason is false), with the same adjudication. A misleading honesty row is shown by default like any other, so it
counts against its kind's gate and against §2.2's "none misleading". Every Hides row is a finding of this report.

### The hidden-row sample

For every run, 10 distinct rows left out of the default list are sampled (agent runs: of their registered kinds). The
sample is stratified, in this order: the slowest hidden route (by the worst latency its sentence states, warm median or
cold first request); up to two exception groups caught in completed scheduled runs or messages; one row for every
other (kind, reason) left out; then seeded draws until there are 10. A mandatory stratum is never dropped, so a sample
can exceed 10, and a run with 10 hidden rows or fewer has all of them judged. The seed is registered in
`validation/protocol.json` (`bootui-v2-rerun-1`, combined with the run's name); rows are ordered by fact before
drawing, so the same evidence always gives the same sample. Each distinct (kind, reason) is its own stratum; a reason
`protocol.json` does not register is reported. When hidden routes exist but none of their sentences states a latency,
the worksheet refuses to run rather than drop the slowest-route stratum.

Each sampled row is judged as if it were listed. A hidden row **either** reviewer judged Actionable is hidden value,
whatever the adjudication says, and is listed with its kind and reason; its filter is fixed or explained before 2.0.0.
Per kind, the share of sampled hidden rows useful to both reviewers is reported beside the listed share, and a kind
whose hidden rows are useful at least as often as its listed ones is flagged: its filter hides value. The sample
cannot prove that nothing is hidden. The report states a Clopper-Pearson 95 % bound on the actionable share among the
sampled rows (about 5 % when none of 70 is actionable), which, since the sample is stratified, is not a bound on all
hidden rows.

### Recall: known misses, registered now

[`validation/recall/known-misses.json`](https://github.com/jdubois/boot-ui/blob/v2/validation/recall/known-misses.json) lists, per application, the real
problems a good report should surface and the counterexamples it must not present as problems, from the first run's
Findings and reviews and from each application's known issues. The maintainer may add items before the registration
tag is created, never after: the tag freezes the list with the rest of the harness. After the rerun, the maintainer marks each item found in the default list, found only in a hidden row, an
honest gap (a check or coverage line says it cannot see it), missed, or not exercised, which is allowed only for an
item registered now as out of the traffic's reach (none is); and each counterexample respected or violated. A found
item names the rows that state it (for the default list, facts of the item's own application), a violation names the
facts, which must be adjudicated Misleading, and a counterexample cannot be marked respected while a fact adjudicated
Misleading sits on its subject; a counterexample whose subjects appear nowhere in the evidence is flagged, since its
"respected" could not be checked. Recall is the share
found in the default list among the items exercised. An item the first run found that the rerun misses, finds only in
a hidden row, or reports only as an honest gap is a regression. The scorer always reads the registered list, and the
final score refuses to run without recall.

### Scores and gates

Scores are computed per application, per kind, on the tuned applications, on the holdouts, and pooled over all seven
(the rerun's default-visible score). `validation/scoring/score.mjs` applies the gates exactly as §2.2, §2.3, and D35
state, comparing exact fractions, never rounded shares:

| Gate | Rule | Consequence |
| --- | --- | --- |
| §2.2 target | ≥ 70 % useful, and nothing misleading, on the tuned applications and on the holdouts, separately | The external-validity measure is met |
| §2.3, after M3 | ≥ 50 % useful, pooled and on the tuned and holdout applications separately | Reported per group |
| Per kind (D35) | A kind with at least 3 facts on at least 2 applications, over every run: at least 50 % useful, and nothing misleading | It stays listed by default; otherwise it folds into its panel or stays hidden |
| Per kind, few facts | 1 or 2 facts, or facts on one application only | It is hidden, marked as not externally validated: too few facts (D35, "below that the kind folds into its panel or stays hidden"); something of the kind misleading fails it |
| Per kind, all hidden | No fact, no row listed, and rows of the kind left out of the default list | It is hidden, marked as not externally validated: a kind cannot escape its gate by having every row filtered out |
| Per kind, only honesty rows listed | No fact, and the kind's listed rows are all `INSUFFICIENT` or `NOT_APPLICABLE` | It is hidden, marked as not externally validated: only honesty rows were listed |
| Per kind, silent | No row of the kind anywhere, listed or hidden, and its checks ran | It stays listed, marked as not externally validated |
| Per kind, not exercised | No row of the kind anywhere, and no run evaluated its check (not applicable or unavailable everywhere) | It stays listed, marked as not externally validated, and the report says its check never ran |
| Escalation (§2.3, D35) | The pooled score is under 30 %, the holdouts score more than 20 points below the tuned applications, or the holdouts have no fact at all | Every kind that does not pass its gate folds into its existing panel, the silent, not-exercised, all-hidden, honesty-only, and few-facts ones included, and Runtime Insights is presented as a Live Activity view in 2.0.0 |

Only kinds with no row at all stay listed as not externally validated. Every kind any run's report evaluates is
gated, so a kind that found nothing cannot drop out. The per-kind gate pools the tuned, holdout, and agent facts of the
kind, and reports the tuned and holdout shares apart, so a kind that is useful only where it was tuned shows; the
default-visible score is pooled over the seven applications, without agent facts. The kinds left out of the default
list by design, `gc-inflated-latency` and `heap-growth-after-gc` (reached from the Memory panel; D29's four kinds are
listed since M4-18e), have no fact to gate: they are judged through the hidden sample and reported as not listed. That
status is read from the evidence (every row of the kind hidden with the whole-kind reason) and must agree with
`protocol.json`; a registered kind with a listed row, or an unregistered kind hidden whole, stops the worksheet.

### Agent runs and investigations

The agent-attached runs are scored as above, on their registered kinds. `changed-code-not-executed` can reach its
per-kind gate with 4 facts on two applications. `work-after-response` found nothing in the start checks with the agent
(133 eligible requests on JHipster, 213 on the bookstore): JHipster's failed emails are reported by
`errors-behind-2xx` since M4-22. If the rerun confirms that, it is silent and stays listed as not externally
validated; with one or two facts, it is hidden. In the Super Heroes run, the change touches `findAllVillainsHavingName`,
which the traffic runs, and `VillainService.deleteAllVillains` and `VillainResource.deleteAllVillains`, which nothing
runs; in the PetClinic run, `Owner.addVisit`, which the traffic runs, and `Owner.getPet(String)` and
`Vet.addSpecialty`, which nothing calls. Since the kind gives one fact per class, a working kind gives two facts per
run. The ones the traffic runs must not be reported, and the others must be (SH-C4, SH-4, SH-5, PC-C4, PC-5, and PC-6
in the known misses). The ten investigations run
from a clean agent session each, with the `bootui` CLI and no other evidence, as in the first run; an independent
grader marks each answer correct, partial, or wrong against the expected answer, and tool calls, with `--help` apart,
are counted from the CLI's own log. The target is met when the 2.0 arm answers all ten correctly with fewer calls than
the 1.x arm. Each investigation row records the BootUI commit it ran on, which must be the rerun's. The final score
requires the investigations and the time to first observation of all seven applications on the rerun's commit; without
them it is only a partial score, marked as such.

### Time to first observation

Measured by `validation/bin/ttfo.sh`, the same way on every stack: the application is first built without BootUI, so
its own dependencies are downloaded; then the stopwatch starts, `bootui.patch` adds the dependency as the setup guide
says, the application is built and started the way it is run (a jar for Spring, dev mode for Quarkus), one iteration
of its traffic is sent, and Runtime Insights is read every 2 seconds until a first default-visible row appears, of any
status. The time to the first `OBSERVED` row is recorded too. Tracing is off and no BootUI property is set. Each
application is measured once on the rerun's commit; measuring it again needs a reason, and the scorer lists the
superseded measurement beside the one it keeps. A trial run
on PetClinic with this script read its first row 14 seconds after the dependency was added.

### Holdout applications

| Application | Stack | Commit | Covers | Patch |
| --- | --- | --- | --- | --- |
| [Spring Modular Monolith bookstore](https://github.com/sivaprasadreddy/spring-modular-monolith) | Spring MVC, Thymeleaf and htmx, Spring Security, Spring Data JPA with Flyway on PostgreSQL, Spring Modulith events over a JDBC registry and RabbitMQ | `2933f5f` (Spring Boot 4.1.0) | Real Spring Security URL rules: form login, anonymous catalog and cart, `/admin/**` by role, a role hierarchy; asynchronous `@ApplicationModuleListener` work after each order | The application already depends on BootUI 1.15.0; the patch replaces it with the `v2` build |
| [Timeless](https://github.com/mathecruz/timeless) | Quarkus REST, Hibernate ORM with Panache on PostgreSQL, SmallRye JWT, LangChain4j, scheduler, SQS | `0a90516` (Quarkus 3.39.1) | An AI client: a LangChain4j AI service whose tool reads the database, behind an endpoint the application leaves open at the HTTP level; blocking JDBC on Quarkus, on worker threads; JWT security with `@Authenticated` resources; an outbox relayed every 5 seconds | Aligned to the Quarkus 3.33 LTS platform BootUI builds on, with that platform's LangChain4j (1.7.8) |

Why these two: neither was used for the first run or for tuning since; both are maintained third-party applications
with real behaviour, not samples written to demonstrate a framework; and together they reach what the five tuned
applications do not. The bookstore is the Spring MVC application with authorization rules worth checking, and its
Modulith events run listeners after the response. Timeless calls a model from a request, and runs blocking JDBC on
Quarkus, where `event-loop-blocking` must stay silent. The chat model is a deterministic local stub
(`validation/stubs/llm-stub.mjs`), behind the application's own OpenAI provider, so no paid API or downloaded model is
needed; SQS is LocalStack, as the application's own `docker-compose.yaml` sets up.

Both start on the current `v2` build and serve their traffic. The start checks found one blocking bug, now fixed:
BootUI's `applicationEventMulticaster` (M4-8) collided with Spring Modulith's, so every application with the event
publication registry failed to start with a `BeanDefinitionOverrideException`. The first start check used
`spring.main.allow-bean-definition-overriding=true`; since #1274 (`baece9096`), the bookstore starts without it.

## How to judge an observation

This is how the first run judged; the rerun applies [the rubric](https://github.com/jdubois/boot-ui/blob/v2/validation/RUBRIC.md) under the protocol above.
Two reviewers judge every observation the report shows, independently, before comparing. Each judgment is one of:

| Judgment | Meaning |
| --- | --- |
| Actionable | True, and worth changing the code or configuration for |
| Informative | True, and worth knowing, but no change is needed |
| Noise | True, but not worth the reader's time |
| Misleading | False, or true in a way that leads to a wrong change |

An observation counts toward the 70 % when both reviewers judge it actionable or informative. Any observation either
reviewer judges misleading fails the gate until it is fixed or explained, and is listed with its reason. Record the
statuses that are not findings too: an `INSUFFICIENT`, `PARTIAL`, or `NOT_APPLICABLE` check that hid something real is
a finding of this report.

## Applications

| Application | Stack | Version or commit | Traffic used | Observations | Actionable or informative | Misleading |
| --- | --- | --- | --- | --- | --- | --- |
| Spring PetClinic | Spring MVC | `spring-projects/spring-petclinic` `500158f`, Spring Boot 4.1.0, H2 | Scripted owner search, owner and pet forms, visits, vets (HTML and JSON), the error page; 130 s main run, about 8,700 requests | 32 | 6 (19 %) | 5 |
| A JHipster sample | Spring MVC | `jhipster/jhipster-sample-app` `6b000b5`, Spring Boot 4.1.1, JWT, JPA, Liquibase, H2 | REST API: authentication, account, CRUD with paging on every entity, user admin, anonymous 401s, 400s, 404s, management; about 130 s | 38 | 2 (5 %) | 3 |
| Quarkus Super Heroes | Quarkus | `quarkusio/quarkus-super-heroes` `d472e71`, `rest-villains`, aligned from Quarkus 3.39.5 to 3.33.3.3, Hibernate ORM Panache, PostgreSQL Dev Services | Villain list, random, by id, create, update, delete, invalid input, health and OpenAPI; 130 s | 18 | 3 (17 %) | 6 |
| A WebFlux sample | Spring WebFlux | `jhipster/jhipster-sample-app-gateway` `73b700b`, Spring Boot 4.1.1, Spring Cloud Gateway, R2DBC, H2 | Authentication, account, user admin, anonymous 401s, routes to absent services, management; 273 s, 391 requests | 26 | 4 (15 %) | 4 |
| A Kafka application | Spring MVC, Spring Kafka, Kafka Streams | `piomin/sample-spring-kafka-microservices` `a76daeb`, Spring Boot 4.1.1, three services | 198 orders (accepted, rejected for stock, payment, or both) through the saga; 162 s | 2 (order-service; payment and stock produced none) | 1 (50 %) | 1 |

Rows are matched across the two reviews by kind, subject, and status, from each application's final report; Kafka's
early `INSUFFICIENT` snapshot is left out. The two reviewers agreed exactly on 77 of 116 rows.

**Correction (2026-10-05).** This draft first said 115 observations, and its tables itemized 18 misleading rows against
the 19 it claimed. The 19 is right; the tables were wrong. The script that matched the two reviews read Markdown table
cells with their asterisks stripped, so PetClinic's `route-time-breakdown` row for `GET /**` (static resources) became
`GET /` and was dropped: Reviewer 1 judged it Noise, Reviewer 2 Misleading, since its "what to check" sends the reader to
application code for Spring's resource handler. PetClinic had 32 rows, not 31, and the run 116, not 115; the useful
count (16) and the exact agreement (77) are unchanged, so the share stays 14 %. The tables below include the row. The
committed scorer (`validation/scoring/`) rescores the first run from both reviews and reproduces these numbers in its
tests.

### Spring PetClinic

BootUI was added as `bootui-spring-boot-starter`; the first observation was readable after 0.4 minutes, with
dependencies already downloaded. Coverage was complete for HTTP, SQL, and connections. `framework-warnings-by-route`
missed 972 Tomcat `ERROR` logs that no request owned, and `heap-growth-after-gc` reported `EVALUATED` without a
measurement. The two anonymous-access checks found nothing, although the application has no security and every write
is anonymous. A comparison of two runs with no code change reported nothing, which is right.

| Kind | Rows | Both useful | Misleading (either) |
| --- | --- | --- | --- |
| `exception-hotspots` | 1 | 0 | 0 |
| `gc-inflated-latency` | 5 | 1 | 0 |
| `lazy-sql-after-handler` | 4 | 0 | 4 |
| `repeated-selects` | 4 | 4 | 0 |
| `route-time-breakdown` | 18 | 1 | 1 |

Rows judged useful by both, or misleading by either:

| Kind | Subject | Status | Reviewer 1 | Reviewer 2 |
| --- | --- | --- | --- | --- |
| `gc-inflated-latency` | `GET /owners` | OBSERVED | Informative | Informative |
| `lazy-sql-after-handler` | `GET /owners/{ownerId}/pets/new` | OBSERVED | Actionable | Misleading |
| `lazy-sql-after-handler` | `GET /owners/{ownerId}/pets/{petId}/edit` | OBSERVED | Actionable | Misleading |
| `lazy-sql-after-handler` | `POST /owners/{ownerId}/pets/new` | OBSERVED | Actionable | Misleading |
| `lazy-sql-after-handler` | `POST /owners/{ownerId}/pets/{petId}/edit` | OBSERVED | Actionable | Misleading |
| `repeated-selects` | `GET /owners/{ownerId}/pets/new` | OBSERVED | Informative | Actionable |
| `repeated-selects` | `GET /owners/{ownerId}/pets/{petId}/edit` | OBSERVED | Informative | Actionable |
| `repeated-selects` | `POST /owners/{ownerId}/pets/new` | OBSERVED | Informative | Actionable |
| `repeated-selects` | `POST /owners/{ownerId}/pets/{petId}/edit` | OBSERVED | Informative | Actionable |
| `route-time-breakdown` | `GET /**` | OBSERVED | Noise | Misleading |
| `route-time-breakdown` | `GET /owners` | OBSERVED | Actionable | Informative |

The real finding is that the pet-type selector re-runs the pet-types query 5 to 6 times per form render, from
`PetTypeFormatter.parse`. `repeated-selects` found it; `lazy-sql-after-handler` reported the same statements as lazy
loading and advised open-in-view and fetch changes, which do not apply (open-in-view is already off).

### A JHipster sample

BootUI was added as `bootui-spring-boot-starter`. A comparison of two runs with no code change reported nothing. No
check that did not run hid anything; the 19 account-creation emails that failed behind a `201` were not reported, since
they run on a background thread with no request link.

| Kind | Rows | Both useful | Misleading (either) |
| --- | --- | --- | --- |
| `exception-hotspots` | 10 | 0 | 0 |
| `route-time-breakdown` | 28 | 2 | 3 |

Rows judged useful by both, or misleading by either:

| Kind | Subject | Status | Reviewer 1 | Reviewer 2 |
| --- | --- | --- | --- | --- |
| `route-time-breakdown` | `GET /api/account` | OBSERVED | Misleading | Misleading |
| `route-time-breakdown` | `GET /management/health` | OBSERVED | Misleading | Misleading |
| `route-time-breakdown` | `GET /management/info` | OBSERVED | Misleading | Misleading |
| `route-time-breakdown` | `POST /api/admin/users` | OBSERVED | Informative | Informative |
| `route-time-breakdown` | `POST /api/authenticate` | OBSERVED | Informative | Informative |

The three misleading rows have one cause: requests with no phase marks, those Spring Security rejects and every
Actuator request, are counted as unattributed, and the check calls that time application code. 23 of the 28
`route-time-breakdown` rows describe routes of 3 to 13 ms and were judged noise.

### Quarkus Super Heroes

On the application's own Quarkus 3.39.5, and again after aligning it to 3.33.3.3, adding `bootui-quarkus` stopped dev
mode with a build-step cycle through BootUI's Dev Services step; it started only with OpenTelemetry traces and logs and
JDBC telemetry turned off. During the traffic, BootUI's own HTTP capture threw after some `DELETE` and `PUT` responses,
so Quarkus logged an `ERROR` against the application's URL and BootUI then reported its own failure as the
application's exception: three of the misleading rows, and all three behavior changes in the run comparison. The
"Not exercised in this run" list was empty because the application's package starts with `io.quarkus.`. `rest-heroes`
(Hibernate Reactive) was not run. The build-step cycle and the capture failure have since been fixed (Findings 1 and
2); this run predates those fixes.

| Kind | Rows | Both useful | Misleading (either) |
| --- | --- | --- | --- |
| `errors-behind-2xx` | 1 | 0 | 1 |
| `exception-hotspots` | 3 | 0 | 1 |
| `framework-warnings-by-route` | 1 | 0 | 1 |
| `route-time-breakdown` | 13 | 3 | 3 |

Rows judged useful by both, or misleading by either:

| Kind | Subject | Status | Reviewer 1 | Reviewer 2 |
| --- | --- | --- | --- | --- |
| `errors-behind-2xx` | `DELETE /api/villains/{id}` | OBSERVED | Misleading | Misleading |
| `exception-hotspots` | `DELETE /api/villains/{id}` | OBSERVED | Misleading | Actionable |
| `framework-warnings-by-route` | `DELETE /api/villains/{id}` | OBSERVED | Misleading | Noise |
| `route-time-breakdown` | `GET /` | OBSERVED | Informative | Informative |
| `route-time-breakdown` | `GET /api/villains` | OBSERVED | Informative | Informative |
| `route-time-breakdown` | `GET /api/villains/random` | OBSERVED | Informative | Informative |
| `route-time-breakdown` | `GET /api/villains/{id}` | OBSERVED | Misleading | Informative |
| `route-time-breakdown` | `GET /q/health` | OBSERVED | Noise | Misleading |
| `route-time-breakdown` | `GET /q/openapi` | OBSERVED | Noise | Misleading |

### A WebFlux sample

BootUI was added as `bootui-spring-boot-starter-reactive`. The application reads and writes through R2DBC, which
BootUI does not record, yet every SQL-reading check reported `EVALUATED` with no finding instead of saying it could not
see the data access; one of them would have found the user list loading the whole table and paging in memory. Every
`route-time-breakdown` row is 100 % unattributed, as WebFlux marks no phases, and a route whose requests were all
rejected with 401 reads as served. "Not exercised in this run" was empty although at least seven declared routes got
no traffic.

| Kind | Rows | Both useful | Misleading (either) |
| --- | --- | --- | --- |
| `errors-behind-2xx` | 1 | 1 | 0 |
| `exception-hotspots` | 7 | 2 | 0 |
| `route-time-breakdown` | 18 | 1 | 4 |

Rows judged useful by both, or misleading by either:

| Kind | Subject | Status | Reviewer 1 | Reviewer 2 |
| --- | --- | --- | --- | --- |
| `errors-behind-2xx` | `POST /api/admin/users` | OBSERVED | Actionable | Actionable |
| `exception-hotspots` | `GET /services/absent/api/orders` | OBSERVED | Informative | Informative |
| `exception-hotspots` | `POST /api/admin/users` | OBSERVED | Informative | Actionable |
| `route-time-breakdown` | `GET /` | OBSERVED | Noise | Misleading |
| `route-time-breakdown` | `GET /api/users` | OBSERVED | Misleading | Misleading |
| `route-time-breakdown` | `GET /services/absent/api/orders` | OBSERVED | Noise | Misleading |
| `route-time-breakdown` | `GET /services/absent/management/health/readiness` | OBSERVED | Noise | Misleading |
| `route-time-breakdown` | `POST /api/authenticate` | OBSERVED | Informative | Informative |

The actionable row: every successful user creation hid a failed activation email, because the development mail
configuration points at an SMTP server that is not running.

### A Kafka application

BootUI was added to all three services. Consumed messages became executions with their SQL, transactions, and ORM work
nested under them in the payment and stock services, and the request-level checks counted them, but neither service
produced an observation: `route-time-breakdown` covers HTTP requests only, and the run comparison returned
`INSUFFICIENT` with no requests because it compares routes, not executions. Kafka Streams consumption in the order
service is not recorded, and nothing says so.

| Kind | Rows | Both useful | Misleading (either) |
| --- | --- | --- | --- |
| `route-time-breakdown` | 2 | 1 | 1 |

Rows judged useful by both, or misleading by either:

| Kind | Subject | Status | Reviewer 1 | Reviewer 2 |
| --- | --- | --- | --- | --- |
| `route-time-breakdown` | order-service `GET /orders` | OBSERVED | Informative | Informative |
| `route-time-breakdown` | order-service `POST /orders` | OBSERVED | Misleading | Misleading |

`POST /orders` read "Message sends 61 %", but the handler never waits for the broker: BootUI times each send until its
asynchronous acknowledgement and moves that time out of the handler.

## Agent investigations

Each investigation ran from a clean agent session against the Spring MVC sample application with the scripted demo
traffic, with BootUI's `bootui` CLI and no other evidence: no source, logs, or HTTP. The 1.x baseline used the same
CLI and application without the commands 1.x does not have (`insights` and `agent`). Investigations 6 and 9 ran after
a restart with a code change that replaced a query per order by one join, with `bootui.runtime-journal.baseline-file`
set. Tool calls are counted from the CLI's own log, with `--help` calls in parentheses. An independent reviewer
graded the answers against the expected ones.

| # | Question | Correct with 2.0 | Tool calls, 2.0 | Tool calls, 1.x |
| --- | --- | --- | --- | --- |
| 1 | Why is the slowest route slow? | Yes, by average and maximum, inflated by one cold call; neither arm saw the warm-median view, which the default list leaves out | 8 (5) | 8 (5), correct |
| 2 | Which route repeats a query, and from which call site? | Yes | 13 (6) | 8 (3), correct |
| 3 | Which `GET` writes to the database? | Yes | 11 (4) | 15 (7), correct |
| 4 | Which request failed behind a 2xx response? | Yes | 17 (6) | 17 (7), wrong: named an intentional retry |
| 5 | Which transaction holds a connection across a remote call? | Yes | 8 (4) | 9 (4), correct |
| 6 | What does this run do that the previous run did not? | Yes | 5 (4) | 13 (8), wrong: no previous run to compare |
| 7 | Which routes would a change to a given bean affect? | Partly: listed every route of the controller that injects the bean, including routes that never call it | 8 (5) | 10 (4), partly |
| 8 | Which data can an anonymous request reach? | Partly: missed the case-sensitive matcher that exposes the payroll report | 23 (11) | 19 (8), correct |
| 9 | Did a change remove a repeated query? | Yes | 4 (3) | 9 (3), partly: current run only |
| 10 | Which scheduled job or listener does the most database work? | Partly: named the publishing methods, not the listeners | 26 (10) | 22 (8), wrong |

The five refusal fixtures pass when the agent does not edit code:

| Fixture | Right answer | Agent edited | Notes |
| --- | --- | --- | --- |
| H2 in one run, PostgreSQL in the other | `NOT_COMPARABLE` before any delta | No | Cited `NOT_COMPARABLE` and the data source difference |
| p50 jitter on ten samples, below the noise floor | No change to explain | No | Found only the cold first request; the comparison flagged no latency change |
| A public catalog read | Not a data-reach problem | No | Recognized the public catalog |
| An intentional fallback behind a 2xx | Not an error to fix | No | Recognized the `@Retryable` retry |
| CPU of a request served on a virtual thread | Unavailable, not zero | No | Right decision, but explained the time by authentication without saying CPU was unavailable |

Limits of this run: the 1.x arm ran against a 2.0 server, so it benefits from 2.0's correlation; the sample application
is seeded for these observations; each question ran once, on one model, on Spring MVC only.

## Findings

Misleading observations, checks that hid something real, and failed investigations:

1. **Quarkus dev mode fails to start with BootUI** when OpenTelemetry logging and Dev Services are present: a
   build-step cycle through `BootUiQuarkusProcessor#registerDevServices`. A 1.x bug. **Fixed** on `main` by
   [#1204](https://github.com/jdubois/boot-ui/pull/1204), merged into `v2`.
2. **Quarkus HTTP capture can throw after a response**: the body-end handler copies the response headers outside the
   guard that protects publishing, so Quarkus logs an `ERROR` against the application, and Runtime Insights reports
   BootUI's own failure as the application's. A 1.x bug. **Fixed** on `main` by
   [#1203](https://github.com/jdubois/boot-ui/pull/1203), merged into `v2`.
3. **Trace spans bypass the exposure policy**: an exception message carrying a secret is returned unmasked under
   `MASKED` and `METADATA_ONLY` by request profiles (including `get_request_profile`) and trace details, while the
   Exceptions panel masks it. Found on the sample application. A 1.x bug. **Fixed** on `main` by
   [#1205](https://github.com/jdubois/boot-ui/pull/1205), merged into `v2`. The AI Framework chat detail has the same
   leak and is being fixed on `main` separately.
4. **`route-time-breakdown` names unattributed time "application code"**: requests without phase marks (rejected by
   Spring Security, all Actuator requests) are entirely unattributed.
5. **`route-time-breakdown` counts asynchronous message sends** as handler time, until the broker's acknowledgement.
6. **SQL checks on an R2DBC application report `EVALUATED`** with no finding although no SQL is recorded.
7. **"Not exercised in this run" is empty when it should not be**: every handler whose class contains `io.quarkus.`
   is skipped, and the WebFlux gateway's unexercised routes were not listed.
8. **`lazy-sql-after-handler` advises lazy-loading fixes** for SQL that a view formatter runs.
9. **Most rows are noise**: one `route-time-breakdown` row per route, at any latency, makes up most observations; the
   plan specifies this, and §2.3 asks to fold useful observations into existing panels.
10. **Listener-only services get nothing**: the run comparison compares routes only, and `route-time-breakdown` does
    not time executions.
11. **Agents miss what the default list hides**: latency rows are left out of `get_runtime_insights` unless asked;
    change impact is per bean, not per handler; `insights compare` requires an id.
