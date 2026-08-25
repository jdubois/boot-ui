# BootUI on Quarkus — design & strategy

::: tip Contributor document
This records how the Quarkus adapter was designed and built, for people working on BootUI itself. If you just want to
know what BootUI gives you on Quarkus, read [Framework support](./FRAMEWORK-SUPPORT.md) instead — and note that your
running console is always the authoritative answer for your own application.
:::

> **Status:** Implemented and shipping. What began as a design proposal is now the `bootui-quarkus` /
> `bootui-quarkus-deployment` extension described throughout this document — the large majority of the panel set is live
> on Quarkus today (see §5 for the authoritative per-panel status and [SPECIFICATION.md](SPECIFICATION.md) §1.1 for the
> honest maturity statement: the Spring Boot adapter is complete, the Quarkus adapter is still being built out). The rest
> of this document is kept as the design record the implementation follows; treat the phased plan in §9 as historical —
> Phases 0–2 have shipped and Phase 3's capture panels are also live (§5.3).

## 1. Goal

Run the **same BootUI developer console — same Vue UI, same REST contract, same DTOs — on a Quarkus dev application**,
sharing as much code as possible with the Spring Boot implementation. Each framework contributes only a thin adapter
layer; the bulk of BootUI's logic lives in framework-neutral shared modules.

Concretely:

1. **One UI artifact.** `bootui-ui` (the Vue 3 SPA) is built once and served unchanged by both backends.
2. **One data contract.** The immutable `record` DTOs in `bootui-core` are the contract; both backends emit identical
   JSON at the configured API path (`/bootui/api/**` by default).
3. **One engine.** Advisor rule engines, scanners, the OSV scanner, the OTLP/telemetry store, JVM/MXBean readers, the
   dependency catalog, secret masking, scoring, and the MCP server move into a shared, Spring-free engine module.
4. **Thin per-framework adapters.** Spring and Quarkus each provide: a web binding (Spring MVC controllers vs JAX-RS /
   Vert.x routes), implementations of a small portability SPI, a safety-filter binding, and an activation hook.

### Non-goals

- **Native-image BootUI.** BootUI is dev-only; Quarkus dev mode is always JVM mode (see §6), so native is out of scope.
- **Integrating into Quarkus's built-in Dev UI** (`/q/dev`). BootUI keeps its own standalone console (`/bootui/` by
  default). Quarkus Dev UI uses build-time Lit web components; reusing our Vue UI is what keeps the UI shared.
- **100% panel parity.** A curated subset ships on Quarkus (§5). Spring-only panels are dropped or replaced.
- **Spring Boot 3.x / Gradle** — unchanged, still out of scope.

## 2. Why this is feasible — evidence from the current codebase

The repository already separates "what the data means" (framework-neutral) from "where the data comes from"
(Spring-specific). Measured against the current tree:

| Observation                                       | Evidence                                                                                                                                                                                                |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The DTO layer has **zero** Spring coupling        | `bootui-core`: 220 Java files, 209 DTOs, `0` files import `org.springframework`                                                                                                                         |
| The UI is already framework-agnostic              | `bootui-ui` uses only relative `fetch('api/…')` calls; no framework knowledge                                                                                                                           |
| The UI already gates panels on backend capability | `App.vue` fetches `/bootui/api/panels`, builds a `panelLookup`, and renders unavailable panels into a separate group                                                                                    |
| The advisor **engines** are framework-neutral    | `bootui-engine` advisor packages contain no Spring imports; framework collection and base-package discovery live in the adapters                                                                        |
| Hibernate analysis already uses neutral APIs      | `HibernateScanner` consumes `jakarta.persistence.EntityManagerFactory` / `Metamodel`, which Quarkus also provides                                                                                       |
| Safety decision logic is already Spring-free      | `CidrRange` and `ContainerGatewayDetector` carry no Spring dependency; only `LocalhostOnlyFilter` / `PanelAccessFilter` bind to `jakarta.servlet`                                                       |
| Several data sources are framework-neutral today  | OSV scanner, OTLP receiver + `TelemetryStore`, `DependencyCatalog` (reads `META-INF/maven/*/pom.properties` + `java.class.path`), JVM MXBean readers, GitHub `HttpClient`, Copilot/Claude log readers   |

The coupling that remains is concentrated in: the web layer (`@RestController`), the Actuator/`ApplicationContext`/
`Environment` data readers, the servlet safety filters, and the `EnvironmentPostProcessor`-based config plumbing. That is
exactly the surface the SPI seam abstracts.

## 3. Current module topology

Current modules and their roles:

```
SHARED (framework-neutral, built once, reused by both backends)
  bootui-core                    DTO records, SecretMasker, BootUiInfo
  bootui-engine                  Framework-neutral services, advisors, and io.github.jdubois.bootui.spi ports
  bootui-conformance             Shared HTTP contract suite and golden panel manifests
  bootui-ui                      Vue 3 SPA, built once

SPRING ADAPTER
  bootui-spring-autoconfigure        Shared Spring MVC/WebFlux auto-configuration, endpoints, SPI implementations, and safety
  bootui-spring-boot-starter         Drop-in Spring MVC starter
  bootui-spring-boot-starter-reactive
                                     Drop-in Spring WebFlux starter
  bootui-spring-sample-app           Spring MVC demo/integration app + Playwright e2e
  bootui-spring-webflux-sample-app   Spring WebFlux demo/conformance app

QUARKUS ADAPTER
  bootui-quarkus-parent              Shared Quarkus LTS BOM and plugin management
  bootui-quarkus                     Runtime JAX-RS/Vert.x resources, SPI implementations, and safety filters
  bootui-quarkus-deployment          Build-time wiring, capability gates, and production-dark activation
  bootui-quarkus-integration-tests   Docker-free @QuarkusTest conformance and smoke tests
  bootui-quarkus-sample-app          Demo/integration app
```

Dependency direction is one-way: `bootui-engine` depends on `bootui-core`; both adapters depend on the shared modules;
the shared modules never depend on Spring, Quarkus, servlet, JAX-RS, Vert.x, or either framework's JSON library. The
neutral SPI remains the `io.github.jdubois.bootui.spi` package inside `bootui-engine`.

```
bootui-core ◄── bootui-engine ◄── Spring and Quarkus adapters
      ▲                ▲
      └────────────────┘

bootui-ui           built once and packaged for each adapter
bootui-conformance  exercises the same HTTP contract against each adapter
```

### How the web layer stays mostly shared

Spring MVC/WebFlux and JAX-RS annotations are incompatible, so their adapter-specific bindings cannot be one class. The
fix is to keep bindings **thin** and push all logic into shared `bootui-engine` services:

```
Spring:   @RestController BeansController ─┐
                                           ├─► (shared) service in bootui-engine ─► DTO from bootui-core
Quarkus:  @Path JAX-RS resource ───────────┘         (calls an SPI provider for raw data)
```

Most BootUI controllers are already shaped this way (e.g. `ArchitectureController` → `ArchitectureScanner`,
`VulnerabilitiesController` → `DependencyProvider`/`OsvVulnerabilityScanner`). The refactor extracts the scanner/service
into `bootui-engine` and leaves a ~10-line binding in each framework module.

## 4. The portability SPI (the `io.github.jdubois.bootui.spi` package in `bootui-engine`)

Small interfaces the shared engine calls; each framework implements them. Names are illustrative.

| SPI interface                  | Purpose                                                              | Spring implementation                                       | Quarkus implementation                       |
| ------------------------------ | -------------------------------------------------------------------- | ----------------------------------------------------------- | -------------------------------------------- |
| `EnvironmentProvider`          | Property values, property sources, active profiles; config overrides | `ConfigurableEnvironment` + `BootUiOverridesPropertySource` | SmallRye `Config` + `ConfigSource`           |
| `AppInfoProvider`              | Framework name/version, main class, banner text                      | `SpringBootVersion`, `Environment`                          | Quarkus version, `@QuarkusMain`              |
| `BasePackageProvider`          | Application base packages for advisors                               | `AutoConfigurationPackages`                                 | Jandex index / configured root               |
| `HealthProvider`               | Health components & status                                           | Actuator `HealthEndpoint`                                   | SmallRye Health                              |
| `MeterRegistrySupplier`        | The Micrometer registry                                              | bean lookup                                                 | bean lookup (**same `MeterRegistry` API**)   |
| `LoggerProvider`               | List / get / set log levels                                          | Actuator `LoggersEndpoint`                                  | JBoss LogManager                             |
| `MappingProvider`              | HTTP route inventory                                                 | Actuator `MappingsEndpoint`                                 | Vert.x `Router` / RESTEasy registry          |
| `ScheduledTaskProvider`        | Scheduled jobs                                                       | `ScheduledTaskHolder`                                       | quarkus-scheduler `Scheduler`                |
| `EntityManagerFactoryProvider` | EMFs + metamodel (Hibernate advisor)                                 | `ObjectProvider<EntityManagerFactory>`                      | Arc `EntityManagerFactory`                   |
| `MigrationProvider`            | Flyway / Liquibase instances                                         | beans                                                       | Arc (`quarkus-flyway` / `quarkus-liquibase`) |
| `DataSourcePoolProvider`       | Connection-pool stats                                                | HikariCP MXBeans                                            | Agroal metrics                               |
| `CacheProvider`                | Cache managers, tiers & native stats                                 | Spring `CacheManager`                                       | quarkus-cache (Caffeine, no stats API)       |
| `RequestCaptureSource`         | Live request feed (Live Activity)                                    | `ServletRequestHandledEvent`                                | Vert.x filter                                |
| `HttpExchangeProvider`         | Recent HTTP exchanges                                                | Actuator `HttpExchangeRepository`                           | Vert.x filter buffer                         |
| `AuditEventProvider`           | Security audit events (Security Logs)                                | Actuator `AuditEventRepository`                             | CDI security events                          |
| `SqlTraceSource`               | Captured SQL statements                                              | datasource-proxy                                            | Agroal / JDBC interceptor                    |
| `KafkaActivityRecorder`        | Kafka messaging capture (Live Activity + the Kafka panel)           | `KafkaTemplate` / `@KafkaListener` `BeanPostProcessor` wrap | SmallRye `Outgoing`/`IncomingInterceptor`    |
| `RabbitActivityRecorder`       | RabbitMQ messaging capture (Live Activity + the RabbitMQ panel)     | `RabbitTemplate` / `AbstractRabbitListenerContainerFactory` wrap | SmallRye `Outgoing`/`IncomingInterceptor` |
| `LogCaptureSource`             | Tailed log lines (Log Tail)                                          | logback appender                                            | JBoss LogManager handler                     |
| `LocalhostGuardBinding`        | Feeds request metadata to the shared guard                           | servlet `Filter`                                            | Vert.x handler                               |

