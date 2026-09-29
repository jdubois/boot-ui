# BootUI Implementation Plan

## 1. Strategy

BootUI adds a safe, local-only developer console to a running application, shipping on **Spring Boot 4 (servlet and
WebFlux starters) and Quarkus (an extension)** from one shared, framework-neutral engine that serves the same Vue UI and
the same `/bootui/api/**` contract on every runtime. The released surface covers 60 panels across runtime introspection,
configuration, database migrations, services, diagnostics, project health, and developer tooling. A **MySQL**
operational sibling to PostgreSQL is delivered (§3.17); the planned **MongoDB** operational view (§3.5) remains
a separate workstream.

The priorities for every item below remain unchanged:

1. Safety and local-only operation.
2. Easy installation with no extra setup.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

Each new panel must:

- be **read-only or read-mostly**, with any mutating control explicitly confirmation-gated like the existing Cache
  clear action;
- **fail closed** when its required classes, beans, Actuator endpoints, or data are unavailable, returning stable empty
  DTOs and a clear unavailable reason;
- route any sensitive property names, headers, addresses, or values through the existing masking and value-exposure model;
- ship with backend slice/edge-case tests, `/bootui/api/panels` availability wiring, docs, router ordering, and sample-app
  Playwright coverage in sync.

## 2. Roadmap status and next workstream

MySQL's bounded operational view is **delivered**, with Oracle MySQL 8.4.6 live coverage through JDBC on all three
stacks. Its source/runtime acceptance and browser integration are verified (§3.17).
MariaDB remains a separate unsupported follow-up.

MongoDB remains the next planned feature workstream. BootUI already recognizes Spring Data MongoDB repositories in the
Spring Data panel, but it has no framework-neutral operational view of MongoDB clients, topology, databases,
collections, or indexes, and the existing JDBC/Flyway/Liquibase panels cannot represent those concepts. The new panel
will therefore be additive rather than an extension of the SQL-specific panels.

A second, diagnostics-focused workstream (§3.18–§3.24) shapes already-captured evidence so it reads the way developers
investigate: every entry point anchors a correlated timeline, logs link to the execution that wrote them, entities read
as summary → runs → timeline, and failure evidence outlives routine traffic. Sampling, quotas, remote ingestion,
alerting integrations, and personal-data capture stay out of scope, because BootUI remains local-only, bounded, and
network-free on render. Each item builds on existing capture points and retained evidence, adding only bounded metadata
and explicit, on-demand local reads.

| Priority | Feature                  | Group    | Primary data source                    | Mutation? | Status  |
| -------- | ------------------------ | -------- | -------------------------------------- | --------- | ------- |
| Delivered | MySQL operational view  | Database | Existing application JDBC datasources | No application-data mutation; explicit read | Delivered |
| Next     | MongoDB operational view | Database | Spring/Quarkus MongoDB client adapters | No        | Planned |
| Planned  | Declarative HTTP client registry | Services | Spring HTTP clients / Quarkus REST Client metadata | No | Planned |
| Planned  | gRPC | Services | Spring gRPC / Quarkus gRPC registries and metrics | No | Planned |
| Planned  | Spring Batch | Services | Spring Batch `JobExplorer` / `JobRepository` | No | Planned |
| Planned  | Correlation-ID filtering | Diagnostics | Existing request and Live Activity capture | No (capture only) | Planned |
| Planned  | Execution-context profiles | Overview | Existing scheduled-run and messaging capture | No (capture only) | Planned |
| Planned  | Log correlation | Diagnostics | Existing Logback appender and Quarkus log handler | No (capture only) | Planned |
| Planned  | Route performance rankings | Diagnostics | Existing HTTP exchange and route-template evidence | No | Planned |
| Planned  | Scheduled task run history | Services | Existing `ScheduledTaskRunStore` | No | Planned |
| Planned  | Failure-preserving retention and ignore rules | Diagnostics | Existing bounded capture buffers | No (capture only) | Planned |
| Planned  | Agent-ready profiles and exception export | Developer tools | Existing profiler and exception store | No | Planned |
| Planned  | Source context for application frames | Diagnostics | Local exploded-build source tree | No | Planned |
| Delivered | Fault Tolerance | Services | Resilience4j / Spring Retry / SmallRye Fault Tolerance | No (capture only) | Delivered |
| Delivered | WebSocket endpoints | Services | Spring WebSocket/STOMP / Quarkus WebSockets Next | No (capture only) | Delivered |
| Delivered | Error-contract catalogue | Services | Spring exception handlers / Quarkus exception mappers | No | Delivered |
| Delivered | Slow-SQL ranking and URI attribution | Database | Existing SQL Trace and HTTP exchange evidence | No | Delivered |
| Delivered | Meter provenance and explanation | Diagnostics | Existing meter registry and curated catalogue | No | Delivered |
| Delivered | Cache tiering and hit ratios | Services | Existing cache managers and native statistics | No | Delivered |
| Delivered | Command-line endpoint, `bootui` CLI and Command Line panel | Developer tools | Existing MCP tool catalog and dispatcher | No (same policy as MCP) | Delivered |

## 3. Feature specifications

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

### 3.6 Declarative HTTP client registry — Services 📋 Planned

BootUI's REST Client panel shows calls that have already happened, but it does not show which HTTP clients the application
declares, how each client resolves its target, or which effective transport policies apply before the first request. This
new panel provides that static and runtime configuration view without making network calls or replacing REST Client trace.

Scope:

- Add one shared HTTP client registry panel and stable `/bootui/api/http-clients/**` contract for Spring servlet, Spring
  WebFlux, and Quarkus.
- Discover Spring `@HttpExchange` interfaces, OpenFeign clients, `RestClient.Builder` beans, and `WebClient.Builder` beans,
  plus Quarkus/MicroProfile `@RegisterRestClient` interfaces and framework-managed client builders.
- Report each client's framework/type, bean or registration name, declared interface where applicable, configured base
  URL, safely resolved base URL after property interpolation, and effective timeout, connection-pool, retry, redirect,
  proxy, and TLS settings when the framework exposes them.
- Show provenance for every effective value so users can distinguish a client-specific override from builder, framework,
  or application defaults. Unknown or inaccessible values must remain explicitly unavailable rather than inferred.
- Cross-link a client to matching retained calls in REST Client trace only when BootUI can attribute them safely. Ambiguous
  builder-derived clients remain unlinked rather than being matched by a guessed host or bean name.
- Support multiple named clients and multiple builders while returning the same stable DTOs and UI on every adapter.

Architecture:

- Put normalization, ordering, provenance, safe URL handling, and report assembly in a JSON-free, framework-neutral engine
  service behind a neutral HTTP client metadata provider SPI.
- Keep Spring HTTP Interface, OpenFeign, MicroProfile REST Client, and Quarkus types in optional, adapter-specific
  providers. Gate each provider on its dependency and native framework capability so applications without that client
  technology do not load its classes.
- Inspect existing registrations, bean definitions, customizers, and framework metadata without instantiating lazy clients,
  replacing application-owned builders, or adding interceptors. Reuse REST Client trace's existing capture path for
  observed-call links.
- Treat client configuration as live policy input where supported. Route displayable URLs and settings through the
  exposure policy, always remove user-info and secret query values, and never serialize credentials, private keys, trust
  material, proxy passwords, or arbitrary customizer state.

Out of scope for the first release:

- Sending test requests, probing target availability, resolving DNS, or validating credentials.
- Capturing request or response bodies, adding a new HTTP interception path, or changing REST Client trace retention.
- Editing client configuration, retry policies, TLS settings, or proxy settings.
- Reconstructing clients created manually outside framework-managed interfaces and builders.
- Guessing effective settings that the framework or underlying client library does not expose safely.

Acceptance criteria:

- Opening the panel performs no network call and does not instantiate a lazy client or mutate an application-owned builder.
- Equivalent Spring servlet, Spring WebFlux, and Quarkus clients produce the same core response shape, with
  adapter-specific unavailable fields represented explicitly.
- Applications without a supported HTTP client technology load normally and show a framework-correct unavailable or empty
  state without optional classloading failures.
- Named clients and builder beans retain distinct identities, and value provenance correctly distinguishes defaults from
  client-specific overrides.
- Base URLs never expose user-info, credentials, secret query values, or TLS/proxy secrets under any exposure mode.
- REST Client trace links appear only for safely attributable calls; ambiguous and unmatched calls do not create false
  relationships.
- Sample applications and fixtures cover absent clients, multiple named clients, unresolved placeholders, masked URLs,
  inherited defaults, client-specific overrides, and ambiguous builder-derived clients without requiring external
  services.

### 3.7 Fault Tolerance — Services ✅ Completed

**Shipped.** The `fault-tolerance` panel is available on Spring MVC, Spring WebFlux, and Quarkus over a shared
`GET /bootui/api/fault-tolerance` contract and a framework-neutral `FaultToleranceService` fed by the
`FaultTolerancePolicyProvider` SPI. Spring contributes Resilience4j (all six registries, read live so lazily created
entries appear) and Spring Retry `@Retryable` metadata; Quarkus contributes SmallRye Fault Tolerance annotations
captured from the Jandex index at build time with MicroProfile Fault Tolerance configuration overrides resolved at
runtime. A bounded, metadata-only `FaultToleranceEventRecorder` feeds both the panel's event feed and Live Activity's
new `FAULT_TOLERANCE` entry type. Everything is capture-only: no policy is ever opened, closed, reset, or otherwise
mutated by BootUI. SmallRye publishes no per-call event stream, so on Quarkus only circuit-breaker state transitions
(for breakers carrying `@CircuitBreakerName`) are captured and per-policy counters are reported as absent rather than
invented.

