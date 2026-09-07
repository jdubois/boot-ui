# BootUI sample application

This module is a small Spring Boot 4 application that demonstrates the
[BootUI](../README.md) developer console end to end. It is the same app that
the Playwright suite under `e2e/` exercises.

## What it shows

- The `bootui-spring-boot-starter` dependency on a real Spring Boot 4 app.
- BootUI auto-activating in local development (the `dev`/`docker` profiles, or via `spring-boot-devtools`).
- A relational Spring Data repository so the Spring Data panel has data to show
  (in-memory H2 by default, PostgreSQL with the `docker` profile).
- Optional PostgreSQL, Redis, Kafka, and Ollama Docker Compose services (`compose.yaml`, enabled by the `docker`
  profile) so the Spring Data, Database Connection Pools, Cache, Kafka, AI Framework, and Dev Services panels have
  realistic infrastructure to show.
- Flyway migrations (the `catalog_*` tables) and Liquibase change sets (the separate
  `inventory_*` tables) with two pending updates each so the Flyway and Liquibase actions can be exercised manually.
- Spring Security, scheduled tasks, custom metrics, and a small static welcome
  page so the corresponding BootUI panels are populated.
- Local diagnostics for Architecture, Pentesting, Vulnerabilities, Traces,
  HTTP Exchanges, Security Logs, Threads, GraalVM, AI Framework, Heap Dump, and other release-supported panels.

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
./mvnw -pl bootui-spring-sample-app spring-boot:run
```

`dev` is the default Spring profile ([`application-dev.properties`](src/main/resources/application-dev.properties)), so
it applies whenever no other profile is active (a bare run, the Playwright e2e suite, etc.); pass
`-Dspring-boot.run.profiles=dev` explicitly for the same result. Most panels work normally, including Configuration,
Database, Spring Data, Flyway, Liquibase, and Cache. The Chat and AI Framework panels report that AI is unavailable,
and Dev Services lists no containers. The published [`jdubois/bootui-sample-app`](../docs/TRY-SAMPLE-APP.md) Docker image
runs this same Docker-free `dev` profile.

## Run it with Docker

For the full experience — Postgres, Redis, Kafka, Ollama, and every panel populated — activate the `docker` profile
from the repository root:

```bash
./mvnw -pl bootui-spring-sample-app spring-boot:run -Dspring-boot.run.profiles=docker
```

Spring Boot will start Docker Compose, wait for Postgres, Redis, Kafka, and Ollama, pull the small `qwen2.5:0.5b` chat
model when missing, and then bind the sample app to `http://localhost:8080`.

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

This walkthrough follows the default Docker-free `dev` mode (a bare `spring-boot:run`). The Dev Services, Cache,
and AI Framework steps note where the `docker` profile adds Postgres/Redis/Ollama-backed behavior.

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
12. **Cache** — verify the in-memory (`ConcurrentHashMap`) `sample-products`
    and `sample-greetings` caches are listed (Redis-backed with the `docker`
    profile), inspect cache annotations, and clear a cache after confirming the action.
13. **Kafka** — unavailable in the default Docker-free mode (no `KafkaTemplate` bean); with the `docker` profile,
    click **Send Kafka message** on the welcome page and watch the produce/consume pair for the `orders.created`
    topic appear in the panel.
14. **Dev Services** — in the default Docker-free mode no containers are listed;
    with the `docker` profile the Postgres and Redis Docker Compose entries appear
    and their service-connection metadata matches the actual mapped ports.
15. **Spring Security and Security Logs** — inspect filter chains, endpoint rule explanations, and recent masked audit
    events.
16. **Traces, Log Tail, HTTP Exchanges, Architecture, Pentesting, Vulnerabilities** — inspect local telemetry, logs,
    inbound requests, and run explicit local scans as development hygiene prompts.
17. **HTTP Probe** — send a request to `/api/echo`, then try to send one to an
    external host and confirm it is rejected as non-loopback.
18. **AI Framework** — the Chat and AI Framework panels report AI is unavailable in the
    default mode; with the `docker` profile, exercise the sample AI endpoints and
    local AI helper paths, then inspect the retained in-memory spans and token summaries.
19. **DevTools, Dev Services, Copilot, Claude Code** — confirm the developer-tool panels show local status, bounded service
    metadata/logs, and sanitized local agent activity.

## Stop it

`Ctrl-C` the Spring Boot process. With the `docker` profile, Spring Boot also stops Docker Compose.

## Cross-service trace demo with the Quarkus sample app

