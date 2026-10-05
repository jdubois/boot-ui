Registered protocol: annotated tag `m4-20-protocol-2` (`5f38a51affaeb11a8c35f9a1b0185d964dd23f7d`) on commit `5bd7cb76eb532f1a72bf3a1ab8b018190c6214a2`.

Reviewers: r1 on claude-opus-5.5, r2 on gpt-6-sol.

## Holdout exposure

What the maintainer saw of each holdout before the rerun. It annotates the holdout scores and never removes a
holdout fact from them or from the gap.

- bookstore (2026-10-05): Start check with 2 traffic iterations, before M4-19: route-time-breakdown rows on every route (most INSUFFICIENT), exception-hotspots on GET /orders/{orderNumber} (OrderNotFoundException) and GET /admin/catalog/products/{code} (ProductNotFoundException), heap-growth-after-gc INSUFFICIENT; proxy-bypass and transaction-across-remote-call INSUFFICIENT, event-loop-blocking, work-after-response, and changed-code-not-executed NOT_APPLICABLE. And the startup failure with Spring Modulith (#1274).
- timeless (2026-10-05): Start checks with 2 to 10 traffic iterations, before and after M4-19: route-time-breakdown rows (POST /api/messages with AI calls at 94 %, POST /api/sign-in at 277 ms), ai-usage-by-route on POST /api/messages, exception-hotspots (UnauthorizedException, ResteasyReactiveViolationException, NullPointerException on GET /api/records, NotFoundException), and framework-warnings-by-route with one row per failed GET /api/records, since Quarkus's message carries a per-request error id. That grouping was fixed before the rerun by #1276 (e1bc61de5).

## Worksheet notes

- bookstore: 31 distinct hidden rows, 10 sampled
- bookstore-comparison: a no-change comparison run, reported in the report's text, not judged row by row
- bookstore+agent: 39 rows of kinds that do not need the agent are judged on bookstore
- jhipster: 38 distinct hidden rows, 10 sampled
- jhipster-comparison: a no-change comparison run, reported in the report's text, not judged row by row
- jhipster+agent: 44 rows of kinds that do not need the agent are judged on jhipster
- kafka: 3 distinct hidden rows, 3 sampled
- kafka-comparison: a no-change comparison run, reported in the report's text, not judged row by row
- petclinic: 30 distinct hidden rows, 10 sampled
- petclinic-comparison: a no-change comparison run, reported in the report's text, not judged row by row
- petclinic+agent: 36 rows of kinds that do not need the agent are judged on petclinic
- super-heroes: 16 distinct hidden rows, 10 sampled
- super-heroes-comparison: a no-change comparison run, reported in the report's text, not judged row by row
- super-heroes+agent: 16 rows of kinds that do not need the agent are judged on super-heroes
- timeless: 14 distinct hidden rows, 10 sampled
- timeless-comparison: a no-change comparison run, reported in the report's text, not judged row by row
- webflux-gateway: 21 distinct hidden rows, 10 sampled
- webflux-gateway-comparison: a no-change comparison run, reported in the report's text, not judged row by row

## Scores

| Group | Facts | Useful to both | Misleading (adjudicated, facts and honesty rows) | Misleading (either reviewer) | ≥ 70 %, none misleading | ≥ 50 % |
| --- | --- | --- | --- | --- | --- | --- |
| tuned | 20 | 7 (35 %) | 0 | 4 | No | No |
| holdout | 11 | 3 (27.3 %) | 2 | 3 | No | No |
| pooled | 31 | 10 (32.3 %) | 2 | 7 | No | No |

Escalation (§2.3): pooled under 30 %: No; tuned minus holdout: 7.7 points (above 20: No); no holdout fact: No. **Not triggered.**

Agent-attached runs (their registered kinds only): 4 facts, 2 useful to both, 0 misleading.

### Per application

| Application | Role | Facts | Useful to both | Misleading |
| --- | --- | --- | --- | --- |
| bookstore | holdout | 5 | 1 (20 %) | 1 |
| jhipster | tuned | 6 | 2 (33.3 %) | 0 |
| kafka | tuned | 0 | 0 (—) | 0 |
| petclinic | tuned | 9 | 4 (44.4 %) | 0 |
| petclinic+agent | agent | 2 | 2 (100 %) | 0 |
| super-heroes | tuned | 1 | 0 (0 %) | 0 |
| super-heroes+agent | agent | 2 | 0 (0 %) | 0 |
| timeless | holdout | 6 | 2 (33.3 %) | 1 |
| webflux-gateway | tuned | 4 | 1 (25 %) | 0 |

### Per kind

| Kind | Facts | Applications | Useful to both | Tuned | Holdout | Misleading | Listed / hidden rows | Hidden sampled, useful | Gate | Outcome |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `after-commit-writes` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `ai-usage-by-route` | 1 | 1 | 0 (0 %) | 0/0 | 0/1 | 1 | 1 / 0 | 0, 0 | FAIL | folds into its panel or stays hidden |
| `anonymous-data-reach` | 1 | 1 | 0 (0 %) | 0/1 | 0/0 | 0 | 1 / 0 | 0, 0 | UNDER_SAMPLED | hidden, not externally validated: too few facts |
| `anonymous-success-on-restricted-route` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `changed-code-not-executed` | 4 | 2 | 2 (50 %) | 0/0 | 0/0 | 0 | 4 / 0 | 0, 0 | PASS | stays listed by default |
| `connections-per-request` | 1 | 1 | 0 (0 %) | 0/0 | 0/1 | 1 | 1 / 0 | 0, 0 | FAIL | folds into its panel or stays hidden |
| `errors-behind-2xx` | 3 | 2 | 3 (100 %) | 3/3 | 0/0 | 0 | 3 / 0 | 0, 0 | PASS | stays listed by default |
| `event-loop-blocking` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `exception-hotspots` | 9 | 6 | 1 (11.1 %) | 0/6 | 1/3 | 0 | 9 / 26 | 11, 0 | FAIL | folds into its panel or stays hidden |
| `framework-warnings-by-route` | 1 | 1 | 0 (0 %) | 0/0 | 0/1 | 0 | 1 / 0 | 0, 0 | UNDER_SAMPLED | hidden, not externally validated: too few facts |
| `gc-inflated-latency` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 15 | 6, 0 | NOT_LISTED | not listed by default; judged through the hidden sample |
| `heap-growth-after-gc` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 4 | 4, 0 | NOT_LISTED | not listed by default; judged through the hidden sample |
| `large-persistence-context` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `lazy-sql-after-handler` | 4 | 1 | 4 (100 %) | 4/4 | 0/0 | 0 | 4 / 0 | 0, 0 | UNDER_SAMPLED | hidden, not externally validated: too few facts |
| `orm-auto-flush` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `proxy-bypass` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `repeated-selects` | 4 | 1 | 0 (0 %) | 0/4 | 0/0 | 0 | 4 / 0 | 0, 0 | UNDER_SAMPLED | hidden, not externally validated: too few facts |
| `route-time-breakdown` | 6 | 3 | 2 (33.3 %) | 0/2 | 2/4 | 0 | 9 / 108 | 42, 0 | FAIL | folds into its panel or stays hidden |
| `safe-method-dml` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `split-transaction-writes` | 1 | 1 | 0 (0 %) | 0/0 | 0/1 | 0 | 1 / 0 | 0, 0 | UNDER_SAMPLED | hidden, not externally validated: too few facts |
| `transaction-across-remote-call` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | NOT_EXERCISED | stays listed, marked as not externally validated: its check never ran |
| `transactional-listener-skipped` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |
| `work-after-response` | 0 | 0 | 0 (—) | 0/0 | 0/0 | 0 | 0 / 0 | 0, 0 | SILENT | stays listed, marked as not externally validated |

### Honesty

100 rows (insufficient or not applicable rows, and checks that did not fully run): 100 honest (100 %), 0 hid something real, 0 misleading.

### Hidden-row sample

| Application | Sampled | Actionable for either reviewer | Useful to both | Misleading |
| --- | --- | --- | --- | --- |
| bookstore | 10 | 0 | 0 | 0 |
| jhipster | 10 | 0 | 0 | 0 |
| kafka | 3 | 0 | 0 | 0 |
| petclinic | 10 | 0 | 0 | 0 |
| super-heroes | 10 | 0 | 0 | 0 |
| timeless | 10 | 0 | 0 | 0 |
| webflux-gateway | 10 | 1 | 0 | 0 |

1 of 63 sampled hidden rows were judged actionable by a reviewer; among sampled rows, at 95 % confidence, the actionable share is at most 8.5 % (the sample is stratified, so this does not bound all hidden rows).

| Row | Kind | Subject | Stratum | Reason left out | r1 | r2 | Adjudicated |
| --- | --- | --- | --- | --- | --- | --- | --- |
| webflux-gateway/hidden/25818fa9 | exception-hotspots | PUT /api/admin/users/{login} | reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses. | It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses. | Actionable | Noise | Actionable |

### Rows judged misleading by either reviewer

| Row | Section | Kind | Subject | r1 | r2 | Final | Reason |
| --- | --- | --- | --- | --- | --- | --- | --- |
| bookstore/fact/411799c2 | fact | connections-per-request | POST /orders | Misleading | Misleading | Misleading | — |
| bookstore/fact/f96ae8af | fact | split-transaction-writes | POST /orders | Informative | Misleading | Informative | Followed 3 of 3 advisory models (Informative, against my first Misleading): All 3: the split is the intended Modulith outbox (OrderService.java:33-44 commits order + event; listener in its own tx, OrderEventInventoryHandler.java:19-22). BootUI's 2nd check explicitly covers 'meant to commit alone, such as an outbox', so it does not push a wrong change. Opus 5: 7 of the 8 units are Modulith registry bookkeeping, inflating the count. |
| petclinic/fact/12b74f6b | fact | repeated-selects | POST /owners/{ownerId}/pets/{petId}/edit | Noise | Misleading | Noise | Followed 3 of 3 advisory models (Noise, against my first Misleading): All 3: real repeats, but caused by PetTypeFormatter.java:53 (fixed type list), duplicated by the lazy-SQL row that already names the formatter; the join/IN advice is conditional ('if it does'), so it wastes a check rather than causing a wrong change. Opus 5 notes the call-site table wrongly attributes all 7 to PetController.java:64 (an engine bug). |
| petclinic/fact/41f7e90d | fact | repeated-selects | POST /owners/{ownerId}/pets/new | Noise | Misleading | Noise | Followed 3 of 3 advisory models (Noise, against my first Misleading): Same as row 2. |
| petclinic/fact/d1ce3167 | fact | repeated-selects | GET /owners/{ownerId}/pets/new | Noise | Misleading | Noise | Followed 3 of 3 advisory models (Noise, against my first Misleading): Same as row 2. |
| petclinic/fact/eb5bb5fb | fact | repeated-selects | GET /owners/{ownerId}/pets/{petId}/edit | Noise | Misleading | Noise | Followed 3 of 3 advisory models (Noise, against my first Misleading): Same as row 2. |
| super-heroes+agent/fact/3ee46b2c | fact | changed-code-not-executed | io.quarkus.sample.superheroes.villain.rest.VillainResource | Informative | Misleading | Informative | Followed 3 of 3 advisory models (Informative, against my first Misleading): All 3: true (deleteAllVillains, VillainResource.java:131, is @DELETE and did not run). Suggesting GET /api/villains is a wasted request, not a wrong code change. Grok: becomes Misleading only if a reader 'fixes wiring' without noticing @DELETE. |
| super-heroes+agent/fact/f379ff0d | fact | changed-code-not-executed | io.quarkus.sample.superheroes.villain.service.VillainService | Informative | Misleading | Informative | Followed 3 of 3 advisory models (Informative, against my first Misleading): All 3: true (VillainService.java:130 reached only by DELETE or replaceAllVillains, neither ran). The GET hint is a wrong probe, not a reason to rewire. |
| timeless/fact/54ac7ea2 | fact | ai-usage-by-route | POST /api/messages | Informative | Misleading | Misleading | GPT-6 is right Confirmed by 2 of 3 advisory models: 2 of 3 Misleading: tool round-trip (TextAiService.java:9), ~1,660 tokens per message with no real growth, yet 'grew up to 1.0 times' and the only advice is to trim the prompt. Grok: Informative, since '1.0 times' is in the same sentence. |

### Recall

| Application | Known | Exercised | Found (default list) | Found (hidden only) | Honest gap | Missed | Recall (default) | Counterexamples violated |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| petclinic | 6 | 6 | 3 | 0 | 1 | 2 | 50 % | none |
| jhipster | 4 | 4 | 2 | 1 | 0 | 1 | 50 % | none |
| super-heroes | 5 | 5 | 2 | 0 | 1 | 2 | 40 % | none |
| webflux-gateway | 5 | 5 | 2 | 1 | 2 | 0 | 40 % | none |
| kafka | 4 | 4 | 0 | 0 | 1 | 3 | 0 % | none |
| bookstore | 2 | 2 | 0 | 0 | 1 | 1 | 0 % | none |
| timeless | 4 | 4 | 2 | 0 | 0 | 2 | 50 % | TL-C2 |

Found by the first run, not in the rerun's default list (regressions): PC-2 (missed), JH-3 (found-hidden), SH-2 (missed), SH-3 (missed), WF-3 (found-hidden), K-4 (missed).

### Agent investigations

| Arm | Questions | Correct | Partial | Wrong | Tool calls | Of which --help |
| --- | --- | --- | --- | --- | --- | --- |
| 2.0 | 10 | 6 | 3 | 1 | 73 | 10 |
| 1.x | 10 | 6 | 2 | 2 | 138 | 20 |

Target (all ten correct with 2.0, fewer calls than 1.x): No.

### Time to first observation

| Application | Stack | Minutes | First row | First OBSERVED row, minutes | ≤ 5 minutes |
| --- | --- | --- | --- | --- | --- |
| petclinic | spring-mvc | 0.2 | exception-hotspots OBSERVED | 0.2 | Yes |
| jhipster | spring-mvc | 0.3 | anonymous-data-reach OBSERVED | 0.3 | Yes |
| super-heroes | quarkus | 0.6 | exception-hotspots OBSERVED | 0.6 | Yes |
| webflux-gateway | spring-webflux | 0.3 | errors-behind-2xx OBSERVED | 0.3 | Yes |
| kafka | spring-mvc | 0.0 | null null | — | No |
| bookstore | spring-mvc | 0.3 | connections-per-request OBSERVED | 0.3 | Yes |
| timeless | quarkus | 0.4 | ai-usage-by-route OBSERVED | 0.4 | Yes |
