# BootUI Implementation Plan

## 1. Strategy

BootUI adds a safe, local-only developer console to a running application, shipping on **Spring Boot 4 (servlet and
WebFlux starters) and Quarkus (an extension)** from one shared, framework-neutral engine that serves the same Vue UI and
the same `/bootui/api/**` contract on every runtime. The released surface covers 53 panels across runtime introspection,
configuration, database migrations, services, diagnostics, project health, and developer tooling. The previous merged
workstream — Live Activity correlation and event capture, the Beans dependency graph, the Email panel, and the
**Transactions** panel — is complete. The next planned panel is a read-only **MongoDB** operational view, scoped in
§3.5. The **Local Service Map** (§3.6) has shipped — not as a panel of its own, but as the **Live flow** mode of the
existing Live Activity panel, on all three runtimes.

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
- Shipped §3.3 (E-mail Viewer) as the **Email** panel: a `JavaMailSender` `BeanPostProcessor` captures every outgoing
  message pass-through by default (with an explicitly opt-in `bootui.email.dev-trap` mode), reveals recipients/subject/body
  by default — decoupled from the global value-exposure model, with an explicitly opt-in `bootui.email.mask-content`
  flag for teams that want the old masked-by-default behavior — renders HTML previews sandboxed, and offers a
  per-message `.eml` download. Captured mail also feeds the Live Activity stream as a `MAIL` entry type.
- Shipped all five §3.4 Live Activity event-type extensions, bringing the stream to nine merged entry types —
  `REQUEST`, `SQL`, `EXCEPTION`, `SECURITY`, `CACHE`, `SCHEDULED`, `MESSAGING`, `MAIL`, and `REST_CLIENT`: Cache
  operations, Scheduled Task runs, messaging (Kafka and RabbitMQ on both adapters; JMS on Spring), Mail (backed by the
  Email panel above), and outbound REST client capture on Spring servlet, Spring WebFlux, and Quarkus. Each keeps
  pass-through-by-default capture, nests under
  the originating request as a child event when a shared trace id/serving thread/time window is available, and reuses
  the same masking, bounded-buffer, and panel-toggle model as the original four entry types.
- Shipped a new **Transactions** panel (Database group): a bounded in-memory `TransactionRecorder` engine service
  captures every `@Transactional` boundary's method, propagation, isolation, status, duration, and parent/child
  nesting, following the same ring-buffer/aggregate-stats/pause/clear conventions as SQL Trace. Capture is wired via
  Spring Framework's `TransactionExecutionListener` SPI against every `ConfigurableTransactionManager` bean (Spring
  servlet and WebFlux), and correlates each transaction to its SQL statement/connection counts by reusing SQL Trace's
  existing thread/time-window correlation rather than duplicating it. Quarkus reports the panel honestly unavailable —
  see `docs/QUARKUS-SUPPORT.md` §5.5.

- Shipped §3.6 (Local Service Map) as Live Activity's **Live flow** mode rather than a separate panel: a
  `GET /bootui/api/activity/service-map` contract on Spring MVC, Spring WebFlux, and Quarkus that assembles a read-only
  topology from evidence BootUI already retains — inbound HTTP exchanges folded into one generic local-client lane, and
  outbound dependencies grouped by safe identity (HTTP origin, configured JDBC pool, produced Kafka topic, published
  RabbitMQ destination). Assembly, identity normalization, configured-versus-observed state, conservative SQL
  attribution, and hard cardinality bounds live in a framework-neutral `ServiceMapAssembler`; the adapters only gather
  beans they already own. The native SVG/Vue map reuses the Beans graph's layout, keyboard, zoom, and hidden-textual-list
  patterns, and animates a short particle only when a stable edge gains a newly completed interaction after an SSE
  refresh — never on first load, never for a new dependency, never perpetually — with reduced motion replaced by a brief
  static edge highlight plus a polite live-region update. Two long-standing Quarkus SSE source-subscription gaps (SQL
  trace and the exception store) were closed in the same change, since the map refreshes off that same tick.

Each new panel must:

- be **read-only or read-mostly**, with any mutating control explicitly confirmation-gated like the existing Cache
  clear action;
- **fail closed** when its required classes, beans, Actuator endpoints, or data are unavailable, returning stable empty
  DTOs and a clear unavailable reason;
- route any sensitive property names, headers, addresses, or values through the existing masking and value-exposure model;
- ship with backend slice/edge-case tests, `/bootui/api/panels` availability wiring, docs, router ordering, and sample-app
  Playwright coverage in sync.

## 2. Roadmap status and next workstream

The previous workstream is complete. The §3.1 correlation item has shipped as the **Live Activity** panel; the §3.3 e-mail
viewer has shipped too, both as a standalone panel and as a `MAIL` entry type feeding the Live Activity stream; and all
five §3.4 event-type extensions (Scheduled Task runs, Cache operations, messaging, Mail, and REST call capture) have
shipped. The bean/dependency graph visualization (§3.2) has now also shipped as the **graph mode** of the existing Beans
panel.

The current cross-platform baseline is 53 shared routes. Spring Boot serves the full applicable surface. Quarkus serves
43 panels; nine Spring-specific panels (including Transactions, see below) are intentionally not applicable there, and
JMS is the only panel still awaiting a Quarkus-native implementation. `docs/QUARKUS-SUPPORT.md` remains authoritative
for per-panel Quarkus fidelity and availability.

