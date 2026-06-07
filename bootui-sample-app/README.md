# BootUI sample application

This module is a small Spring Boot 4 application that demonstrates the
[BootUI](../README.md) developer console end to end. It is the same app that
the Playwright suite under `e2e/` exercises.

## What it shows

- The `bootui-spring-boot-starter` dependency on a real Spring Boot 4 app.
- BootUI auto-activating when the `dev` profile is active.
- A PostgreSQL-backed Spring Data repository so the Spring Data panel has data to
  show.
- PostgreSQL, Redis, and Ollama Docker Compose services (`compose.yaml`) so the Spring Data,
  Database Connection Pools, Spring Cache, AI Usage, and Dev Services panels have realistic infrastructure
  to show.
- Flyway migrations (the `catalog_*` tables) and Liquibase change sets (the separate
  `inventory_*` tables) with two pending updates each so the Flyway and Liquibase actions can be exercised manually.
- Spring Security, scheduled tasks, custom metrics, and a small static welcome
  page so the corresponding BootUI panels are populated.
- Local diagnostics for Architecture, Pentesting, Vulnerabilities, Traces,
  HTTP Exchanges, Security Logs, Threads, GraalVM, AI Usage, Heap Dump, and other release-supported panels.

## Prerequisites

- Java 17 or later
- Docker (or any Docker-compatible engine) for the Postgres, Redis, and Ollama containers
- The repository's Maven Wrapper (`./mvnw`) — no global Maven install needed

## Run it

From the repository root:

```bash
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev
```

Spring Boot will start Docker Compose, wait for Postgres, Redis, and Ollama, pull the small `qwen2.5:0.5b` chat model
when missing, and then bind the sample app to `http://localhost:8080`.

## Visit BootUI

Open <http://localhost:8080/bootui> in a browser running on the same machine.

Useful URLs:

| URL                                         | What you see                                        |
| ------------------------------------------- | --------------------------------------------------- |
| <http://localhost:8080/>                    | Sample application welcome page                     |
| <http://localhost:8080/bootui>              | BootUI console                                      |
| <http://localhost:8080/bootui/api/overview> | Stable BootUI JSON DTO for the Overview panel       |
| <http://localhost:8080/api/echo>            | Sample REST endpoint surfaced by the Mappings panel |

## Suggested walkthrough

1. **Overview and GitHub** — confirm the activation reason is `profile=dev`,
   localhost-only is `true`, and the local GitHub origin is detected.
2. **Beans** — search for `EchoScheduler` and follow the dependency graph back
   into Spring framework beans.
3. **Conditions** — filter on `DataSourceAutoConfiguration` to see the matched
   and skipped auto-configurations behind the Postgres datasource.
4. **Configuration** — locate `spring.datasource.password`, confirm the value is
   masked, then try toggling `bootui.expose-values` between `MASKED`,
   `METADATA_ONLY`, and `FULL` (only do `FULL` locally) and reload the panel.
5. **Configuration → Add override** — add `logging.level.io.github.jdubois=DEBUG`
   and confirm the override is persisted to
   `.bootui/application-bootui.properties` under the repo's working directory.
6. **Loggers** — set `io.github.jdubois.bootui.sample` to `TRACE`, exercise an
   endpoint, then clear the level and observe the inherited level reappear.
7. **Health, HTTP Sessions, Metrics, Memory, JVM Tuning, Heap Dump, Threads, Startup Timeline, GraalVM** —
   sanity-check the live runtime values, inspect session/thread activity, calculate JVM/container sizing, run native-image
   readiness checks, and use Heap Dump to analyze a value-free class histogram.
8. **Spring Data and Hibernate** — open `BootUiSampleRepository` to inspect its query methods and domain type,
   then run the Hibernate panel to review the sample JPA mappings.
9. **Database Connection Pools** — inspect datasource pool metadata and live
   active / idle / total connection chart without borrowing a connection.
10. **Flyway** — inspect the applied and pending `catalog_*` migrations (versions,
    descriptions, state) tracked in `flyway_schema_history`, then run the two pending
    migrations after browser confirmation.
