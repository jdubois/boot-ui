# BootUI Implementation Plan

## 1. Strategy

BootUI ships as a **Spring Boot 4 starter** that adds a safe, local-only developer console to a running application. The
released surface already covers runtime introspection, configuration, services, diagnostics, and developer tooling across
28 panels. This plan describes the **next feature workstream**: a focused set of six new capabilities chosen to close the
clearest gaps against comparable developer dashboards (Spring Boot Admin, Quarkus Dev UI, Laravel Telescope/Pulse,
Phoenix LiveDashboard, .NET Aspire, Symfony Web Profiler) while staying inside BootUI's read-mostly, fail-closed safety
model.

The priorities for every item below remain unchanged:

1. Safety and local-only operation.
2. Easy installation with no extra setup.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

Each new panel must:

- be **read-only or read-mostly**, with any mutating control explicitly confirmation-gated like the existing Cache clear
  action;
- **fail closed** when its required classes, Actuator endpoints, or data are unavailable, returning stable empty DTOs and
  a clear unavailable reason;
- route any sensitive property names, headers, or values through the existing masking and value-exposure model;
- ship with backend slice/edge-case tests, `/bootui/api/panels` availability wiring, docs, router ordering, and sample-app
  Playwright coverage in sync.

## 2. Scope of this workstream

Six features, grouped by priority. The first four are high-value, table-stakes panels found in competing dashboards but
missing from BootUI today. The last two enrich existing data rather than adding new sources.

| Priority | Feature                               | Group         | New data source                                    |
| -------- | ------------------------------------- | ------------- | -------------------------------------------------- |
| 1        | Thread / Process Viewer               | Runtime       | Actuator `threaddump` / `ThreadMXBean`             |
| 1        | HTTP Exchanges / Request History      | Diagnostics   | Actuator `httpexchanges`                           |
| 1        | Flyway / Liquibase Migrations         | Services      | Actuator `flyway` / `liquibase`                    |
| 1        | Audit / Security Events               | Services      | Actuator `auditevents`                             |
| 2        | Trace ↔ Log ↔ Request correlation     | Diagnostics   | Existing Traces, Log Tail, and HTTP Exchanges data |
| 2        | Bean / dependency graph visualization | Configuration | Existing Beans and Conditions data                 |

Items are intended to land roughly in the order listed. The HTTP Exchanges panel should land before the correlation work
in §3.5, because correlation depends on captured request data.

## 3. Feature specifications

### 3.1 Thread / Process Viewer (Runtime)

The largest gap versus Phoenix LiveDashboard and Spring Boot Admin. BootUI can capture a heap dump today but has no live
view of thread state. This panel surfaces a point-in-time snapshot of the JVM's threads.

Scope:

- Show a snapshot of live threads with name, id, state, priority, daemon flag, CPU/user time where available, and the
  current stack trace.
- Summarize counts by thread state (RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, etc.) and highlight the totals at the top
  of the panel.
- Detect and clearly flag deadlocks when the JVM reports them.
- Surface virtual-thread context where it can be detected, so users running Loom-style workloads see carrier vs. virtual
  thread information when available.
- Provide server-side filtering and bounded paging for high thread counts, matching the Beans panel approach.
- Offer an explicit, confirmation-gated download of the raw thread-dump snapshot for offline analysis.

Design constraints:

- Read-only. The panel must never suspend, interrupt, or kill threads.
- Prefer an internal `ThreadMXBean` / thread-dump invoker over requiring the host app to expose `threaddump` over HTTP.
- Stack frames may include in-flight argument-bearing class names; route any value-like content through the existing
  masking model and keep the panel inside the loopback-only and value-exposure controls.
- Fail closed with a stable empty DTO and an unavailable reason when thread information cannot be read.

### 3.2 HTTP Exchanges / Request History (Diagnostics)

Recorded request/response history is the centerpiece of Telescope, Symfony Web Profiler, and Django Debug Toolbar, yet
BootUI only has outbound HTTP Probe and Log Tail. This panel records recent inbound HTTP exchanges handled by the running
app.

Scope:

- List recent exchanges with timestamp, method, path, status, duration, and response size.
- Show per-exchange detail: request headers, query/path, selected request metadata, response headers, and timing.
- Provide server-side filtering (by method, status class, path fragment) and bounded paging.
- Expose a stable trace identifier per exchange where one is present, to support the correlation work in §3.5.

Design constraints:

- Read-only.
- Prefer Spring Boot's `HttpExchangeRepository` / `httpexchanges` data through an internal bridge rather than requiring the
  endpoint to be exposed over HTTP.
- Recorded exchanges can contain `Authorization`, `Cookie`, and other sensitive headers; mask them by default through the
  existing value-exposure model and never log raw secret headers.
- Request and response bodies stay out of scope by default; if added later they must be opt-in and masked.
- Capture is bounded (fixed-size ring buffer) and disabled cleanly when the underlying repository bean is absent, with a
  stable empty DTO and unavailable reason.

### 3.3 Flyway / Liquibase Migrations (Services)

Already a long-standing roadmap item and a common ask. Read-only visibility into database schema migration state.

Scope:

- Detect configured Flyway and/or Liquibase tools and list each one.
- Show current schema version, applied migrations (version, description, type, installed-on, execution time, success),
  and pending migrations where the tool exposes them.
