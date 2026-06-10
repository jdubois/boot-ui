# BootUI sample application

This module is a small Spring Boot 4 application that demonstrates the
[BootUI](../README.md) developer console end to end. It is the same app that
the Playwright suite under `e2e/` exercises.

## What it shows

- The `bootui-spring-boot-starter` dependency on a real Spring Boot 4 app.
- BootUI auto-activating in local development (the `dev`/`docker` profiles, or via `spring-boot-devtools`).
- A relational Spring Data repository so the Spring Data panel has data to show
  (in-memory H2 by default, PostgreSQL with the `docker` profile).
- Optional PostgreSQL, Redis, and Ollama Docker Compose services (`compose.yaml`, enabled by the `docker` profile) so the
  Spring Data, Database Connection Pools, Spring Cache, AI Usage, and Dev Services panels have realistic infrastructure
  to show.
- Flyway migrations (the `catalog_*` tables) and Liquibase change sets (the separate
  `inventory_*` tables) with two pending updates each so the Flyway and Liquibase actions can be exercised manually.
- Spring Security, scheduled tasks, custom metrics, and a small static welcome
  page so the corresponding BootUI panels are populated.
- Local diagnostics for Architecture, Pentesting, Vulnerabilities, Traces,
  HTTP Exchanges, Security Logs, Threads, GraalVM, AI Usage, Heap Dump, and other release-supported panels.

## Prerequisites