BootUI exposes raw metrics that fault tolerance libraries may publish, but it does not explain which protections apply
to each operation, their current runtime state, or why a call was retried, rejected, or short-circuited. This panel
provides one cross-platform view over Resilience4j and Spring Retry on Spring, and SmallRye Fault Tolerance on
Quarkus.

Scope:

- Add one shared `fault-tolerance` panel and stable `/bootui/api/fault-tolerance/**` contract for Spring servlet, Spring
  WebFlux, and Quarkus.
- Discover configured circuit breakers, retries, rate limiters, bulkheads, and time limiters, including annotation-driven
  and registry-backed definitions where the library exposes them safely.
- Report the protected bean/class and method, policy type, effective configuration, configuration provenance, and current
  runtime state. Include bounded success, failure, retry, rejection, timeout, and short-circuit counts where native
  registries or metrics expose them.
- Show circuit-breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`, or an explicit adapter-specific/unknown state), retry limits
  and delay policy, rate-limit capacity and refresh policy, bulkhead concurrency/queue limits, and timeout thresholds.
- Capture bounded, metadata-only fault tolerance events and feed them into Live Activity as a `FAULT_TOLERANCE` entry
  type. Include policy name/type, protected operation, outcome, attempt number where applicable, duration, and safe
  failure category; never capture method arguments, return values, payloads, or raw exception messages.
- Correlate events to an originating request through the existing trace-id or safe adapter-specific correlation path when
  available. Background and asynchronously detached events remain top-level rather than being matched heuristically.
- Support multiple named registries and policies while returning the same stable DTOs and UI on every adapter.

Architecture:

- Put DTO assembly, normalization, ordering, bounds, state mapping, and event-to-Live-Activity mapping in JSON-free,
  framework-neutral engine services behind neutral fault tolerance metadata and event provider SPIs.
- Keep Resilience4j, Spring Retry, and SmallRye Fault Tolerance types in optional adapter providers. Gate each provider on
  its dependency, registry/bean presence, and native framework capability so absent libraries cannot cause classloading
  failures.
- Prefer native registries, event publishers, retry listeners, and metrics over wrapping application beans or replacing
  interceptors. Any listener registration must compose with application listeners, remain pass-through/fail-open, and be
  removed or disabled when capture is disabled.
- Treat policy configuration and panel enablement as live inputs where supported. Hash or omit high-cardinality operation
  identifiers when necessary, route displayable values through the exposure policy, and keep event buffers independently
  bounded so fault tolerance traffic cannot evict unrelated Live Activity sources.

Out of scope for the first release:

- Opening, closing, or resetting circuit breakers; changing retry, rate-limit, bulkhead, or timeout configuration.
- Invoking protected operations, generating synthetic failures, or probing downstream services.
- Capturing method arguments, return values, request/response bodies, message payloads, or raw exception messages.
- Reimplementing fault tolerance behavior or creating a BootUI abstraction that application code depends on.
- Inferring a policy or runtime state when a framework does not expose sufficient metadata.

Acceptance criteria:

- Opening the panel performs no protected call, network request, state transition, or policy mutation.
- Equivalent Resilience4j, Spring Retry, and SmallRye policies produce the same core response shape, with unsupported
  policy types or fields represented explicitly rather than guessed.
- Applications without a supported fault tolerance library load normally and show a framework-correct unavailable or empty
  state without optional classloading failures.
- Listener and event capture composes with application-owned listeners/interceptors, remains pass-through on BootUI
  failures, and stops cleanly when the panel or capture is disabled.
- Live Activity shows bounded metadata-only retry, rejection, timeout, short-circuit, and breaker-transition events, with
  request correlation only when supported by retained evidence.
- No method argument, return value, payload, credential, raw exception message, or unbounded operation identifier reaches
  the response or event buffer.
- Sample applications and fixtures cover absent libraries, multiple named policies, inherited and overridden
  configuration, every supported policy type, state transitions, exhausted retries, rate-limit rejection, bulkhead
  rejection, timeout, disabled capture, and unavailable adapter capabilities without external services.

### 3.8 gRPC — Services 📋 Planned

BootUI's Mappings and REST Client panels explain HTTP endpoints and calls, but gRPC services, methods, client channels,
transport security, and call outcomes remain invisible. This panel provides a read-only local registry and aggregate
runtime view for Spring gRPC and Quarkus gRPC without enabling reflection or recording individual calls.

Scope:

- Add one shared `grpc` panel and stable `/bootui/api/grpc/**` contract for Spring servlet, Spring WebFlux, and Quarkus
  when their supported gRPC integration is present.
- Discover registered server services and methods, including full service/method names, method type
  (`UNARY`, `CLIENT_STREAMING`, `SERVER_STREAMING`, or `BIDI_STREAMING`), implementation class, and interceptor chain
  where exposed safely.
- Report server transport configuration including listening address/port, plaintext or TLS state, reflection enablement,
  maximum message limits, keepalive settings, and other bounded framework-exposed values.
- Discover framework-managed outbound client channels and stubs, including registration name, target authority after safe
  normalization, load-balancing policy, plaintext or TLS state, retry enablement, message limits, and interceptors where
  the framework exposes them.
- Show aggregate per-service and per-method call counts, in-progress calls, latency, and gRPC status-code counts from
  existing native observations or metrics. Missing metric families remain explicitly unavailable rather than triggering a
  second instrumentation path.
- Support multiple servers and named client channels while returning the same stable DTOs and UI on every adapter.

Architecture:

- Put DTO assembly, method-type and status normalization, ordering, bounds, identity handling, and aggregate metric joins
  in a JSON-free, framework-neutral engine service behind neutral gRPC metadata and metrics provider SPIs.
- Keep Spring gRPC, `io.grpc`, Micrometer, Quarkus gRPC, and adapter metric types in optional providers. Gate providers on
  their dependencies, beans, and Quarkus capabilities so applications without gRPC cannot load optional classes.
- Read existing local registries, server definitions, managed-channel configuration, observations, and metrics. Do not
  enable server reflection, create channels or stubs, resolve DNS, register a second tracing interceptor, or record
  individual calls.
- Route targets, authorities, interceptor names, and transport settings through the exposure policy. Remove user-info and
  secret parameters, never serialize credentials or TLS key/trust material, and bound method, channel, interceptor, and
  metric cardinality before assembly.

Out of scope for the first release:

- Invoking RPCs, browsing remote reflection data, health-checking services, or testing client connectivity.
- Capturing request/response messages, protobuf payloads, metadata values, individual call histories, or Live Activity
  events.
- Editing channel, server, retry, load-balancing, keepalive, TLS, or reflection configuration.
- Generating clients, rendering arbitrary protobuf message editors, or acting as a gRPC debugging proxy.
- Adding custom call interception solely to synthesize metrics absent from the application's native instrumentation.

Acceptance criteria:

- Opening the panel creates no channel or stub, performs no DNS lookup or RPC, and does not enable reflection or add an
  interceptor.
- Equivalent Spring and Quarkus services, methods, and channels produce the same core response shape, with
  framework-specific unavailable fields represented explicitly.
- Applications without supported gRPC integration load normally and show a framework-correct unavailable state without
  optional classloading failures.
- Unary and all streaming method types are classified correctly, and multiple servers/channels retain distinct stable
  identities.
- Aggregate calls, latency, in-progress work, and status-code counts are joined only from existing native metrics; absent
  or ambiguous series do not create invented values.
- No protobuf payload, metadata value, credential, raw target secret, or TLS key/trust material reaches the response.
- Sample applications and fixtures cover absent gRPC support, unary and streaming services, multiple named channels,
  plaintext and TLS metadata, reflection on/off, metric presence/absence, status-code aggregates, malformed targets, and
  high-cardinality bounds without external services.

### 3.9 Spring Batch — Services 📋 Planned

BootUI shows scheduled task definitions and runs, but it does not expose Spring Batch jobs, executions, step progress, or
failure outcomes. Spring Batch already retains this operational history through `JobExplorer` and `JobRepository`, making
it available for a strictly read-only Spring panel without adding capture or controlling jobs.

Scope:

- Add one shared `batch` panel and stable `/bootui/api/batch/**` contract for Spring servlet and Spring WebFlux. Quarkus
  reports the panel honestly unavailable because it has no equivalent Spring Batch runtime.
- Discover registered job names and available job metadata, then list bounded, pageable job instances and executions
  newest-first.
- Report each execution's job name, instance and execution identifiers, start/create/end/update times, batch status, exit
  code, safely rendered exit description, and identifying/non-identifying job parameters with type and provenance.
- Show step executions with status, timing, read/write/filter/skip/commit/rollback counts, termination state, and bounded,
  safely rendered failure summaries.
- Provide server-side filtering by job name, status, execution identifier, and time range, plus drill-down from a job to
  its instances, executions, and steps.
- Treat running executions as live data and refresh their progress without creating a separate recorder or Live Activity
  event source.

Architecture:

- Put DTO assembly, paging, filtering, ordering, status normalization, bounds, and safe failure rendering in a JSON-free,
  framework-neutral engine service behind a neutral batch metadata provider SPI.
- Keep Spring Batch types in a classpath- and bean-gated Spring provider. Both servlet and WebFlux adapters use the same
  provider and controller contract; Quarkus wires only explicit unavailability metadata.
- Query `JobExplorer` for read-only history and use repository metadata only where necessary to explain configuration.
  Never call `JobLauncher`, `JobOperator`, `JobRepository` mutation methods, or application job beans.
- Route parameter names/values, exit descriptions, and failure details through the exposure and masking policy. Bound
  queries and response cardinality before loading step details so a large batch repository cannot exhaust the application.

Out of scope for the first release:

- Launching, restarting, stopping, abandoning, or deleting jobs or executions.
- Editing job parameters, repository state, execution context, or Spring Batch configuration.
- Capturing item payloads, execution-context values, reader/writer contents, or full exception stack traces.
- Adding a Batch event type to Live Activity or installing listeners around application jobs and steps.
- Providing a Quarkus-specific batch implementation without a comparable native runtime contract.

Acceptance criteria:

- Opening or refreshing the panel never launches, stops, restarts, abandons, or otherwise mutates a job execution.
- Spring servlet and Spring WebFlux return the same stable DTOs and paging behavior for equivalent Spring Batch metadata;
  Quarkus reports a clear not-applicable reason.
- Applications without Spring Batch or without a `JobExplorer` load normally and show a framework-correct unavailable
  state without optional classloading failures.
- Large job repositories remain bounded through server-side paging and filtering, and running execution progress refreshes
  without loading unrelated history.
- Job parameters, exit descriptions, and failure summaries respect masking and exposure policy; execution-context values,
  item payloads, and full stack traces never reach the response.
- Sample applications and fixtures cover absent Batch support, empty repositories, multiple jobs and instances, running,
  completed, stopped, and failed executions, step skip/rollback counts, masked parameters, long failure descriptions, and
  high-cardinality paging without external services.

### 3.10 WebSocket endpoints — Services ✅ Delivered

BootUI's Mappings panel explains request/response HTTP routes, but long-lived WebSocket endpoints, STOMP message mappings,
active sessions, subscriptions, and frame activity remain invisible. This panel provides a bounded, metadata-only view
over Spring WebSocket/STOMP and Quarkus WebSockets Next without capturing message payloads or feeding the general Live
Activity stream.

Scope:

- Add one shared `websockets` panel and stable `/bootui/api/websockets/**` contract for Spring servlet, Spring WebFlux,
  and Quarkus when their supported WebSocket integration is present.
- Discover Spring WebSocket handlers, STOMP endpoints, `@MessageMapping` destinations, broker prefixes, and application
  destination prefixes, plus Quarkus WebSockets Next endpoints, paths, callback methods, and supported message types.
- Report each endpoint's normalized path/destination, implementation class and method, direction/callback type, declared
  subprotocols, handshake policy metadata, and interceptor/filter names where exposed safely.
- Show active session counts by endpoint and bounded session metadata using opaque stable session identifiers, connection
  time, last-activity time, negotiated subprotocol, and safe local/remote transport metadata.
- Show bounded subscriptions for STOMP and equivalent framework-exposed channel/topic registrations, grouped by endpoint
  and destination without exposing user/session principals or arbitrary subscription headers.
- Capture recent metadata-only inbound and outbound frame activity: endpoint, opaque session id, direction, frame/message
  type, destination where applicable, payload size, timestamp, duration, and success/failure category. Never capture
  payload bytes/text or arbitrary headers.
- Keep recent frame activity in the WebSocket panel's independent bounded buffer; do not add a WebSocket event type to
  Live Activity in the first release.

Architecture:

- Put DTO assembly, endpoint normalization, ordering, bounds, session identity hashing, and activity aggregation in
  JSON-free, framework-neutral engine services behind neutral WebSocket metadata, session, and activity provider SPIs.
- Keep Spring WebSocket/STOMP and Quarkus WebSockets Next types in optional adapter providers. Gate each provider on its
  dependency, beans, and Quarkus capability so absent integrations cannot cause classloading failures.
- Prefer native endpoint registries, lifecycle hooks, channel interceptors, and connection callbacks. Capture must compose
  with application interceptors, preserve dispatch order and backpressure, remain pass-through/fail-open, and add no
  payload decoding or copying.
- Route endpoint paths, destinations, transport metadata, and failure categories through the exposure policy. Hash session
  identifiers, omit principals and arbitrary headers, and enforce independent limits for endpoints, sessions,
  subscriptions, and activity before serialization.

Out of scope for the first release:

- Sending frames, opening or closing sessions, subscribing/unsubscribing clients, or disconnecting users.
- Capturing payload text/bytes, application objects, arbitrary headers, authentication tokens, principals, cookies, or
  query-string values.
- Acting as a WebSocket client, broker, proxy, replay tool, or protocol debugger.
- Adding WebSocket activity to Live Activity or correlating individual frames to HTTP requests heuristically.
- Supporting third-party WebSocket stacks that bypass the framework-managed Spring or Quarkus integration.

Acceptance criteria:

- Opening the panel establishes no connection, sends no frame, changes no subscription, and does not close an application
  session.
- Equivalent Spring and Quarkus endpoints, sessions, and activity produce the same core response shape, with
  framework-specific unavailable fields represented explicitly.
- Applications without supported WebSocket integration load normally and show a framework-correct unavailable state
  without optional classloading failures.
- Capture composes with application interceptors/callbacks, preserves dispatch and backpressure behavior, remains
  pass-through on BootUI failures, and stops cleanly when disabled.
- Payloads, arbitrary headers, principals, credentials, cookies, raw session identifiers, and secret query values never
  enter the buffer or response.
- Endpoint, session, subscription, and activity cardinality is independently bounded with visible truncation, and WebSocket
  traffic cannot evict another panel's retained data.
- Sample applications and fixtures cover absent support, Spring STOMP, Spring native handlers, Quarkus WebSockets Next,
  multiple endpoints, active/closed sessions, subscriptions, inbound/outbound text and binary metadata, failures,
  disabled capture, and high-cardinality truncation without external services.

### 3.11 Error-contract catalogue — REST API and Exceptions ✅ Delivered

Delivered as a declaration-only catalogue on the existing REST API panel
(`GET /bootui/api/rest-api/error-contract`), a conservative Exceptions cross-link, and three evidence-based
REST API advisor rules (`RAPI-ERR-009`, `RAPI-ERR-010`, `RAPI-ERR-011`). Spring MVC, Spring WebFlux, and
Quarkus are all supported; Quarkus discovery is captured from the build-time Jandex index because no
runtime enumeration of resolved mappers exists.

BootUI's Exceptions panel shows failures that have occurred, while the REST API panel explains declared endpoints. Neither
shows which exception handlers define the application's error contract, which status and body shape each handler returns,
or whether endpoints have consistent and safe failure responses. This enhancement adds the catalogue to REST API and
cross-links retained failures from Exceptions rather than creating another panel.

Scope:

- Extend the existing REST API contract and UI with a stable, pageable error-contract catalogue for Spring servlet,
  Spring WebFlux, and Quarkus; keep the existing panel id, route, enablement, and read-only policy.
- Discover Spring `@ControllerAdvice`, `@RestControllerAdvice`, and `@ExceptionHandler` methods, plus Jakarta REST
  `@Provider` `ExceptionMapper` implementations on Quarkus.
- Report each handled exception type, declaring component and method, precedence/scope, resolved or declared HTTP status,
  produced media types, and safely inferred response-body category.
- Identify RFC 9457 `ProblemDetail`/problem-details usage and framework-native equivalents without instantiating handlers
  or executing application code.
- Resolve handler precedence and applicability where the framework exposes enough metadata. Ambiguous or dynamic mappings
  remain explicitly unresolved rather than being assigned a guessed handler.
- Cross-link an Exceptions entry to its declared handler and REST API error contract when exception type and retained
  request evidence allow safe attribution; unmatched and ambiguous failures remain unlinked.
- Add evidence-based REST API advisor findings for endpoint exception types with no declared mapping, inconsistent error
  body categories or media types across related controllers, and configurations that may expose stack traces or raw
  exception details.

Architecture:

- Put normalized DTO assembly, ordering, paging, handler precedence, body-category classification, cross-linking, and
  advisory policy in JSON-free, framework-neutral engine services behind a neutral error-contract provider SPI.
- Keep Spring MVC/WebFlux resolver types and Quarkus/Jakarta REST mapper types in thin adapter providers. Use native
  handler registries and metadata where available, with classpath and capability gates for optional integrations.
- Reuse the REST API panel and Exceptions data already retained by BootUI. Do not invoke handlers, synthesize requests,
  throw exceptions, or add another exception-capture path.
- Report only declaration metadata: component, method, exception, status, body category, and media types are Java type
  and constant names read from the application's own declarations, so they carry no property values and are shown
  verbatim, exactly as the Mappings and Beans panels already show type names. Retained failure details stay behind the
  Exceptions panel's existing masking and exposure policy; the catalogue adds a reference to a declaration and never a
  new value. Advisor findings must cite concrete configuration or declaration evidence and avoid claims based solely on
  the absence of observed failures.

Out of scope for the first release:

- Invoking exception handlers, generating failures, replaying requests, or validating response bodies over the network.
- Capturing additional exception payloads, response bodies, stack traces, or method arguments.
- Editing handler precedence, status mappings, serialization, stack-trace settings, or application error configuration.
- Full static control-flow analysis to prove every exception an endpoint may throw.
- Inferring dynamic handler behavior or arbitrary response schemas by executing application code.

Acceptance criteria:

- Opening the REST API error-contract view invokes no handler, sends no request, and does not alter exception resolution.
- Equivalent Spring servlet, Spring WebFlux, and Quarkus mappings produce the same core response shape, with dynamic or
  unsupported fields explicitly unresolved.
- Applications without advice or exception mappers show a clear empty state without optional classloading failures.
- Handler precedence and scope distinguish global and controller-specific mappings, including ambiguous mappings, without
  inventing certainty.
- Exceptions cross-links appear only when retained evidence safely identifies a declared handler; ambiguous and unmatched
  failures do not create false relationships.
- Advisor findings identify only evidence-backed unmapped exceptions, inconsistent contracts, or unsafe detail exposure,
  with clear remediation and no raw stack trace or sensitive response content in the report.
- Sample applications and fixtures cover global/local handlers, inheritance, precedence, multiple exception types,
  `ProblemDetail`, custom response bodies, Quarkus exception mappers, ambiguous/dynamic status, unmapped retained
  exceptions, inconsistent contracts, safe/unsafe stack-trace settings, and high-cardinality paging.

### 3.12 Slow-SQL ranking and URI attribution — SQL Trace ✅ Shipped

Shipped as `GET /bootui/api/sql-trace/insights` on Spring MVC, Spring WebFlux, and Quarkus, plus the two new SQL Trace
panel sections and the `DB-RUNTIME-001` Database advisor rule. Ranking, normalization, bounding, attribution, and
advisory policy live in the framework-neutral engine (`SqlStatementNormalizer`, `SqlStatementRanking`,
`RoutePathMasker`, `SqlRouteAttribution`, `SqlTraceInsightsService`); the adapters only supply inbound-request evidence
they already captured. Correlation is trace-id first, then serving thread on Spring MVC only, then time window, and each
tier requires a unique candidate — Spring WebFlux and Quarkus advertise `TRACE_ID` + `TIME_WINDOW` only, and Quarkus
matches the captured path against the application's declared `@Path` routes, because RESTEasy Reactive registers one
catch-all Vert.x route and so exposes no per-request template; only an unmatched path falls back to a masked path, and
`routeSource` reports which was used. Executions that cannot be placed
stay in explicit unattributed/ambiguous buckets. See `docs/SPECIFICATION.md` §5.17.6,
`docs/DATABASE-ADVISOR-CHECKS.md` (`DB-RUNTIME-001`), and the SQL Trace section of `docs/features/database.md`.


SQL Trace shows retained statements chronologically and already detects N+1 patterns, but it does not rank normalized
statements by cumulative cost or explain which inbound request routes are responsible for that database work. This
enhancement derives both views from BootUI's existing bounded SQL and HTTP evidence and adds a conservative Database
advisor rule for likely string-concatenated SQL.

Scope:

- Extend the existing SQL Trace contract and UI with top-N rankings by cumulative duration, maximum duration, execution
  count, average duration, error count, and available percentile estimates; keep the existing panel id, route,
  enablement, capture controls, and retention settings.
- Normalize statements using the existing SQL normalization and masking rules so equivalent parameterized executions
  aggregate without exposing bound values.
- Attribute a statement execution to an inbound request using the existing trace-id-first, then safe serving-thread and
  time-window correlation used by Transactions. WebFlux and Quarkus must use trace context where thread affinity is not
  reliable.
- Group attributed work by normalized route template when available, falling back to a masked method/path only when it is
  safe and unambiguous. Never group by raw query string or path parameter value.
- Show per-route database totals, top normalized statements, statement count, error count, and share of retained database
  time, with deep links between the route ranking and filtered SQL Trace entries.
- Keep unattributed and ambiguously attributed executions visible in explicit buckets rather than forcing a request
  relationship.
- Add an evidence-based Database advisor rule for statements that appear to embed dynamic literal values instead of bind
  parameters, with clear confidence and limitations so generated SQL and legitimate constants do not become categorical
  findings.

Architecture:

- Put ranking, aggregation, normalization, bounds, route attribution, and advisory policy in JSON-free,
  framework-neutral engine services over the existing SQL Trace and HTTP exchange DTOs/recorders.
- Reuse existing adapter trace-id providers and request-correlation evidence. Do not add JDBC wrappers, request
  interceptors, SQL parsing dependencies, or a second statement recorder.
- Compute rankings over the bounded retained window and state that window explicitly; results are diagnostic evidence, not
  lifetime database metrics.
- Route SQL, route templates, paths, and error metadata through the existing masking and exposure policy. Bound ranked
  groups and route/statement cross-products before serialization to prevent high-cardinality workloads from expanding the
  response.

Out of scope for the first release:

- Database-side execution plans, table statistics, index recommendations, or active query profiling.
- Capturing bind values, request query strings, path parameters, request/response bodies, or additional SQL text.
- Persisting rankings beyond the existing SQL Trace retention window.
- Claiming causal request attribution when trace/thread/time evidence is absent or ambiguous.
- Automatically rewriting SQL or treating the concatenated-SQL heuristic as proof of a vulnerability.

Acceptance criteria:

- The ranking uses only retained SQL Trace evidence and adds no database call, JDBC interception, or request capture.
- Equivalent Spring servlet, Spring WebFlux, and Quarkus evidence produces the same ranking and attribution DTOs, with
  adapter-specific unavailable correlation represented explicitly.
- Aggregate counts and durations reconcile with the retained statements in the selected window, including truncation and
  clear handling of ties.
- Route attribution uses trace context where available, refuses ambiguous thread/time matches, and never exposes query
  strings or path-parameter values.
- Normalization aggregates equivalent parameterized statements without merging materially different statements or
  exposing literals removed by masking policy.
- The concatenated-SQL advisor reports only evidence-backed candidates with confidence and limitations, and never exposes
  captured literal values in its evidence.
- Sample applications and fixtures cover repeated and one-off statements, ties, errors, N+1 overlap, trace-correlated and
  thread-correlated requests, WebFlux context shifts, ambiguous/unattributed work, route templates, masked paths,
  high-cardinality truncation, and likely/false-positive concatenated SQL patterns.

### 3.14 Correlation-ID filtering — Live Activity 📋 Planned

Live Activity correlates retained evidence through trace ids, serving threads, and time windows, but many applications also
use business or gateway correlation headers that developers recognize directly. This enhancement captures a bounded,
explicit set of correlation identifiers, makes them filterable across related activity, and permits copying only when
BootUI's value-exposure policy allows it.

Scope:

- Recognize `X-Correlation-ID`, `X-Request-ID`, and `X-Flow-ID` case-insensitively on inbound requests across Spring
  servlet, Spring WebFlux, and Quarkus.
- Allow additional header names through a bounded configuration property. Reject reserved sensitive names such as
  authorization, cookies, proxy credentials, and API-key headers even when configured.
- Capture at most a small fixed number of normalized, length-bounded identifiers per request and propagate their opaque
  lookup identities to child Live Activity entries already correlated with that request.
- Show identifier names and masked values in request details by default. Reveal and enable copy actions only when the live
  value-exposure policy permits the specific value.
- Add exact filter-by-ID input and per-identifier filter actions. Matching must work while values remain masked in the
  response, and must include the owning request plus its correlated child events without broad substring matching.
- Cross-link matching retained entries in Live Activity, HTTP Exchanges, and other panels that already preserve the same
  request/trace relationship; do not add new correlation guesses for unrelated events.

Architecture:

- Put header-name validation, normalization, bounds, opaque lookup identity generation, filtering, and child propagation
  in JSON-free, framework-neutral engine helpers shared by all adapters.
- Extract configured correlation headers at the existing inbound request capture points. Do not add another servlet
  filter, WebFlux filter, Quarkus route filter, or request-body read.
- Keep the bounded raw identifier only where required for exposure-controlled rendering and clipboard use; use a
  one-way-derived lookup identity for matching and child propagation so filtering does not require broadcasting raw values
  in every activity DTO.
- Apply the live exposure and masking policy at response assembly time. Never log identifiers, include them in error
  messages, use them as metric labels, or place them in URLs/query strings generated by BootUI.

Out of scope for the first release:

- Generating, replacing, or propagating correlation headers on behalf of the application.
- Reading identifiers from request/response bodies, cookies, authentication tokens, messaging payloads, or arbitrary
  unconfigured headers.
- Fuzzy, prefix, regular-expression, or case-insensitive value matching.
- Correlating background or messaging activity that does not already share retained request/trace evidence.
- Persisting identifiers beyond existing in-memory retention or exporting them to telemetry systems.

Acceptance criteria:

- The three built-in header names and valid configured names behave identically across Spring servlet, Spring WebFlux, and
  Quarkus, including case-insensitive header-name matching.
- Reserved sensitive header names, invalid names, overlong names/values, and values beyond the per-request cap are rejected
  or visibly truncated according to one canonical policy.
- Values are masked by default; reveal and copy remain unavailable until value exposure permits them, and policy changes
  take effect without restarting capture.
- Exact filtering works from a user-supplied raw value without exposing that value in unrelated response entries, logs,
  metric labels, or BootUI-generated URLs.
- A match returns the owning request and existing correlated children, while unrelated entries with similar or partial
  values remain excluded.
- Capture adds no request-body access, duplicate request filter, propagation behavior, or unbounded identifier
  cardinality.
- Tests cover built-in/custom names, mixed casing, multiple IDs, duplicate headers, reserved/invalid names, empty and
  overlong values, masking and live exposure changes, copy denial/success, exact/non-match filtering, child propagation,
  eviction, and equivalent behavior on all three adapters.

### 3.15 Meter provenance and explanation — Metrics ✅ Shipped

**Status: completed.** Shipped in the existing Metrics panel (see `docs/features/runtime.md` → *Metrics*): meters are grouped by
provenance, explanations are sourced from the registry first and a curated, versioned catalogue second, and
`GET /bootui/api/metrics` gained `group`, `provenance`, and `explanation` filters plus `groups` and `catalogueVersion`,
identically on Spring MVC, Spring WebFlux, and Quarkus.

The Metrics panel is close to a raw registry dump: it shows meter names, tags, and values without explaining which
integration contributed a family, what the measurements mean, or how related meters should be read together. This
enhancement groups meters by evidence-backed provenance and combines native descriptions with a curated BootUI catalogue
for common integrations.

Scope:

- Extend the existing Metrics contract and UI with provenance groups and concise meter-family explanations on Spring
  servlet, Spring WebFlux, and Quarkus; keep the existing panel id, route, enablement, and read-only policy.
- Group related meters into integration families such as JVM, process, system, HTTP server/client, datasource pools,
  caches, messaging, resilience, gRPC, and framework/runtime metrics when names and native metadata provide sufficient
  evidence.
- Show each group's contributor/integration, meter count, available description coverage, common tag keys, base units,
  and a short explanation of what the family measures and how to interpret its principal counters, gauges, timers, and
  distributions.
- Prefer the registry's native meter description and base unit for an individual meter. Use a curated, versioned BootUI
  catalogue to explain well-known meter families when native descriptions are absent or too narrow.
- Mark explanation source and confidence (`NATIVE`, `CURATED`, or `UNKNOWN`) and preserve unmatched/custom meters in an
  explicit **Application / unclassified** group rather than assigning guessed provenance.
- Add group, provenance, and explanation-availability filters while preserving existing meter-name and tag filtering.

Architecture:

- Put family matching, provenance classification, explanation lookup, ordering, and bounds in JSON-free,
  framework-neutral engine services over existing neutral meter metadata.
- Keep the curated catalogue as versioned project data keyed by stable meter-family patterns, with tests against supported
  Spring Boot, Micrometer, Quarkus, and common integration naming conventions.
- Use native descriptions and base units from existing registries; do not instantiate binders, register meters, scrape
  external endpoints, or infer contributors from current numeric values.
- Bound family-pattern evaluation and group cardinality, and route meter/tag names and descriptions through the existing
  exposure policy. Never include tag values in catalogue matching or explanations.

Out of scope for the first release:

- Changing meter registration, tags, histograms, percentiles, exporters, or observation configuration.
- Querying Prometheus, OTLP backends, or any external monitoring system.
- Generating alerts, health claims, SLOs, or recommendations from current metric values.
- Exhaustively documenting arbitrary application-defined meters or third-party naming conventions without stable evidence.
- Assigning provenance from tag values, stack traces, classpath presence alone, or speculative name similarity.

Acceptance criteria:

- Opening the enhanced Metrics view registers no meter or binder and performs no external scrape or network request.
- Equivalent meter metadata produces the same provenance and explanation DTOs across Spring servlet, Spring WebFlux, and
  Quarkus.
- Native descriptions take precedence and are visibly distinguished from curated explanations; unknown meters remain
  unclassified without invented documentation.
- Curated family matching is deterministic, versioned, and tested against naming collisions so application meters with
  similar prefixes are not silently misclassified.
- Group counts reconcile with the filtered meter set, every meter belongs to exactly one group, and high-cardinality
  registries remain bounded and pageable.
- Tag values do not influence provenance and no sensitive tag value is copied into an explanation.
- Fixtures cover native/curated/unknown descriptions, common integration families, naming collisions, custom meters,
  missing units, renamed/versioned families, filters, high cardinality, and equivalent adapter output.

### 3.16 Cache tiering and hit ratios — Cache ✅ Implemented

The Cache panel shows cache managers and aggregate topology, but a multi-level or composed cache can still appear as one
opaque manager and provider statistics are not explained consistently. This provider-agnostic enhancement exposes
framework-available tier structure and native per-cache effectiveness metrics without adding invalidation capture or
provider-specific promises.

**Shipped.** `CacheTierDto`/`CacheStatisticsDto` extend the core Cache contract, the engine
`CacheStatisticsAssembler` owns every ratio, sanitization, provenance and bounding rule, and the adapters return raw
metadata only through classloading-gated inspectors (`SpringCacheInspectors` for the JDK map, Caffeine, Redis and
no-op cases; `QuarkusCacheProvider` for `io.quarkus.cache.CaffeineCache`). Quarkus's public cache API exposes no
statistics accessor, so its tiers report counters as honestly unavailable — see `docs/QUARKUS-SUPPORT.md`.

Scope:

- Extend the existing Cache contract and UI with hierarchical manager/cache/tier metadata and native statistics on Spring
  servlet, Spring WebFlux, and any Quarkus cache integration that exposes equivalent metadata; keep the existing panel id,
  route, enablement, read-only policy, and existing clear-action policy.
- Report each cache manager's implementation type, wrapping/composition structure, declared caches, dynamic-cache state,
  and safely discoverable backing tiers.
- Represent each tier with a stable identity, implementation category, local/distributed classification when explicitly
  exposed, configured maximum size/expiry policy where available, and parent/child order.
- Show per-cache and per-tier native request, hit, miss, put, eviction, removal, load-success/failure, and size statistics
  where exposed, plus derived hit/miss ratios only when their source counters share compatible semantics and windows.
- Label every statistic with source, scope, and availability so application-lifetime provider counters are not confused
  with BootUI's bounded Cache activity recorder.
- Preserve opaque managers and caches in the report when their implementation does not expose tiers or statistics rather
  than using reflection heuristics to invent structure.

Architecture:

- Extend the framework-neutral Cache DTOs and engine assembly with bounded hierarchical tier and statistic records,
  deterministic ordering, safe ratio derivation, and explicit provenance.
- Extend existing cache adapter/provider SPIs to return only metadata and native statistics they can access through public,
  supported APIs. Do not import provider libraries into core/engine or hard-code first-release support for named vendors.
- Keep optional provider/framework types in gated adapter implementations and report unsupported capabilities honestly.
  Do not use deep reflection into internal cache implementations or trigger cache creation while discovering topology.
- Reuse existing masking, manager/cache enablement, and clear-action policy. Bound managers, caches, tiers, and statistic
  series before serialization and refresh live native counters without resetting them.

Out of scope for the first release:

- Capturing distributed invalidation events or adding another Cache/Live Activity event type.
- Reading, browsing, searching, exporting, warming, or mutating cache entries beyond the panel's existing clear action.
- Enabling provider statistics, resetting counters, changing expiry/size/tiering configuration, or forcing cache creation.
- Promising tier discovery or hit ratios for providers that do not expose compatible public metadata.
- Inferring local/distributed state, tier order, or counter semantics from implementation names alone.

Acceptance criteria:

- Opening or refreshing the panel creates no cache, loads no entry, enables/resets no statistic, and performs no network
  request.
- Equivalent exposed tier and statistic metadata produces the same core DTOs across adapters; unsupported providers or
  fields remain explicitly unavailable.
- Opaque and dynamically created cache managers render safely without deep reflection, classloading failures, or invented
  tier structure.
- Derived ratios are shown only for compatible counters with a known denominator and scope, including correct handling of
  zero requests, unavailable counters, and counter resets.
- Existing clear actions retain their current confirmation, enablement, and read-only behavior; this enhancement adds no
  new mutation.
- High-cardinality managers/caches/tiers/statistics are bounded with visible truncation and deterministic ordering.
- Fixtures cover single-tier, composed, opaque, dynamic, local/distributed-declared, statistics-present/absent, zero/reset
  counters, incompatible scopes, adapter unavailability, existing clear policy, and high-cardinality truncation.

### 3.17 MySQL operational view — Database ✅ Delivered

Delivered scope: one `mysql` panel beside PostgreSQL, backed by the framework-neutral engine and existing JDBC
datasource discovery. Spring MVC, WebFlux with JDBC, and Quarkus with JDBC share one report, policy, and agent
surface. Oracle MySQL 8.4 LTS is the tested server line, using 8.4.6 with Spring Connector/J 9.7.0 / HikariCP 7.0.2
and Quarkus Connector/J 9.6.0 / Agroal 3.0.1. No MariaDB, other-line, or compatible-server certification is implied.

- Covers all eight areas: vital signs, sessions/blocking, normalized statements, indexes, tables, InnoDB, basic local
  replication, and curated settings. Sections retain usable evidence with explicit denied/disabled/failed reasons,
  scope labels, unknown values, estimates, and row-cap limitations.
- `GET /bootui/api/mysql`, `get_mysql_report`, and `bootui db mysql report` read only the sanitized cache.
  `POST /bootui/api/mysql/read`, `mysql_read`, and `bootui db mysql read` explicitly collect through one shared
  single-flight admission. No SQL on page load, discovery, or cache invalidation after exposure-policy changes.
- Keeps collection read-only, bounded, and fail-closed, preserving application transactions and pooled connection state.
  Enforces global/panel read-only, localhost/Host/cross-site policy, and optional-dependency safety on all adapters.
- No scores or advisor recommendations, application rows, raw/sample/session SQL, lock key values, automatic
  instrumentation changes, maintenance, query plans, or topology/precise replication-lag claims.

The [feature contract](features/database.md#mysql) and [properties](PROPERTIES.md#mysql) specify defaults,
permissions, exact decimal-string counters, scope, and troubleshooting. The 15-second cooperative read,
5-second SELECT, 2-second metadata-lock, and 400-character text bounds are verified. The at-most-7-second JDBC
network guard separately covers control/SHOW I/O; pool acquisition remains application-controlled.

Verified source/runtime acceptance:

- [x] Pinned MySQL 8.4.6 live tests prove every collector, restricted grants/active roles, disabled instrumentation,
      timeouts, and safe pool cleanup; required scenarios execute rather than skip.
- [x] Available and unavailable MVC/WebFlux/Quarkus REST, MCP, and CLI contracts agree, including no-SQL cached reads,
      exposure invalidation, partial results, exact numeric strings, and policy/busy responses.
- [x] All seven Spring live suites, including the four MVC/WebFlux × default/custom-mount HTTP cases, pass.
      Quarkus custom-mount REST/MCP/CLI and Agroal physical connection eviction pass.
- [x] Global digest collection does not require thread instrumentation, and cap-only partial results do not suggest
      source failure; unit and genuine MySQL regressions verify both.
- [x] Generated CLI manifest, Vue unit/navigation checks, optional sample setups, and screenshot are integrated.
- [x] The Java 17 coverage reactor and focused final runtime regressions pass.

Final integration follow-through:

- [x] Complete the four full browser suites against the integrated change; all 80 MySQL browser cases pass
      across Spring MVC, Spring WebFlux, custom mounts, and Quarkus.
- [x] Rebuild documentation after final content synchronization.

Mocks and a green H2-only conformance run do not establish MySQL support. See
[live MySQL contributor validation](https://github.com/jdubois/boot-ui/blob/main/CONTRIBUTING.md#live-mysql-validation)
for the executable commands.
This feature does not replace or expand the MongoDB scope above.

### 3.18 Execution-context profiles — Live Activity 📋 Planned

The per-request profiler (`GET /bootui/api/activity/request/{id}`, `RequestProfileDto`) explains what one HTTP request
did, but work that starts anywhere else has no equivalent. A `SCHEDULED` entry is top-level, and only an unowned
exception nests under it, through a serving-thread and time-window join. Consumed Kafka, RabbitMQ, and JMS entries are
always top-level. The profiler itself still omits REST client calls, cache accesses, and scheduled runs, even though the
SQL, REST client, cache, and exception recorders already retain a thread and trace id per record.
`ScheduledTaskRunStore` retains the executing thread but no trace id, and consumed-message records retain neither a
thread, a start time, nor a trace id. This enhancement treats a scheduled execution and a consumed-message listener
invocation as execution contexts in their own right, with the same drill-down as a request and the same honesty about
how each link was established.

Scope:

- Treat `SCHEDULED` executions and consumed `MESSAGING` deliveries (Kafka and RabbitMQ on every adapter, JMS on Spring)
  as profileable anchors beside `REQUEST` entries, and set `profileable` on them.
- Add `GET /bootui/api/activity/execution/{id}` beside the request profiler. It returns the anchor summary plus
  correlated SQL, SQL groups with N+1 flags and call sites, exceptions, REST client calls, cache accesses, message
  sends, the distributed trace when one matched, a timing breakdown, and notes.
- Add REST client, cache, and nested scheduled-run evidence to the request profile and to **Copy profile**.
- Nest correlated children under a `SCHEDULED` or consumed `MESSAGING` anchor through the existing `parentId`. Work that
  cannot be placed precisely stays top-level.
- Record, at the existing capture points only, the trace id active during a scheduled execution, and the start time,
  executing thread, and active trace id of a consumed-message listener invocation, whenever the framework exposes them.
- Label every correlation with its tier (`TRACE_ID`, `SERVING_THREAD`, or `TIME_WINDOW`), and mark a profile approximate
  whenever a weaker tier was used.

Architecture:

- Put anchor selection, tiered correlation, child ordering, timing, N+1 reuse, and notes in one framework-neutral engine
  assembler that generalizes `RequestProfileAssembler`, instead of growing Spring's `LiveActivityCorrelator` separately.
  HTTP anchors keep today's request-profile policy unchanged.
- Correlate by trace id first on every adapter. A trace id attaches a child only when exactly one anchor of any type
  carries that trace and its window contains the child, extending `TraceCorrelationIndex`'s uniqueness guard across
  anchor types, because a request and the message or execution it triggers can share one trace. For blocking scheduled
  methods and listener invocations that run to completion on one thread, allow serving-thread correlation within the
  recorded window, under the unique-candidate rule SQL route attribution already uses. Allow time-window correlation
  only as a labelled last resort.
- Extend `ScheduledTaskRunStore.Run` and the Kafka, RabbitMQ, and JMS consumed-record shapes with nullable trace-id,
  thread, and start fields, supplied by `ScheduledTaskRunObservationHandler`, `QuarkusScheduledTaskRunRecorder`, and the
  existing consumer capture hooks. Add no interceptor, proxy, or executor wrapper.
- On Quarkus, a scheduled run is recorded from `SuccessfulExecution` and `FailedExecution` events with the trigger's
  fire time as its start, so its window is approximate. SmallRye Reactive Messaging interceptors are asynchronous, so
  consumed messages correlate by trace id only. Use thread and window tiers on Quarkus only where the recorder can prove
  them.
- A message send nests under its request or execution only when it was recorded with that anchor's trace id.
- Keep `GET /bootui/api/activity/request/{id}` and `RequestProfileDto` backward compatible: new request-profile sections
  are additive and nullable.
- Route every child through its source panel's masking, exposure, and self-filtering, and bound each child list with a
  visible truncation count.

Out of scope for the first release:

- `@Async`, `TaskExecutor`, `CompletableFuture`, `ApplicationRunner`, and `CommandLineRunner` capture. It needs executor
  instrumentation that composes with the application's own `TaskDecorator`, and is a candidate follow-up.
- Treating message sends, cache accesses, or REST client calls as anchors.
- Linking a consumed message to the request that produced it in another process, beyond an existing shared trace id.
- Capturing message payloads, message headers, or task arguments.
- Retaining profiles beyond existing buffer and optional activity-persistence retention.

Acceptance criteria:

- Opening an execution profile performs no capture, network call, or mutation.
- Equivalent evidence produces the same execution-profile DTO on Spring MVC, Spring WebFlux, and Quarkus, and a tier an
  adapter cannot provide is reported as unavailable rather than inferred.
- A child attaches to at most one anchor. Ambiguous trace, thread, or window matches stay top-level and are counted in
  the notes.
- A failed scheduled run still nests its exception exactly as it does today, now alongside the rest of its evidence.
- REST client, cache, and scheduled evidence appear, already masked, in request profiles, execution profiles, and
  **Copy profile**.
- Records without the new fields, and runs whose framework exposes no thread or trace id, degrade to top-level entries
  with a clear note.
- Fixtures cover scheduled runs with and without trace ids, overlapping runs of one task, concurrent listener
  invocations, a request and a consumed message sharing one trace, consumed and sent messages, Quarkus fire-time
  windows, masked children, truncation, and all three adapters.

### 3.19 Log correlation — Log Tail and Live Activity 📋 Planned

Log Tail captures log lines through `BootUiLogAppender`, a Logback appender, on Spring and through
`QuarkusLogTailHandler`, a root `java.util.logging` handler, on Quarkus, both into the shared `LogTailBuffer`. Each
`LogLineDto` carries only a timestamp, level, logger, message, and thread. A log line therefore cannot be tied to the
request or execution that wrote it, and a warning never appears in Live Activity next to the SQL and exceptions it
explains. Log messages are also not routed through the exposure policy today. This enhancement stamps log lines with
correlation evidence at capture time, applies the exposure policy to them, and surfaces warnings and errors as a Live
Activity signal.

Scope:

- Add a nullable `traceId` and a bounded `context` map to `LogLineDto`. On Spring the values come from the Logback
  event's MDC. On Quarkus they come from the active OpenTelemetry context and the log record's MDC when the log manager
  provides one.
- Copy context only for the trace and span ids and for MDC keys explicitly configured through
  `bootui.log-tail.context-keys`. Cap the key count and key and value lengths, and reject secret-looking key names even
  when configured.
- Never copy §3.14 correlation identifiers automatically. When §3.14 lands, a configured key that holds one is matched
  through §3.14's one-way lookup identity, and its raw value is shown only where §3.14's exposure rules allow it.
- Add server-side filters to `GET /bootui/api/log-tail/recent` for minimum level, logger prefix, trace id, and text. The
  SSE stream only gains the new fields.
- Add a `LOG` signal to Live Activity for `WARN` and above, with the threshold set by `bootui.activity.log-level`,
  bound on every adapter. Nest it under its request or execution by trace id, then by serving thread within the request
  window on Spring MVC.
- Include correlated log lines in request and execution profiles (§3.18), and link each Log Tail row that has a trace id
  to its profile and Traces entry.
- Apply the exception-message exposure rule to log messages and context values at read time, in Log Tail, Live Activity,
  profiles, and `get_log_tail`: `MASKED` scrubs secret-like assignments, `METADATA_ONLY` omits messages and context
  values, and `FULL` shows them verbatim.

Architecture:

- Keep the capture adapters thin. `BootUiLogAppender` and `QuarkusLogTailHandler` copy only allowlisted keys and a trace
  id into a neutral record, and the engine owns bounds, masking, filtering, and activity mapping.
- Link a log line that carries a throwable to the exception it produced through a per-event identity shared at capture
  time by the Log Tail and exception log handlers (`BootUiLogAppender` and `BootUiExceptionLogAppender` on Spring,
  `QuarkusLogTailHandler` and `QuarkusExceptionLogHandler` on Quarkus). The log record keeps the exception-group id the
  exception store assigned, so Live Activity shows the event once, as `EXCEPTION`, with the log line attached. Never
  de-duplicate by trace, thread, or time. With the Exceptions panel disabled, the line stays a `LOG` entry.
- Keep excluding BootUI's own loggers, and keep capture fail-open: a failure reading the MDC or trace context records
  the line without correlation.

Out of scope for the first release:

- Capturing arbitrary MDC keys, structured-logging arguments, or marker payloads.
- Reading log files, other appenders, or remote log stores.
- Log grouping, pattern mining, or alerting.
- Extracting correlation identifiers from requests, which §3.14 owns. This item only reads what the application already
  placed in the MDC.
- Log4j2 capture on Spring.

Acceptance criteria:

- A line logged on a request's thread carries that request's trace id and nests under it wherever tracing is active. On
  Spring MVC the serving-thread tier works without tracing.
- Keys outside the allowlist never reach the buffer, secret-looking key names are rejected even when configured, and no
  correlation identifier is copied without explicit configuration.
- Messages and context values honor `MASKED`, `METADATA_ONLY`, and `FULL`, and a live exposure change applies without a
  restart.
- `LOG` entries respect the configured threshold and the Live Activity cap, and disabling Log Tail removes the signal.
- An error logged with a throwable does not produce duplicate activity entries.
- Existing Log Tail clients keep working, because the new DTO fields are additive and nullable.
- Fixtures cover present and absent MDC, WebFlux context hops, Quarkus OpenTelemetry, every exposure mode, oversized
  values, allowlist rejection, de-duplication, filters, and all three adapters.

### 3.20 Route performance rankings — HTTP Exchanges 📋 Planned

SQL Trace ranks statements and database time by request route, but HTTP Exchanges is a flat list of recent requests,
and `HttpExchangesReport` carries no aggregates. Live Activity's KPI strip computes p50 and p95 latency and names the
single slowest retained request by its raw path, with no route context. This enhancement gives inbound traffic the same
summary → runs → profile structure: a per-route table over the retained window, a drill-down to that route's
exchanges, and a link from each exchange to its request profile.

Scope:

- Add a route summary to HTTP Exchanges: per method and route template, the request count; 2xx, 3xx, 4xx, and 5xx
  counts; average, p50, p95, p99, and maximum duration; and share of retained request time. Rank by count, p95,
  maximum, error count, or cumulative duration.
- Link each route row to the exchange list filtered to that route, and each exchange to its request profile.
- Resolve templates exactly as SQL route attribution does — framework template, then the application's declared
  mappings, then a masked path — and report which source was used.
- Label Live Activity's slowest-request KPI with its resolved route template, and link it to that route's summary row.
- State the evidence window inline: retained exchanges, buffer size, evictions, oldest retained exchange, and hidden
  BootUI exchanges.
- Add a read-only `get_http_routes` MCP tool and `bootui http routes` CLI command.

Architecture:

- Put grouping, ranking, percentiles, bounds, and window reporting in a framework-neutral engine service over existing
  exchange evidence. Reuse `RouteTemplateResolver` and `RoutePathMasker`, and extract the nearest-rank percentile logic
  that `SqlStatementAggregate` and `LiveActivityAssembler` each implement into one shared helper.
- On Spring, take the framework template from the existing `HttpExchangeTraceRegistry`, which `RequestCorrelationFilter`
  and `ReactiveHttpExchangeTraceFilter` already populate. On Quarkus, resolve it from declared JAX-RS mappings through
  `QuarkusMappingProvider`, as SQL Trace does.
- Add no request filter, and never group by query string or path-parameter value.

Out of scope for the first release:

- Lifetime or time-series metrics beyond the retained window. The Metrics panel already exposes Micrometer's
  `http.server.requests`.
- Latency targets, alerts, or health claims.
- Grouping by user, client, or remote address.

Acceptance criteria:

- Route counts and durations reconcile with the retained, visible exchanges in the window.
- Equivalent exchanges produce the same route summary on all three adapters, with the route source reported.
- Ambiguous declared mappings produce no template, and a masked path never exposes a path-parameter value.
- High-cardinality routes are bounded with a visible truncation count and deterministic tie ordering.
- BootUI's own exchanges stay out of the summary while `bootui.monitoring.exclude-self` is on.
- Fixtures cover templated and untemplated routes, ties, status classes, masked paths, eviction, self traffic, and all
  three adapters.

### 3.21 Scheduled task run history — Scheduled Tasks 📋 Planned

The Scheduled Tasks panel lists task definitions only (`ScheduledTaskDto`: runnable, trigger type, expression, initial
delay, and time unit). Every completed `@Scheduled` method execution is already retained in `ScheduledTaskRunStore` to
feed Live Activity, but the panel never shows that evidence, so "is this task failing, and how slow is it?" means
scanning the activity feed. This enhancement joins retained runs to their definitions.

Scope:

- Per task: retained run count, failure count, average, p95, and maximum duration, last run time and outcome, and the
  last failure's exception class and exposure-governed message.
- The next scheduled execution when the framework exposes it, and an explicit unavailable state when it does not.
- A per-task drill-down of recent runs, newest first, each linking to its execution profile (§3.18) or Live Activity
  entry.
- An explicit **Unmatched runs** group for runs whose identifier matches no listed definition, instead of dropping them.
- Filters for task name, outcome, and slow runs. The slow threshold is a new
  `bootui.activity.scheduled-task-slow-threshold-ms`, bound on every adapter beside
  `bootui.activity.max-scheduled-task-runs`. It also drives the `SLOW` severity of `SCHEDULED` entries, which today use
  the Spring MVC request threshold (1,000 ms by default) or a fixed 500 ms in the shared engine assembler. One
  documented default replaces both.
- The evidence window: retained runs, `bootui.activity.max-scheduled-task-runs`, and evictions.
- Extend `get_scheduled_tasks` and `bootui scheduled` with the run summary.

Architecture:

- Join in a framework-neutral engine service over `ScheduledTasksService` definitions and `ScheduledTaskRunStore` runs,
  keyed by the runnable identifier both already share. Reuse the shared percentile helper from §3.20.
- Keep the DTO change additive: each definition gains a nullable run summary, and the report gains the window and
  unmatched runs.
- Apply the exception-message exposure rule to failure messages.

Out of scope for the first release:

- Triggering, pausing, or rescheduling tasks.
- Retaining runs beyond the existing buffer and optional activity persistence.
- Observing manually registered `Runnable` or `Trigger` tasks that no hook captures today.
- Detecting missed or overlapping runs.

Acceptance criteria:

- Opening the panel never invokes a task, and summaries reconcile with the retained runs.
- Spring MVC, Spring WebFlux, and Quarkus return the same shapes for equivalent runs, with next execution null and a
  reason where the framework does not expose it, and classify slow runs with the same threshold.
- Failure messages honor the exposure policy.
- A task with no retained run reads **No runs in window** rather than showing zero-duration statistics.
- Fixtures cover successes, failures, slow runs, eviction, unmatched runs, overlapping runs, missing next-execution
  support, and all three adapters.

### 3.22 Failure-preserving retention and ignore rules — Diagnostics 📋 Planned

Every capture buffer evicts oldest first. That applies to HTTP Exchanges, which use Actuator's
`InMemoryHttpExchangeRepository` on Spring and BootUI's `HttpExchangeBuffer` on Quarkus, as well as to SQL Trace and
REST Client. On Spring, BootUI's own requests are hidden at read time but still occupy HTTP exchange slots; Quarkus
already drops them before recording. No setting excludes routine application traffic such as health probes or polling
endpoints, so a chatty local loop can evict, within seconds, the one failure a developer came to investigate. This
enhancement keeps failure evidence longer than routine evidence and lets developers drop noise at capture time.

Scope:

- Reserve a bounded, configurable share of each BootUI-owned HTTP exchange, SQL Trace, and REST Client buffer for failed
  or slow records: 5xx responses, failed statements, failed and error-response calls, and records over each panel's
  slow threshold. The reservation comes out of existing capacity, never extra memory.
- Evict routine records first. The reserved share evicts its own oldest record only when it is full.
- Report retained, reserved, and evicted counts per buffer, so no panel implies its window is complete.
- Add `bootui.monitoring.ignore-paths`, a list of path patterns whose requests are not captured as exchanges, Live
  Activity requests, route rankings, or route attribution. It is empty by default. A 5xx on an ignored path is still
  captured, and Exceptions capture is never affected.
- Define one pattern grammar for every adapter: path-only patterns anchored at `/`, matched case-sensitively against
  the decoded, normalized request path below the context path, never the query string, where `*` matches within one
  segment and `**` matches any number of segments. An invalid pattern is reported in the panel and matches nothing.
- On Spring, keep BootUI's own requests out of the application's slots while `bootui.monitoring.exclude-self` is on, as
  Quarkus already does.
- Apply these capture-time guarantees only where BootUI owns recording: SQL Trace, REST Client, Quarkus exchanges, and
  Spring exchanges recorded by BootUI's fallback repository and filter. When the application provides its own
  `HttpExchangeRepository` or `HttpExchangesFilter`, BootUI keeps its current back-off, reports retention as managed by
  the application, and applies ignore rules at read time only.

Architecture:

- Add one framework-neutral tiered buffer to the engine and reuse it for Quarkus exchanges, SQL Trace, and REST Client.
- On Spring, replace BootUI's `InMemoryHttpExchangeRepository` fallback with a BootUI-owned `HttpExchangeRepository`
  over the same buffer, which also drops ignored and self paths on `add`.
- Classify records at insertion from data already on them, and keep panels newest-first across both tiers.
- Own the pattern matcher in the engine beside the self-path check, and consult it at the existing capture points.

Out of scope for the first release:

- Sampling or other probabilistic capture. BootUI stays deterministic.
- Changing the retention of an application-provided `HttpExchangeRepository` or replacing its `HttpExchangesFilter`.
- Raising default memory budgets.
- Ignore rules for SQL statements or outbound hosts.

Acceptance criteria:

- Under a flood of successful requests, the most recent failed and slow records survive up to the reserved capacity in
  every BootUI-owned buffer on all three adapters.
- Retained records never exceed the configured capacity, and reported counts reconcile with buffer contents.
- Ignored paths produce no exchange, Live Activity request, or route-ranking entry, while a 5xx on an ignored path is
  still captured. The same pattern matches the same paths on every adapter, and an invalid pattern matches nothing.
- BootUI's own traffic no longer displaces application exchanges on Spring while `exclude-self` is on.
- An application-provided repository or filter is never replaced, its retention is labelled as application-managed,
  and ignore rules apply to it at read time.
- Tests cover classification, eviction order under mixed load, a capacity of one, valid and invalid patterns,
  self-filter interaction, and all three adapters.

### 3.23 Agent-ready profiles and exception export — Developer tools 📋 Planned

The MCP server and CLI expose `get_live_activity`, `get_exceptions`, and `get_exception_detail`, but no tool returns a
request profile. An agent can see that a request was slow, but not its SQL, N+1 groups, or call sites. In the browser,
**Copy profile** exports a plain-text timeline, while the Exceptions panel has no copy action. This enhancement adds the
missing tools and one consistent, already-masked Markdown export that a developer can paste into an agent.

Scope:

- Add a read-only `get_request_profile` MCP tool that takes an activity entry id and returns the same
  `RequestProfileDto` as `GET /bootui/api/activity/request/{id}`, exposed as `bootui activity profile <id>`.
- Once §3.18 lands, add `get_execution_profile`, exposed as `bootui activity execution <id>`.
- Add an additive `exceptionGroupId` to `RequestProfileExceptionDto`, so a profile can reach each exception's detail.
- Add **Copy for AI** to the Exceptions detail and the profiler drawer. It produces one Markdown document with the
  summary, exception type and exposure-governed message, the cause chain with application frames marked, recent
  occurrences with request context, correlated normalized SQL with N+1 call sites, and, once §3.24 lands, source
  excerpts.
- Render **Copy profile** through the same Markdown helper.
- Show the full document before copying, as **Copy as cURL** does, and list what was omitted, such as masked values or
  truncated sections. Preparing the preview loads the referenced exception details, and later source excerpts, through
  existing read endpoints. The copy itself sends nothing.

Architecture:

- Register the tools in `McpToolCatalog` under the `ACTIVITY` panel with `McpToolSchema.ID`, as read tools on every
  stack. Describe them in `McpToolDescriptions`, and regenerate `bootui-cli/src/main/resources/bootui-tools.json`. An
  unknown or evicted id returns the same unavailable profile, with its reason, that the REST endpoint returns.
- Build the Markdown in one shared frontend helper from DTOs the browser already holds or loads through existing read
  endpoints, so the export never contains anything the panels do not show, and identical DTOs produce identical text on
  every adapter. Fence code and SQL, and escape Markdown in captured strings.
- Document the investigation workflow — list activity, pick a profileable id, fetch its profile — in
  `docs/AI-AGENTS.md`, `docs/CLI.md`, and `skills/bootui/SKILL.md`.

Out of scope for the first release:

- Sending anything to an AI provider or other external service. Export is clipboard-only, and the tools are read-only.
- Issue assignment or comments beyond the existing exception triage status.
- AI-generated summaries inside BootUI.
- Markdown rendering in the CLI or on the server.

Acceptance criteria:

- `get_request_profile` returns the same masked DTO as the REST endpoint on all three adapters, including the
  unavailable profile for an unknown or evicted id, and is unavailable when Live Activity is disabled.
- The regenerated CLI manifest includes the new commands and passes `ToolManifestGeneratorTests`.
- Preparing a preview uses only existing read endpoints, and the copy itself sends no request and changes no state. The
  preview matches the clipboard exactly, and the text stays selectable when clipboard access is denied.
- The export never contains a value the panel masked, and honors `METADATA_ONLY`.
- Markdown in captured messages, paths, or SQL cannot break the document structure.
- Tests cover profiles with and without SQL, N+1 groups, truncated sections, unknown ids, masked values, cause chains,
  and identical output across adapters.

### 3.24 Source context for application frames — Exceptions 📋 Planned

Exception frames carry a class, method, file, and line, and `ExceptionStore` marks application frames using the detected
application packages. The developer still has to find each file by hand. BootUI runs beside the source it diagnoses, so
it can show a few lines around each application frame and open the file in the IDE. This enhancement adds both,
fail-closed.

Scope:

- For application frames only, show a short excerpt around the failing line, read from the local source tree, in the
  Exceptions detail, the profilers, and **Copy for AI**.
- Offer **Open in IDE** per application frame through a URL template chosen in the UI and remembered in the browser,
  with presets for IntelliJ IDEA and VS Code, and an option to hide links.
- Resolve a frame to a file only through its package path and file name under exact allowed source roots. Never search
  the disk.
- Allow the feature to be turned off with `bootui.exceptions.source-context.enabled`.

Architecture:

- Add a framework-neutral engine source locator behind an SPI that returns exact allowed source roots, not a project
  root. Spring derives them from exploded class-output directories — the application's own code source and, for other
  application frames, the class-file resource location looked up without loading the class — mapped from Maven and
  Gradle output layouts to their conventional `src/main/java` and `src/main/kotlin` directories, and confined under
  `ProjectSourceTree`'s project root. `bootui.exceptions.source-context.roots` adds explicit roots, under the same
  confinement, for layouts that cannot be inferred. Quarkus supplies source roots in dev mode where the adapter can
  determine them. Packaged jars, native images, and unresolved roots report the feature as unavailable with a reason.
- Confine resolution to normalized paths under the source roots, refuse symlinks that escape them, read only regular
  `.java` and `.kt` files under a size cap, and cache bounded excerpts.
- Serve excerpts from a separate read endpoint for one exception group, called only when the user opens that group or
  prepares an export, so rendering the list reads no files.
- Apply the exposure policy to excerpts. Pattern-based secret detection cannot recognize every credential in source, so
  `MASKED` lexes the whole file and replaces the contents of every string, character, and text-block literal and every
  comment with a placeholder, omitting the excerpt when the file cannot be lexed. `METADATA_ONLY` omits excerpts but
  keeps IDE links, and only `FULL` shows source verbatim.

Out of scope for the first release:

- Editing source, applying fixes, or writing files.
- Excerpts for dependency or JDK frames, decompiled classes, or source jars.
- Git blame, history, or ownership.
- Mapping sources for remote or containerized applications.

Acceptance criteria:

- No file is read to render the exception list.
- Escaping paths and symlinks, missing files, non-source files, and oversized files are refused with a per-frame reason.
- A packaged jar, native image, or unresolved project root disables excerpts with a reason while frames stay readable.
- Under `MASKED`, no literal or comment content reaches the browser; excerpts are omitted under `METADATA_ONLY`; and a
  live exposure change applies without a restart.
- Out-of-range line numbers and generated, lambda, or synthetic frames produce no excerpt rather than a wrong one.
- The IntelliJ IDEA and VS Code presets open the correct file and line.
- Tests cover Maven and Gradle layouts, Kotlin sources, multi-module projects launched from the reactor root, explicit
  roots, traversal attempts, and availability on all three adapters.

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
- Documentation updates in `docs/features/`, `docs/PROPERTIES.md`, `docs/SPECIFICATION.md`, and the relevant platform
  support document, plus screenshots at the project's standard size.

## 5. Risks

| Risk                                                              | Feature(s) | Impact | Mitigation                                                                                                    |
| ----------------------------------------------------------------- | ---------- | ------ | ------------------------------------------------------------------------------------------------------------- |
| Optional Actuator endpoints, libraries, beans, or servers missing | all        | Medium | Internal bridges, classpath/bean gating, stable empty DTOs, and clear unavailable reasons per panel.          |
| MongoDB inspection leaks credentials/documents or performs surprising network work | 3.5 | High | Never expose documents or raw connection strings; initial render is network-free; inspection is explicit, bounded, timed out, masked, and read-only. |
| MongoDB optional drivers break applications without the extension | 3.5 | High | Keep driver types in adapter-only providers and use Spring classpath gates plus Quarkus capability/exclusion build steps. |
| Large MongoDB catalog or partial permissions make inspection slow or misleading | 3.5 | Medium | Hard caps, paging, configurable timeouts, partial-result DTOs, and per-target permission errors. |
| Scope creep beyond the planned MongoDB inventory/advisor surface | 3.5 | High | Keep document browsing, arbitrary commands, writes, tracing, and migrations out of the first release. |
| Correlation over-claims which request or execution caused a record | 3.18, 3.19 | Medium | Tiered, labelled correlation with a unique-candidate rule; ambiguous work stays top-level. |
| Log messages, MDC values, exports, or source excerpts leak secrets | 3.19, 3.23, 3.24 | High | Explicitly configured MDC keys, read-time exposure policy, omission under `METADATA_ONLY`, literal and comment stripping for source under `MASKED`, and exports built only from masked DTOs. |
| New capture fields slow application hot paths | 3.18, 3.19, 3.22 | Medium | Copy only data already at hand at existing hooks, with bounded copies and fail-open capture. |
| Reserved retention hides recent routine traffic | 3.22 | Low | Reserve a bounded share of existing capacity and report retained, reserved, and evicted counts. |
| Source reads escape the project tree | 3.24 | High | Resolve only by package path under exact allowed source roots, refuse escaping paths and symlinks, and read only on open or export. |

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
- [ ] `docs/features/`, `docs/PROPERTIES.md`, `docs/SPECIFICATION.md`, and the relevant platform support document
      describe the new surface, with screenshots at the standard size.
- [ ] Spring Boot stays disabled in `prod`/`production` unless explicitly enabled; Quarkus remains production-dark in
      normal launch mode; and every adapter rejects non-local requests.
