# Changelog

All notable changes to BootUI are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-06-02

Third BootUI release, focused on the new JVM Tuning Advisor, Java 17 baseline, stronger AI telemetry guidance, and
release-facing documentation/screenshots for the updated menu surface.

### Added

- Tuning Advisor panel split out from Memory, with fixed bare-metal JVM options, percentage-based Kubernetes
  `JAVA_TOOL_OPTIONS`, optional Burstable request sizing, Actuator probe YAML, and virtual-thread sizing guidance based on
  the running JVM context.
- LangChain4j support in the AI Usage panel. BootUI now detects Spring AI and/or LangChain4j, shows the selected
  framework with header badges, and offers side-by-side Spring AI and LangChain4j telemetry setup guides explaining the
  dependency and configuration each needs to emit GenAI spans (including optional prompt/completion content capture).
- Health panel setup guidance: a disabled state with guidance when no Actuator `HealthEndpoint` is available, and
  guidance (without changing reported statuses) when a health tree contains only Spring Boot's default indicators.
- Frontend test coverage for the shared auto-refresh and refresh-state utilities, the Health view, and the panel header
  component.

### Changed

- ArchUnit is now bundled transitively through `bootui-spring-boot-starter`, so the Architecture panel works out of the
  box without an extra application dependency; the sample app's redundant direct dependency was removed.
- Architecture checks were expanded with additional coding-practice and Spring proxy/stereotype heuristics.
- Pentesting checks now align their local-only hygiene catalogue with OWASP Top 10 2025.
- Release preparation (Maven module versions, README install snippet, release commit, and tag) and Maven Central
  publishing are unified into a single `Release` workflow, replacing the separate `Prepare Release` workflow.
- Lowered the build baseline from Java 25 to Java 17, updating the Maven compiler release, the CI build matrix, and
  CodeQL analysis.
- The Data menu item is now Spring Data, and the database pool view is consistently named Database Connection Pools.
- AI Usage content-capture guidance and documentation now cover LangChain4j alongside Spring AI.
- Copilot and Claude Code agent panels now share the standard panel refresh behavior.

### Fixed

- Removed auto-refresh flicker by showing panel skeletons only on first load and sharing refresh state across panels.
- Normalized frontend errors when the backend is offline or unavailable.
- Removed duplicated `formatDuration`/`formatTime` helpers in the Traces panel in favor of the shared format utilities.
- Updated the footer GitHub link, release docs, and regenerated feature screenshots for the current sidebar menu.

## [0.2.0] - 2026-06-01

Second BootUI release, focused on local security diagnostics, three new local-only diagnostics panels — Architecture
(ArchUnit), Heap Dump (value-free class histogram), and Database Connection Pools — and safer defaults around
host-application security, plus release/documentation hardening for the full visible panel surface.

### Added

- Architecture panel that runs a curated, zero-config ArchUnit ruleset against the host application's own classes for
  package-cycle, coding-practice, and Spring-stereotype hygiene, with an on-demand scan and the latest report.
- Heap Dump panel that captures local JVM heap dumps on demand and analyzes a value-free class histogram, including a
  `max-classes` memory cap plus big-objects and collection-bloat smart filters. Raw `.hprof` download stays disabled by
  default because dumps contain plaintext secrets.
- Database Connection Pools panel that surfaces read-only database pool sizing, masked JDBC metadata, and a live
  active/idle/total/pending saturation chart, failing closed when pool support is unavailable.
- Pentesting panel with explicit, local-only OWASP-aligned hygiene checks for security headers, CORS behavior, cookie
  flags, verbose errors, Spring Security wiring, actuator exposure, DevTools, H2 console, and risky configuration values.
- BootUI Spring Security integration that keeps `/bootui/**` and `/bootui/api/**` reachable in local applications using
  Spring Security while preserving the localhost-only safety filter.
- Automatic local application trace capture so Traces and AI Usage can populate from the host app without requiring a
  separate local OTLP exporter setup.
- CI test report publishing for Maven/JUnit and Playwright runs.
- Playwright end-to-end coverage for the Pentesting panel.

