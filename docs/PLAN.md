# BootUI Implementation Plan

## 1. Strategy

BootUI adds a safe, local-only developer console to a running application, shipping on **both Spring Boot 4 (a starter)
and Quarkus (an extension)** from one shared, framework-neutral engine that serves the same Vue UI and the same
`/bootui/api/**` contract on either runtime. The released surface already covers runtime introspection, configuration,
database migrations, services, diagnostics, project health, and developer tooling, including the recently shipped
Threads, HTTP Exchanges, Flyway, Liquibase, Hibernate Advisor, HTTP Sessions, GitHub, Security Advisor, and Overview
scanner dashboard panels. This plan describes the **next merged feature workstream** after the `1.0.0` release: it keeps
the remaining roadmap items and the one capture-oriented addition chosen to close the clearest gaps against comparable
developer dashboards (Spring Boot Admin, Quarkus Dev UI, Laravel Telescope/Pulse, Phoenix LiveDashboard, .NET Aspire,
Symfony Web Profiler) while staying inside BootUI's read-mostly, fail-closed safety model.

The priorities for every item below remain unchanged:

1. Safety and local-only operation.
2. Easy installation with no extra setup.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

### Completed for 1.0.0

- Promoted the current grouped sidebar surface to the stable `1.0.0` release line.
- Added the Security Advisor panel, `/bootui/api/security` API, panel availability/read-only wiring, tests, feature
  documentation, rule catalogue, and screenshot.
- Redesigned Overview into an on-demand security & health scoring dashboard that aggregates the available scanner panels.
- Added token-first activity charts to the Copilot and Claude Code dashboards and refreshed their screenshots.
- Published the VuePress documentation site with GitHub Pages deployment, setup/sample-app pages, and fixed markdown links.
- Fixed Spring Modulith Flyway reporting and proxied Hikari datasource discovery so the existing Database panels better
  match real applications.

### Completed in this workstream

- Shipped §3.1 (Trace ↔ Log ↔ Request correlation) as the **Live Activity** panel: a single reverse-chronological stream
  that merges requests, SQL, exceptions, and security events from BootUI's existing in-memory buffers, nests correlated
  signals under the request that produced them, and adds a per-request profiler that joins each request's signals by
  trace id, serving thread, and time window. It refreshes over a `/bootui/api/activity/stream` Server-Sent Events feed,
  carries a KPI strip, and deep-links into the HTTP Exchanges, SQL Trace, Exceptions, Health, and Heap Dump panels. The
  panel is read-only and reuses the existing masking, value-exposure, and panel-toggle model.

Each new panel must:

- be **read-only or read-mostly**, with any mutating control explicitly confirmation-gated like the existing Cache
  clear action;
- **fail closed** when its required classes, beans, Actuator endpoints, or data are unavailable, returning stable empty
  DTOs and a clear unavailable reason;
- route any sensitive property names, headers, addresses, or values through the existing masking and value-exposure model;
- ship with backend slice/edge-case tests, `/bootui/api/panels` availability wiring, docs, router ordering, and sample-app
  Playwright coverage in sync.

## 2. Scope of this workstream

One open feature remains. The §3.1 correlation item has shipped as the **Live Activity** panel; the §3.3 e-mail viewer
has shipped too, both as a standalone panel and as a `MAIL` entry type feeding the Live Activity stream; and all five
§3.4 event-type extensions (Scheduled Task runs, Cache operations, Kafka messaging, Mail, and REST call capture) have
shipped. The bean/dependency graph visualization (§3.2) is the one remaining item in this workstream.

| Priority | Feature                               | Group         | Primary data source                                | Mutation?         | Origin           |
| -------- | ------------------------------------- | ------------- | -------------------------------------------------- | ----------------- | ---------------- |
| Done     | Trace ↔ Log ↔ Request correlation     | Diagnostics   | Existing Traces, Log Tail, and HTTP Exchanges data | No                | Existing roadmap |
| 1        | Bean / dependency graph visualization | Configuration | Existing Beans and Conditions data                 | No                | Existing roadmap |
| Done     | E-mail Viewer                         | Diagnostics   | Intercepted `JavaMailSender`                       | No (capture only) | New addition     |
| Done     | Live Activity — REST call capture     | Diagnostics   | Intercepted `RestClient`/`RestTemplate`/`WebClient` | No (capture only) | Shipped |
| Done     | Live Activity — new event types       | Diagnostics   | Cache, scheduled-task, Kafka, and mail capture sources (see §3.4) | No (capture only) | Cache, Scheduled Tasks, Kafka, and Mail all shipped |