- Java 17 or later
- The repository's Maven Wrapper (`./mvnw`) — no global Maven install needed
- Optional: Docker (or any Docker-compatible engine) — only for the [full Docker experience](#run-it-with-docker); the
  default run is [Docker-free](#run-it)

## Run it

By default the sample app runs **Docker-free**: a bare run uses the `dev` profile, which swaps PostgreSQL, Redis, and
Ollama for an in-memory H2 database, a simple in-memory cache, and disabled Spring AI, so no Docker engine or model
download is needed:

```bash
./mvnw -pl bootui-sample-app spring-boot:run
```

`dev` is the default Spring profile ([`application-dev.properties`](src/main/resources/application-dev.properties)), so
it applies whenever no other profile is active (a bare run, the Playwright e2e suite, etc.); pass
`-Dspring-boot.run.profiles=dev` explicitly for the same result. Most panels work normally, including Configuration,
Database, Spring Data, Flyway, Liquibase, and Spring Cache. The Chat and AI Usage panels report that AI is unavailable,
and Dev Services lists no containers. The [`run-sample`](../docs/TRY-SAMPLE-APP.md) helper scripts run this Docker-free
`dev` profile.

## Run it with Docker

For the full experience — Postgres, Redis, Ollama, and every panel populated — activate the `docker` profile from the
repository root:

```bash
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=docker
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

This walkthrough follows the default Docker-free `dev` mode (a bare `spring-boot:run`). The Dev Services, Spring Cache,
and AI Usage steps note where the `docker` profile adds Postgres/Redis/Ollama-backed behavior.

1. **Overview and GitHub** — confirm BootUI is active (the activation reason is `devtools` for a bare run, or
   `profile=dev` when you pass the profile explicitly), localhost-only is `true`, and the local GitHub origin is
   detected.
2. **Beans** — search for `EchoScheduler` and follow the dependency graph back
   into Spring framework beans.
3. **Conditions** — filter on `DataSourceAutoConfiguration` to see the matched
   and skipped auto-configurations behind the H2 datasource.
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
12. **Spring Cache** — verify the in-memory (`ConcurrentHashMap`) `sample-products`
    and `sample-greetings` caches are listed (Redis-backed with the `docker`
    profile), inspect cache annotations, and clear a cache after confirming the action.
13. **Dev Services** — in the default Docker-free mode no containers are listed;
    with the `docker` profile the Postgres and Redis Docker Compose entries appear
    and their service-connection metadata matches the actual mapped ports.
14. **Spring Security and Security Logs** — inspect filter chains, endpoint rule explanations, and recent masked audit
    events.
15. **Traces, Log Tail, HTTP Exchanges, Architecture, Pentesting, Vulnerabilities** — inspect local telemetry, logs,
    inbound requests, and run explicit local scans as development hygiene prompts.
16. **HTTP Probe** — send a request to `/api/echo`, then try to send one to an
    external host and confirm it is rejected as non-loopback.
17. **AI Usage** — the Chat and AI Usage panels report AI is unavailable in the
    default mode; with the `docker` profile, exercise the sample AI endpoints and
    local AI helper paths, then inspect the retained in-memory spans and token summaries.
18. **DevTools, Dev Services, Copilot, Claude Code** — confirm the developer-tool panels show local status, bounded service
    metadata/logs, and sanitized local agent activity.

## Stop it

`Ctrl-C` the Spring Boot process. With the `docker` profile, Spring Boot also stops Docker Compose.

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

## Run it with CRaC (fast restore)

[CRaC](https://crac.org/) (Coordinated Restore at Checkpoint) snapshots a fully
warmed-up JVM process to disk and restores it in a few tens of milliseconds,
without the ahead-of-time compilation a native image requires. The BootUI **CRaC**
panel (Runtime group) inspects this readiness; this section actually runs the
sample app from a checkpoint.

CRaC only works on a **Linux** host and needs a **CRaC-enabled JDK** (here
BellSoft Liberica with CRaC) plus elevated container privileges, because
[CRIU](https://criu.org/) — the tool that checkpoints and restores the live
process — needs them. Ready-to-use Docker assets live at the repository root:

- [`Dockerfile-crac`](../Dockerfile-crac) — builds the reactor and produces a
  runtime image on a CRaC-enabled JDK.
- [`bootui-sample-app/src/main/script/checkpoint-and-run.sh`](src/main/script/checkpoint-and-run.sh) —
  the container entrypoint: it creates a checkpoint on the first start and
  restores from it on every start afterwards.
- [`docker-compose-crac.yml`](../docker-compose-crac.yml) — runs the CRaC image
  with the simple in-memory profile (no extra services).

### With Docker Compose (recommended)

No local CRaC JDK is required — the toolchain lives in the build image. The image
runs the app with its `dev` profile **active** (`SPRING_PROFILES_ACTIVE=dev`),
which uses an **in-memory H2 database** and an in-memory cache, so nothing holds
an open network socket when the checkpoint is taken and it succeeds out of the
box. From the repository root, on a Linux host:

```bash
docker compose -f docker-compose-crac.yml up --build
```

The **first** start boots the app once to write the checkpoint
(`spring.context.checkpoint=onRefresh`), so it takes as long as a normal start.
Watch the logs for the `[crac]` lines: the process is checkpointed and then
restored. Every later start (`docker compose -f docker-compose-crac.yml up`)
restores the warmed-up image almost instantly — the log shows a `Restored
BootUiSampleApplication in 0.1xx seconds` line. The checkpoint is stored in the
`crac-checkpoint` named volume; delete it to force a fresh checkpoint:

```bash
docker compose -f docker-compose-crac.yml down
docker volume rm boot-ui_crac-checkpoint
```

(Docker Compose prefixes the volume with the project name, which defaults to the
working directory — `boot-ui` here; run `docker volume ls` if yours differs.)

Then open <http://localhost:8080/bootui/> or hit
<http://localhost:8080/actuator/health>. The Compose file binds the app port to
host loopback (`127.0.0.1`) so BootUI stays local-only while the browser can
still reach the containerized app. The BootUI **CRaC** panel's runtime status
will now report a CRaC-capable JVM.

On this sample app (Spring Boot 4, `dev`/H2 profile) the restore is dramatically
faster than a cold JVM start:

| Start              | Spring-reported            | Wall-clock to `/actuator/health` 200 |
| ------------------ | -------------------------- | ------------------------------------ |
| Normal JVM         | `Started … in ~9.7 s`      | ~12 s                                |
| CRaC restore       | `Restored … in ~0.11 s`    | ~1–2 s (mostly container start)      |

That is roughly an **80×** improvement on the Spring-reported figure (and a 7×+
wall-clock win even including container startup). The checkpoint itself is about
270 MB of CRIU images in the named volume. Your numbers will vary with hardware,
but the order of magnitude holds.

To build just the image:

```bash
docker build -f Dockerfile-crac -t bootui-sample-crac .
```

### Without Docker Compose

The `app` service must run with CRIU privileges, so a plain `docker run` needs
`--privileged` (or, more narrowly,
`--cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN
--security-opt seccomp=unconfined`) and a volume for the checkpoint:

```bash
docker build -f Dockerfile-crac -t bootui-sample-crac .
docker run --rm --privileged -p 127.0.0.1:8080:8080 \
  -e BOOTUI_ALLOW_NON_LOCALHOST=true \
  -v bootui-crac:/opt/crac/checkpoint bootui-sample-crac
```

The same container takes the checkpoint on its first start and restores it on
every later start, as long as the `bootui-crac` volume is reused.

### How the checkpoint is taken

`spring.context.checkpoint=onRefresh` asks Spring to take the checkpoint as soon
as the application context finishes refreshing. This requires the
[`org.crac:crac`](https://crac.org/) adapter on the classpath (the sample app
declares it; the version is managed by the Spring Boot BOM) in addition to the
CRaC-enabled JDK. With the default `dev` profile the only resources are in-memory
(H2 and a simple cache), so no socket or file descriptor is open at checkpoint
time and CRaC can snapshot the process directly. The sample app does not
implement any custom [`org.crac.Resource`](https://crac.org/) callbacks. Run the
BootUI **CRaC** panel's readiness scan first if you add code that holds OS
resources directly.

> **BootUI must see an _active_ profile.** BootUI activates when one of its
> `bootui.enabled-profiles` (`dev,local,docker`) is in
> `Environment.getActiveProfiles()`. A profile set only through
> `spring.profiles.default` does **not** count, and `spring-boot-devtools` is
> stripped from the repackaged jar, so the CRaC image sets
> `SPRING_PROFILES_ACTIVE=dev` to turn BootUI on (and select H2). Because CRaC
> freezes configuration into the checkpoint, this must be set on the first start.


> **CRaC freezes configuration into the checkpoint.** Environment variables and
> system properties are read when the checkpoint is taken, not when it is
> restored. Set anything that influences the running app (such as
> `BOOTUI_ALLOW_NON_LOCALHOST`) **before the first start**; changing it for a
> later restore-only start has no effect until you delete the checkpoint and let
> a new one be taken.

### Using external services (PostgreSQL + Redis)

The default H2 profile keeps the demo self-contained. To checkpoint the sample
app against the real PostgreSQL and Redis services instead (the `docker` profile,
backed by [`compose.yaml`](../compose.yaml)), two extra constraints apply because
CRaC snapshots live OS resources:

- **Both services must be reachable when the checkpoint is taken and when it is
  restored.** HikariCP and the Lettuce Redis client reconnect on restore, so the
  databases have to be up at both moments.
- **No pooled connection may be open at checkpoint time.** CRaC aborts the
  checkpoint with `CheckpointOpenSocketException` if, for example, HikariCP still
  holds an open PostgreSQL socket when `onRefresh` fires. Spring Boot closes
  CRaC-aware resources (the Hikari pool, the Lettuce client) before the
  checkpoint, but a pool configured to keep a minimum number of idle connections,
  or any background task that re-opens one, will reintroduce an open socket and
  fail the checkpoint. Keep such connections closed (for example, let the pool
  drain to zero idle connections) until the checkpoint has been written.

Because of these constraints the external-services path is intentionally left as
an opt-in exercise rather than the default; start from the H2 setup above, then
wire in PostgreSQL/Redis and the matching `SPRING_DATASOURCE_*` /
`SPRING_DATA_REDIS_*` environment variables once you have verified the checkpoint
flow.

## Playwright suite

The Playwright end-to-end tests in [`e2e/`](e2e/README.md) drive the same
sample app and assert that every visible BootUI route loads. See the e2e
README for the run instructions.