### Changed

- Monitoring panels now hide BootUI's own beans, mappings, loggers, metrics, traces, and related runtime data by default
  through `bootui.monitoring.exclude-self=true`.
- Request-driven BootUI controllers and agent session stores are lazy-loaded, and agent session parsing is bounded to
  avoid unnecessary startup work.
- Vulnerability findings are sorted by severity/importance first, with stable ordering inside each severity group.
- The app shell, panel headers, skeleton states, auto-refresh controls, and command palette/navigation were polished for a
  faster and more consistent UI.
- Architecture, Pentesting, and Vulnerabilities now share clearer scan status messaging.
- Startup Timeline configuration, panel read-only controls, application property reference docs, pentest catalogue docs,
  feature docs, screenshots, and E2E documentation were reconciled with the implemented `0.2.0` behavior.
- Refreshed and reorganized `SECURITY.md`.
- Regenerated feature screenshots and extended the docs screenshot script to cover the Architecture, Heap Dump, and
  Database Connection Pools panels.

### Fixed

- Fixed sample-app and BootUI security audit findings, including enabling CSRF protection in the sample app.
- Fixed hidden-BootUI-internals assumptions in Beans E2E coverage after self-data filtering became the default.
- Removed duplicated panel headings and restored Overview/Metrics heading behavior.
- Fixed BootUI navigation controls, including theme persistence and command palette shortcut behavior.
- Fixed the Claude Code sidebar icon.
- Added registry-level coverage so global read-only mode is checked for every action-capable panel.

## [0.1.0] - 2026-05-29

First final BootUI release. This promotes the alpha line to the final `0.1.x` coordinate while keeping the local-only,
developer-console safety model and the full visible panel surface.

### Added

- Copilot and Claude Code panels for sanitized local activity dashboards, including session summaries, activity trends,
  tool and model usage, failures, and bounded live refresh behavior.
- Vitest, Vue Test Utils, and jsdom coverage for reusable frontend behavior, wired into the Maven test phase.

### Changed

- README, feature documentation, the release plan, and generated feature screenshots are aligned with the final `0.1.0`
  panel surface and install coordinates.
- Beans, Conditions, Mappings, Configuration, and Loggers use bounded server-side filtering and pagination for
  high-cardinality applications.
- The full visible route set is promoted from the supported alpha surface to the supported `0.1.0` release surface.

### Fixed

- Dev Services discovery and controls handle prototype-scoped Testcontainers beans, stopped containers, null log output,
  restart failures, metadata-only connection detail masking, and abstract bean definitions more defensively.

## [0.1.0-alpha.5] - 2026-05-27

Latest tagged alpha with the expanded panel surface, telemetry features, and release hardening.

### Added

- Backend test coverage for `BootUiProperties` binding, additional activation rules
  (devtools activation, custom disabled profiles, invalid `bootui.enabled` failing closed),
  controller mappings and DTO serialization for every `/bootui/api/**` endpoint,
  Config controller HTTP CRUD with masking modes and restart warnings, logger level
  mutation/clear, broader secret masking coverage, and panel edge cases
  (Data, Scheduled, HTTP Probe, Log Tail, Profile Diff, Security, Metrics, DevTools,
  Dev Services, Memory).
- `CHANGELOG.md` and a sample-app walkthrough at `bootui-sample-app/README.md`.
- Spring Cache panel for cache managers, known caches, safe local sizes, Micrometer cache metrics,
  cache annotations, and confirmation-gated clear actions.
- Embedded OTLP/HTTP trace receiver at `/bootui/api/otlp/v1/traces`, plus Traces and AI Usage panels
  for local trace waterfalls, Spring AI observations, token usage, tool calls, and bounded in-memory
  telemetry.
- Optional panel availability metadata so the sidebar can dim panels whose backing classpath,
  Actuator endpoint, or local infrastructure is unavailable.
- GitHub project links in the UI and sample-app AI prompt helpers for exercising telemetry locally.

### Changed

