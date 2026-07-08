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

Several open features remain, grouped by priority. The §3.1 correlation item has shipped as the **Live Activity** panel;
the graph item remains in scope, and the capture-heavy e-mail viewer lands after the read-only items because it needs
stricter masking, bounding, sandboxing, and opt-in behaviour. The Live Activity panel itself keeps growing: a REST call
capture branch and the ideas in §3.4 are the panel's next steps.

| Priority | Feature                               | Group         | Primary data source                                | Mutation?         | Origin           |
| -------- | ------------------------------------- | ------------- | -------------------------------------------------- | ----------------- | ---------------- |
| Done     | Trace ↔ Log ↔ Request correlation     | Diagnostics   | Existing Traces, Log Tail, and HTTP Exchanges data | No                | Existing roadmap |
| 1        | Bean / dependency graph visualization | Configuration | Existing Beans and Conditions data                 | No                | Existing roadmap |
| 2        | E-mail Viewer                         | Diagnostics   | Intercepted `JavaMailSender`                       | No (capture only) | New addition     |
| 3        | Live Activity — REST call capture     | Diagnostics   | Intercepted `RestClient`/`RestTemplate`/`WebClient` | No (capture only) | In progress (branch) |
| 4        | Live Activity — new event types       | Diagnostics   | New/expanded capture sources (see §3.4)            | No (capture only) | Kafka shipped (Spring-only); other sources pending |

The Trace ↔ Log ↔ Request correlation work in §3.1 has shipped as the **Live Activity** panel, building on the
already-shipped HTTP Exchanges panel and the existing Traces and Log Tail panels. Two Live Activity extensions are
already underway outside this document (an E-mail Viewer-backed `MAIL` event type, tracked as §3.3, and an outbound
`RestClient`/`RestTemplate`/`WebClient` capture branch that is not yet a PR); §3.4 captures further ideas for what
should come next in the panel, on top of those two. Each capture-oriented feature must keep pass-through application
behaviour by default and make any dev-trap mode explicitly opt-in.

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

### 3.3 E-mail Viewer — Diagnostics

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

### 3.4 Live Activity — future event types and correlation — Diagnostics

Live Activity currently merges five entry types — `REQUEST`, `SQL`, `EXCEPTION`, `SECURITY`, and `MESSAGING` — from
BootUI's existing in-memory buffers (see `docs/SPECIFICATION.md` §5.14.2). Two extensions are already underway outside
this plan: the
E-mail Viewer (§3.3) adds a `MAIL` event, and a separate branch (not yet a PR) adds an outbound
`RestClient`/`RestTemplate`/`WebClient` capture. This section captures what should come next in the panel, prioritized by
value versus new-instrumentation cost, drawn from the same comparable-dashboard benchmarks (Laravel Telescope, Symfony
Web Profiler, .NET Aspire) already guiding this workstream.

Scope — new event types, roughly in priority order:

- **Scheduled Task runs.** The existing Scheduled Tasks panel only shows static `@Scheduled` definitions; capturing each
  *execution* (start/success/failure, duration, exception if any) as a `SCHEDULED_TASK` entry reuses that panel's
  existing discovery and closes an obvious "did my job run, and how long did it take" gap. No request parent (background
  thread), but nests a correlated exception the same way `REQUEST` does today.
- **Cache operations.** The Cache panel shows topology and aggregate hit/miss counters only; a lightweight, sampled
  `CACHE` event (hit/miss/put/evict, cache name, key hash — never the raw key/value) explains *why* those counters moved
  and correlates naturally as a `REQUEST` child, mirroring how `SQL` nests today.
