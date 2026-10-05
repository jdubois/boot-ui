# BootUI 2.0 validation rerun: maintainer adjudication

**Adjudicated 2026-10-05 by the maintainer, with three advisory models** (see the report's
[Adjudication](V2-VALIDATION-REPORT.md#adjudication) section). The M4-20 rerun under `m4-20-protocol-2` (tag object
`5f38a51af`, commit `5bd7cb76e`), built once from `5bd7cb76e` (engine SHA-256 `e913e36b…`, clean tree), was judged
by two independent reviewers (r1 on claude-opus-5.5, r2 on gpt-6-sol) following the registered reviewer prompt. The
operator gathered the evidence below and judged nothing. See the [validation report](V2-VALIDATION-REPORT.md#rerun-results)
for the scores.

This page is the record of the rulings:

1. **Adjudication** (sections 1 and 2): every row the reviewers judged differently, and every row one of them judged
   Misleading, with the maintainer's ruling and reason from [`adjudication.csv`](validation/m4-20-rerun/adjudication.csv).
   Two reviewers who agree are never overruled, Misleading included (section 2 lists those for the record).
2. **Hidden-row sample** (section 3): either reviewer judging a hidden row Actionable counts as hidden value; the
   adjudication of such a row is recorded, not decisive.
3. **Recall** (section 4): the outcome of every known miss and counterexample from
   [`recall.csv`](validation/m4-20-rerun/recall.csv), marked from the [recall evidence](validation/m4-20-rerun/recall-evidence.md).

The [data README](validation/m4-20-rerun/README.md) shows how to rescore, from a checkout at `5bd7cb76e` with the tag
`m4-20-protocol-2` fetched.

## 1. Rows to adjudicate (26)

| # | Row | Kind | Subject | r1 | r2 | Ruling |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `bookstore/fact/f96ae8af` | `split-transaction-writes` | POST /orders | Informative | Misleading | **Informative** |
| 2 | `petclinic/fact/12b74f6b` | `repeated-selects` | POST /owners/{ownerId}/pets/{petId}/edit | Noise | Misleading | **Noise** |
| 3 | `petclinic/fact/41f7e90d` | `repeated-selects` | POST /owners/{ownerId}/pets/new | Noise | Misleading | **Noise** |
| 4 | `petclinic/fact/d1ce3167` | `repeated-selects` | GET /owners/{ownerId}/pets/new | Noise | Misleading | **Noise** |
| 5 | `petclinic/fact/eb5bb5fb` | `repeated-selects` | GET /owners/{ownerId}/pets/{petId}/edit | Noise | Misleading | **Noise** |
| 6 | `super-heroes+agent/fact/3ee46b2c` | `changed-code-not-executed` | io.quarkus.sample.superheroes.villain.rest.VillainResource | Informative | Misleading | **Informative** |
| 7 | `super-heroes+agent/fact/f379ff0d` | `changed-code-not-executed` | io.quarkus.sample.superheroes.villain.service.VillainService | Informative | Misleading | **Informative** |
| 8 | `timeless/fact/54ac7ea2` | `ai-usage-by-route` | POST /api/messages | Informative | Misleading | **Misleading** |
| 9 | `bookstore/fact/69437826` | `route-time-breakdown` | POST /registration | Noise | Informative | **Informative** |
| 10 | `bookstore/honesty/850374c1` | `after-commit-writes` | (check) | Honest | Hides | **Honest** |
| 11 | `bookstore/honesty/f85f04a9` | `route-time-breakdown` | POST /login | Honest | Hides | **Honest** |
| 12 | `jhipster/fact/1c61d2f0` | `errors-behind-2xx` | POST /api/admin/users | Informative | Actionable | **Informative** |
| 13 | `jhipster/fact/2aa57137` | `anonymous-data-reach` | POST /api/account/reset-password/init | Noise | Informative | **Noise** |
| 14 | `jhipster/fact/56bdfa9e` | `route-time-breakdown` | POST /api/admin/users | Noise | Informative | **Informative** |
| 15 | `jhipster/fact/6a1818fd` | `route-time-breakdown` | POST /api/authenticate | Noise | Informative | **Informative** |
| 16 | `jhipster/fact/c25fff20` | `errors-behind-2xx` | POST /api/account/reset-password/init | Informative | Actionable | **Informative** |
| 17 | `petclinic+agent/fact/e1a21005` | `changed-code-not-executed` | org.springframework.samples.petclinic.vet.Vet | Informative | Actionable | **Informative** |
| 18 | `timeless/fact/f6bad530` | `route-time-breakdown` | POST /api/sign-in | Noise | Informative | **Informative** |
| 19 | `webflux-gateway/fact/18d872b0` | `errors-behind-2xx` | POST /api/admin/users | Informative | Actionable | **Informative** |
| 20 | `webflux-gateway/fact/54542269` | `exception-hotspots` | GET /services/absent/management/health/readiness | Informative | Noise | **Noise** |
| 21 | `webflux-gateway/fact/57d1338e` | `exception-hotspots` | Behind 4xx responses | Informative | Noise | **Informative** |
| 22 | `webflux-gateway/fact/726b62eb` | `exception-hotspots` | GET /services/absent/api/orders | Informative | Noise | **Noise** |
| 23 | `webflux-gateway/hidden/25818fa9` | `exception-hotspots` | PUT /api/admin/users/{login} | Actionable | Noise | **Actionable** |
| 24 | `webflux-gateway/honesty/17ea33c9` | `event-loop-blocking` | (check) | Hides | Honest | **Honest** |
| 25 | `webflux-gateway/honesty/45559ea2` | `route-time-breakdown` | POST /api/admin/users | Honest | Hides | **Honest** |
| 26 | `webflux-gateway/honesty/ca64f722` | `route-time-breakdown` | POST /api/authenticate | Honest | Hides | **Honest** |

### 1a. Judged Misleading by one reviewer (8)

#### `bookstore/fact/f96ae8af`

- **bookstore**, `split-transaction-writes`, OBSERVED, subject `POST /orders`
- Sentence: `POST /orders` committed its writes in up to 8 independent units in 40 of 40 requests that wrote: if these writes must succeed together, one may persist while another fails.
- What to check (BootUI): If these writes must succeed together, run them in one transaction.; If one is meant to commit alone, such as an audit or outbox write, check that a failure after it leaves consistent data.
- Affected/eligible: 40 / 40; exemplar requests: de81524c73fa67f6, f521fec9734297b9, ac6e2c7e13abc3e7
- Evidence: `docs/validation/m4-20-rerun/evidence/bookstore/` (`split-transaction-writes:3bb9ca6432`)
- **r1 (claude-opus-5.5): Informative**: True: createOrder commits the order with its event publications atomically (OrderService.java:33-44) and Modulith then runs the inventory decrement and registry updates in separate transactions (OrderEventInventoryHandler.java:19-22), which is the intended outbox design worth knowing but not changing, as the second check acknowledges.
- **r2 (gpt-6-sol): Misleading**: orders/domain/OrderService.java:43 — The order explicitly publishes a durable Modulith event whose inventory update runs in a separate listener transaction, so merging publication and delivery into one transaction would break the design.
- **Ruling: Informative.** Followed 3 of 3 advisory models (Informative, against my first Misleading): All 3: the split is the intended Modulith outbox (OrderService.java:33-44 commits order + event; listener in its own tx, OrderEventInventoryHandler.java:19-22). BootUI's 2nd check explicitly covers 'meant to commit alone, such as an outbox', so it does not push a wrong change. Opus 5: 7 of the 8 units are Modulith registry bookkeeping, inflating the count.

#### `petclinic/fact/12b74f6b`

- **petclinic**, `repeated-selects`, OBSERVED, subject `POST /owners/{ownerId}/pets/{petId}/edit`
- Sentence: `POST /owners/{ownerId}/pets/{petId}/edit` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 367 requests, up to 7 times in one.
- What to check (BootUI): Open an exemplar request in Live Activity and check whether each repeat loads the children of one row the first statement returned.; If it does, load them with one statement, such as a join or an IN list, in SQL Trace's call site.
- Affected/eligible: 183 / 367; exemplar requests: f8515a288cd5018e, 18b65e1d8465056b, dbc173182300c73d
- Evidence: `docs/validation/m4-20-rerun/evidence/petclinic/` (`repeated-selects:30e9325d8e`)
- **r1 (claude-opus-5.5): Noise**: This repeats the lazy-SQL row for the same route and statement, and its call site PetController.java:64 plus join/IN-list advice point at populatePetTypes instead of the actual repeat source PetTypeFormatter.java:53.
- **r2 (gpt-6-sol): Misleading**: owner/PetTypeFormatter.java:53 — The repeats come from a fixed pet-type lookup inside the formatter, not child rows of a parent query, so the proposed join or IN-list investigation points to the wrong fix.
- **Ruling: Noise.** Followed 3 of 3 advisory models (Noise, against my first Misleading): All 3: real repeats, but caused by PetTypeFormatter.java:53 (fixed type list), duplicated by the lazy-SQL row that already names the formatter; the join/IN advice is conditional ('if it does'), so it wastes a check rather than causing a wrong change. Opus 5 notes the call-site table wrongly attributes all 7 to PetController.java:64 (an engine bug).

#### `petclinic/fact/41f7e90d`

- **petclinic**, `repeated-selects`, OBSERVED, subject `POST /owners/{ownerId}/pets/new`
- Sentence: `POST /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 366 requests, up to 7 times in one.
- What to check (BootUI): Open an exemplar request in Live Activity and check whether each repeat loads the children of one row the first statement returned.; If it does, load them with one statement, such as a join or an IN list, in SQL Trace's call site.
- Affected/eligible: 183 / 366; exemplar requests: 39cc18149f357d63, c495e6a786d71e16, 8da5c40f511ccc52
- Evidence: `docs/validation/m4-20-rerun/evidence/petclinic/` (`repeated-selects:613704a9e5`)
- **r1 (claude-opus-5.5): Noise**: Same statement and requests as the lazy-SQL row for POST /owners/{ownerId}/pets/new, and its call site PetController.java:64 and N+1 join advice do not match the real cause in PetTypeFormatter.java:53.
- **r2 (gpt-6-sol): Misleading**: owner/PetTypeFormatter.java:53 — The repeats come from a fixed pet-type lookup inside the formatter, not child rows of a parent query, so the proposed join or IN-list investigation points to the wrong fix.
- **Ruling: Noise.** Followed 3 of 3 advisory models (Noise, against my first Misleading): Same as row 2.

#### `petclinic/fact/d1ce3167`

- **petclinic**, `repeated-selects`, OBSERVED, subject `GET /owners/{ownerId}/pets/new`
- Sentence: `GET /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 183 requests, up to 7 times in one.
- What to check (BootUI): Open an exemplar request in Live Activity and check whether each repeat loads the children of one row the first statement returned.; If it does, load them with one statement, such as a join or an IN list, in SQL Trace's call site.
- Affected/eligible: 183 / 183; exemplar requests: 7c9f22f688117dbd, db7ff59c69bbc289, 9d382f6187e8bdb8
- Evidence: `docs/validation/m4-20-rerun/evidence/petclinic/` (`repeated-selects:8f816c3c0f`)
- **r1 (claude-opus-5.5): Noise**: It duplicates the lazy-SQL row for GET /owners/{ownerId}/pets/new and attributes the repeats to PetController.java:64 with join/IN-list advice, when they come from PetTypeFormatter.java:53.
- **r2 (gpt-6-sol): Misleading**: owner/PetTypeFormatter.java:53 — The repeats come from a fixed pet-type lookup inside the formatter, not child rows of a parent query, so the proposed join or IN-list investigation points to the wrong fix.
- **Ruling: Noise.** Followed 3 of 3 advisory models (Noise, against my first Misleading): Same as row 2.

#### `petclinic/fact/eb5bb5fb`

- **petclinic**, `repeated-selects`, OBSERVED, subject `GET /owners/{ownerId}/pets/{petId}/edit`
- Sentence: `GET /owners/{ownerId}/pets/{petId}/edit` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 183 requests, up to 6 times in one.
- What to check (BootUI): Open an exemplar request in Live Activity and check whether each repeat loads the children of one row the first statement returned.; If it does, load them with one statement, such as a join or an IN list, in SQL Trace's call site.
- Affected/eligible: 183 / 183; exemplar requests: bf64808006da90d7, 7ab93f217662aa42, f2f4be5504e07b44
- Evidence: `docs/validation/m4-20-rerun/evidence/petclinic/` (`repeated-selects:99568a33e9`)
- **r1 (claude-opus-5.5): Noise**: It duplicates the lazy-SQL row for the pet edit page, with a call site (PetController.java:64) and N+1 advice that miss the formatter at PetTypeFormatter.java:53.
- **r2 (gpt-6-sol): Misleading**: owner/PetTypeFormatter.java:53 — The repeats come from a fixed pet-type lookup inside the formatter, not child rows of a parent query, so the proposed join or IN-list investigation points to the wrong fix.
- **Ruling: Noise.** Followed 3 of 3 advisory models (Noise, against my first Misleading): Same as row 2.

#### `super-heroes+agent/fact/3ee46b2c`

- **super-heroes+agent**, `changed-code-not-executed`, OBSERVED, subject `io.quarkus.sample.superheroes.villain.rest.VillainResource`
- Sentence: Your change has not run yet: 1 changed method of `VillainResource` was not executed in this run.
- What to check (BootUI): Send a request to a route that ran this class's other methods, such as `GET /api/villains`, or run the test that reaches it.; If it still does not run, check that the code path that should call it is wired: the right bean, route, or condition.
- Affected/eligible: 1 / 1; exemplar requests: -
- Evidence: `docs/validation/m4-20-rerun/evidence/super-heroes+agent/` (`changed-code-not-executed:1a70ea57b2`)
- **r1 (claude-opus-5.5): Informative**: True: the changed deleteAllVillains (VillainResource.java:131-134) was never called because the traffic does not send DELETE /api/villains, which the developer would want to know, though the suggested GET /api/villains would not reach it.
- **r2 (gpt-6-sol): Misleading**: rest/VillainResource.java:131 — The changed delete-all method was not run, but suggesting GET on a listing route will never reach the delete-all endpoint, so the recommended test is wrong.
- **Ruling: Informative.** Followed 3 of 3 advisory models (Informative, against my first Misleading): All 3: true (deleteAllVillains, VillainResource.java:131, is @DELETE and did not run). Suggesting GET /api/villains is a wasted request, not a wrong code change. Grok: becomes Misleading only if a reader 'fixes wiring' without noticing @DELETE.

#### `super-heroes+agent/fact/f379ff0d`

- **super-heroes+agent**, `changed-code-not-executed`, OBSERVED, subject `io.quarkus.sample.superheroes.villain.service.VillainService`
- Sentence: Your change has not run yet: 1 changed method of `VillainService` was not executed in this run.
- What to check (BootUI): Send a request to a route that ran this class's other methods, such as `GET /`, or run the test that reaches it.; If it still does not run, check that the code path that should call it is wired: the right bean, route, or condition.
- Affected/eligible: 1 / 2; exemplar requests: -
- Evidence: `docs/validation/m4-20-rerun/evidence/super-heroes+agent/` (`changed-code-not-executed:ed332e0635`)
- **r1 (claude-opus-5.5): Informative**: True: the changed VillainService.deleteAllVillains (VillainService.java:130-131) is reached only through DELETE /api/villains, which never ran, so the change is unverified; the other changed method findAllVillainsHavingName did run.
- **r2 (gpt-6-sol): Misleading**: service/VillainService.java:130 — The changed delete-all method was not run, but suggesting GET on a listing route will never reach the delete-all endpoint, so the recommended test is wrong.
- **Ruling: Informative.** Followed 3 of 3 advisory models (Informative, against my first Misleading): All 3: true (VillainService.java:130 reached only by DELETE or replaceAllVillains, neither ran). The GET hint is a wrong probe, not a reason to rewire.

#### `timeless/fact/54ac7ea2`

- **timeless**, `ai-usage-by-route`, OBSERVED, subject `POST /api/messages`
- Sentence: `POST /api/messages` made 160 AI operations in 80 of 120 requests: 160 model calls, up to 2 in one request, a median 154 ms each, 133216 input and 3040 output tokens (reported by 160 of 160 calls). Input tokens grew across successive model calls in 80 requests, up to 1.0 times the first call's.
- What to check (BootUI): Input that grows with each call resends the conversation: trim or summarize what is sent.
- Affected/eligible: 80 / 120; exemplar requests: e038c05b9c33ad67, f83a2c2614d7ec9a, 4ac6a7fb75d75fcb
- Evidence: `docs/validation/m4-20-rerun/evidence/timeless/` (`ai-usage-by-route:6d11138a0f`)
- **r1 (claude-opus-5.5): Informative**: The per-request AI cost (two model calls and about 1,660 tokens per WhatsApp message through TextAiService.java:9-12 and MessageResource.java:87) is true and worth knowing, while the growth it flags is only 1.0x, so there is nothing real to trim.
- **r2 (gpt-6-sol): Misleading**: infra/ai/TextAiService.java:9 — The AI service uses a tool-enabled prompt and the second model call has only 1.0 times the first input, so treating tool-roundtrip context as growing conversation needing trimming is a wrong fix.
- **Ruling: Misleading.** GPT-6 is right Confirmed by 2 of 3 advisory models: 2 of 3 Misleading: tool round-trip (TextAiService.java:9), ~1,660 tokens per message with no real growth, yet 'grew up to 1.0 times' and the only advice is to trim the prompt. Grok: Informative, since '1.0 times' is in the same sentence.

### 1b. Other disagreements (18)

#### `bookstore/fact/69437826`

- **bookstore**, `route-time-breakdown`, OBSERVED, subject `POST /registration`
- Sentence: `POST /registration`: warm median 79 ms over 79 requests; Handler, other work 90 %, Response write 6 %, SQL 3 %. First request 30 ms (cold).
- What to check (BootUI): Most of the time is in the handler outside recorded calls: run Profile resources to see the route's hottest frames.; Open the slowest exemplar request in Live Activity to see its timeline.
- Affected/eligible: 79 / 79; exemplar requests: 5fdb93969fa96a8c, 20c5b6ffae1c9ccc, 2d39ea02203230d3
- Evidence: `docs/validation/m4-20-rerun/evidence/bookstore/` (`route-time-breakdown:2a05f9407e`)
- **r1 (claude-opus-5.5): Noise**: The 90 % 'handler, other work' on valid registrations is the deliberate BCrypt hash in UserService.java:31 (CommonSecurityConfig.java:20), reported without suggesting a fix, so it only restates expected cost.
- **r2 (gpt-6-sol): Informative**: users/domain/UserService.java:30 — Registration hashes a password with BCrypt, which explains the handler-heavy median without implying a code change.
- **Ruling: Informative.** GPT-6 is right Confirmed by 2 of 3 advisory models: 2 of 3 Informative: the 90 % handler share is BCrypt (UserService.java:30-31); profiling advice does not suggest weakening it. Grok: Noise (expected cost).

#### `bookstore/honesty/850374c1`

- **bookstore**, `after-commit-writes`, UNAVAILABLE, subject `(check)`
- Sentence: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- Evidence: `docs/validation/m4-20-rerun/evidence/bookstore/` ()
- **r1 (claude-opus-5.5): Honest**: Spring Modulith's JDBC event registry replaces the multicaster (pom.xml:111, application.properties:43-47), and its listeners write in their own REQUIRES_NEW transactions so nothing joins a committed one (OrderEventInventoryHandler.java:19).
- **r2 (gpt-6-sol): Hides**: inventory/eventhandlers/OrderEventInventoryHandler.java:19 — The disabled event capture conceals the real post-commit inventory write performed by the Modulith listener after an order event.
- **Ruling: Honest.** Followed 3 of 3 advisory models (Honest, against my first Hides): All 3 Honest: Modulith replaces the multicaster, so UNAVAILABLE is true; the check targets AFTER_COMMIT writes WITHOUT their own transaction (AfterCommitWrites.java:17-21), and Modulith's listener commits in its own (InventoryService.java:23), so nothing of that class is hidden.

#### `bookstore/honesty/f85f04a9`

- **bookstore**, `route-time-breakdown`, INSUFFICIENT, subject `POST /login`
- Sentence: `POST /login`: warm median 80 ms over 40 requests; all of them reached no handler BootUI marks (an Actuator endpoint, a request the security filters answered, or a servlet outside Spring MVC), so the time is not split into phases. Recorded calls: SQL 2 %, Connection wait 0 %. First request 270 ms (cold).
- What to check (BootUI): Open the slowest exemplar request in Live Activity to see its timeline and recorded calls.
- Affected/eligible: 40 / 40; exemplar requests: 76e1d4d1f1ba85ac, a473118708a2c767, 54ba4b30826829d7
- Evidence: `docs/validation/m4-20-rerun/evidence/bookstore/` (`route-time-breakdown:0f81055bc7`)
- **r1 (claude-opus-5.5): Honest**: POST /login is answered by Spring Security's form-login filter, not a handler (WebSecurityConfig.java:45), and the evidence table still attributes 98 % to Authentication, i.e. the BCrypt check (CommonSecurityConfig.java:20).
- **r2 (gpt-6-sol): Hides**: config/WebSecurityConfig.java:45 — The form-login request is correctly outside a marked MVC handler but the observation omits its recorded 98 percent Authentication phase while calling the time unattributed.
- **Ruling: Honest.** Followed 3 of 3 advisory models (Honest, against my first Hides): All 3 Honest: POST /login is form login (WebSecurityConfig.java:45), and the evidence table shows Authentication at 98 %, so nothing is hidden; but the sentence 'not split into phases' contradicts its own table (a wording bug).

#### `jhipster/fact/1c61d2f0`

- **jhipster**, `errors-behind-2xx`, OBSERVED, subject `POST /api/admin/users`
- Sentence: `POST /api/admin/users` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- What to check (BootUI): Verify the response contract: should the caller learn that part of this request failed?; A task this request handed to another thread failed: verify its failure is handled, retried or alerted, or that the response does not promise what the task was to do.
- Affected/eligible: 13 / 13; exemplar requests: fb93ad98a3a4644c, a03766b0f8e07508, ea418d4a15886be2
- Evidence: `docs/validation/m4-20-rerun/evidence/jhipster/` (`errors-behind-2xx:26d546b877`)
- **r1 (claude-opus-5.5): Informative**: True: every admin user creation returns 201 while its @Async creation email fails (UserResource.java:118-120, MailService.java:109-112) and MailService only logs a WARN (MailService.java:79-81), which is JHipster's deliberate fire-and-forget design here failing only because no SMTP server listens at localhost:25 (application-dev.yml:51-53), so worth knowing rather than changing.
- **r2 (gpt-6-sol): Actionable**: web/rest/UserResource.java:118 — The endpoint queues an asynchronous email after returning success, and the recorded mail failure means notification did not arrive despite the successful response; delivery monitoring or retries are warranted.
- **Ruling: Informative.** Followed 2 of 3 advisory models (Informative, against my first Actionable): 2 of 3 Informative: true, but caught-and-logged by design (MailService.java:79-80) and caused by no SMTP on localhost:25 in dev (application-dev.yml:51-53). GPT-6.1: Actionable (lost onboarding email warrants delivery recovery).

#### `jhipster/fact/2aa57137`

- **jhipster**, `anonymous-data-reach`, OBSERVED, subject `POST /api/account/reset-password/init`
- Sentence: `POST /api/account/reset-password/init` wrote table `jhi_user` in 13 of 13 successful anonymous requests.
- What to check (BootUI): If `jhi_user` should only change for signed-in callers, check the rule that let these requests through.; Do not add authorization from this row alone: a public route or write, such as a catalog, a sign-up, or a contact form, is often intended.
- Affected/eligible: 13 / 13; exemplar requests: e632b4d0bee48337, 7a0ff155315667cb, 05c8656564bb4279
- Evidence: `docs/validation/m4-20-rerun/evidence/jhipster/` (`anonymous-data-reach:50353f241f`)
- **r1 (claude-opus-5.5): Noise**: Password reset is deliberately public (SecurityConfiguration.java:72) and must store a reset key on jhi_user (UserService.java:82-91), and the row only reports it while saying not to add authorization from it alone.
- **r2 (gpt-6-sol): Informative**: web/rest/AccountResource.java:165 — The reset-password route is intentionally public and updates the target user reset key before mailing it, so an anonymous write is expected here.
- **Ruling: Noise.** Followed 2 of 3 advisory models (Noise, against my first Informative): 2 of 3 Noise: the route is intentionally public (SecurityConfiguration.java:72) and must store a reset key; BootUI already warns not to add authorization. Opus 5: Informative (worth one confirming glance).

#### `jhipster/fact/56bdfa9e`

- **jhipster**, `route-time-breakdown`, OBSERVED, subject `POST /api/admin/users`
- Sentence: `POST /api/admin/users`: warm median 98 ms over 12 requests; Handler, other work 94 %, Hibernate flushes 2 %, Response write 1 %. Median CPU 92 ms and 570 KB allocated per request. First request 255 ms (cold).
- What to check (BootUI): Most of the time is in the handler outside recorded calls: run Profile resources to see the route's hottest frames.; Open the slowest exemplar request in Live Activity to see its timeline.
- Affected/eligible: 12 / 12; exemplar requests: a03766b0f8e07508, 49c30fd5520a9656, 54003fe48c4fe64f
- Evidence: `docs/validation/m4-20-rerun/evidence/jhipster/` (`route-time-breakdown:67b3d00d88`)
- **r1 (claude-opus-5.5): Noise**: The 92 ms CPU per request is the intended BCrypt hash of the generated password in UserService.java:156 (SecurityConfiguration.java:41), reported without a fix, so it restates expected cost.
- **r2 (gpt-6-sol): Informative**: web/rest/UserResource.java:118 — Creating a new user hashes a generated password, explaining the handler-heavy 98 ms without itself calling for weaker hashing.
- **Ruling: Informative.** Confirmed by 2 of 3 advisory models: 2 of 3 Informative: the 92 ms CPU is BCrypt of the generated password (UserService.java:156-158). Grok: Noise.

#### `jhipster/fact/6a1818fd`

- **jhipster**, `route-time-breakdown`, OBSERVED, subject `POST /api/authenticate`
- Sentence: `POST /api/authenticate`: warm median 87 ms over 38 requests; Handler, other work 96 %, Response write 3 %, Other filters 1 %. Median CPU 84 ms and 283 KB allocated per request. First request 833 ms (cold).
- What to check (BootUI): Most of the time is in the handler outside recorded calls: run Profile resources to see the route's hottest frames.; Open the slowest exemplar request in Live Activity to see its timeline.
- Affected/eligible: 38 / 38; exemplar requests: ea3e939455ad78c4, 7d2538569ab9e431, 59cf503137f9ab7c
- Evidence: `docs/validation/m4-20-rerun/evidence/jhipster/` (`route-time-breakdown:5cf2c95c76`)
- **r1 (claude-opus-5.5): Noise**: The 84 ms CPU in POST /api/authenticate is the deliberate BCrypt verification (AuthenticateController.java:56, SecurityConfiguration.java:41), expected for a login endpoint.
- **r2 (gpt-6-sol): Informative**: web/rest/AuthenticateController.java:60 — Authentication runs password verification and token creation, so its handler-heavy cost is useful context rather than evidence that BCrypt should be removed.
- **Ruling: Informative.** Confirmed by 2 of 3 advisory models: 2 of 3 Informative: intended BCrypt verification (AuthenticateController.java:56-62). Grok: Noise.

#### `jhipster/fact/c25fff20`

- **jhipster**, `errors-behind-2xx`, OBSERVED, subject `POST /api/account/reset-password/init`
- Sentence: `POST /api/account/reset-password/init` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- What to check (BootUI): Verify the response contract: should the caller learn that part of this request failed?; A task this request handed to another thread failed: verify its failure is handled, retried or alerted, or that the response does not promise what the task was to do.
- Affected/eligible: 13 / 13; exemplar requests: e632b4d0bee48337, 7a0ff155315667cb, 05c8656564bb4279
- Evidence: `docs/validation/m4-20-rerun/evidence/jhipster/` (`errors-behind-2xx:cc7aaad3ad`)
- **r1 (claude-opus-5.5): Informative**: True: a valid reset answers 200 while the @Async reset email fails (AccountResource.java:165-167, MailService.java:115-118) and is only logged at WARN (MailService.java:79-81), an environment failure (no SMTP at application-dev.yml:51-53) worth knowing since the user would never get the reset link.
- **r2 (gpt-6-sol): Actionable**: web/rest/AccountResource.java:165 — The endpoint queues an asynchronous email after returning success, and the recorded mail failure means notification did not arrive despite the successful response; delivery monitoring or retries are warranted.
- **Ruling: Informative.** Followed 2 of 3 advisory models (Informative, against my first Actionable): 2 of 3 Informative: reset mail fails only because there is no dev SMTP; caught by design. GPT-6.1: Actionable.

#### `petclinic+agent/fact/e1a21005`

- **petclinic+agent**, `changed-code-not-executed`, OBSERVED, subject `org.springframework.samples.petclinic.vet.Vet`
- Sentence: Your change has not run yet: 1 changed method of `Vet` was not executed in this run.
- What to check (BootUI): Run the test or send the request that should reach it, then read Code Inventory again.; If it still does not run, check that the code path that should call it is wired: the right bean, route, or condition.
- Affected/eligible: 1 / 1; exemplar requests: -
- Evidence: `docs/validation/m4-20-rerun/evidence/petclinic+agent/` (`changed-code-not-executed:2d43305351`)
- **r1 (claude-opus-5.5): Informative**: True: the changed Vet.addSpecialty (Vet.java:70) is called only from tests (VetControllerTests.java:72), never by the running app, which is worth knowing but needs no fix.
- **r2 (gpt-6-sol): Actionable**: vet/Vet.java:70 — This changed method remains unexercised by the recorded traffic and should be covered by an appropriate test or route before relying on the change.
- **Ruling: Informative.** Followed 3 of 3 advisory models (Informative, against my first Actionable): All 3 Informative: Vet.addSpecialty (Vet.java:70) is only called from VetControllerTests.java:72, so no traffic can reach it; the right action is running that test, not a code or config change.

#### `timeless/fact/f6bad530`

- **timeless**, `route-time-breakdown`, OBSERVED, subject `POST /api/sign-in`
- Sentence: `POST /api/sign-in`: warm median 100 ms over 79 requests; Handler, other work 98 %, SQL 2 %, Other filters 0 %. Median CPU 98 ms and 328 KB allocated per request. First request 101 ms (cold).
- What to check (BootUI): Most of the time is in the handler outside recorded calls: run Profile resources to see the route's hottest frames.; Open the slowest exemplar request in Live Activity to see its timeline.
- Affected/eligible: 79 / 79; exemplar requests: cf56856cf1d59a48, c48ca979fba85ce0, 15f0237fd51477e7
- Evidence: `docs/validation/m4-20-rerun/evidence/timeless/` (`route-time-breakdown:73d49b7b11`)
- **r1 (claude-opus-5.5): Noise**: The 98 ms CPU per sign-in is the intended BCrypt check (SignInResource.java:36, BCryptAdapter.java:12), reported without a fix.
- **r2 (gpt-6-sol): Informative**: presentation/SignInResource.java:36 — Sign-in performs BCrypt password verification, an intentional security cost that explains the handler-heavy 100 ms.
- **Ruling: Informative.** Confirmed by 2 of 3 advisory models: 2 of 3 Informative: BCrypt check plus JWT signing (SignInResource.java:36-43). Grok: Noise.

#### `webflux-gateway/fact/18d872b0`

- **webflux-gateway**, `errors-behind-2xx`, OBSERVED, subject `POST /api/admin/users`
- Sentence: `POST /api/admin/users` answered 2xx in 24 of 24 successful requests: 24 requests that recorded an exception.
- What to check (BootUI): Verify the response contract: should the caller learn that part of this request failed?
- Affected/eligible: 24 / 24; exemplar requests: 2bd02f3babe55d5c, 4ef92f1a193fb7ed, 4eb08ff54c127ef8
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`errors-behind-2xx:26d546b877`)
- **r1 (claude-opus-5.5): Informative**: True: each created user gets 201 while its creation email fails with MailSendException that MailService only logs at WARN (UserResource.java:132, MailService.java:52-56,79-80), an SMTP-less environment fact worth knowing since the user never receives the email.
- **r2 (gpt-6-sol): Actionable**: web/rest/UserResource.java:132 — Admin user creation invokes mail sending after persistence, and a logged MailSendException with a 201 needs reliable delivery monitoring or retry.
- **Ruling: Informative.** Followed 2 of 3 advisory models (Informative, against my first Actionable): 2 of 3 Informative: same no-SMTP failure as rows 12/16 (UserResource.java:132, MailService.java:79-80). GPT-6.1: Actionable.

#### `webflux-gateway/fact/54542269`

- **webflux-gateway**, `exception-hotspots`, OBSERVED, subject `GET /services/absent/management/health/readiness`
- Sentence: `GET /services/absent/management/health/readiness` recorded `AbstractChannel$AnnotatedConnectException` in 24 of 24 requests (24 occurrences).
- What to check (BootUI): Open the exception group to read its message and stack trace.
- Affected/eligible: 24 / 24; exemplar requests: 12c854791949f7df, 97419a7c26aec8a2, 9d5339a3ffd8bb71
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`exception-hotspots:bce42a1451`)
- **r1 (claude-opus-5.5): Informative**: True: the permitted readiness route (SecurityConfiguration.java:82) proxies to a downstream that refuses connections and the gateway answers 500 rather than 502/503, which tells the developer the route target is down even though no code change follows from it here.
- **r2 (gpt-6-sol): Noise**: config/SecurityConfiguration.java:82 — The route explicitly targets an absent service and the connection exception is the expected result of the deliberately missing upstream.
- **Ruling: Noise.** Confirmed by 2 of 3 advisory models: 2 of 3 Noise: /services/absent is the harness's deliberately missing upstream (validation/apps/webflux-gateway/app.sh:20-28), not an app defect. Opus 5: Informative.