The Local Service Map (§3.6) has since shipped as Live Activity's **Live flow** mode on all three runtimes, reusing the
Live Activity panel id, route, and availability rather than adding a route, so the cross-platform baseline stays at 52
shared routes.

MongoDB is the next bounded feature workstream. BootUI already recognizes Spring Data MongoDB repositories in the
Spring Data panel, but it has no framework-neutral operational view of MongoDB clients, topology, databases,
collections, or indexes, and the existing JDBC/Flyway/Liquibase panels cannot represent those concepts. The new panel
will therefore be additive rather than an extension of the SQL-specific panels.

| Priority | Feature                               | Group         | Primary data source                                      | Mutation?         | Status  |
| -------- | ------------------------------------- | ------------- | -------------------------------------------------------- | ----------------- | ------- |
| Next     | MongoDB operational view              | Database      | Spring/Quarkus MongoDB client adapters                   | No                | Planned |
| Done     | Local Service Map (Live flow mode)    | Diagnostics   | Existing HTTP, JDBC, Kafka, and RabbitMQ evidence        | No                | Shipped |
| Done     | Trace ↔ Log ↔ Request correlation     | Diagnostics   | Existing Traces, Log Tail, and HTTP Exchanges data       | No                | Shipped |
| Done     | Bean / dependency graph visualization | Configuration | Existing Beans and Conditions data                       | No                | Shipped |
| Done     | E-mail Viewer                         | Services      | Spring Mail / Quarkus Mailer capture adapters            | No (capture only) | Shipped |
| Done     | Live Activity — REST call capture     | Diagnostics   | Spring HTTP clients / Quarkus MicroProfile REST clients  | No (capture only) | Shipped |
| Done     | Live Activity — new event types       | Diagnostics   | Cache, scheduled-task, messaging, mail, and REST capture | No (capture only) | Shipped |
| Done     | Transactions                          | Database      | Spring `TransactionExecutionListener` capture (Spring-only) | Yes (clear/pause) | Shipped |

The Trace ↔ Log ↔ Request correlation work in §3.1 has shipped as the **Live Activity** panel, building on the
already-shipped HTTP Exchanges panel and the existing Traces and Log Tail panels. The E-mail Viewer (§3.3) has shipped as
the **Email** panel (Services group): a `JavaMailSender` `BeanPostProcessor` captures every outgoing message
pass-through by default, with an explicitly opt-in `bootui.email.dev-trap` mode, recipients/subject/body revealed by
default (opt-in `bootui.email.mask-content`), a sandboxed HTML preview, and a per-message `.eml` download. All five of
the §3.4 Live Activity event-type extensions have
now shipped — Scheduled Task runs, Cache operations, messaging (Kafka and RabbitMQ on both adapters; JMS on Spring), an E-mail Viewer-backed `MAIL` event type, and
outbound `RestClient`/`RestTemplate`/`WebClient` capture. The bean/dependency graph visualization (§3.2) has shipped as a
graph mode in the existing Beans panel, completing this workstream. Each capture-oriented feature keeps pass-through
application behaviour by default
and makes any dev-trap mode explicitly opt-in.

The **Transactions** panel (Database group) has also shipped: a bounded in-memory `TransactionRecorder`, following the
same ring-buffer/aggregate-stats/pause/clear conventions as SQL Trace, captures every `@Transactional` boundary's
method, propagation, isolation, status, duration, and parent/child nesting via Spring Framework's
`TransactionExecutionListener` SPI, registered against every `ConfigurableTransactionManager` bean without replacing or
wrapping the application's own transaction management. Completed transactions are correlated to their SQL statement and
connection counts by reusing SQL Trace's existing thread/time-window correlation logic rather than duplicating it. The
panel is Spring-only (both servlet and WebFlux, the latter observing any blocking `PlatformTransactionManager` a
reactive application still uses); Quarkus has no comparable per-boundary listener hook on Narayana JTA or the CDI
`@Transactional` interceptor without much more invasive instrumentation, so it honestly reports unavailable there
rather than forcing false parity (see `docs/QUARKUS-SUPPORT.md` §5.5).

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

### 3.2 Bean / dependency graph visualization — Configuration ✅ Completed

**Status: completed.** Shipped as the **graph mode** of the existing Beans panel (see `docs/FEATURES.md` → *Beans*). The
original scope and design constraints below are retained for reference.

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

### 3.3 E-mail Viewer — Services ✅ Completed

**Status: completed.** Shipped as the **Email** panel (see `docs/FEATURES.md` → *Email*). The original scope and
design constraints below are retained for reference.

Captured outgoing mail (HTML preview plus raw source) is a high-value dev-loop aid with no built-in Spring equivalent.

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
- Recipients, subjects, and bodies are revealed by default, like BootUI's other
  data-capture panels (HTTP Exchanges, SQL Trace) — email content is not a config secret, so masking is decoupled from
  the global value-exposure model. An opt-in `bootui.email.mask-content` flag applies the same name-based
  `SecretMasker` heuristic for teams routing real customer PII through a shared dev environment. HTML is rendered
  sandboxed to prevent script execution.