- Documentation reconciled with the implemented `AUTO|ON|OFF` activation model,
  persisted runtime overrides, plain-JavaScript Vue 3 frontend, and the full
  visible panel set as supported alpha functionality.
- Dev Services, Vulnerabilities, Traces, and AI Usage now have stronger empty/disabled states,
  bounded data handling, and focused Playwright coverage.
- Vulnerability scan results are retained in memory after an explicit scan so the panel can keep
  showing the latest local results.
- Sample app PostgreSQL JDBC driver updated to 42.7.11.
- Repository formatting checks and release documentation now cover the alpha release workflow,
  README version synchronization, and Maven Central signing constraints.

### Fixed

- Corrected the sample Redis service port mapping used by the Cache panel tests.
- Fixed GitHub code scanning workflow permissions and an incomplete string escaping/encoding finding.

### Security

- Enabled CSRF protection in the sample app.

## [0.1.0-alpha.4] - 2026

First successful Maven Central publication of the alpha line.

### Fixed

- Source-less modules (`bootui-ui`, `bootui-spring-boot-starter`) now attach an
  empty `javadoc.jar` at the `package` phase so the release-profile `gpg-sign`
  binding (running at `verify`) signs it. Without this, Sonatype Central
  rejected the deployment.

## [0.1.0-alpha.3] - 2026

Release attempt blocked by missing javadoc signatures (see alpha.4 fix).

## [0.1.0-alpha.2] - 2026

Initial public alpha attempt. Sonatype Central deployment failed; subsequent
attempts re-used the GAV coordinate and required version bumps because
Sonatype consumes a coordinate even on failure.

## [0.1.0-alpha.1] - 2026

First tagged BootUI alpha. Highlights of the harden-all-visible-panels scope:

### Added

- Spring Boot 4 starter (`bootui-spring-boot-starter`) and auto-configuration
  (`bootui-autoconfigure`) packaged with a Vue 3 / Vite UI shell served from
  `/bootui` and `/bootui/api/**`.
- `bootui.enabled=AUTO|ON|OFF` activation model with profile-based enablement
  (`dev`, `local`) and disablement (`prod`, `production`); fail-closed on
  invalid values; auto-activation when Spring Boot DevTools is on the classpath.
- Localhost-only safety filter with explicit `bootui.allow-non-localhost` opt-out.
- Secret-masking for browser-visible property names and values, with three
  exposure modes: `MASKED` (default), `METADATA_ONLY`, and `FULL`.
- Runtime configuration overrides persisted to
  `.bootui/application-bootui.properties` and applied at high precedence on the
  next start; restart/rebind caveats surfaced for every override mutation.
- Internal Actuator bridge that returns stable BootUI DTOs even when the
  underlying Actuator endpoint or Spring module is absent.
- Panels: Overview, Beans, Conditions, Configuration, Mappings, Health, Loggers,
  Startup Timeline, JVM Memory (with suggested options), Spring Data,
  Scheduled Tasks, HTTP Probe (loopback-only), Log Tail, Profile Diff,
  Spring Security, Micrometer Metrics, Dependency inventory + OSV vulnerability
  scan, Spring Boot DevTools reload/restart, and Dev Services for Docker Compose,
  Testcontainers beans, and service connection metadata.
- Sample application (`bootui-sample-app`) and a Playwright end-to-end suite
  exercising every visible browser route.

### Notes

- Spring Boot 3.x support, Gradle plugin, CLI, extension SPI, hosted features,
  request history, distributed tracing, multi-service orchestration, and live
  Docker Compose lifecycle control are intentionally out of scope for the alpha.

[Unreleased]: https://github.com/jdubois/boot-ui/compare/v0.3.0...HEAD

[0.3.0]: https://github.com/jdubois/boot-ui/compare/v0.2.0...v0.3.0

[0.2.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0...v0.2.0

[0.1.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0-alpha.5...v0.1.0

[0.1.0-alpha.5]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.5

[0.1.0-alpha.4]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.4

[0.1.0-alpha.3]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.3

[0.1.0-alpha.2]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.2

[0.1.0-alpha.1]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.1