The framework-neutral safety **decision** (loopback check, `Host`/allowed-hosts validation, `Origin`/`Sec-Fetch-Site`
CSRF defense) is extracted into a shared `LocalhostGuard` in `bootui-engine`, reusing the existing `CidrRange` /
`ContainerGatewayDetector` logic. Each framework only supplies request metadata and writes the deny response.

Per-panel **access** rules (`bootui.panels.<id>.enabled` / `.read-only`, plus the global `bootui.read-only`) are a
separate, sibling mechanism — implemented on Spring as `PanelAccessFilter` and, at full behavioral parity (same config
keys, same canonical JSON 403 body), on Quarkus as `QuarkusPanelAccessFilter`. Both bind to the same shared
`BootUiPanels` registry to resolve a request path to a panel id and its `actionCapable()` flag. Both run as a second
filter *after* the loopback/Host/CSRF guard: `QuarkusPanelAccessFilter` is registered at a lower Vert.x filter priority
than `BootUiQuarkusSafetyFilter`, so a request failing both checks is rejected by the safety guard, not the panel-access
filter.

## 5. The Quarkus panel set

The console keeps a single `routes.js`. For every route, the backend's `/bootui/api/panels` manifest returns a
`PanelDto`: `available` reports whether the adapter can currently serve the panel, and `unavailableReason` explains why
when it cannot. The separate `enabled`, `readOnly`, and `readOnlyReason` fields describe operator policy. `App.vue` uses
those fields so the same UI build renders the correct sidebar and status on each backend.

> **Implementation status (current).** The Quarkus adapter now lights up the large majority of the panel set — all of
> §5.1 and §5.2 below, plus the advisors (Architecture, the Quarkus application advisor replacing Spring, Hibernate,
> Pentesting, a Quarkus-native Security advisor, REST API, Memory) and the §5.3 capture panels (HTTP Exchanges, Live
> Activity, Log Tail, SQL Trace, REST Client, Exceptions, Security Logs, Email, Kafka, RabbitMQ).
> **Action-capable panels behave identically to Spring**, behind the shared `LocalhostGuard` write floor: Heap Dump
> (capture/analyze/delete/download), Threads (download), the advisor scans, Loggers (set level), HTTP Probe, Cache
> (clear), Flyway (migrate/clean), Liquibase (update), Traces (clear), Email (clear), Kafka (clear), RabbitMQ (clear),
> REST Client Reactive (clear + recording toggle), and the MCP Server toggle. Eight panels — **GraalVM**, **CRaC**,
> **Conditions**, **Startup Timeline**, **HTTP Sessions**, **Spring Data**, **Spring Security**, and **DevTools** — are
> intentionally unavailable with a panel-specific not-applicable reason (§5.5). **JMS** is the sole panel that is not yet
> available on Quarkus (§5.6). The per-panel `**Implemented**` markers below and `docs/features/` carry the authoritative,
> current per-platform detail.
>
> Expensive advisor actions also share Spring's per-scanner single-flight contract: overlapping Architecture,
> Quarkus-application, Hibernate, Memory, Security, Pentesting, REST API, and Vulnerabilities/OSV scans fail fast with
> the canonical JSON `409` response, while Heap Dump capture/analyze/delete share one mutation-domain admission. MCP
> returns the same busy message in-band, and passive reads continue serving the last completed report.

### 5.1 Ported as-is — framework-agnostic or same library (19)

Logic lives entirely in `bootui-core` + `bootui-engine`; the Quarkus adapter adds at most a trivial supplier.

| Panel                                                 | Notes                                                                             |
| ----------------------------------------------------- | --------------------------------------------------------------------------------- |
| `Memory`, `Live Memory`, `JVM Tuning`, `Heap Dump`, `Threads` | Pure JVM MXBeans                                                           |
| `Metrics`                                             | Micrometer — same API                                                             |
| `Hibernate` advisor                                   | Same 72-rule registry/report contract; mapping and configuration rules port, while Spring Data query rules skip when repository metadata is unavailable |
| `Hibernate Statistics`                                | Standalone Database-section panel over `org.hibernate.stat.Statistics`, gated on the same Hibernate ORM capability as the advisor; its runtime-enable action has the same read-only and cross-site-write protection as Spring |
| `Vulnerabilities`                                     | Classpath Maven metadata + OSV                                                    |
| `HTTP Probe`                                          | Local HTTP probing                                                                |
| `AI Framework`                                        | —                                                                                 |
| `Traces`                                              | OTLP — a standard; Quarkus/LangChain4j export it                                  |
| `GitHub`                                              | `HttpClient`                                                                      |
| `Copilot`, `Claude Code`                              | Read `~/.copilot` / `~/.claude`                                                   |
| `Pentesting`                                          | Shared 80-check engine (see below)                                                |
| `MCP Server`                                          | **Implemented** — full JSON-RPC bridge (see below)                                |
| `Dev Services`                                        | **Implemented** — Quarkus-native concept (see below)                             |

Pentesting uses the shared 80-check engine and report contract. Its thin Quarkus collector supplies the live port,
`quarkus.http.root-path`, CORS, OIDC auth-server URL, and direct-listener TLS posture, with TLS property-name discovery
bounded at 4096 entries. It explicitly marks Spring endpoint/security metadata unavailable; A01 is therefore
`NOT_APPLICABLE`, while no-finding mixed categories use Quarkus-specific `INFO` wording rather than a false pass. The
explicit scan sends at most one GET and one OPTIONS request directly to `127.0.0.1`, with no redirect, proxy, or external
host access. See [PENTEST-CHECKS.md](PENTEST-CHECKS.md) for the exact evidence limits and mappings.

::: details MCP Server and Dev Services wiring

**MCP Server** is a full JSON-RPC bridge. The shared engine `McpDispatcher` owns method routing/gating/tool lookup, and
a thin Jackson-2 `QuarkusMcpEnvelope` codec + `QuarkusMcpTools` catalog + working enable toggle sit behind the
`LocalhostGuard` write floor. JVM-mode integration is covered end to end; native-image availability follows each backing
panel and is not claimed beyond the native-image tests that exercise that capability.

**Dev Services** is a Quarkus-native concept: a build-time `DevServicesResultBuildItem` snapshot captured via recorder +
synthetic bean, with masked config and logs/restart unavailable. Service `type` is classified via the shared
`DevServiceTypeInference` engine helper, matching Spring's classification.

:::

### 5.2 Ported by swapping the data source (12)

Same DTO and UX; the Quarkus adapter implements the relevant SPI against a Quarkus API.

| Panel                 | Quarkus source                                                                                             |
| --------------------- | ---------------------------------------------------------------------------------------------------------- |
| `Health`              | → SmallRye Health                                                                                          |
| `Configuration`       | **Implemented** — → SmallRye Config; read path enumerates/masks/pages the effective config. Read-only on Quarkus because the runtime-override write path is Spring-bootstrap-specific |
| `Profile Diff`        | **Implemented** — → SmallRye Config; groups active `%profile.`-prefixed keys                              |
| `Loggers`             | → JBoss LogManager                                                                                         |
| `Mappings`            | **Implemented** — → the application's JAX-RS resources scanned from the build-time Jandex index by a `registerMappings` build step + `@Recorder` into a synthetic bean; same paged `MappingProvider` DTO as Spring's Actuator read, BootUI's own routes filtered out at build time |
| `Flyway`              | → `quarkus-flyway`                                                                                         |
| `Liquibase`           | **Implemented** — → `quarkus-liquibase`; discovered via `LiquibaseFactoryUtil.getActiveLiquibaseFactories()`, the shared `RanChangeSet` history read + `update` action behind the same DTO contract |
| `Scheduled Tasks`     | → `quarkus-scheduler`                                                                                      |
| `Fault Tolerance`     | **Implemented** — → SmallRye Fault Tolerance (Jandex-scanned declarations, MicroProfile config overrides, live named-breaker state). See details below |
| `Architecture` advisor | Shared ArchUnit registry; generic rules run unchanged, Spring-only annotation rules no-op, and Jakarta-based/platform-sensitive rules use Quarkus semantics |
| `Beans`               | **Implemented** — → Arc/CDI `BeanManager.getBeans(...)`, with resolved injection edges captured after Arc build-time validation and overlaid on the retained runtime inventory; defining resources and Spring Conditions evidence remain unavailable |
| `Overview`            | Panel available; the scoring dashboard aggregates the advisor endpoints client-side, and `GET /bootui/api/overview` reports the Quarkus version + shell chrome |

::: details Fault Tolerance fidelity

`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimit`, and `@Fallback` declarations are scanned from the
build-time Jandex index by a `registerFaultTolerancePolicies` build step + `@Recorder` into a synthetic bean, then
mapped onto the same `FaultTolerancePolicyProvider` SPI and DTO contract Spring's Resilience4j/Spring Retry providers
use.

MicroProfile Fault Tolerance configuration overrides (`<class>/<method>/<annotation>/<member>` and its two broader
forms) are resolved at runtime through SmallRye Config and reported with `CONFIGURED` provenance. This includes the
`.../<annotation>/enabled` switches and `MP_Fault_Tolerance_NonFallback_Enabled`: a policy the application turned off
through configuration is listed with a leading `enabled` = `false` setting instead of being silently shown as if it
still applied. Live circuit-breaker state and state-transition events come from `CircuitBreakerMaintenance`, which
SmallRye only exposes for breakers carrying `@CircuitBreakerName`; anonymous breakers honestly report `UNKNOWN`.