The Trace ↔ Log ↔ Request correlation work in §3.1 has shipped as the **Live Activity** panel, building on the
already-shipped HTTP Exchanges panel and the existing Traces and Log Tail panels. The E-mail Viewer (§3.3) has shipped as
the **Email** panel (Diagnostics group): a `JavaMailSender` `BeanPostProcessor` captures every outgoing message
pass-through by default, with an explicitly opt-in `bootui.email.dev-trap` mode, masked recipients/subject/body, a
sandboxed HTML preview, and a per-message `.eml` download. All five of the §3.4 Live Activity event-type extensions have
now shipped — Scheduled Task runs, Cache operations, Kafka messaging, an E-mail Viewer-backed `MAIL` event type, and
outbound `RestClient`/`RestTemplate`/`WebClient` capture — leaving only the bean/dependency graph visualization (§3.2) as
the remaining item in this workstream. Each capture-oriented feature keeps pass-through application behaviour by default
and makes any dev-trap mode explicitly opt-in.

## 3. Feature specifications

### 3.1 Trace ↔ Log ↔ Request correlation — Diagnostics ✅ Completed

**Status: completed.** Shipped as the **Live Activity** panel (see `docs/FEATURES.md` → *Live Activity*), which merges
requests, SQL, exceptions, and security events into one reverse-chronological stream with request-scoped nesting, a
per-request profiler that correlates each request's signals by trace id / serving thread / time window, an SSE feed at
`/bootui/api/activity/stream`, and deep links back into the HTTP Exchanges, SQL Trace, and Exceptions panels. The original
scope and design constraints below are retained for reference.

This is where Aspire and Symfony differentiate. BootUI already owns a trace pipeline (the in-app OTLP sink and Traces
panel) plus Log Tail, and the HTTP Exchanges panel; the three can be cross-linked by trace and span id.

Scope:

- Where a trace/span id is present, cross-link related items between the Traces, Log Tail, and HTTP Exchanges panels so a
  user can pivot from a span to its log lines and originating request, and back.
- Add a "view related" affordance on each side that filters the other panels to the shared trace id.
- Degrade gracefully: when no trace context is present on a log line, exchange, or span, simply omit the correlation
  affordance rather than guessing.

Design constraints:

- Read-only and purely client-side/data-join: this feature adds correlation over data the panels already expose; it does
  not introduce a new capture source.
- Correlation must not weaken masking; linked views reuse each panel's existing value-exposure rules.
- Trace propagation is best-effort. Correlation is presented as a convenience, not a guarantee, and must work for the
  common case where Micrometer Tracing/OTLP is active without breaking when it is not.

### 3.2 Bean / dependency graph visualization — Configuration

Layers an Aspire-style relationship view on top of data BootUI already has from the Beans and Conditions panels, without a
new data source.

Scope:

- Visualize beans and their dependencies as a navigable graph, with the ability to focus on a selected bean and see its
  direct dependencies and dependents.
- Reuse the existing BootUI bean classification and Conditions data so users can see why a bean exists and how it is
  wired.
- Provide search/focus and bounded rendering so large application contexts stay responsive, consistent with the existing
  large-app rendering hardening.

Design constraints:

- Read-only.
- Built entirely from existing Beans/Conditions DTOs; no new endpoint capture beyond what those panels already provide.
- Bound the rendered graph (focus + neighborhood, not the full context at once) to keep the frontend bundle and runtime
  performance within the project's large-app budget.
- Avoid heavy graph libraries where a lightweight approach is sufficient, in line with the bundle-size risk in §5.

### 3.3 E-mail Viewer — Diagnostics ✅ Completed

**Status: completed.** Shipped as the **Email** panel (see `docs/FEATURES.md` → *Email*). The original scope and
design constraints below are retained for reference.

