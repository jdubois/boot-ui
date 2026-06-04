# BootUI Implementation Plan

## 1. Strategy

BootUI ships as a **Spring Boot 4 starter** that adds a safe, local-only developer console to a running application. The
released surface already covers runtime introspection, configuration, services, diagnostics, and developer tooling,
including the recently shipped Thread / Process Viewer and HTTP Exchanges panels. This plan describes the **next merged
feature workstream**: it keeps the three current roadmap items and folds in the proposed new capabilities chosen to close
the clearest gaps against comparable developer dashboards (Spring Boot Admin, Quarkus Dev UI, Laravel Telescope/Pulse,
Phoenix LiveDashboard, .NET Aspire, Symfony Web Profiler) while staying inside BootUI's read-mostly, fail-closed safety
model.

The priorities for every item below remain unchanged:

1. Safety and local-only operation.
2. Easy installation with no extra setup.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

Each new panel must:

- be **read-only or read-mostly**, with any mutating control explicitly confirmation-gated like the existing Spring Cache
  clear action;
- **fail closed** when its required classes, beans, Actuator endpoints, or data are unavailable, returning stable empty
  DTOs and a clear unavailable reason;
- route any sensitive property names, headers, addresses, or values through the existing masking and value-exposure model;
- ship with backend slice/edge-case tests, `/bootui/api/panels` availability wiring, docs, router ordering, and sample-app
  Playwright coverage in sync.

## 2. Scope of this workstream

Eight features, grouped by priority. This list intentionally merges the three existing roadmap items with the proposed
additions instead of replacing either set. Items are intended to land roughly in the order listed: the existing migrations,
correlation, and graph items remain in scope; the new introspection panels slot next to related areas; capture-heavy,
server-specific, and outbound-network features land later because they need stricter masking, bounding, and opt-in
behaviour.

| Priority | Feature                               | Group           | Primary data source                                | Mutation?         | Origin           |
| -------- | ------------------------------------- | --------------- | -------------------------------------------------- | ----------------- | ---------------- |
| 1        | Flyway / Liquibase Migrations         | Database        | `Flyway` / `SpringLiquibase` beans                 | No                | Existing roadmap |
| 2        | Feature Flags (Togglz)                | Configuration   | Togglz `FeatureManager`                            | Optional, gated   | New addition     |
| 3        | Hibernate Scanner & Optimizer         | Services        | Hibernate / JPA `Metamodel`                        | No                | New addition     |
| 4        | Trace ↔ Log ↔ Request correlation     | Diagnostics     | Existing Traces, Log Tail, and HTTP Exchanges data | No                | Existing roadmap |
| 5        | Bean / dependency graph visualization | Configuration   | Existing Beans and Conditions data                 | No                | Existing roadmap |
| 6        | E-mail Viewer                         | Diagnostics     | Intercepted `JavaMailSender`                       | No (capture only) | New addition     |
| 7        | HTTP Session Viewer (Tomcat)          | Diagnostics     | Embedded Tomcat Catalina `Manager`                 | Optional, gated   | New addition     |
| 8        | GitHub Integration                    | Developer tools | Local git remote + GitHub REST API                 | No (phase 1)      | New addition     |

The Trace ↔ Log ↔ Request correlation work in §3.4 builds on the already-shipped HTTP Exchanges panel. The GitHub
integration is the only feature here requiring outbound network and credentials, so it lands last and ships read-only
first.

## 3. Feature specifications

### 3.1 Flyway / Liquibase Migrations (Database) — shipped

Already a long-standing roadmap item and a common ask. Read-only visibility into database schema migration state. A new
**Database** menu group (above Security) now hosts the existing Database Connection Pools and Spring Data panels alongside
the new Flyway and Liquibase panels.

Scope:

- Detect configured Flyway and/or Liquibase tools and list each one.
- Show current schema version, applied migrations (version, description, type, installed-on, execution time, success),
  and pending migrations where the tool exposes them.
- Surface validation/checksum state and clearly distinguish "applied", "pending", and "failed/out-of-order" entries.
- Render a clear degraded/empty state when neither tool is present.

Design constraints:

- Flyway `migrate`/`clean` and Liquibase `update` actions are confirmation-gated and blocked by the global/per-panel
  read-only state; Flyway clean also honors Flyway's own `clean-disabled` setting. No repair, baseline, rollback,
  Liquibase `dropAll`, or migration generation action is exposed yet.
