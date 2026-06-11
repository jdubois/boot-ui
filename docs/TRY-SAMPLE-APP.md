# Try the sample app

The quickest way to try BootUI is to run the published sample-app container image. No clone, no build, and no
JDK required — only a Docker-compatible engine. The image is rebuilt and published daily from `main` to Docker Hub.

Prerequisites: Docker (or any Docker-compatible engine).

```bash
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO jdubois/bootui-sample-app
```

Then open <http://localhost:8080/bootui>.

The image runs the sample app's `dev` profile, so it starts **Docker-free** on an in-memory H2 database with a simple
in-memory cache and disabled Spring AI. Most panels work normally (Configuration, Database, Spring Data, Flyway,
Liquibase, Spring Cache); the Chat and AI Usage panels report that AI is unavailable, and Dev Services lists no
containers. `BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO` lets your host browser reach BootUI through Docker's bridge gateway
while keeping it local-only (the port is published on your machine).

The sample app can apply two pending Flyway migrations and two Liquibase change sets on startup so those panels have
data to show. They are off by default for a faster boot; turn them on at runtime:

```bash
docker run --rm -p 8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e SPRING_FLYWAY_ENABLED=true -e SPRING_LIQUIBASE_ENABLED=true \
  jdubois/bootui-sample-app
```

## Other published images

Two more sample-app images are published alongside the JVM one, for when you want to explore faster startup options:

- **CRaC (Coordinated Restore at Checkpoint):** [`jdubois/bootui-sample-app-crac`](https://hub.docker.com/r/jdubois/bootui-sample-app-crac).
  Restores a warmed-up JVM image in a few tens of milliseconds. CRaC only works on a Linux host and needs CRIU
  privileges, so it is easiest to run through
  [`docker-compose-crac.yml`](https://github.com/jdubois/boot-ui/blob/main/docker-compose-crac.yml). See the
  ["Run it with CRaC" section of the sample app README](https://github.com/jdubois/boot-ui/blob/main/bootui-sample-app/README.md#run-it-with-crac-fast-restore)
  for details.
- **GraalVM native image:** [`jdubois/bootui-sample-app-native`](https://hub.docker.com/r/jdubois/bootui-sample-app-native).
  Starts in well under a second. The native image is built with the `docker` profile and expects PostgreSQL and Redis,
  so it is easiest to run through
  [`docker-compose-native.yml`](https://github.com/jdubois/boot-ui/blob/main/docker-compose-native.yml). See the
  ["Run it as a GraalVM native image" section of the sample app README](https://github.com/jdubois/boot-ui/blob/main/bootui-sample-app/README.md#run-it-as-a-graalvm-native-image)
  for details.

## Want the full experience?

For the full experience with PostgreSQL, Redis, and Ollama (a Docker-compatible engine is required), build and run the
sample app with the `docker` profile — see the
[sample app README](https://github.com/jdubois/boot-ui/blob/main/bootui-sample-app/README.md#run-it-with-docker) for
details.
