# BootUI on Spring WebFlux — support status

## 1. Goal

Support Spring WebFlux (reactive, Netty/`DispatcherHandler`) Spring Boot 4 applications as a first-class BootUI
target alongside the existing Spring MVC (servlet) adapter and the Quarkus adapter: the same shared engine
(`bootui-engine`/`bootui-core`), the same Vue UI, the same `/bootui/api/**` contract, panel parity wherever a
reactive analog genuinely exists, and an honest "not yet ported" / "not applicable" status where it doesn't.

## 2. Current status

The WebFlux adapter serves the large majority of the panel surface — the same 49-panel manifest the servlet adapter
reports, minus the five panels that stay unavailable for stack reasons described below. **Every action-capable panel
that is available behaves identically to the servlet adapter**, behind the same shared `LocalhostGuard` write floor:
Loggers (set level), HTTP Probe, Cache (clear), Flyway (migrate/clean), Liquibase (update), Heap Dump
(capture/analyze/delete/download), Threads (download), Traces (clear), SQL Trace (toggle recording/clear), the
advisor scans (Architecture, Spring, Hibernate, Pentesting, REST API, Memory, Vulnerabilities/OSV), and Exceptions
triage.

Only **HTTP Sessions**, the **Security advisor**, the raw **Spring Security** panel, **MCP Server**, and the
standalone **REST Client Trace** panel stay unavailable, each with a panel-specific reason surfaced through the
`/bootui/api/panels` manifest (and, in turn, the sidebar tooltip and the panel's own alert banner — see §5).
`docs/FEATURES.md` and the per-panel `unavailableReason` strings in `PanelsController` are the authoritative, current
detail.

## 3. Why this is feasible — evidence from the current codebase

- `bootui-core`/`bootui-engine`/`bootui-ui` were already 100% framework-neutral before this work started, and
  **needed zero changes** beyond adding one new platform constant (`PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE`).
  Every advisor engine, DTO, and Vue view is reused byte-for-byte from the servlet adapter — the same reuse story the
  Quarkus adapter already proved out.
- The overwhelming majority of the servlet adapter's `@RestController`s use the shared
  `org.springframework.web.bind.annotation` model, return plain DTO records, and never reference
  `HttpServletRequest`/`HttpServletResponse` directly — `DispatcherHandler` (WebFlux's dispatcher) invokes them
  exactly as `DispatcherServlet` does, unmodified.
- The handful of panels that genuinely needed new code all needed it for the same reason: a servlet-only streaming or
  eventing primitive (`SseEmitter`, `HandlerExceptionResolver`, `ServletRequestHandledEvent`) with no reactive
  equivalent already wired. Each of those has a reactive analog available in Spring itself
  (`Flux<ServerSentEvent<T>>`, `WebExceptionHandler`) — the engine services underneath (`SqlTraceRecorder`,
  `ExceptionStore`, `LogTailBuffer`, `AgentSessionStore`, …) needed no changes at all.

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

`BootUiAutoConfiguration` (servlet) and `BootUiReactiveAutoConfiguration` (reactive) are two separate
autoconfigurations in the same `bootui-spring-autoconfigure` module — not two modules — because nearly all of the
underlying `@RestController`s, safety decisions, and engine wiring are identical; only the request/response binding
and the streaming primitive genuinely differ per stack. Spring Boot's `WebApplicationType` deduction means the two
autoconfigurations are mutually exclusive in practice: a running application is either `SERVLET` or `REACTIVE`, never
both, so exactly one of the two autoconfigurations activates.

## 5. Activation & safety on WebFlux

- **Same activation rule, same fail-closed default.** `BootUiReactiveAutoConfiguration` is gated by the identical
  `BootUiActivationCondition` the servlet adapter uses (`bootui.enabled=ON|OFF`, `bootui.enabled-profiles` /
  `bootui.disabled-profiles`, `spring-boot-devtools` on the classpath), plus
  `@ConditionalOnWebApplication(REACTIVE)` and `@ConditionalOnClass(DispatcherHandler)`. There is no separate
  "reactive mode" flag to opt into — BootUI simply detects which stack Spring Boot picked and binds accordingly.
- **Same `LocalhostGuard`, ported to `WebFilter`.** `ReactiveLocalhostOnlyFilter` is a thin `WebFilter` binding over
  the exact same framework-neutral `io.github.jdubois.bootui.engine.safety.LocalhostGuard` the servlet
  `LocalhostOnlyFilter` uses — same loopback-source trust, `Host` allow-list, cross-site-write/CSRF defense, same
  canonical `{"error":"…"}` 403 JSON body. Only the request/response plumbing (`ServerWebExchange` instead of
  `HttpServletRequest`/`HttpServletResponse`) differs.
- **Same per-panel gating.** `ReactivePanelAccessFilter` enforces `bootui.panels.*` (enable/read-only) via the same
  `BootUiPanels` registry the servlet `PanelAccessFilter` uses — same config keys, same canonical JSON 403 body.
- **Same platform-aware manifest mechanism the Quarkus adapter established.** `PanelsController` — a single shared
  bean bulk-imported unmodified by both autoconfigurations — detects the running context type
  (`applicationContext instanceof ReactiveWebApplicationContext`) and reports `platform:
  "spring-boot-reactive"` in `/bootui/api/panels`, plus per-panel `available`/`unavailableReason` computed for the
  four panels that diverge (§6). The Vue UI already reads this manifest via `inject('panels')` and renders the
  `unavailableReason` in both the sidebar tooltip and the panel's own alert banner — no `.vue` file needed to change.

## 6. Panel disposition

`Port` = ships from shared code, zero adapter changes needed · `Adapt` = reuses the shared engine over a small new
reactive binding (e.g. a `WebFilter` capturing into the same engine store) · `Rebuild` = a genuinely new reactive
capture layer replacing a servlet-only primitive · `Not yet ported` = deliberately deferred, no reactive
implementation wired yet · `Not applicable` = no faithful reactive analog exists for this panel's concept.

### 6.1 Ported as-is (36 panels)

Bulk-imported from the servlet adapter's `@RestController`s with no code changes at all — confirming these
controllers were already framework-neutral in practice, not just in the engine underneath them:

Overview · GitHub · Beans · Conditions · Configuration · Mappings · Health · Loggers · Startup Timeline · Spring Data ·
Hibernate · Flyway · Liquibase · Database Connection Pools · Cache · Dev Services · Vulnerabilities · Scheduled Tasks ·
HTTP Probe · Pentesting · Heap Dump · Architecture · REST API advisor · Profile Diff · Spring advisor[^spring-advisor-reactive] ·
Live Memory · JVM Tuning · Metrics · DevTools · Traces · AI Usage · GraalVM · CRaC · Threads · Memory · Email.

[^spring-advisor-reactive]: The `SpringController` wiring itself needed no adapter change, but the ruleset it runs
    (`SpringScanner`/`SpringRules`) is reactive-aware internally: it detects a WebFlux `ReactiveWebApplicationContext`
    the same way `PanelsController.isReactive()` does, skips a servlet-only rule that does not apply
    (`SPRING-WEB-007`, Tomcat thread cap), also matches `WebClient` beans for the HTTP-client-timeout rule
    (`SPRING-WEB-005`), points four rules' "Learn more" links at the reactive docs page instead of the servlet one,
    and adds two WebFlux-only rules (`SPRING-REACTIVE-001`, `SPRING-REACTIVE-002`) that are otherwise `SKIPPED`. See
    `docs/SPRING-CHECKS.md`.

### 6.2 Adapted with a small new binding (1 panel)

| Panel          | Reactive binding                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------------------- |
| HTTP Exchanges | `ReactiveHttpExchangeRepositoryConfiguration` supplies Actuator's reactive `HttpExchangeRepository` bean instead of the servlet one — same DTO, same UI, same capture semantics |

### 6.3 Rebuilt with a new reactive capture layer (6 panels)

The DTO and UI are reused unchanged; only the capture/streaming source was rewritten because the servlet original
depended on `SseEmitter` (SQL Trace, Log Tail, Security Logs, Exceptions) or `HandlerExceptionResolver` (Exceptions):

| Panel         | Reactive source                                                                                                                                                                        |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SQL Trace     | `ReactiveSqlTraceController`, streaming over the new shared `ReactiveBootUiChangeStream` SSE primitive (`Flux<ServerSentEvent<T>>`), feeding the same `SqlTraceRecorder` engine class |
| Log Tail      | `ReactiveLogTailController` — same `LogTailBuffer`/Logback appender, SSE via `ReactiveBootUiChangeStream`               |
| Security Logs | `ReactiveSecurityLogsController` over a fallback `InMemoryAuditEventRepository` (Spring's audit-event bus is itself framework-neutral, so no reactive-specific capture code was needed) |
| Exceptions    | `ReactiveExceptionsController` + new `ReactiveBootUiExceptionHandler` (a `WebExceptionHandler` at `HIGHEST_PRECEDENCE`, replacing the servlet `HandlerExceptionResolver`); see the fidelity note below |
| Copilot       | `ReactiveCopilotController` over the same `AgentSessionStore`, SSE via `ReactiveBootUiChangeStream`                     |
| Claude Code   | `ReactiveClaudeCodeController` over the same `AgentSessionStore`, SSE via `ReactiveBootUiChangeStream`                  |

**`ReactiveBootUiChangeStream`** is a small shared `Sinks.Many`-backed SSE broadcaster (`open()` /
`signal()` / `close()`) used by every "push an update when something changes" panel above, instead of each controller
hand-rolling its own sink — it centralizes coalescing, back-pressure, and a concurrency limit once rather than
per-panel.

**Known fidelity gap, accepted, documented in code (`ReactiveBootUiExceptionHandler`'s Javadoc):** a
`@RestController`'s own local `@ExceptionHandler` method consumes an exception *inside* the WebFlux dispatch pipeline,
before any `WebExceptionHandler` sees it — narrower capture than the servlet adapter's resolver-chain-based approach,
where `BootUiExceptionHandlerResolver` runs at `HIGHEST_PRECEDENCE` in the same chain `@ExceptionHandler` resolution
uses. An application-level `@ExceptionHandler` will suppress BootUI's capture on WebFlux where it would not on
servlet. Unhandled exceptions (the common case) are captured identically on both stacks.

### 6.4 Rebuilt as a merge over already-reactive signals (1 panel)

Live Activity needed no new *capture* pipeline for any of its **nine** merged signal types. The original four —
HTTP Exchanges, SQL Trace, Exceptions, and Security Logs — are already captured reactively by the panels in
§6.2/§6.3. The five newer entry types added by the Live Activity event-type extension workstream (`docs/PLAN.md`
§3.4) reuse the same framework-neutral engine buffers regardless of servlet vs. reactive stack, because their
capture wiring (`BootUiEngineConfiguration`) is gated purely on classpath/bean presence, never on
`ConditionalOnWebApplication`: Cache and Scheduled Tasks are read from the same `CacheActivityRecorder`/
`ScheduledTaskRunStore` the §6.1 Cache/Scheduled Tasks panels already expose unmodified; Mail is read from the same
`EmailCaptureService`/`EmailController` the §6.1 Email panel exposes; Kafka messaging is read from
`KafkaActivityRecorder` (fed by the same `KafkaTemplate`/`@KafkaListener` `BeanPostProcessor` wrapping used on the
servlet adapter, which has no servlet-specific dependency); and REST/WebClient calls are read from the same
`RestClientTraceRecorder` fed by `BootUiEngineConfiguration`'s `WebClientCustomizer` (see §6.5 — capture is active
here even though the *standalone* REST Client Trace panel is not). The servlet adapter's `LiveActivityController`
additionally depends on two things with no reactive equivalent: a `ServletRequestHandledEvent` listener, which exists
purely as an SSE-refresh trigger (not a data source), and a thread-based `LiveActivityCorrelator` that stitches a
request to its downstream activity via serving-thread identity — meaningless on Reactor Netty, where a request is
not served start-to-finish on one dedicated worker thread.

| Panel         | Reactive source                                                                                                                                                                                     |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Live Activity | `ReactiveLiveActivityController`, merging `HttpExchangesController` (requests), `SqlTraceRecorder` (SQL), `ExceptionStore` (exceptions), `ReactiveSecurityLogsController` (security), `CacheActivityRecorder` (cache), `ScheduledTaskRunStore` (scheduled tasks), `KafkaActivityRecorder` (messaging), `EmailCaptureService`/`EmailController` (mail), and `RestClientTraceRecorder` (REST/WebClient calls) via the shared engine `LiveActivityAssembler`/`RequestProfileAssembler` — the same classes the Quarkus adapter validated first; refreshed over `ReactiveBootUiChangeStream`, signaled by a new lightweight `ReactiveActivitySignalFilter` `WebFilter` after each non-BootUI request completes. |

`ReactiveActivitySignalFilter` takes an `ObjectProvider<ReactiveLiveActivityController>` rather than a direct
reference: `WebFilter` beans are eagerly resolved by WebFlux at startup to build the filter chain, so a direct
constructor dependency would force-eager the controller and defeat its place in
`BootUiReactiveAutoConfiguration.LAZY_CONTROLLER_CLASS_NAMES`. Calling `.getIfAvailable()` per request is safe
because `ReactiveBootUiChangeStream.signal()` is already a no-op with no subscribers, and the first `/stream`
request naturally resolves (and thus creates) the controller bean once the panel is actually opened.

**Trace-id correlation, now stamped identically to Quarkus:** a reactive-only `ReactiveOtelTraceIdProvider` reads
`Span.current()` unconditionally at every capture point — HTTP exchange (via a new `ReactiveHttpExchangeTraceFilter`
feeding a side-buffer `HttpExchangeTraceRegistry`, since Actuator's `HttpExchange` model has no trace-id field to
populate directly), SQL (`SqlTraceRecorder.setTraceIdProvider`), exceptions (`ReactiveBootUiExceptionHandler
.setTraceIdProvider`), and security events (`ReactiveSecurityLogsController.setTraceIdProvider` +
`ReactiveSecurityEventTraceRegistry`) — replacing the earlier inbound-header/SLF4J-MDC-only reliance. All four are
wired by `BootUiReactiveAutoConfiguration.ReactiveOpenTelemetryCorrelationConfiguration`, gated only on the
OpenTelemetry SDK being present (matching Quarkus's own `Capability.OPENTELEMETRY_TRACER` gate) — deliberately
*not* also gated on `bootui.telemetry.enabled`, which governs BootUI's own span export for the Traces/AI Usage
panels, a separate concern from reading the id of a span the application's own tracing already started.

**This alone is not sufficient — a second, non-obvious requirement makes it actually work.** WebFlux has no
thread-per-request invariant: a single request's reactive chain hops between the Netty event loop, `boundedElastic`
(blocking JDBC), and `parallel` schedulers. `Span.current()` only resolves correctly across those hops when
Reactor's *automatic context propagation* is enabled (`Hooks.enableAutomaticContextPropagation()`); Spring Boot 4.1
only calls that when `spring.reactor.context-propagation=auto`, and its own default is `limited`. Without `auto`,
the trace-stamping code above is wired correctly but reads an empty/invalid span everywhere except by coincidence
on the exact thread the request started on. `BootUiActuatorDefaultsEnvironmentPostProcessor` now contributes
`spring.reactor.context-propagation=auto` as an overridable default (the same "library default, host always wins"
pattern already used for `management.tracing.sampling.probability`) whenever the application is reactive and the
OpenTelemetry SDK is present — see §7 for how this was found.

**Known, accepted residual limitations:**

- Correlation is still trace-id-primary, exactly like Quarkus: a request with no active tracing span at all (for
  example, OpenTelemetry entirely absent from the classpath) still shows every signal flat/uncorrelated rather than
  nested, since there is no id to key on. This is not a WebFlux-specific gap — the same is true of the Quarkus
  adapter today.
- `HttpExchangeTraceRegistry#match` (and its servlet-side sibling `RequestCorrelationRegistry`) deliberately requires
  a *unique* method+path+time-window candidate: two genuinely concurrent identical requests (for example, the same
  endpoint hit twice within roughly the same tens of milliseconds, with no other distinguishing signal available)
  correlate to *neither* rather than risk attributing one request's trace id to the other. Both requests still show
  up in the feed; they simply render without a nested SQL/exception child until a less ambiguous signal is added.
- The servlet adapter's thread-based correlation (`LiveActivityCorrelator`) is not ported — it has no reactive
  equivalent.

### 6.5 Not yet ported (3 panels)

| Panel          | Reason                                                                                                                                                                                        |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Spring Security (raw panel, `spring-security`) | *"Not yet ported for Spring WebFlux: this advisor analyzes the servlet SecurityFilterChain/HttpSecurity configuration model, which has no reactive equivalent wired here yet (a ServerHttpSecurity/SecurityWebFilterChain ruleset is planned)."* `springSecurityAvailable()` now requires `!isReactive()` in addition to the pre-existing classpath/bean checks. |
| MCP Server              | *"Not yet ported for Spring WebFlux: the MCP tool catalog is hard-wired to the servlet panel controllers, so it cannot yet resolve the reactive panel surface."* The JSON-RPC bridge itself (`McpDispatcher`) is already framework-neutral; only `BootUiMcpTools`' tool catalog needs a reactive-aware rewrite. |
| REST Client Trace       | *"REST Client Trace is only available on the Spring MVC (servlet) adapter."* — the panel's availability check in `PanelsController` requires `!isReactive()` alongside the pre-existing `RestClientTraceRecorder` bean-presence check, so the dedicated full-detail panel (with its own filtering/paging over every captured call) is not wired into `BootUiReactiveAutoConfiguration`. **Capture itself is not stack-gated**, though: `BootUiEngineConfiguration`'s `WebClientCustomizer` attaches `RestClientTraceExchangeFilter` to every auto-configured `WebClient.Builder` regardless of web application type, so REST/WebClient calls are still captured into the shared `RestClientTraceRecorder` and still appear as `REST_CLIENT` entries in the Live Activity merge (§6.4) on WebFlux — only the standalone panel is unavailable. |

Note: **the Security advisor** (`security`, grouped under Advisors — distinct from the raw **Spring Security**
configuration panel above, grouped under Security) also stays unavailable on WebFlux, but needed no dedicated
reactive-aware string: `securityAvailable()` checks for a `FilterChainProxy` bean (the servlet security filter,
`extends GenericFilterBean`), while a reactive Spring Security setup only ever registers a `WebFilterChainProxy` bean
(`implements WebFilter`, package `org.springframework.security.web.server`) — two unrelated types in the same
`spring-security-web` jar. So the existing check already resolves `false` on WebFlux by construction, and the panel
falls through to its pre-existing generic reasons ("Spring Security not on the classpath" / "No Spring Security
filter chains are available") rather than a WebFlux-specific one. A reactive ruleset for this advisor is a genuinely
new advisor (comparable in scope to the from-scratch Quarkus Security ruleset), deliberately deferred to a follow-up.

### 6.6 Not applicable (1 panel)

| Panel        | Reason                                                                                                                                                                                                     |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| HTTP Sessions | *"Not applicable on Spring WebFlux: HTTP Sessions are the servlet container's HttpSession API, which has no reactive equivalent (WebSession is a different, non-container-managed model), so this panel does not apply here."* Mirrors how GraalVM/CRaC are `NOT_APPLICABLE` on Quarkus rather than "not yet." |

## 7. A real bug this port found and fixed

Building the WebFlux-only sample app (§8) — the first time this codebase ran a truly WebFlux-only classpath, as
opposed to a unit-test classpath that always carries both `spring-webmvc` and `spring-webflux` — surfaced a genuine
`NoClassDefFoundError`: `BootUiEngineConfiguration.bootUiPentestingScanner()` referenced the MVC-only
`RequestMappingInfoHandlerMapping.class` unconditionally in an `@Lazy` `@Bean` method body. Resolving that
class-literal constant-pool entry throws when `spring-webmvc` is genuinely absent. Fixed with a
`ClassUtils.isPresent(...)` guard before the `.class` literal, passing `null` to
`SpringPentestingObservationCollector` when absent (already null-safe: an absent provider renders an empty endpoint
inventory, the same "deliberately empty inventory" pattern the Quarkus adapter's Pentesting port already uses). This
is the same defensive pattern the rest of the codebase already uses for optional-dependency adapters — the reactive
starter was simply the first Spring-side consumer where an MVC type can be genuinely absent from the classpath, not
just absent as a bean.

A second gap of the same *class* — availability manifest disagreeing with actual wiring — was caught the same way:
`PanelsController` unconditionally reported `mcp-server` and `activity` as `available: true` on every platform, even
though the reactive autoconfiguration didn't yet wire their controllers. Fixed alongside the other divergent-panel
availability checks (§6.5 today). `activity` has since been genuinely wired reactively (§6.4) and now correctly
reports `available: true` again — this time because a real `ReactiveLiveActivityController` backs it.

Both were caught by `WebFluxApiConformanceTest`'s inherited `availablePanelsAnswerTheirPrimaryGet()` assertion (see
§8) — direct evidence for why the conformance suite runs against a real, minimal, single-stack sample app rather than
relying on unit tests against a shared multi-stack test classpath alone.

A third gap surfaced only through genuine end-to-end testing against the running sample app — unit tests call
capture points directly on a single thread, so they never exercise a real Reactor scheduler hop and could not have
caught this. Stamping `Span.current()` at each capture point (§6.4) is necessary but not sufficient: Spring Boot
4.1's `spring-boot-reactor` module only calls `Hooks.enableAutomaticContextPropagation()` when
`spring.reactor.context-propagation=auto`, and its own default is `limited`. Under the default, Reactor does not
restore OpenTelemetry's ThreadLocal-based span context across the scheduler hops a WebFlux request constantly
makes (Netty event loop → `boundedElastic`/`loomBoundedElastic` for blocking JDBC → `parallel`), so `Span.current()`
returned a valid span only by coincidence, on whichever thread a request happened to still be on — every new
trace-stamping capture point read `null`/an invalid span in practice despite being wired correctly. Fixed by having
`BootUiActuatorDefaultsEnvironmentPostProcessor` contribute `spring.reactor.context-propagation=auto` as an
overridable default whenever the application is reactive and the OpenTelemetry SDK is present. Confirmed by hitting
the running sample app directly (`/api/notes`, `/api/sample/boom`) and checking `/bootui/api/http-exchanges`,
`/bootui/api/sql-trace`, `/bootui/api/exceptions`, and `/bootui/api/activity` for populated, correctly-nested
(`parentId`) trace ids — none of which a unit test asserts, since they all call the affected classes' methods
directly rather than through a real multi-scheduler Reactor pipeline.

## 8. Sample app & end-to-end testing

- **`bootui-spring-webflux-sample-app`** is a minimal WebFlux app (Netty, `spring-boot-starter-webflux`, deliberately
  no `spring-boot-starter-web`) with `notes`/`scheduling`/`greeting` packages, seeded with a scheduled task and an H2
  datasource (Flyway + Liquibase migrations on separate baselined schemas) so the data-source-backed panels
  (Flyway, Liquibase, Database Connection Pools, SQL Trace) have something real to show.
- **`bootui-conformance`** gained `expected-panels-webflux.json` — identical to `expected-panels-spring.json` except
  `platform: "spring-boot-reactive"`, itself evidence the shared-contract thesis holds even in the golden fixture —
  and the sample app's `WebFluxApiConformanceTest extends AbstractBootUiApiConformanceTest` reuses the entire shared
  HTTP contract suite for free, exactly as the Quarkus adapter does.
- **`bootui-spring-sample-app/e2e/playwright.webflux.config.js`** and `tests-webflux/webflux-smoke.spec.js` are a
  second, separate Playwright config and test directory (not a new npm project) so the default `npm test` run against
  the servlet sample app is untouched; the WebFlux suite checks the platform manifest, navbar branding, a
  representative sample of ported panels rendering cleanly (now including Live Activity), and that `http-sessions`,
  `spring-security`, and `mcp-server` each show their WebFlux-specific reason in both the sidebar and the panel alert
  (the `security` advisor's equivalent reason is covered at the unit level by `PanelsControllerTests`, not re-asserted
  in e2e).
- Run it: see the "WebFlux (reactive) smoke suite" section of `bootui-spring-sample-app/e2e/README.md`.
- **`Dockerfile-webflux`** (repository root) is the reactive analogue of the plain servlet `Dockerfile`: the same
  exploded-jar-layers + jlink + distroless-glibc recipe, pointed at `bootui-spring-webflux-sample-app` instead of
  `bootui-spring-sample-app`, keeping the sample app's own `server.port=8081` default in the container too — the same
  port locally and in Docker, matching the one-dedicated-port-per-sample-app-family scheme (servlet/AOT/native/CRaC
  share 8080, WebFlux is 8081, Quarkus is 8082). There is deliberately no AOT/CRaC/native variant for WebFlux — see the
  Dockerfile's own header comment. `.github/workflows/docker-publish.yml` builds, smoke-tests (`/actuator/health`,
  `/bootui/api/panels`, `/bootui/`) against port 8081 for this image and publishes it daily to Docker Hub as
  `jdubois/bootui-sample-app-webflux`, alongside the other five images.

## 9. Operational note: profile activation

`BootUiActivationCondition` checks `Environment.getActiveProfiles()`, not `getDefaultProfiles()`. An application
whose `application.properties` sets `spring.profiles.default=dev` (a default, only used when literally zero profiles
are active) will **not** activate BootUI under a bare `java -jar` launch unless a profile is explicitly activated
(`--spring.profiles.active=dev`, `SPRING_PROFILES_ACTIVE=dev`, or `spring-boot-devtools` on the classpath, which is
excluded from a repackaged jar by default). This is not WebFlux-specific — it reproduces identically on the servlet
sample app — but is easy to trip over when smoke-testing a freshly built reactive sample app jar by hand.

## 10. Future work

- A reactive Security advisor ruleset (`ServerHttpSecurity`/`SecurityWebFilterChain`), closing the
  `security`/`spring-security` gap in §6.5.
- A reactive-aware `BootUiMcpTools` catalog so the MCP Server panel and JSON-RPC bridge work on WebFlux.
- Deeper Live Activity correlation for requests with no active tracing span at all (today: trace-id-primary only,
  now matching the Quarkus adapter exactly since `Span.current()` is stamped unconditionally at every capture
  point — see §6.4).