11. **Liquibase** — inspect the two applied and two pending `inventory_*` change sets
    tracked in `DATABASECHANGELOG`, on a table set fully separate from Flyway's, then
    apply the pending change sets after browser confirmation.
12. **Spring Cache** — verify the Redis-backed `sample-products` and
    `sample-greetings` caches are listed, inspect cache annotations, and clear a
    cache after confirming the action.
13. **Dev Services** — verify the Postgres and Redis Docker Compose entries are
    present and their service-connection metadata matches the actual mapped
    ports.
14. **Spring Security and Security Logs** — inspect filter chains, endpoint rule explanations, and recent masked audit
    events.
15. **Traces, Log Tail, HTTP Exchanges, Architecture, Pentesting, Vulnerabilities** — inspect local telemetry, logs,
    inbound requests, and run explicit local scans as development hygiene prompts.
16. **HTTP Probe** — send a request to `/api/echo`, then try to send one to an
    external host and confirm it is rejected as non-loopback.
17. **AI Usage** — exercise the sample endpoints and local AI helper
    paths, then inspect the retained in-memory spans and token summaries.
18. **DevTools, Dev Services, Copilot, Claude Code** — confirm the developer-tool panels show local status, bounded service
    metadata/logs, and sanitized local agent activity.

## Stop it

`Ctrl-C` the Spring Boot process. Spring Boot will stop Docker Compose.

## Run it as a GraalVM native image

The sample app can also be compiled ahead-of-time into a GraalVM native
executable. A `native` Maven profile is declared in this module's
[`pom.xml`](pom.xml), and ready-to-use Docker assets live at the repository
root:

- [`Dockerfile-native`](../Dockerfile-native) — builds the whole reactor with
  GraalVM 25 and produces a native executable (startup is well under a second).
- [`Dockerfile`](../Dockerfile) — a JVM image built with Eclipse Temurin 25, for
  comparison.
- [`docker-compose-native.yml`](../docker-compose-native.yml) — runs the native
  image together with the PostgreSQL and Redis services it needs.

### With Docker (recommended)

No local GraalVM install is required — the toolchain lives in the build image.
From the repository root:

```bash
# Build and run the native app with Postgres and Redis
docker compose -f docker-compose-native.yml up --build
```

Then open <http://localhost:8080/bootui/> or hit
<http://localhost:8080/actuator/health>. The Compose file binds the app port to
host loopback (`127.0.0.1`) so BootUI remains local-only while still allowing the
browser to reach the containerized app.

To build just the image:

```bash
docker build -f Dockerfile-native -t bootui-sample-native .
```

### With a local GraalVM

With a GraalVM 25+ toolchain on the `PATH`, build the native executable from the
repository root:

```bash
./mvnw -Pnative -DskipTests -pl bootui-sample-app -am package
./bootui-sample-app/target/bootui-sample-app
```

The `-am` flag also builds the BootUI modules the sample app depends on.

### How BootUI is included in the native image

BootUI is a development console that stays disabled outside dev profiles, and it
normally activates because `spring-boot-devtools` is on the classpath — but
devtools is excluded from the native image. Because GraalVM AOT processing
freezes Spring's bean conditions at build time, the `native` profile enables
BootUI for the AOT step (`-Dbootui.enabled=ON` on the `process-aot` execution)
so the panels and BootUI's [`RuntimeHints`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiRuntimeHints.java)
are baked into the executable. Those hints register the classpath resources and
reflective calls that BootUI performs at runtime (Maven `pom.properties`,
configuration metadata, the BootUI version file, the HotSpot diagnostic MXBean
used for heap dumps, and the Spring Security types it inspects) so the
Dependencies, Config, Heap Dump, and Security panels keep working under native.

Applications that embed BootUI through the starter inherit these hints
automatically; they only need to ensure BootUI is active during their own AOT
processing for it to appear in their native image.

## Playwright suite

The Playwright end-to-end tests in [`e2e/`](e2e/README.md) drive the same
sample app and assert that every visible BootUI route loads. See the e2e
README for the run instructions.