- **Messaging (Kafka/RabbitMQ/JMS) publish and consume — Kafka shipped (Spring-only).** The highest-value new-instrumentation
  candidate after mail and
  REST calls: async messaging is exactly where a Telescope/Aspire-style console helps most, since message flow is
  otherwise invisible outside the debugger. As scoped below, this landed **Kafka-first**: a `KafkaActivityRecorder`
  (framework-neutral, `bootui-engine`) is fed by `KafkaProducerCaptureBeanPostProcessor` /
  `KafkaConsumerCaptureBeanPostProcessor` (`bootui-spring-autoconfigure`, `@ConditionalOnClass(KafkaTemplate)`), which
  wrap application-owned `KafkaTemplate`/`@KafkaListener` container factory beans — composing with, not replacing, any
  existing `ProducerListener`/`RecordInterceptor` — and surface every send/delivery outcome as a `MESSAGING` entry
  (topic, partition, offset, truncated key, direction, success/failure, consumer group id, listener id, duration).
  Message values/payloads are never captured (out of scope by design, sidestepping the payload-masking problem
  entirely). Controlled by `bootui.kafka.*` (see `docs/PROPERTIES.md`). RabbitMQ/JMS remain later, separately-scoped
  follow-ups, and the Quarkus port (SmallRye Reactive Messaging) remains out of scope — Unlike the other items above,
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
  HTTP header). On Quarkus, the natural interception point is different in kind, not just in wiring: Quarkus applications
  typically use SmallRye Reactive Messaging (`@Incoming`/`@Outgoing` channels) rather than Spring's imperative
  `spring-kafka`/`spring-rabbit` templates, so a shared `MessagingProvider`-style SPI would need materially different
  per-adapter implementations — closer to the Beans panel's `BeanProvider` split (CDI vs. Spring bean introspection) than
  the thinner Cache/Flyway provider seams. The panel-registration plumbing itself (a `@Produces` bean + auto-discovered
  `@Path` resource) follows the existing GitHub-panel template with no deployment build-step change, but the capture
  mechanism is effectively a second engine-service design per adapter, not a thin shared seam — still an open item for a
  future Quarkus iteration.

Scope — enhancements on top of the existing/in-flight event types, generally cheaper than a new source and some of
higher value:

- **Nest the REST call capture as a `REQUEST` child**, the same way `SQL`/`EXCEPTION`/`SECURITY` nest today, once that
  branch lands, so a request that fans out to outbound calls shows the whole causal chain in one profiler view. This is
  likely the single highest-value change once REST call capture ships, since correlation — not just another list — is
  the panel's core value proposition.
- **Nest `MAIL` entries under their triggering `REQUEST`** the same way, once the E-mail Viewer lands, using the same
  trace-id/serving-thread tiered join the profiler already uses.
- **Extend the KPI strip** with metrics for each new source as it lands: outbound-call error rate/p95, cache hit ratio,
  scheduled-task failure count.
- **Verify persistence and filtering stay generic over `type`** as new event types are added — `JdbcActivityStore`,
  `BufferedActivityStore`, and the client-side type filter chips should pick up new types automatically, but this should
  be confirmed with tests as each lands.
- **Add deep links** from `REQUEST` entries into the Cache and Scheduled Tasks panels, matching the existing deep links
  into HTTP Exchanges, SQL Trace, Exceptions, Health, and Heap Dump.

Design constraints:

- Every new source stays **read-only** and **fails closed** when its backing bean/class is absent, consistent with
  §3.1/§3.3 and the cross-cutting rules in §4.
- Sensitive payloads (cache keys/values, message bodies) are masked by default and follow the same value-exposure model
  as the rest of the panel; cache keys are hashed rather than shown raw even under full exposure.
- New capture buffers are bounded and self-filtering (BootUI's own traffic must not appear in its own feed), consistent
  with the existing `bootui.monitoring.exclude-self` behaviour.
- Recommended sequencing: land Mail (§3.3) and REST call capture with `REQUEST`-nesting from day one; then Scheduled
  Task runs and Cache operations (cheapest, reuse existing panel discovery); treat Messaging as the next major
  new-instrumentation investment.

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
| Messaging capture's added optional-dependency surface (Kafka/RabbitMQ/JMS clients), invasive interception of app-owned messaging beans, and a per-adapter capture design (SmallRye Reactive Messaging on Quarkus vs. imperative templates on Spring) | 3.4 | High | Kafka shipped Spring-only with classpath/capability gating identical to Hibernate/Cache/Flyway/Liquibase, pass-through-by-default fail-open wrapping, and no message-value/payload capture at all (metadata-only, sidestepping body masking); Rabbit/JMS and the Quarkus port remain separate follow-ups. |
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