#### `webflux-gateway/fact/57d1338e`

- **webflux-gateway**, `exception-hotspots`, OBSERVED, subject `Behind 4xx responses`
- Sentence: 4 exception groups on 4 routes were recorded only behind 4xx responses: 96 occurrences in 96 requests.
- What to check (BootUI): Check that each of these 4xx responses is the one the caller should get, such as 400 for invalid input; Show all routes lists each group with its requests.; Open the exception group to read its message and stack trace.
- Affected/eligible: 0 / 0; exemplar requests: a04554b882321fea, 705f0d70596327dc, 6e5c187bbb0d13b5
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`exception-hotspots:64b0c2ab69`)
- **r1 (claude-opus-5.5): Informative**: Most groups are intended 4xx (missing user or authority, duplicate authority), but checking them as the row asks reveals that every PUT without an id is rejected with a misleading EmailAlreadyUsedException for the user's own email (UserResource.java:161-167).
- **r2 (gpt-6-sol): Noise**: service/UserService.java:162 — The resource deliberately rejects missing IDs or duplicate emails, making these 4xx exceptions expected client responses.
- **Ruling: Informative.** Followed 2 of 3 advisory models (Informative, against my first Noise): All 3 say at least Informative (GPT-6.1 Actionable): following this row's instruction surfaces a real bug: every PUT /api/admin/users/{login} fails with EmailAlreadyUsedException (UserResource.java:154-167, null body id).

