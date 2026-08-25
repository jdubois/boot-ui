# BootUI on Spring WebFlux — support status

::: tip Contributor document
This records how the WebFlux adapter was designed and built, for people working on BootUI itself. If you just want to
know what BootUI gives you on WebFlux, read [Framework support](./FRAMEWORK-SUPPORT.md) instead — and note that your
running console is always the authoritative answer for your own application.
:::

## 1. Goal

Spring WebFlux (reactive, Netty / `DispatcherHandler`) Spring Boot 4 applications are a first-class BootUI target,
alongside the Spring MVC (servlet) and Quarkus adapters. WebFlux reuses the same shared engine
(`bootui-engine` / `bootui-core`), the same Vue UI, and the same JSON contract (`/bootui/api/**` by default). Panels
reach parity wherever a reactive analog genuinely exists, and report an honest *not yet ported* or *not applicable*
status where they don't.

## 2. Current status

The WebFlux adapter serves the large majority of the panel surface — the same 53-panel manifest the servlet adapter
reports, minus the one panel (**HTTP Sessions**, §6.7) that stays unavailable for stack reasons. Every available
action-capable panel behaves identically to the servlet adapter, behind the same shared `LocalhostGuard` write floor.

::: details Action-capable panels (identical to servlet)

| Panel                | Actions                              |
| -------------------- | ------------------------------------ |
| Loggers              | set level                            |
| HTTP Probe           | probe                                |
| Cache                | clear                                |
| Hibernate Statistics | runtime enable                       |
| Flyway               | migrate / clean                      |
| Liquibase            | update                               |
| Heap Dump            | capture / analyze / delete / download |
| Threads              | download                             |
| Traces               | clear                                |
| SQL Trace            | toggle recording / clear             |
| Transactions         | clear / toggle recording             |
| REST Client          | clear / toggle recording             |
| Exceptions           | triage                               |
| Advisor scans        | Architecture, Spring, Hibernate, Pentesting, REST API, Security, Memory, Vulnerabilities/OSV |

:::

The adapter shares the exact per-scanner single-flight contract with MVC and Quarkus. Overlapping expensive advisor
actions fail fast with canonical JSON `409` rather than queueing on Netty or repeating work; Heap Dump
capture/analyze/delete share one admission. Passive reads keep serving the last completed report, and MCP converts the
same conflict to an in-band tool error. `WebFluxApiConformanceTest` runs the shared concurrent Architecture burst that
pins this transport behavior.

Only **HTTP Sessions** stays unavailable, with a panel-specific reason surfaced through `/bootui/api/panels` — and, in
turn, the sidebar tooltip and the panel's own alert banner (§6.7). `docs/features/` and the per-panel
`unavailableReason` strings in `PanelsController` are the authoritative, current detail.

## 3. Why this is feasible — evidence from the current codebase

- `bootui-core` / `bootui-engine` / `bootui-ui` were already 100% framework-neutral before this work started. They
  needed **zero changes** beyond adding one new platform constant (`PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE`). Every
  advisor engine, DTO, and Vue view is reused byte-for-byte from the servlet adapter — the same reuse story the Quarkus
  adapter proved out.
