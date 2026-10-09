# BootUI v2 workshop exercise manifest

The [participant entry](../docs/workshop/README.md) is the published course. This manifest freezes the teaching
contract for facilitators. Participants use released **2.0.0** from **v2.0.0**, matched CLI/Java agent, and a disposable
clone. No release/version change is made by these materials.

## Environment

| Item | Baseline |
| --- | --- |
| Primary sample | `bootui-spring-sample-app`, MVC, `dev` |
| Storage/services | Seeded H2, Caffeine `recordStats`; no Docker/Kafka/application model |
| JDK | 17 or later supported by the release; avoid early-access JDK as event prerequisite |
| App/console | `http://localhost:8080`, `/bootui`; host app explicitly bound with `--server.address=127.0.0.1` |
| MCP | Opt-in `/bootui/api/mcp`; actual client configuration |
| CLI | Independent default-enabled transport, `BOOTUI_URL` points at same app |
| Exposure | `MASKED` or `METADATA_ONLY`; no guard changes |
| Warm build | `./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-spring-sample-app -am install -DskipTests` |
| Core launch | Chapter 01's loopback-bound `run-local.sh`, then Chapter 05's loopback-bound/trigger-file `run-local-agent.sh` |
| Same-JVM reload | Set `spring.devtools.restart.trigger-file=.workshop-reload`, finish compile/test, touch sample `target/classes/.workshop-reload`, observe one new run |

Record final immutable tag SHA, artifact checksums, tested JDKs/OS/client versions in the event preparation log.
The local implementation checkout can validate mechanics; it is not proof the future published tag/artifacts exist.

## Frozen traffic and expected evidence

All paths below are relative to the selected app/port. Startup must finish seeding before traffic.

| Chapter | Request/action | Expected evidence |
| --- | --- | --- |
| 02 | Explicit Hibernate advisor scan | `HIB-FETCH-001`, `HIB-QUERY-005` affected sample symbols |
| 03 | GET `/api/insights/eager-orders` | 16 ordered `{id,description,customer}` summaries; normally 17 SELECTs |
| 03 | GET `/api/insights/eager-orders/joined` | Identical array; normally one SELECT |
| 03 | POST `/api/insights/orders/6/import` | 200/accepted, rollback, ERROR; retained exception detail when available |
| 04 | Six sequential GETs of each eager-order route | Original repeated-SELECT observation; control no repetition pattern |
| 04 | POST `/api/insights/orders/2/confirm` | Nested `REQUIRES_NEW` audit transaction |
| 04 | POST `/api/insights/orders/3/ship` | Same-transaction control |
| 04 optional | POST `/api/insights/orders/5/recalculate`, `/5/recalculate-through-bean` | Self-call bypass vs proxy-boundary control |
| 05 | Attach default Java agent; inspect sensor self-tests | Actual installed executors/inventory/code-paths and relevant effect sensors |
| 05 | Repeat six GETs to eager-order pair | Code Path/SQL attribution and method execution baseline |
| 05 demo | GET `/api/insights/orders/after-response` | Accepted response then delayed raw-pool SQL, propagated ownership |
| 05 demo | GET `/api/insights/orders/after-response/waits` | Handler waits for executor work |
| 05 optional | GET `/api/side-effects/java-version`, `/runtime-version` | Process start/exit vs in-JVM control |
| 05 optional | Metadata-only method probe; two original-route calls | Active then bounded invocations, max 20/60 seconds/five probes |
| 06 | Bounded existing-evidence assessment and one separate impact read | Versioned action plan, observed affected routes/gaps, prepared unsent approval |
| 07 | One service-query edit, compile/test, six pair GETs | Same response, changed method executed, normally 17 -> 1 SELECT |
| 08 | Partner handoff | Evidence-backed statement plus unsupported/unmeasured claim |

Sample `/api/insights/**` POSTs are deliberately CSRF-exempt in its own security chain. That does not grant
authentication/CSRF bypass in a different app. BootUI actions use their normal token/confirmation/loopback guards.
Those guards protect BootUI, not application fixtures; the documented host binding limits both to loopback.
The Chapter 05 full stop loses prior cached scans/import observations. Their worksheet notes are historical; the
instrumented process records a fresh eager-order baseline before the assessment.

## Thresholds and contracts

Repeated SELECTs: same SELECT repeated >=5 times after another statement, in >=3 requests. Timing breakdown:
>=5 warm requests; collect six sequential calls and allow processing. Behavior comparison: several checks need
>=3 route samples in both runs. No full-run effects absence claim without both runs' complete sensor coverage.

The acceptance test checks equality to the unchanged control, exactly 16 records, exact field set, increasing ids,
nonblank text, and 1-2 prepared statements per endpoint. The live fixture's expected measured count is 17 -> 1;
the two-statement ceiling allows the existing fixture's framework tolerance.

The SQL-loop `/api/insights/orders` has nested lines; its `/joined` control is flat. It is intentionally **not**
the response-preserving capstone.

## Capstone assets and expectations

- `fixtures/WorkshopEagerOrdersTest.java`: install only into the disposable participant test package.
- `answers/eager-orders.patch`: the reviewed service-only reference answer.
- Upstream `EagerDemoOrdersTest`: passes on original fixture; intentionally incompatible after the capstone.
- Participant test command: `./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-spring-sample-app -Dtest=WorkshopEagerOrdersTest test`.
- Live compile: `./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-spring-sample-app compile`.
- Release reload after compile/test: `touch bootui-spring-sample-app/target/classes/.workshop-reload` (PowerShell: set its `LastWriteTime`).

No fixture source/test is changed in the delivered BootUI repository. The standalone test/answer is applied only
during the workshop. Red must be the query-budget assertion, not build/setup/data failure. Green does not prove the
live process ran the new method; the worksheet requires that separate evidence.
The participant installs the test and checks red **before** sending the prepared action approval. The agent may
edit, test, and compile, but only the participant updates the trigger file after success and diff review.

## Extension effects

Network SDK/client demos call loopback only. File/report demos require separate explicit mutation approval and
exact-path cleanup. Persistence creates a synthetic table; in-memory H2 is not a durable-JVM-survival demonstration.
JFR, optional sensors, Docker services, application AI, and security reasoning are outside the timed core.
The optional Quarkus sample also requires Docker/Podman for PostgreSQL Dev Services or a separately prepared local
PostgreSQL configuration; the Docker-free promise applies to the MVC core.