Laravel Telescope's mail watcher is a beloved feature with no built-in Spring equivalent. Captured outgoing mail (HTML
preview plus raw source) is a high-value dev-loop aid.

Scope:

- Intercept the application's `JavaMailSender` so every `send(...)` is recorded into a bounded ring buffer **before
  delegating to the real sender** — pass-through by default, so application behaviour is unchanged.
- Capture parsed `from`/`to`/`cc`/`subject`, HTML and text parts, and attachment metadata (name/size/type, not contents).
- List captured messages newest-first (bounded), with a detail view rendering the HTML part in a sandboxed frame, the
  text alternative, headers, and attachment metadata; plus per-message `.eml` download.
- An optional, explicitly opt-in **"dev trap" mode** records without actually sending (like MailDev/GreenMail), off by
  default so BootUI never silently swallows mail.

Design constraints:

- Available only when a `JavaMailSender` bean is present (e.g. `spring-boot-starter-mail`); otherwise fail closed.
- Recipients, subjects, and bodies are sensitive → masked by default and revealed only under value-exposure; HTML is
  rendered sandboxed to prevent script execution.
- Fixed-size buffer; no persistence to disk beyond on-demand `.eml` download.

### 3.4 Live Activity — event types and correlation — Diagnostics ✅ Completed

**Status: completed.** All five event-type extensions below have shipped. Live Activity now merges nine entry types —
`REQUEST`, `SQL`, `EXCEPTION`, `SECURITY`, `CACHE`, `SCHEDULED_TASK`, `MESSAGING`, `MAIL`, and `REST_CLIENT` — from
BootUI's existing in-memory buffers (see `docs/SPECIFICATION.md` §5.14.2). The original scope, prioritized by value
versus new-instrumentation cost and drawn from the same comparable-dashboard benchmarks (Laravel Telescope, Symfony Web
Profiler, .NET Aspire) already guiding this workstream, is retained below for reference.

Scope — new event types, roughly in priority order:

- **Scheduled Task runs — implemented on both adapters.** Each `@Scheduled` method *execution* (start/success/failure,
  duration, exception if any) is captured as a `SCHEDULED_TASK` entry, reusing the existing Scheduled Tasks panel's
  discovery/naming so a captured run and its static definition share the same identifier. On Spring, the framework's own
  Micrometer instrumentation (`ScheduledTaskObservationContext`, present since Spring Framework 6.1) is tapped via a
  `SchedulingConfigurer` bean that installs an `ObservationHandler` — no AOP proxying or bean wrapping needed — feeding a
  bounded, framework-neutral `ScheduledTaskRunStore` in `bootui-engine`. **On Quarkus**, the scheduler
  (`io.quarkus.scheduler.Scheduler`) exposes only one CDI-bean-limited `JobInstrumenter` SPI, already claimed by
  `quarkus-opentelemetry` when scheduler tracing is enabled, so registering a second one would create ambiguous CDI
  resolution and break the app's own tracing. Instead, `QuarkusScheduledTaskRunRecorder` observes the ordinary CDI
  `io.quarkus.scheduler.SuccessfulExecution`/`FailedExecution` events that `BaseScheduler` always fires after every
  execution regardless of how many other observers exist — the same, documented mechanism Quarkus's own Dev UI scheduler
  page uses — and feeds the same `ScheduledTaskRunStore`. Since these events fire only on completion, the trigger's
  `getFireTime()` is used as a proxy for the run's start timestamp (a small margin of error from invoker-chain overhead,
  acceptable for a duration display, not precise profiling); the method identifier comes from
  `Trigger.getMethodDescription()` (`declaringClassName#methodName`, matching the static panel's own identifier), so a
  programmatically registered job (no method description) is not captured, matching the Spring adapter's method-only
  scope. The observer is gated on the `SCHEDULER` capability (R2: `quarkus-scheduler` is `provided`-scope, excluded from
  bean discovery when the capability or a non-production launch mode is absent), matching the existing
  `QuarkusSecurityEventCapture` pattern. No request parent (background thread); a correlated exception is both
  summarized inline via `detail` (the run recorder observes the failure directly) and — when that same failure is
  independently captured into the shared exception log buffer — nested as a full `EXCEPTION` child entry the same way
  `REQUEST` does today, via a serving-thread + time-window join against the run's execution window (the same tiered
  strategy the SQL/exception profiler already uses, minus the trace-id tier: a background job is not a distributed-trace
  participant). The KPI strip's "Scheduled failures" tile and the
  `REQUEST`/`SQL`/`EXCEPTION` deep-link pattern (into `/scheduled`, prefilling its filter with the runnable name) both
  ship on both adapters.
