# Try the sample app

The fastest way to see BootUI is to run a published sample-app image. No clone, no build, and no JDK are required —
only a Docker-compatible engine such as Docker Desktop, Docker Engine, or Podman.

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app
```

Then open <http://localhost:8080/bootui> from a browser on the same machine.

`BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO` lets the browser on your host reach BootUI through Docker's bridge gateway while
the Host allow-list and cross-site-write defenses stay in force. See
[running inside a Docker container](setup/environments.md#running-inside-a-docker-container).

## Available images

Each flavor listens on one fixed port, identical whether you run it from Docker or from source with Maven.

| Image | Port | What it demonstrates |
| ----- | ---- | -------------------- |
| `jdubois/bootui-sample-app` | 8080 | Spring Boot servlet on a plain JVM |
| [`jdubois/bootui-sample-app-aot`](#jvm-aot-image) | 8080 | The same app with Spring AOT and the JDK 25 AOT class loading cache |
| [`jdubois/bootui-sample-app-native`](#graalvm-native-image) | 8080 | The same app as a GraalVM native image |
| [`jdubois/bootui-sample-app-crac`](#crac-image) | 8080 | The same app restored from a CRaC checkpoint, on Linux only |
| [`jdubois/bootui-sample-app-webflux`](#webflux-image) | 8081 | The reactive adapter on Netty |
| [`jdubois/bootui-sample-app-quarkus`](#quarkus-image) | 8082 | The Quarkus extension, in dev mode |

## What the default profile gives you

Every image runs the sample app's `dev` profile, which is Docker-free: an in-memory H2 database, an in-memory cache,
and Spring AI disabled. No PostgreSQL, Redis, or Ollama is needed.

Most panels work normally, including Configuration, Database, Spring Data, Flyway, and Liquibase. The Chat and AI
Framework panels report that AI is unavailable, and Dev Services lists no containers.

Database migrations are disabled for a faster boot. To populate the Flyway and Liquibase panels, turn them back on:

```bash
docker run --rm -p 8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e SPRING_FLYWAY_ENABLED=true -e SPRING_LIQUIBASE_ENABLED=true \
  jdubois/bootui-sample-app
```

The AOT and CRaC images take the same two variables. The WebFlux image takes them on port 8081, and the Quarkus image
uses `QUARKUS_FLYWAY_MIGRATE_AT_START=true` and `QUARKUS_LIQUIBASE_MIGRATE_AT_START=true` on port 8082. The native
image freezes this choice at build time.

## JVM + AOT image

`jdubois/bootui-sample-app-aot` combines two Ahead-of-Time optimizations on the plain JVM image. It needs no GraalVM
toolchain and no CRIU privileges:

- **Spring AOT** pre-generates the application context wiring at build time, replacing dynamic CGLIB proxies and
  reflection with static factory code.
- **The JDK 25 AOT class loading cache (JEP 483)** records class loading and linking during a training run in the
  Docker build and replays it on every start.

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-aot
```

On the sample app, that is roughly 40–45 % off the Spring-reported startup time (about 9.7 s down to 5–6 s) for a
70–100 MB larger image. The profile is not frozen: override it at runtime with `-e SPRING_PROFILES_ACTIVE=...`.

::: details The JVM crashes during an advisor scan

JDK 25 through 25.0.4 can crash with `SIGILL` in `~AdapterBlob` when an AOT cache built on one CPU runs on another CPU
with different instruction support. The application starts normally and crashes only when a scan reaches the affected
code. This is [OpenJDK JDK-8388703](https://bugs.openjdk.org/browse/JDK-8388703), fixed in JDK 25.0.5.

`Dockerfile-aot` already disables CPU-specific method adapter caching during training and at runtime, while keeping
Spring AOT and the class loading cache enabled. For an image that still crashes, apply the same workaround without
replacing its existing JVM options:

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e 'JDK_JAVA_OPTIONS=-XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching' \
  jdubois/bootui-sample-app-aot
```

:::

## GraalVM native image

`jdubois/bootui-sample-app-native` is a [GraalVM](https://www.graalvm.org/) native image that starts in about 0.3 s:

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-native
```

A native image freezes auto-configuration during AOT, so both the `dev` profile and the disabled migrations are baked
in at build time. Rebuild the image to change them.

To run it against the full PostgreSQL and Redis stack instead, use
[`docker-compose-native.yml`](https://github.com/jdubois/boot-ui/blob/main/docker-compose-native.yml).

::: tip Rebuild older native images for the Security advisor
The advisor needs BootUI's private-field reflection hints compiled into the executable, not only in a replacement JAR.
The sample keeps usable results from its three chains but still reports partial coverage for unsupported observations,
rather than claiming a complete security assessment. See
[Security checks](SECURITY-CHECKS.md#availability-and-bounds).
:::

## CRaC image

`jdubois/bootui-sample-app-crac` uses
[CRaC](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html) to restore a warmed-up
JVM in about 0.11 s. It runs on a **Linux** host only, needs elevated privileges for [CRIU](https://criu.org/), and
keeps its checkpoint in a volume so it survives container restarts:

```bash
docker run --rm -p 8080:8080 \
  --cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN --cap-add=NET_ADMIN \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -v bootui-sample-app-crac:/opt/crac/checkpoint \
  jdubois/bootui-sample-app-crac
```

The first start boots once to write the checkpoint; later starts restore it. Run
`docker volume rm bootui-sample-app-crac` to force a fresh checkpoint. For details, see
["Run it with CRaC"](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-sample-app/README.md) in the sample app
README.

## WebFlux image

`jdubois/bootui-sample-app-webflux` serves the same console at `/bootui`, backed by the reactive build of the BootUI
engine on Netty:

```bash
docker run --rm -p 8081:8081 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-webflux
```

Then open <http://localhost:8081/bootui>.

Every panel except HTTP Sessions behaves as it does on the servlet image, including every advisor scan and every
action. See [Framework support](FRAMEWORK-SUPPORT.md#spring-webflux).

There is one WebFlux flavor, with no AOT, native, or CRaC variant: the reactive sample exists to exercise the reactive
adapter, not to demonstrate every JVM startup technique twice.

## Quarkus image

`jdubois/bootui-sample-app-quarkus` serves the same console at `/bootui`, backed by the Quarkus build of the engine:

```bash
docker run --rm -p 8082:8082 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app-quarkus
```

Then open <http://localhost:8082/bootui>.

BootUI activates only outside Quarkus' production launch mode, so the image starts the application in dev mode. That
requires a full JDK base image, which makes it larger than the Spring images.

Most panels are live. A handful target Spring-specific concepts and are marked *not applicable* — see
[what is not on Quarkus](FRAMEWORK-SUPPORT.md#what-is-not-on-quarkus). There is no AOT, native, or CRaC variant:
Quarkus builds native images itself, and BootUI's GraalVM and CRaC advisors are Spring-oriented.

## Want the full experience?

To exercise every panel with PostgreSQL, Redis, and Ollama, run the sample app with the `docker` profile from a
checkout of the repository. See the
[sample app README](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-sample-app/README.md#run-it-with-docker).