The "Sample action lab" also has a **"Call the Quarkus sample app"** button that demonstrates a real
cross-service call: a `SampleController` endpoint here (`GET /api/sample/quarkus-secure-products`) uses a
Spring `RestClient` to call the companion [`bootui-quarkus-sample-app`](../bootui-quarkus-sample-app)'s own
secured, SQL-backed endpoint (`GET /api/secure/products`, admin/admin) over plain HTTP — the same endpoint
this app's own "Secure SQL request as admin" button exercises locally.

Run both apps from source, in separate terminals:

```bash
./mvnw -pl bootui-spring-sample-app spring-boot:run                                            # :8080
JAVA_HOME=/path/to/jdk-17 ./mvnw -pl bootui-quarkus-sample-app -am quarkus:dev                  # :8082
```

Then open this app's console at <http://localhost:8080/bootui>, click **"Call the Quarkus sample app"**, and open
the **Traces** panel. W3C trace-context propagation is on by default on both adapters, so the single browser click
starts one trace that crosses both JVMs; it renders as **one merged waterfall** spanning the Spring request and
the Quarkus request it triggers — including the Quarkus-side SQL query and authentication event — with
`bootui-sample` and `bootui-quarkus-sample` badges on the same trace (the `bootui.service` span attribute this
PR's enrichment adds). This works because the Quarkus sample's `application.properties` exports its
OpenTelemetry spans over OTLP/HTTP to this app's `POST /bootui/api/otlp/v1/traces` receiver by default when run
from source — the Traces panel's "aggregator topology" (`docs/SPECIFICATION.md` §5.14.3). It has no effect on
the Quarkus app's own Traces panel, which keeps recording its own spans in-process independently, and it is
disabled in the published Quarkus Docker image, which has no Spring app nearby.

> **Why not the Live Activity panel?** Live Activity (`docs/SPECIFICATION.md` §5.14.2) intentionally only ever
> merges one JVM's own local HTTP Exchanges/SQL Trace/Exceptions/Security Logs buffers, reusing their existing
> controllers/DTOs with no new instrumentation. There is no API for one BootUI instance to pull or receive
> another instance's Live Activity stream, and BootUI never forwards that raw data off-process (a stated
> non-goal). The Traces panel's OTLP aggregator topology above is the supported mechanism for seeing
> cross-service activity from one BootUI instance.

## Run it with JVM AOT (faster JVM startup)

The plain JVM image (Dockerfile) starts Docker-free but takes around 9–10 seconds to reach the first HTTP
request. Two orthogonal AOT optimizations can cut that in half without requiring GraalVM or CRIU:

1. **Spring AOT** — `spring-boot:process-aot` generates the application context wiring at build time.
   Instead of dynamic CGLIB proxies and reflection, the app uses pre-compiled factory classes at startup,
   saving 30–50 % of context-refresh time.

2. **JDK 25 AOT class loading cache (JEP 483)** — a training run inside the Docker build stage records
   every class loaded during a real startup. On every production start the JVM loads those classes from
   the pre-verified, pre-linked cache instead of parsing and linking bytecodes from scratch, saving a
   further 20–30 % off class-loading time.

The combined saving is **35–55 %** on Spring-reported startup for this sample app. Unlike CRaC the
profile can be overridden at runtime; unlike GraalVM no conditions are frozen at build time.

Ready-to-use Docker assets live at the repository root:

- [`Dockerfile-aot`](../Dockerfile-aot) — builds the reactor with the `aot` Maven profile (Spring AOT),
  trains the JDK 25 AOT cache during the build stage, and copies it into a distroless runtime image.
- [`docker-compose-aot.yml`](../docker-compose-aot.yml) — runs the AOT image with the in-memory `dev`
  profile, no external services required.

### With Docker Compose (recommended)

No special privileges are required. From the repository root:

```bash
docker compose -f docker-compose-aot.yml up --build
```

Then open <http://localhost:8080/bootui/> or hit <http://localhost:8080/actuator/health>. The Compose
file binds the app port to host loopback (`127.0.0.1`) so BootUI remains local-only.

