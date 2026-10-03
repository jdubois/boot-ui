# BootUI 2.0 validation report

This report checks whether Runtime Insights is worth reading on applications that were not written for BootUI
([v2 plan](PLAN-v2.md) §2.2 and §2.4). It is filled in from an [early-adopter build](V2-EARLY-ADOPTERS.md) and rerun
before 2.0.0.

**Draft, first run (2026-10-03), for the maintainer's review.** Built from `v2` at `fb4cc07e5` (version `1.19.0`).
Every application ran with its own Maven repository seeded with that build, and each run proved it used it: the
`runtime-insights` endpoint answered, and the resolved `bootui-engine` jar's SHA-256 matched the local build. Two
reviewers on different models judged every observation independently, with the application's source at hand; the
operator who ran each application gathered facts and code pointers but judged nothing.

## Release gates

| Measure | Target | Result |
| --- | --- | --- |
| External validity | On the five applications, ≥ 70 % of observations judged actionable or informative by two reviewers, and none misleading | **Not met.** 16 of 115 observations (14 %) judged actionable or informative by both reviewers; 19 judged misleading by at least one, 6 by both |
| Time to first observation | ≤ 5 minutes from adding the dependency to reading a first observation, with tracing off and no extra property | **Not met on Quarkus.** 0.4 min (PetClinic), 0.8 min (Kafka), 1.9 min (WebFlux), 2.4 min (JHipster, mostly the traffic script), 8.5 min (Quarkus Super Heroes, which needed a workaround to start, see Findings). Several first observations were only `INSUFFICIENT` or latency rows |
| Agent effectiveness | Ten scripted investigations answered correctly from tool output alone, with fewer tool calls than with 1.x tools; five refusal fixtures where the right answer is not to edit | **Not met.** 7 correct and 3 partial with 2.0, against 5 correct, 2 partial, and 3 wrong with 1.x tools; 123 calls against 130 (65 against 73 without `--help`), the saving coming only from the two run-comparison questions; no agent edited code in the five refusal fixtures, but one gave the wrong reason |

The external-validity result also trips §2.3's gate after M3: fewer than 50 % of external-application observations
were judged useful.

## How to judge an observation

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
| Spring PetClinic | Spring MVC | `spring-projects/spring-petclinic` `500158f`, Spring Boot 4.1.0, H2 | Scripted owner search, owner and pet forms, visits, vets (HTML and JSON), the error page; 130 s main run, about 8,700 requests | 31 | 6 (19 %) | 5 |
| A JHipster sample | Spring MVC | `jhipster/jhipster-sample-app` `6b000b5`, Spring Boot 4.1.1, JWT, JPA, Liquibase, H2 | REST API: authentication, account, CRUD with paging on every entity, user admin, anonymous 401s, 400s, 404s, management; about 130 s | 38 | 2 (5 %) | 3 |
| Quarkus Super Heroes | Quarkus | `quarkusio/quarkus-super-heroes` `d472e71`, `rest-villains`, aligned from Quarkus 3.39.5 to 3.33.3.3, Hibernate ORM Panache, PostgreSQL Dev Services | Villain list, random, by id, create, update, delete, invalid input, health and OpenAPI; 130 s | 18 | 3 (17 %) | 6 |
| A WebFlux sample | Spring WebFlux | `jhipster/jhipster-sample-app-gateway` `73b700b`, Spring Boot 4.1.1, Spring Cloud Gateway, R2DBC, H2 | Authentication, account, user admin, anonymous 401s, routes to absent services, management; 273 s, 391 requests | 26 | 4 (15 %) | 4 |
| A Kafka application | Spring MVC, Spring Kafka, Kafka Streams | `piomin/sample-spring-kafka-microservices` `a76daeb`, Spring Boot 4.1.1, three services | 198 orders (accepted, rejected for stock, payment, or both) through the saga; 162 s | 2 (order-service; payment and stock produced none) | 1 (50 %) | 1 |

Rows are matched across the two reviews by kind, subject, and status, from each application's final report; Kafka's
early `INSUFFICIENT` snapshot is left out. The two reviewers agreed exactly on 77 of 115 rows.

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
| `route-time-breakdown` | 17 | 1 | 0 |

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