- Most of the servlet adapter's `@RestController`s use the shared `org.springframework.web.bind.annotation` model,
  return plain DTO records, and never reference `HttpServletRequest`/`HttpServletResponse` directly. `DispatcherHandler`
  (WebFlux's dispatcher) invokes them exactly as `DispatcherServlet` does, unmodified.
- The handful of panels that needed new code all needed it for one reason: a servlet-only streaming or eventing
  primitive (`SseEmitter`, `HandlerExceptionResolver`, `ServletRequestHandledEvent`) with no reactive equivalent wired.
  Each has a reactive analog in Spring itself (`Flux<ServerSentEvent<T>>`, `WebExceptionHandler`). The engine services
  underneath (`SqlTraceRecorder`, `ExceptionStore`, `LogTailBuffer`, `AgentSessionStore`, …) needed no changes at all.

## 4. Module topology

```text
bootui-core / bootui-engine / bootui-ui        Unchanged — reused by all three adapters
bootui-spring-autoconfigure                    Shared Spring module: servlet AND reactive bindings both live here
  ...autoconfigure.web                         Servlet @RestControllers (framework-neutral; reused unmodified by both)
  ...autoconfigure.reactive                    Reactive-only bindings: WebFilters, the two new @Configuration classes,
                                                and the handful of Reactive* controllers that needed a genuine rewrite
bootui-spring-boot-starter                     Drop-in servlet starter (Tomcat + MVC) — unchanged
bootui-spring-boot-starter-reactive            New drop-in reactive starter (Netty + WebFlux), this effort's Phase 0
bootui-spring-webflux-sample-app               New reference WebFlux app for demos + conformance + e2e (Phase 5)
```

`BootUiAutoConfiguration` (servlet) and `BootUiReactiveAutoConfiguration` (reactive) are two separate autoconfigurations
in the same `bootui-spring-autoconfigure` module — not two modules — because nearly all of the underlying
`@RestController`s, safety decisions, and engine wiring are identical. Only the request/response binding and the
streaming primitive genuinely differ per stack. Spring Boot's `WebApplicationType` deduction makes the two mutually
exclusive: a running application is either `SERVLET` or `REACTIVE`, never both, so exactly one activates.

## 5. Activation & safety on WebFlux

Every safety rule mirrors the servlet adapter over a reactive binding; only request/response plumbing differs.

- **Same activation rule, same fail-closed default.** `BootUiReactiveAutoConfiguration` is gated by the identical
  `BootUiActivationCondition` the servlet adapter uses (`bootui.enabled=ON|OFF`, `bootui.enabled-profiles` /
  `bootui.disabled-profiles`, `spring-boot-devtools` on the classpath), plus `@ConditionalOnWebApplication(REACTIVE)`
  and `@ConditionalOnClass(DispatcherHandler)`. There is no separate "reactive mode" flag — BootUI detects which stack
  Spring Boot picked and binds accordingly.
- **Same dark shell when inactive.** `WebFluxAutoConfiguration` serves `classpath:/META-INF/resources/` by default, so
  the packaged Vue bundle would stay reachable at `/bootui/**` even with every BootUI bean unwired.
  `BootUiShellGuardAutoConfiguration` registers `ReactiveBootUiShellGuardFilter` under the exact negation of the
  activation condition, answering `404` for the reserved namespace; the servlet adapter gets identical treatment from
  the same auto-configuration. The reactive filter matches on `PathContainer.PathSegment.valueToMatch()` (the decoded
  value `PathPattern` matches) and covers the prefix `spring.webflux.static-path-pattern` introduces, mirroring the
  servlet guard's handling of `spring.mvc.servlet.path` and `spring.mvc.static-path-pattern`. Both filters share the
  framework-neutral `BootUiInternalMount` predicate rather than calling into each other, because a WebFlux-only
  application has no servlet API on its classpath at all.
- **Same `LocalhostGuard`, ported to `WebFilter`.** `ReactiveLocalhostOnlyFilter` is a thin `WebFilter` binding over the
  same framework-neutral `io.github.jdubois.bootui.engine.safety.LocalhostGuard` the servlet `LocalhostOnlyFilter` uses
  — same loopback-source trust, `Host` allow-list, cross-site-write/CSRF defense, same canonical `{"error":"…"}` 403
  JSON body. Only the plumbing (`ServerWebExchange` instead of `HttpServletRequest`/`HttpServletResponse`) differs.
- **Same per-panel gating.** `ReactivePanelAccessFilter` enforces `bootui.panels.*` (enable/read-only) via the same
  `BootUiPanels` registry the servlet `PanelAccessFilter` uses — same config keys, same canonical JSON 403 body.
- **Same configurable path contract.** `bootui.path` moves the shell, assets, APIs, streams, downloads, and action
  endpoints together; `bootui.api-path` can override the derived `<bootui.path>/api` mount independently. Both compose
  with `spring.webflux.base-path` exactly once. A dedicated WebFlux static-resource handler serves the configured mount,
  while an earlier blocker prevents the packaged `/bootui/**` resources from leaking as a legacy alias. The generated
  shell injects the browser-visible UI/API paths for the shared SPA, and the authentication cookie is scoped to the
  composed API path.
- **Same platform-aware manifest mechanism the Quarkus adapter established.** `PanelsController` — one shared bean
  bulk-imported unmodified by both autoconfigurations — detects the running context type
  (`applicationContext instanceof ReactiveWebApplicationContext`) and reports `platform: "spring-boot-reactive"` in
  `/bootui/api/panels`, plus per-panel `available`/`unavailableReason` for the panels that diverge (§6). The Vue UI reads
  this manifest via `inject('panels')` and renders `unavailableReason` in both the sidebar tooltip and the panel's own
  alert banner — no `.vue` file changed.

::: details One blocking-execution boundary for the shared controller surface

`ReactiveBootUiHandlerAdapter` delegates BootUI controller dispatch to WebFlux's fully configured request-mapping
adapter on Reactor's bounded-elastic scheduler. This keeps argument resolution, blocking network calls,
advisor/classpath scans, heap and JVM diagnostics, downloads, and filesystem handlers off Reactor Netty event-loop
threads without duplicating scheduler code across the controllers shared with Spring MVC. Selection follows the existing
class-level `${bootui.api-path:...}` mapping convention rather than an endpoint allowlist, so custom API mounts and newly
shared controllers inherit it automatically. The shell, static assets, host-application controllers, the host's own
WebFlux blocking-execution policy, and requests rejected by the preceding safety filters remain untouched.

:::

## 6. Panel disposition

| Tier             | Meaning                                                                          |
| ---------------- | -------------------------------------------------------------------------------- |
| `Port`           | Ships from shared code, zero adapter changes needed                              |
| `Adapt`          | Reuses the shared engine over a small new reactive binding (e.g. a `WebFilter`)  |
| `Rebuild`        | A genuinely new reactive capture layer replacing a servlet-only primitive        |
| `Not yet ported` | Deliberately deferred, no reactive implementation wired yet                      |
| `Not applicable` | No faithful reactive analog exists for this panel's concept                      |

### 6.1 Ported as-is (42 panels)

Bulk-imported from the servlet adapter's `@RestController`s with no code changes at all — confirming these controllers
were already framework-neutral in practice, not just in the engine underneath them.

| Panels ported unchanged |
| ----------------------- |
| Overview, GitHub, Beans, Conditions, Configuration, Mappings, Health, Loggers, Startup Timeline, Spring Data |
| Database, Hibernate, Hibernate Statistics, Flyway, Liquibase, Database Connection Pools, Cache, Dev Services |
| Vulnerabilities, Scheduled Tasks, Fault Tolerance, HTTP Probe, Pentesting, Heap Dump, Architecture, REST API advisor |
| Profile Diff, Spring advisor[^spring-advisor-reactive], Live Memory, JVM Tuning, Metrics, DevTools, Traces, AI Framework |
| GraalVM, CRaC, Threads, Memory, Email, Kafka, RabbitMQ, JMS |

These controllers keep their synchronous servlet-facing signatures. On WebFlux, the centralized
`ReactiveBootUiHandlerAdapter` dispatches their argument resolution and handler invocation on bounded-elastic threads,
so controller-local scheduler annotations or endpoint allowlists are not required and new shared handlers cannot
accidentally inherit the Reactor Netty event loop.

::: details Messaging panels (Kafka, RabbitMQ, JMS)

`KafkaController`, `RabbitController`, and `JmsController`, plus their template/listener-factory `BeanPostProcessor`
capture pairs, carry no `ConditionalOnWebApplication` or reactive-specific code. The panels and their Live Activity
`MESSAGING` capture therefore work identically to the servlet adapter with zero adapter changes. JMS remains an
imperative, blocking broker API, but its work runs on the application's JMS template/listener threads; the WebFlux panel
only reads the shared in-memory recorder.

:::

::: details Fault Tolerance

`FaultToleranceController` is framework-neutral: Resilience4j registries and Spring Retry `@Retryable` metadata are
plain beans with no servlet or reactive coupling. Resilience4j's event publishers plus the additive `BootUiRetryListener`
bean feed the shared `FaultToleranceEventRecorder` from whatever thread the protected call runs on, so the panel and its
Live Activity `FAULT_TOLERANCE` entries work with zero adapter-specific code. Resilience4j's reactive operators
(`ReactorCircuitBreaker` and friends) publish through the same registries and event publishers as the imperative ones,
so reactive pipelines use the same read path. The reactive sample application declares Resilience4j — and deliberately
*not* Spring Retry, so the two Spring samples between them cover both halves of the backend's optional-dependency guards
— and exercises its policies from a `boundedElastic` scheduler thread, which `WebFluxFaultToleranceIntegrationTest`
asserts reaches the panel report.

:::

::: details Cache and CRaC fidelity

The Cache panel's tier and native-statistics reporting is identical on both Spring adapters: it is produced by the
shared `SpringCacheProvider` and its classloading-gated inspectors, which depend only on Spring's cache abstraction and
the cache provider's own public API, never on a servlet or reactive type. A WebFlux application configured with
`spring.cache.type=simple` shows the same single local in-memory map tier — and the same honest "a plain map records
nothing" statistics state — the servlet adapter shows for that configuration.

CRaC uses the same framework-neutral scanner and Spring runtime inventory on MVC and WebFlux. Its pool inventory
includes R2DBC factories, and its task/scheduling checks use Spring context APIs rather than servlet types. Generated
assets still target a JVM process and Spring's checkpoint lifecycle; they do not imply Quarkus or native-image support.

:::

[^spring-advisor-reactive]: The `SpringController` wiring needed no adapter change, but its ruleset
    (`SpringScanner`/`SpringRules`) is reactive-aware internally. It detects a WebFlux `ReactiveWebApplicationContext`
    the same way `PanelsController.isReactive()` does. It checks the active embedded server before evaluating
    `SPRING-WEB-007` (the Tomcat thread cap, including reactive Tomcat), and matches `WebClient` beans for the
    HTTP-client-timeout rule (`SPRING-WEB-005`). Four rules' "Learn more" links point at the reactive docs page instead
    of the servlet one, and two WebFlux-only rules (`SPRING-REACTIVE-001`, `SPRING-REACTIVE-002`) that are otherwise
    `SKIPPED` are added. See `docs/SPRING-CHECKS.md`.

### 6.2 Adapted with a small new binding (1 panel)

| Panel          | Reactive binding                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------------------- |
| HTTP Exchanges | `ReactiveHttpExchangeRepositoryConfiguration` supplies Actuator's reactive `HttpExchangeRepository` bean instead of the servlet one — same DTO, same UI, same capture semantics |

::: details REST API declared error contract needs no reactive binding

`@ControllerAdvice`, `@ExceptionHandler`, and `@ResponseStatus` all live in `spring-web`, so one
`SpringErrorContractProvider` serves both stacks by reading bean metadata. The only reactive-specific behavior is in the
provider's return-type analysis, which unwraps `Mono`, `Flux`, `CompletionStage`, `CompletableFuture`, `Callable`,
`DeferredResult`, and `WebAsyncTask` — matched by class name, so nothing links Reactor — before classifying the declared
response body. A reactive handler returning `Mono<ResponseEntity<ErrorBody>>` reports the same body category and the
same runtime-built status as its servlet equivalent, and WebFlux's functional `ServerResponse` is treated like
`ResponseEntity`: a runtime-built status with a runtime-decided body. Neither stack instantiates or invokes a handler —
discovery reads bean *types* without creating them, so a `FactoryBean` declaring advice is never forced into existence
just to build the catalogue.

:::

### 6.3 Rebuilt with a new reactive capture layer (9 panels)

The DTO and UI are reused unchanged; only the capture/streaming source was rewritten because the servlet original
depended on `SseEmitter` (SQL Trace, Log Tail, Security Logs, Exceptions, REST Client, Transactions) or
`HandlerExceptionResolver` (Exceptions).

| Panel         | Reactive source                                                                                                          |
| ------------- | ------------------------------------------------------------------------------------------------------------------------ |
| SQL Trace     | `ReactiveSqlTraceController` over the new shared `ReactiveBootUiChangeStream` SSE primitive (`Flux<ServerSentEvent<T>>`), feeding the same `SqlTraceRecorder`. See the fidelity note below. |
| Transactions  | `ReactiveTransactionsController` over `ReactiveBootUiChangeStream`, feeding the same `TransactionRecorder`. See the fidelity note below. |
| Log Tail      | `ReactiveLogTailController` — same `LogTailBuffer`/Logback appender, SSE via `ReactiveBootUiChangeStream`.                |
| Security Logs | `ReactiveSecurityLogsController` over a fallback `InMemoryAuditEventRepository` (Spring's audit-event bus is framework-neutral, so no reactive-specific capture code was needed). |
| Exceptions    | `ReactiveExceptionsController` + new `ReactiveBootUiExceptionHandler` (a `WebExceptionHandler` at `HIGHEST_PRECEDENCE`, replacing the servlet `HandlerExceptionResolver`); see the fidelity note below. |
| Copilot       | `ReactiveCopilotController` over the same `AgentSessionStore`, SSE via `ReactiveBootUiChangeStream`.                      |
| Claude Code   | `ReactiveClaudeCodeController` over the same `AgentSessionStore`, SSE via `ReactiveBootUiChangeStream`.                   |
| REST Client   | `ReactiveRestClientTraceController` — same `RestClientTraceRecorder`, SSE via `ReactiveBootUiChangeStream`. See the fidelity note below. |
| WebSockets    | `ReactiveWebSocketController` + `ReactiveWebSocketMetadataProvider` — same `WebSocketService`, SSE via `ReactiveBootUiChangeStream`. See the fidelity note below. |

`ReactiveBootUiChangeStream` is a small shared `Sinks.Many`-backed SSE broadcaster (`open()` / `signal()` / `close()`)
used by every "push an update when something changes" panel above, instead of each controller hand-rolling its own sink.
It centralizes coalescing, back-pressure, and a concurrency limit once rather than per-panel.

::: details Per-panel fidelity notes (SQL Trace, Transactions, Exceptions, REST Client, WebSockets)

**SQL Trace.** Statement rankings and request-route attribution (`GET /bootui/api/sql-trace/insights`) are served from
the framework-neutral `SqlTraceInsightsService`; request evidence comes from the reactive `HttpExchangeTraceRegistry`,
which also captures the best-matching reactive route pattern so routes group by template rather than by masked path.
*Fidelity gap, accepted:* a reactive request is not pinned to one thread, so serving-thread correlation is deliberately
not offered — the report advertises `TRACE_ID` and `TIME_WINDOW` only, and work it cannot place lands in the explicit
unattributed/ambiguous buckets rather than being guessed onto a route. The `HttpExchangeTraceRegistry` ships with the
OpenTelemetry correlation configuration, so on a WebFlux application without an OpenTelemetry starter, route attribution
reports itself *unavailable* — naming that requirement — instead of advertising correlation tiers over an empty
candidate list. Statement rankings are unaffected.

**Transactions.** Capture is identical to the servlet adapter: BootUI contributes a `TransactionExecutionListener`
through Spring Boot's standard transaction-manager customization and completes registration for user-defined
`ConfigurableTransactionManager` beans after singleton initialization, observing any configurable blocking transaction
manager a WebFlux application still uses (e.g. JDBC repositories behind a thread-blocking data access layer). *Fidelity
gap, accepted:* a WebFlux application backed only by a `ReactiveTransactionManager` (R2DBC) has no
`ConfigurableTransactionManager` bean to observe — Spring's transaction-execution listener hook exists solely on the
blocking SPI — so the panel reports "No configurable PlatformTransactionManager bean is available" rather than silently
showing an empty table.

**Exceptions.** *Known fidelity gap, accepted, documented in code (`ReactiveBootUiExceptionHandler`'s Javadoc):* a
`@RestController`'s own local `@ExceptionHandler` method consumes an exception *inside* the WebFlux dispatch pipeline,
before any `WebExceptionHandler` sees it. This is narrower capture than the servlet adapter's resolver-chain approach,
where `BootUiExceptionHandlerResolver` runs at `HIGHEST_PRECEDENCE` in the same chain `@ExceptionHandler` resolution
uses. An application-level `@ExceptionHandler` will suppress BootUI's capture on WebFlux where it would not on servlet.
Unhandled exceptions (the common case) are captured identically on both stacks.

**REST Client.** Availability is gated on `RestClientTraceRecorder#hasInstrumentedClient()` (a `WebClient.Builder`
auto-configured with the BootUI customizer must have been built). The `RestClient`/`RestTemplate` interceptors are not
linked on a WebFlux-only classpath (their `@ConditionalOnClass` gate requires `spring-boot-restclient`), so the recorder
only sees `WebClient` customization on this stack — the correct signal.

**WebSockets.** Endpoint topology is read from the reactive `SimpleUrlHandlerMapping` beans that map
`org.springframework.web.reactive.socket.WebSocketHandler`s. *Fidelity gap, accepted and reported honestly:*
`@EnableWebSocketMessageBroker` and its `WebSocketHandlerDecoratorFactory`/`ChannelInterceptor` seams are servlet-only,
so the reactive report sets `frameCaptureSupported=false` with that reason and the panel shows endpoints only — it never
fabricates a frame log or an empty capture buffer. There is likewise no reactive session registry, so
`sessionTrackingSupported=false` and the Sessions table reads *not supported on this stack* rather than implying no
client is connected.

:::

### 6.4 Rebuilt as a merge over already-reactive signals (1 panel)

Live Activity needed no new *capture* pipeline for any of its **nine** merged signal types — they were all already
captured reactively or by framework-neutral engine buffers.

| Panel         | Reactive source                                                                                                          |
| ------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Live Activity | `ReactiveLiveActivityController`, merging `HttpExchangesController` (requests), `SqlTraceRecorder` (SQL), `ExceptionStore` (exceptions), `ReactiveSecurityLogsController` (security), `CacheActivityRecorder` (cache), `ScheduledTaskRunStore` (scheduled tasks), `KafkaActivityRecorder`/`RabbitActivityRecorder`/`JmsActivityRecorder` (messaging), `EmailCaptureService`/`EmailController` (mail), and `RestClientTraceRecorder` (REST/WebClient calls) via the shared engine `LiveActivityAssembler`/`RequestProfileAssembler` — the same classes the Quarkus adapter validated first; refreshed over `ReactiveBootUiChangeStream`, signaled by a new lightweight `ReactiveActivitySignalFilter` `WebFilter` after each non-BootUI request completes. |

::: details Where each of the nine signals comes from

The original four — HTTP Exchanges, SQL Trace, Exceptions, and Security Logs — are already captured reactively by the
panels in §6.2/§6.3. The five newer entry types added by the Live Activity event-type extension workstream
(`docs/PLAN.md` §3.4) reuse the same framework-neutral engine buffers regardless of stack, because their capture wiring
(`BootUiEngineConfiguration`) is gated purely on classpath/bean presence, never on `ConditionalOnWebApplication`:

- Cache and Scheduled Tasks are read from the same `CacheActivityRecorder`/`ScheduledTaskRunStore` the §6.1 panels
  already expose unmodified.
- Mail is read from the same `EmailCaptureService`/`EmailController` the §6.1 Email panel exposes.
- Messaging is read from the independent `KafkaActivityRecorder`, `RabbitActivityRecorder`, and `JmsActivityRecorder`
  buffers, fed by the same template/listener-factory `BeanPostProcessor` wrapping the servlet adapter uses.
- REST/WebClient calls are read from the same `RestClientTraceRecorder` fed by `BootUiEngineConfiguration`'s
  `WebClientCustomizer`; capture is active on both stacks, and the standalone panel is now wired reactively too (§6.3).

The servlet adapter's `LiveActivityController` additionally depends on two things with no reactive equivalent. One is a
`ServletRequestHandledEvent` listener, which exists purely as an SSE-refresh trigger, not a data source. The other is a
thread-based `LiveActivityCorrelator` that stitches a request to its downstream activity via serving-thread identity —
meaningless on Reactor Netty, where a request is not served start-to-finish on one dedicated worker thread.

:::

::: details Live flow service map — no reactive-specific work

The **Live flow** service map (`GET {api}/activity/service-map`) needed no reactive-specific work. `LiveServiceMapController`
injects only beans both stacks register — the shared `HttpExchangesController` plus the engine's `ConnectionPoolService`,
`SqlTraceRecorder`, `RestClientTraceRecorder`, `KafkaActivityRecorder`, `RabbitActivityRecorder`, and
`CacheActivityRecorder`. It returns a stable core DTO, so one class registered in both `BootUiAutoConfiguration` and
`BootUiReactiveAutoConfiguration` makes Spring MVC and WebFlux serve a byte-identical map, including its cache dependency
and opaque flow correlation. All interpretation (identity normalization, configured-versus-observed state, conservative
SQL attribution, cardinality bounds) lives in the framework-neutral `ServiceMapAssembler`. The map refreshes off the
same `ReactiveBootUiChangeStream` SSE tick the feed uses; it performs no additional polling and contacts nothing. Cache
is reported as an available source only after the shared post-processor successfully instruments at least one
`CacheManager`, so an enabled but disconnected recorder never overstates runtime support.

The map's opaque `ServiceMapInteractionDto.flowId` — derived one-way from whatever trace id was active when an
interaction completed — needed no reactive-specific work either. `BootUiReactiveAutoConfiguration` already installs the
same `ReactiveOtelTraceIdProvider` on the HTTP exchange, SQL, REST-client, and cache recorders. `ServiceMapAssembler`
only ever reads whatever trace id those recorders already captured, so causal flow correlation on this adapter is
byte-identical to Spring MVC and Quarkus wherever OpenTelemetry is configured.

`ReactiveActivitySignalFilter` takes an `ObjectProvider<ReactiveLiveActivityController>` rather than a direct reference.
`WebFilter` beans are eagerly resolved by WebFlux at startup to build the filter chain, so a direct constructor
dependency would force-eager the controller and defeat its place in
`BootUiReactiveAutoConfiguration.LAZY_CONTROLLER_CLASS_NAMES`. Calling `.getIfAvailable()` per request is safe because
`ReactiveBootUiChangeStream.signal()` is already a no-op with no subscribers, and the first `/stream` request naturally
resolves (and creates) the controller bean once the panel is opened.

:::

::: details Trace-id correlation, stamped identically to Quarkus

A reactive-only `ReactiveOtelTraceIdProvider` reads `Span.current()` unconditionally at every capture point: HTTP
exchange (via a new `ReactiveHttpExchangeTraceFilter` feeding a side-buffer `HttpExchangeTraceRegistry`, since
Actuator's `HttpExchange` model has no trace-id field), SQL (`SqlTraceRecorder.setTraceIdProvider`), exceptions
(`ReactiveBootUiExceptionHandler.setTraceIdProvider`), and security events (`ReactiveSecurityLogsController.setTraceIdProvider`
+ `ReactiveSecurityEventTraceRegistry`). This replaces the earlier inbound-header/SLF4J-MDC-only reliance. All four are
wired by `BootUiReactiveAutoConfiguration.ReactiveOpenTelemetryCorrelationConfiguration`, gated only on the OpenTelemetry
SDK being present (matching Quarkus's own `Capability.OPENTELEMETRY_TRACER` gate). It is deliberately *not* also gated on
`bootui.telemetry.enabled`, which governs BootUI's own span export for the Traces/AI Framework panels — a separate
concern from reading the id of a span the application's own tracing already started.

**This alone is not sufficient.** WebFlux has no thread-per-request invariant: a single request's reactive chain hops
between the Netty event loop, `boundedElastic` (blocking JDBC), and `parallel` schedulers. `Span.current()` only
resolves correctly across those hops when Reactor's *automatic context propagation* is enabled
(`Hooks.enableAutomaticContextPropagation()`). Spring Boot 4.1 only calls that when
`spring.reactor.context-propagation=auto`, and its own default is `limited`. Without `auto`, the trace-stamping code is
wired correctly but reads an empty/invalid span everywhere except by coincidence on the thread the request started on.
`BootUiActuatorDefaultsEnvironmentPostProcessor` now contributes `spring.reactor.context-propagation=auto` as an
overridable default (the same "library default, host always wins" pattern used for
`management.tracing.sampling.probability`) whenever the application is reactive and the OpenTelemetry SDK is present —
see §7 for how this was found.

**Known, accepted residual limitations:**

- Correlation is still trace-id-primary, exactly like Quarkus. A request with no active tracing span at all (for
  example, OpenTelemetry entirely absent) still shows every signal flat/uncorrelated rather than nested, since there is
  no id to key on. This is not WebFlux-specific — the same is true of the Quarkus adapter today.
- `HttpExchangeTraceRegistry#match` (and its servlet sibling `RequestCorrelationRegistry`) deliberately requires a
  *unique* method+path+time-window candidate. Two genuinely concurrent identical requests (the same endpoint hit twice
  within roughly the same tens of milliseconds, with no other distinguishing signal) correlate to *neither* rather than
  risk attributing one request's trace id to the other. Both still show in the feed; they simply render without a nested
  SQL/exception child until a less ambiguous signal is added.
- The servlet adapter's thread-based correlation (`LiveActivityCorrelator`) is not ported — it has no reactive
  equivalent.

:::

### 6.5 Raw Spring Security panel (`spring-security`) — live on WebFlux

The raw Spring Security panel is ported via `ReactiveSpringSecurityController` and `ReactiveSpringSecurityService`. It is
available whenever the application contributes at least one `SecurityWebFilterChain`; BootUI's own permit-all chain is
excluded from that decision and from the report. The extractor reads the ordered chain beans directly rather than
reflecting into `WebFilterChainProxy`, and every filter collection, chain match, and authorization simulation stays a
Reactor `Mono`/`Flux` pipeline — no request path calls `block()` or performs other event-loop-blocking waits.

Platform-aware fidelity notes:

- **Filters are `WebFilter` beans**, not `jakarta.servlet.Filter`; they are named faithfully in the filter list.
- **Matchers are `ServerWebExchangeMatcher`** (`PathPatternParserServerWebExchangeMatcher`, etc.). Actual matching calls
  each chain's public reactive `matches(...)` API, including custom chain implementations. Spring Security does not
  expose the standard chain's matcher metadata, so BootUI uses bounded, read-only reflection for that description only.
  Known Spring matchers are rendered from an allow-list; a custom matcher's arbitrary `toString()` is never exposed
  because it could contain a configured header or token.
- **Explain is deliberately reduced-fidelity.** `/explain` builds a sanitized path-and-method-only exchange and never
  reuses the BootUI request's headers, cookies, principal, session, body, addresses, or TLS state. Reactive explain
  results carry `bestEffort:true`; a context-dependent matcher can report no match rather than receiving live secrets.
- **`sessionManagementPresent` is a compatibility field, not proof of a `WebSession` policy.** On WebFlux it is set when
  the chain contains Spring Security's context integration filter
  (`SecurityContextServerWebExchangeWebFilter`/`ReactorContextWebFilter`). The UI labels this signal **Security
  context**; it does not claim server-side session persistence is enabled or disabled.
- **Endpoint authorization** (`/endpoints`) uses the reactive `RequestMappingHandlerMapping` where available. Annotation
  mappings are evaluated with the same sanitized exchange and synthetic anonymous/authenticated principals.
  Context-dependent or custom authorization managers are reported as `custom`/`unknown`, never guessed as `denyAll`.
  Functional-style `RouterFunction` routes are not included because they emit no annotation mapping metadata.
- **The BootUI permit-all chain** (`bootUiReactiveSecurityWebFilterChain` from
  `BootUiReactiveSpringSecurityAutoConfiguration`) is always filtered out by `BootUiSelfDataFilter`, so the panel never
  shows BootUI's own internal chain.
- **`BootUiReactiveSpringSecurityAutoConfiguration`** is auto-configured (alongside the servlet
  `BootUiSpringSecurityAutoConfiguration`) and permits BootUI routes at highest precedence when reactive Spring Security
  is configured, preventing the application's login wall from blocking the `/bootui` root and its descendants. It keeps
  the MCP bridge, OTLP ingestion, and API-unlock session endpoints outside browser CSRF while requiring a cookie/header
  token pair for other state-changing BootUI requests. The configuration is isolated behind string-based class and bean
  conditions, so a WebFlux application without Spring Security starts without linking any reactive security type.
- The WebFlux sample app (`bootui-spring-webflux-sample-app`) includes `spring-boot-starter-security` to demonstrate the
  integration end to end.

### 6.6 Security advisor (`security`) — live on WebFlux

The advisor uses a dedicated 26-rule reactive catalogue (`SEC-RXF-*`) over a neutral observation model collected from
the application's `SecurityWebFilterChain` configuration. It stays distinct from the raw `spring-security` panel: the
raw panel explains the configured chains and mappings, while the advisor turns the observed posture into bounded,
deterministic findings across authorization, CSRF, CORS, headers, Actuator exposure, OAuth2/JWT, configuration, and
reactive session policy. BootUI's own permit-all chain is excluded from availability and analysis. See
`docs/SECURITY-CHECKS.md` for the complete reactive catalogue.

The catalogue is aligned with the Java 17 / Spring Boot 4.1.0 / Spring Security 7.1.0 baseline and deliberately
describes only evidence the adapter can observe: installed filter/header-writer types, inspectable CORS maps, and host
`Environment` properties. An installed `AuthorizationWebFilter` does not reveal whether its manager chose `permitAll`,
`authenticated`, a role check, or custom logic; a decoder-local JWT validator is not inferred from unrelated validator
beans; and management-path authorization, reverse-proxy TLS policy, handler-level CORS, and custom filters remain
outside the snapshot. Unknown filter, header-writer, or CORS extraction skips dependent conclusions and produces a
partial observation rather than treating it as an absent protection. Recursive composite header traversal is
depth-bounded and reports incomplete evidence explicitly.

::: details Rule alignment history

Two unsupported checks were replaced without changing the 25-rule count: the non-existent reactive
`spring.security.debug` switch and the unprovable global-bean audience-validator check gave way to host-configured
Actuator `show-values=always` detection for web-exposed `env`/`configprops` endpoints and RFC 7662 plain-HTTP
opaque-token introspection detection.

Existing findings were narrowed where needed. Credentialed CORS now targets the legal `allowedOriginPatterns="*"` case;
CSP absence is a LOW contextual review (and report-only policies are called out as non-enforcing); static JWT keys are
LOW rotation advice; HTTPS/Actuator findings avoid claiming knowledge of external deployment policy; and the mixed
bearer/login rule uses WebFlux's real `NoOpServerSecurityContextRepository` remediation rather than servlet-only
`SessionCreationPolicy`.

A follow-up parity review brought the catalogue to 26 rules: `SEC-RXF-CSRF-001` and `SEC-RXF-SESSION-001` now also
recognize `formLogin()` chains, not just OAuth2/OIDC login filters, and the new `SEC-RXF-CORS-003` flags broad
`allowedOriginPatterns` (e.g. `https://*`) to match the servlet stack's `SEC-CORS-006`.

:::

### 6.7 Not applicable (1 panel)

| Panel         | Reason                                                                                                                   |
| ------------- | ----------------------------------------------------------------------------------------------------------------------- |
| HTTP Sessions | *"Not applicable on Spring WebFlux: HTTP Sessions are the servlet container's HttpSession API, which has no reactive equivalent (WebSession is a different, non-container-managed model), so this panel does not apply here."* Mirrors how GraalVM/CRaC are `NOT_APPLICABLE` on Quarkus rather than "not yet." |

## 7. A real bug this port found and fixed

Three availability/wiring gaps surfaced only because the WebFlux-only sample app (§8) was the first truly single-stack
classpath the codebase ran, rather than a unit-test classpath that always carries both `spring-webmvc` and
`spring-webflux`.

::: details 1. NoClassDefFoundError on a genuinely MVC-free classpath

Building the WebFlux-only sample app surfaced a genuine `NoClassDefFoundError`:
`BootUiEngineConfiguration.bootUiPentestingScanner()` referenced the MVC-only `RequestMappingInfoHandlerMapping.class`
unconditionally in an `@Lazy` `@Bean` method body. Resolving that class-literal constant-pool entry throws when
`spring-webmvc` is genuinely absent. Fixed with a `ClassUtils.isPresent(...)` guard before the `.class` literal, passing
`null` to `SpringPentestingObservationCollector` when absent. The collector records that MVC endpoint metadata is
**unavailable**, rather than returning an empty inventory that could be mistaken for inspection finding no mappings.
Pentesting still evaluates bounded Spring configuration/OAuth metadata plus at most one GET and one OPTIONS loopback
response, but A01 servlet coverage is `NOT_APPLICABLE` and no-finding mixed-category coverage uses WebFlux-specific
`INFO` wording. The reactive Security advisor owns `SecurityWebFilterChain`, reactive CORS, and route-policy review. The
collector uses `spring.webflux.base-path` for the validated loopback target instead of the servlet-only
`server.servlet.context-path`. This is the same defensive pattern the rest of the codebase uses for optional-dependency
adapters — the reactive starter was simply the first Spring-side consumer where an MVC type can be genuinely absent from
the classpath, not just absent as a bean.

:::

::: details 2. Manifest disagreeing with wiring

`PanelsController` unconditionally reported `mcp-server` and `activity` as `available: true` on every platform, even
though the reactive autoconfiguration didn't yet wire their controllers. Fixed alongside the other divergent-panel
availability checks (§6.5 today). `activity` has since been genuinely wired reactively (§6.4) and now correctly reports
`available: true` again — this time because a real `ReactiveLiveActivityController` backs it. Both were caught by
`WebFluxApiConformanceTest`'s inherited `availablePanelsAnswerTheirPrimaryGet()` assertion (§8) — direct evidence for
why the conformance suite runs against a real, minimal, single-stack sample app rather than relying on unit tests
against a shared multi-stack test classpath alone.

:::

::: details 3. Reactor context propagation not enabled by default

This gap surfaced only through end-to-end testing against the running sample app — unit tests call capture points
directly on a single thread, so they never exercise a real Reactor scheduler hop. Stamping `Span.current()` at each
capture point (§6.4) is necessary but not sufficient: Spring Boot 4.1's `spring-boot-reactor` module only calls
`Hooks.enableAutomaticContextPropagation()` when `spring.reactor.context-propagation=auto`, and its own default is
`limited`. Under the default, Reactor does not restore OpenTelemetry's ThreadLocal-based span context across the
scheduler hops a WebFlux request constantly makes (Netty event loop → `boundedElastic`/`loomBoundedElastic` for blocking
JDBC → `parallel`), so `Span.current()` returned a valid span only by coincidence, on whichever thread a request
happened to still be on. Every new trace-stamping capture point read `null`/an invalid span in practice despite being
wired correctly. Fixed by having `BootUiActuatorDefaultsEnvironmentPostProcessor` contribute
`spring.reactor.context-propagation=auto` as an overridable default whenever the application is reactive and the
OpenTelemetry SDK is present. Confirmed by hitting the running sample app directly (`/api/notes`, `/api/sample/boom`)
and checking `/bootui/api/http-exchanges`, `/bootui/api/sql-trace`, `/bootui/api/exceptions`, and `/bootui/api/activity`
for populated, correctly-nested (`parentId`) trace ids — none of which a unit test asserts.

:::

## 8. Sample app & end-to-end testing

- **`bootui-spring-webflux-sample-app`** is a minimal WebFlux app (Netty, `spring-boot-starter-webflux`, deliberately no
  `spring-boot-starter-web`) with `notes`/`scheduling`/`greeting` packages, seeded with a scheduled task and an H2
  datasource (Flyway + Liquibase migrations on separate baselined schemas) so the data-source-backed panels (Flyway,
  Liquibase, Database Connection Pools, SQL Trace) have something real to show.
- **`bootui-conformance`** gained `expected-panels-webflux.json` — identical to `expected-panels-spring.json` except
  `platform: "spring-boot-reactive"`, itself evidence the shared-contract thesis holds even in the golden fixture — and
  the sample app's `WebFluxApiConformanceTest extends AbstractBootUiApiConformanceTest` reuses the entire shared HTTP
  contract suite for free, exactly as the Quarkus adapter does.
- **`bootui-spring-sample-app/e2e/playwright.webflux.config.js`** and `tests-webflux/webflux-smoke.spec.js` are a second,
  separate Playwright config and test directory (not a new npm project), so the default `npm test` run against the
  servlet sample app is untouched. The WebFlux suite checks the platform manifest, navbar branding, a representative
  sample of ported panels rendering cleanly (now including Live Activity and the MCP Server panel), that `http-sessions`
  shows its WebFlux-specific reason in both the sidebar and the panel alert, and that the Security advisor (`security`)
  is available and can be scanned.
- Run it: see the "WebFlux (reactive) smoke suite" section of `bootui-spring-sample-app/e2e/README.md`.
- **`playwright.custom-path.config.js`** runs one shared browser contract against both MVC and WebFlux with a
  non-default application root, UI path, and independently configured API path. It proves the shell metadata, assets,
  manifest, API fetches, SSE, CSRF enforcement, MCP controls, servlet OTLP receiver, and 404 behavior of the old
  `/bootui` mount in a real browser.
- **`Dockerfile-webflux`** (repository root) is the reactive analogue of the plain servlet `Dockerfile`: the same
  exploded-jar-layers + jlink + distroless-glibc recipe, pointed at `bootui-spring-webflux-sample-app`. It keeps the
  sample app's own `server.port=8081` default in the container too — the same port locally and in Docker, matching the
  one-dedicated-port-per-sample-app-family scheme (servlet/AOT/native/CRaC share 8080, WebFlux is 8081, Quarkus is
  8082). There is deliberately no AOT/CRaC/native variant for WebFlux — see the Dockerfile's own header comment.
  `.github/workflows/docker-publish.yml` builds, smoke-tests (`/actuator/health`, `/bootui/api/panels`, `/bootui/`)
  against port 8081, and publishes it daily to Docker Hub as `jdubois/bootui-sample-app-webflux`, alongside the other
  five images.

## 9. Operational note: profile activation

`BootUiActivationCondition` checks `Environment.getActiveProfiles()`, not `getDefaultProfiles()`. An application whose
`application.properties` sets `spring.profiles.default=dev` (a default, used only when literally zero profiles are
active) will **not** activate BootUI under a bare `java -jar` launch unless a profile is explicitly activated
(`--spring.profiles.active=dev`, `SPRING_PROFILES_ACTIVE=dev`, or `spring-boot-devtools` on the classpath, which is
excluded from a repackaged jar by default). This is not WebFlux-specific — it reproduces identically on the servlet
sample app — but is easy to trip over when smoke-testing a freshly built reactive sample app jar by hand.

## 10. Future work

- Deeper Live Activity correlation for requests with no active tracing span at all (today: trace-id-primary only, now
  matching the Quarkus adapter exactly since `Span.current()` is stamped unconditionally at every capture point — see
  §6.4).
