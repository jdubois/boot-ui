# Try the sample app

The quickest way to see BootUI in action is to run the published sample-app container image. No clone, no build, and no
JDK are required — only a Docker-compatible engine.

Prerequisites: a running Docker engine (Docker Desktop, Docker Engine, Podman, etc.).

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app
```

Then open <http://localhost:8080/bootui> from a browser on the same machine.

The image runs the sample app's `dev` profile, which is **Docker-free** (in-memory H2 database, a simple in-memory
cache, and disabled Spring AI), so no PostgreSQL, Redis, or Ollama is needed. `BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO` lets
the browser on your host reach BootUI through Docker's bridge gateway while keeping the Host allow-list and CSRF
defenses in force — see the [container access](SETUP.md#running-inside-a-docker-container) notes in the setup guide for details.

In this Docker-free mode most panels work normally (Configuration, Database, Spring Data, Flyway, Liquibase, Cache); the
Chat and AI Usage panels report that AI is unavailable, and Dev Services lists no containers.

Populate the Flyway and Liquibase panels with the sample migrations (disabled by default for a faster boot):

```bash
docker run --rm -p 8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e SPRING_FLYWAY_ENABLED=true -e SPRING_LIQUIBASE_ENABLED=true \
  jdubois/bootui-sample-app
```

## Other sample-app images

Three more flavors of the same sample app are published for experimentation. Like the JVM image above, all three default
to the Docker-free `dev` profile (in-memory H2) and accept `BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO` so the host browser can
reach BootUI while the Host allow-list and CSRF defenses stay in force.

### JVM + AOT image (faster startup, no extra infrastructure)

`jdubois/bootui-sample-app-aot` combines two Ahead-of-Time optimizations on the plain JVM image for a significantly
faster start — no GraalVM toolchain and no CRIU privileges required:

- **Spring AOT** — the application context wiring is pre-generated at build time, replacing dynamic CGLIB proxies and
  reflection with static factory code.
- **JDK 25 AOT class loading cache (JEP 483)** — class-loading and linking patterns are recorded in a training run
  during the Docker build and replayed on every production start, bypassing most of the normal classloading pipeline.

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-aot
```

Typical result: **~40–45 % shorter startup** vs the plain JVM image (Spring-reported ~9.7 s → ~5–6 s), at the cost of a
~70–100 MB larger image (the AOT cache file). The Spring profile can still be overridden at runtime with
`-e SPRING_PROFILES_ACTIVE=...` — nothing is frozen at build time.

### GraalVM native image

`jdubois/bootui-sample-app-native` is a [GraalVM](https://www.graalvm.org/) native image that starts in well under a
second:

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-native
```

To run the native image against the full PostgreSQL + Redis stack instead, use
[`docker-compose-native.yml`](https://github.com/jdubois/boot-ui/blob/main/docker-compose-native.yml).

### CRaC image

`jdubois/bootui-sample-app-crac` is a JVM image using
[CRaC](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html) (Coordinated Restore at
Checkpoint) for near-instant restarts. It only works on a **Linux** host, needs elevated privileges for
[CRIU](https://criu.org/), and uses a volume to store the checkpoint so it survives container restarts:

```bash
docker run --rm -p 8080:8080 \
  --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -v bootui-sample-app-crac:/opt/crac/checkpoint \
  jdubois/bootui-sample-app-crac
```

The first start boots once to write the checkpoint into the `bootui-sample-app-crac` volume; every later start restores
the warmed-up JVM in tens of milliseconds. Delete the volume (`docker volume rm bootui-sample-app-crac`) to force a fresh
checkpoint. See the ["Run it with CRaC"](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-sample-app/README.md)
section of the sample app README for details.

## BootUI on Quarkus

BootUI also ships as a Quarkus extension, and its Quarkus sample app is published as a separate image. It serves the
**same** Vue console at `/bootui`, backed by the Quarkus build of the BootUI engine:

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-quarkus
```

Then open <http://localhost:8080/bootui> from a browser on the same machine.

Like the Spring image it is **Docker-free** (in-memory H2 — no PostgreSQL Dev Service container) and honors
`BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO`. There is a **single** Quarkus flavor — no AOT, GraalVM native, or CRaC variants:
Quarkus builds native images itself, and BootUI's GraalVM/CRaC advisors are Spring-oriented. Because BootUI activates only
outside Quarkus' production launch mode, the image launches the app in **dev mode**, so it uses a full JDK base and is
larger than the Spring images.

Populate the Flyway and Liquibase panels with the sample migrations (disabled by default for a faster boot):

```bash
docker run --rm -p 8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e QUARKUS_FLYWAY_MIGRATE_AT_START=true -e QUARKUS_LIQUIBASE_MIGRATE_AT_START=true \
  jdubois/bootui-sample-app-quarkus
```

Most panels light up on Quarkus; a handful stay Spring- or framework-specific (for example GraalVM, CRaC, Conditions,
Startup Timeline, HTTP Sessions, Spring Data, Spring Security, DevTools) and are clearly marked *not applicable*. See
[FEATURES.md](FEATURES.md) for the full per-platform availability.

## Want the full experience?

To exercise every panel with PostgreSQL, Redis, and Ollama, run the sample app with the `docker` profile from a checkout
of the repository — see the [sample app README](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-sample-app/README.md#run-it-with-docker)
for details.