- Fixed-size buffer; no persistence to disk beyond on-demand `.eml` download.

### 3.4 Live Activity — event types and correlation — Diagnostics ✅ Completed

**Status: completed.** All five event-type extensions below have shipped. Live Activity now merges nine entry types —
`REQUEST`, `SQL`, `EXCEPTION`, `SECURITY`, `CACHE`, `SCHEDULED`, `MESSAGING`, `MAIL`, and `REST_CLIENT` — from
BootUI's existing in-memory buffers (see `docs/SPECIFICATION.md` §5.14.2). The original scope, prioritized by value
versus new-instrumentation cost and drawn from the same comparable-dashboard benchmarks (Laravel Telescope, Symfony Web
Profiler, .NET Aspire) already guiding this workstream, is retained below for reference.

Scope — new event types, roughly in priority order:

- **Scheduled Task runs — implemented on both adapters.** Each `@Scheduled` method *execution* (start/success/failure,
  duration, exception if any) is captured as a `SCHEDULED` entry, reusing the existing Scheduled Tasks panel's
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
- **Messaging (Kafka/RabbitMQ/JMS) publish and consume — shipped.** The highest-value new-instrumentation
  candidate after mail and
  REST calls: async messaging is exactly where a Telescope/Aspire-style console helps most, since message flow is
  otherwise invisible outside the debugger. As scoped below, this landed **Kafka-first**: a `KafkaActivityRecorder`
  (framework-neutral, `bootui-engine`) is fed by `KafkaProducerCaptureBeanPostProcessor` /
  `KafkaConsumerCaptureBeanPostProcessor` (`bootui-spring-autoconfigure`, `@ConditionalOnClass(KafkaTemplate)`), which
  wrap application-owned `KafkaTemplate`/`@KafkaListener` container factory beans — composing with, not replacing, any
  existing `ProducerListener`/`RecordInterceptor` — feeding both the standalone **Kafka** panel (Services group) and,
  like Cache/Mail/REST Client, a `MESSAGING` entry into the merged Live Activity feed (topic, partition, offset, a hash
  of the key, direction, success/failure, consumer group id, listener id, duration).
  Message values/payloads are never captured (out of scope by design, sidestepping the payload-masking problem
  entirely). Controlled by `bootui.kafka.*` (see `docs/PROPERTIES.md`). **Spring JMS has now shipped**:
  `JmsProducerCaptureBeanPostProcessor` wraps every `JmsTemplate` bean via a
  CGLIB proxy to intercept `send`/`convertAndSend` calls, and `JmsListenerCaptureBeanPostProcessor` wraps every
  `AbstractJmsListenerContainerFactory` to intercept `createListenerContainer()` and wrap the returned container's
  listener with a matching plain or session-aware capture adapter, preserving its original dispatch path. Both feed a
  dedicated framework-neutral `JmsActivityRecorder` shared by Live Activity and a standalone **JMS** panel with
  destination/message-ID/subscription/listener filters and a confirmation-gated clear action. Its independent bounded
  buffer means JMS traffic cannot evict Kafka or RabbitMQ panel history; `bootui.jms.*` controls JMS without coupling any
  transport's enablement, retention, hashing, counters, or clear behavior. Gated on both `JmsTemplate` and the Jakarta JMS
  API, pass-through/fail-open, and not yet ported to Quarkus. The **Quarkus Kafka port (SmallRye Reactive Messaging) has
  now shipped**, reusing the same
  `KafkaActivityRecorder` and the same `bootui.kafka.*` keys/defaults: because Quarkus applications use SmallRye's
  `@Incoming`/`@Outgoing` channel model rather than `spring-kafka`'s imperative templates, the capture point is
  SmallRye's `OutgoingInterceptor`/`IncomingInterceptor` SPI, implemented by two `@ApplicationScoped` interceptors
  (`QuarkusKafkaProducerCapture`/`QuarkusKafkaConsumerCapture`, `bootui-quarkus`) that read Kafka record metadata into
  the shared recorder. They are the sole importers of the SmallRye messaging types, capability-gated on `Capability.KAFKA`
  via an `ExcludedTypeBuildItem` exactly like Hibernate/Cache/Flyway/Liquibase (production-dark), a no-op for non-Kafka
  (in-memory/RabbitMQ/JMS) channels, pass-through/fail-open, and set the lowest interceptor precedence so an
  application's own channel interceptor always wins. `LiveActivityResource` merges the captured `MESSAGING` entries into
  the feed adapter-side (top-level, no request correlation) via the shared `KafkaActivityEntries` mapping, so both
  adapters render byte-identical entries. **RabbitMQ has also shipped on both
  adapters** via a parallel `RabbitActivityRecorder` (`bootui-engine`) fed on Spring by
  `RabbitProducerCaptureBeanPostProcessor` (`addBeforePublishPostProcessors` on every `RabbitTemplate` bean,
  composing with any existing post-processors) and `RabbitConsumerCaptureBeanPostProcessor` (prepends a
  `MethodInterceptor` to every `AbstractRabbitListenerContainerFactory`'s advice chain, composing with existing
  advice, timing the `onMessage(Message)` invocation); on Quarkus it uses the same SmallRye
  `OutgoingInterceptor`/`IncomingInterceptor` SPI as the Kafka capture
  (`QuarkusRabbitProducerCapture`/`QuarkusRabbitConsumerCapture`), capability-gated on `IncomingRabbitMQMetadata`
  class presence via a `registerRabbitCapture` deployment build-step (production-dark, R2), exactly like
  Hibernate/Cache/Flyway. Both adapters use the same DTO for direction, routing metadata, success/failure, timing, and
  (opt-in via `bootui.rabbitmq.capture-correlation-id=true`, default `false`) a hashed correlation ID; Quarkus leaves
  producer exchange, consumer queue, and producer duration unavailable because SmallRye's callbacks do not expose them.
  Neither adapter captures the message body, arbitrary headers, or raw exception messages. Both SmallRye capture pairs
  are the sole importers of their connector-specific metadata types, are excluded from bean discovery when their
  extension is absent, are pass-through/fail-open, and use the lowest interceptor precedence so an application's own
  channel interceptor wins.
  `LiveActivityResource` merges both recorders' `MESSAGING` entries into the feed adapter-side (top-level, no request
  correlation), preserving the same wire shape across adapters. Kafka, RabbitMQ, and JMS are three unrelated client APIs,
  so each keeps an adapter-specific interception seam, a framework-neutral recorder, and an independent bounded buffer.
  Interception itself is more invasive than any existing capture source: it means wrapping the app's own messaging beans
  (a `BeanPostProcessor`, mirroring the existing HTTP Exchanges repository wrapper) or registering interceptor/advice
  hooks, so pass-through-by-default and fail-open wrapping are non-negotiable design constraints; message bodies are
  never captured rather than entrusted to a generic masker (they are arbitrary, potentially large application payloads,
  unlike a SQL statement or HTTP header). On Quarkus, the interception point was different in kind, not just in wiring:
  Quarkus applications typically use SmallRye Reactive Messaging (`@Incoming`/`@Outgoing` channels) rather than Spring's imperative
  `spring-kafka`/`spring-rabbit` templates, so the Quarkus capture is a per-adapter interceptor pair rather than the
  thinner Cache/Flyway provider seams — closer to the Beans panel's `BeanProvider` split (CDI vs. Spring bean
  introspection) in spirit, though the shared, framework-neutral recorders and entry mappings keep the engine seam intact.
  The panel-registration plumbing itself (an unconditional recorder `@Produces` bean
  plus the capability-gated interceptor beans) follows the existing optional-dependency template with a single
  build step per optional connector, which also lights up the standalone Kafka and RabbitMQ panels on both adapters.