- Surface validation/checksum state and clearly distinguish "applied", "pending", and "failed/out-of-order" entries.
- Render a clear degraded/empty state when neither tool is present.

Design constraints:

- Read-only at first. No migrate, repair, clean, baseline, or rollback actions in this iteration; if added later they must
  be confirmation-gated and disabled by default.
- Prefer the Actuator `flyway` / `liquibase` endpoint data through an internal bridge.
- Mask any sensitive datasource metadata (URLs, credentials) through the existing model.
- Fail closed per tool: an absent or inaccessible tool shows an unavailable reason, not an error.

### 3.4 Audit / Security Events (Services)

Pairs naturally with the existing Security panel and closes a gap versus Spring Boot Admin. Read-only view of recorded
security audit events.

Scope:

- List recent audit events with timestamp, principal, event type (for example authentication success/failure,
  authorization denied), and bounded event data.
- Provide server-side filtering by principal, type, and time window, with bounded paging.
- Summarize recent event counts by type at the top of the panel (for example failed logins in the current buffer).

Design constraints:

- Read-only.
- Prefer Spring Boot's `AuditEventRepository` / `auditevents` data through an internal bridge.
- Principal names and event detail can be sensitive; route them through the existing masking and value-exposure controls.
- Capture is bounded and the panel fails closed with a stable empty DTO and unavailable reason when no audit repository is
  present.

### 3.5 Trace ↔ Log ↔ Request correlation (Diagnostics)

This is where Aspire and Symfony differentiate. BootUI already owns a trace pipeline (the in-app OTLP sink and Traces
panel) plus Log Tail; once HTTP Exchanges (§3.2) lands, the three can be cross-linked by trace and span id.

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

### 3.6 Bean / dependency graph visualization (Configuration)

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
- Avoid heavy graph libraries where a lightweight approach is sufficient, in line with the bundle-size risk in §6.

## 4. Cross-cutting work for every new panel

For each feature above, the following must move together, consistent with the existing panel-registration process:

- Stable BootUI DTOs in `bootui-core` for all browser-facing responses.
- A `/bootui/api/**` controller (lazy-imported, internal-bridge first) plus panel registration and `/bootui/api/panels`
  availability wiring, including the disabled/unavailable sidebar state.
- A Vue 3 route and panel with empty/unavailable states, server-side filtering/paging where lists can be large, and the
  shared masking-aware rendering.
- Per-panel enable/disable and read-only properties, documented in `docs/PROPERTIES.md`.
- Backend slice and edge-case tests, frontend unit tests, and sample-app Playwright coverage.
- Documentation updates in `README.md`, `docs/FEATURES.md`, `docs/SPECIFICATION.md`, and screenshots at the project's
  standard size.

## 5. Explicitly out of scope

These were considered and deliberately deferred or rejected because they conflict with BootUI's safety stance or its
embedded single-app focus:

- In-browser file editing or a workspace (Quarkus Dev UI) and an arbitrary SQL query console (Telescope): both violate the
  read-mostly, no-SQL-execution, fail-closed model.
- A continuous test runner (Quarkus): large, and better served by the IDE/CI than a runtime console.
- Destructive migration, audit-store, or thread operations: not part of this iteration; only ever added later behind
  explicit confirmation and disabled-by-default gating.

## 6. Risks

| Risk                                                     | Impact | Mitigation                                                                                                                                 |
| -------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Exposing sensitive headers, principals, or stack values  | High   | Loopback-only, dev-only activation, masking/value-exposure on every new surface, fail-closed defaults, and focused per-panel tests.        |
| Unbounded capture buffers (exchanges, audit, threads)    | Medium | Fixed-size ring buffers, server-side paging, and bounded snapshots for every new data source.                                              |
| Optional Actuator endpoints/tools unavailable            | Medium | Internal bridges, classpath/bean gating, stable empty DTOs, and clear unavailable reasons per panel.                                       |
| Bean/dependency graph or correlation bloating the bundle | Medium | Bounded focus-and-neighborhood rendering, lightweight visualization, and lazy-loaded panels.                                               |
| Duplicating Spring Boot Admin                            | Medium | Stay focused on the embedded local single-app developer experience; keep new panels read-mostly and dev-only.                              |
| Scope creep beyond these six features                    | High   | Treat this list as the maximum near-term surface; move further ideas (messaging/queues, migrations actions, mail preview) to a later plan. |

## 7. Validation checklist

Run after each feature lands and before any release that includes it:

- [ ] `./mvnw -B -ntp clean install` passes.
- [ ] The UI build is executed automatically by Maven.
- [ ] The new panel loads and handles empty/unavailable data with a clear reason.
- [ ] The new panel masks sensitive values and respects the value-exposure mode.
- [ ] `/bootui/api/panels` reports the panel's availability and the sidebar dims it when unavailable.
- [ ] Server-side filtering/paging works for any high-cardinality list.
- [ ] Backend slice/edge-case tests, frontend unit tests, and sample-app Playwright coverage exist for the panel.
- [ ] `README.md`, `docs/FEATURES.md`, `docs/PROPERTIES.md`, and `docs/SPECIFICATION.md` describe the new surface, with
      screenshots at the standard size.
- [ ] BootUI stays disabled in `prod`/`production` unless `bootui.enabled=ON`, and non-local requests are rejected.