#### `webflux-gateway/fact/726b62eb`

- **webflux-gateway**, `exception-hotspots`, OBSERVED, subject `GET /services/absent/api/orders`
- Sentence: `GET /services/absent/api/orders` recorded `AbstractChannel$AnnotatedConnectException` in 24 of 24 requests (24 occurrences).
- What to check (BootUI): Open the exception group to read its message and stack trace.
- Affected/eligible: 24 / 24; exemplar requests: a20932e3f8e83342, b90285b6bb758676, 4015ec09eb0440d4
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`exception-hotspots:673e80e2da`)
- **r1 (claude-opus-5.5): Informative**: True: the gateway route to the absent service fails to connect on every request and surfaces as a 500 (application.yml:121-130 gateway routing), which is worth knowing about the route's target but needs no code change in this app.
- **r2 (gpt-6-sol): Noise**: config/SecurityConfiguration.java:82 — The route explicitly targets an absent service and the connection exception is the expected result of the deliberately missing upstream.
- **Ruling: Noise.** Confirmed by 2 of 3 advisory models: 2 of 3 Noise: same deliberately missing upstream as row 20. Opus 5: Informative.

#### `webflux-gateway/hidden/25818fa9`

- **webflux-gateway**, `exception-hotspots`, OBSERVED, subject `PUT /api/admin/users/{login}`
- Sentence: `PUT /api/admin/users/{login}` recorded `EmailAlreadyUsedException` in 24 of 24 requests (24 occurrences).
- Hidden because: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.
- Sample stratum: reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses.
- What to check (BootUI): Open the exception group to read its message and stack trace.
- Affected/eligible: 24 / 24; exemplar requests: 705f0d70596327dc, 80fd370510ec15f9, 7f756ebe041c6d51
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`exception-hotspots:6628e5d0b5`)
- **r1 (claude-opus-5.5): Actionable**: Every PUT /api/admin/users/{login} without a body id fails with EmailAlreadyUsedException for the user's own email, because updateUser ignores the path login and treats a null id as another user (UserResource.java:154-167), a confusing contract worth fixing that the default list only folds into the 4xx summary.
- **r2 (gpt-6-sol): Noise**: web/rest/UserResource.java:154 — The controller deliberately rejects missing or duplicate resources, or this mail failure is already in the default list.
- **Ruling: Actionable.** Followed 3 of 3 advisory models (Actionable, against my first Noise): All 3 Actionable: a REAL BUG. updateUser ignores the path login, and a null body id makes the duplicate-email filter reject the user's own email, so 24 of 24 PUTs fail (UserResource.java:154-167). This is the one genuine defect in the set, hidden by the 4xx rule. (Hidden row: your ruling is recorded but changes no gate.)

