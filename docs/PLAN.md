# BootUI Implementation Plan

## 1. Strategy

BootUI adds a safe, local-only developer console to a running application, shipping on **Spring Boot 4 (servlet and
WebFlux starters) and Quarkus (an extension)** from one shared, framework-neutral engine that serves the same Vue UI and
the same `/bootui/api/**` contract on every runtime. The released surface covers 57 panels across runtime introspection,
configuration, database migrations, services, diagnostics, project health, and developer tooling. The next planned panel
is a read-only **MongoDB** operational view, scoped in §3.5.

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

MongoDB is the next bounded feature workstream. BootUI already recognizes Spring Data MongoDB repositories in the
Spring Data panel, but it has no framework-neutral operational view of MongoDB clients, topology, databases,
collections, or indexes, and the existing JDBC/Flyway/Liquibase panels cannot represent those concepts. The new panel
will therefore be additive rather than an extension of the SQL-specific panels.

| Priority | Feature                  | Group    | Primary data source                    | Mutation? | Status  |
| -------- | ------------------------ | -------- | -------------------------------------- | --------- | ------- |
| Next     | MongoDB operational view | Database | Spring/Quarkus MongoDB client adapters | No        | Planned |
| Planned  | Declarative HTTP client registry | Services | Spring HTTP clients / Quarkus REST Client metadata | No | Planned |
| Planned  | gRPC | Services | Spring gRPC / Quarkus gRPC registries and metrics | No | Planned |
| Planned  | Spring Batch | Services | Spring Batch `JobExplorer` / `JobRepository` | No | Planned |
| Planned  | Correlation-ID filtering | Diagnostics | Existing request and Live Activity capture | No (capture only) | Planned |
| Delivered | Fault Tolerance | Services | Resilience4j / Spring Retry / SmallRye Fault Tolerance | No (capture only) | Delivered |
| Delivered | WebSocket endpoints | Services | Spring WebSocket/STOMP / Quarkus WebSockets Next | No (capture only) | Delivered |
| Delivered | Error-contract catalogue | Services | Spring exception handlers / Quarkus exception mappers | No | Delivered |
| Delivered | Slow-SQL ranking and URI attribution | Database | Existing SQL Trace and HTTP exchange evidence | No | Delivered |
| Delivered | Meter provenance and explanation | Diagnostics | Existing meter registry and curated catalogue | No | Delivered |
| Delivered | Cache tiering and hit ratios | Services | Existing cache managers and native statistics | No | Delivered |

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
groups by masked path because RESTEasy Reactive exposes no per-request route template. Executions that cannot be placed
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
