# Changelog

All notable changes to BootUI are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.20.0] - 2026-10-05

BootUI 1.20.0 makes request evidence easier to hand to agents and easier to trust in the UI. It adds richer request
profiles, route rankings, code locations, broader database support, advisor audits, stronger vulnerability reporting,
and stricter value exposure.

### Added

- **Agent-ready request profiles and Copy for AI.** MCP, CLI, REST, Live Activity, and Exceptions now export the same
  masked profile, with Copy for AI and REST client/cache evidence included
  ([#1192](https://github.com/jdubois/boot-ui/pull/1192), [#1148](https://github.com/jdubois/boot-ui/pull/1148)).
- **Route rankings and finding locations.** HTTP Exchanges ranks retained routes by traffic, latency, and errors, while
  advisor findings show copyable source locations ([#1152](https://github.com/jdubois/boot-ui/pull/1152),
  [#1149](https://github.com/jdubois/boot-ui/pull/1149)).
- **Failure-preserving retention.** HTTP Exchanges, SQL Trace, REST Client, and durable Live Activity persistence now
  reserve capacity for failed and slow records by default ([#1153](https://github.com/jdubois/boot-ui/pull/1153),
  [#1154](https://github.com/jdubois/boot-ui/pull/1154)).
- **MySQL and MariaDB reach.** The MySQL panel now reads every Oracle MySQL version and best-effort MariaDB behind MySQL
  Connector/J ([#1191](https://github.com/jdubois/boot-ui/pull/1191),
  [#1194](https://github.com/jdubois/boot-ui/pull/1194)).
- **New REST API, Architecture, and Database rules.** Runtime-breaking handlers, ignored injection/lifecycle
  annotations, and schema or Hibernate mapping risks gained checks
  ([#1168](https://github.com/jdubois/boot-ui/pull/1168), [#1165](https://github.com/jdubois/boot-ui/pull/1165),
  [#1169](https://github.com/jdubois/boot-ui/pull/1169)).

### Changed

- **Advisor catalog audits.** Spring, CRaC, Hibernate, GraalVM, Security, Quarkus, Memory, and Pentesting audits added
  checks, retired noisy rules with IDs preserved, and recalibrated severities
  ([#1164](https://github.com/jdubois/boot-ui/pull/1164), [#1170](https://github.com/jdubois/boot-ui/pull/1170),
  [#1172](https://github.com/jdubois/boot-ui/pull/1172), [#1171](https://github.com/jdubois/boot-ui/pull/1171),
  [#1173](https://github.com/jdubois/boot-ui/pull/1173), [#1167](https://github.com/jdubois/boot-ui/pull/1167),
  [#1162](https://github.com/jdubois/boot-ui/pull/1162), [#1166](https://github.com/jdubois/boot-ui/pull/1166)).
- **Vulnerabilities severity.** CVSS v4.0 now drives severity when present, and OpenSSF malicious-package advisories are
  CRITICAL with removal guidance ([#1163](https://github.com/jdubois/boot-ui/pull/1163)).
- **Shared request defaults.** WebFlux and Quarkus now honor the 1,000 ms slow threshold, `0` disables slow everywhere,
  and Spring stops recording BootUI's own requests while self-exclusion is on
  ([#1153](https://github.com/jdubois/boot-ui/pull/1153)).
- **Consistent profile and route evidence.** All adapters use the shared profile assembler, one slowest-request KPI, and
  consistent route labels ([#1148](https://github.com/jdubois/boot-ui/pull/1148),
  [#1152](https://github.com/jdubois/boot-ui/pull/1152)).
- **Release artifacts and dependencies.** Maven Central releases publish placeholder Javadoc jars, Quarkus moves to
  3.33.3.3, and notable frontend/build dependencies were refreshed
  ([#1190](https://github.com/jdubois/boot-ui/pull/1190), [#1135](https://github.com/jdubois/boot-ui/pull/1135)).

### Fixed

- **Quarkus runtime safety.** HTTP and exception capture can no longer fail requests after response end, and
  OpenTelemetry logs with Dev Services start again ([#1203](https://github.com/jdubois/boot-ui/pull/1203),
  [#1204](https://github.com/jdubois/boot-ui/pull/1204)).
- **Advisor and vulnerability accuracy.** Fixes reduce false positives, ignore test-only SBOM libraries, and avoid
  overclaiming damaged Quarkus dependency models ([#1178](https://github.com/jdubois/boot-ui/pull/1178),
  [#1179](https://github.com/jdubois/boot-ui/pull/1179), [#1180](https://github.com/jdubois/boot-ui/pull/1180),
  [#1181](https://github.com/jdubois/boot-ui/pull/1181), [#1163](https://github.com/jdubois/boot-ui/pull/1163),
  [#1164](https://github.com/jdubois/boot-ui/pull/1164), [#1168](https://github.com/jdubois/boot-ui/pull/1168),
  [#1169](https://github.com/jdubois/boot-ui/pull/1169)).
- **Persistence and diagnostics reliability.** Live Activity persistence works on MySQL and Oracle, sample-app call
  sites appear, and Log Tail streams release resources correctly ([#1144](https://github.com/jdubois/boot-ui/pull/1144),
  [#1143](https://github.com/jdubois/boot-ui/pull/1143), [#1120](https://github.com/jdubois/boot-ui/pull/1120),
  [#1150](https://github.com/jdubois/boot-ui/pull/1150)).

### Security

- **Value-exposure policy.** AI chat details, traces, request profiles, Log Tail, and Dev Services logs now apply the
  live exposure policy instead of exposing raw captured values ([#1210](https://github.com/jdubois/boot-ui/pull/1210),
  [#1205](https://github.com/jdubois/boot-ui/pull/1205), [#1150](https://github.com/jdubois/boot-ui/pull/1150)).
- **Authorization masking.** Log, exception, and container-log masking now hides credentials after Authorization-like
  schemes while preserving harmless scheme names ([#1150](https://github.com/jdubois/boot-ui/pull/1150)).

## [1.19.0] - 2026-09-25

Maintenance release focused on safer, more accurate diagnostics: JVM values are masked consistently, SQL timings gain
microsecond precision, advisor partial scans explain missing evidence, and several false positives are reduced.

### Changed

- **Advisor severity declarations match emitted results.** HIB-QUERY-007 and SEC-CORS-006 now declare the severities
  they already emitted, so catalog metadata aligns with findings and scores stay unchanged
  ([#1098](https://github.com/jdubois/boot-ui/pull/1098), [#1100](https://github.com/jdubois/boot-ui/issues/1100)).

### Fixed

- **JVM argument secrets are masked.** JVM Tuning and Live Memory now apply the exposure policy across Spring MVC,
  WebFlux, Quarkus, REST, MCP, and CLI output, including OnError commands and METADATA_ONLY JVM options
  ([#1113](https://github.com/jdubois/boot-ui/issues/1113)).
- **Hibernate Advisor partial scans are explainable.** Reports now include bounded diagnostics and coverage notes, and
  the Hibernate and Database panels show missing evidence without hiding affected rules
  ([#1086](https://github.com/jdubois/boot-ui/issues/1086)).
- **SQL Trace keeps sub-millisecond work visible.** Statement durations and aggregates now use microseconds, while the
  slow-query threshold keeps its millisecond semantics ([#1093](https://github.com/jdubois/boot-ui/issues/1093)).
- **Generated-code and Spring-executor checks are quieter.** OpenAPI `ApiUtil` and Spring-owned executors no longer
  produce misleading findings ([#1085](https://github.com/jdubois/boot-ui/issues/1085),
  [#1083](https://github.com/jdubois/boot-ui/issues/1083)).
- **Hibernate and database advisor unknowns are reduced.** STRING enums, default joins, PostgreSQL unique indexes,
  duplicate/special indexes, UUID foreign keys, and named index unknowns are assessed more accurately
  ([#1090](https://github.com/jdubois/boot-ui/issues/1090), [#1087](https://github.com/jdubois/boot-ui/issues/1087),
  [#1088](https://github.com/jdubois/boot-ui/issues/1088), [#1092](https://github.com/jdubois/boot-ui/issues/1092),
  [#1089](https://github.com/jdubois/boot-ui/issues/1089), [#1091](https://github.com/jdubois/boot-ui/issues/1091)).
- **Vulnerability coverage recognizes first-party Spring archives.** Application module JARs, exploded layer indexes,
  and `spring-boot-jarmode-tools` no longer make coverage look incomplete when identifiable locally
  ([#1084](https://github.com/jdubois/boot-ui/issues/1084)).

## [1.18.0] - 2026-09-21

Feature release adding PostgreSQL and MySQL operational diagnostics, retained advisor violation pages, and a Claude Code
plugin, with more accurate advisor checks, Pentesting dismissals, and Spring Boot archive coverage.

### Added

- **PostgreSQL and MySQL operational diagnostics.** Read-only panels and REST/MCP/CLI reports cover vital signs,
  sessions, statements, indexes, tables, replication, settings, partial evidence, comparisons, and a MySQL sample
  profile ([#1026](https://github.com/jdubois/boot-ui/pull/1026),
  [#1059](https://github.com/jdubois/boot-ui/pull/1059)).
- **Paginated advisor violation details.** Advisors expose retained per-rule details in the UI, REST, MCP, and CLI;
  retained details default to 10,000 per advisor, pages default to 100, and truncation is explicit
  ([#1037](https://github.com/jdubois/boot-ui/pull/1037)).
- **Claude Code plugin.** `/plugin install bootui@bootui` installs the BootUI skill and local MCP server registration,
  with `BOOTUI_MCP_URL` available to override the default endpoint
  ([#1068](https://github.com/jdubois/boot-ui/pull/1068)).

### Changed

- **Generated classes and database row caps are handled more clearly.** ARCH-CODE rules skip only positively identified
  generated sources, PostgreSQL limits have documented defaults, and PostgreSQL/MySQL cap-only results use local labels
  ([#1042](https://github.com/jdubois/boot-ui/pull/1042), [#1044](https://github.com/jdubois/boot-ui/pull/1044),
  [#1046](https://github.com/jdubois/boot-ui/pull/1046), [#1059](https://github.com/jdubois/boot-ui/pull/1059)).

### Fixed

- **Pentesting honors persisted dismissals.** Dismissed findings no longer affect active totals, severity bars, or
  Overview penalties across UI, REST, MCP, and CLI, and dismissal state survives restart
  ([#1040](https://github.com/jdubois/boot-ui/pull/1040), [#1041](https://github.com/jdubois/boot-ui/pull/1041)).
- **Advisor false positives are reduced.** Architecture, Hibernate, and Database checks better recognize repository
  transactions, date conversions, thread factories, bulk-update version maintenance, and view metadata
  ([#1039](https://github.com/jdubois/boot-ui/pull/1039), [#1038](https://github.com/jdubois/boot-ui/pull/1038),
  [#1043](https://github.com/jdubois/boot-ui/pull/1043), [#1023](https://github.com/jdubois/boot-ui/pull/1023),
  [#1035](https://github.com/jdubois/boot-ui/pull/1035)).
- **Runtime diagnostics report more honest state.** Vulnerability coverage for extracted Spring Boot archives, MySQL
  instrumentation and datasource discovery, and WebFlux HTTP/1.x rejections avoid incomplete coverage or stranded
  requests ([#1036](https://github.com/jdubois/boot-ui/pull/1036),
  [#1059](https://github.com/jdubois/boot-ui/pull/1059)).

## [1.17.0] - 2026-09-10

Feature release focused on evidence-led diagnostics and agent-guided assessment. It adds an application-assessment MCP
prompt, safer MCP client setup, a Hibernate bulk-update check, and broad advisor accuracy and evidence-quality fixes.

### Added

- **Hibernate detects bulk updates that skip optimistic-locking versions.** The new check reviews observed Spring Data
  JPQL/HQL updates on versioned entities and stays inapplicable when repository metadata is unavailable
  ([#1019](https://github.com/jdubois/boot-ui/pull/1019)).
- **Agents can request an application assessment before changing code.** The BootUI skill, MCP prompt, panel copy and
  guide collect bounded evidence, propose prioritized actions, and stop for approval before fixes
  ([#981](https://github.com/jdubois/boot-ui/pull/981), [#990](https://github.com/jdubois/boot-ui/pull/990)).
- **MCP setup now matches common clients.** The MCP Server panel and docs show VS Code, Claude Code, Cursor and generic
  snippets, plus an explicit bearer-header switch for agents outside loopback
  ([#928](https://github.com/jdubois/boot-ui/issues/928)).

### Changed

- **Advisor scores now reflect usable known findings instead of assumed coverage.** Partial evidence, scan notes,
  not-applicable scopes, GitHub security counts and overview badges now avoid fake passes or fake zeros
  ([#954](https://github.com/jdubois/boot-ui/issues/954), [#989](https://github.com/jdubois/boot-ui/issues/989)).
- **Advisor audits separate evidence gaps from clean results.** Database, Quarkus, GraalVM, Vulnerabilities, Pentesting
  and Security checks were corrected, with unsupported or low-signal rules retired where noted
  ([#977](https://github.com/jdubois/boot-ui/issues/977), [#959](https://github.com/jdubois/boot-ui/issues/959),
  [#958](https://github.com/jdubois/boot-ui/issues/958), [#978](https://github.com/jdubois/boot-ui/issues/978),
  [#961](https://github.com/jdubois/boot-ui/issues/961), [#965](https://github.com/jdubois/boot-ui/issues/965)).
- **More advisor catalogs preserve uncertainty instead of guessing.** Hibernate, Spring, REST API, Memory and JVM Tuning
  fixes keep contracts stable while retiring or skipping unsupported checks where the audit did
  ([#964](https://github.com/jdubois/boot-ui/issues/964), [#969](https://github.com/jdubois/boot-ui/issues/969),
  [#962](https://github.com/jdubois/boot-ui/issues/962), [#956](https://github.com/jdubois/boot-ui/issues/956),
  [#955](https://github.com/jdubois/boot-ui/issues/955)).
- **Configuration search and override suggestions understand relaxed property names.** Dotted, kebab-case and
  environment spellings match consistently, while the picker suggests bindable dotted keys
  ([#940](https://github.com/jdubois/boot-ui/issues/940), [#939](https://github.com/jdubois/boot-ui/issues/939),
  [#945](https://github.com/jdubois/boot-ui/issues/945)).
- **Console state persistence is documented for image rebuilds.** The Docker guidance explains how overrides and
  dismissed findings can survive rebuilds through `bootui.overrides-file`
  ([#930](https://github.com/jdubois/boot-ui/discussions/930)).

### Fixed

- **Overview and browser coverage handle incomplete scans honestly.** Cached reports from panels and agents are found
  without rescanning, stale `NOT_SCANNED` data is cleared, and partial reports keep findings visible
  ([#986](https://github.com/jdubois/boot-ui/issues/986), [#983](https://github.com/jdubois/boot-ui/issues/983)).
- **Panel navigation recovers after backend rebuilds.** Missing lazy assets now offer an explicit reload that preserves
  route intent and warns before discarding unsaved input ([#985](https://github.com/jdubois/boot-ui/issues/985)).
- **Spring evidence handling is less noisy.** Native scheduling observability, BootUI-wrapped caches, OSIV absence and
  not-applicable vendor checks no longer count as missing applicable evidence
  ([#989](https://github.com/jdubois/boot-ui/issues/989)).
- **Architecture, CRaC, Kotlin and Modulith checks avoid framework-language false positives.** Valid logger, scheduling,
  lifecycle, default-argument, `lateinit`, nested-exception and post-commit listener shapes are recognized
  ([#957](https://github.com/jdubois/boot-ui/issues/957), [#960](https://github.com/jdubois/boot-ui/issues/960),
  [#925](https://github.com/jdubois/boot-ui/issues/925), [#926](https://github.com/jdubois/boot-ui/issues/926)).
- **Security and pentest findings stop blaming unverified or BootUI-owned defaults.** Actuator protection is not
  inferred from filter descriptions, and BootUI's health-details default is ignored as host configuration
  ([#922](https://github.com/jdubois/boot-ui/issues/922), [#965](https://github.com/jdubois/boot-ui/issues/965), #923).
- **Wrapped datasources are discovered consistently.** Database Advisor and SQL Trace inspect pools behind Spring
  datasource proxies and report unreadable wrappers instead of going blind
  ([#924](https://github.com/jdubois/boot-ui/issues/924)).

## [1.16.0] - 2026-09-03

Feature release that takes BootUI diagnostics to terminals and CI through a new CLI, dependency-free client, and plain
REST command endpoint. It also fixes MCP error reporting, Spring DevTools status, and a Spring path-filter bypass.

### Added

- **A `bootui` CLI now exposes every diagnostic as a command.** The Maven Central/JBang tool renders tables or JSON,
  supports URL/token/timeout options and exits distinctly for transport, usage, disabled and read-only outcomes.
- **A command endpoint enabled by default projects MCP tools onto REST.** All three stacks expose live discovery and
  invocation behind existing local, host, CSRF, token, panel-policy, concurrency and timeout guards; `bootui-client`
  reuses it.
- **The engine catalog now drives the CLI and Command Line panel.** Users can see install commands, live endpoint
  metrics, refused commands and the current command catalog while the panel remains read-only.

### Changed

- **The DevTools panel is now Spring DevTools.** Only the display name changes; the panel id, route, properties and API
  contract stay the same, and Quarkus remains not applicable.

### Fixed

- **MCP and Spring DevTools failures degrade cleanly.** Refused MCP tool 4xx calls now report in-band client errors, and
  uninitialised Restarter status reads report restart unavailability instead of failing.

### Security

- **Spring safety filters match encoded and matrix-parameter BootUI paths.** Spring MVC and WebFlux now resolve paths
  like their handlers, so loopback, host, DNS-rebinding, CSRF, token, panel and security-header guards cannot be
  bypassed.

## [1.15.0] - 2026-08-27

Feature release adding Fault Tolerance and WebSockets panels, richer Metrics, SQL Trace, REST API, Cache, and theme
experiences, plus important masking, activation, Host-header, and input-bounding fixes.

### Added

- **Fault Tolerance and WebSockets panels.** All three stacks report declared fault-tolerance policies, bounded metadata
  events, WebSocket endpoints, live connections, STOMP subscriptions, and recent frame metadata without payload capture.
- **Metrics and Cache explain their sources.** Metrics gain provenance, grouped explanations, and filters, while Cache
  rows disclose implementation-described tiers and comparable native hit ratios without fabricating unavailable data.
- **HTTP and SQL diagnostics are easier to act on.** HTTP Exchanges can copy a safe cURL template, SQL Trace ranks
  retained statements by route, and DB-RUNTIME-001 flags changing SQL shapes as bounded evidence.
- **REST API error contracts are catalogued.** Declared handlers and Quarkus mappers appear in a pageable view, retained
  exceptions can link to exact handlers, and three evidence-based error-contract advisor rules were added.
- **Theme picker adds five opt-in skins.** Graphite, Minimal, Cyberpunk, France, and Windows 95 join light and dark,
  with explicit choice only, reduced-motion handling, and contrast gates (#878).

### Changed

- **Panel presentation, frontend loading, and docs were refined.** Shared surfaces, deferred chunks, site search,
  focused feature/setup pages, searchable catalogues, rebuilt navigation, and consent-only analytics were added (#875,
  #876).

### Fixed

- **Theme, REST API, and Quarkus diagnostics render correctly.** Theme menus are opaque, REST API load failures show the
  real reason, and custom Quarkus BootUI mounts stay out of HTTP Exchanges, Live Activity, and Exceptions (#857).
- **HTTP Probe input is bounded before forwarding.** Method, path, body, header count, names, values, and total header
  size are checked in UTF-8 bytes on all three adapters, with canonical 400 errors for over-limit input (#860).
- **Core DTO collections are defensively copied.** Published reports stay stable for readers and keep deterministic
  Jackson 3/Jackson 2 JSON ordering (#855).

### Security

- **Deactivated Spring MVC and WebFlux apps no longer serve the packaged console.** The reserved BootUI namespace now
  returns 404 for static assets just like Quarkus, including encoded and relocatable-path variants (#856).
- **URI credentials and REST-client transport errors are sanitized.** User-info is always removed, sensitive query,
  matrix, and fragment values follow policy, and nested credential URLs are masked in free-text errors (#858).
- **Malformed Host headers and opaque Origins fail closed.** Strict authority parsing rejects smuggled or invalid Host
  values, and serialized `Origin: null` is treated as no concrete host on state-changing requests (#859).
- **Malformed HTTP Probe input cannot exceed its byte budget.** Unpaired surrogates are charged at the worst-case UTF-8
  replacement size so invalid input cannot bypass the configured limits.

## [1.14.1] - 2026-08-20

Patch release that restores Spring native and Quarkus Docker startup, fixes the native-image build bootstrap on AMD64,
and strengthens release verification against the artifacts consumers actually download.

### Fixed

- **The packaged BootUI shell is no longer reachable on Spring MVC or Spring WebFlux when BootUI is inactive.**
  Deactivating BootUI (a `prod`/`production` profile, `bootui.enabled=OFF`, an invalid `bootui.enabled` value, or simply
  no enabling profile) already unwired every BootUI route, but `bootui-ui` ships the compiled console at
  `META-INF/resources/bootui/`, one of Spring Boot's default static-resource locations, so `GET /bootui/index.html` and
  every asset under it still answered `200` with an empty shell. A new `BootUiShellGuardAutoConfiguration`, gated by the
  exact negation of the activation condition and by the presence of the packaged shell, answers `404` for the reserved
  `/bootui` namespace on both stacks, matching the Quarkus adapter's production shell guard. It matches the decoded
  request path (so `/%62ootui/index.html` and matrix-parameter spellings cannot slip through) and follows relocated
  static handling (`spring.mvc.servlet.path`, `spring.mvc.static-path-pattern`, `spring.webflux.static-path-pattern`),
  under which the same bundle otherwise surfaced at, for example, `/app/static/bootui/index.html`. Requests outside the
  reserved `/bootui` namespace are passed through untouched; a host application that served its own routes there while
  shipping BootUI now receives `404` for them while BootUI is inactive (#856).
- **Spring native images can start with the sample application's Hibernate second-level JCache configuration.**
  Runtime hints now retain the reflectively created `JCacheRegionFactory` constructor together with Caffeine's
  `application.conf` and default `reference.conf` cache configuration resources (#832, #835).
- **Docker image publishing works across the supported sample configurations.** Native AMD64 builds fetch and verify the
  pinned Maven Wrapper JAR before invoking the wrapper, Quarkus Docker startup accepts the intentionally pinned
  Flyway-compatible H2 driver, and the Spring Docker-profile Live Activity check recognizes its PostgreSQL datasource
  instead of requiring the development-only H2 URL (#828, #833, #834).
- **Maven Central smoke tests now exercise standalone Spring MVC and WebFlux consumer projects.** They can no longer pass
  by resolving unpublished reactor sample modules instead of the released starter artifacts (#827).
- **The MCP concurrency-limit test no longer fails intermittently on JDK 25** while preserving the production capacity
  guard (#829).

## [1.14.0] - 2026-08-18

Feature release headlined by Database advisor, Transactions, Hibernate Statistics, Live Activity's Live flow map, and
complete MCP access to BootUI's safely exposable features.

### Added

- **Database diagnostics expanded.** Database advisor, Transactions, and Hibernate Statistics add bounded database,
  transaction, and ORM visibility with honest unavailable states (#760, #761, #781, #795, #807).
- **Animated Live flow map.** Live Activity turns existing HTTP, JDBC, REST client, cache, Kafka, and RabbitMQ evidence
  into an accessible dependency map without new probes (#777, #784).
- **Complete capability-aware MCP reads.** MCP exposes every safely passive panel read, cached advisor report, bounded
  diagnostic control, strict schema, and runtime counter (#776, #791).

### Changed

- **Advisor accuracy was audited broadly.** Architecture, Spring, Quarkus, Hibernate, Security, REST API, JVM Tuning,
  Memory, Vulnerabilities, GraalVM, CRaC, Pentesting, and Database use stronger evidence (#743–#755, #775, #782, #795,
  #806, #807).
- **Advisor scores count every finding.** Panels and Overview apply severity weights per concrete finding, not only once
  per violated rule (#803).
- **SSE health and auto-refresh share one model.** Live Activity, Exceptions, SQL Trace, Security Logs, REST Client, and
  Transactions show calm paused, retrying, and unavailable states (#757, #783).
- **The console uses one Calm Control Room system.** All panel routes, the shell, and Hibernate Statistics received
  consistent hierarchy, responsive states, focus treatment, and reduced-motion support (#812, #813, #816).

### Fixed

- **Quarkus Security advisor matches Quarkus 3.33 LTS.** Effective auth, TLS, CORS, roles, management, literal-secret,
  password, and hostname-verification behavior is modeled more accurately; the obsolete unsigned-JWT rule was retired
  (#755).
- **WebFlux avoids event-loop blocking.** BootUI work in applicable WebFlux handlers is offloaded without changing the
  shared API contract (#726).
- **Bounded data stays bounded.** Metrics, SQL previews, email bodies, telemetry snapshots, scanner concurrency, and
  vulnerability severity parsing no longer leak stale, partial, or misleading state (#720, #727, #732, #742, #771,
  #780).
- **Shared UI resilience improved.** Data panels, navigation, keyboard behavior, responsive layouts, sidebar scrolling,
  cgroup discovery, and Dev Services classification handle edge cases correctly (#721, #722, #730, #735, #737, #772,
  #815, #817).

## [1.13.1] - 2026-08-07

Patch release that restores accurate scanner scores with customized Jackson mappers and removes Quarkus split-package
warnings during application augmentation.

### Fixed

- **Overview scanner scores no longer show a false `100 / Good` result when a Spring host application enables Jackson
  polymorphic typing.** BootUI API responses now use a path-scoped clean Jackson 3 serializer on both Spring MVC and
  WebFlux without changing host endpoint serialization, while malformed severity summaries surface as scanner errors
  instead of silently receiving a perfect score (#724).
- **Quarkus applications no longer report split-package warnings for BootUI runtime packages.** Same-package white-box
  tests now live in the Quarkus runtime module instead of an integration-test application archive, keeping augmentation
  output clean without changing runtime behavior (#719).

## [1.13.0] - 2026-08-06

Feature release headlined by Beans dependency graph mode, reactive security and REST Client coverage, messaging
diagnostics, configurable mounts, and another accessibility hardening pass.

### Added

- **Beans dependency graph.** The panel adds a bounded, keyboard-accessible graph with list fallback and Quarkus
  fidelity notes (#656).
- **RabbitMQ and JMS diagnostics.** RabbitMQ covers all runtimes; JMS covers Spring MVC/WebFlux without payload or
  arbitrary-header retention (#655, #660).
- **Reactive security and REST Client coverage.** WebFlux security plus WebFlux/Quarkus REST Client metadata feed shared
  panels and Live Activity (#654, #657, #658, #663).

### Changed

- **Custom UI and API mounts work end to end.** `bootui.path` and `bootui.api-path` move the full surface together, fail
  invalid paths clearly, and do not leave legacy `/bootui` exposed (#662).
- **SSE-backed panels recover predictably.** Live Activity, Exceptions, SQL Trace, Security Logs, and REST Client show
  calm reconnect states, avoid duplicate streams, and retry cleanly (#661).
- **State-changing UI actions require confirmation.** Thread dumps, unsafe HTTP Probe methods, and configuration writes
  use the branded confirm flow with duplicate-submit protection (#692).
- **Panel naming and Quarkus baselines were updated.** AI Usage became AI Framework, and Quarkus modules align on
  Quarkus 3.33.3.1 LTS through the shared parent (#691, #701).

### Fixed

- **Accessibility and stale-request defects were corrected.** Labels, navigation, command palette semantics, keyboard
  operation, contrast, filtering, refreshes, pagination, and route changes now behave consistently (#659, #694–#698).
- **Slow Log Tail subscribers cannot block Spring MVC logging.** SSE delivery uses bounded per-subscriber queues and
  disconnects only the overloaded browser (#693).
- **Custom mounts and browser storage fail safely.** Application-root links respect configured roots, and UI preferences
  fall back to safe in-memory state when storage is unavailable (#699, #700).

### Security

- **MCP tool-call failures no longer leak internals.** Unexpected `tools/call` errors return the standard detail-free
  JSON-RPC error while logging details server-side (#705).
- **Vulnerable docs and frontend dependencies were patched.** `immutable`, `linkify-it`, `fast-uri`, and affected
  `brace-expansion` lines were updated outside BootUI's shipped runtime (#625–#627, #676, #678–#679, 4c895203).

## [1.12.0] - 2026-07-12

Security and hardening release that adds authenticated remote API access, brings the MCP Server to Spring WebFlux,
enforces bounded outbound HTTP responses, and completes another accuracy pass across every advisor.

### Added

- **Authenticated non-loopback API access on all three adapters.** Every non-loopback `/bootui/api/**` request now
  requires a bearer token, while loopback use remains frictionless. BootUI generates and logs a 256-bit token once when
  remote access is enabled without a configured `bootui.authentication.token`; callers can send it in the
  `Authorization` header, and the browser can exchange it for an HttpOnly, `SameSite=Strict` cookie scoped to the BootUI
  API (#608).
- **MCP Server support on Spring WebFlux**, with a reactive tool catalog over the reactive Live Activity, Exceptions,
  Security Logs, SQL Trace, and Log Tail controllers. The shared MCP core now validates protocol versions and JSON-RPC
  envelopes, handles notifications and lifecycle methods correctly, bounds payload size and concurrent calls, and is
  covered by the same conformance suite on Spring MVC, Spring WebFlux, and Quarkus. WebFlux advertises every applicable
  tool except `security_scan`, whose backing advisor is not yet ported (#611, #612).
- **A distributable BootUI agent skill** for GitHub Copilot and other skill-aware coding agents, covering installation,
  runtime inspection, advisor scans, verification, and MCP setup (#611).
- **Shared security-header policy** across Spring MVC, Spring WebFlux, and Quarkus, including CSP,
  `X-Content-Type-Options`, `X-Frame-Options`, and HSTS where applicable (#582).
- **Focused JDK 21 and 25 compatibility CI**, alongside the existing Java 17 build/release baseline (#579).

### Changed

- **All outbound HTTP integrations now enforce response-size budgets.** HTTP Probe returns a clear truncation signal;
  pentesting probes, GraalVM metadata, OSV/EPSS, and GitHub clients reject oversized responses rather than buffering
  unbounded remote content (#568).
- **The shared API conformance suite now covers nested and action endpoints**, canonical access-denied responses,
  pagination/filtering, scanner lifecycles, trace operations, and MCP behavior on every adapter. Backend panel metadata is
  centralized in one validated catalog and checked against UI routes, manifests, guarded API prefixes, and
  `docs/FEATURES.md` (#581, #583).
- **Another full advisor accuracy pass** corrected false positives, stale platform assumptions, severity/rationale gaps,
  and runtime heuristics across Architecture, Spring/Quarkus application, Hibernate, Memory, Security, REST API,
  Pentesting, Vulnerabilities, GraalVM, and CRaC. New coverage includes REST response-contract checks, proxy-aware
  architecture rules, current JVM/container memory heuristics, additional security configuration evidence, stricter
  version comparison and OSV result handling, and expanded GraalVM/CRaC runtime checks (#597–#606).
- **Release verification now smoke-tests all published distributions from Maven Central** — Spring MVC, Spring WebFlux,
  and Quarkus — before committing and tagging a prepared release (#576).
- **Dependencies updated**, including Quarkus 3.37.2, Spring Kafka 4.1.0, the Quarkus LangChain4j BOM, Vite, Prettier,
  vue-tsc, Playwright, VuePress, Sharp, and the patched `js-yaml` 3.15.0 transitive dependency.

### Fixed

- **REST Client panel now reports unavailable until a client is actually instrumented (Spring MVC).** The panel's
  `/bootui/api/panels` availability previously only checked that the internal `RestClientTraceRecorder` bean existed
  — but that bean is registered unconditionally (it also backs Live Activity), so the check was always true and the
  panel showed up in the sidebar even for applications with no `RestClient`, `RestTemplate`, or `WebClient` ever
  built. Availability now mirrors the recorder's own "has anything been instrumented yet" signal, the same pattern
  Kafka/Email/Cache already use (#560).
- **CRaC Docker publish smoke tests** now grant the capabilities CRIU needs to restore a checkpointed process and use the
  corrected generated-image startup path (#607).
- **WebFlux MCP availability and conformance expectations** now match the implemented reactive server, and nested
  endpoint conformance checks no longer misclassify valid endpoint families (#612).
- **Release pushes retry safely after a non-fast-forward update**, rebasing the release-only version commit before
  recreating the tag instead of leaving a successfully published version untagged (#559).

## [1.11.0] - 2026-07-09

Feature release headlined by three new dev-loop panels — **Email**, **REST Client Trace**, and **Kafka** — and
**Live Activity growing from 4 to 9 merged signal types**. Also ships a ~2.4x faster Maven build.

### Added

- **Email panel** — captures outgoing application email (recipients, subject, HTML/text body, attachments) for local
  inspection, mirroring Laravel Telescope's mail watcher. Ships on both Spring and Quarkus; content is revealed by
  default, with an opt-in `bootui.email.mask-content` flag for teams routing real PII through a shared dev
  environment (#538).
- **REST Client Trace panel** — captures outbound `RestClient`/`RestTemplate`/`WebClient` calls (method, host, path,
  status, duration), with slow-call and "chatty" (repeated-call) detection. Spring servlet adapter only for now
  (#544).
- **Kafka panel** — a dedicated, filterable view over producer/consumer activity: direction, topic, partition,
  offset, key hash, duration, and success/failure, with the message value/payload never captured. Ships on both
  Spring and Quarkus (#550).
- **Live Activity grows from 4 to 9 merged signal types**, adding cache accesses, scheduled-task runs, Kafka
  producer/consumer activity, outbound REST client calls, and captured email to the existing request/SQL/exception/
  security feed — each nested under its correlated request. Kafka and scheduled-task capture are also new on
  Quarkus, and cache capture is also new on Spring WebFlux (#538, #541, #542, #543, #544).

### Changed

- **Email and REST Client Trace moved into the Services group** in the sidebar, alongside Scheduled Tasks and Cache.
- **~2.4x faster full Maven build** (5:09 → ~2:10 min on a 10-core machine) by enabling reactor (`-T 1C`) and
  Surefire test parallelism, after fixing two latent Quarkus concurrency races that made parallel builds flaky
  (#545).

### Fixed

- **Hardened Kafka and Scheduled Tasks activity capture to fail open on errors**, so a capture-side bug can no
  longer disrupt a real Kafka send/consume or scheduled-task run.

## [1.10.0] - 2026-07-07

Feature release headlined by Spring WebFlux support as BootUI's third first-class adapter, OpenTelemetry span
enrichment, and the next advisor audit pass. It also corrects Quarkus loopback-enforcement reporting.

### Added

- **Spring WebFlux support.** A new reactive starter serves the shared engine, Vue UI, and API contract on Netty with
  the same safety floor as Spring MVC; unsupported panels report clear reasons (#523, #526, #536).
- **Reactive trace correlation.** WebFlux HTTP, SQL, exception, and security capture stamps active OpenTelemetry trace
  ids, with BootUI contributing an overridable Reactor context-propagation default (#523, #526, #536).
- **OpenTelemetry span enrichment.** BootUI can stamp service identity plus SQL and exception depth attributes on spans,
  and the Traces drawer surfaces those enriched cross-service traces (#525).
- **Advisor coverage grew.** Spring Security added default-user and cookie-prefix checks, and Hibernate added
  composite-id, bind-logging, and natural-id checks (#511, #522).

### Changed

- **The advisor audit reached the remaining rule sets.** Architecture, Memory, REST API, Pentesting, Vulnerabilities,
  Spring, GraalVM, CRaC, Security, Hibernate, and Quarkus gained fixes and targeted checks (#509–#520, #522).
- **Obsolete or duplicate advisor logic was removed.** The audit retired duplicate Architecture checks, stale Spring
  Security claims, and dead Quarkus Security rules while documenting replacements (#509, #519, #520, #522).
- **BootUI self-traffic classification is shared.** Capture and transform paths agree on configured `bootui.path`
  instead of hardcoding `/bootui` in capture (#508).
- **Sample apps have dedicated ports.** Spring MVC, WebFlux, and Quarkus samples now run on 8080, 8081, and 8082 so they
  can run side by side.
- **Sample-app integration tests run Docker-free on H2.** The tests no longer require a PostgreSQL Testcontainer (#506).
- **Dependencies were refreshed.** Quarkus, PostgreSQL JDBC, Vite, Vitest, vue-tsc, and Prettier moved to current lines
  (#528–#533).

### Fixed

- **Quarkus overview now reports loopback enforcement accurately.** `activation.localhostOnly` mirrors Spring semantics
  and no longer warns that Quarkus reads lack the shared `LocalhostGuard` policy.
- **Quarkus Security advisor matches current Quarkus behavior.** Dead checks were removed, JWT algorithm, GraphQL, CORS,
  secret, TLS, management, authz, messaging, and session logic were corrected, and new checks were added (#520).
- **Quarkus application advisor rules now fire and rank correctly.** Scheduling, REST-client timeout, reactive JDBC, Dev
  Services, schema generation, CDI, virtual threads, profiles, and new checks were corrected (#520).

## [1.9.0] - 2026-07-03

Feature release headlined by optional durable JDBC persistence for Live Activity, Exceptions triage, SQL call-site
capture, Quarkus parity work, and a full advisor audit. It also fixes a Quarkus XSS vulnerability.

### Added

- **Live Activity can persist to JDBC.** The default remains the in-memory ring buffer, but operators can switch to
  masked, instance-namespaced JDBC persistence with buffered writes and self-capture protection (#504).
- **Exceptions gained triage state.** Exception groups can be Open, Acknowledged, or Resolved; resolved regressions
  reopen automatically with a badge, while acknowledged groups stay acknowledged (#499).
- **SQL call sites are visible.** SQL Trace and Live Activity show application call sites, and rows surface suspected
  N+1 patterns without opening the drawer (#500).
- **Vulnerabilities can be dismissed and restored.** Dismissals survive patch-version bumps for the same vulnerable
  package and are excluded from rollups while remaining visible (#485).
- **Quarkus parity improved.** Security events, profile drill-down, JAX-RS exception mapping, and access gating now
  align with the Spring adapter's shared model (#489, #491, #496, #501).

### Changed

- **All nine advisor rule sets were audited.** Architecture, Spring, Security, Pentesting, Hibernate, REST API, Memory,
  Vulnerabilities, and Quarkus Security received bug fixes, false-positive reductions, and new checks (#479–#487).
- **Maven Central publishing was trimmed.** Demo/test modules and unnecessary checksum fanout were removed from releases
  to reduce the publishing footprint (#477, #493).

### Fixed

- **Quarkus XSS vulnerability fixed.** The shell `<base href>` now comes from static root-path configuration rather than
  attacker-influenced request URI data (#503).
- **Quarkus production builds no longer expose the shell.** The entire `/bootui` surface returns 404 in
  `LaunchMode.NORMAL`, matching the already-dark API behavior (#497).
- **Quarkus Exceptions and Live Activity keep HTTP context.** Method, path, handler, and richer request details now win
  the capture race (#492, #495).
- **Quarkus panel filtering and badges are accurate.** Beans and Mappings no longer hide similarly-prefixed application
  classes, and Security Logs use Quarkus event names for badge colors (#498).
- **Quarkus docs and release guards were corrected.** Panel availability claims, javadocs, and stale Quarkus version
  references were fixed, with release checks preventing future stale versions (#476, #478, #488).

## [1.8.0] - 2026-07-01

Feature release headlined by Quarkus support, Quarkus-native advisors, shared conformance, and the breaking renames
needed for the Quarkus port.

### Added

- **Quarkus support.** The new `bootui-quarkus` extension serves the same Vue UI, API contract, and shared engine as
  Spring Boot, activates only in dev/test, and reports unsupported panels clearly (#467).
- **Quarkus-native Security advisor.** The shared security panel reviews Elytron/OIDC auth, Quarkus HTTP permissions,
  TLS, CORS, and role annotations on Quarkus (#467, #472).
- **Shared HTTP conformance.** `bootui-conformance` pins the manifest, available-panel JSON reads, and cross-site write
  rejection across Spring and Quarkus (#467).
- **Quarkus sample and tests.** A reference sample app plus Docker-free Quarkus integration and conformance suites
  demonstrate and gate the adapter (#467).

### Changed

- **Quarkus advisors were hardened before release.** Application and Security advisors gained parity fixes plus
  Quarkus-specific production, JWT, header, form-auth, management, gRPC, GraphQL, and messaging checks (#472).
- **Dependencies were refreshed.** Vue, Spotless, PostgreSQL JDBC, and Prettier moved to current lines (#468–#471).
- **Spring Cache is now Cache.** The route and API moved to `/cache` and `/bootui/api/cache`; the old browser route
  redirects, but the old API path and `bootui.panels.spring-cache.*` keys were removed.
- **Spring adapter modules were renamed.** `bootui-autoconfigure` became `bootui-spring-autoconfigure`; direct Maven
  users must update that artifact id, while starter users need no change.
- **`bootui-spi` was merged into `bootui-engine`.** The `bootui-spi` Maven coordinate no longer exists, but the
  `io.github.jdubois.bootui.spi.*` package remains in the engine for import compatibility.

## [1.7.0] - 2026-06-29

Feature release headlined by **richer Dependabot insight in the GitHub panel** and **filterable readiness concerns on the
GraalVM and CRaC advisors**, alongside a console-wide accessibility, motion, and design-token polish and a fix for a
trailing-slash redirect loop.

### Added

- **Dependabot alert details in the GitHub panel security drawer.** The drawer previously showed only an open-alert count
  plus a privacy note. It now lists the bounded set of open Dependabot alerts with their package, ecosystem, severity,
  advisory ID, summary, affected range, and fixed version — non-secret advisory metadata. Code scanning and secret
  scanning stay count-only and never inline secret values or vulnerable code snippets (#464).
- **Concern filtering on the GraalVM and CRaC advisor panels.** Both panels listed every readiness concern with no way to
  narrow them. A shared filter toolbar (severity chips, category dropdown, free-text search) now appears once a scan finds
  concerns, with a live count and a no-match empty state, keeping the two advisors uniform (#463).

### Changed

- **Console-wide accessibility, motion, and design-token polish.** A design pass verified WCAG 2.1 AA contrast in both
  light and dark themes (including caution body text), put a visible branded focus ring on every interactive control,
  calmed elevation and motion (flattened card shadows, hover-lift only on interactive cards, honors
  `prefers-reduced-motion`), and replaced native `window.confirm()` on one-click mutations with a branded confirmation
  dialog. `DESIGN.md` and its sidecar are now the documented source of truth for the radius, severity-color, and chart
  palettes (#459).
- **Bumped the toolchain:** build plugins to latest (#462), Testcontainers to 2.0.5 (#461), and Node to 24.18.0 / npm to
  11.17.0 (#460).
- **Clarified the front-end hot-reload workflow** in the contributor and agent docs so UI iteration uses the Vite dev
  server (`:5173/bootui/`) rather than the pre-built Maven-served console (#457).

### Fixed

- **`/bootui` no longer redirects to `/bootui/`, fixing a "localhost redirected you too many times"
  (`ERR_TOO_MANY_REDIRECTS`) loop behind a trailing-slash–stripping filter or proxy.** BootUI used to
  answer `GET /bootui` with a `302` to the canonical `/bootui/` so its relatively-referenced Vue assets
  and `fetch('api/...')` calls would resolve. Spring Framework 6.1+/Boot 4 dropped trailing-slash URL
  matching, so a host application that restores it with `UrlHandlerFilter.trailingSlashHandler("/**")
  .wrapRequest()` (a standard Boot 4 idiom) rewrites `/bootui/` back to `/bootui` ahead of the
  dispatcher — turning BootUI's redirect into an infinite loop. The same happens behind any proxy or
  filter that strips trailing slashes. BootUI now serves the SPA shell at **both** `/bootui` and
  `/bootui/` and injects a runtime `<base href="{contextPath}/bootui/">` so assets, API calls, and lazy
  chunks resolve regardless of the trailing slash, with no redirect to loop on. Host
  `server.servlet.context-path` support (#332) is preserved because the base href is computed
  per-request (#456).

## [1.6.0] - 2026-06-25

Feature release headlined by **idle memory reclamation** — BootUI now releases its live diagnostic buffers and pauses
recording while the console sits idle — alongside a fix for a YAML activation gotcha that could silently disable BootUI
and a restore of the Spring Security panel's collapsible sections.

### Added

- **Idle memory reclamation for the live diagnostic buffers.** BootUI fills several bounded in-memory buffers from the
  host application's own traffic — ingested OTLP traces/spans, the SQL trace, and the request/security correlation
  windows. In development that traffic keeps flowing even when nobody has the console open, so the buffers sat at their
  steady-state size for no observable benefit. Once the console has been idle for `bootui.free-on-idle.timeout`
  (default `5m`), BootUI now releases those buffers' retained data and pauses recording into them, then refills them from
  live traffic on the next console request. A `ConsoleActivityFilter` registered just after the safety filters marks the
  console active on any trusted-local `/bootui` request (UI load, API poll, or stream open), so an open console never
  reclaims while a genuinely unused one does. The Exceptions and Log Tail buffers are deliberately retained so a recent
  error stays visible when the console is opened, the idle gate is kept separate from any user-facing pause toggle so
  resuming never overrides an explicit pause, and the whole behavior is dev-only and fully disabled with
  `bootui.free-on-idle.enabled=false` (#452).
- **Ecosystem page on the documentation site** ([`docs/WORKS-WITH.md`](docs/WORKS-WITH.md)) that tells the shared-Java
  workflow story across BootUI, [Coffilot](https://www.julien-dubois.com/coffilot/), and
  [Dr JSkill](https://www.julien-dubois.com/dr-jskill/), reachable from a new "Ecosystem" navbar entry (#432).

### Fixed

- **`bootui.enabled: ON` / `OFF` in YAML no longer silently disables BootUI.** YAML parses the unquoted `ON` as the
  boolean `true`, which reached `BootUiActivationCondition` as the string `"true"`, was rejected as an invalid value, and
  turned BootUI off — so `/bootui` fell through to the static-resource handler and 404'd with
  `NoResourceFoundException`. The activation condition now normalizes boolean-ish values (`TRUE`/`YES` → `ON`,
  `FALSE`/`NO` → `OFF`), matching Spring's relaxed enum binding used for BootUI's other `Mode` properties; unknown values
  still fail closed (#447, #448).
- **Restored the collapsible accordion in the Spring Security panel.** Bootstrap's collapse JavaScript was never
  imported, so clicking the panel's accordion section headers did nothing; BootUI now bundles `bootstrap/js/dist/collapse`
  and the sections expand and collapse again (#431).
- **Corrected the heading-anchor scroll offset on the documentation site** so deep links and in-page anchors no longer
  land with the target heading hidden behind the fixed navbar (#426).

## [1.5.2] - 2026-06-17

Patch release fixing a startup crash for applications that contribute their own `HttpExchangeRepository`, polishing two
panels (Metrics measurement spacing and the Flyway sidebar icon), and adding a guide for driving BootUI from local AI
coding agents.

### Added

- **AI agents guide** ([`docs/AI-AGENTS.md`](docs/AI-AGENTS.md)) covering how to drive BootUI from local AI coding agents
  over the Model Context Protocol (MCP): connecting an agent to BootUI's MCP server so it can consult the running
  application before proposing a fix and verify it afterwards, a worked Hibernate-findings example, and how BootUI pairs
  with [Coffilot](https://github.com/jdubois/coffilot) to build, run, and scan an app from the GitHub Copilot App's side
  panel (#423).

### Fixed

- **BootUI no longer crashes applications that contribute their own `HttpExchangeRepository`.** BootUI registers a
  fallback in-memory `HttpExchangeRepository` guarded by `@ConditionalOnMissingBean`, but runs `@AutoConfigureBefore` the
  standard HTTP-exchange auto-configurations. When the host application supplied its own repository from a configuration
  ordered after BootUI (for example its own auto-configuration), the condition could not see it yet, so BootUI created
  its fallback as well — leaving two repositories that broke single-bean injection into BootUI's recording filter and
  Spring Boot's own `httpExchangesEndpoint`, crashing the context at startup. BootUI now reconciles the repositories in a
  `BeanFactoryPostProcessor` that runs after every bean definition is registered (regardless of ordering) and before any
  bean is instantiated, dropping its fallback whenever another `HttpExchangeRepository` is present so exactly one remains
  — the application's own — which BootUI's filter and HTTP Exchanges panel then use transparently (#422).
- **Corrected the Flyway panel icon** in the sidebar and panel header so it no longer reuses an unrelated glyph (#412).
- **Restored the spacing between the statistic label and value** in the Metrics panel's per-sample measurements, which
  had run together without a gap (#410).

## [1.5.1] - 2026-06-15

Patch release with two fixes: BootUI's live panels no longer hold the JVM open until the configured shutdown timeout
(a graceful-shutdown regression introduced by 1.5.0's move to Server-Sent Events), and BootUI no longer crashes
Spring Cloud Config applications during the bootstrap phase.

### Fixed

- **BootUI's live panels no longer delay graceful shutdown.** The Server-Sent Events panels (Live Activity, Exceptions,
  SQL Trace, Security Logs, Log Tail, Copilot, Claude Code) open an `SseEmitter` with no timeout, which counts as an
  active request. Their emitter cleanup ran from a bean-destruction (`@PreDestroy`) hook — too late, because Spring Boot
  4's default graceful shutdown waits for in-flight requests *before* beans are destroyed, so every JVM stop blocked
  until the `spring.lifecycle.timeout-per-shutdown-phase` timeout (30s by default). BootUI now completes these streams on
  `ContextClosedEvent`, which fires before the web server's graceful-shutdown lifecycle, so the application stops
  promptly again.
- **BootUI no longer breaks Spring Cloud bootstrap startup.** When a host application used Spring Cloud Config / the
  legacy bootstrap context (`spring-cloud-starter-bootstrap`) with BootUI active (for example under the `dev` profile),
  the application crashed at startup with
  `MissingWebServerFactoryBeanException: No qualifying bean of type 'ServletWebServerFactory' available`. BootUI's
  command-line support forces a servlet web type so the console can be served, but it was also applying that to Spring
  Cloud's transient, non-web **bootstrap** application context, which has no `ServletWebServerFactory`. BootUI now
  detects the bootstrap context (via Spring Cloud's `"bootstrap"` marker property source) and leaves it untouched, while
  still forcing the servlet web type on the main application.

## [1.5.0] - 2026-06-15

Feature release headlined by a new **Live Activity** panel — a diagnostics "home base" that merges BootUI's already
captured signals into one reverse-chronological stream and adds a Symfony-style per-request profiler — and a move to
**Server-Sent Events** for the event-driven panels so they update the moment something happens instead of polling on a
timer. It also adds keyboard shortcuts and number-key navigation to the command palette, a fourth Ahead-of-Time
sample-app Docker image (Spring AOT + JDK AOT cache), simplifies the Overview panel, upgrades the sample app to Spring AI
2.0.0 GA, and quiets the tracing libraries' DEBUG noise.

### Added

- **Live Activity panel** — a new Overview panel that is the diagnostics home base: one reverse-chronological stream of
  everything the application just did plus a per-request profiler. It adds no new instrumentation, instead reusing the
  controllers and DTOs that back the HTTP Exchanges, SQL Trace, Exceptions, and Security Logs panels, so every value is
  already masked, self-filtered, and bounded. The stream merges `REQUEST`, `SQL`, `EXCEPTION`, and `SECURITY` entries
  with a colour-coded severity, a latency heat scale, a requests-over-time sparkline, a KPI strip (requests/min, error
  rate, p50/p95 latency, SQL rate, slowest endpoint, active exceptions, health, heap), and client-side filters that
  persist in the browser. Correlated SQL, exceptions, and security events are nested chronologically under the request
  that produced them — pinned by trace id, by the request's serving thread, or by method/path — and the per-request
  profiler correlates one request's signals with a tiered join that degrades gracefully and labels approximate matches
  rather than fabricating links, flags likely N+1 access patterns, and offers a **Copy profile** action. The merged feed
  is pushed over **Server-Sent Events** (`GET /bootui/api/activity/stream`) and can be paused and resumed. The panel is
  read-only and inherits BootUI's full safety model. Configurable under `bootui.activity.*` (#388).
- **Server-Sent Events live updates** for the Exceptions, SQL Trace, and Security Logs panels. Each panel subscribes to a
  per-panel `/stream` endpoint and re-fetches the moment a signal is captured (or the buffer is cleared or
  paused/resumed) instead of polling on a fixed interval. The push carries no data — masking, truncation, and
  value-exposure rules still apply through the regular endpoint — bursts are coalesced into a single refresh, the stream
  is closed when the auto-refresh toggle is off or the tab is hidden, and the panels fall back to their initial load when
  Server-Sent Events are unavailable (#386).
- **Command palette keyboard shortcuts and number-key navigation** — each panel now has a two-letter shortcut that the
  palette matches and displays, and the unfiltered palette can be navigated with the number keys `1`–`9` (#381).
- **AOT-optimized sample-app Docker image** (`Dockerfile-aot`, `docker-compose-aot.yml`, published as
  `jdubois/bootui-sample-app-aot`) — a fourth startup-optimization option that combines Spring AOT processing with a JDK
  AOT (Ahead-of-Time) cache on the plain JVM image for significantly faster startup, documented in
  [`docs/TRY-SAMPLE-APP.md`](docs/TRY-SAMPLE-APP.md) (#383, #387).
- Sample-app **action-lab buttons** that exercise more BootUI panels for richer demos and integration coverage (#389).
- New `bootui.activity.*` configuration properties, catalogued in [`docs/PROPERTIES.md`](docs/PROPERTIES.md).

### Changed

- **Simplified the Overview panel** by removing the live Health and Memory cards. The panel now opens with the hero
  banner and quick links and leads straight into the on-demand security & health scoring dashboard; the dedicated Health,
  Live Memory, and Heap Dump panels (also reachable from the Live Activity KPI strip) remain the home for that detail
  (#396).
- **Quieted the tracing libraries' DEBUG noise at full sampling.** Because BootUI raises
  `management.tracing.sampling.probability` to `1.0` for local development, the OpenTelemetry SDK and Micrometer Tracing
  span/propagation code runs on every request and floods the console with low-value lines when the host's root logger is
  at `DEBUG`. BootUI now pins `logging.level.io.opentelemetry` and `logging.level.io.micrometer.tracing` to `INFO` as
  overridable defaults while the Traces panel is active; set either key yourself to opt back in (#394).
- Upgraded the sample app to **Spring AI 2.0.0 GA** (from `2.0.0-RC1`) (#378).
- Bumped `esbuild` from `0.28.0` to `0.28.1` (#380).

### Fixed

- **`NoSuchMethodError` opening the Configuration or MCP Server panel on older Jackson 3** — `ConfigMetadataCatalog` now
  binds to the stable `ObjectMapper.treeToValue(TreeNode, Class)` overload instead of `treeToValue(JsonNode, Class)`,
  which was only added in jackson-databind 3.1. Host applications whose classpath resolves an earlier Jackson 3 (for
  example 3.0.x dragged in by a transitive dependency) no longer crash `ConfigController` start-up with
  `java.lang.NoSuchMethodError: 'java.lang.Object tools.jackson.databind.ObjectMapper.treeToValue(...)'` (#384).
- **SQL Trace no longer fails under Spring Boot DevTools' class-loader split.** When DevTools loads the application on its
  restart class loader, the data source's own loader (the base loader, where the driver/pool jar lives) cannot see
  BootUI's `SqlTracedDataSource` marker, so creating the JDBC tracing proxy threw. BootUI now defines the proxy with its
  own class loader — a descendant of the data source's that can see both the marker and the JDK's JDBC interfaces — so
  SQL tracing keeps working during DevTools-powered development (#395).
- Updated the Claude Code panel icon (#392).
- Fixed the documentation site's home-page `<title>` tag (#382) and a duplicate `@vuepress/plugin-markdown-tab` warning
  during the docs build (#379).

## [1.4.0] - 2026-06-12

Feature release headlined by three new panels — a **SQL Trace** panel that records executed SQL through a hand-written
JDBC proxy and flags slow queries and likely N+1 access patterns, an **Exceptions** diagnostics panel that captures and
groups runtime exceptions, and an opt-in, local-only **MCP server** (with a Developer Tools panel) that exposes BootUI's
advisors and read-only diagnostics to local AI coding agents. It also sharpens the Traces panel, strengthens the CRaC
advisor, fixes two GraalVM native-image issues in the starter, hardens the sample app's JVM and native Docker images to
zero OS-package CVEs, shrinks the packaged UI, and polishes the console shell.

### Added

- **SQL Trace panel** — a new Database panel showing the SQL statements the application recently executed, captured by a
  hand-written JDBC tracing proxy built on the JDK's own dynamic-proxy support (no third-party database-proxy library).
  It transparently wraps each `DataSource` and records SQL text, statement/category, wall-clock duration, affected rows,
  batch size, connection, thread, and failures into a bounded in-memory ring buffer; groups identical statements; flags
  likely **N+1** access patterns and slow queries; and offers local-only Pause/Resume and Clear actions. Parameter
  capture is off by default and masked when enabled, wrapping fails open, and the JDK proxies are registered for GraalVM
  native images. Configurable under `bootui.sql-trace.*` (#359).
- **Exceptions panel** — a new Diagnostics panel that captures, groups, and surfaces exceptions thrown by the host
  application. Capture uses two observe-only sources (a `HandlerExceptionResolver` for MVC handler exceptions with
  request context, and a Logback root appender for anything logged with a throwable), deduplicated by `Throwable`
  identity and grouped by a SHA-256 fingerprint of the class and top stack frames, with bounded recent occurrences and
  the cause chain. Messages are masked, request paths are captured without query strings, and the clear action honors
  read-only. Configurable under `bootui.exceptions.*` (#358).
- **MCP server for AI agents** — BootUI can optionally expose its advisors and read-only diagnostics to local AI coding
  agents (GitHub Copilot, Claude Code) through an opt-in, local-only
  [Model Context Protocol](https://modelcontextprotocol.io) server. It is a hand-rolled JSON-RPC 2.0 endpoint at
  `POST /bootui/api/mcp`, disabled by default (`bootui.mcp.enabled=OFF`), that reuses the existing controllers and DTOs
  so every tool returns the same masked, bounded shape as the REST API — advisor scans as action tools, plus diagnostics
  and core-context read tools — and inherits BootUI's full safety model. A new **MCP Server** panel (top of Developer
  Tools) documents the exposed tools, shows connection details and a copyable client-configuration JSON, and toggles the
  server on or off at runtime, overriding the configured mode. Configurable under `bootui.mcp.*` (#368, #370).
- Three new **CRaC readiness checks** — `CRAC-FILE-001` (direct file-handle opens), `CRAC-CACHE-001` (live
  `CacheManager` beans), and `CRAC-CONFIG-001` (static initializers capturing env/properties) — plus broadened
  `CRAC-RES-001`, `CRAC-SECRET-001`, and `CRAC-POOL-001` coverage, all catalogued in
  [`docs/CRAC-READINESS-CHECKS.md`](docs/CRAC-READINESS-CHECKS.md) (#355).
- New `bootui.sql-trace.*`, `bootui.exceptions.*`, and `bootui.mcp.*` configuration properties, all catalogued in
  [`docs/PROPERTIES.md`](docs/PROPERTIES.md).
- A brand favicon for the console UI, the sample app, and the VuePress documentation site (#364).

### Changed

- **Traces panel now shows the HTTP request path** a trace served (falling back to the root span name when no path
  attribute is present) instead of labelling traces with generic root spans, and **fully excludes BootUI's own
  traffic**: self-span filtering is now trace-level, so once any span identifies a trace as BootUI's own the whole trace
  — and sibling spans exported in other OTLP batches — is dropped. Applied to both the in-process span exporter and the
  `/bootui/api/otlp` receiver (#375).
- **Polished the console shell** — collapsed-sidebar hover flyouts, a mobile overlay drawer and responsive topbar, a
  pulsing "live" dot on the auto-refresh toggle, recently visited panels floated to the top of the command palette, and
  human-readable byte sizes in Health details (#362).
- **Shrank the packaged UI JAR** by subsetting the Bootstrap Icons font and CSS to only the icons BootUI uses (#363).
- **Hardened the sample app's JVM Docker image** (published as `jdubois/bootui-sample-app`) to carry no known OS-package
  CVEs. Its runtime stage now uses Google's distroless glibc base (`gcr.io/distroless/base-debian12:nonroot`, the same
  base `Dockerfile-native` uses) instead of Alpine, which removes the vulnerable `openssl` (`libssl3`/`libcrypto3`) and
  `busybox` packages a scanner previously flagged. The `jlink` runtime is now assembled on the glibc JDK to match the
  base; because distroless ships no shell, JVM flags moved from a `sh -c $JAVA_OPTS` entrypoint to `JAVA_TOOL_OPTIONS`,
  the entrypoint is exec-form, and the Docker `HEALTHCHECK` was dropped (probe `/actuator/health` from your orchestrator
  instead, as the native image already documents) (#365).
- **Hardened the sample app's GraalVM native Docker image** by building a mostly-static binary and switching the runtime
  stage to `gcr.io/distroless/base-debian12:nonroot`, taking it (and the BootUI-generated `Dockerfile-native`) to zero
  CVEs; the curl-based `HEALTHCHECK` was dropped (#356).
- **Shrank the sample app's JVM Docker image from 739MB to 338MB** by exploding the repackaged Spring Boot jar into
  layers, assembling a curated `jlink` runtime, and using a minimal Alpine final stage (#361).
- Refactored the sample app into clean feature packages for clearer demos and integration tests (#371).

### Fixed

- **GraalVM native image: the Mappings panel no longer fails.** `GET /bootui/api/mappings` (the compatibility
  endpoint that returns Actuator's raw mappings descriptor) threw a `MissingReflectionRegistrationError` in a native
  image because Jackson reflectively instantiates the array forms of Actuator's nested `MediaTypeExpressionDescription`
  / `NameValueExpressionDescription` types while serializing them. BootUI's `RuntimeHints` now register those types and
  their array forms, so consumers of the starter no longer need to declare the hints themselves (#367).
- **GraalVM native image: BootUI no longer self-reports as "Disabled" while running.** In a native image the activation
  condition is frozen at AOT build time, but the `BootUiActivation` bean recomputed it against the live runtime
  environment (where the build-time `dev` profile / `bootui.enabled=ON` no longer apply), so the Overview panel and the
  startup log claimed BootUI was disabled even though it was serving. When running AOT-generated artifacts, BootUI now
  trusts the frozen build-time decision and reports the accurate enabled state (#367).
- Exempted the `/bootui/api/mcp` endpoint from Spring Security's SPA CSRF token so non-browser MCP clients (e.g. VS
  Code) can connect without a token, while `LocalhostOnlyFilter`'s loopback, `Host` allow-list, and cross-site
  defenses still apply (#370).
- Fixed VuePress documentation anchor links landing on the wrong section (#374).

## [1.3.0] - 2026-06-11

Feature release headlined by two new GraalVM/CRaC capabilities — a new **CRaC (Coordinated Restore at Checkpoint)
readiness panel** and **GraalVM reachability-metadata** support (repository lookup plus an install-into-source-tree
action) — alongside an upgrade to **Spring Boot 4.1.0** and a second wave of advisor improvements (Memory, REST API,
GraalVM) authored with Anthropic's new **Claude Fable 5** model, on top of the 1.2.0 hardening pass. It also makes BootUI
reachable from inside containers through narrow, fail-closed opt-ins (`bootui.trusted-proxies` and
`bootui.trust-container-gateway`), runs the sample app Docker-free by default, and adds a cross-platform CI build matrix.

### Added

- **CRaC readiness panel** — a new Runtime panel that reviews the host application's
  [Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
  readiness. It reports whether the `org.crac` API is on the classpath, whether the running JVM is a CRaC-capable JDK
  (detected via the real CRaC implementation rather than the no-op shim), whether `spring.context.checkpoint=onRefresh`
  is set, and any `-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom` JVM arguments. It scans the host application's own
  classes against a curated set of `CRaC-*` checks (including the `CRAC-POOL-001` connection-pool readiness check) for
  constructs that complicate checkpoint/restore, and generates ready-to-use container assets — a multi-stage
  `Dockerfile-crac` plus a `checkpoint-and-run.sh` entrypoint. The full catalogue lives in
  [`docs/CRAC-READINESS-CHECKS.md`](docs/CRAC-READINESS-CHECKS.md) (#322).
- **GraalVM reachability-metadata lookup** — the GraalVM panel queries the reachability-metadata repository for the
  host's dependencies and adds an _install into source tree_ action that writes the metadata into the project; the
  "Include dependencies" toggle now defaults to on (#324, #331).
- A second wave of advisor rules on top of the 1.2.0 hardening pass: **7 new GraalVM checks** (plus the new
  `GRAAL-FFM-001` Foreign Function & Memory check, replacing the AWT check), **7 new Memory rules**, and **9 new REST API
  rules**, all catalogued in the refreshed `docs/*-CHECKS.md`. This wave of advisor work was authored with Anthropic's
  new Claude Fable 5 model.
- **`bootui.trusted-proxies`** — an opt-in list of source IP ranges (CIDR notation, e.g. `172.16.0.0/12`) trusted in
  addition to loopback by the safety filter. Lets local Docker-bridge callers reach BootUI without the blunt
  `bootui.allow-non-localhost=true`: it relaxes only the source-address check and keeps the `Host` allow-list
  (DNS-rebinding) and cross-site write (CSRF) protections in force. Pair it with `bootui.allowed-hosts` for the hostname
  the browser uses.
- **`bootui.trust-container-gateway`** (`OFF` / `AUTO` / `ON`, default `OFF`) — a one-flag opt-in to trust the
  auto-detected container gateway as a single `/32`, so BootUI can be reached inside a container with a published port
  without knowing the subnet or setting a broad `bootui.trusted-proxies` CIDR. Detection covers both the Linux Docker
  Engine bridge gateway (from `/proc/net/route`) and the Docker Desktop gateway (via the `gateway.docker.internal` DNS
  name). Like `bootui.trusted-proxies`, it relaxes only the source-address check and keeps the `Host` allow-list and
  CSRF protections in force.
- **Cross-platform CI** — a build workflow matrix that runs the full build on Linux, Windows, and macOS.
- A "Docker container access" section in [`docs/SETUP.md`](docs/SETUP.md) covering the trusted-proxies and
  container-gateway options.

### Changed

- **Upgraded to Spring Boot 4.1.0** (from 4.0.x), including aligning the Hibernate advisor end-to-end tests with
  Hibernate 7.4 collection-fetch behavior (#326).
- Marked the **JVM Tuning, GraalVM, Architecture, REST API, and CRaC** panels unavailable in GraalVM native images,
  where their underlying JVM/bytecode analysis cannot run. The startup-timeline buffer is now installed for AOT images
  even when BootUI is inactive, so timeline data is captured if BootUI is later enabled.
- **`bootui-sample-app` now runs Docker-free by default** — the `dev` Spring profile (the default via
  `spring.profiles.default=dev`) swaps the Docker Compose PostgreSQL, Redis, and Ollama services for an in-memory H2
  database, a simple in-memory cache, and disabled Spring AI, so a bare `spring-boot:run` (and the Playwright e2e suite)
  starts offline with no Docker engine. A new `docker` profile (`-Dspring-boot.run.profiles=docker`) restores the full
  Docker experience (PostgreSQL, Redis, Ollama, and the `qwen2.5:0.5b` chat model).
- **"Try the sample app" now uses the published Docker image** — [`docs/TRY-SAMPLE-APP.md`](docs/TRY-SAMPLE-APP.md) runs
  `jdubois/bootui-sample-app` (the JVM image), with `docker run` command lines for the CRaC
  (`jdubois/bootui-sample-app-crac`) and GraalVM native (`jdubois/bootui-sample-app-native`) images too. The
  `scripts/run-sample.sh` and `scripts/run-sample.ps1` helper scripts, which cloned and built the repository locally,
  were removed.
- The sample app's three Dockerfiles (JVM, CRaC, and native) now default to the `dev` profile with Flyway/Liquibase
  disabled for fast startup, and the JVM and CRaC images set explicit JVM tuning flags.
- Shared a single source-tree writer and build-system detection routine across the GraalVM and CRaC config generators.

### Fixed

- BootUI now loads correctly when the host application sets a non-root `server.servlet.context-path` (#332).
- Fixed the generated `Dockerfile-native` Maven build for plain Spring Boot applications (#325).
- Normalized install display paths to forward slashes on Windows so the setup snippets render correctly.
- Removed the ArchUnit gate from CRaC availability — only running in a native image disables the panel.
- Fixed broken documentation links and aligned the VuePress navbar logo with the sidebar toggle.

## [1.2.0] - 2026-06-09

Feature release headlined by a **sweeping hardening pass across all eight rule-based advisors** — recalibrated
severities, far fewer false positives and negatives, and a wave of new high-signal checks — backed by the new ability to
**dismiss and restore advisor findings**. It also makes the console load even when the host disables Spring's
static-resource mappings, and fixes scheduled-task and sample-app native-image regressions.

### Added

- **New high-signal advisor checks** added across the rule advisors during the hardening pass, including Architecture
  (`ARCH-SPRING-017`, `ARCH-SPRING-018`, `ARCH-SPRING-019`, `ARCH-SPRING-021`, `ARCH-MOD-001`), REST API (`RAPI-MAP-008`,
  `RAPI-RESP-008`, `RAPI-VER-005`), and GraalVM (`GRAAL-CLASSGEN-001`, `GRAAL-INIT-002`, `GRAAL-SER-002`,
  `GRAAL-SCAN-001`, `SPRING-AOT-001`, `SPRING-AOT-002`, plus new class-generation, classpath-scanning, and Spring-AOT
  categories), with further new Spring, Hibernate, Memory, Security, and Pentesting rules catalogued in the refreshed
  `docs/*-CHECKS.md`.
- Added `CRITICAL` severity to the Architecture, REST API, Spring, Hibernate, Security, Pentesting, and GraalVM advisors;
  official "learn more" links to the Architecture, Pentesting, and GraalVM panels; and a muted analysis-error channel on
  the Architecture, Spring, Security, and Memory panels that surfaces rules which throw during evaluation.
- **Dismiss / restore advisor findings** — every finding surfaced by the seven Overview-scored rule advisors
  (Architecture, REST API, Spring, Hibernate, Memory, Security, Pentesting) now carries a _Dismiss_ button. Dismissed
  rules collapse into a "Dismissed rules" list at the bottom of the panel and are excluded from the panel's finding
  count, severity bars, the panel's own advisor score, and the weighted Overview score; they can be restored at any time.
  Dismissals are keyed by the globally unique rule IDs, applied server-side, and persisted under the `dismissedRules`
  node of a developer-local `.bootui/boot-ui.yml` file (next to the runtime overrides file), so they survive restarts and
  stay consistent between each panel and the Overview dashboard.
- Per-advisor **0–100 score** now shown on each of those advisor panels (100 minus the weighted finding penalty), always
  matching the value the Overview dashboard computes for that advisor, rendered through a shared `AdvisorScoreCard`.

### Changed

- **Hardened every rule-based advisor (Phases 0–8)** — Architecture, REST API, Spring, Hibernate, Memory, Security,
  Pentesting, and GraalVM — with context- and profile-aware dynamic severity, recalibrated thresholds, canonical Spring
  Boot 4 property names, and fewer false positives/negatives. The matching `docs/*-CHECKS.md` catalogues were refreshed.
- Reworked Memory heap-pressure detection to measure pressure from a post-GC dual snapshot and to track GC-overhead
  trend across scans rather than within a single forced GC.
- Extracted shared UI building blocks (`FlashBanner`, `SpinnerButton`, `ReadOnlyNotice`, `AdvisorScoreCard`) and a shared
  `AgentSessionController` base for the Copilot and Claude Code panels.
- Changed the Spring panel icon from a lightbulb to a leaf.
- Restructured the documentation site: install the starter via a dedicated dev Maven/Gradle profile, render the
  Maven/Gradle setup as VuePress tabs, reordered the setup sections, and removed the README badges.

### Fixed

- Serve the BootUI console assets even when the host sets `spring.web.resources.add-mappings=false`: a dedicated
  `WebMvcConfigurer` maps `/bootui/**` to the bundled SPA without re-exposing the host's own static resources, and
  disabled static-resource mappings are now logged at `WARN` with a troubleshooting note (#291).
- Scheduled Tasks panel no longer returns HTTP 500 when the host application registers its own `ScheduledTaskHolder`
  bean; tasks are aggregated across every holder so programmatically registered timers appear alongside `@Scheduled`
  tasks (#288).
- Fixed the sample app's native-image smoke test for its new `@Inheritance(TABLE_PER_CLASS)` and `UUID` `@Id` demo
  entities by registering reflection hints for the `UnionSubclassEntityPersister` constructor and the `UUID[]` multi-id
  loader array in the sample app's native-hints configuration.
- Fixed a Liquibase connection leak and a brittle cache type check flagged by static analysis, and stabilized the
  Hibernate advisor end-to-end tests against cached scan state and duplicated paged collection-fetch detail rendering.

## [1.1.0] - 2026-06-07

Feature release that introduces a dedicated **Advisors** workspace with three new rule-based panels (Spring, REST API,
Memory), expands every existing advisor catalogue, lets the console run from non-web applications, and reorganizes the
runtime memory panels — while hardening the safety filter and correcting the Actuator-defaults precedence.

### Added

- **Spring panel** — new advisor that inspects the running application for Spring and Spring Boot 4 best-practice issues,
  shipping 31 curated rules documented in `docs/SPRING-CHECKS.md`.
- **REST API panel** — new advisor that audits controller/handler mappings against 36 curated REST design rules,
  documented in `docs/REST-API-CHECKS.md`.
- **Memory panel** — new advisor with 22 heap, native, GC, and finalizer checks documented in `docs/MEMORY-CHECKS.md`.
- **Live Memory** runtime panel showing live JVM memory-pool usage, split out from the previous runtime Memory panel.
- Support for serving the BootUI console from **non-web (command-line) Spring Boot applications**, not just servlet web
  apps.
- GitHub panel **open-issues drawer** that lists open repository issues with bounded refreshes.
- Five new Spring Security checks — BCrypt work-factor floor (`SEC-AUTH-006`), Referrer-Policy and Permissions-Policy
  headers (`SEC-HEAD-005`/`SEC-HEAD-006`), concurrent session control (`SEC-SESSION-007`), and HTTPS enforcement in
  production (`SEC-CONFIG-006`), documented in `docs/SECURITY-CHECKS.md`.
- Expanded advisor coverage across the board: GraalVM readiness grows from 5 to 12 checks, plus new Architecture,
  Hibernate, and Memory rules, all reflected in their `docs/*-CHECKS.md` catalogues.

### Changed

- Grouped the rule-based panels under a dedicated **Advisors** navigation group (Architecture, REST API, Spring,
  Hibernate, Memory, Security) alongside Pentesting and Vulnerabilities, and wired the new advisors into the Overview
  security & health scoring dashboard.
- Renamed panels for clearer URLs and class names: dropped the "Advisor" suffix from the rule-based panels, renamed
  Tuning Advisor to **JVM Tuning** and Dependencies to **Vulnerabilities**, and split the runtime Memory panel into
  **Live Memory** and **JVM Tuning**. Legacy routes (`/security-advisor`, `/hibernate-advisor`, `/tuning-advisor`,
  `/pentest`, `/dependencies`, `/rest-advisor`, `/spring-advisor`, `/memory-advisor`, `/profiles`) redirect to the new
  paths.
- Aligned endpoint, controller, and DTO naming for Log Tail, HTTP Probe, Database Connection Pools, and Profile Diff,
  and fixed the Database Connection Pools component pluralization.
- Standardized the empty/unavailable panel state behind a shared `UnavailableState` component and consolidated shared
  time/number formatting helpers across views.
- Renamed the advisor catalogue docs from `*-ADVISOR-CHECKS.md` to `*-CHECKS.md`, closed rule-ID numbering gaps so every
  sequence is continuous, corrected drifted Hibernate doc entries (`HIB-CONFIG-016`, `HIB-CONFIG-017`, `HIB-MAP-018`,
  `HIB-MAP-019`), and rewrote `docs/REST-API-CHECKS.md` in the shared `### ID - Title` format. Renumbered rule IDs:
  `MEM-GC-002` → `MEM-GC-001`, `MEM-GC-003` → `MEM-GC-002`, `RAPI-VALID-003` → `RAPI-VALID-002`, `RAPI-VALID-004` →
  `RAPI-VALID-003`, and `HIB-FETCH-007` → `HIB-FETCH-006`.
- Gave AI Usage, Copilot, and Claude Code their own documentation sections and synced `docs/FEATURES.md`,
  `docs/PROPERTIES.md`, the README feature table, and the screenshots with the Advisors regrouping.
- Scoped the Maven Central release secrets to a protected `maven-central` GitHub environment.

### Fixed

- BootUI's Actuator defaults are now contributed as true lowest-priority defaults so a host application's
  `EnvironmentPostProcessor` settings always win (#246).
- Hardened the localhost-only safety filter and added value-based secret masking for browser-visible property values.
- Fixed dark-mode contrast on Bootstrap contextual utilities and on the GitHub quota metric cards.
- Made the developer tooling more robust: `run-sample.sh` no longer fails on macOS Bash 3.2, the getting-started scripts
  can target `main` or one of the last five tags, the Maven offline setup primes `spring-boot-maven-plugin`, and the
  Copilot dev server self-heals on a cold worktree `.m2`.
- Fixed documentation-site build failures (GitHub Pages Node 24 configuration, backticked angle-bracket type fragments,
  and the Hibernate advisor image) and pinned the Ollama Docker Compose port to stabilize the e2e startup.

## [1.0.0] - 2026-06-05

First stable BootUI release, focused on promoting the current local developer-console surface to `1.0.0`, adding the
Spring Security Advisor, and publishing the redesigned documentation site.

### Added

- Security Advisor panel with explicit Spring Security hardening checks for authentication, authorization, CSRF, sessions,
  headers, CORS, method security, actuator exposure, OAuth2 resource-server validation, and security configuration hygiene,
  plus the `docs/SECURITY-ADVISOR-CHECKS.md` rule catalogue.
- Overview security & health scoring dashboard that can run the available Architecture, Hibernate Advisor, Security
  Advisor, Vulnerabilities, Pentesting, and GitHub scanners individually or together.
- VuePress documentation site, GitHub Pages workflow, repository documentation, and setup/sample-app pages for the public
  docs at `julien-dubois.com/boot-ui`.

### Changed

- Copilot and Claude Code dashboards now emphasize input/output token usage charts while retaining event and failure
  views for sanitized local agent activity.
- Refreshed release-facing screenshots for the 1.0 surface, including Overview, Security Advisor, Copilot, and Claude Code,
  and verified the screenshot set against the routed panel list.
- Updated the implementation roadmap so completed 1.0 work is separated from the next workstream for trace/log/request
  correlation, bean graph visualization, and an e-mail viewer.

### Fixed

- Detected proxied Hikari data sources in the Database Connection Pools panel.
- Corrected Spring Modulith Flyway migration reporting so module-specific history tables remain visible and read-only.
- Fixed VuePress markdown links, homepage setup navigation, GitHub Pages Node 24 configuration, and sample quick-start
  script UI bundling.

## [0.5.1] - 2026-06-04

Patch release focused on preserving BootUI startup in applications that do not include Spring Security Core while
keeping Security Logs support available when Spring Security authentication events are present.

### Fixed

- Guarded BootUI's auto-configured Spring Security audit event repository behind Spring Security authentication event
  classes so applications without `spring-security-core` no longer fail at startup.

## [0.5.0] - 2026-06-04

Fifth BootUI release, focused on repository context, servlet session inspection, database migration/advisor tooling, and
release-facing documentation for the expanded 0.5.0 panel surface.

### Added

- GitHub dashboard panel under Overview, with local repository detection, bounded refreshes for pull requests, issues,
  latest GitHub Actions executions, dynamic rate-limit/quota drawers, security signals, and Copilot usage report metadata.
- HTTP Sessions panel backed by embedded Tomcat session metadata, with masked session identifiers and attributes by
  default plus confirmation-gated clear/destroy actions.
- Database navigation group with read-mostly Flyway and Liquibase panels, including migration/change-set inventory and
  confirmation-gated `migrate`, `clean`, and `update` actions.
- Hibernate Advisor panel with explicit Hibernate/JPA mapping, configuration, caching, and repository-query checks, plus
  the `docs/HIBERNATE-CHECKS.md` rule catalogue.
- Auto-configured an in-memory Spring Boot `AuditEventRepository` for Security Logs when BootUI is active, audit events are
  enabled, and the host app has not provided its own repository.
- Sample-app quick-start scripts for macOS/Linux and Windows PowerShell.

### Changed

- Standardized panel auto-refresh controls and visibility-aware refresh behaviour across live panels.
- Updated the GitHub Actions drawer to show latest execution details and count only the latest run per workflow when
  reporting workflow failures.
- Expanded the sample app with Flyway/Liquibase schemas, richer Hibernate/JPA sample mappings, HTTP session data, security
  events, and release screenshots for the current sidebar surface.
- Reworked the implementation roadmap so already-shipped database/security/runtime panels moved out of the plan and the
  next workstream focuses on trace/log/request correlation, bean graph visualization, and an e-mail viewer.

### Fixed

- Corrected AI Usage telemetry KPIs and summary calculations.
- Matched the exact BootUI root paths in BootUI's highest-priority Spring Security chain so host SPA fallback filters do
  not intercept `/bootui` before BootUI can redirect to its console.
- Tightened security diagnostics, value exposure handling, panel availability wiring, and release documentation for the
  0.5.0 panel surface.

## [0.4.0] - 2026-06-03

Fourth BootUI release, focused on new local runtime/security diagnostics, native-image readiness tooling, and the
current grouped sidebar surface.

### Added

- GraalVM native-image readiness panel with on-demand host-application checks for reflection, dynamic proxies, resources,
  serialization, native access, dependency reachability metadata, and a reviewable `reachability-metadata.json` scaffold.
- Threads panel backed by in-process `ThreadMXBean` snapshots, with state counts, deadlock detection, virtual-thread
  context, server-side filtering/paging, stack expansion, and confirmation-gated raw dump download.
- HTTP Exchanges panel for recent inbound application requests, including bounded recording, server-side filtering,
  masked headers/query data, trace identifiers, and drawer-style request/response details.
- Security Logs panel and Security navigation group for recent Spring Boot audit events, bounded retention, masking,
  filters, and visibility-aware auto-refresh.
- Weekly GraalVM native-image build workflow and native-image sample-app Docker assets/readiness documentation.

### Changed

- Renamed the Cache surface to Spring Cache across the route metadata, docs, and release-facing screenshots.
- Updated HTTP Exchanges and Security Logs to use standard visibility-aware auto-refresh instead of manual refresh
  buttons.
- Updated the next-feature roadmap so already-shipped panels moved out of the plan and the next workstream is focused on
  migrations, trace/log/request correlation, and bean graph visualization.
- Bumped build and dependency plumbing, including Spring AI 2.0.0-M8, the GraalVM native build tools plugin, Sonatype
  Central publishing plugin, Maven plugins, `actions/checkout`, and the frontend/API error-handling utilities.

### Fixed

- Added Spring AOT runtime hints and sample native-image wiring so BootUI resources, DTOs, heap-dump/security reflective
  calls, and Maven metadata survive native-image builds.
- Updated feature documentation, sample-app walkthroughs, security policy, Playwright docs, and screenshots for the
  current sidebar grouping and full 0.4.0 panel surface.
- Completed Java/frontend audit follow-ups around nullability, shared helpers, frontend API normalization, and duplicate
  utility removal.

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

[Unreleased]: https://github.com/jdubois/boot-ui/compare/v1.20.0...HEAD
[1.20.0]: https://github.com/jdubois/boot-ui/compare/v1.19.0...v1.20.0
[1.19.0]: https://github.com/jdubois/boot-ui/compare/v1.18.0...v1.19.0
[1.18.0]: https://github.com/jdubois/boot-ui/compare/v1.17.0...v1.18.0
[1.17.0]: https://github.com/jdubois/boot-ui/compare/v1.16.0...v1.17.0
[1.16.0]: https://github.com/jdubois/boot-ui/compare/v1.15.0...v1.16.0
[1.15.0]: https://github.com/jdubois/boot-ui/compare/v1.14.1...v1.15.0
[1.14.1]: https://github.com/jdubois/boot-ui/compare/v1.14.0...v1.14.1
[1.14.0]: https://github.com/jdubois/boot-ui/compare/v1.13.1...v1.14.0
[1.13.1]: https://github.com/jdubois/boot-ui/compare/v1.13.0...v1.13.1
[1.13.0]: https://github.com/jdubois/boot-ui/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/jdubois/boot-ui/compare/v1.11.0...v1.12.0
[1.11.0]: https://github.com/jdubois/boot-ui/compare/v1.10.0...v1.11.0
[1.10.0]: https://github.com/jdubois/boot-ui/compare/v1.9.0...v1.10.0
[1.9.0]: https://github.com/jdubois/boot-ui/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/jdubois/boot-ui/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/jdubois/boot-ui/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/jdubois/boot-ui/compare/v1.5.2...v1.6.0
[1.5.2]: https://github.com/jdubois/boot-ui/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/jdubois/boot-ui/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/jdubois/boot-ui/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/jdubois/boot-ui/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/jdubois/boot-ui/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/jdubois/boot-ui/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jdubois/boot-ui/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jdubois/boot-ui/compare/v0.5.1...v1.0.0
[0.5.1]: https://github.com/jdubois/boot-ui/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/jdubois/boot-ui/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/jdubois/boot-ui/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/jdubois/boot-ui/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0-alpha.5...v0.1.0
[0.1.0-alpha.5]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.5
[0.1.0-alpha.4]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.4
[0.1.0-alpha.3]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/jdubois/boot-ui/commit/6ad9e3371c1c92d82597400ffa9063b7746bafe7
