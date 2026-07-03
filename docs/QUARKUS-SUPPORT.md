# BootUI on Quarkus — design & strategy

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
   JSON at `/bootui/api/**`.
3. **One engine.** Advisor rule engines, scanners, the OSV scanner, the OTLP/telemetry store, JVM/MXBean readers, the
   dependency catalog, secret masking, scoring, and the MCP server move into a shared, Spring-free engine module.
4. **Thin per-framework adapters.** Spring and Quarkus each provide: a web binding (Spring MVC controllers vs JAX-RS /
   Vert.x routes), implementations of a small portability SPI, a safety-filter binding, and an activation hook.

### Non-goals

- **Native-image BootUI.** BootUI is dev-only; Quarkus dev mode is always JVM mode (see §6), so native is out of scope.
- **Integrating into Quarkus's built-in Dev UI** (`/q/dev`). BootUI keeps its own console at `/bootui/`. Quarkus Dev UI
  uses build-time Lit web components; reusing our Vue UI as a standalone console is what keeps the UI shared.
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
| The advisor **engines** are almost Spring-free    | Spring imports per package: architecture `2/12`, memory `1/10`, restapi `2/14`, pentesting `2/16`, hibernate `3/10`. The Spring files are the **controller** and base-package discovery — not the rules |
| Hibernate analysis already uses neutral APIs      | `HibernateScanner` consumes `jakarta.persistence.EntityManagerFactory` / `Metamodel`, which Quarkus also provides                                                                                       |
| Safety decision logic is already Spring-free      | `CidrRange` and `ContainerGatewayDetector` carry no Spring dependency; only `LocalhostOnlyFilter` / `PanelAccessFilter` bind to `jakarta.servlet`                                                       |
| Several data sources are framework-neutral today  | OSV scanner, OTLP receiver + `TelemetryStore`, `DependencyCatalog` (reads `META-INF/maven/*/pom.properties` + `java.class.path`), JVM MXBean readers, GitHub `HttpClient`, Copilot/Claude log readers   |

The coupling that remains is concentrated in: the web layer (`@RestController`), the Actuator/`ApplicationContext`/
`Environment` data readers, the servlet safety filters, and the `EnvironmentPostProcessor`-based config plumbing. That is
exactly the surface the SPI seam abstracts.

## 3. Target module topology

> **Update (post-merge):** the `bootui-spi` module described below was ultimately **folded into `bootui-engine`** as the
> `io.github.jdubois.bootui.spi` package, leaving three shared modules (`bootui-core`, `bootui-engine`, `bootui-conformance`).
> The SPI interfaces, providers, and the framework-neutral boundary (now pinned by `SpiBoundaryArchitectureTests` inside
> `bootui-engine`) are unchanged — only the module/POM boundary went away. Read every `bootui-spi` mention below as "the
> `spi` package within `bootui-engine`".

Current modules and their proposed roles:

```
SHARED (framework-neutral, built once, reused by both backends)
  bootui-core            DTO records, SecretMasker, BootUiInfo               (unchanged — already Spring-free)
  bootui-spi             NEW: small interfaces per data-source category
  bootui-engine          NEW: framework-neutral services & advisor engines,
                         OSV scanner, OTLP/TelemetryStore, dependency catalog,
                         JVM/MXBean readers, scoring, MCP server
                         (depends on: bootui-core, bootui-spi)
  bootui-ui              Vue 3 SPA + REST contract                            (unchanged — built once)

SPRING ADAPTER
  bootui-spring-autoconfigure        Spring MVC controllers (thin) + SPI impls over
                              Actuator/ApplicationContext/Environment +
                              servlet safety filters + EnvironmentPostProcessors
  bootui-spring-boot-starter  Drop-in starter                                 (unchanged role)
  bootui-spring-sample-app           Demo/integration app + Playwright e2e           (unchanged)

QUARKUS ADAPTER
  bootui-quarkus              NEW (runtime): JAX-RS/Vert.x resources (thin) +
                              SPI impls over SmallRye/Arc/Micrometer/Agroal/
                              JBoss LogManager + Vert.x safety handler
  bootui-quarkus-deployment   NEW (deployment): @BuildStep wiring, dev-mode-only
                              activation, route + reflection registration
  bootui-quarkus-sample-app   NEW: demo/integration app + parallel Playwright e2e (§8)
```