**Honest fidelity gaps:** SmallRye publishes no per-call event stream, so retries, rejections, and timeouts are not
individually captured on Quarkus (only breaker state transitions are). It also exposes no per-policy call counters
through a public API, so the metrics block is reported absent rather than invented. The whole integration is
capability-gated on `Capability.SMALLRYE_FAULT_TOLERANCE`, with `SmallRyeCircuitBreakerStates` — the only class
importing the fault-tolerance API — R2-excluded from Arc when the extension is absent.

:::

### 5.3 Kept, with a rebuilt capture layer or reduced fidelity (13)

The DTO and UI are reused; the Quarkus adapter rebuilds the capture/source on the reactive stack.

| Panel                 | Quarkus source / limitation                                                                                                                    |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `REST API` advisor    | **Implemented** — conventions (status codes, versioning, pagination) shared; the engine models JAX-RS `@Path` resources and standards-defined custom `@HttpMethod` annotations alongside Spring MVC, and irreducibly-Spring rules (ProblemDetail, ResponseEntity codes) SKIP honestly on JAX-RS. The handler model unwraps SmallRye Mutiny `Uni`/`Multi` and RESTEasy Reactive's typed `RestResponse<T>` so body-shape rules (entity leakage, untyped bodies, pagination, plural naming, etc.) see the real payload type on idiomatic reactive Quarkus code, the same as Spring's `Mono`/`Flux`. Both the classic `@Provider ExceptionMapper<X>` and RESTEasy Reactive's `@ServerExceptionMapper` are modeled as full exception handlers (not just a boolean), so the exception-quality rules evaluate JAX-RS error handling instead of vacuously passing. The `RAPI-DOC-*` documentation-presence rules recognize MicroProfile OpenAPI (`quarkus-smallrye-openapi`) equally to Spring's springdoc — via `BootUiEngineProducer`'s classpath probe for `org.eclipse.microprofile.openapi.annotations.Operation`, the same technique the Spring adapter uses for Swagger's `@Operation` — rather than being hardcoded off on Quarkus. The panel's **declared error contract** is captured at **build time** from the Jandex index by the `registerErrorContract` build step, because Quarkus exposes no reliable runtime enumeration of resolved mappers and only the index carries the `ExceptionMapper<X>` generic signature. Only `@Provider`-annotated `ExceptionMapper` implementations are reported, because an unregistered implementation never participates in exception resolution; they are application-wide and ranked by `@Priority`, while resource-local `@ServerExceptionMapper` methods are resource-scoped (ranked by that annotation's own `priority` attribute, which is the only ordering signal Quarkus reads there) and so outrank global mappers exactly as Spring's controller-local `@ExceptionHandler` outranks a `@ControllerAdvice`. The mapped exception type is resolved through the superclass chain, so a mapper extending an abstract base still reports the exception it handles. `Response`/`RestResponse` returns are reported honestly as runtime-built statuses, and `Uni`/`CompletionStage` returns are unwrapped before the body is classified. The capture is skipped in `NORMAL` launch mode, so the provider reports the backend explicitly unavailable rather than empty |
| `DB Connection Pools` | **Implemented** — reads **Agroal** metrics instead of HikariCP MXBeans via `QuarkusAgroalConnectionPoolProvider` (the sole importer of `io.agroal.*`, gated on the `AGROAL` capability). Agroal→Hikari mapping: active←activeCount, idle←availableCount, total←active+idle, pending←awaitingCount; acquisition/idle/max-lifetime timeouts map across; `validationTimeoutMs`/`keepaliveTimeMs` report `-1` (rendered "—") and `readOnly` reports `false` (no Agroal accessor). Requires `quarkus.datasource.jdbc.metrics.enabled=true` for live counts — configuration still renders without metrics, but the snapshot is null and the pool is marked unavailable with a specific reason. Read-only: no write gate |
| `SQL Trace`           | **Implemented** — two complementary feeders into the shared `SqlTraceRecorder`: an `@Alternative` Agroal `DataSource` wrap (manual JDBC, gated on Agroal) **and** a `@PersistenceUnitExtension` Hibernate `StatementInspector` (ORM SQL, which bypasses the wrapped DataSource; gated on Hibernate). Statement text/type/category/N+1 full-fidelity; per-statement duration/rows/params are best-effort for ORM SQL (the StatementInspector SPI has no execution-end hook). Call-site capture (`bootui.sql-trace.capture-call-site`, default `true`) runs once at the shared `SqlTraceRecorder.record(...)` choke point both feeders call into, so it is byte-identical to Spring on both feeders with no extra per-feeder code. `clear`/`recording` behind the `LocalhostGuard` write floor. Statement rankings and request-route attribution (`GET /bootui/api/sql-trace/insights`) run on the same framework-neutral `SqlTraceInsightsService` as both Spring stacks, with request evidence read from the engine's own `HttpExchangeBuffer`. **Two honest fidelity gaps:** correlation is `TRACE_ID` + `TIME_WINDOW` only — the Vert.x event loop gives no reliable thread-per-request anchor, exactly as for Live Activity above — and RESTEasy Reactive exposes no per-request route template to the adapter, so the route key is resolved *after the fact* against the application's own declared JAX-RS mappings (read from the Mappings panel's build-time `QuarkusMappingProvider`, so no new route enumeration is introduced): a captured path is labelled `ROUTE_TEMPLATE` only when exactly one declaration matches it segment for segment and is strictly the most literal match, and falls back to a **masked path** (identifier-looking segments replaced with `{value}`, query string discarded) otherwise. That fallback matters for privacy as much as for grouping — masking alone cannot tell a word-shaped path parameter such as `/api/users/alice` from a fixed segment, and the declared template can. Both are declared in the payload (`supportedCorrelations`, `routeSource`) so the UI states them instead of silently degrading |
| `REST Client`         | **Implemented** — when `Capability.REST_CLIENT_REACTIVE` is present, a generated `RestClientListener` service-provider entry attaches `QuarkusRestClientTraceFilter` to every REST Client Reactive proxy and feeds the shared `RestClientTraceRecorder`. The listener type is excluded and no provider entry is generated when the optional extension is absent, so an app without REST Client starts without linking the optional API. Listener lookup is lazy through the running Arc container because Quarkus constructs proxies after CDI startup. The filter runs after application request filters and before application response filters, captures metadata only (never payloads or arbitrary headers), strips URI user-info/fragments, masks sensitive path/query values before storage, and isolates all capture failures from application calls. Real `4xx`/`5xx` responses are successful transports; Quarkus's status `0` callback records a pre-response transport failure with a null HTTP status. The active trace id is snapshotted in the request filter because the response callback can run after OpenTelemetry detaches its context; application call-site attribution is likewise best-effort after that reactive handoff. `clear`/`recording` are behind the shared write and panel read-only gates; SSE and Live Activity update on each mutation/call. Manifest availability is capability-driven because proxies initialize lazily; the report remains honest until one has been instrumented. |
| `Live Activity`       | **Implemented** — merges the nine captured signals into the shared feed, with trace-id-based request correlation, a red **N+1** badge from the shared `SqlTraceGrouping`, SmallRye Kafka/RabbitMQ messaging capture, captured `MAIL` events, an optional durable JDBC persistence backend, and the Live flow service map. Cache (`CACHE`) events are **not** captured on Quarkus. See the details blocks below the table. |
| `HTTP Exchanges`      | **Implemented** — buffer exchanges via a Vert.x filter instead of Actuator's repository                                                       |
| `Exceptions`          | **Implemented** — captured into the shared `ExceptionStore` by **three** feeders, deduped across feeders across the whole cause chain; BootUI's own frames self-filtered. `QuarkusExceptionLogHandler` (a `java.util.logging` handler) catches logged throwables, and `QuarkusExceptionCaptureFilter` (a Vert.x failure handler) catches unhandled web failures; Quarkus logs an unhandled request failure synchronously (`QuarkusErrorHandler`) before the Vert.x failure handler's late `addBodyEndHandler` callback runs, so the log handler is normally the feeder the store's dedup keeps for that case; it resolves the owning request's method/path itself from the CDI-current `CurrentVertxRequest`, so both feeders carry full web context. Neither feeder, however, observes an exception that a custom `jakarta.ws.rs.ext.ExceptionMapper`/`@ServerExceptionMapper` resolves without logging it — RESTEasy Reactive never calls `RoutingContext.fail(...)` once a mapper has produced a response, so that class of failure (Quarkus's analogue of a Spring `@ExceptionHandler`) was invisible. `QuarkusPreMappingExceptionCaptureHandler` closes that gap: it is installed via the RESTEasy Reactive `PreExceptionMapperHandlerBuildItem` build item (the same first-party extension point Quarkus's own OpenTelemetry extension uses to attach exception info to the active span before mapping), which Quarkus guarantees runs for **every** exception about to be resolved by any mapper, mapped or not — so it is now normally the earliest, and hence dedup-winning, feeder for any exception dispatched through the JAX-RS chain, while the other two feeders remain the safety net for logged-but-unmapped failures and non-JAX-RS Vert.x routes. Being a build-time `ServerRestHandler` instance (not a CDI bean), it resolves `ExceptionStore`/`TraceIdProvider` lazily per invocation via `Arc.container()`, matching Quarkus's own `AttachExceptionHandler` precedent. This brings Quarkus to parity with Spring's `BootUiExceptionHandlerResolver` (a `HandlerExceptionResolver` at `HIGHEST_PRECEDENCE` that captures every `@ExceptionHandler`-resolved exception the same way). The `handler` field (the JAX-RS resource class + method serving the request, e.g. `MyResource#doSomething`, matching Spring's `HandlerMethod`-derived format) is resolved the same way by `QuarkusResourceHandlers`, reading RESTEasy Reactive's current-request state (`CurrentRequestManager`/`ResteasyReactiveResourceInfo`, the same mechanism `quarkus-rest-jackson` itself uses for per-method `@JsonView` resolution) — populated/cleared in lockstep with the `CurrentVertxRequest` above, so it survives the same event-loop→worker hop. The Open/Acknowledged/Resolved triage workflow and regression auto-reopen (a `Resolved` group that fires again flips back to `Open` and increments a `regressionCount`) live entirely in the shared `ExceptionStore`/`ExceptionsService`, so they are identical on Quarkus; `ExceptionsResource` exposes the same `POST /bootui/api/exceptions/{id}/status` with the same validation/status codes as Spring's `ExceptionsController`, behind the same `LocalhostGuard` write floor |
| `Security Logs`       | **Implemented** — captures Quarkus CDI security events into the shared `SecurityEventBuffer` via `QuarkusSecurityEventCapture` (a `@Observes SecurityEvent` observer), which replaces Spring's `AuditEventRepository`. Gated on `quarkus-security` (the observer is excluded by the deployment processor when no security extension is present, R2) and `quarkus.security.events.enabled=true` (panel reports unavailable with a clear message when events are disabled). Honest partial: Quarkus fires events only for authentication success/failure and authorization failure — no logout or session events (no Quarkus equivalent). SSE `/stream` ticks on each capture. Read-only (no write endpoints) |
| `Log Tail`            | **Implemented** — captured via a `java.util.logging` `Handler` (`QuarkusLogTailHandler`) attached to the root JBoss LogManager logger at `StartupEvent` (detached at `ShutdownEvent` so dev-mode restarts never leak handlers). The handler feeds the shared `LogTailBuffer`; both `/recent` (snapshot) and the SSE `/stream` (live fan-out with atomic snapshot-then-subscribe to avoid gaps) are full-fidelity. BootUI's own loggers are self-filtered. Identical wire to Spring's Logback appender path |
| `Email`               | **Implemented** — captured via a CDI `@Observes SentMail` observer (`QuarkusEmailCapture`) into the shared `EmailCaptureService`, replacing Spring's `CapturingJavaMailSender` decorator; one observer catches the blocking/reactive/Mutiny send styles (all funnel through the internal mailer that fires `io.quarkus.mailer.SentMail` after each successful send, mock or real). Gated on `quarkus-mailer` — the sole `io.quarkus.mailer`-importing class (`QuarkusEmailCapture`) is `provided`-scoped and excluded by the deployment `registerEmail` build step (class-presence check on `io.quarkus.mailer.reactive.ReactiveMailer`, non-prod only) when absent, R2; the mailer-free `EmailResource`/`EmailCaptureService` are always wired, so `GET /bootui/api/email` renders `available:false` with a `quarkus-mailer` hint rather than throwing. The `.eml` download delegates to the shared engine renderer so the bytes match Spring. Reduced fidelity: because the event fires *after* the send, there is no BootUI dev-trap — the sent/not-sent distinction instead reflects the framework's own mock-mail mode (`quarkus.mailer.mock`, default in dev/test), labelled **mock** in the UI; attachment size is unknown (the sent-attachment API exposes none). Content masking is identical (revealed by default, decoupled from `bootui.expose-values`; opt in with `bootui.email.mask-content=true`). `clear` (DELETE) behind the `LocalhostGuard` write floor |
| `Kafka`               | **Implemented** — the standalone panel over the same shared `KafkaActivityRecorder`/`Capability.KAFKA` gate described in the `Live Activity` row above; `GET`/`DELETE /bootui/api/kafka` mirror Spring's `KafkaController` contract exactly (list newest-first, clear), and the same reduced-fidelity notes carry over unchanged: metadata-only capture (never the payload), a null consumer group id (`IncomingKafkaRecordMetadata` exposes none), the channel name used as the listener id, and a null producer duration (the ack callback carries no send-start timestamp). Reports unavailable with a `quarkus-messaging-kafka` hint when the capability is absent. `clear` (DELETE) behind the `LocalhostGuard` write floor |
| `RabbitMQ`            | **Implemented** — the standalone panel over the shared `RabbitActivityRecorder` and class-presence gate described in the `Live Activity` row above; `GET`/`DELETE /bootui/api/rabbitmq` mirror Spring's list/clear contract. Capture is metadata-only, correlation IDs are omitted by default and hashed when explicitly enabled, and failures use generic text. Reports unavailable with a `quarkus-messaging-rabbitmq` hint when the connector is absent. `clear` (DELETE) behind the shared write and panel read-only gates |
| `WebSockets`          | **Implemented** — endpoint topology is captured at build time from the Jandex index (`@WebSocket` classes and their `@OnOpen`/`@OnTextMessage`/… callbacks) and replayed through a synthetic `QuarkusWebSockets` bean; live connections come from `OpenConnections` plus `@Observes @Open`/`@Closed WebSocketConnection` events. Reduced fidelity: WebSockets Next exposes no message-interception SPI, so the report states `frameCaptureSupported=false` with that reason and the panel shows endpoints and connections only — no frame log. Gated on `quarkus-websockets-next` class presence with the two websockets-importing beans excluded when absent |

::: details Live Activity — signals, trace-id correlation, and the N+1 badge

The feed merges nine captured signals: HTTP requests (via the Vert.x ring buffer), SQL trace, REST Client calls,
exceptions, security events, scheduled-task runs, Kafka messaging, RabbitMQ messaging, and captured email. SQL
contributes only when a datasource is configured (a clean warning otherwise), and the security/REST/messaging sources
honor their own availability and panel-enabled gates. Scheduled-task runs are captured by
`QuarkusScheduledTaskRunRecorder`, a CDI observer of the scheduler's own `SuccessfulExecution`/`FailedExecution` events
(gated on the `SCHEDULER` capability; `quarkus-scheduler` R2-excluded when absent), since the scheduler's single-instance
`JobInstrumenter` SPI is already claimed by `quarkus-opentelemetry`.

**Signal-to-request correlation is trace-id-based, gated on `quarkus-opentelemetry`.** Spring's thread-per-request anchor
is unportable on the Vert.x event loop. Instead the adapter stamps the active server span's trace id at each capture
point (HTTP filter, REST Client recorder, SQL recorder, exception store, and the CDI `SecurityEvent` observer) via a
capability-gated `QuarkusOtelTraceIdProvider`. The engine `LiveActivityAssembler` then nests REST/SQL/exception/security/
email entries under the request sharing that trace id — OTel `Context` propagates across the event-loop→worker hop,
including into the CDI security-event observer. A security event whose trace id uniquely matches one request also stamps
that request's `securedPrincipal` (falling back only when the request's own captured principal is null), so the
"authenticated" badge lights up from either signal. An ambiguous trace id (shared by more than one in-flight request) is
never nested and never stamps a principal — the same guard already used for SQL/exceptions. With OpenTelemetry absent,
trace ids stay null and the feed renders flat (status quo).

**N+1.** Any request whose correlated SQL trips the N+1 threshold carries a list-level `sqlNPlusOneSuspected` flag,
rendered as a red **N+1** badge directly in the main stream row, not just the drawer. It is computed by the shared engine
`SqlTraceGrouping` helper — the identical code path Spring's `LiveActivityService` uses — so flagging and call-site
aggregation are byte-identical across adapters.

**Captured email (`MAIL`).** `LiveActivityResource` reads the same framework-neutral `EmailCaptureService` the standalone
Email panel uses directly, with no separate Quarkus capture instrumentation, and feeds its merged SSE stream identically
to Spring, nesting as a `REQUEST` child via the same trace-id join.

**Cache (`CACHE`) is not captured on Quarkus.** The Spring servlet and WebFlux adapters feed a `CacheActivityRecorder` by
decorating `CacheManager` beans, but `quarkus-cache`'s built-in interceptors cast the resolved cache to an internal,
non-public `AbstractCache` type. A Spring-style decorator has no comparable runtime interception seam, so the
`cacheHitRatioPercent` KPI always renders `null` on this adapter (see `docs/PLAN.md` §3.4).

:::

::: details Messaging capture on Quarkus (Kafka & RabbitMQ)

**Kafka capture is gated on `quarkus-messaging-kafka` (`Capability.KAFKA`).** Quarkus apps use SmallRye Reactive
Messaging's `@Incoming`/`@Outgoing` channels rather than Spring's imperative `KafkaTemplate`/`@KafkaListener`. Capture is
done by two `@ApplicationScoped` interceptors — `QuarkusKafkaProducerCapture` (`OutgoingInterceptor`) and
`QuarkusKafkaConsumerCapture` (`IncomingInterceptor`) — that read Kafka record metadata into the shared, framework-neutral
`KafkaActivityRecorder`. This is the same buffer and the same `bootui.kafka.*` keys/defaults as Spring
(`enabled`/`capture-key` `true`, `max-entries` `200`, `max-key-length` `16`), with capture disabled whenever the
dedicated Kafka panel is disabled. The resource merges those `MESSAGING` entries into the feed (top-level, no request
correlation) through the shared `KafkaActivityEntries` mapping, so both adapters render byte-identical entries.

**RabbitMQ follows the same SmallRye pattern.** `QuarkusRabbitProducerCapture`/`QuarkusRabbitConsumerCapture` feed the
shared `RabbitActivityRecorder`, are class-presence-gated on `quarkus-messaging-rabbitmq`, stop capture whenever the
dedicated RabbitMQ panel is disabled, and are excluded from Arc when the connector is absent. Both capture pairs are the
sole importers of their connector metadata APIs, production-dark, pass-through/fail-open, and use `Integer.MAX_VALUE`
priority so application channel interceptors win.

**Honest fidelity gaps.** Capture is metadata-only and never stores payloads, arbitrary headers, or raw broker exception
messages (failed entries retain only generic failure text). Kafka lacks consumer group id and producer duration, while
RabbitMQ lacks per-message producer exchange/consumer queue and producer duration, because SmallRye does not expose them
at those callbacks. Correlation IDs are omitted by default and only a truncated SHA-256 hash is retained when enabled.

**Current interceptor limitations.** Incoming Kafka/RabbitMQ deliveries carry connector metadata and are captured
normally. An outgoing message is captured only when it already carries the matching `OutgoingKafkaRecordMetadata` or
`OutgoingRabbitMQMetadata`; a payload-only emission relying entirely on channel configuration is not visible at the
interceptor callback. SmallRye also selects only one default `OutgoingInterceptor` and one default `IncomingInterceptor`
per channel. BootUI's Kafka and RabbitMQ pairs are both default-qualified at the same fallback priority, so an
application that installs both connectors currently gets capture for only the pair SmallRye selects; a channel-specific
application interceptor takes precedence over BootUI as designed. Finally, Kafka availability is currently gated by the
broad Quarkus `KAFKA` capability, so an application with only the lower-level `quarkus-kafka-client` can over-report the
panel as available even though Reactive Messaging capture is not wired.

:::

::: details Why the per-request profiler (`GET /bootui/api/activity/{id}`) is trace-id-only on Quarkus

Spring's `/activity/request/{id}` profiler is a Symfony-style join across SQL, exceptions, security audit events, the
distributed trace, and timing for one request (`LiveActivityCorrelator`) — not CPU/flame-graph sampling. Its richness
comes from a **tiered** correlation strategy: (1) OpenTelemetry trace id (the strongest, most precise signal), then, for
requests without one, (2) HTTP method+path+time-window+thread heuristics for exceptions, (3) serving-thread correlation
for SQL, and (4) time-window+principal for security events.

Tiers 2-4 key on **serving-thread identity**. A servlet request runs start-to-finish on one worker thread that serves
only one request at a time, so SQL, exceptions, and security events observed on that thread within its time window
belong to it exactly, even with no distributed tracing at all (`threadMatched` is a first-class field on
`RequestProfileSecurityDto`/carried by `RequestProfileExceptionDto`'s `thread`). Quarkus's Vert.x event-loop-plus-worker
model has no equivalent "the one thread that served this request" identity — handling can hop across the event loop and
one or more worker threads. Tiers 2-4 therefore have nothing to key on and are **not ported** (investigated and correctly
ruled out as infeasible; see the PR that shipped this trace-id-only profiler).

Tier 1, however, **is** portable and **is now implemented**. The same trace-id stamping that powers the
`LiveActivityAssembler` nesting above is reused by a dedicated engine class, `RequestProfileAssembler`, to answer the
endpoint directly. When the requested exchange carries a trace id, the endpoint gathers every SQL/exception/security
entry sharing that exact trace id and returns `available: true` with `sqlCorrelationApproximate: false` (trace-id
matching is exact, unlike Spring's time-window heuristics) plus a `notes` entry disclosing that this is a reduced,
trace-id-only profile. `threadMatched` always reports `false` (Quarkus has no thread-identity concept), while
`principalMatched` on `RequestProfileSecurityDto` **is** honestly computed by comparing principals. Ambiguous trace ids
(shared by more than one in-flight request) render an `available: true` profile with empty correlated lists plus an
explanatory note, never a hard failure or cross-request leak. When the exchange has no trace id at all — OpenTelemetry
absent, or the request predates `quarkus-opentelemetry` — the endpoint honestly returns `available: false` with a clear
reason (`RequestProfileDto.unavailable(...)`) rather than fabricating a partial result. In the main feed,
`ActivityEntryDto.profileable` is computed adapter-side in `LiveActivityResource`: `true` for request entries with a
resolvable trace id, `false` otherwise; the shared engine's own `profileable` default is untouched, so Spring is
unaffected.

:::

::: details The optional durable JDBC persistence backend

`bootui.activity.persistence.enabled` is implemented identically to Spring. Every engine class — `ActivityStore`,
`InMemoryActivityStore`, `JdbcActivityStore`, `BufferedActivityStore`, `ActivityCaptureCoordinator`/`ActivityCapturePoller`,
`ActivityInstanceIds`, `ActivityStoreFactory`, `ActivityPersistenceSettings`, `SimpleDriverDataSource`,
`BootUiJdbcCaptureGuard` — is 100% framework-neutral and reused unchanged; nothing in `bootui-engine` needed to change.

`BootUiEngineProducer` `@Produces` the `ActivityPersistenceSettings` (read live from MicroProfile `Config`, matching
Spring's relaxed-binding defaults) and `ActivityStore` (built by the same `ActivityStoreFactory.create(...)`, resolving a
`DataSource` through `Instance<DataSource>` for `SHARED` mode) beans **unconditionally** — like the
Cache/Flyway/Liquibase/Connection-Pools producers — since `ActivityStoreFactory` itself renders a harmless
`InMemoryActivityStore` when disabled. So there is no capability gate, no `ExcludedTypeBuildItem`, and no R2 classloading
trap (`javax.sql.DataSource` is a JDK type, not an optional library). Because both beans always exist,
`LiveActivityResource` injects `ActivityStore` and `ActivityPersistenceSettings` directly (no `Instance<>` wrapper) and
branches its `activity()` method on `persistenceSettings.enabled()` exactly like Spring's controller.

Unlike Spring — whose inferred-destroy-method convention auto-closes the `ActivityStore` bean at context shutdown —
CDI/Arc has no equivalent automatic behavior, so a small dedicated lifecycle bean, `QuarkusActivityCapture`, fills the
gap. `@Observes StartupEvent` builds the sequencer/coordinator/poller and starts polling
`LiveActivityResource#mergedReport` on `bootui.activity.persistence.capture-interval` (only when enabled). `@Observes
ShutdownEvent` stops the poller — making one last synchronous capture pass first, so entries produced since the last tick
aren't dropped — then unconditionally closes the store, which flushes any still-buffered entries with the same bounded
(2-10s), never-blocks-shutdown guarantee `BufferedActivityStore#close()` gives Spring.

One honest, pre-existing divergence: Quarkus's baseline (persistence-disabled) feed has no server-side
`type`/`severity`/`since` filtering to begin with. Unlike Spring's separate `LiveActivityService`, the shared
`LiveActivityAssembler` Quarkus's resource calls has no such filters, so those filters take effect on Quarkus only once
persistence is enabled and the query is served from the `ActivityStore`. The KPI strip stays computed from the full,
unfiltered live merge either way, on both adapters.

:::

::: details Live flow service map, SSE subscriptions, and the datasource hot-switch

**Service map (`GET /bootui/api/activity/service-map`) is implemented identically to Spring.** `LiveServiceMapResource`
is a thin JAX-RS binding that gathers evidence from beans this adapter already owns — the Vert.x-fed `HttpExchangeBuffer`
(through the shared `HttpExchangesService`, so self-filtering and masking are inherited), the `RestClientTraceRecorder`,
the Agroal-backed `ConnectionPoolService`, the optional `Instance<SqlTraceRecorder>`, and the shared
`KafkaActivityRecorder`/`RabbitActivityRecorder`. It delegates every interpretation rule to the framework-neutral
`ServiceMapAssembler`, so the wire contract is byte-identical across all three runtimes. Each source is gated on both
`QuarkusPanelAvailability.isPanelAvailable` and `isPanelEnabled`, so a panel unavailable on this runtime or switched off
contributes nothing rather than appearing as a dependency with no traffic; an unsatisfied `SqlTraceRecorder` (no Agroal
datasource) simply yields no statement evidence. The resource is registered through
`BootUiQuarkusProcessor.addBeanClasses(...)` like every other runtime resource, is strictly read-only, and performs no
network call, probe, or scan. `sources()` always passes `cacheAvailable: false` with an empty event list — this runtime
has no `CacheActivityRecorder`-equivalent bean, for the identical reason its feed carries no `CACHE` entries — so the map
never draws a cache dependency it cannot honestly back with evidence.

**The opaque `ServiceMapInteractionDto.flowId` needed no Quarkus-specific work.** `ServiceMapAssembler` derives it one-way
from whatever distributed-trace id was already captured on the `HttpExchangeBuffer`/`RestClientTraceRecorder`/
`SqlTraceRecorder` entries this resource reads — the same capability-gated `QuarkusOtelTraceIdProvider` stamping. So
wherever `quarkus-opentelemetry` is present, an inbound request, its SQL, and any outbound REST call sharing one trace
already correlate into one flow with no additional code. Only cache participates on Spring MVC/WebFlux alone, since
Quarkus captures no cache evidence to correlate.

**Two SSE source-subscription gaps are now closed.** `LiveActivityResource#stream` previously fanned in the HTTP exchange
buffer, scheduled-task runs, Kafka, RabbitMQ, captured email, and REST Client calls, but neither the SQL trace recorder
nor the exception store — both of which already fed `activity()`. A purely database-driven or purely failing workload
therefore left the panel silently stale until an unrelated signal arrived. Both now subscribe like every other source
(the SQL recorder through a resolvable-guarded change source, since it is an optional bean), which also keeps the Live
flow map — refreshing off the same tick — honest for JDBC and failure evidence. The shared Quarkus SSE helper coalesces
any number of notifications into one pending update while downstream has no demand, rather than using an unbounded
buffer; normal delivery and the existing per-resource maximum-open-stream limit remain unchanged.

**The runtime "Use the existing datasource" hot-switch is also implemented identically to Spring.** The shared engine
`ActivitySwitchService` — which verifies/creates the backing table over the resolved `DataSource` and atomically swaps
the live `SwitchableActivityStore`'s delegate from `InMemoryActivityStore` to a newly built durable `BufferedActivityStore`,
starting its capture poller in the same step — is reused unchanged. `BootUiEngineProducer` `@Produces` a
`SwitchableActivityStore` in front of it, rather than producing `ActivityStore` directly. A thin
`LiveActivityResource#useExistingDatasource` mirrors Spring's controller method: 404 when no `DataSource` is present, 400
when the request is not explicitly confirmed, 200-and-no-op when persistence is already active, and 200 with the store
swapped and capturing on success — all behind the shared `LocalhostGuard` write floor. Every `GET /bootui/api/activity`
response carries the same `persistenceOption` field (`{"active": false, "dataSourceAvailable": true, "tableName":
"bootui_activity"}`) the Vue UI reads to render the "Currently saving N events in memory" tip and the **Use a database**
button/confirmation flow, so the panel behaves and looks identical on both adapters.

:::

### 5.4 Replaced with a Quarkus-native panel (3)

| Spring panel        | Quarkus replacement                                                                                    |
| ------------------- | ------------------------------------------------------------------------------------------------------ |
| `Spring` advisor    | **Implemented** — **`Quarkus` advisor**: new Quarkus-native ruleset over the shared scanning engine (CDI/Arc scopes, build-time config, reactive idioms, profiles) under the same panel id `spring` + `/bootui/api/spring` + `SpringReport`. See [QUARKUS-ADVISOR-CHECKS.md](QUARKUS-ADVISOR-CHECKS.md) |
| `Cache`             | **Implemented** — served over `quarkus-cache` (Caffeine) under the shared id `cache`; cache names + Micrometer metrics + clear, with an empty operations list (caching annotations are build-time woven). Tiering is reported (one local Caffeine tier per cache, with the maximum size and expiry configured under `quarkus.cache.caffeine."<name>".*`), but **native hit/miss statistics are honestly unavailable**: the public `io.quarkus.cache.CaffeineCache` interface exposes only `keySet`/`getIfPresent`/`put`/`setExpireAfter*`/`setMaximumSize` and no statistics accessor, and reaching into the internal `CaffeineCacheImpl` would violate R2. The panel states that reason and points at Micrometer cache metrics instead. |
| `Security` advisor  | **Implemented** — a Quarkus-native ruleset (Elytron/OIDC, `quarkus.http.auth.permission.*`, TLS, CORS, Jakarta security annotations, `@PermissionsAllowed`, and `@AuthorizationPolicy`) under the same panel id `security`, replacing the Spring-Security-coupled checks. See [QUARKUS-CHECKS.md](QUARKUS-CHECKS.md) |

### 5.5 Dropped on Quarkus (9)

No equivalent, low value, or superseded by Quarkus's own tooling:

- **Build-time model differences:** `Conditions` (Quarkus resolves conditions at build time — no runtime report),
  `Startup Timeline` (build-time augmentation eliminates startup steps — there is no runtime per-step buffer like
  Spring's `BufferingApplicationStartup`, only coarse boot totals — so it is reported *not applicable*, not
  *not yet*, alongside GraalVM/CRaC).
- **Different security/data stacks:** `Spring Security`, `Spring Data` (Quarkus uses Elytron/OIDC and Panache).
- **Hibernate advisor query rules:** mapping/configuration rules run against Quarkus' JPA metamodel, but HIB-QUERY rules
  remain unavailable because Panache exposes no reliable runtime equivalent of Spring Data repository query metadata.
- **Servlet-only / low value on a reactive stack:** `HTTP Sessions`.
- **Superseded or moot:**
  - `GraalVM` readiness — Quarkus is native-first with its own build.
  - `CRaC` — BootUI's advisor and generated assets depend on Spring `LifecycleProcessor`, Spring scheduling, Spring Boot
    Hikari integration, and `spring.context.checkpoint=onRefresh`, so partial reuse would be misleading.
  - `DevTools` (**Implemented as `NOT_APPLICABLE`**) — Quarkus has built-in dev-mode live reload, so there is no
    Spring-style DevTools restart/LiveReload to expose; the panel reports *not applicable* rather than *not yet*.
- **No comparable capture hook:** `Transactions` — capture relies on Spring Framework's `TransactionExecutionListener`,
  registered against `ConfigurableTransactionManager` beans. Quarkus's transaction management goes through Narayana's
  JTA `TransactionManager`/`Synchronization` or the CDI `@Transactional` interceptor, neither of which exposes a
  comparable per-boundary listener without far more invasive instrumentation (wrapping every `@Transactional` bean with
  a custom interceptor ordered ahead of every other interceptor); the panel honestly reports *not applicable* rather
  than forcing a lower-fidelity capture path.

### 5.6 Not yet available on Quarkus (1)

- `JMS` uses Spring JMS (`JmsTemplate` and `@JmsListener`) today. Quarkus users can use the implemented Kafka and RabbitMQ
  panels while a Quarkus-native JMS capture layer remains unimplemented.

**Result:** 47 of the 57 panels ship on Quarkus: 26 are statically available and 21 are capability/detector-gated. The
remaining 10 panels do not ship: 9 are intentionally not applicable (GraalVM, CRaC, Conditions, Startup Timeline, HTTP
Sessions, Spring Data, Spring Security, DevTools, Transactions), and 1 (`JMS`) is not yet available. By portability
strategy, the 47 shipped panels comprise 19 ported as-is, 12 source-swapped, 13 capture-rebuilt, and 3 replaced with a
Quarkus-native panel. The Overview dashboard panel is available (its scoring dashboard renders client-side from the
advisor endpoints, and the shell-chrome `GET /bootui/api/overview` endpoint is served on both adapters).

## 6. Activation & safety on Quarkus

- **Dev-mode-only extension.** The `bootui-quarkus-deployment` module registers BootUI's routes and beans **only in dev
  mode** via `@BuildStep`. This is the natural Quarkus analogue of BootUI's "active only in `dev`/`local`, fail closed"
  rule, and it means BootUI is simply absent from production/native builds.
- **The static shell is dark in production too, not just the API.** The launch-mode-gated `@BuildStep`s above stop
  BootUI's CDI beans/JAX-RS resources from being wired in `LaunchMode.NORMAL`, but the compiled Vue bundle at
  `META-INF/resources/bootui/` used to remain reachable regardless. `BootUiProdShellGuardFilter` closes this, at parity
  with the Spring adapter's `BootUiShellGuardAutoConfiguration` (#856).

  ::: details How the production shell guard works

  Quarkus' built-in static-resource handler serves any classpath resource under `META-INF/resources/**`
  unconditionally, wired by `quarkus-vertx-http` independently of this extension's build steps, and Quarkus offers no
  build-time mechanism to exclude a single path from that scan. Left alone, a production deployment would still answer
  `GET /bootui/` with the empty SPA shell's `index.html`/JS/CSS — no working API behind it, but reachable.

  `BootUiProdShellGuardFilter` is a CDI Vert.x filter registered by its own **always-on** `@BuildStep` (deliberately
  *not* launch-mode-gated, the opposite polarity from every other build step in the extension). Its `handle()` method
  reads a CDI-injected `LaunchMode` and answers a plain `404` for the configured UI/API surface and the private
  `/bootui` classpath mount only when it is `LaunchMode.NORMAL` — an immediate no-op pass-through otherwise, so
  dev/`@QuarkusTest` behavior is unaffected. Net effect: the public and internal BootUI paths are plain 404s in
  production, at parity with the Spring adapter, whose `BootUiShellGuardAutoConfiguration` answers the same 404 for the
  same reserved `/bootui` mount whenever BootUI's activation condition resolves to disabled (Spring Boot's default
  static-resource handling exposed the packaged shell there for the same reason, #856).

  Proven by a genuine `LaunchMode.NORMAL` build+run via `QuarkusProdModeTest` (`BootUiQuarkusProdShellGuardBootTest`,
  in the dedicated `bootui-quarkus-prod-shell-guard-integration-tests` module — kept separate from every
  `@QuarkusTest`-based module because Quarkus's own test framework refuses to mix `QuarkusProdModeTest` and
  `@QuarkusTest` in the same Surefire fork), alongside a white-box unit suite (`BootUiProdShellGuardFilterTest`, in
  `bootui-quarkus`). See `BootUiQuarkusProcessor`'s class Javadoc for the full investigation.

  :::
- **Native is therefore a non-issue.** Quarkus dev mode always runs on the JVM, so the bytecode-scanning advisors,
  classpath Maven metadata (`Vulnerabilities`), and JVM MXBeans all work exactly as on Spring. The native-image
  limitations (no runtime classpath scan, stripped metadata) only apply to production native images, which BootUI never
  ships in.
- **Loopback safety preserved.** The shared `LocalhostGuard` enforces the same loopback / allowed-hosts / CSRF rules; the
  Quarkus adapter binds it as a high-priority Vert.x handler after configured public requests have been rerouted to the
  private mount, failing closed for non-loopback callers — matching `LocalhostOnlyFilter`'s `Integer.MIN_VALUE` servlet
  ordering.
- **Per-panel access gating at parity.** `QuarkusPanelAccessFilter` enforces `bootui.panels.<id>.enabled` /
  `.read-only` and the global `bootui.read-only`, mirroring Spring's `PanelAccessFilter` exactly (same config keys,
  same `BootUiPanels` path resolution, same canonical `{"error":"BootUI panel access denied","panel":"<id>",
  "reason":"<reason>"}` JSON 403 body). It runs as a lower-priority Vert.x filter than `BootUiQuarkusSafetyFilter`, so
  the loopback/Host/CSRF guard always evaluates first. `QuarkusPanelAvailability` and `QuarkusMcpPanelPolicy` (the MCP
  tool gate) both read the same config, so a disabled or read-only panel is refused consistently across the REST API,
  the `/bootui/api/panels` manifest, and the MCP bridge. Backend/integration coverage lives alongside the runtime
  classes. **Browser-level** coverage is `bootui-quarkus-sample-app/e2e/tests/read-only.spec.js`, the Quarkus twin of
  the Spring sample's `read-only.spec.js`. It spawns fresh, throwaway `quarkus:dev` instances with `bootui.read-only` /
  `bootui.panels.<id>.read-only` passed as plain JVM system properties (MicroProfile Config picks these up live, the
  same mechanism `playwright.config.js` already relies on for `-Dquarkus.http.port`). It asserts both the API (403 +
  canonical reason) and the shared Vue UI (disabled controls, the `panel-read-only-alert` banner) reflect the setting.
  One divergence from the Spring spec: it cannot use the `config` panel as the "unaffected control panel" the way Spring
  does, because Configuration is *always* read-only on Quarkus for an unrelated reason (no runtime-override write path
  yet — see above); it uses `memory` instead.

### Configurable path routing

`bootui.path` and `bootui.api-path` use the same normalized contract as the Spring adapters. `/bootui` and
`/bootui/api` remain the defaults. The public paths compose with `quarkus.http.root-path`; the host root is prepended
exactly once to browser-visible links, the startup banner, session-cookie scope, and production suppression.

Quarkus' runtime and static resources remain registered at a private `/bootui` classpath/JAX-RS mount. An early Vert.x
filter recognizes only the configured public UI/API paths and reroutes them internally without redirecting the browser.
It preserves the query string, marks the routing context before rerouting to prevent recursion, and sends the bare and
trailing-slash shell paths through the index resource so both receive runtime `<base>` and API metadata. When
`bootui.path` is custom, direct requests to the private `/bootui` mount return 404; it is not exposed as a legacy alias.
The later safety, authentication, panel-access, and response-header filters therefore continue to enforce one internal
route shape without weakening the public boundary.

The UI path cannot be nested under `/bootui/**`, which avoids collisions with that private mount. Invalid active
configuration fails dev/test startup. Production remains dark even if dormant path settings are invalid: the
always-registered production guard uses fail-closed safe defaults and suppresses both configured/default and private
mounts without wiring data-bearing resources.

BootUI's own requests are excluded from the telemetry it reports on. HTTP-exchange capture, request-failure capture,
pre-mapping exception capture, and log-based exception capture all resolve "is this BootUI?" through one shared matcher
that strips `quarkus.http.root-path` and matches the configured `bootui.path` / `bootui.api-path` mounts as well as the
private `/bootui` mount. The console therefore never appears in HTTP Exchanges, Live Activity, or Exceptions under a
custom root path or a custom mount, while application paths that merely resemble the console — `/bootui-other`, for
instance — remain captured.

## 7. Code-sharing scorecard

| Layer                                                                                                                 | Shared?          | Notes                                                                            |
| --------------------------------------------------------------------------------------------------------------------- | ---------------- | -------------------------------------------------------------------------------- |
| DTOs (`bootui-core`)                                                                                                  | ✅ 100%          | Already Spring-free                                                              |
| Vue UI (`bootui-ui`)                                                                                                  | ✅ 100%          | Built once; panel set driven by `/api/panels` manifest                           |
| Advisor engines, OSV, OTLP/telemetry, dependency catalog, JVM readers, scoring, MCP, secret masking (`bootui-engine`) | ✅ majority      | Today 80–90% Spring-free; refactor extracts the few coupled files behind the SPI |
| Web binding                                                                                                           | ❌ per-framework | Thin controllers/resources (~10 lines each) delegating to shared services        |
| Data-source SPI impls                                                                                                 | ❌ per-framework | ~15–20 small adapter classes per framework                                       |
| Safety binding & activation                                                                                           | ❌ per-framework | Shared decision logic; per-framework request plumbing                            |

The Quarkus-specific code is concentrated in the SPI implementations, the thin web layer, and the extension plumbing —
deliberately small relative to the shared engine and UI.

## 8. Quarkus sample app & end-to-end testing

Quarkus support needs its own reference application, mirroring `bootui-spring-sample-app`: a new `bootui-quarkus-sample-app`
reactor module that is a deliberately feature-rich Quarkus **dev** application exercising every supported panel, plus a
parallel Playwright suite. Like the Spring sample app it is **demo/integration only** and must set
`<maven.deploy.skip>true</maven.deploy.skip>` so it is never published to Maven Central, while still building as part of
the reactor.

Its job is the same as the Spring sample app's: give every panel realistic, non-sensitive data and seed intentional
anti-patterns so the advisors and the Overview score produce demonstrable findings (and so screenshots show populated
panels, per the contributor guidance).

### 8.1 Ingredient mapping

The Spring sample app already wires up one feature per panel. The Quarkus sample app mirrors each with its Quarkus
counterpart; ingredients for dropped/replaced panels are swapped or omitted.

| Spring sample app provides                                                         | Drives panel(s)                        | Quarkus sample app equivalent                                            |
| ---------------------------------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------ |
| `bootui-spring-boot-starter` (dev profile)                                         | activation                             | `bootui-quarkus` extension (dev mode)                                    |
| `spring-boot-starter-data-jpa` + JPA entities/repos with intentional anti-patterns | Hibernate advisor, DB pools, SQL Trace | `quarkus-hibernate-orm-panache` with the same intentional anti-patterns  |
| `@RestController`s (Hello/Admin/Sample + `ArchitectureIssuesController`)           | REST API, Mappings, Architecture       | `quarkus-rest` (JAX-RS) resources with equivalent issues                 |
| `spring-ai-starter-model-ollama` + `ChatController`                                | AI Framework, Traces                   | `quarkus-langchain4j-ollama` + `quarkus-opentelemetry`                   |
| `@Scheduled` `EchoScheduler`                                                       | Scheduled Tasks                        | `quarkus-scheduler` `@Scheduled`                                         |
| Flyway **and** Liquibase (both, same changelogs)                                   | Flyway, Liquibase                      | `quarkus-flyway` + `quarkus-liquibase` (reuse the migrations/changelogs) |
| `spring-boot-starter-cache` + Redis                                                | Spring Cache → **Quarkus Cache**       | `quarkus-cache`                                                          |
| `spring-boot-starter-security` + `SecurityConfiguration` + `AdminController`       | Security Logs, **Quarkus advisor**     | `quarkus-security` + basic/OIDC, protected resource                      |
| `compose.yaml` (Postgres, Redis) + `spring-boot-docker-compose`                    | Dev Services                           | Quarkus **Dev Services** (zero-config Postgres/Redis containers)         |
| `spring-boot-starter-actuator`                                                     | Health, Metrics, Loggers               | `quarkus-smallrye-health`, `quarkus-micrometer`                          |
| `crac`, `NativeHintsConfiguration`                                                 | CRaC, GraalVM                          | omitted (panels dropped on Quarkus)                                      |

Pure-JVM panels (Memory, Threads, Heap Dump, …) and host-level panels (GitHub, Copilot, Claude Code, Vulnerabilities,
Pentesting, HTTP Probe, MCP Server) need no special ingredients — they work against any running Quarkus dev app.

### 8.2 Dev loop & CI

- **Run:** `./mvnw -pl bootui-quarkus-sample-app -am quarkus:dev` starts Quarkus dev mode and serves the console at
  `http://localhost:8082/bootui` — the analogue of the Spring sample app's `spring-boot:run` smoke-test path. The sample
  app defaults to 8082, not 8080, so it can run alongside the Spring servlet sample app (8080) and the Spring WebFlux
  sample app (8081) for the cross-service trace demo (see `bootui-spring-sample-app/README.md`). Quarkus live reload
  replaces DevTools for the inner loop. (`-am` builds the upstream `bootui-quarkus` extension first.)
- **e2e:** a `bootui-quarkus-sample-app/e2e/` Playwright project mirrors `bootui-spring-sample-app/e2e/`, with one spec per
  supported panel plus `quarkus-advisor.spec.js` / `cache.spec.js`; drop specs only for the panels genuinely not shipped
  on Quarkus (`conditions`, `startup`, `spring-security`, `data`, `http-sessions`, `graalvm`, `crac`, `devtools`) covered
  instead by `not-applicable.spec.js`. (An earlier revision of this plan also listed `beans`, `profile-diff`, and
  `security` as drop candidates; all three shipped and now have dedicated specs — see §5 for the authoritative
  per-panel availability.) Reuse the existing `fixtures.js` / `app-shell.spec.js` patterns. To honor the "as much common
  code as possible" goal, factor the panel-agnostic Playwright helpers into a shared library both suites import, rather
  than copying them.
- **CI:** add a job mirroring the existing Spring e2e job in `.github/workflows/build.yml` — build the extension + sample
  app, `npx playwright install --with-deps chromium`, then `npm test` — so **both** platforms are gated on every build.
- **Screenshots:** the docs screenshots in `docs/images/bootui-*.webp` are captured from the Spring sample app and stay
  the canonical set. The Quarkus sample app's e2e can reuse `scripts/capture-docs-screenshots.mjs` if/when the docs add
  Quarkus-specific imagery; otherwise it is test-only.

## 9. Phased delivery

1. **Phase 0 — Refactor in place (no behavior change).** Introduce `bootui-engine` and its
   `io.github.jdubois.bootui.spi` package; move
   framework-neutral services and advisor engines out of `bootui-spring-autoconfigure`; reimplement the Spring controllers as
   thin bindings over the shared services; extract `LocalhostGuard`. Spring BootUI must stay green (all existing JUnit,
   Vitest, and Playwright suites pass) — this phase ships value even without Quarkus.
2. **Phase 1 — Quarkus MVP.** Stand up `bootui-quarkus` + `-deployment`, serve the shared UI at `/bootui/`, wire the
   Vert.x safety handler and dev-mode activation, and light up §5.1 + §5.2 panels (the framework-agnostic and
   source-swapped sets). Stand up `bootui-quarkus-sample-app` and its parallel Playwright run (§8) to gate the work.
3. **Phase 2 — Advisors & replacements.** Port the `REST API` handler model to JAX-RS, add the `Quarkus` advisor ruleset,
   and add the `Quarkus Cache` panel. Grow the sample app and e2e specs to match.
4. **Phase 3 — Capture panels.** Implement the Vert.x/Agroal/LogManager-based capture for the §5.3 set.

## 10. Risks & open questions

- **Overlap with Quarkus Dev UI.** The source-swapped runtime panels (Config, Health, Mappings, Scheduled, …) duplicate
  Quarkus's built-in Dev UI. BootUI's net-new value on Quarkus is the **advisor + scoring + agent (MCP) loop** — which is
  also the most portable part. Lead with it; treat generic runtime panels as table stakes.
- **Build-time augmentation effort.** A Quarkus extension (runtime + deployment + `@BuildStep`s) is more involved than a
  Spring auto-configuration. Phase 0/1 should validate the route + dev-mode wiring early.
- **Reactive capture fidelity.** Vert.x-based request/exchange/SQL capture must be verified to match the servlet panels'
  detail (timing, headers, correlation). Correlation is now resolved via the OpenTelemetry trace id: Live Activity nests
  SQL/exceptions/security events under their request, and stamps `securedPrincipal`, when `quarkus-opentelemetry` is
  present. The per-request profile drill-down is now implemented too, but in reduced, trace-id-only form. Spring's
  time-window/thread-based tiers lean on servlet thread-per-request serving-thread identity that the Vert.x model has no
  equivalent for, so they remain deliberately unported (§5.3 has the detailed reasoning).
- **Module naming & coordinates.** New shared/adapter modules keep `com.julien-dubois.bootui:*` coordinates and
  `io.github.jdubois.bootui.*` packages; the Quarkus extension follows Quarkus's `runtime` / `deployment` convention.
- **Docs & checks.** The Quarkus application advisor is backed by `docs/QUARKUS-ADVISOR-CHECKS.md` and the Quarkus
  Security advisor by `docs/QUARKUS-CHECKS.md`, mirroring the existing
  `*-CHECKS.md` files; `docs/features/` would gain a per-platform availability note; and the contributor docs
  (`CONTRIBUTING.md`, AI instructions) would document the second sample app and its e2e suite.

## 11. Appendix — full panel disposition

`Port` = ships from shared code · `Adapt` = swap data source via SPI · `Rebuild` = reimplement capture ·
`Replace` = Quarkus-native panel · `Drop` = intentionally not applicable · `Not yet` = not currently shipped.

| Panel               | Tier        | Quarkus | Shared component                 | Quarkus adapter / reason                    |
| ------------------- | ----------- | ------- | -------------------------------- | ------------------------------------------- |
| Memory              | as-is       | Port    | JVM memory reader                | —                                           |
| Live Memory         | as-is       | Port    | JVM memory reader                | —                                           |
| JVM Tuning          | as-is       | Port    | JVM flags reader                 | —                                           |
| Heap Dump           | as-is       | Port    | HotSpotDiagnostic reader         | —                                           |
| Threads             | as-is       | Port    | ThreadMXBean reader              | —                                           |
| Metrics             | as-is       | Port    | Micrometer reader                | `MeterRegistrySupplier`                     |
| Hibernate           | as-is       | Port    | Hibernate advisor engine         | `EntityManagerFactoryProvider`              |
| Hibernate Statistics | as-is      | Port    | `HibernateStatisticsService`     | `HibernateStatisticsProvider` (same Hibernate ORM capability gate as the Hibernate advisor) |
| Database            | as-is       | Port    | Database advisor rule engine     | `DataSourceProvider` (Agroal `@DataSource` qualifier names read reflectively, positional fallback; SQL Trace wrapper de-duplicated to its physical pool; `javax.sql.DataSource` is unconditional, no capability gating) |
| Vulnerabilities     | as-is       | Port    | OSV scanner + dependency catalog | —                                           |
| Pentesting          | as-is       | Port    | Pentesting engine                | CORS/OIDC/TLS metadata; Spring endpoint inventory explicitly unavailable |
| HTTP Probe          | as-is       | Port    | HTTP probe service               | —                                           |
| AI Framework        | as-is       | Port    | TelemetryStore (OTLP)            | —                                           |
| Traces              | as-is       | Port    | OTLP receiver + TelemetryStore   | —                                           |
| GitHub              | as-is       | Port    | GitHub `HttpClient` service      | —                                           |
| Copilot             | as-is       | Port    | CLI log reader                   | —                                           |
| Claude Code         | as-is       | Port    | CLI log reader                   | —                                           |
| MCP Server          | as-is       | Port    | BootUI MCP server                | —                                           |
| Dev Services        | as-is       | Port    | Dev Services model               | Quarkus Dev Services source                 |
| Overview            | equiv       | Adapt   | Client-side dashboard + `OverviewDto` | `QuarkusApplicationInfo` (chrome; scoring is client-side) |
| Health              | equiv       | Adapt   | Health mapper                    | `HealthProvider` → SmallRye Health          |
| Configuration       | equiv       | Adapt   | Config mapper + masking          | `EnvironmentProvider` → SmallRye Config     |
| Loggers             | equiv       | Adapt   | Logger mapper                    | `LoggerProvider` → JBoss LogManager         |
| Mappings            | equiv       | Adapt   | Mapping mapper                   | `MappingProvider` → Vert.x/RESTEasy         |
| Flyway              | equiv       | Adapt   | Flyway mapper                    | `MigrationProvider` → quarkus-flyway        |
| Liquibase           | equiv       | Adapt   | Liquibase mapper                 | `MigrationProvider` → quarkus-liquibase     |
| Scheduled Tasks     | equiv       | Adapt   | Scheduled mapper                 | `ScheduledTaskProvider` → quarkus-scheduler |
| Fault Tolerance     | equiv       | Adapt   | `FaultToleranceService` + DTO    | `FaultTolerancePolicyProvider` → SmallRye Fault Tolerance (Jandex-scanned declarations, MicroProfile config overrides, live named-breaker state) |
| Architecture        | equiv       | Adapt   | ArchUnit engine                  | `BasePackageProvider` (rules run unmodified) |
| REST API            | **done**    | Rebuild | REST conventions engine          | JAX-RS handler-model builder                |
| Database Connection Pools | **done**    | Rebuild | Pool model                       | `DataSourcePoolProvider` → Agroal           |
| SQL Trace           | **done**    | Rebuild | SQL trace model                  | `SqlTraceSource` → Agroal/JDBC              |
| Live Activity       | **done**    | Rebuild | Activity model                   | `RequestCaptureSource` → Vert.x; OTel trace-id correlation + trace-id-only profile drill-down; optional JDBC persistence backend via `QuarkusActivityCapture` (unconditional producers, identical to Spring); Kafka and RabbitMQ messaging capture via SmallRye `Outgoing`/`IncomingInterceptor` feeding the shared transport recorders; captured email (`MAIL`) reuses the shared `EmailCaptureService` directly, no separate capture needed |
| HTTP Exchanges      | **done**    | Rebuild | Exchange model                   | `HttpExchangeProvider` → Vert.x             |
| Exceptions          | **done**    | Rebuild | Exception model                   | log handler + Vert.x failure handler + `PreExceptionMapperHandlerBuildItem` |
| Security Logs       | **done**    | Rebuild | Audit model                      | `AuditEventProvider` → CDI events           |
| Log Tail            | **done**    | Rebuild | Log tail model                   | `LogCaptureSource` → JBoss LogManager       |
| Email               | **done**    | Rebuild | Email capture service            | CDI `@Observes SentMail` observer → quarkus-mailer |
| Kafka               | **done**    | Rebuild | `KafkaActivityRecorder`          | SmallRye `Outgoing`/`IncomingInterceptor` (`Capability.KAFKA`-gated); same recorder as Live Activity |
| RabbitMQ            | **done**    | Rebuild | `RabbitActivityRecorder`         | SmallRye `Outgoing`/`IncomingInterceptor` (`quarkus-messaging-rabbitmq` class-presence-gated); same recorder as Live Activity |
| JMS                 | spring-only | Not yet | `JmsActivityRecorder`            | Quarkus JMS capture not yet implemented; use Kafka/RabbitMQ panels |
| REST Client         | **done**    | Rebuild | `RestClientTraceRecorder`        | `Capability.REST_CLIENT_REACTIVE`-gated generated `RestClientListener` service provider → metadata-only `QuarkusRestClientTraceFilter`; URI sanitization, status-0 transport failures, trace correlation, SSE/actions, and absent-extension type exclusion |
| WebSockets          | **done**    | Rebuild | `WebSocketService`               | Build-time Jandex capture of `@WebSocket` endpoints into a synthetic `QuarkusWebSockets` bean plus live `OpenConnections`/`@Open`/`@Closed` session tracking (`quarkus-websockets-next` class-presence-gated, absent-extension type exclusion); metadata only — no message-interception SPI, so no frame capture |
| Spring              | **done**    | Replace | Scanning engine                  | new `Quarkus` advisor ruleset               |
| Cache               | **done**    | Replace | Cache model                      | `CacheProvider` → quarkus-cache             |
| Beans               | **done**    | Adapt   | Beans service                    | `BeanProvider` → Arc (build-time; low fidelity) |
| Profile Diff        | **done**    | Adapt   | Config service                   | `ConfigProvider` → SmallRye profiles        |
| Security            | **done**    | Replace | Quarkus security ruleset         | Quarkus-native checks (OIDC/auth/TLS/CORS/annotations); see QUARKUS-CHECKS.md |
| GraalVM             | **done**    | Drop    | —                                | Quarkus native-first; `NOT_APPLICABLE`      |
| CRaC                | **done**    | Drop    | —                                | Spring lifecycle-specific; `NOT_APPLICABLE` |
| DevTools            | **done**    | Drop    | —                                | Quarkus live reload built in; `NOT_APPLICABLE` |
| Conditions          | spring-only | Drop    | —                                | no runtime conditions report                |
| Startup Timeline    | spring-only | Drop    | —                                | not applicable: build-time augmentation, no runtime per-step buffer |
| Spring Security     | spring-only | Drop    | —                                | Elytron/OIDC, different model               |
| Spring Data         | spring-only | Drop    | —                                | Panache, different model                    |
| HTTP Sessions       | spring-only | Drop    | —                                | reactive/stateless stack                    |
| Transactions        | spring-only | Drop    | —                                | `TransactionExecutionListener` has no Narayana/CDI-interceptor equivalent |