- **Cache operations. ✅ Shipped (Spring servlet and WebFlux adapters).** The Cache panel showed topology and aggregate
  hit/miss counters only; a lightweight, bounded `CACHE` event (hit/miss/put/evict/clear, cache name, key hash — never
  the raw key/value) now explains *why* those counters moved and nests as a `REQUEST` child, mirroring how `SQL` nests
  today. Captured by decorating `CacheManager`/`Cache` beans (`CacheActivityCacheManagerBeanPostProcessor`), so both
  annotation-driven (`@Cacheable`/`@CachePut`/`@CacheEvict`) and programmatic `CacheManager` access are covered; the
  capture beans now live in the shared `BootUiEngineConfiguration` so both the servlet and WebFlux adapters wire them
  identically. Correlation is trace-id-based: the servlet adapter also falls back to serving-thread tiering like `SQL`,
  while WebFlux (which has no thread-per-request invariant) correlates purely via the OpenTelemetry-backed trace id
  provider already used for its SQL/exception/security capture. Feeds a new `cacheHitRatioPercent` KPI tile deep-linked
  to `/cache` on both adapters. Quarkus is out of scope for now — `quarkus-cache`'s built-in interceptors cast the
  resolved cache to an internal, non-public `AbstractCache` type, so a Spring-style decorator implementing only the
  public `Cache` interface would fail with a `ClassCastException`; there is no comparable runtime interception seam, so
  the Quarkus adapter continues to report `cacheHitRatioPercent: null` (see `docs/QUARKUS-SUPPORT.md`).
