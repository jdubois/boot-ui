# BootUI on Spring WebFlux — support status

## 1. Goal

Support Spring WebFlux (reactive, Netty/`DispatcherHandler`) Spring Boot 4 applications as a first-class BootUI
target alongside the existing Spring MVC (servlet) adapter and the Quarkus adapter: the same shared engine
(`bootui-engine`/`bootui-core`), the same Vue UI, the same `/bootui/api/**` contract, panel parity wherever a
reactive analog genuinely exists, and an honest "not yet ported" / "not applicable" status where it doesn't.

## 2. Current status

The WebFlux adapter serves the large majority of the panel surface — the same 47-panel manifest the servlet adapter
reports, minus the five panels that stay unavailable for stack reasons described below. **Every action-capable panel
that is available behaves identically to the servlet adapter**, behind the same shared `LocalhostGuard` write floor:
Loggers (set level), HTTP Probe, Cache (clear), Flyway (migrate/clean), Liquibase (update), Heap Dump
(capture/analyze/delete/download), Threads (download), Traces (clear), SQL Trace (toggle recording/clear), the
advisor scans (Architecture, Spring, Hibernate, Pentesting, REST API, Memory, Vulnerabilities/OSV), and Exceptions
triage.

Only **HTTP Sessions**, the **Security advisor**, the raw **Spring Security** panel, **MCP Server**, and **Live
Activity** stay unavailable, each with a panel-specific reason surfaced through the `/bootui/api/panels` manifest (and,
in turn, the sidebar tooltip and the panel's own alert banner — see §5). `docs/FEATURES.md` and the per-panel
`unavailableReason` strings in `PanelsController` are the authoritative, current detail.

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
  five panels that diverge (§6). The Vue UI already reads this manifest via `inject('panels')` and renders the
  `unavailableReason` in both the sidebar tooltip and the panel's own alert banner — no `.vue` file needed to change.

## 6. Panel disposition

`Port` = ships from shared code, zero adapter changes needed · `Adapt` = reuses the shared engine over a small new
reactive binding (e.g. a `WebFilter` capturing into the same engine store) · `Rebuild` = a genuinely new reactive
capture layer replacing a servlet-only primitive · `Not yet ported` = deliberately deferred, no reactive
implementation wired yet · `Not applicable` = no faithful reactive analog exists for this panel's concept.

### 6.1 Ported as-is (35 panels)

Bulk-imported from the servlet adapter's `@RestController`s with no code changes at all — confirming these
controllers were already framework-neutral in practice, not just in the engine underneath them:

Overview · GitHub · Beans · Conditions · Configuration · Mappings · Health · Loggers · Startup Timeline · Spring Data ·
Hibernate · Flyway · Liquibase · Database Connection Pools · Cache · Dev Services · Vulnerabilities · Scheduled Tasks ·
HTTP Probe · Pentesting · Heap Dump · Architecture · REST API advisor · Profile Diff · Spring advisor · Live Memory ·
JVM Tuning · Metrics · DevTools · Traces · AI Usage · GraalVM · CRaC · Threads · Memory.

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

### 6.4 Not yet ported (3 panels)

| Panel          | Reason                                                                                                                                                                                        |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Spring Security (raw panel, `spring-security`) | *"Not yet ported for Spring WebFlux: this advisor analyzes the servlet SecurityFilterChain/HttpSecurity configuration model, which has no reactive equivalent wired here yet (a ServerHttpSecurity/SecurityWebFilterChain ruleset is planned)."* `springSecurityAvailable()` now requires `!isReactive()` in addition to the pre-existing classpath/bean checks. |
| MCP Server              | *"Not yet ported for Spring WebFlux: the MCP tool catalog is hard-wired to the servlet panel controllers, so it cannot yet resolve the reactive panel surface."* The JSON-RPC bridge itself (`McpDispatcher`) is already framework-neutral; only `BootUiMcpTools`' tool catalog needs a reactive-aware rewrite. |
| Live Activity           | *"Not yet ported for Spring WebFlux: Live Activity aggregates the servlet-only ServletRequestHandledEvent signal, which has no reactive equivalent wired here yet."* A reactive request-capture source (a `WebFilter` recording into the shared `ActivityStore`, mirroring the Quarkus adapter's Vert.x-filter approach) is the natural follow-up. |

Note: **the Security advisor** (`security`, grouped under Advisors — distinct from the raw **Spring Security**
configuration panel above, grouped under Security) also stays unavailable on WebFlux, but needed no dedicated
reactive-aware string: `securityAvailable()` checks for a `FilterChainProxy` bean (the servlet security filter,
`extends GenericFilterBean`), while a reactive Spring Security setup only ever registers a `WebFilterChainProxy` bean
(`implements WebFilter`, package `org.springframework.security.web.server`) — two unrelated types in the same
`spring-security-web` jar. So the existing check already resolves `false` on WebFlux by construction, and the panel
falls through to its pre-existing generic reasons ("Spring Security not on the classpath" / "No Spring Security
filter chains are available") rather than a WebFlux-specific one. A reactive ruleset for this advisor is a genuinely
new advisor (comparable in scope to the from-scratch Quarkus Security ruleset), deliberately deferred to a follow-up.

### 6.5 Not applicable (1 panel)

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
though the reactive autoconfiguration never wires their controllers. Fixed alongside the other divergent-panel
availability checks in §6.4.

Both were caught by `WebFluxApiConformanceTest`'s inherited `availablePanelsAnswerTheirPrimaryGet()` assertion (see
§8) — direct evidence for why the conformance suite runs against a real, minimal, single-stack sample app rather than
relying on unit tests against a shared multi-stack test classpath alone.

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
  representative sample of ported panels rendering cleanly, and that `http-sessions`, `spring-security`, `mcp-server`,
  and `activity` each show their WebFlux-specific reason in both the sidebar and the panel alert (the `security`
  advisor's equivalent reason is covered at the unit level by `PanelsControllerTests`, not re-asserted in e2e).
- Run it: see the "WebFlux (reactive) smoke suite" section of `bootui-spring-sample-app/e2e/README.md`.

## 9. Operational note: profile activation

`BootUiActivationCondition` checks `Environment.getActiveProfiles()`, not `getDefaultProfiles()`. An application
whose `application.properties` sets `spring.profiles.default=dev` (a default, only used when literally zero profiles
are active) will **not** activate BootUI under a bare `java -jar` launch unless a profile is explicitly activated
(`--spring.profiles.active=dev`, `SPRING_PROFILES_ACTIVE=dev`, or `spring-boot-devtools` on the classpath, which is
excluded from a repackaged jar by default). This is not WebFlux-specific — it reproduces identically on the servlet
sample app — but is easy to trip over when smoke-testing a freshly built reactive sample app jar by hand.

## 10. Future work

- A reactive Security advisor ruleset (`ServerHttpSecurity`/`SecurityWebFilterChain`), closing the
  `security`/`spring-security` gap in §6.4.
- A reactive-aware `BootUiMcpTools` catalog so the MCP Server panel and JSON-RPC bridge work on WebFlux.
- A `WebFilter`-based request-capture source for Live Activity, mirroring the Quarkus adapter's Vert.x-filter
  approach, including trace-id correlation if `micrometer-tracing`/OTLP is present.