Dependency direction (both adapters depend on the shared modules; the shared modules never depend on a framework):

```
        bootui-core  ◄── bootui-spi  ◄── bootui-engine
             ▲                ▲               ▲
             │                │               │
   ┌─────────┴───────┐  ┌─────┴───────────────┴─────┐
   │ bootui-          │  │ bootui-quarkus            │
   │ autoconfigure    │  │ (+ -deployment)           │
   │ (Spring adapter) │  │ (Quarkus adapter)         │
   └──────────────────┘  └───────────────────────────┘

   bootui-ui  ── built once, packaged into whichever adapter serves /bootui/
```

### How the web layer stays mostly shared

Spring MVC and JAX-RS annotations are incompatible, so the _controllers themselves_ cannot be one class. The fix is to
keep controllers **thin** and push all logic into shared `bootui-engine` services:

```
Spring:   @RestController BeansController ─┐
                                           ├─► (shared) service in bootui-engine ─► DTO from bootui-core
Quarkus:  @Path JAX-RS resource ───────────┘         (calls a bootui-spi provider for raw data)
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
| `CacheProvider`                | Cache managers & stats                                               | Spring `CacheManager`                                       | quarkus-cache (Caffeine)                     |
| `RequestCaptureSource`         | Live request feed (Live Activity)                                    | `ServletRequestHandledEvent`                                | Vert.x filter                                |
| `HttpExchangeProvider`         | Recent HTTP exchanges                                                | Actuator `HttpExchangeRepository`                           | Vert.x filter buffer                         |
| `AuditEventProvider`           | Security audit events (Security Logs)                                | Actuator `AuditEventRepository`                             | CDI security events                          |
| `SqlTraceSource`               | Captured SQL statements                                              | datasource-proxy                                            | Agroal / JDBC interceptor                    |
| `LogCaptureSource`             | Tailed log lines (Log Tail)                                          | logback appender                                            | JBoss LogManager handler                     |
| `LocalhostGuardBinding`        | Feeds request metadata to the shared guard                           | servlet `Filter`                                            | Vert.x handler                               |

The framework-neutral safety **decision** (loopback check, `Host`/allowed-hosts validation, `Origin`/`Sec-Fetch-Site`
CSRF defense) is extracted into a shared `LocalhostGuard` in `bootui-engine`, reusing the existing `CidrRange` /
`ContainerGatewayDetector` logic. Each framework only supplies request metadata and writes the deny response.

Per-panel **access** rules (`bootui.panels.<id>.enabled` / `.read-only`, plus the global `bootui.read-only`) are a
separate, sibling mechanism — implemented on Spring as `PanelAccessFilter` and, at full behavioral parity (same config
keys, same canonical JSON 403 body), on Quarkus as `QuarkusPanelAccessFilter`. Both bind to the same shared
`BootUiPanels` registry to resolve a request path to a panel id and its `actionCapable()` flag, and both run as a
second filter *after* the loopback/Host/CSRF guard (`QuarkusPanelAccessFilter` is registered at a lower Vert.x filter
priority than `BootUiQuarkusSafetyFilter`, so a request failing both checks is rejected by the safety guard, not the
panel-access filter).

## 5. The Quarkus panel set

The console keeps a single `routes.js`. The backend's `/bootui/api/panels` manifest declares, per panel, whether it is
**supported on this platform** (hidden when not) versus **available** (shown, possibly read-only). This is a small
extension of the mechanism `App.vue` already uses, so the same UI build renders the correct sidebar on each backend.

> **Implementation status (current).** The Quarkus adapter now lights up the large majority of the panel set — all of
> §5.1 and §5.2 below, plus the advisors (Architecture, the Quarkus application advisor replacing Spring, Hibernate,
> Pentesting, a Quarkus-native Security advisor, REST API, Memory) and the §5.3 capture panels (HTTP Exchanges, Live
> Activity, Log Tail, SQL Trace, Exceptions, Security Logs). **Action-capable panels behave identically to Spring**,
> behind the shared `LocalhostGuard` write floor: Heap Dump (capture/analyze/delete/download), Threads (download), the
> advisor scans, Loggers (set level), HTTP Probe, Cache (clear), Flyway (migrate/clean), Liquibase (update), Traces
> (clear), and the MCP Server toggle. Only **GraalVM**, **CRaC**, **Conditions**, **Startup Timeline**, **HTTP
> Sessions**, **Spring Data**, **Spring Security**, and **DevTools** stay deliberately unavailable, each with a
> panel-specific not-applicable reason. The per-panel `**Implemented**` markers below and `docs/FEATURES.md` carry the
> authoritative, current per-platform detail.

### 5.1 Ported as-is — framework-agnostic or same library (17)

Logic lives entirely in `bootui-core` + `bootui-engine`; the Quarkus adapter adds at most a trivial supplier.

`Memory` · `Live Memory` · `JVM Tuning` · `Heap Dump` · `Threads` (pure JVM MXBeans) · `Metrics` (Micrometer — same API)
· `Hibernate` advisor (Hibernate ORM + `jakarta.persistence`; rules port directly) · `Vulnerabilities` (classpath Maven
metadata + OSV) · `Pentesting` · `HTTP Probe` (local HTTP probing) · `AI Usage` · `Traces` (OTLP — a standard;
Quarkus/LangChain4j export it) · `GitHub` (`HttpClient`) · `Copilot` · `Claude Code` (read `~/.copilot` / `~/.claude`) ·
`MCP Server` (**Implemented** — full JSON-RPC bridge: the shared engine `McpDispatcher` owns method routing/gating/tool
lookup, a thin Jackson-2 `QuarkusMcpEnvelope` codec + `QuarkusMcpTools` catalog + working enable toggle sit behind the
`LocalhostGuard` write floor) · `Dev Services` (**Implemented** — a Quarkus-native concept; build-time
`DevServicesResultBuildItem` snapshot captured via recorder + synthetic bean, masked config, logs/restart unavailable).

### 5.2 Ported by swapping the data source (10)

Same DTO and UX; the Quarkus adapter implements the relevant SPI against a Quarkus API.

`Health` (→ SmallRye Health) · `Configuration` (**Implemented** — → SmallRye Config; read path enumerates/masks/pages the
effective config, read-only on Quarkus because the runtime-override write path is Spring-bootstrap-specific) · `Profile
Diff` (**Implemented** — → SmallRye Config; groups active `%profile.`-prefixed keys) · `Loggers` (→ JBoss LogManager) ·
`Mappings` (**Implemented** — → the application's JAX-RS resources scanned from the build-time Jandex index by a
`registerMappings` build step + `@Recorder` into a synthetic bean; same paged `MappingProvider` DTO as Spring's Actuator
read, BootUI's own routes filtered out at build time) · `Flyway` (→ `quarkus-flyway`) · `Liquibase` (**Implemented** — → `quarkus-liquibase`;
discovered via `LiquibaseFactoryUtil.getActiveLiquibaseFactories()`, the shared `RanChangeSet` history read + `update`
action behind the same DTO contract) · `Scheduled Tasks`
(→ `quarkus-scheduler`) · `Architecture` advisor (ArchUnit engine + rules run unmodified; Spring-stereotype rules
simply match no classes and degrade to a no-op pass, while a few rules are already dual-framework via the shared
`jakarta.*` annotations) · `Overview` (panel available; the scoring dashboard aggregates the advisor endpoints client-side, and
`GET /bootui/api/overview` reports the Quarkus version + shell chrome).

### 5.3 Kept, with a rebuilt capture layer or reduced fidelity (8)

The DTO and UI are reused; the Quarkus adapter rebuilds the capture/source on the reactive stack.

| Panel                 | Quarkus source / limitation                                                                                                                    |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `REST API` advisor    | **Implemented** — conventions (status codes, versioning, pagination) shared; the engine models JAX-RS `@Path` resources alongside Spring MVC, and irreducibly-Spring rules (ProblemDetail, ResponseEntity codes) SKIP honestly on JAX-RS |
| `DB Connection Pools` | **Implemented** — reads **Agroal** metrics instead of HikariCP MXBeans via `QuarkusAgroalConnectionPoolProvider` (the sole importer of `io.agroal.*`, gated on the `AGROAL` capability). Agroal→Hikari mapping: active←activeCount, idle←availableCount, total←active+idle, pending←awaitingCount; acquisition/idle/max-lifetime timeouts map across; `validationTimeoutMs`/`keepaliveTimeMs` report `-1` (rendered "—") and `readOnly` reports `false` (no Agroal accessor). Requires `quarkus.datasource.jdbc.metrics.enabled=true` for live counts — configuration still renders without metrics, but the snapshot is null and the pool is marked unavailable with a specific reason. Read-only: no write gate |
| `SQL Trace`           | **Implemented** — two complementary feeders into the shared `SqlTraceRecorder`: an `@Alternative` Agroal `DataSource` wrap (manual JDBC, gated on Agroal) **and** a `@PersistenceUnitExtension` Hibernate `StatementInspector` (ORM SQL, which bypasses the wrapped DataSource; gated on Hibernate). Statement text/type/category/N+1 full-fidelity; per-statement duration/rows/params are best-effort for ORM SQL (the StatementInspector SPI has no execution-end hook). `clear`/`recording` behind the `LocalhostGuard` write floor |
| `Live Activity`       | **Implemented** — merges all four signals (HTTP requests via the Vert.x ring buffer + SQL trace + exceptions + security events) into the shared activity feed; SQL contributes only when a datasource is configured (a clean warning otherwise), and the security source is gated on the Security Logs panel's own availability. **Signal-to-request correlation is trace-id-based, gated on `quarkus-opentelemetry`:** Spring's thread-per-request anchor is unportable on the Vert.x event loop, so instead the adapter stamps the active server span's trace id at each capture point (HTTP filter, SQL recorder, exception store, and the CDI `SecurityEvent` observer) via a capability-gated `QuarkusOtelTraceIdProvider`, and the engine `LiveActivityAssembler` nests SQL/exception/security entries under the request sharing that trace id (OTel `Context` propagates across the event-loop→worker hop, including into the CDI security-event observer). A security event whose trace id uniquely matches one request also stamps that request's `securedPrincipal` (falling back only when the request's own captured principal — see `HTTP Exchanges` below — is null), so the "authenticated" badge lights up from either signal. An ambiguous trace id (shared by more than one in-flight request) is never nested and never stamps a principal, the same guard already used for SQL/exceptions. With OpenTelemetry absent, trace ids stay null and the feed renders flat (status quo). The per-request **profile** drill-down (`GET /bootui/api/activity/{id}`) is now **implemented in reduced, trace-id-only form** — see the note below the table for exactly what is and isn't ported. |
| `HTTP Exchanges`      | **Implemented** — buffer exchanges via a Vert.x filter instead of Actuator's repository                                                       |
| `Exceptions`          | **Implemented** — captured into the shared `ExceptionStore` by a `java.util.logging` handler (logged throwables) + a Vert.x failure handler (unhandled web failures), deduped across feeders across the whole cause chain; BootUI's own frames self-filtered. Quarkus logs an unhandled request failure synchronously (`QuarkusErrorHandler`) before the Vert.x failure handler's late `addBodyEndHandler` callback runs, so the log handler is normally the feeder the store's dedup keeps; it resolves the owning request's method/path itself from the CDI-current `CurrentVertxRequest`, so both feeders carry full web context. The `handler` field (the JAX-RS resource class + method serving the request, e.g. `MyResource#doSomething`, matching Spring's `HandlerMethod`-derived format) is resolved the same way by `QuarkusResourceHandlers`, reading RESTEasy Reactive's current-request state (`CurrentRequestManager`/`ResteasyReactiveResourceInfo`, the same mechanism `quarkus-rest-jackson` itself uses for per-method `@JsonView` resolution) — populated/cleared in lockstep with the `CurrentVertxRequest` above, so it survives the same event-loop→worker hop. The Open/Acknowledged/Resolved triage workflow and regression auto-reopen (a `Resolved` group that fires again flips back to `Open` and increments a `regressionCount`) live entirely in the shared `ExceptionStore`/`ExceptionsService`, so they are identical on Quarkus; `ExceptionsResource` exposes the same `POST /bootui/api/exceptions/{id}/status` with the same validation/status codes as Spring's `ExceptionsController`, behind the same `LocalhostGuard` write floor |
| `Security Logs`       | **Implemented** — captures Quarkus CDI security events into the shared `SecurityEventBuffer` via `QuarkusSecurityEventCapture` (a `@Observes SecurityEvent` observer), which replaces Spring's `AuditEventRepository`. Gated on `quarkus-security` (the observer is excluded by the deployment processor when no security extension is present, R2) and `quarkus.security.events.enabled=true` (panel reports unavailable with a clear message when events are disabled). Honest partial: Quarkus fires events only for authentication success/failure and authorization failure — no logout or session events (no Quarkus equivalent). SSE `/stream` ticks on each capture. Read-only (no write endpoints) |
| `Log Tail`            | **Implemented** — captured via a `java.util.logging` `Handler` (`QuarkusLogTailHandler`) attached to the root JBoss LogManager logger at `StartupEvent` (detached at `ShutdownEvent` so dev-mode restarts never leak handlers). The handler feeds the shared `LogTailBuffer`; both `/recent` (snapshot) and the SSE `/stream` (live fan-out with atomic snapshot-then-subscribe to avoid gaps) are full-fidelity. BootUI's own loggers are self-filtered. Identical wire to Spring's Logback appender path |