- **Messaging (Kafka/RabbitMQ/JMS) publish and consume — Kafka shipped (both adapters).** The highest-value new-instrumentation
  candidate after mail and
  REST calls: async messaging is exactly where a Telescope/Aspire-style console helps most, since message flow is
  otherwise invisible outside the debugger. As scoped below, this landed **Kafka-first**: a `KafkaActivityRecorder`
  (framework-neutral, `bootui-engine`) is fed by `KafkaProducerCaptureBeanPostProcessor` /
  `KafkaConsumerCaptureBeanPostProcessor` (`bootui-spring-autoconfigure`, `@ConditionalOnClass(KafkaTemplate)`), which
  wrap application-owned `KafkaTemplate`/`@KafkaListener` container factory beans — composing with, not replacing, any
  existing `ProducerListener`/`RecordInterceptor` — and surface every send/delivery outcome as a `MESSAGING` entry
  (topic, partition, offset, a hash of the key, direction, success/failure, consumer group id, listener id, duration).
  Message values/payloads are never captured (out of scope by design, sidestepping the payload-masking problem
  entirely). Controlled by `bootui.kafka.*` (see `docs/PROPERTIES.md`). RabbitMQ/JMS remain later, separately-scoped
  follow-ups. The **Quarkus port (SmallRye Reactive Messaging) has now shipped**, reusing the same
  `KafkaActivityRecorder` and the same `bootui.kafka.*` keys/defaults: because Quarkus applications use SmallRye's
  `@Incoming`/`@Outgoing` channel model rather than `spring-kafka`'s imperative templates, the capture point is
  SmallRye's `OutgoingInterceptor`/`IncomingInterceptor` SPI, implemented by two `@ApplicationScoped` interceptors
  (`QuarkusKafkaProducerCapture`/`QuarkusKafkaConsumerCapture`, `bootui-quarkus`) that read Kafka record metadata into
  the shared recorder. They are the sole importers of the SmallRye messaging types, capability-gated on `Capability.KAFKA`
  via an `ExcludedTypeBuildItem` exactly like Hibernate/Cache/Flyway/Liquibase (production-dark), a no-op for non-Kafka
  (in-memory/RabbitMQ/JMS) channels, pass-through/fail-open, and set the lowest interceptor precedence so an
  application's own channel interceptor always wins. `LiveActivityResource` merges the captured `MESSAGING` entries into
  the feed adapter-side (top-level, no request correlation) via the shared `KafkaActivityEntries` mapping, so both
  adapters render byte-identical entries. Unlike the other items above,
  this was a materially bigger investment: Kafka,
  RabbitMQ, and JMS are three unrelated client APIs (no single "messaging" abstraction to intercept once), so scope this
  as **Kafka-first**, with RabbitMQ/JMS as later, separately-scoped follow-ups rather than one bundled feature. Each
  needs its own bounded capture buffer, wired only when the relevant client bean/class (`KafkaTemplate`,
  `RabbitTemplate`, JMS `ConnectionFactory`) is present, following the same optional-dependency/fail-closed pattern as
  Hibernate/Cache/Flyway/Liquibase — the interceptor must live in the adapter behind a `@ConditionalOnClass` gate (Spring)
  or a capability-gated `ExcludedTypeBuildItem` (Quarkus), never statically imported by the framework-neutral engine.
  Interception itself is more invasive than any existing capture source: it means wrapping the app's own messaging beans
  (a `BeanPostProcessor`, mirroring the existing HTTP Exchanges repository wrapper) or registering interceptor/advice
  hooks, so pass-through-by-default and fail-open wrapping are non-negotiable design constraints, and message bodies
  need their own bounding/masking design (arbitrary, potentially large application payloads, unlike a SQL statement or
  HTTP header). On Quarkus, the interception point was different in kind, not just in wiring: Quarkus applications
  typically use SmallRye Reactive Messaging (`@Incoming`/`@Outgoing` channels) rather than Spring's imperative
  `spring-kafka`/`spring-rabbit` templates, so the Quarkus capture is a per-adapter interceptor pair rather than the
  thinner Cache/Flyway provider seams — closer to the Beans panel's `BeanProvider` split (CDI vs. Spring bean
  introspection) in spirit, though the shared, framework-neutral `KafkaActivityRecorder` and `KafkaActivityEntries`
  mapping keep the engine seam intact. The panel-registration plumbing itself (an unconditional recorder `@Produces` bean
  plus the capability-gated interceptor beans) follows the existing optional-dependency template with a single
  `registerKafkaCapture` deployment build-step, and needs no new panel/route (Live Activity gains a source, not a panel).
