# Framework support

BootUI runs on three stacks from one codebase: **Spring MVC**, **Spring WebFlux**, and **Quarkus**. They share the same
engine, the same console, and the same JSON contract, so what you learn on one carries over to the others.

| Stack          | Panel availability                                  | Setup                               |
| -------------- | --------------------------------------------------- | ----------------------------------- |
| Spring MVC     | Every panel. This is the reference stack.           | [Setup](./SETUP.md)                 |
| Spring WebFlux | Every panel except **HTTP Sessions**.               | [WebFlux setup](./setup/webflux.md) |
| Quarkus        | Most panels — see [below](#what-is-not-on-quarkus). | [Quarkus setup](./setup/quarkus.md) |

## Startup compatibility

BootUI targets **Spring Boot 4.0+ within the 4.x line** with Spring Framework 7, and **Quarkus 3.33+ within
the 3.x line**. The build baseline and older LTS coverage are recorded in [Repository and documentation](REPOSITORY.md).
Supported patches do not need to equal BootUI's build patch.

Startup diagnostics use stable `BOOTUI-COMPAT-*` identifiers:

- **A proven incompatible platform stops startup.** Spring Boot 3 is not supported. The diagnostic applies even with
  `bootui.enabled=OFF`: that flag cannot repair the starter's incompatible transitive framework dependencies. Remove
  the starter or upgrade the application platform.
- **Broken required security-chain APIs stop startup rather than weakening the chain.** MVC and WebFlux use separate
  checks. An application without Spring Security remains supported.
- **Unknown version metadata is not an incompatibility.** A newer framework generation outside the declared target
  produces a warning; a different supported patch does not.
- **Expected missing integrations do not stop startup.** No Kafka, Hibernate, JDBC datasource or agent simply means
  the corresponding evidence is unavailable. An incompatible attached agent remains unused with a warning.
- **Reactive SQL capture is incomplete.** Startup warns for configured reactive database clients, including hybrid
  applications whose JDBC datasource is used only for migrations. JDBC observations never establish what reactive
  queries ran.

Warnings are independent of the startup URL banner. Checks use local version/API/bean metadata, not network requests,
database connections, broker probes or whole-classpath scans. AOT/native builds evaluate registration checks while
generating their bean definitions; native startup does not repeat arbitrary library reflection.

This is not certification of every transitive library version. BootUI does not automatically disable itself, rewrite
dependencies, or impose exact CLI/server version equality. Some invalid dependency graphs fail in the framework or
resolver before BootUI's diagnostic can run. Java versions below 17 can likewise be rejected by the JVM before any
BootUI code executes.

Bytecode advisors validate application class-file headers only during an explicit scan. Unsupported or unreadable
headers yield an error/incomplete result rather than a clean assessment of a silently imported subset. Header
validation does not certify every class body, dependency resolution, or classpath-discovery result.

### Maintaining compatibility during upgrades

The root `spring-boot.version` and `quarkus.platform.version` properties select the **build baseline**, not an
exact-version allowlist. For a dependency upgrade:

- Keep the declared support range unless changing it is an explicit, reviewed decision. Ordinary supported
  patch/minor upgrades do not require widening the runtime rules.
- Run the current-baseline conformance checks and retain older supported consumer coverage: the Spring Boot 4.0
  fixture in `.github/scripts/consumer-smoke-tests.sh` and the older Quarkus LTS lane in `build.yml`.
- Preserve the rejected-platform consumer cases and their actionable diagnostics. Fix API incompatibilities rather
  than weakening checks or removing assertions to make a bump pass.
- For an intentional generation or minimum-support change, update `PlatformCompatibility`, the affected adapter API
  checks, this support documentation, consumer fixtures, and Quarkus descriptor range where applicable together.
  A future-generation warning does not automatically declare that generation supported.

The shared policy lives in `bootui-engine` under `engine/compatibility`; Spring's early listener and security API
checks live under `autoconfigure/compatibility`. Quarkus checks the platform in its deployment module. Keep AOT/native
registration semantics intact when changing these checks.

## Your app is the real answer

This page describes the stacks. **BootUI describes your application**, which is what you actually need, because most
panels also depend on what you have on the classpath — no Kafka, no Kafka panel.

Open the console and almost every panel that cannot run tells you so directly: it stays in the sidebar with a tooltip,
and opening it shows a banner with the specific reason. The same information is on `/bootui/api/panels`, so agents and
scripts read it too.

The PostgreSQL and MySQL panels behave differently, because they are vendor-specific. Without that vendor's JDBC driver
on the runtime classpath they do not appear at all, so a manifest can legitimately carry 63 panels rather than the 65
in the catalog.

That is always current for your app and your dependencies. Prefer it over any list in the documentation.

### JDBC diagnostics are datasource-specific

An HTTP stack does not determine a database client's capabilities. PostgreSQL needs its corresponding JDBC
datasource; the **[MySQL operational panel](features/database.md#mysql)** provides the same JDBC-backed
behavior on MVC, WebFlux, and Quarkus. Default and named JDBC datasources are in scope. A WebFlux app with only R2DBC,
or a Quarkus app with only a reactive MySQL client, cannot use this panel.

Oracle MySQL 8.4 LTS and 9.7 LTS are the tested server lines, with live coverage on 8.4.6 and 9.7.2. Other Oracle MySQL
versions are read with an "untested server line" note. MariaDB reached through MySQL Connector/J is read on a
best-effort basis and labelled unsupported, without replication receiver state or counter comparisons; it has no
automated coverage, and MariaDB Connector/J is not offered the panel. Other compatible flavors are skipped; the
feature guide lists the tested driver/pool combinations.

## Spring WebFlux

Everything works, including Java Agent runtime sensor switches and every action — setting log levels, running migrations,
capturing heap dumps, and every advisor scan — behind the same safety rules as the servlet stack.

The single exception is **HTTP Sessions**, which inventories embedded Tomcat's servlet sessions. WebFlux's
`WebSession` is a different model, with no equivalent container-managed session inventory.

## What is not on Quarkus

Nine panels are permanently not applicable. In every case the capability is either absent from the stack or already
covered by something else:

| Panel                                | Why, and what to use instead                                                                       |
| ------------------------------------ | -------------------------------------------------------------------------------------------------- |
| **GraalVM**, **CRaC**                | Quarkus is native-first and starts fast by design. Use its own native build.                       |
| **Conditions**, **Startup Timeline** | Quarkus resolves wiring at build time, so there is no runtime graph or step timeline.              |
| **Spring Security**, **Spring Data** | Quarkus uses Elytron/OIDC and Panache. Use the Quarkus Security advisor and the Hibernate advisor. |
| **Spring DevTools**                  | Quarkus dev mode already owns live reload.                                                         |
| **HTTP Sessions**                    | Reactive and stateless by default, as on WebFlux.                                                  |
| **Transactions**                     | Boundary capture needs a Spring hook that Narayana and CDI do not expose.                          |

One panel, **JMS**, is not yet available: the capture targets Spring JMS. Use the **Kafka** or **RabbitMQ** panels for
Quarkus Reactive Messaging.

Two smaller gaps are worth knowing. The Hibernate advisor runs its mapping and configuration rules, but its query rules
need Spring Data repository metadata that Panache does not expose. And some capture panels reconstruct their data from
Vert.x rather than a servlet thread. Live Activity and request profiles correlate first through BootUI's own request
or execution id, without requiring tracing, and then through an unambiguous OpenTelemetry trace id when present.
Serving-thread and time-window request-profile joins are deliberately unavailable there.

Everything else ships, including the Java Agent panel, the whole advisor and scoring surface, and the MCP server, which
is where BootUI adds the most on a stack that already has a dev UI.

## Going deeper

Per-panel behavior lives with the panel, in [Features](./features/README.md).

If you want the engineering rationale — how the adapters share code, why each panel was ported, adapted, rebuilt, or
dropped, and what remains open — that is in the design notes for
[Quarkus](./QUARKUS-SUPPORT.md) and [Spring WebFlux](./WEBFLUX-SUPPORT.md). They are written for people working on
BootUI itself.