- **Captured email — ✅ Shipped (both adapters).** The standalone Email panel (§3.3) already captured every outgoing
  message via the shared, framework-neutral `EmailCaptureService`; this item only adds a `MAIL` entry to the merged Live
  Activity feed, so — like Cache and Scheduled Task runs — it needed no new capture instrumentation, just a read of an
  existing buffer. Unlike `MESSAGING` entries (always top-level, no correlation attempted, since a message has no
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
- **Outbound REST call capture — ✅ Shipped (Spring servlet, Spring WebFlux, and Quarkus adapters).** Every `RestClient`/`RestTemplate`/
  `WebClient` built through Spring Boot's auto-configured builders is instrumented via Spring Boot's own
  `RestClientCustomizer`/`RestTemplateCustomizer`/`WebClientCustomizer` hooks, attaching a `RestClientTraceInterceptor`
  (`RestClient`/`RestTemplate`) or `RestClientTraceExchangeFilter` (`WebClient`) from inside the `customize(...)`
  callback — so a disabled panel or `bootui.rest-client-trace.enabled=false` adds no interceptor at all, and
  pass-through application behaviour is unaffected either way. Both customizer configurations live in the shared
  `BootUiEngineConfiguration`, gated by `@ConditionalOnClass` on the optional `spring-boot-restclient`/
  `spring-boot-webclient` modules, so servlet and WebFlux wire identically and the configuration is skipped entirely
  when a module is absent. The framework-neutral `RestClientTraceRecorder` (`bootui-engine`) captures each call (client
  type, URI, method, status, duration, headers/call-site when enabled) into a bounded buffer, feeding both the
  standalone REST Client panel and, like Cache/Mail, a `REST_CLIENT` entry into the merged Live Activity feed —
  nesting as a `REQUEST` child via the same trace-id-then-thread join `SQL`/`CACHE`/`MAIL` use (see the `MAIL` bullet
  above for why `EXCEPTION`/`SECURITY` are not part of that list), and adding
  `restCallErrorRatePercent`/`restCallP95LatencyMs` KPI tiles deep-linked to `/rest-client-trace`. Quarkus uses the
  supported MicroProfile `RestClientListener` SPI (`QuarkusRestClientTraceListener`, registered through a generated
  service-provider entry only when `Capability.REST_CLIENT_REACTIVE` is present) to attach a
  `QuarkusRestClientTraceFilter` at transport-bracketing priority on every client proxy. Capture is metadata-only on
  Quarkus: no headers or bodies are read, and URI credentials/sensitive query values are sanitized before storage.
  Quarkus reports pre-response transport failures with status `0`; the filter maps those to failed calls with no HTTP
  status, while real `4xx`/`5xx` responses remain transport-successful error responses. A capability-absent exclusion
  keeps the optional listener type unlinked, and the recorder's Quarkus OTel `TraceIdProvider` feeds trace-only Live
  Activity correlation.

**Done** — Quarkus REST Client capture shipped (issue #653).

Scope — enhancements on top of the shipped event types, generally cheaper than a new source and some of higher value:

- **Extend the KPI strip** with metrics for each new source as it lands — this has now shipped for every source that
  has one: outbound-call error rate/p95 (REST call capture), cache hit ratio (Cache), and scheduled-task failure count
  (Scheduled Task runs).
- **Verify persistence and filtering stay generic over `type`** as new event types are added — `JdbcActivityStore`,
  `BufferedActivityStore`, and the client-side type filter chips pick up new types automatically; keep confirming this
  with tests if any further event type lands.
- **Add deep links** for each entry type into its own source panel — Cache, Scheduled Tasks, Kafka, RabbitMQ, JMS, and
  REST Client entries now link to `/cache`, `/scheduled`, `/kafka`, `/rabbitmq`, `/jms`, and `/rest-client-trace`,
  respectively, joining the existing
  per-entry deep links into HTTP Exchanges (REQUEST), SQL Trace (SQL), and Exceptions (EXCEPTION); the KPI strip's own
  launchpad cards additionally deep-link Health and Heap usage.

Design constraints:

- Every new source stays **read-only** and **fails closed** when its backing bean/class is absent, consistent with
  §3.1/§3.3 and the cross-cutting rules in §4.
- Messaging payloads are never captured; cache values are omitted and cache keys are hashed rather than shown raw even
  under full exposure.
- New capture buffers are bounded and self-filtering (BootUI's own traffic must not appear in its own feed), consistent
  with the existing `bootui.monitoring.exclude-self` behaviour.
- Actual shipped sequencing: Scheduled Task runs and Cache operations landed first, then `MESSAGING` (initially Kafka,
  later RabbitMQ and Spring JMS), then Mail, and finally REST call capture — all five event types now ship, each with
  `REQUEST`-nesting where a nesting relationship applies (`MESSAGING` is deliberately always top-level).

### 3.5 MongoDB operational view — Database 📋 Planned

BootUI already detects Spring Data MongoDB repository metadata under the existing Spring Data panel. This new panel
addresses a different question: "Which MongoDB clients and data structures is this running application connected to, and
which operational risks should I review?" It must not force document-database concepts into JDBC connection-pool, SQL
Trace, Flyway, or Liquibase contracts.

Scope:

- Add one shared `mongodb` panel and `/bootui/api/mongodb/**` contract for Spring servlet, Spring WebFlux, and Quarkus.
- On initial load, report only locally available client/configuration metadata and inspection state. Do not contact a
  MongoDB server merely because the route rendered.
- Provide an explicit **Inspect** action that reads a bounded snapshot of reachable server/topology information,
  databases, collections, and indexes. Results must be capped and paged where cardinality can grow, and a permissions
  failure for one database or collection must be reported against that target without discarding the rest of the
  snapshot.
- Surface read-only review prompts for high-value, evidence-based issues such as missing indexes for declared repository
  metadata where this can be determined safely, unexpectedly large unindexed collections, or unsafe development
  configuration. Do not infer a finding when the server or required metadata is unavailable.
- Support named/multiple clients and both supported driver styles where the host framework exposes them, while returning
  the same stable DTOs and UI on every adapter.

Architecture:

- Put report assembly, bounds, ordering, and advisory policy in a JSON-free, framework-neutral engine service. Define a
  neutral MongoDB provider SPI; adapters translate their native driver metadata into core records.
- Keep MongoDB driver imports out of the engine. Spring wiring must be classpath/bean-gated. Quarkus wiring must be
  capability-gated and exclude the optional driver-dependent provider classes when the MongoDB extension is absent, using
  the existing Hibernate/Cache/Flyway/Liquibase optional-dependency pattern.
- Treat client settings as live policy inputs where they can change at runtime. Never serialize credentials, raw
  connection strings, authentication sources, TLS key material, document values, or arbitrary command responses; route
  displayable addresses and settings through the existing exposure/masking policy.

Out of scope for the first release:

- Browsing, searching, editing, inserting, or deleting documents.
- An arbitrary MongoDB shell or command runner.
- Creating or dropping databases, collections, or indexes.
- Query tracing/profiling, change-stream capture, schema inference from stored documents, or migration tooling.
- Replacing the Spring Data repository panel; its existing MongoDB repository metadata remains a complementary
  Spring-specific view.

Acceptance criteria:

- With no supported MongoDB client/extension, the panel is unavailable with a framework-correct setup hint and no
  optional driver classloading failure.
- Opening the panel performs no MongoDB network call. Only the explicit Inspect action contacts configured servers, and
  the shared localhost/write guard plus panel read-only policy protects that action even though it does not mutate data.
- Inspection uses configurable timeouts and hard caps, returns partial results with explicit per-target errors, and never
  exposes document contents or secrets.
- Spring servlet, Spring WebFlux, and Quarkus run the same conformance contract and render the same fixture shape for
  equivalent MongoDB metadata.
- The sample applications cover absent-client, unreachable-server, insufficient-permission, empty-database, and
  multi-client states without requiring MongoDB for the default Docker-free test path.

### 3.6 Local Service Map — Live Activity ✅ Completed

**Status: completed.** Shipped as the **Live flow** mode of the Live Activity panel (see `docs/FEATURES.md` →
*Live Activity → Live flow*, and `docs/SPECIFICATION.md` §5.14.2.1), on Spring MVC, Spring WebFlux, and Quarkus in one
step rather than the MVC-only MVP originally sketched below — the framework-neutral `ServiceMapAssembler` made the two
extra adapters thin enough that a stack-specific phase would have cost more than it saved. It is a **mode**, not a new
panel: the map is a second reading of evidence Live Activity already merges, so it reuses that panel's id, route,
availability, read-only policy, and SSE tick instead of adding a 53rd route. The original scope is retained below, with
two deliberate deviations recorded inline: the surface is a Live Activity mode rather than an Overview panel, and one
generic inbound local-HTTP-client lane was added so the map shows what reaches the application as well as what it
reaches out to.

The Local Service Map answers: "What external systems does this running application depend on, and what evidence
has BootUI recently observed for each relationship?" It assembles a single, read-only topology from data BootUI
already owns rather than introducing distributed tracing infrastructure, network discovery, or active health probes.

User value:

- Give developers an immediate mental model of the application's local integration surface without searching across
  Connection Pools, REST Client, Kafka, RabbitMQ, traces, and configuration panels.
- Distinguish **configured** dependencies from **observed** runtime relationships so absence of traffic is not mistaken
  for absence of a dependency.
- Highlight retained failures and last-seen activity as debugging evidence, while explicitly avoiding claims about the
  current health of a remote system.
- Provide an evidence trail and deep link from every node to its source panel, turning the map into a navigation surface
  rather than a decorative diagram.

Initial scope:

- Center the running application and group outbound dependencies by safe identity: HTTP origin
  (`scheme://host:port`), JDBC pool/target, Kafka topic, and RabbitMQ exchange/routing destination.
- **Added during implementation:** fold completed *incoming* requests into a single generic local HTTP client lane
  feeding the application. Per-caller nodes are deliberately not derived — a remote address is neither a stable identity
  nor safe to display here — so the lane stays one honest node rather than a guessed caller inventory.
- Show protocol, configured/observed state, retained interaction and failure counts, distinct operation count, and
  last-seen time where the source supports them.
- Reuse only existing bounded sources: REST-client trace capture, masked connection-pool reports, Kafka activity, and
  RabbitMQ activity. Include producer/publisher evidence only; incoming consumer traffic is not an outbound edge.
- Offer protocol and text filters, keyboard-selectable nodes, an accessible textual detail view, responsive layouts, and
  links to the source evidence panels.
- Respect every source panel's enablement setting. If a source panel is disabled or unavailable, its evidence must not be
  included in the map.

Safety and interpretation:

- Opening the panel performs no network call, probe, DNS lookup, connection attempt, scan, or new interception.
- JDBC labels and details must use the existing exposure/masking policy. HTTP identities must omit user-info, paths,
  query values, and fragments. Messaging payloads remain completely out of scope.
- A retained failure means only that an interaction in the bounded buffer failed; it must not be presented as live
  dependency health. Unknown and stale evidence must remain visually distinct from healthy or failing states.
- Cardinality must be bounded before rendering. High-cardinality destinations should be summarized or truncated with a
  visible explanation rather than silently overwhelming the graph.

Architecture and sequencing:

- **Shipped as delivered:** a framework-neutral, JSON-free `ServiceMapAssembler` (`bootui-engine`) over existing core
  DTOs and recorders, plus a stable core DTO contract (`ServiceMapReport`/`ServiceMapNodeDto`/`ServiceMapEdgeDto`/
  `ServiceMapInteractionDto`/`ServiceMapTruncationDto`) and three thin bindings: one shared `LiveServiceMapController`
  registered in both Spring autoconfigurations, and a `LiveServiceMapResource` on Quarkus.
- No instrumentation was added. A relationship that cannot be derived safely from existing evidence is absent rather
  than guessed.
- The shared availability-driven conformance suite asserts the contract, bounds, and identity safety on all three
  runtimes.
- Keep graph layout in the Vue client and report assembly, grouping, sorting, bounds, and interpretation in the engine.
  Do not introduce a graph database or a new visualization dependency unless native SVG proves insufficient.

**Later increment — opaque flow correlation, Cache as a first-class dependency, and causal motion sequencing.** The
map's animation originally treated every stable edge's new evidence as an independent blip; it had no notion that an
inbound request, a cache access, a SQL statement, and an outbound call could be evidence of the very same request's
path through the application. This increment closes that gap without adding any new capture:

- `ServiceMapAssembler` now derives an opaque `ServiceMapInteractionDto.flowId` from whatever distributed-trace id
  (already captured by the HTTP Exchanges, SQL Trace, REST Client, and Cache recorders) was active when each
  interaction completed — one-way SHA-256 (`ServiceMapIdentities.flowId`), never the raw trace id, and `null` for a
  blank/absent trace or for Kafka/RabbitMQ (which never carry a trace id at capture time and stay uncorrelated).
  Interactions sharing a trace share a `flowId`, letting the client recognize one causal flow across edges instead of
  treating every edge as unrelated.
- **Cache joins the map as a first-class dependency (Spring MVC and Spring WebFlux only).** The same
  `CacheActivityRecorder` behind the Cache panel and Live Activity's `CACHE` entries now also feeds
  `ServiceMapSources.cacheEvents`, gated identically (Cache panel enabled *and* the recorder itself capturing).
  Accesses group by the safe cache-manager/cache-name identity — never the accessed key or value — and surface the
  same `HIT`/`MISS`/`PUT`/`EVICT`/`CLEAR` operations Live Activity already shows. Cache dependencies are
  observed-only (`configured: false`), the same honesty rule Kafka/RabbitMQ dependencies use, since no independent
  cache-configuration evidence is wired in yet. A `MISS` is never a retained failure, since it is a normal outcome.
  Quarkus continues to honestly report `cacheAvailable: false` — it has no comparable interception seam for
  `quarkus-cache` (see `docs/QUARKUS-SUPPORT.md`) — with no invented capture path. One notable side effect: because
  `ServiceMapAssembler` is fully shared, Quarkus's and Spring WebFlux's existing OpenTelemetry-backed trace id
  stamping on HTTP/SQL/REST capture already gives both adapters the same `flowId` correlation for those three
  sources at no extra cost; only cache participates on Spring MVC/WebFlux alone.
- **A new pure `sequenceFlowPulses` helper (`bootui-ui`)** paces a batch of freshly diffed pulses: within a shared,
  non-null `flowId`, the inbound leg always starts immediately and downstream pulses start only once that inbound pulse
  would have finished arriving at the application. Downstream completions replay in ascending retained timestamp order
  (cache precedes JDBC/outbound HTTP only as an equal-millisecond tie-break), so the UI never invents an execution order,
  and further downstream pulses in the same flow are staggered by a small, bounded step. Pulses with no `flowId`, and any
  flow whose current batch carries no retained inbound pulse, are left untouched — they animate immediately exactly
  as before, so an orphaned downstream pulse never waits for an inbound arrival that batch will never carry. The
  animation queue's existing concurrency and per-edge caps apply unchanged regardless of sequencing, so a
  causally-sequenced burst can never exceed the same bounds an unrelated one would.
- **Slow interactions are now unmistakable by timing, not color alone:** duration itself now differs by tone — a calm
  amber pulse (with a restrained trailing halo) for a slow completion runs 1200–1500ms, a normal completion
  650–850ms, and a failure 900–1100ms — and a non-color "slow" text label appears in the node detail view and in
  live-region announcements alongside the existing amber/red styling. A sequenced pulse's CSS Motion Path animation
  stays fully transparent for its entire mount-relative delay, so it never flashes into view before its causal
  predecessor has arrived; every pulse still plays exactly once, linearly, with no bounce, loop, or drift.
- Reduced motion is deliberately unchanged in timing: it never sequences or delays anything (there is no travel to
  pace), so every changed edge is still emphasized immediately. Its live-region announcement was extended with a
  `describeFlowSequence` narration so a screen-reader user gets the same causal story — "Flow: &lt;inbound&gt; →
  &lt;cache&gt; → &lt;outbound&gt;" — that sighted users read from the sequenced motion.

**Later refinement — bounded hybrid layout and transient target evidence.** Typical maps now use a fixed-radius
right-facing fan through six dependencies, while denser maps switch to a spacious two-column rack bounded at 1,040
logical pixels wide. A 72-pixel row pitch reserves the SLOW/ERROR chip and ring envelope; the 28-node rack is 1,046
pixels tall inside the existing scrollable stage. Smooth fan paths and deterministic dense routes keep every connector
clear of unrelated nodes, and CSS Motion Path consumes each connector's exact path with mount-relative timing. Retained
failures no longer permanently color topology nodes or edges. Accepted slow/failure pulses instead schedule
reference-counted target rings and explicit `SLOW · <duration>` / `ERROR` chips for exactly their own delay and duration;
inbound targets the application, outbound targets the dependency, and failure wins only while overlapping slow.
Reduced-motion users receive the same target semantics as a brief static emphasis plus explicit live narration.

Complexity and risks:

- **Estimated complexity: medium for the MVC MVP; medium-high for a shared production feature.** Aggregation is
  straightforward, but identity normalization, partial evidence, dense graph UX, optional source wiring, and equivalent
  adapter semantics require careful contracts.
- The largest product risk is false confidence: incomplete observation can look authoritative. Copy and visual states
  must continuously communicate configured vs. observed vs. unknown.
- The largest technical risks are leaking endpoint/configuration details, creating unbounded/high-cardinality graphs,
  misclassifying incoming messaging as outbound, and coupling the engine to framework-specific recorder types.

Acceptance criteria (met):

- A Spring MVC sample with JDBC configuration and a retained outbound HTTP failure renders both relationships without
  making any request from the map itself.
- Empty, source-disabled, unavailable, malformed-identity, and high-cardinality inputs render safely and clearly.
- No secret, HTTP path/query value, message payload, or unmasked JDBC credential reaches the response.
- The panel remains usable by keyboard and screen reader and at desktop and mobile widths.
- The feature ships inside Live Activity rather than as its own sidebar entry, so the cross-panel synthesis has to earn
  attention next to the feed it complements before it would ever justify a route of its own.

## 4. Cross-cutting work for every new panel

For each feature above, the following must move together, consistent with the existing panel-registration process:

- Stable BootUI DTOs in `bootui-core` for all browser-facing responses.
- A framework-neutral engine service and SPI where the data source differs by runtime, plus thin Spring MVC/WebFlux and
  Quarkus HTTP adapters. Keep optional framework/driver types in gated adapter classes.
- Panel registration in `BootUiPanels` and per-adapter `/bootui/api/panels` availability wiring, including the
  disabled/unavailable sidebar state. Append new action-capable panels last to keep index-coupled tests stable.
- A Vue 3 route and panel with empty/unavailable states, server-side filtering/paging where lists can be large, and the
  shared masking-aware rendering.
- Per-panel enable/disable and read-only properties, documented in `docs/PROPERTIES.md`.
- Backend slice and edge-case tests, frontend unit tests, and sample-app Playwright coverage. Update the hard-coded panel
  counts/indices in `PanelsControllerTests`, `BootUiAutoConfigurationTests`, `PanelAccessFilterTests` (action-capable
  panels only), `routes.test.js`, and e2e `app-shell.spec.js`.
- Documentation updates in `docs/FEATURES.md`, `docs/PROPERTIES.md`, `docs/SPECIFICATION.md`, and the relevant platform
  support document, plus screenshots at the project's standard size.

## 5. Risks

| Risk                                                              | Feature(s) | Impact | Mitigation                                                                                                |
| ----------------------------------------------------------------- | ---------- | ------ | --------------------------------------------------------------------------------------------------------- |
| Exposing sensitive headers, trace context, or mail body           | 3.1, 3.3   | High   | Loopback-only activation, masking/value-exposure on every new surface, sandboxed HTML, and focused tests. |
| Unbounded capture buffers or large rendered graphs/lists          | 3.2, 3.3   | Medium | Fixed-size buffers, server-side paging, bounded snapshots, and focus-and-neighborhood graph rendering.    |
| Optional Actuator endpoints, libraries, beans, or servers missing | all        | Medium | Internal bridges, classpath/bean gating, stable empty DTOs, and clear unavailable reasons per panel.      |
| Bean/dependency graph or correlation bloating the bundle          | 3.1, 3.2   | Medium | Bounded rendering, lightweight visualization, and lazy-loaded panels.                                     |
| Silently swallowing application mail                              | 3.3        | Medium | Pass-through by default; "dev trap" mode strictly opt-in.                                                 |
| Over-broad or noisy new Live Activity event types (e.g. cache operations) | 3.4 | Medium | Explicit opt-in wiring by bean/class presence, bounded buffers, masked payloads/hashed cache keys. |
| Messaging capture's added optional-dependency surface (Kafka/RabbitMQ/JMS clients), invasive interception of app-owned messaging beans, and a per-adapter capture design (SmallRye Reactive Messaging on Quarkus vs. imperative templates on Spring) | 3.4 | High | Kafka and RabbitMQ shipped on **both adapters**, and JMS on Spring, with classpath/capability gating identical to Hibernate/Cache/Flyway/Liquibase, pass-through/fail-open wrapping, bounded metadata-only buffers, and no message-value/payload capture. |
| MongoDB inspection leaks credentials/documents or performs surprising network work | 3.5 | High | Never expose documents or raw connection strings; initial render is network-free; inspection is explicit, bounded, timed out, masked, and read-only. |
| MongoDB optional drivers break applications without the extension | 3.5 | High | Keep driver types in adapter-only providers and use Spring classpath gates plus Quarkus capability/exclusion build steps. |
| Large MongoDB catalog or partial permissions make inspection slow or misleading | 3.5 | Medium | Hard caps, paging, configurable timeouts, partial-result DTOs, and per-target permission errors. |
| Scope creep beyond the planned MongoDB inventory/advisor surface | 3.5 | High | Keep document browsing, arbitrary commands, writes, tracing, and migrations out of the first release. |
| Incomplete service-map evidence creates false confidence or false health claims | 3.6 | High | **Shipped:** `configured` and `observed` are separate fields that are never collapsed, `outcome` is `NO_EVIDENCE`/`OBSERVED_OK`/`RETAINED_FAILURES`, statement-to-pool attribution is refused when ambiguous, and the copy states plainly that a retained failure is evidence, not a health check. |
| Service-map identities expose secrets or create an unbounded topology | 3.6 | High | **Shipped:** HTTP identities are reduced to `scheme://host[:port]` (user-info, path, query, and fragment dropped); JDBC targets independently strip authority/Oracle credentials and parameter tails regardless of exposure mode; complete sanitized identities drive grouping and SHA-256-derived stable ids while only labels are truncated; payloads/keys/SQL text never enter the contract; and 28 dependencies / 6 interactions per edge are enforced before serialization with visible truncation. |

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
- [ ] `docs/FEATURES.md`, `docs/PROPERTIES.md`, `docs/SPECIFICATION.md`, and the relevant platform support document
      describe the new surface, with screenshots at the standard size.
- [ ] Spring Boot stays disabled in `prod`/`production` unless explicitly enabled; Quarkus remains production-dark in
      normal launch mode; and every adapter rejects non-local requests.