- **Captured email — ✅ Shipped (both adapters).** The standalone Email panel (§3.3) already captured every outgoing
  message via the shared, framework-neutral `EmailCaptureService`; this item only adds a `MAIL` entry to the merged Live
  Activity feed, so — like Cache and Scheduled Task runs — it needed no new capture instrumentation, just a read of an
  existing buffer. Unlike Kafka `MESSAGING` entries (always top-level, no correlation attempted, since a message has no
  single owning request), `MAIL` nests as a `REQUEST` child whenever the captured message's trace id matches an
  in-flight request — the same trace-id-then-thread `parentRequestId` join `SQL`/`CACHE` already use (`EXCEPTION`/
  `SECURITY` predate this join and are correlated differently depending on adapter: on Spring **servlet** (MVC),
  neither carries a trace id — the exception-resolver context has none, nor does Spring Boot's audit repository — so
  they correlate by method/path + serving thread and by a thread-classifier registry respectively; on Spring
  **WebFlux** and Quarkus, both *do* join by trace id like `SQL`/`CACHE`/`MAIL`, since each has its own
  `TraceIdProvider`-backed capture point — see `ActivityEntryDto.parentId`) — so an email sent from inside a request
  handler shows up in that request's profiler drawer. `EmailCaptureService.subscribe(...)`
  feeds the same `BootUiChangeStream`/`ReactiveBootUiChangeStream` coalesced SSE tick the other five in-process sources
  already use, so a newly captured message refreshes the live feed the same way a new cache access or scheduled-task
  run does, on both the servlet and WebFlux adapters. On Quarkus, `LiveActivityResource` reads the same
  `EmailCaptureService` directly (not through the Email panel's own resource) and feeds its merged SSE stream
  identically, mirroring the Spring wiring exactly.
- **Outbound REST call capture — ✅ Shipped (Spring servlet and WebFlux adapters).** Every `RestClient`/`RestTemplate`/
  `WebClient` built through Spring Boot's auto-configured builders is instrumented via Spring Boot's own
  `RestClientCustomizer`/`RestTemplateCustomizer`/`WebClientCustomizer` hooks, attaching a `RestClientTraceInterceptor`
  (`RestClient`/`RestTemplate`) or `RestClientTraceExchangeFilter` (`WebClient`) from inside the `customize(...)`
  callback — so a disabled panel or `bootui.rest-client-trace.enabled=false` adds no interceptor at all, and
  pass-through application behaviour is unaffected either way. Both customizer configurations live in the shared
  `BootUiEngineConfiguration`, gated by `@ConditionalOnClass` on the optional `spring-boot-restclient`/
  `spring-boot-webclient` modules, so servlet and WebFlux wire identically and the configuration is skipped entirely
  when a module is absent. The framework-neutral `RestClientTraceRecorder` (`bootui-engine`) captures each call (client
  type, URI, method, status, duration, headers/call-site when enabled) into a bounded buffer, feeding both the
  standalone REST Client Trace panel and, like Cache/Mail, a `REST_CLIENT` entry into the merged Live Activity feed —
  nesting as a `REQUEST` child via the same trace-id-then-thread join `SQL`/`CACHE`/`MAIL` use (see the `MAIL` bullet
  above for why `EXCEPTION`/`SECURITY` are not part of that list), and adding
  `restCallErrorRatePercent`/`restCallP95LatencyMs` KPI tiles deep-linked to `/rest-client-trace`. Quarkus is out of
  scope for now — like Cache, no comparable runtime interception seam exists yet for the Quarkus-native REST client
  (see `docs/QUARKUS-SUPPORT.md`), so the Quarkus adapter reports the merged-stream slot unavailable.

Scope — enhancements on top of the shipped event types, generally cheaper than a new source and some of higher value:

- **Extend the KPI strip** with metrics for each new source as it lands — this has now shipped for every source that
  has one: outbound-call error rate/p95 (REST call capture), cache hit ratio (Cache), and scheduled-task failure count
  (Scheduled Task runs).
- **Verify persistence and filtering stay generic over `type`** as new event types are added — `JdbcActivityStore`,
  `BufferedActivityStore`, and the client-side type filter chips pick up new types automatically; keep confirming this
  with tests if any further event type (e.g. RabbitMQ/JMS messaging) lands later.
- **Add deep links** for each entry type into its own source panel — Cache and Scheduled Tasks entries now link to
  `/cache` and `/scheduled` respectively, and REST Client entries link to `/rest-client-trace`, joining the existing
  per-entry deep links into HTTP Exchanges (REQUEST), SQL Trace (SQL), and Exceptions (EXCEPTION); the KPI strip's own
  launchpad cards additionally deep-link Health and Heap usage.

Design constraints:

- Every new source stays **read-only** and **fails closed** when its backing bean/class is absent, consistent with
  §3.1/§3.3 and the cross-cutting rules in §4.
- Sensitive payloads (cache keys/values, message bodies) are masked by default and follow the same value-exposure model
  as the rest of the panel; cache keys are hashed rather than shown raw even under full exposure.
- New capture buffers are bounded and self-filtering (BootUI's own traffic must not appear in its own feed), consistent
  with the existing `bootui.monitoring.exclude-self` behaviour.
- Actual shipped sequencing: Scheduled Task runs and Cache operations landed first, then Kafka messaging, then Mail,
  then REST call capture last — all five now shipped, each with `REQUEST`-nesting from day one where a nesting
  relationship applies (Kafka `MESSAGING` is the deliberate exception: always top-level, no correlation attempted).

## 4. Cross-cutting work for every new panel

For each feature above, the following must move together, consistent with the existing panel-registration process:

- Stable BootUI DTOs in `bootui-core` for all browser-facing responses.
- A `/bootui/api/**` controller (lazy-imported, internal-bridge first; annotate the production constructor with
  `@Autowired` when two constructors exist) plus panel registration in `BootUiPanels` and `/bootui/api/panels`
  availability wiring, including the disabled/unavailable sidebar state. Append new action-capable panels last to keep
  index-coupled tests stable.
- A Vue 3 route and panel with empty/unavailable states, server-side filtering/paging where lists can be large, and the
  shared masking-aware rendering.
- Per-panel enable/disable and read-only properties, documented in `docs/PROPERTIES.md`.
- Backend slice and edge-case tests, frontend unit tests, and sample-app Playwright coverage. Update the hard-coded panel
  counts/indices in `PanelsControllerTests`, `BootUiAutoConfigurationTests`, `PanelAccessFilterTests` (action-capable
  panels only), `routes.test.js`, and e2e `app-shell.spec.js`.
- Documentation updates in `README.md`, `docs/FEATURES.md`, `docs/SPECIFICATION.md`, and screenshots at the project's
  standard size.

## 5. Risks

| Risk                                                              | Feature(s) | Impact | Mitigation                                                                                                |
| ----------------------------------------------------------------- | ---------- | ------ | --------------------------------------------------------------------------------------------------------- |
| Exposing sensitive headers, trace context, or mail body           | 3.1, 3.3   | High   | Loopback-only activation, masking/value-exposure on every new surface, sandboxed HTML, and focused tests. |
| Unbounded capture buffers or large rendered graphs/lists          | 3.2, 3.3   | Medium | Fixed-size buffers, server-side paging, bounded snapshots, and focus-and-neighborhood graph rendering.    |
| Optional Actuator endpoints, libraries, beans, or servers missing | all        | Medium | Internal bridges, classpath/bean gating, stable empty DTOs, and clear unavailable reasons per panel.      |
| Bean/dependency graph or correlation bloating the bundle          | 3.1, 3.2   | Medium | Bounded rendering, lightweight visualization, and lazy-loaded panels.                                     |
| Silently swallowing application mail                              | 3.3        | Medium | Pass-through by default; "dev trap" mode strictly opt-in.                                                 |
| Over-broad or noisy new Live Activity event types (e.g. cache operations) | 3.4 | Medium | Explicit opt-in wiring by bean/class presence, bounded buffers, masked payloads/hashed cache keys. |
| Messaging capture's added optional-dependency surface (Kafka/RabbitMQ/JMS clients), invasive interception of app-owned messaging beans, and a per-adapter capture design (SmallRye Reactive Messaging on Quarkus vs. imperative templates on Spring) | 3.4 | High | Kafka shipped on **both adapters** with classpath/capability gating identical to Hibernate/Cache/Flyway/Liquibase, pass-through-by-default fail-open wrapping, and no message-value/payload capture at all (metadata-only, sidestepping body masking); Spring wraps `KafkaTemplate`/listener beans while Quarkus uses SmallRye's `OutgoingInterceptor`/`IncomingInterceptor` SPI feeding the same `KafkaActivityRecorder`. Rabbit/JMS remain separate follow-ups. |
| Scope creep beyond this merged feature set                        | all        | High   | Treat this list as the maximum near-term surface; move further ideas to a later plan.                     |

## 6. Validation checklist

Run after each feature lands and before any release that includes it:

- [ ] `./mvnw -B -ntp clean install` passes.
- [ ] The UI build is executed automatically by Maven.
- [ ] The new panel loads and handles empty/unavailable data with a clear reason.
- [ ] The new panel masks sensitive values and respects the value-exposure mode.
- [ ] `/bootui/api/panels` reports the panel's availability and the sidebar dims it when unavailable.
- [ ] Server-side filtering/paging works for any high-cardinality list.
- [ ] Any mutating action is confirmation-gated and disabled by default.
- [ ] Backend slice/edge-case tests, frontend unit tests, and sample-app Playwright coverage exist for the panel.
- [ ] `README.md`, `docs/FEATURES.md`, `docs/PROPERTIES.md`, and `docs/SPECIFICATION.md` describe the new surface, with
      screenshots at the standard size.
- [ ] BootUI stays disabled in `prod`/`production` unless `bootui.enabled=ON`, and non-local requests are rejected.