#### `webflux-gateway/honesty/17ea33c9`

- **webflux-gateway**, `event-loop-blocking`, UNAVAILABLE, subject `(check)`
- Sentence: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` ()
- **r1 (claude-opus-5.5): Hides**: The R2DBC reason is true for SQL, but the app really does block a reactive thread: doOnSuccess calls MailService.sendEmail, whose Mono.defer(...).subscribe() runs the blocking JavaMailSender send inline after the R2DBC save (UserResource.java:132, MailService.java:52-56), and the report never says so.
- **r2 (gpt-6-sol): Honest**: service/UserService.java:180 — User creation schedules BCrypt password hashing on boundedElastic, and the unavailable SQL-dependent blocking check does not assert an event loop was safe.
- **Ruling: Honest.** Confirmed by 3 of 3 advisory models: All 3 Honest: the check is JDBC-on-event-loop only (EventLoopBlocking.java:16-18) and the app uses R2DBC, so UNAVAILABLE is truthful. Note: the app's mail send can block a reactive thread (MailService.java:52-56), a gap no check covers.

#### `webflux-gateway/honesty/45559ea2`

- **webflux-gateway**, `route-time-breakdown`, INSUFFICIENT, subject `POST /api/admin/users`
- Sentence: `POST /api/admin/users`: warm median 22 ms over 47 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 21 ms and 3.9 MB allocated per request. First request 473 ms (cold).
- What to check (BootUI): Open the slowest exemplar request in Live Activity to see its timeline.
- Affected/eligible: 47 / 47; exemplar requests: 99156b6f9d16674b, 95df36ed6f5c868e, 8b4c5e690a39c8dc
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`route-time-breakdown:67b3d00d88`)
- **r1 (claude-opus-5.5): Honest**: WebFlux routes are not split into phases, as stated, and the row still reports the 21 ms CPU (BCrypt on boundedElastic and the inline mail send, UserService.java:180-182, MailService.java:52-56) that the errors-behind-2xx row explains.
- **r2 (gpt-6-sol): Hides**: web/rest/UserResource.java:132 — User creation hashes a password on boundedElastic and triggers email delivery, but the 22 ms WebFlux report leaves that work wholly unattributed.
- **Ruling: Honest.** Followed 3 of 3 advisory models (Honest, against my first Hides): All 3 Honest: WebFlux has no phase marks, the table is 100 % Unattributed and says so; BCrypt runs on boundedElastic (UserService.java:179-181). Nothing measured was dropped.

#### `webflux-gateway/honesty/ca64f722`

- **webflux-gateway**, `route-time-breakdown`, INSUFFICIENT, subject `POST /api/authenticate`
- Sentence: `POST /api/authenticate`: warm median 85 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 85 ms and 792 KB allocated per request. First request 285 ms (cold).
- What to check (BootUI): Open the slowest exemplar request in Live Activity to see its timeline.
- Affected/eligible: 23 / 23; exemplar requests: 7da5deae0ae1e8ad, d41bc5a8bef56d96, 097e4ac5e28f9a6d
- Evidence: `docs/validation/m4-20-rerun/evidence/webflux-gateway/` (`route-time-breakdown:5cf2c95c76`)
- **r1 (claude-opus-5.5): Honest**: WebFlux marks no phases, as stated, and the 85 ms CPU it reports is the intended BCrypt check of the login (SecurityConfiguration.java:75 permits /api/authenticate).
- **r2 (gpt-6-sol): Hides**: web/rest/AuthenticateController.java:60 — The route spends 85 ms authenticating through a password manager, but the unphased WebFlux row does not tell readers where that real handler work went.
- **Ruling: Honest.** Followed 3 of 3 advisory models (Honest, against my first Hides): All 3 Honest: same WebFlux limit, disclosed; the 85 ms CPU is intended password verification.

## 2. Judged Misleading by both reviewers (1): final, not adjudicated

#### `bookstore/fact/411799c2`

- **bookstore**, `connections-per-request`, OBSERVED, subject `POST /orders`
- Sentence: `POST /orders` held 3 connections of `dataSource` at the same time in 40 of 80 requests.
- What to check (BootUI): Check for a REQUIRES_NEW transaction, or a second call to the data source, inside an open transaction (Transactions).; With a pool of 10 connections and the observed maximum of 3 held per request, 5 concurrent requests can exhaust the pool while waiting for another connection. This is a hold-and-wait estimate, not proof of a deadlock (Database Connection Pools).
- Affected/eligible: 40 / 80; exemplar requests: de81524c73fa67f6, f521fec9734297b9, ac6e2c7e13abc3e7
- Evidence: `docs/validation/m4-20-rerun/evidence/bookstore/` (`connections-per-request:925b771827`)
- **r1 (claude-opus-5.5): Misleading**: The 3 concurrent connections come from Modulith's three async @ApplicationModuleListener REQUIRES_NEW transactions after createOrder commits (OrderEventInventoryHandler.java:19, OrderEventNotificationHandler.java:13, @Externalized OrderCreatedEvent.java:5), which is parallel independent work, so the nested hold-and-wait pool-exhaustion estimate and the 'REQUIRES_NEW inside an open transaction' check point at a problem the code (OrderService.java:33-44) does not have.
- **r2 (gpt-6-sol): Misleading**: orders/domain/OrderService.java:43 — The order publishes a Modulith event inside its transaction, and the concurrent listener can hold separate connections, so searching for an in-method REQUIRES_NEW call misdirects this finding.

## 3. Hidden-row sample (63 rows, up to 10 per application, seed bootui-v2-rerun-1)

Hidden value means either reviewer judged the row Actionable. Notes are the reviewers' own, shortened to one line.

### bookstore: 10 sampled, 0 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `bookstore/hidden/103d0ab2` | reason: gc-inflated-latency: garbage collection and heap rows are reached from the memory panel rather than listed by default. | `gc-inflated-latency` | A stop-the-world pause completed during 3 of `GET /products`'s 16 slowest requests (19 %), against 0 % of its other 143 requests; those pauses total 42 ms. | **Noise**: Three young G1 pauses of 11-16 ms on a 25-41 ms catalog page (ProductWebController.java:27) are too small and rare to matter. | **Noise**: catalog/web/ProductWebController.java:19 — The product view has only three pause-overlapping slow requests and 42 ms of total pauses, too little to motivate changing i… |
| `bookstore/hidden/2251ab2b` | slowest hidden route | `route-time-breakdown` | `GET /login`: warm median 5.1 ms over 40 requests; Response write 90 %, Other filters 5 %, Handler, other work 4 %. First request 588 ms (cold). | **Noise**: A 5.1 ms login page render (UserController.java:24) is unremarkable, so hiding it loses nothing. | **Noise**: config/WebSecurityConfig.java:45 — The mapped controller completes this small request with ordinary template rendering or SQL, so the reported sub-20 ms phase breakdow… |
| `bookstore/hidden/2d06c1f8` | seeded draw | `route-time-breakdown` | `GET /admin/inventory`: warm median 18 ms over 40 requests; Response write 61 %, SQL 19 %, Handler, other work 17 %. First request 0.8 ms (cold). | **Noise**: An 18 ms paged inventory page (AdminInventoryWebController.java:26, InventoryService.java:37-44) with no surprising phase is not worth reading. | **Noise**: inventory/web/AdminInventoryWebController.java:26 — The mapped controller completes this small request with ordinary template rendering or SQL, so the reported sub-20 … |
| `bookstore/hidden/5469855d` | seeded draw | `route-time-breakdown` | `GET /registration`: warm median 5.4 ms over 39 requests; Response write 92 %, Handler, other work 4 %, Other filters 4 %. First request 22 ms (cold). | **Noise**: A 5.4 ms registration form render (UserController.java:29-33) has nothing to act on. | **Noise**: users/web/UserController.java:29 — The mapped controller completes this small request with ordinary template rendering or SQL, so the reported sub-20 ms phase breakdow… |
| `bookstore/hidden/5c00a3ea` | reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses. | `exception-hotspots` | `GET /admin/catalog/products/{code}` recorded `ProductNotFoundException` in 40 of 80 requests (40 occurrences). | **Noise**: ProductNotFoundException is the deliberate 404 for the traffic's NO-SUCH probe (AdminProductWebController.java:71, CatalogExceptionHandler.java:16-22) and is already c… | **Noise**: catalog/web/AdminProductWebController.java:71 — The controller deliberately throws a not-found exception when the requested product or order does not exist, making the… |
| `bookstore/hidden/825b4489` | seeded draw | `route-time-breakdown` | `POST /admin/catalog/products/{code}/edit`: warm median 6.8 ms over 39 requests; Handler, other work 48 %, SQL 35 %, Other filters 6 %. First request 16 ms (cold). | **Noise**: A 6.8 ms product edit (AdminProductWebController.java:94, ProductService.java:62-66) has no notable phase. | **Noise**: catalog/web/AdminProductWebController.java:94 — The mapped controller completes this small request with ordinary template rendering or SQL, so the reported sub-20 ms p… |
| `bookstore/hidden/8f9910ed` | reason: heap-growth-after-gc: garbage collection and heap rows are reached from the memory panel rather than listed by default. | `heap-growth-after-gc` | 1 collection reclaimed old-generation space in this run; a trend needs 3. | **Noise**: True that one old-generation-reclaiming collection cannot show a trend in a 45 s run, but it carries no information about the app (BookStoreApplication.java:1 runs a s… | **Noise**: orders/web/OrderWebController.java:36 — The run has only one old-generation reclamation, so no heap-growth conclusion follows for the application. |
| `bookstore/hidden/ace3d620` | seeded draw | `route-time-breakdown` | `GET /admin/orders`: warm median 19 ms over 40 requests; Response write 68 %, Handler, other work 19 %, SQL 11 %. First request 1.8 ms (cold). | **Noise**: A 19 ms paged admin orders list (AdminOrderWebController.java:29, OrderService.java:77-83) with SQL at 11 % is unremarkable. | **Noise**: orders/web/AdminOrderWebController.java:29 — The mapped controller completes this small request with ordinary template rendering or SQL, so the reported sub-20 ms phas… |
| `bookstore/hidden/d32fa7f7` | reason: route-time-breakdown: its time is not split into phases, since its requests reached no handler bootui marks or named nothing at all, and its warm median is under # ms. | `route-time-breakdown` | `GET /actuator/health`: warm median 2.7 ms over 40 requests; all of them reached no handler BootUI marks (an Actuator endpoint, a request the security filters answered, or a servle | **Noise**: A 2.7 ms actuator health check (application.properties:50 exposes actuator) has nothing to split or change. | **Noise**: orders/web/OrderWebController.java:36 — An uninstrumented 2.7 ms health endpoint needs no further profiling. |
| `bookstore/hidden/f4added4` | seeded draw | `route-time-breakdown` | `POST /admin/orders/{orderNumber}/status`: warm median 7.5 ms over 39 requests; Handler, other work 45 %, SQL 38 %, Hibernate flushes 6 %. First request 12 ms (cold). | **Noise**: A 7.5 ms status update (AdminOrderWebController.java:58, OrderService.java:64-75) has no surprising phase. | **Noise**: orders/web/AdminOrderWebController.java:58 — The mapped controller completes this small request with ordinary template rendering or SQL, so the reported sub-20 ms phas… |

### jhipster: 10 sampled, 0 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `jhipster/hidden/0c875dfe` | slowest hidden route | `route-time-breakdown` | `GET /management/health`: warm median 2.9 ms over 13 requests; all of them reached no handler BootUI marks (an Actuator endpoint, a request the security filters answered, or a serv | **Noise**: A 2.9 ms public health probe (SecurityConfiguration.java:77) has nothing to change. | **Noise**: web/rest/AccountResource.java:163 — The uninstrumented management health check finishes under three milliseconds and merits no action. |
| `jhipster/hidden/0e720811` | seeded draw | `route-time-breakdown` | `GET /api/labels`: warm median 5.9 ms over 12 requests; Handler, other work 55 %, Other filters 16 %, Response write 14 %. Median CPU 5.7 ms and 244 KB allocated per request. First | **Noise**: A 5.9 ms label list (LabelResource.java:142) is unremarkable. | **Noise**: web/rest/LabelResource.java:40 — The mapped route or expected validation exception is already described elsewhere, and its small timing or duplicate error gives no use… |
| `jhipster/hidden/1a1d80e9` | seeded draw | `exception-hotspots` | `POST /api/account/reset-password/init` recorded `ConstraintViolationException` in 13 of 26 requests (13 occurrences). | **Noise**: The ConstraintViolationException is the intended 400 for the traffic's JSON-quoted email that fails @Email (AccountResource.java:163) and is already counted in the 4xx… | **Noise**: web/rest/AccountResource.java:165 — The route intentionally rejects invalid credentials, validation errors, or duplicate data, so the 4xx exception is already an expec… |
| `jhipster/hidden/23025143` | seeded draw | `route-time-breakdown` | `DELETE /api/bank-accounts/{id}`: warm median 5.8 ms over 12 requests; Handler, other work 39 %, Other filters 27 %, Hibernate flushes 11 %. Median CPU 5.4 ms and 196 KB allocated  | **Noise**: A 5.8 ms delete (BankAccountResource.java:177) has no surprising phase. | **Noise**: web/rest/BankAccountResource.java:40 — The mapped route or expected validation exception is already described elsewhere, and its small timing or duplicate error gives … |
| `jhipster/hidden/27ba89d1` | reason: route-time-breakdown: its warm median is under # ms, and authorization takes under # % of its time and under # decisions a request, so it is not prominent. | `route-time-breakdown` | `GET /api/authenticate`: warm median 2.9 ms over 25 requests; Other filters 39 %, Response write 24 %, Handler, other work 19 %. Median CPU 2.7 ms and 148 KB allocated per request. | **Noise**: A 2.9 ms authentication check (AuthenticateController.java:74) is not worth reading. | **Noise**: web/rest/AuthenticateController.java:74 — The mapped route or expected validation exception is already described elsewhere, and its small timing or duplicate error giv… |
| `jhipster/hidden/6b9f27ba` | reason: exception-hotspots: it was recorded only behind #xx and #xx responses: errors behind #xx responses reports the #xx ones. | `exception-hotspots` | `POST /api/account/reset-password/init` recorded `MailSendException` in 13 of 26 requests (13 occurrences). | **Noise**: The MailSendException on the reset route repeats what the errors-behind-2xx row already reports for the same requests (MailService.java:79-81). | **Noise**: web/rest/AccountResource.java:165 — The route intentionally rejects invalid credentials, validation errors, or duplicate data, so the 4xx exception is already an expec… |
| `jhipster/hidden/7a099409` | seeded draw | `route-time-breakdown` | `PATCH /api/bank-accounts/{id}`: warm median 7.6 ms over 12 requests; Handler, other work 52 %, Other filters 14 %, Response write 12 %. Median CPU 7.6 ms and 275 KB allocated per  | **Noise**: A 7.6 ms partial update (BankAccountResource.java:107) has nothing notable. | **Noise**: web/rest/BankAccountResource.java:40 — The mapped route or expected validation exception is already described elsewhere, and its small timing or duplicate error gives … |
| `jhipster/hidden/a1d26881` | seeded draw | `route-time-breakdown` | `GET /api/bank-accounts/{id}`: warm median 5.9 ms over 25 requests; Handler, other work 55 %, Other filters 19 %, Response write 14 %. Median CPU 5.9 ms and 485 KB allocated per re | **Noise**: A 5.9 ms single-account read (BankAccountResource.java:164) has nothing notable. | **Noise**: web/rest/BankAccountResource.java:40 — The mapped route or expected validation exception is already described elsewhere, and its small timing or duplicate error gives … |
| `jhipster/hidden/d16969e2` | reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses. | `exception-hotspots` | `POST /api/bank-accounts` recorded `MethodArgumentNotValidException` in 13 of 26 requests (13 occurrences). | **Noise**: The MethodArgumentNotValidException is the intended 400 for the traffic's invalid bank-account body (BankAccountResource.java:52) and is counted in the 4xx summary row. | **Noise**: web/rest/BankAccountResource.java:40 — The route intentionally rejects invalid credentials, validation errors, or duplicate data, so the 4xx exception is already an ex… |
| `jhipster/hidden/d25babcd` | seeded draw | `route-time-breakdown` | `GET /**`: warm median 4.1 ms over 12 requests; Handler, other work 37 %, Other filters 29 %, Response write 22 %. Median CPU 4.0 ms and 1004 KB allocated per request. First reques | **Noise**: GET /** is the 4.1 ms static-resource 404 for the traffic's probe of /management/metrics, which is not exposed (application.yml:31-32), so nothing here matters. | **Noise**: web/filter/SpaWebFilter.java:16 — The mapped route or expected validation exception is already described elsewhere, and its small timing or duplicate error gives no us… |

### kafka: 3 sampled, 0 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `kafka/hidden/08a0a568` | all hidden rows | `route-time-breakdown` | `POST /orders`: warm median 1.2 ms over 199 requests; Handler, other work 60 %, Response write 21 %, Other filters 19 %. Median CPU 1.1 ms and 33 KB allocated per request. First re | **Noise**: A 1.2 ms POST that hands the order to KafkaTemplate.send without waiting (order-service/.../OrderController.java:37-43) has no phase worth reading. | **Noise**: order-service/src/main/java/pl/piomin/order/controller/OrderController.java:38 — The order route publishes to or reads from Kafka Streams in about two milliseconds, so… |
| `kafka/hidden/96444689` | all hidden rows | `heap-growth-after-gc` | 1 collection reclaimed old-generation space in this run; a trend needs 3. | **Noise**: A single old-generation reclamation in a short run says nothing about the stock service, whose state is a fixed H2 product table (StockApp.java:41-45). | **Noise**: stock-service/src/main/java/pl/piomin/stock/StockApp.java:29 — The stock consumer only had one reclaiming collection, so a memory trend cannot be inferred from this Ka… |
| `kafka/hidden/be122012` | all hidden rows | `route-time-breakdown` | `GET /orders`: warm median 2.1 ms over 41 requests; Handler, other work 60 %, Response write 29 %, Other filters 12 %. Median CPU 1.8 ms and 224 KB allocated per request. First req | **Noise**: A 2.1 ms read of the Kafka Streams store (order-service/.../OrderController.java:51-62) is unremarkable. | **Noise**: order-service/src/main/java/pl/piomin/order/controller/OrderController.java:54 — The order route publishes to or reads from Kafka Streams in about two milliseconds, so… |

### petclinic: 10 sampled, 0 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `petclinic/hidden/151407e4` | slowest hidden route | `route-time-breakdown` | `GET /owners`: warm median 4.2 ms over 549 requests; Response write 84 %, Handler, other work 12 %, Other filters 2 %. Median CPU 4.1 ms and 2.8 MB allocated per request. | **Noise**: A 4.2 ms paged owner search page (OwnerController.java:94) is mostly template rendering and has nothing to act on. | **Noise**: owner/OwnerController.java:94 — This controller serves the mapped route in only a few milliseconds, so a phase breakdown or small memory sample is not worth changing c… |
| `petclinic/hidden/52bccce7` | seeded draw | `gc-inflated-latency` | A stop-the-world pause completed during 3 of `POST /owners/{ownerId}/pets/{petId}/visits/new`'s 37 slowest requests (8 %), against 0 % of its other 331 requests; those pauses total | **Noise**: Three pauses totalling 8 ms across 368 visit submissions (VisitController.java:70) are too small to matter. | **Noise**: owner/PetController.java:100 — The route handler is real, but these few pauses total only milliseconds across hundreds of calls and do not justify a code change. |
| `petclinic/hidden/5ead16c9` | reason: heap-growth-after-gc: garbage collection and heap rows are reached from the memory panel rather than listed by default. | `heap-growth-after-gc` | 2 collections reclaimed old-generation space in this run; a trend needs 3. | **Noise**: Two old-generation reclamations cannot show a trend, and the app keeps no growing state besides the small vets cache (VetRepository.java:45). | **Noise**: owner/OwnerController.java:94 — Only two old-generation collections were observed, insufficient to infer a growth trend from this app run. |
| `petclinic/hidden/868f3d4d` | seeded draw | `gc-inflated-latency` | A stop-the-world pause completed during 4 of `POST /owners/{ownerId}/pets/new`'s 37 slowest requests (11 %), against 0 % of its other 329 requests; those pauses total 7.0 ms. | **Noise**: Four pauses totalling 7 ms over 366 pet creations (PetController.java:107) are negligible. | **Noise**: owner/PetController.java:100 — The route handler is real, but these few pauses total only milliseconds across hundreds of calls and do not justify a code change. |
| `petclinic/hidden/ab2c192c` | seeded draw | `route-time-breakdown` | `GET /owners/{ownerId}/edit`: warm median 2.7 ms over 183 requests; Response write 65 %, Handler, other work 23 %, Other filters 11 %. Median CPU 2.7 ms and 1.8 MB allocated per re | **Noise**: A 2.7 ms owner edit form (OwnerController.java:148) is unremarkable. | **Noise**: owner/OwnerController.java:94 — This controller serves the mapped route in only a few milliseconds, so a phase breakdown or small memory sample is not worth changing c… |
| `petclinic/hidden/ae3cba5d` | reason: gc-inflated-latency: garbage collection and heap rows are reached from the memory panel rather than listed by default. | `gc-inflated-latency` | A stop-the-world pause completed during 2 of `GET /owners/{ownerId}/edit`'s 19 slowest requests (11 %), against 0 % of its other 164 requests; those pauses total 6.0 ms. | **Noise**: Two pauses totalling 6 ms on the owner edit form (OwnerController.java:148) do not explain any latency worth chasing. | **Noise**: owner/OwnerController.java:94 — The route handler is real, but these few pauses total only milliseconds across hundreds of calls and do not justify a code change. |
| `petclinic/hidden/cd939c68` | seeded draw | `route-time-breakdown` | `GET /owners/{ownerId}/pets/{petId}/edit`: warm median 3.8 ms over 183 requests; Response write 60 %, Handler, other work 31 %, Other filters 8 %. Median CPU 3.7 ms and 2.0 MB allo | **Noise**: The 3.8 ms breakdown of the pet edit page (PetController.java:86) adds nothing beyond the lazy-SQL rows and shows the cost is small. | **Noise**: owner/PetController.java:100 — This controller serves the mapped route in only a few milliseconds, so a phase breakdown or small memory sample is not worth changing co… |
| `petclinic/hidden/e44d6dea` | seeded draw | `route-time-breakdown` | `GET /oups`: warm median 0.5 ms over 183 requests; Handler, other work 67 %, Other filters 33 %. Median CPU 0.5 ms and 85 KB allocated per request. | **Noise**: A 0.5 ms deliberate crash endpoint (CrashController.java:31) has no time worth splitting. | **Noise**: system/CrashController.java:31 — The crash controller explicitly throws the exception as a demonstration, so the repeated 500s are intentional rather than a defect. |
| `petclinic/hidden/f6dc433c` | seeded draw | `route-time-breakdown` | `GET /vets.html`: warm median 3.4 ms over 183 requests; Response write 82 %, Handler, other work 11 %, Other filters 7 %. Median CPU 3.3 ms and 3.2 MB allocated per request. | **Noise**: A 3.4 ms cached vets page (VetController.java:45, VetRepository.java:45) is unremarkable. | **Noise**: vet/VetController.java:45 — This controller serves the mapped route in only a few milliseconds, so a phase breakdown or small memory sample is not worth changing code … |
| `petclinic/hidden/f79c672d` | seeded draw | `gc-inflated-latency` | A stop-the-world pause completed during 6 of `GET /owners/{ownerId}`'s 55 slowest requests (11 %), against 0 % of its other 494 requests; those pauses total 21 ms. | **Noise**: Six young pauses totalling 21 ms over 549 owner page views (OwnerController.java:178) are negligible. | **Noise**: owner/OwnerController.java:94 — The route handler is real, but these few pauses total only milliseconds across hundreds of calls and do not justify a code change. |

### super-heroes: 10 sampled, 0 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `super-heroes/hidden/13e51cf3` | reason: heap-growth-after-gc: garbage collection and heap rows are reached from the memory panel rather than listed by default. | `heap-growth-after-gc` | 1 collection reclaimed old-generation space in this run; a trend needs 3. | **Noise**: One old-generation reclamation in a two-minute CRUD run (VillainService.java:30) shows nothing about the app. | **Noise**: rest/VillainResource.java:59 — Only one old-generation reclamation was recorded during the villain service run, so this does not establish heap growth. |
| `super-heroes/hidden/1f359072` | seeded draw | `route-time-breakdown` | `PUT /api/villains/{id}`: warm median 7.4 ms over 41 requests; Handler, other work 52 %, SQL 41 %, Hibernate flushes 4 %. First request 22 ms (cold). | **Noise**: A 7.4 ms full update (VillainResource.java:82, VillainService.java:84) has no surprising phase. | **Noise**: rest/VillainResource.java:59 — The villain resource serves this route in a few milliseconds with routine reads or writes, making its phase percentages unimportant. |
| `super-heroes/hidden/2ea3ae37` | slowest hidden route | `route-time-breakdown` | `GET /`: warm median 7.0 ms over 43 requests; Handler, other work 52 %, SQL 33 %, Other filters 7 %. First request 311 ms (cold). | **Noise**: The 7 ms UI page with its name filter query (UIResource.java:34) is unremarkable. | **Noise**: rest/UIResource.java:34 — The villain resource serves this route in a few milliseconds with routine reads or writes, making its phase percentages unimportant. |
| `super-heroes/hidden/8c617d97` | seeded draw | `route-time-breakdown` | `GET /api/villains/{id}`: warm median 4.0 ms over 83 requests; Handler, other work 41 %, SQL 38 %, Other filters 17 %. First request 13 ms (cold). | **Noise**: A 4 ms lookup by id (VillainResource.java:59) has nothing to act on. | **Noise**: rest/VillainResource.java:59 — The villain resource serves this route in a few milliseconds with routine reads or writes, making its phase percentages unimportant. |
| `super-heroes/hidden/959282a8` | reason: route-time-breakdown: it has fewer than # warm requests, so where its time goes is not known yet, and fewer than # of them or a warm median under # ms. | `route-time-breakdown` | `GET /q/health/ready`: 0 of 5 warm requests needed. First request 2.9 ms (cold). | **Noise**: A readiness probe called once in warm-up (q/health/ready, served by quarkus-smallrye-health at pom.xml:77) has no time worth knowing. | **Noise**: rest/VillainResource.java:59 — The health or OpenAPI framework endpoint lacks a marked handler and has negligible warm latency, so profiling it adds no value. |
| `super-heroes/hidden/a649fc2b` | seeded draw | `route-time-breakdown` | `GET /q/health`: warm median 2.0 ms over 5 requests; all of them reached no handler BootUI marks (a framework endpoint such as /q/health, a Vert.x route or static resource, or a re | **Noise**: A 2 ms framework health endpoint (pom.xml:77) needs no phase split. | **Noise**: rest/VillainResource.java:59 — The health or OpenAPI framework endpoint lacks a marked handler and has negligible warm latency, so profiling it adds no value. |
| `super-heroes/hidden/ba2e94e3` | reason: route-time-breakdown: its time is not split into phases, since its requests reached no handler bootui marks or named nothing at all, and its warm median is under # ms. | `route-time-breakdown` | `GET /q/openapi`: warm median 0.2 ms over 5 requests; all of them reached no handler BootUI marks (a framework endpoint such as /q/health, a Vert.x route or static resource, or a r | **Noise**: A 0.2 ms OpenAPI document (pom.xml:68) is irrelevant. | **Noise**: rest/VillainResource.java:59 — The health or OpenAPI framework endpoint lacks a marked handler and has negligible warm latency, so profiling it adds no value. |
| `super-heroes/hidden/daf6376e` | seeded draw | `route-time-breakdown` | `POST /api/villains`: warm median 4.4 ms over 41 requests; Handler, other work 59 %, SQL 23 %, Hibernate flushes 9 %. First request 84 ms (cold). | **Noise**: A 4.4 ms creation (VillainResource.java:73, VillainService.java:75) has no notable phase. | **Noise**: rest/VillainResource.java:59 — The villain resource serves this route in a few milliseconds with routine reads or writes, making its phase percentages unimportant. |
| `super-heroes/hidden/e7fd48a2` | reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses. | `exception-hotspots` | `POST /api/villains` recorded `ResteasyReactiveViolationException` in 21 of 42 requests (21 occurrences). | **Noise**: The violation is the intended 400 for the traffic's invalid villain (VillainService.java:75 @Valid) and is already in the 4xx summary row. | **Noise**: rest/VillainResource.java:59 — The villain resource deliberately returns 404 for a missing entity and rejects invalid creations with 400, so these client errors are ex… |
| `super-heroes/hidden/f3cf2bff` | seeded draw | `route-time-breakdown` | `GET /api/villains`: warm median 5.4 ms over 62 requests; SQL 49 %, Handler, other work 33 %, Other filters 11 %. First request 28 ms (cold). | **Noise**: A 5.4 ms villain list (VillainResource.java:47, VillainService.java:46) is unremarkable. | **Noise**: rest/VillainResource.java:59 — The villain resource serves this route in a few milliseconds with routine reads or writes, making its phase percentages unimportant. |

### timeless: 10 sampled, 0 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `timeless/hidden/25597938` | seeded draw | `exception-hotspots` | `POST /api/sign-up` recorded `ResteasyReactiveViolationException` in 40 of 120 requests (40 occurrences). | **Noise**: The violation is the intended 400 for the traffic's invalid sign-up body (SignUpResource.java:29) and is counted in the 4xx summary. | **Noise**: presentation/SignUpResource.java:50 — The resource deliberately restricts unauthorized access or rejects invalid sign-up, making these 4xx exceptions expected. |
| `timeless/hidden/39de18ae` | seeded draw | `route-time-breakdown` | `GET /api/records`: warm median 5.4 ms over 120 requests; SQL 43 %, Handler, other work 36 %, Other filters 19 %. Median CPU 2.5 ms and 243 KB allocated per request. First request  | **Noise**: A 5.4 ms paged record list (RecordResource.java:76-80) is unremarkable. | **Noise**: presentation/RecordResource.java:87 — The mapped small CRUD request has ordinary SQL, rendering or filter time and no actionable latency defect. |
| `timeless/hidden/3baa0b75` | seeded draw | `route-time-breakdown` | `POST /api/sign-up`: warm median 3.1 ms over 119 requests; Handler, other work 93 %, SQL 5 %, Hibernate flushes 1 %. Median CPU 2.1 ms and 110 KB allocated per request. First reque | **Noise**: The 3.1 ms median reflects mostly rejected or duplicate sign-ups (SignUpResource.java:29-42), and nothing in it calls for action. | **Noise**: presentation/SignUpResource.java:50 — The mapped small CRUD request has ordinary SQL, rendering or filter time and no actionable latency defect. |
| `timeless/hidden/5f30e1ea` | reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses. | `exception-hotspots` | `GET /api/records` recorded `UnauthorizedException` in 40 of 160 requests (40 occurrences). | **Noise**: The UnauthorizedException is the intended 401 for anonymous calls to the @Authenticated record resource (RecordResource.java:37), already counted in the 4xx row. | **Noise**: presentation/RecordResource.java:87 — The records mapper dereferences each transaction and category without null protection, so a failing seeded record calls for input… |
| `timeless/hidden/7109b216` | seeded draw | `route-time-breakdown` | `GET /api/users/{id}`: warm median 2.0 ms over 79 requests; Other filters 49 %, SQL 29 %, Handler, other work 18 %. Median CPU 1.1 ms and 172 KB allocated per request. First reques | **Noise**: A 2 ms profile read (UserResource.java:51-53) has nothing to act on. | **Noise**: presentation/UserResource.java:55 — The mapped small CRUD request has ordinary SQL, rendering or filter time and no actionable latency defect. |
| `timeless/hidden/7b1803e6` | reason: route-time-breakdown: it has fewer than # warm requests, so where its time goes is not known yet, and fewer than # of them or a warm median under # ms. | `route-time-breakdown` | `GET /q/health/live`: 0 of 5 warm requests needed. First request 29 ms (cold). | **Noise**: A liveness probe from quarkus-smallrye-health (timeless-api/pom.xml:109) hit only at warm-up has no time worth knowing. | **Noise**: presentation/RecordResource.java:76 — The uninstrumented Quarkus health endpoint is only a few milliseconds or has too few warm samples. |
| `timeless/hidden/b9445b35` | reason: route-time-breakdown: its time is not split into phases, since its requests reached no handler bootui marks or named nothing at all, and its warm median is under # ms. | `route-time-breakdown` | `GET /q/health`: warm median 2.3 ms over 39 requests; all of them reached no handler BootUI marks (a framework endpoint such as /q/health, a Vert.x route or static resource, or a r | **Noise**: A 2.3 ms framework health check from quarkus-smallrye-health (timeless-api/pom.xml:109) needs no phase split. | **Noise**: presentation/RecordResource.java:76 — The uninstrumented Quarkus health endpoint is only a few milliseconds or has too few warm samples. |
| `timeless/hidden/c365c174` | reason: gc-inflated-latency: garbage collection and heap rows are reached from the memory panel rather than listed by default. | `gc-inflated-latency` | A stop-the-world pause completed during 2 of `POST /api/messages`'s 12 slowest requests (17 %), against 0 % of its other 107 requests; those pauses total 50 ms. | **Noise**: Two pauses totalling 50 ms on ~316 ms AI-bound requests (MessageResource.java:87) do not explain the latency, which the breakdown attributes to model calls. | **Noise**: presentation/MessageResource.java:87 — The message handler waits for a model-assisted response, so learning that AI calls occupy 96 percent of its 316 ms latency infor… |
| `timeless/hidden/caa8eff2` | slowest hidden route | `route-time-breakdown` | `POST /api/records`: warm median 6.0 ms over 159 requests; Handler, other work 48 %, SQL 39 %, Other filters 12 %. Median CPU 2.5 ms and 221 KB allocated per request. First request | **Noise**: A 6 ms record creation in its own transaction (RecordResource.java:61-72) is unremarkable. | **Noise**: presentation/RecordResource.java:62 — The mapped small CRUD request has ordinary SQL, rendering or filter time and no actionable latency defect. |
| `timeless/hidden/e4c9eef0` | seeded draw | `exception-hotspots` | `GET /api/users/{id}` recorded `UnauthorizedException` in 40 of 119 requests (40 occurrences). | **Noise**: The UnauthorizedException is the intended 401 for anonymous calls to the @Authenticated user resource (UserResource.java:21), already in the 4xx row. | **Noise**: presentation/UserResource.java:55 — The resource deliberately restricts unauthorized access or rejects invalid sign-up, making these 4xx exceptions expected. |

### webflux-gateway: 10 sampled, 1 judged Actionable by either reviewer

| Row | Stratum | Kind | Sentence | r1 | r2 |
| --- | --- | --- | --- | --- | --- |
| `webflux-gateway/hidden/0e7f9432` | seeded draw | `route-time-breakdown` | `GET /api/admin/users/{login}`: warm median 7.2 ms over 71 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU | **Noise**: A 7.2 ms admin user read (UserResource.java:233) has nothing worth reading. | **Noise**: web/rest/UserResource.java:235 — The resource has a small warm median and no WebFlux phase attribution, so this hidden timing row gives no change worth making. |
| `webflux-gateway/hidden/1298342a` | seeded draw | `route-time-breakdown` | `DELETE /api/authorities/{id}`: warm median 6.6 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU | **Noise**: A 6.6 ms authority delete (AuthorityResource.java:114) is unremarkable. | **Noise**: web/rest/AuthorityResource.java:102 — The resource has a small warm median and no WebFlux phase attribution, so this hidden timing row gives no change worth making. |
| `webflux-gateway/hidden/12fef9cc` | seeded draw | `route-time-breakdown` | `DELETE /api/admin/users/{login}`: warm median 9.3 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median  | **Noise**: A 9.3 ms user deletion over R2DBC (UserResource.java:249) carries nothing actionable. | **Noise**: web/rest/UserResource.java:154 — The resource has a small warm median and no WebFlux phase attribution, so this hidden timing row gives no change worth making. |
| `webflux-gateway/hidden/13b00168` | seeded draw | `route-time-breakdown` | `PUT /api/admin/users/{login}`: warm median 8.9 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU | **Noise**: The 8.9 ms timing of a PUT that always fails validation (UserResource.java:154-167) adds nothing beyond the exception row. | **Noise**: web/rest/UserResource.java:154 — The resource has a small warm median and no WebFlux phase attribution, so this hidden timing row gives no change worth making. |
| `webflux-gateway/hidden/206930c5` | seeded draw | `exception-hotspots` | `GET /api/authorities/{id}` recorded `ResponseStatusException` in 24 of 48 requests (24 occurrences). | **Noise**: The ResponseStatusException is the intended 404 for the traffic's missing-authority probe (AuthorityResource.java:100) and is already in the 4xx summary. | **Noise**: web/rest/AuthorityResource.java:102 — The controller deliberately rejects missing or duplicate resources, or this mail failure is already in the default list. |
| `webflux-gateway/hidden/25818fa9` | reason: exception-hotspots: it was recorded only behind #xx responses, which are usually intended, and is counted in the row behind #xx responses. | `exception-hotspots` | `PUT /api/admin/users/{login}` recorded `EmailAlreadyUsedException` in 24 of 24 requests (24 occurrences). | **Actionable**: Every PUT /api/admin/users/{login} without a body id fails with EmailAlreadyUsedException for the user's own email, because updateUser ignores the path login and treat… | **Noise**: web/rest/UserResource.java:154 — The controller deliberately rejects missing or duplicate resources, or this mail failure is already in the default list. |
| `webflux-gateway/hidden/716264e7` | reason: exception-hotspots: it was recorded only behind #xx and #xx responses: errors behind #xx responses reports the #xx ones. | `exception-hotspots` | `POST /api/admin/users` recorded `MailSendException` in 24 of 48 requests (24 occurrences). | **Noise**: The MailSendException repeats what the errors-behind-2xx row already reports for the same creation requests (MailService.java:79-80). | **Noise**: web/rest/UserResource.java:132 — The controller deliberately rejects missing or duplicate resources, or this mail failure is already in the default list. |
| `webflux-gateway/hidden/bafea04a` | seeded draw | `route-time-breakdown` | `GET /management/info`: warm median 4.3 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 4.1 ms  | **Noise**: A 4.3 ms public info endpoint (SecurityConfiguration.java:88) needs no attention. | **Noise**: config/SecurityConfiguration.java:86 — The framework management endpoint finishes in only a few milliseconds despite the missing WebFlux phase marks. |
| `webflux-gateway/hidden/d790b882` | slowest hidden route | `route-time-breakdown` | `GET /management/health`: warm median 5.5 ms over 24 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 5.6 m | **Noise**: A 5.5 ms public health endpoint (SecurityConfiguration.java:86) needs no attention. | **Noise**: config/SecurityConfiguration.java:86 — The framework management endpoint finishes in only a few milliseconds despite the missing WebFlux phase marks. |
| `webflux-gateway/hidden/fc1557c2` | seeded draw | `route-time-breakdown` | `GET /api/admin/users`: warm median 4.7 ms over 71 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 4.3 ms  | **Noise**: A 4.7 ms paged admin user list (UserResource.java:200) is unremarkable. | **Noise**: web/rest/UserResource.java:235 — The resource has a small warm median and no WebFlux phase attribution, so this hidden timing row gives no change worth making. |

## 4. Recall

Outcomes from [`recall.csv`](validation/m4-20-rerun/recall.csv). Known misses are found in the default list, found only
in a hidden row, an honest gap, or missed; counterexamples are respected or violated. Rows name the worksheet or
observation ids that support the outcome.

### petclinic

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| PC-1 | Known miss | The pet-type selector re-runs the pet-types query 5 to 6 times per form render, from PetTypeFormatter.parse (PetTypeFormatter.java:52-53, fragments/selectField.html:13-14). | **found-default** | `petclinic/fact/d1ce3167`, `petclinic/fact/41f7e90d`, `petclinic/fact/eb5bb5fb`, `petclinic/fact/12b74f6b`, `petclinic/fact/7d8b8716`, `petclinic/fact/05eafb9a`, `petclinic/fact/0ec63efe`, `petclinic/fact/173c24f4` | Default repeated-select facts show the pet-types query running 5 or more times on each form route, and the lazy-SQL facts name PetTypeFormatter.parse during view render. [3 of 3 advisors] |
| PC-2 | Known miss | The owner list renders one pagination link per page (owners/ownersList.html:34-37), so GET /owners has a heavy tail that grows with the owner table. | **missed** | — | The only owners row is a hidden 4.2 ms breakdown that never mentions one pagination link per page or a tail that grows with the owner table. [2 of 3 advisors (opus-5 said found-hidden)] |
| PC-3 | Known miss | Tomcat logs an ERROR for every GET /oups that no request owns, 972 in the first run. | **missed** | — | Framework warnings were evaluated with no findings, and the eviction note about request-less ERROR logs does not identify the unowned Tomcat errors for GET /oups. [2 of 3 advisors (gpt-6.1-sol said honest-gap)] |
| PC-4 | Known miss | Every write route is anonymous, since the application has no security at all; a check that needs authorization data must say it saw none rather than report a clean result. | **honest-gap** | `petclinic/honesty/1b89b7cb`, `petclinic/honesty/bad21e50` | Both authorization checks are INSUFFICIENT with no eligible work rather than a clean pass, though the reason cites eviction instead of the absence of security. [3 of 3 advisors] |
| PC-5 | Known miss | In the agent run, change.patch changes Owner.getPet(String), which nothing calls: changed-code-not-executed must report it. | **found-default** | `petclinic+agent/fact/fe60e045` | The default fact says one changed method of Owner was not executed, and the comparison shows that method is getPet. [3 of 3 advisors] |
| PC-6 | Known miss | In the agent run, change.patch changes Vet.addSpecialty, which nothing calls: changed-code-not-executed must report it. | **found-default** | `petclinic+agent/fact/e1a21005` | The default fact says one changed method of Vet was not executed, and the comparison shows that method is addSpecialty. [3 of 3 advisors] |
| PC-C1 | Counterexample | GET /oups throws on purpose to show the error page (CrashController.java:31-34). | **respected** | — | GET /oups throws on purpose; both reviewers judged its hotspot (petclinic/fact/b1f1e46f) Noise, and the protocol counts a counterexample as violated only when a fact on it is adjudicated Misleading. [protocol override of a 2 of 3 'violated' majority (opus-5 said respected)] |
| PC-C2 | Counterexample | The repeated pet-type SELECTs come from a view formatter, not lazy loading: open-in-view and fetch joins do not apply (application.properties:11). | **respected** | — | The default lazy-SQL fact attributes the repeated types query to PetTypeFormatter.parse and does not prescribe open-in-view or a fetch join. [3 of 3 advisors] |
| PC-C3 | Counterexample | Vets are cached (VetRepository.java:44-46); their reads are not an N+1. | **respected** | — | No row calls the cached vet reads an N+1; the vets breakdowns are only hidden timing rows. [3 of 3 advisors] |
| PC-C4 | Counterexample | In the agent run, change.patch changes Owner.addVisit, which every valid visit runs: changed-code-not-executed must not report it. | **respected** | — | The Owner fact reports one unexecuted changed method, and the comparison marks addVisit executed. [3 of 3 advisors] |

### jhipster

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| JH-1 | Known miss | POST /api/admin/users answers 201 while its @Async activation email fails against the SMTP server that does not run, and the exception is swallowed (UserResource.java:119, MailService.java:54-80, application-dev.yml:51-53). | **found-default** | `jhipster/fact/1c61d2f0` | The default fact says every successful user creation answered 2xx while a task it handed to another thread failed. [3 of 3 advisors] |
| JH-2 | Known miss | The dev profile's DEBUG logging and LoggingAspect dominate handler time and allocation (application-dev.yml:18, LoggingAspectConfiguration.java:13). | **missed** | — | The timing facts say handler work dominates but never name DEBUG logging or LoggingAspect. [3 of 3 advisors] |
| JH-3 | Known miss | POST /api/account/reset-password/init with a JSON-quoted email fails validation and never reaches the reset logic (AccountResource.java:162). | **found-hidden** | `jhipster/hidden/1a1d80e9` | The hidden ConstraintViolationException hotspot is the validation failure on this route, though it does not mention the JSON-quoted email. [3 of 3 advisors] |
| JH-4 | Known miss | A valid POST /api/account/reset-password/init answers 200 while its @Async reset email fails against the SMTP server that does not run (AccountResource.java:162-173, MailService.java:115-118). Its request is new in the rerun's traffic. | **found-default** | `jhipster/fact/c25fff20` | The default fact says every successful reset init answered 2xx while a task handed to another thread failed. [3 of 3 advisors] |
| JH-C1 | Counterexample | BCrypt on authentication and user creation is deliberate cost (SecurityConfiguration.java:41). | **respected** | — | The slow authenticate and create-user facts never name BCrypt or say the deliberate hash cost should be removed. [3 of 3 advisors] |
| JH-C2 | Counterexample | Invalid bodies, bad credentials, anonymous calls, and missing ids answer the intended 400, 401, and 404. | **respected** | — | Intended 400, 401, and 404 results are not default facts; BadCredentials is unlisted as behind 4xx and usually intended. [3 of 3 advisors] |
| JH-C3 | Counterexample | Actuator requests and requests Spring Security rejects reach no application handler: their time is not application code. | **respected** | — | Actuator rows say those requests reached no marked handler, so their time is not split into application phases. [3 of 3 advisors] |

### super-heroes

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| SH-1 | Known miss | DELETE /api/villains (not exercised) loads every villain and deletes them one by one (VillainService.java:130-136); the routes the traffic never reaches must be listed as not exercised. | **honest-gap** | — | The not-exercised list names both villain routes, but that list is a limitation without a worksheet fact, so it cannot count as found in the default list. [2 of 3 advisors (grok-4.7 said found-default)] |
| SH-2 | Known miss | GET /api/villains is not paginated, and GET /api/villains/random runs a count and an offset query per request (VillainService.java:46-57, Villain.java:36-51). | **missed** | — | The hidden timing rows show SQL share only and do not say the list is unpaginated or that random runs a count plus offset. [2 of 3 advisors (opus-5 said found-hidden)] |
| SH-3 | Known miss | GET / renders the complete villain list rather than a bounded page (UIResource.java:31-40). | **missed** | — | The hidden GET / breakdown does not say the page renders the complete villain list. [2 of 3 advisors (opus-5 said found-hidden)] |
| SH-4 | Known miss | In the agent run, change.patch changes deleteAllVillains, which nothing runs: changed-code-not-executed must report it. | **found-default** | `super-heroes+agent/fact/f379ff0d` | The default fact says one changed method of VillainService was not executed, and the comparison shows deleteAllVillains never ran. [3 of 3 advisors] |
| SH-5 | Known miss | In the agent run, change.patch changes VillainResource.deleteAllVillains (DELETE /api/villains), which the traffic never calls: changed-code-not-executed must report it. | **found-default** | `super-heroes+agent/fact/3ee46b2c` | The default fact says one changed method of VillainResource was not executed, and the comparison shows deleteAllVillains never ran. [3 of 3 advisors] |
| SH-C1 | Counterexample | Invalid villains answer 400 through bean validation, and missing or non-numeric ids answer 404, on purpose. | **respected** | — | The bean-validation and not-found exceptions are hidden or filed under behind-4xx, not presented as default defects. [3 of 3 advisors] |
| SH-C2 | Counterexample | Health, OpenAPI, and hello endpoints are framework or trivial endpoints, not application performance problems. | **respected** | — | Health and OpenAPI rows say they reached no application handler, and hello is an unlisted insufficient sample rather than a performance finding. [3 of 3 advisors] |
| SH-C3 | Counterexample | Blocking endpoints run on virtual threads (@RunOnVirtualThread), not on the event loop. | **respected** | — | event-loop-blocking was evaluated with no findings, so blocking on the event loop was not reported. [3 of 3 advisors] |
| SH-C4 | Counterexample | In the agent run, change.patch changes findAllVillainsHavingName, which the traffic runs: changed-code-not-executed must not report it. | **respected** | — | The VillainService fact reports one unexecuted method, and the comparison marks findAllVillainsHavingName executed. [3 of 3 advisors] |

### webflux-gateway

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| WF-1 | Known miss | GET /api/admin/users loads the whole user and authority join over R2DBC and pages, sorts, and limits it in memory (UserRepository.java:100-114); BootUI cannot see R2DBC, and must say so. | **honest-gap** | `webflux-gateway/honesty/1b7fc08c`, `webflux-gateway/hidden/fc1557c2` | The repeated-selects check is UNAVAILABLE and states that R2DBC database access is not recorded. [3 of 3 advisors] |
| WF-2 | Known miss | Each successful user creation hides a failed activation email behind its 201 (UserResource.java:132, MailService.java:53-56). | **found-default** | `webflux-gateway/fact/18d872b0` | The default fact says every successful user creation answered 2xx and recorded an exception, without naming the activation email. [3 of 3 advisors] |
| WF-3 | Known miss | Every PUT /api/admin/users/{login} fails, since the body carries no id and the path's login is not used (UserResource.java:154-167). | **found-hidden** | `webflux-gateway/hidden/25818fa9` | The hidden hotspot says every PUT throws EmailAlreadyUsedException, which that missing-id path produces, but it does not state the missing id. [2 of 3 advisors (gpt-6.1-sol said missed)] |
| WF-4 | Known miss | Gateway routes to a service that does not run answer 500 rather than a gateway error or fallback (application.yml:116-135). | **found-default** | `webflux-gateway/fact/726b62eb`, `webflux-gateway/fact/54542269` | Default hotspots report a connect exception on every request to both absent-service routes. [3 of 3 advisors] |
| WF-5 | Known miss | Declared routes the traffic never reaches must be listed as not exercised (at least seven in the first run). | **honest-gap** | — | The limitation says declared routes could not be read, so unreached routes are not listed and that does not mean they were exercised. [3 of 3 advisors] |
| WF-C1 | Counterexample | Anonymous readiness and the deliberate 401 probes are configured behaviour (SecurityConfiguration.java:81-82). | **respected** | — | No default fact presents the anonymous readiness or 401 probes as a data-reach or authorization defect. [3 of 3 advisors] |
| WF-C2 | Counterexample | WebFlux marks no phases: time is not attributed to application code it cannot see. | **respected** | — | WebFlux timing rows say no phases are marked and where the time went is not known, so it is not attributed to unseen application code. [3 of 3 advisors] |

### kafka

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| K-1 | Known miss | The payment and stock listeners read in a read-only transaction and save in a second one, with a repeated select during the merge (payment and stock OrderManageService.java:24-39). | **missed** | — | Payment and stock evaluated repeated selects and split transactions over hundreds of consumed messages and reported no findings. [3 of 3 advisors] |
| K-2 | Known miss | Listener-only services do SQL, transaction, and ORM work per consumed message that must be visible per execution. | **missed** | — | No row shows per-message SQL, transaction, or ORM work, and the listener time check only says no eligible work despite 400 retained messages. [2 of 3 advisors (gpt-6.1-sol said honest-gap)] |
| K-3 | Known miss | Kafka Streams consumes, joins, and materializes the order table in order-service (OrderApp.java:68-94), which the journal does not record; a coverage line must say so. | **honest-gap** | — | The order-service limitation states that BootUI does not record Kafka Streams processing. [3 of 3 advisors] |
| K-4 | Known miss | GET /orders scans the whole Kafka Streams store on every request and does not close its KeyValueIterator (OrderController.java:51-61). | **missed** | — | The hidden GET /orders breakdown does not say the handler scans the whole store or leaves the iterator open. [2 of 3 advisors (opus-5 said found-hidden)] |
| K-C1 | Counterexample | Saga rejections for stock or payment are the intended outcome of the traffic. | **respected** | — | No row presents saga stock or payment rejections as a defect. [3 of 3 advisors] |
| K-C2 | Counterexample | POST /orders does not wait for the broker: the asynchronous acknowledgement is not handler time (OrderController.java:37-42). | **respected** | — | The hidden POST /orders breakdown does not treat the asynchronous broker acknowledgement as handler time. [3 of 3 advisors] |

### bookstore

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| BS-1 | Known miss | POST /orders publishes OrderCreatedEvent; two @ApplicationModuleListener handlers run after the commit in their own transactions (OrderEventInventoryHandler reads then saves the stock level), Spring Modulith records each publication in its JDBC registry, and the event is externalized to RabbitMQ (OrderService.java:33-44, OrderEventInventoryHandler.java:19-23). | **honest-gap** | `bookstore/honesty/850374c1` | after-commit-writes is UNAVAILABLE because Modulith's event multicaster means application events are not recorded, and no row names the listeners or RabbitMQ. [2 of 3 advisors (opus-5 said found-default)] |
| BS-2 | Known miss | The URL rules leave /buy, /cart, /update-cart, and GET /products open to anonymous visitors and protect /admin/** by role; an anonymous request never reaches an admin page (WebSecurityConfig.java:33-43). | **missed** | — | Both anonymous checks were evaluated with no findings, and no row states which URL rules are open or that anonymous callers never reach admin. [3 of 3 advisors] |
| BS-C1 | Counterexample | Anonymous cart and catalog access is the intended rule, not a data-reach problem. | **respected** | — | Anonymous cart and catalog access is not reported as a data-reach problem. [3 of 3 advisors] |
| BS-C2 | Counterexample | A customer gets 403 on /admin/**, and an anonymous visitor a redirect to the login form, on purpose. | **respected** | — | No fact presents the admin 403 or login redirect as a defect. [3 of 3 advisors] |
| BS-C3 | Counterexample | An unknown order or product code answers 404 through OrderNotFoundException or ProductNotFoundException, on purpose. | **respected** | — | The not-found exceptions are hidden or behind 4xx and are not default facts on those routes. [3 of 3 advisors] |

### timeless

| Item | Kind | Fact | Outcome | Rows | Note |
| --- | --- | --- | --- | --- | --- |
| TL-1 | Known miss | GET /api/records without page and limit throws a NullPointerException, since RecordResource calls Optional.of on a missing query parameter, and answers 500. | **found-default** | `timeless/fact/01c6181c`, `timeless/fact/637c2a77` | Default facts report NullPointerException and a QuarkusErrorHandler ERROR on GET /api/records. [3 of 3 advisors] |
| TL-2 | Known miss | POST /api/messages is open to anonymous callers at the HTTP level (its comment relies on a network rule): given a phone number it reads the user, calls the AI, and writes records (MessageResource.java). | **missed** | — | anonymous-data-reach was evaluated with no findings, and no row says this route is open and reads or writes user data anonymously. [3 of 3 advisors] |
| TL-3 | Known miss | AI calls dominate POST /api/messages, with two model calls in one request when the getBalance tool runs, and that tool reads every record of the user (GetBalanceTool.java). | **found-default** | `timeless/fact/160673f8` | Default rows show AI calls taking 96 percent of POST /api/messages and up to two model calls in one request, but not the getBalance full read. [timeless/fact/54ac7ea2 dropped because it is adjudicated Misleading; 3 of 3 advisors] |
| TL-4 | Known miss | GET /api/records counts the user's records, reads one page, and then reads the amount and type of every record of the user on every request (RecordResource.getRecords). | **missed** | — | The hidden timing row shows SQL share only and does not describe the count, page, and full amount and type reads. [2 of 3 advisors (opus-5 said found-hidden)] |
| TL-C1 | Counterexample | BCrypt makes POST /api/sign-in slow on purpose. | **respected** | — | The slow sign-in fact attributes time to the handler and does not name BCrypt or say the hash cost should be removed. [3 of 3 advisors] |
| TL-C2 | Counterexample | The AI call runs before QuarkusTransaction.requiringNew, outside any transaction: no transaction is held across it. | **violated** | `timeless/fact/54ac7ea2` | No row says a transaction is held across the AI call, but the protocol counts a counterexample as violated when any fact on its subject (POST /api/messages) is adjudicated Misleading, and timeless/fact/54ac7ea2 was, for its 'grew up to 1.0 times' token wording. [3 of 3 advisors said respected; registered scorer rule] |
| TL-C3 | Counterexample | Panache's blocking JDBC runs on worker threads, not on the Vert.x event loop. | **respected** | — | event-loop-blocking was evaluated with no findings. [3 of 3 advisors] |
| TL-C4 | Counterexample | Anonymous calls to @Authenticated resources answer 401, a duplicate sign-up 409, and another user's profile 403, on purpose. | **respected** | — | The intentional 401, 409, and 403 exceptions are hidden, and the default records fact is the separate null-pointer failure. [3 of 3 advisors] |
