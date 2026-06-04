# BootUI Implementation Plan

## 1. Strategy

BootUI ships as a **Spring Boot 4 starter** that adds a safe, local-only developer console to a running application. The
released surface already covers runtime introspection, configuration, services, diagnostics, and developer tooling. This
plan describes the **next feature workstream**: a focused set of five new capabilities chosen to close the clearest gaps
against comparable developer dashboards (Spring Boot Admin, Quarkus Dev UI, Laravel Telescope/Pulse,
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

Five features, grouped by priority. Items are intended to land roughly in the order listed. The first two are the
lowest-risk, highest-fit additions because they are pure introspection that mirror panels BootUI already has. The email
and session viewers add a new capture/inspection surface that needs careful masking. The GitHub integration is the most
valuable but also the only one requiring outbound network and credentials, so it lands last and ships read-only first.

| Priority | Feature                       | Group           | New data source                    | Mutation?         |
| -------- | ----------------------------- | --------------- | ---------------------------------- | ----------------- |
| 1        | Feature Flags (Togglz)        | Configuration   | Togglz `FeatureManager`            | Optional, gated   |
| 2        | Hibernate Scanner & Optimizer | Services        | Hibernate / JPA `Metamodel`        | No                |
| 3        | E-mail Viewer                 | Diagnostics     | Intercepted `JavaMailSender`       | No (capture only) |
| 4        | HTTP Session Viewer (Tomcat)  | Diagnostics     | Embedded Tomcat Catalina `Manager` | Optional, gated   |
| 5        | GitHub Integration            | Developer tools | Local git remote + GitHub REST API | No (phase 1)      |

## 3. Feature specifications

### 3.1 Feature Flags (Togglz) — Configuration

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

### 3.2 Hibernate Scanner & Optimizer — Services

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

### 3.4 HTTP Session Viewer (Tomcat) — Diagnostics

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

- Available only on embedded Tomcat; fail closed on Jetty, Undertow, Netty, or reactive setups with a clear "Tomcat
  only" reason.
- All attribute values flow through masking/value-exposure; bounded result size and server-side paging.
- Implemented defensively: access across the Tomcat handle is wrapped so a layout change degrades to a stable empty
  report rather than failing, the same posture as the ArchUnit importer.

### 3.5 GitHub Integration — Developer tools

A read-only dashboard for the **current** project, detected from the local git remote: repository metadata, open pull
requests with CI/check status, recent workflow runs, and headline stats (open issues/PRs, stars), plus an API
rate-limit indicator. Live metrics are bounded client-side polling. PR creation and other mutating actions are deferred;
if added later they must be confirmation-gated and disabled by default.

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

| Risk                                                   | Feature(s) | Impact | Mitigation                                                                                                  |
| ------------------------------------------------------ | ---------- | ------ | ----------------------------------------------------------------------------------------------------------- |
| Leaking session attributes, principals, or CSRF tokens | 3.4        | High   | Attribute names only by default, values behind value-exposure, bounded paging.                              |
| Leaking email recipients or bodies                     | 3.3        | High   | Mask by default, sandboxed HTML render, capture-only pass-through, fixed-size buffer.                       |
| Outbound network and token exposure                    | 3.5        | High   | Off by default, opt-in token never persisted or echoed, host allowlist, timeouts, unauthenticated fallback. |
| Silently swallowing application mail                   | 3.3        | Medium | Pass-through by default; "dev trap" mode strictly opt-in.                                                   |
| Server-portability assumptions (Tomcat only)           | 3.4        | Medium | Hard classpath/server gating, reflective access wrapped, fail closed off-Tomcat.                            |
| Unintended flag or session mutation                    | 3.1, 3.4   | Medium | Read-only by default; mutating actions confirmation-gated and dependent on a writable backend.              |
| Optional libraries/beans unavailable                   | all        | Medium | Classpath/bean gating, stable empty DTOs, and clear unavailable reasons per panel.                          |
| Scope creep beyond these five features                 | all        | High   | Treat this list as the maximum near-term surface; move further ideas to a later plan.                       |

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