**Why the per-request profiler (`GET /bootui/api/activity/{id}`) is trace-id-only on Quarkus.** Spring's
`/activity/request/{id}` profiler is a Symfony-style join across SQL, exceptions, security audit events, the distributed
trace, and timing for one request (`LiveActivityCorrelator`) — it is not CPU/flame-graph sampling. Its richness comes
from a **tiered** correlation strategy: (1) OpenTelemetry trace id (the strongest, most precise signal), then, for
requests without one, (2) HTTP method+path+time-window+thread heuristics for exceptions, (3) serving-thread correlation
for SQL, and (4) time-window+principal for security events. Tiers 2-4 key on **serving-thread identity**: a servlet
request runs start-to-finish on one worker thread that serves only one request at a time, so SQL, exceptions, and
security events observed on that thread within its time window belong to it exactly, even with no distributed tracing
configured at all (`threadMatched` is a first-class field on `RequestProfileSecurityDto`/carried by
`RequestProfileExceptionDto`'s `thread`). Quarkus's Vert.x event-loop-plus-worker-thread model has no equivalent "the one
thread that served this request" identity — handling can hop across the event loop and one or more worker threads — so
tiers 2-4 have nothing to key on and are **not ported** (this was investigated and correctly ruled out as infeasible; see
the PR that shipped this trace-id-only profiler for the reasoning).

Tier 1, however, **is** portable and **is now implemented**: the same trace-id stamping that powers the `LiveActivityAssembler`
nesting above (§ the `Live Activity` row) is reused by a dedicated engine class, `RequestProfileAssembler`, to answer
`GET /bootui/api/activity/{id}` directly. When the requested exchange carries a trace id, the endpoint gathers every
SQL/exception/security entry sharing that exact trace id and returns `available: true` with `sqlCorrelationApproximate:
false` (trace-id matching is exact, unlike Spring's time-window heuristics for untraced requests) and a `notes` entry
disclosing that this is a reduced, trace-id-only profile — no time-window/thread-based correlation. `threadMatched`
always reports `false` (Quarkus has no thread-identity concept to key on), while `principalMatched` on
`RequestProfileSecurityDto` **is** honestly computed by comparing principals. Ambiguous trace ids (shared by more than
one in-flight request — the same edge case the main feed already guards) render an `available: true` profile with empty
correlated lists plus an explanatory note, never a hard failure or cross-request data leak. When the exchange has no
trace id at all — OpenTelemetry absent, or this particular request predates `quarkus-opentelemetry` being added — the
endpoint honestly returns `available: false` with a clear reason
(`RequestProfileDto.unavailable(...)`) rather than fabricating a partial result. In the main feed, `ActivityEntryDto.profileable`
is computed adapter-side in `LiveActivityResource`: `true` for request entries with a resolvable trace id, `false`
otherwise; the shared engine's own `profileable` default is untouched so Spring is completely unaffected.

### 5.4 Replaced with a Quarkus-native panel (2)

| Spring panel     | Quarkus replacement                                                                                    |
| ---------------- | ------------------------------------------------------------------------------------------------------ |
| `Spring` advisor | **Implemented** — **`Quarkus` advisor**: new Quarkus-native ruleset over the shared scanning engine (CDI/Arc scopes, build-time config, reactive idioms, profiles) under the same panel id `spring` + `/bootui/api/spring` + `SpringReport`. See [QUARKUS-ADVISOR-CHECKS.md](QUARKUS-ADVISOR-CHECKS.md) |
| `Cache`          | **Implemented** — served over `quarkus-cache` (Caffeine) under the shared id `cache`; cache names + Micrometer metrics + clear, with an empty operations list (caching annotations are build-time woven) |

### 5.5 Dropped on Quarkus (8)

No equivalent, low value, or superseded by Quarkus's own tooling:

- **Build-time model differences:** `Conditions` (Quarkus resolves conditions at build time — no runtime report),
  `Startup Timeline` (build-time augmentation eliminates startup steps — there is no runtime per-step buffer like
  Spring's `BufferingApplicationStartup`, only coarse boot totals — so it is reported *not applicable*, not
  *not yet*, alongside GraalVM/CRaC).
- **Different security/data stacks:** `Spring Security`, `Spring Data` (Quarkus uses Elytron/OIDC and Panache).
- **Servlet-only / low value on a reactive stack:** `HTTP Sessions`.
- **Superseded or moot:** `GraalVM` readiness (Quarkus is native-first with its own build), `CRaC` (native focus makes
  it niche), `DevTools` (**Implemented as `NOT_APPLICABLE`** — Quarkus has built-in dev-mode live reload, so there is no
  Spring-style DevTools restart/LiveReload to expose; the panel reports *not applicable* rather than *not yet*).

**Result:** 39 of the ~47 panels ship on Quarkus (17 ported as-is, 10 source-swapped, 8 capture-rebuilt, 2 replaced,
plus the advisors and the runtime panels lit up through the shared engine), and 8 are dropped as *not applicable*
(GraalVM, CRaC, Conditions, Startup Timeline, HTTP Sessions, Spring Data, Spring Security, DevTools). No panels
remain merely *not yet* ported: the Overview dashboard panel is now available (its scoring dashboard renders
client-side from the advisor endpoints, and the shell-chrome `GET /bootui/api/overview` endpoint is served on both
adapters).

## 6. Activation & safety on Quarkus

- **Dev-mode-only extension.** The `bootui-quarkus-deployment` module registers BootUI's routes and beans **only in dev
  mode** via `@BuildStep`. This is the natural Quarkus analogue of BootUI's "active only in `dev`/`local`, fail closed"
  rule, and it means BootUI is simply absent from production/native builds.
- **The static shell is dark in production too, not just the API.** The launch-mode-gated `@BuildStep`s above stop
  BootUI's own CDI beans/JAX-RS resources from being wired in `LaunchMode.NORMAL`, but the compiled Vue bundle at
  `META-INF/resources/bootui/` used to remain reachable regardless: Quarkus' built-in static-resource handler serves any
  classpath resource under `META-INF/resources/**` unconditionally, wired by `quarkus-vertx-http` independently of this
  extension's build steps, and Quarkus offers no build-time mechanism to exclude a single path from that scan. Left
  alone, a production deployment would still answer `GET /bootui/` with the empty SPA shell's `index.html`/JS/CSS — no
  working API behind it, but reachable. `BootUiProdShellGuardFilter` closes this: a CDI Vert.x filter registered by its
  own **always-on** `@BuildStep` (deliberately *not* launch-mode-gated, the opposite polarity from every other build
  step in the extension), whose `handle()` method reads a CDI-injected `LaunchMode` and answers a plain `404` for
  `/bootui`/`/bootui/**` only when it is `LaunchMode.NORMAL` — an immediate no-op pass-through otherwise, so
  dev/`@QuarkusTest` behavior is unaffected. Net effect: `/bootui`/`/bootui/**` is a plain 404 in production, at parity
  with the Spring adapter (which never registers any BootUI route when inactive, so nothing is reachable there either).
  Proven by a genuine `LaunchMode.NORMAL` build+run via `QuarkusProdModeTest`
  (`BootUiQuarkusProdShellGuardBootTest`, in the dedicated `bootui-quarkus-prod-shell-guard-integration-tests`
  module — kept separate from every `@QuarkusTest`-based module because Quarkus's own test framework refuses to mix
  `QuarkusProdModeTest` and `@QuarkusTest` in the same Surefire fork), alongside a white-box unit suite
  (`BootUiProdShellGuardFilterTest`, in `bootui-quarkus-integration-tests`) — see `BootUiQuarkusProcessor`'s class
  Javadoc for the full investigation.
- **Native is therefore a non-issue.** Quarkus dev mode always runs on the JVM, so the bytecode-scanning advisors,
  classpath Maven metadata (`Vulnerabilities`), and JVM MXBeans all work exactly as on Spring. The native-image
  limitations (no runtime classpath scan, stripped metadata) only apply to production native images, which BootUI never
  ships in.
- **Loopback safety preserved.** The shared `LocalhostGuard` enforces the same loopback / allowed-hosts / CSRF rules; the
  Quarkus adapter binds it as a high-priority Vert.x handler on `/bootui/*` and `/bootui/api/*`, failing closed for
  non-loopback callers — matching `LocalhostOnlyFilter`'s `Integer.MIN_VALUE` servlet ordering.
- **Per-panel access gating at parity.** `QuarkusPanelAccessFilter` enforces `bootui.panels.<id>.enabled` /
  `.read-only` and the global `bootui.read-only`, mirroring Spring's `PanelAccessFilter` exactly (same config keys,
  same `BootUiPanels` path resolution, same canonical `{"error":"BootUI panel access denied","panel":"<id>",
  "reason":"<reason>"}` JSON 403 body). It runs as a lower-priority Vert.x filter than `BootUiQuarkusSafetyFilter`, so
  the loopback/Host/CSRF guard always evaluates first. `QuarkusPanelAvailability` and `QuarkusMcpPanelPolicy` (the MCP
  tool gate) both read the same config, so a disabled or read-only panel is refused consistently across the REST API,
  the `/bootui/api/panels` manifest, and the MCP bridge. Backend/integration coverage lives alongside the runtime
  classes; **browser-level** coverage is `bootui-quarkus-sample-app/e2e/tests/read-only.spec.js`, the Quarkus twin of
  the Spring sample's `read-only.spec.js` — it spawns fresh, throwaway `quarkus:dev` instances with
  `bootui.read-only` / `bootui.panels.<id>.read-only` passed as plain JVM system properties (MicroProfile Config
  picks these up live, the same mechanism `playwright.config.js` already relies on for `-Dquarkus.http.port`) and
  asserts both the API (403 + canonical reason) and the shared Vue UI (disabled controls, the `panel-read-only-alert`
  banner) reflect the setting. One divergence from the Spring spec: it cannot use the `config` panel as the
  "unaffected control panel" the way Spring does, because Configuration is *always* read-only on Quarkus for an
  unrelated reason (no runtime-override write path yet — see above); it uses `memory` instead.

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
| `spring-ai-starter-model-ollama` + `ChatController`                                | AI Usage, Traces                       | `quarkus-langchain4j-ollama` + `quarkus-opentelemetry`                   |
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
  `http://localhost:8080/bootui` — the analogue of the Spring sample app's `spring-boot:run` smoke-test path. Quarkus
  live reload replaces DevTools for the inner loop. (`-am` builds the upstream `bootui-quarkus` extension first.)
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

1. **Phase 0 — Refactor in place (no behavior change).** Introduce `bootui-spi` and `bootui-engine`; move
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
  detail (timing, headers, correlation). Correlation is now resolved via the OpenTelemetry trace id (Live Activity nests
  SQL/exceptions/security events under their request, and stamps `securedPrincipal`, when `quarkus-opentelemetry` is
  present); the per-request profile drill-down is now implemented too, but in reduced, trace-id-only form — Spring's
  time-window/thread-based tiers lean on servlet thread-per-request serving-thread identity that the Vert.x model has no
  equivalent for, so they remain deliberately unported (§5.3 has the detailed reasoning).
- **Module naming & coordinates.** New shared/adapter modules keep `com.julien-dubois.bootui:*` coordinates and
  `io.github.jdubois.bootui.*` packages; the Quarkus extension follows Quarkus's `runtime` / `deployment` convention.
- **Docs & checks.** The Quarkus application advisor is backed by `docs/QUARKUS-ADVISOR-CHECKS.md` and the Quarkus
  Security advisor by `docs/QUARKUS-CHECKS.md`, mirroring the existing
  `*-CHECKS.md` files; `docs/FEATURES.md` would gain a per-platform availability note; and the contributor docs
  (`CONTRIBUTING.md`, AI instructions) would document the second sample app and its e2e suite.

## 11. Appendix — full panel disposition

`Port` = ships from shared code · `Adapt` = swap data source via SPI · `Rebuild` = reimplement capture ·
`Replace` = Quarkus-native panel · `Drop` = not shipped.

| Panel               | Tier        | Quarkus | Shared component                 | Quarkus adapter / reason                    |
| ------------------- | ----------- | ------- | -------------------------------- | ------------------------------------------- |
| Memory              | as-is       | Port    | JVM memory reader                | —                                           |
| Live Memory         | as-is       | Port    | JVM memory reader                | —                                           |
| JVM Tuning          | as-is       | Port    | JVM flags reader                 | —                                           |
| Heap Dump           | as-is       | Port    | HotSpotDiagnostic reader         | —                                           |
| Threads             | as-is       | Port    | ThreadMXBean reader              | —                                           |
| Metrics             | as-is       | Port    | Micrometer reader                | `MeterRegistrySupplier`                     |
| Hibernate           | as-is       | Port    | Hibernate advisor engine         | `EntityManagerFactoryProvider`              |
| Vulnerabilities     | as-is       | Port    | OSV scanner + dependency catalog | —                                           |
| Pentesting          | as-is       | Port    | Pentesting engine                | deliberately empty endpoint inventory (avoids a false-positive `spring-security-web` finding) |
| HTTP Probe          | as-is       | Port    | HTTP probe service               | —                                           |
| AI Usage            | as-is       | Port    | TelemetryStore (OTLP)            | —                                           |
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
| Architecture        | equiv       | Adapt   | ArchUnit engine                  | `BasePackageProvider` (rules run unmodified) |
| REST API            | **done**    | Rebuild | REST conventions engine          | JAX-RS handler-model builder                |
| DB Connection Pools | **done**    | Rebuild | Pool model                       | `DataSourcePoolProvider` → Agroal           |
| SQL Trace           | **done**    | Rebuild | SQL trace model                  | `SqlTraceSource` → Agroal/JDBC              |
| Live Activity       | **done**    | Rebuild | Activity model                   | `RequestCaptureSource` → Vert.x; OTel trace-id correlation + trace-id-only profile drill-down |
| HTTP Exchanges      | **done**    | Rebuild | Exchange model                   | `HttpExchangeProvider` → Vert.x             |
| Exceptions          | **done**    | Rebuild | Exception model                  | Vert.x failure handler                      |
| Security Logs       | **done**    | Rebuild | Audit model                      | `AuditEventProvider` → CDI events           |
| Log Tail            | **done**    | Rebuild | Log tail model                   | `LogCaptureSource` → JBoss LogManager       |
| Spring              | **done**    | Replace | Scanning engine                  | new `Quarkus` advisor ruleset               |
| Cache               | **done**    | Replace | Cache model                      | `CacheProvider` → quarkus-cache             |
| Beans               | **done**    | Adapt   | Beans service                    | `BeanProvider` → Arc (build-time; low fidelity) |
| Profile Diff        | **done**    | Adapt   | Config service                   | `ConfigProvider` → SmallRye profiles        |
| Security (advisor)  | **done**    | Replace | Quarkus security ruleset         | Quarkus-native checks (OIDC/auth/TLS/CORS/annotations); see QUARKUS-CHECKS.md |
| GraalVM             | **done**    | Drop    | —                                | Quarkus native-first; `NOT_APPLICABLE`      |
| CRaC                | **done**    | Drop    | —                                | native focus; `NOT_APPLICABLE`              |
| DevTools            | **done**    | Drop    | —                                | Quarkus live reload built in; `NOT_APPLICABLE` |
| Conditions          | spring-only | Drop    | —                                | no runtime conditions report                |
| Startup Timeline    | spring-only | Drop    | —                                | not applicable: build-time augmentation, no runtime per-step buffer |
| Spring Security     | spring-only | Drop    | —                                | Elytron/OIDC, different model               |
| Spring Data         | spring-only | Drop    | —                                | Panache, different model                    |
| HTTP Sessions       | spring-only | Drop    | —                                | reactive/stateless stack                    |
