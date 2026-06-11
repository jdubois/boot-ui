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

In this Docker-free mode most panels work normally (Configuration, Database, Spring Data, Flyway, Liquibase, Spring
Cache); the Chat and AI Usage panels report that AI is unavailable, and Dev Services lists no containers.

Populate the Flyway and Liquibase panels with the sample migrations (disabled by default for a faster boot):

```bash
docker run --rm -p 8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e SPRING_FLYWAY_ENABLED=true -e SPRING_LIQUIBASE_ENABLED=true \
  jdubois/bootui-sample-app
```

## Other sample-app images

Two more flavors of the same sample app are published for experimentation:

| Image                              | Use it for                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `jdubois/bootui-sample-app-crac`   | A JVM image using [CRaC](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html) (Coordinated Restore at Checkpoint) for near-instant restarts. CRaC requires a Linux host and elevated privileges — see [`docker-compose-crac.yml`](https://github.com/jdubois/boot-ui/blob/main/docker-compose-crac.yml) and the ["Run it with CRaC"](https://github.com/jdubois/boot-ui/blob/main/bootui-sample-app/README.md) section of the sample app README. |
| `jdubois/bootui-sample-app-native` | A GraalVM native image that starts in well under a second. It is wired for the full PostgreSQL + Redis stack — see [`docker-compose-native.yml`](https://github.com/jdubois/boot-ui/blob/main/docker-compose-native.yml).                                                                                                                                                                                                                                                          |

## Want the full experience?

To exercise every panel with PostgreSQL, Redis, and Ollama, run the sample app with the `docker` profile from a checkout
of the repository — see the [sample app README](https://github.com/jdubois/boot-ui/blob/main/bootui-sample-app/README.md#run-it-with-docker)
for details.