- Bridge directly from the `Flyway` / `SpringLiquibase` beans in the context (equivalent to the Actuator `flyway` /
  `liquibase` endpoint data) without depending on the Actuator endpoints being exposed.
- Mask any sensitive datasource metadata (URLs, credentials) through the existing model.
- Fail closed per tool: an absent or inaccessible tool shows an unavailable reason, not an error.

### 3.2 Feature Flags (Togglz) — Configuration

Togglz is the de-facto Java feature-flag library; a flag panel is a natural BootUI fit that complements the existing
Configuration and Conditions panels. Togglz ships its own `/togglz-console`, but a BootUI panel keeps everything in the
single dev console: integrated, masking-aware, fail-closed, and dev-only.

Scope:

- List all features grouped by Togglz feature groups, showing enabled state, active activation strategy and its
  parameters, label, and attributes.
- Show the backing `StateRepository` type, whether it is writable, and the `FeatureManager` name (multi-manager setups
  handled).
- A **toggle action is optional and confirmation-gated**, disabled by default behind the per-panel read-only property,
  and offered only when the `StateRepository` is writable — consistent with the Spring Cache clear precedent.

Design constraints:

- Available only when `togglz-core` is on the classpath **and** a `FeatureManager` bean exists; otherwise fail closed
  with a clear unavailable reason.
- Route strategy parameter values through masking/value-exposure (they can embed user ids, percentages, allow-lists).
- No dependency on the Togglz web console being enabled.

### 3.3 Hibernate Scanner & Optimizer — Services

A directly requested scanner that works like the other scanners in the starter, most closely the Architecture panel: it
runs on demand, is bounded to the host application's own entities, evaluates a curated registry of severity-ranked rules,
fails closed to a stable empty report, and is framed as a **review prompt, not a verdict**. BootUI ships an independent,
open implementation of these rules, following well-known Hibernate/JPA performance best practices, with "learn more"
links out to the relevant articles. No third-party optimizer product is bundled or required.

Data source: the JPA `EntityManagerFactory` / Hibernate `SessionFactory` **Metamodel** plus persistence configuration —
static mapping introspection bounded to the application's own entities, not runtime query interception. This keeps the
scan bounded and safe in the same way the ArchUnit scanner is.

Candidate rule set (each with a severity and a remediation link):

- `FetchType.EAGER` on associations (`@ManyToOne`/`@OneToOne`/`@ManyToMany`/`@OneToMany`) → prefer `LAZY`.
- `GenerationType.IDENTITY` identifiers → disables JDBC batch inserts; prefer `SEQUENCE` with a pooled optimizer.
- Unidirectional `@OneToMany` without `@JoinColumn`/`mappedBy` → extra join table / inefficient DML.
- `@ManyToMany` using `List` instead of `Set` → delete-and-reinsert problem.
- `@Enumerated(ORDINAL)` → fragile; prefer `STRING`.
- `open-in-view` enabled (`spring.jpa.open-in-view=true`) → warn about lazy loading outside transactions.
- Missing batch-fetch configuration (`hibernate.default_batch_fetch_size` / `@BatchSize`) → N+1 risk (informational).
- Risky `ddl-auto` (`update`/`create`/`create-drop`) outside test profiles (informational).
- Entity `equals`/`hashCode` hygiene where statically detectable (informational).

Design constraints:

- Read-only. Available only when Hibernate ORM and an `EntityManagerFactory` bean are present; degrade to a stable empty
  report when the metamodel cannot be read, mirroring the ArchUnit scanner's fail-closed behaviour.
- Reuse the architecture-style report DTO shape (analyzer header, severity counts, sorted violations, disclaimer) so the
  frontend and test scaffolding are largely reused.
- A companion `docs/HIBERNATE-CHECKS.md` (like `docs/ARCHITECTURE-CHECKS.md`) documents every rule.

### 3.4 Trace ↔ Log ↔ Request correlation — Diagnostics

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

### 3.5 Bean / dependency graph visualization — Configuration

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

### 3.6 E-mail Viewer — Diagnostics

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

### 3.7 HTTP Session Viewer (Tomcat) — Diagnostics

Visibility into active HTTP sessions is useful during local authentication and state debugging. This panel targets
**embedded Tomcat only**.

Scope:

- Reach the Catalina `Manager` via the running `WebServerApplicationContext` → `TomcatWebServer` → `Context` →
  `Manager.findSessions()`, reading session id, creation and last-access time, max-inactive interval, and attribute
  **names**. Bounded snapshot.
- List active sessions with their timing metadata and attribute names; attribute **values** are hidden by default
  because they routinely hold auth principals, CSRF tokens, and security context.
- An **invalidate-session action is optional and confirmation-gated**, disabled by default behind the read-only property.

Design constraints:

- Available only on embedded Tomcat; fail closed on Jetty, Undertow, Netty, or reactive setups with a clear "Tomcat only"
  reason.
- All attribute values flow through masking/value-exposure; bounded result size and server-side paging.
- Implemented defensively: access across the Tomcat handle is wrapped so a layout change degrades to a stable empty report
  rather than failing, the same posture as the ArchUnit importer.

### 3.8 GitHub Integration — Developer tools

A read-only dashboard for the **current** project, detected from the local git remote: repository metadata, open pull
requests with CI/check status, recent workflow runs, and headline stats (open issues/PRs, stars), plus an API rate-limit
indicator. Live metrics are bounded client-side polling. PR creation and other mutating actions are deferred; if added
later they must be confirmation-gated and disabled by default.

Data source:

- Repo detection (no network): parse `owner/repo` from `.git/config` / `git remote get-url origin`; activate only for
  GitHub remotes (github.com or a configured GitHub Enterprise host).
- GitHub REST API (outbound, opt-in): pull requests, checks/statuses, workflow runs, repo stats, and `/rate_limit`.
  Outbound HTTP has precedent in the OSV Vulnerabilities scanner.

Credentials and network safety:

- Token discovery is opt-in and read-only: environment `GITHUB_TOKEN`/`GH_TOKEN` or an existing `gh` CLI login. The token
  is never persisted, never echoed, and shown masked. Without a token, fall back to unauthenticated calls (60 req/hr) and
  surface the rate-limit state clearly.
- Outbound calls are off by default, triggered by an explicit Connect/Refresh action, with a configurable host allowlist
  (`api.github.com` / GitHub Enterprise) and request timeouts.

Design constraints:

- Available only when the working tree has a GitHub `origin`. Degrade with a clear reason when the project is not a git
  repository, has a non-GitHub remote, is offline, hits an unauthenticated private repository, or is rate-limited.
- Bounded result counts and refresh interval; reuse masking for any user-identifying fields.
- Phase 1 is a read-only dashboard only; anything mutating (PR creation, labels, merges) is a later, confirmation-gated
  iteration.

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

| Risk                                                               | Feature(s)     | Impact | Mitigation                                                                                                  |
| ------------------------------------------------------------------ | -------------- | ------ | ----------------------------------------------------------------------------------------------------------- |
| Exposing sensitive headers, principals, session data, or mail body | 3.4, 3.6, 3.7 | High   | Loopback-only activation, masking/value-exposure on every new surface, sandboxed HTML, and focused tests.   |
| Leaking datasource or migration metadata                           | 3.1            | Medium | Prefer Actuator bridge data, mask datasource details, and degrade per tool with unavailable reasons.        |
| Outbound network and token exposure                                | 3.8            | High   | Off by default, opt-in token never persisted or echoed, host allowlist, timeouts, unauthenticated fallback. |
| Unbounded capture buffers or large rendered graphs/lists           | 3.3, 3.5-3.7  | Medium | Fixed-size buffers, server-side paging, bounded snapshots, and focus-and-neighborhood graph rendering.      |
| Optional Actuator endpoints, libraries, beans, or servers missing  | all            | Medium | Internal bridges, classpath/bean gating, stable empty DTOs, and clear unavailable reasons per panel.        |
| Bean/dependency graph or correlation bloating the bundle           | 3.4, 3.5      | Medium | Bounded rendering, lightweight visualization, and lazy-loaded panels.                                       |
| Silently swallowing application mail                               | 3.6            | Medium | Pass-through by default; "dev trap" mode strictly opt-in.                                                   |
| Unintended flag or session mutation                                | 3.2, 3.7      | Medium | Read-only by default; mutating actions confirmation-gated and dependent on a writable backend.              |
| Scope creep beyond this merged feature set                         | all            | High   | Treat this list as the maximum near-term surface; move further ideas to a later plan.                       |

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