> **Note:** the Docker build trains the JDK AOT cache with a real application startup. This extends the
> build time by the duration of one cold-start (typically 60–90 extra seconds on the first build;
> subsequent builds reuse Docker's layer cache up to the layer that changes).

### Without Docker Compose

```bash
docker build -f Dockerfile-aot -t bootui-sample-aot .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e BOOTUI_ALLOW_NON_LOCALHOST=true \
  bootui-sample-aot
```

### Benchmark results

Numbers collected on the BootUI sample app (Spring Boot 4, `dev`/H2 profile, Flyway/Liquibase disabled)
on a GitHub Actions `ubuntu-latest` runner:

| Image variant           | Spring-reported startup       | Wall-clock to `/actuator/health` | Image size |
| ----------------------- | ----------------------------- | ------------------------------------- | ---------- |
| JVM (`Dockerfile`)      | `Started … in ~9.7 s`         | ~12 s                                 | ~170 MB    |
| JVM + AOT (`Dockerfile-aot`) | `Started … in ~5–6 s`    | ~7–9 s                                | ~240–270 MB |
| CRaC restore (`Dockerfile-crac`) | `Restored … in ~0.11 s` | ~1–2 s (container start)           | ~170 MB + 270 MB volume |
| GraalVM native (`Dockerfile-native`) | `Started … in ~0.3 s` | ~1–2 s (container start)        | ~45 MB     |

Key takeaways:
- The AOT image starts **~40–45 %** faster than plain JVM (Spring-reported: 9.7 s → 5–6 s).
- The AOT cache file adds **~70–100 MB** to the image (the largest single cost).
- For workloads where container start time matters (scale-to-zero, frequent rolling restarts),
  AOT provides a meaningful improvement over plain JVM with no additional runtime infrastructure.
- CRaC and GraalVM native are still the best options when sub-second starts are required; AOT is the
  right choice when you need faster-than-JVM starts without the operational complexity of CRIU snapshots
  or the build-time freeze of GraalVM.

### How Spring AOT integrates with BootUI

BootUI is a development console that stays disabled outside dev profiles. The `aot` Maven profile
enables BootUI for the `process-aot` step (`-Dbootui.enabled=ON`) so the generated factories include
BootUI's bean wiring. At runtime, `-Dspring.aot.enabled=true` (already set in `JAVA_TOOL_OPTIONS`)
activates the pre-generated code. Unlike the `native` profile, the Spring profile is **not** frozen;
you can override `SPRING_PROFILES_ACTIVE` at runtime to switch to a different profile (BootUI will
only be active if the new profile is in its `bootui.enabled-profiles` list).

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
./mvnw -Pnative -DskipTests -pl bootui-spring-sample-app -am package
./bootui-spring-sample-app/target/bootui-spring-sample-app
```

The `-am` flag also builds the BootUI modules the sample app depends on.

### How BootUI is included in the native image

BootUI is a development console that stays disabled outside dev profiles, and it
normally activates because `spring-boot-devtools` is on the classpath — but
devtools is excluded from the native image. Because GraalVM AOT processing
freezes Spring's bean conditions at build time, the `native` profile enables
BootUI for the AOT step (`-Dbootui.enabled=ON` on the `process-aot` execution)
so the panels and BootUI's [`RuntimeHints`](../bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiRuntimeHints.java)
are baked into the executable. Those hints register the classpath resources and
reflective calls that BootUI performs at runtime (Maven `pom.properties`,
configuration metadata, the BootUI version file, the HotSpot diagnostic MXBean
used for heap dumps, and the Spring Security types it inspects) so the
Dependencies, Config, Heap Dump, and Security panels keep working under native.

Applications that embed BootUI through the starter inherit these hints
automatically; they only need to ensure BootUI is active during their own AOT
processing for it to appear in their native image.

## Run it with CRaC (fast restore)

[CRaC](https://crac.org/) (Coordinated Restore at Checkpoint) snapshots a JVM process
to disk for faster subsequent startup, without the ahead-of-time compilation a
native image requires. This sample takes a startup checkpoint, not a fully
warmed-up application snapshot. The BootUI **CRaC** panel (Runtime group) provides
passive readiness guidance; this section actually runs the sample app from a checkpoint.

This **CRIU-based recipe** uses Linux, a **CRaC-enabled JDK 21** (here BellSoft
Liberica with CRaC), and the `CHECKPOINT_RESTORE`, `SYS_PTRACE`, `SYS_ADMIN`, and
`NET_ADMIN` capabilities for [CRIU](https://criu.org/). These are broad privileges,
not universal requirements for every CRaC engine. Use an isolated local development
machine, not production or a shared host, and verify the exact JDK, bundled CRIU,
kernel and CPU combination. Docker assets live at the repository root:

- [`Dockerfile-crac`](../Dockerfile-crac) — builds the reactor and produces a
  runtime image on a CRaC-enabled JDK.
- [`bootui-spring-sample-app/src/main/script/checkpoint-and-run.sh`](src/main/script/checkpoint-and-run.sh) —
  the container entrypoint: it attempts creation in an empty directory and attempts
  restore when an `inventory.img` candidate marker is present.
- [`docker-compose-crac.yml`](../docker-compose-crac.yml) — runs the CRaC image
  with the simple in-memory profile (no extra services).

### With Docker Compose (recommended)

No local CRaC JDK is required — the toolchain lives in the build image. The image
runs the app with its `dev` profile **active** (`SPRING_PROFILES_ACTIVE=dev`),
which uses an **in-memory H2 database** and an in-memory cache. This reduces
external-service dependencies; it does not establish that all resources are
checkpoint-safe. From the repository root, on a compatible Linux host:

```bash
docker compose -f docker-compose-crac.yml up --build
```

The **first** start attempts a checkpoint after non-lazy singleton initialization
but **before lifecycle start and the context-refreshed event**
(`spring.context.checkpoint=onRefresh`). Watch the `[crac]` logs for creation and
restore attempts. On successful restore, Spring can log a `Restored
BootUiSampleApplication in 0.1xx seconds` line; this is not a startup-time guarantee.
The checkpoint is stored in the `crac-checkpoint` named volume.

The entrypoint treats `inventory.img` only as a **candidate marker**, not proof of
image integrity, completion or compatibility. Restore failures remain failures,
without falling back to creation. A nonempty directory without that marker causes
a clear nonzero exit: partial images, dump logs, hidden files and user data are
left untouched. Inspect and preserve that data, then explicitly choose a new empty
directory or volume for another attempt; the script never cleans it up automatically.

Compose uses `restart: "no"` so a failed attempt stays stopped instead of retrying
the same preserved incomplete checkpoint. To recover, run
`docker compose -f docker-compose-crac.yml stop app`, inspect the service logs
(`docker compose -f docker-compose-crac.yml logs app`) and preserve the checkpoint
volume and its dump logs. Then explicitly select a fresh volume: for example, change
the `app` mount source from `crac-checkpoint` to a new name such as
`crac-checkpoint-retry-1` and declare that name under top-level `volumes` before
running `up --build` again. Keep the old volume; do not use `down -v` or volume pruning
as recovery. For a bind mount, select a new empty host directory instead.

`CRAC_CHECKPOINT_DIR` defaults to `/opt/crac/checkpoint` only when unset. If set,
it must be an absolute path using letters, digits, `.`, `_` or `-`, with no
dot-only components, repeated/trailing separators or symlink components. Empty,
relative, root and ambiguous paths are rejected before creating or using the directory.
Stop the container before changing its volume selection; do not share a checkpoint
directory between concurrent writers.

Then open <http://localhost:8080/bootui/> or hit
<http://localhost:8080/actuator/health>. The Compose file binds the app port to
host loopback (`127.0.0.1`) so BootUI stays local-only while the browser can
still reach the containerized app. The BootUI **CRaC** panel's runtime status
reports runtime observations, not proof that a checkpoint or restore will succeed.

On this sample app (Spring Boot 4, `dev`/H2 profile) the restore is dramatically
faster than a cold JVM start:

| Start              | Spring-reported            | Wall-clock to `/actuator/health` 200 |
| ------------------ | -------------------------- | ------------------------------------ |
| Normal JVM         | `Started … in ~9.7 s`      | ~12 s                                |
| CRaC restore       | `Restored … in ~0.11 s`    | ~1–2 s (mostly container start)      |

That is roughly an **80×** improvement on the Spring-reported figure (and a 7×+
wall-clock win even including container startup). The checkpoint itself is about
270 MB of CRIU images in the named volume. These sample measurements are illustrative;
your numbers depend on the application, runtime and hardware.

To build just the image:

```bash
docker build -f Dockerfile-crac -t bootui-sample-crac .
```

### Without Docker Compose

This recipe uses CRIU's broad capabilities and a volume for the checkpoint:

```bash
docker build -f Dockerfile-crac -t bootui-sample-crac .
docker run --rm --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN --cap-add=NET_ADMIN \
  -p 127.0.0.1:8080:8080 \
  -e BOOTUI_ALLOW_NON_LOCALHOST=true \
  -v bootui-crac:/opt/crac/checkpoint bootui-sample-crac
```

The same container attempts creation in an empty volume and attempts restore from
a candidate on later starts, as long as the `bootui-crac` volume is reused.

Docker's default `/proc` restrictions prevent CRIU from restoring the checkpointed PID without `SYS_ADMIN`, even when
`CHECKPOINT_RESTORE` is available, and recreating the container's network interfaces requires `NET_ADMIN`. `SYS_ADMIN`
grants broad host access, so use this image only for local development on an isolated machine — never production or a
shared host.

> **Compatibility is deployment-specific.** If creation fails, inspect the preserved
> CRIU dump log and the exact runtime's release notes. CPU register handling, kernel
> support and bundled CRIU versions vary. Do not infer support from a vendor name
> or architecture, or assume that a checkpoint created elsewhere will restore here.

### How the checkpoint is taken

`spring.context.checkpoint=onRefresh` asks Spring to checkpoint after non-lazy
singleton initialization, before lifecycle start and the context-refreshed event.
It does not provide request warmup or guarantee cleanup of resources opened early.
This requires the
[`org.crac:crac`](https://crac.org/) adapter on the classpath (the sample app
declares it; the version is managed by the Spring Boot BOM) in addition to the
CRaC-enabled JDK. The default `dev` profile uses H2 and a simple in-memory cache
instead of external PostgreSQL and Redis. The CRaC image also enables
`spring.datasource.hikari.allow-pool-suspension=true`, allowing Spring Boot's
`HikariCheckpointRestoreLifecycle` to block new borrows while it drains a pool it manages.
This setting alone proves neither lifecycle ownership nor cleanup at the original
pre-start checkpoint. The sample app does not
implement any custom [`org.crac.Resource`](https://crac.org/) callbacks. Run the
BootUI **CRaC** panel's readiness scan first if you add code that holds OS
resources directly, then test the exact deployment separately.

> **BootUI must see an _active_ profile.** BootUI activates when one of its
> `bootui.enabled-profiles` (`dev,local,docker`) is in
> `Environment.getActiveProfiles()`. A profile set only through
> `spring.profiles.default` does **not** count, and `spring-boot-devtools` is
> stripped from the repackaged jar, so the CRaC image sets
> `SPRING_PROFILES_ACTIVE=dev` to turn BootUI on (and select H2). Because CRaC
> freezes configuration into the checkpoint, this must be set on the first start.

> **This recipe applies `JAVA_OPTS` only during creation.** It does not forward
> these flags on restore. Regenerate the checkpoint when changing its JVM flags or
> startup configuration. This is the entrypoint's contract, not a claim about all
> CRaC runtimes' restore-time options.
>
> A runtime may update some environment variables or system properties during restore,
> but already-cached application values and Spring bean configuration need not rebind.
> Set startup configuration (such as `BOOTUI_ALLOW_NON_LOCALHOST`) **before the first
> start** and use a fresh checkpoint to apply changes reliably.

### Using external services (PostgreSQL + Redis)

The default H2 profile keeps the demo self-contained. To checkpoint the sample
app against the real PostgreSQL and Redis services instead (the `docker` profile,
backed by [`compose.yaml`](./compose.yaml)), review resource ownership and startup timing:

- **Verify the managed lifecycle path.** Spring Boot provides
  `HikariCheckpointRestoreLifecycle` for supported Hikari pools; HikariCP itself
  does not implement CRaC callbacks. Configure
  `spring.datasource.hikari.allow-pool-suspension=true` before pool initialization.
  Spring Data Redis's managed `LettuceConnectionFactory` also has lifecycle support;
  arbitrary raw clients or shared external resources are not covered by that fact.
- **Review resources opened during initialization.** The original `onRefresh`
  checkpoint happens before lifecycle start, so it cannot inherit a blanket
  guarantee from on-demand stop/restart behavior. Open sockets can cause a
  `CheckpointOpenSocketException`; verify handling for the exact deployment.
- **Provide connectivity when the application needs it.** Initialization may
  already contact backing services, and restored work may need to reconnect.
  Reachability at every checkpoint is not a universal CRaC requirement.

Because of these constraints the external-services path is intentionally left as
an opt-in exercise rather than the default; start from the H2 setup above, then
wire in PostgreSQL/Redis and the matching `SPRING_DATASOURCE_*` /
`SPRING_DATA_REDIS_*` environment variables once you have verified the checkpoint
flow.

## Playwright suite

The Playwright end-to-end tests in [`e2e/`](e2e/README.md) drive the same
sample app and assert that every visible BootUI route loads. See the e2e
README for the run instructions.
