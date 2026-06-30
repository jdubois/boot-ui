# BootUI Quarkus Sample App

A reference [Quarkus](https://quarkus.io/) application that demonstrates the BootUI developer console,
the Quarkus analogue of [`bootui-spring-sample-app`](../bootui-spring-sample-app). It exists so every BootUI panel that
ships on Quarkus has realistic, non-sensitive data to show, and as the integration target for the Quarkus
e2e suite.

## What it wires up

Each ingredient maps to a BootUI panel, mirroring the Spring sample's demo intent:

| Ingredient (Quarkus extension)                  | Panels it feeds                          |
| ----------------------------------------------- | ---------------------------------------- |
| `quarkus-rest` (+ Jackson)                      | REST API advisor, Mappings, demo endpoints |
| `quarkus-hibernate-orm-panache`                 | Hibernate advisor, Database, SQL Trace   |
| `quarkus-jdbc-postgresql` + Dev Services        | Database Connection Pools, Dev Services   |
| `quarkus-flyway` / `quarkus-liquibase`          | Flyway, Liquibase                        |
| `quarkus-cache`                                 | Cache                                    |
| `quarkus-scheduler`                             | Scheduled Tasks                          |
| `quarkus-security` (+ elytron properties file)  | Security Logs, Quarkus advisor           |
| `quarkus-smallrye-health`                       | Health                                   |
| `quarkus-micrometer-registry-prometheus`        | Metrics                                  |
| `quarkus-opentelemetry`                         | Traces                                   |
| `quarkus-langchain4j-ollama`                    | AI Usage                                 |

## Demo endpoints

`SampleResource` (`/api/sample/*`) mirrors the Spring sample's `SampleController`: `hello`, `products`,
`product-search`, `metrics-burst`, `allocate`, `slow`, `pool-stress`, `chained`, and `boom`. The
`advisor/hibernate` package contains intentional JPA mapping anti-patterns (the Hibernate advisor scans the
metamodel at boot, so they are flagged without needing rows), and `ArchitectureIssuesResource` triggers
advisor findings. Secured endpoints (`/admin`, `/api/secure`) require the `admin`/`admin` account.

## Running from source

Quarkus Dev Services starts a throwaway PostgreSQL container, so **Docker (or Podman) must be running**.
Install the extension (and its dependencies) once, then launch the sample in dev mode:

```bash
./mvnw -pl bootui-quarkus-deployment,bootui-quarkus-sample-app -am -DskipTests install
./mvnw -f bootui-quarkus-sample-app/pom.xml quarkus:dev
```

Then open the console at <http://localhost:8080/bootui/> and the landing page at <http://localhost:8080/>.
Ollama is optional: the chat endpoint returns a clear "AI unavailable" response when it is not reachable.

BootUI activates automatically under `quarkus:dev` (development launch mode). In a packaged production run
(`java -jar`, NORMAL launch mode) the console stays dark by design — there is no runtime flag to force it on.

> Run from source on **JDK 17 or 21**: Hibernate ORM's ByteBuddy enhancement cannot augment newer class
> files, so `quarkus:dev` fails on JDK 22+. The Docker image below sidesteps this by building inside JDK 21.

## Importing into an IDE (IntelliJ IDEA)

This module is part of the **always-on** Maven reactor, so IntelliJ imports it as a Java/Maven module on
**any** JDK — including JDK 26+. Only the Quarkus build-time augmentation is skipped on a JDK newer than the
Quarkus platform supports (the `skip-quarkus-build-on-unsupported-jdk` profile in this module's `pom.xml`); the
sources still compile and resolve, so code intelligence works in the IDE regardless of the importer JDK.

If you are on an older checkout (where the whole module sat behind a JDK-`[17,26)` profile) and IntelliJ shows
it as *"not a Java/Maven project"*, point the Maven importer at a JDK the platform supports and reload:

- **Settings → Build, Execution, Deployment → Build Tools → Maven → Importing → "JDK for importer"** → pick a
  JDK 17 / 21 / 25.
- **File → Project Structure → Project → SDK** → set the project SDK to the same JDK.
- Optionally, in the **Maven tool window → Profiles**, tick `quarkus-sample-app`, then **Reload All Maven
  Projects**.

To actually run or augment the app from the IDE (`quarkus:dev`) you still need a JDK 17 / 21 / 25, for the same
Hibernate ByteBuddy reason as above; on JDK 26+ the module imports and compiles but does not augment.

## Docker image

[`Dockerfile-quarkus`](../Dockerfile-quarkus) at the repository root builds a self-contained JVM image that
runs this app with the BootUI console, **Docker-free** (in-memory H2 — no PostgreSQL Dev Service needed):

```bash
docker build -f Dockerfile-quarkus -t bootui-quarkus-sample-app .
docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO bootui-quarkus-sample-app
# then open http://localhost:8080/bootui
```

This is the **only** image flavor for Quarkus — deliberately no AOT, GraalVM native, or CRaC variants
(Quarkus builds native images itself, and BootUI's GraalVM/CRaC advisors are Spring-oriented and report *not
applicable* on Quarkus). Because BootUI activates only outside production launch mode, the image launches the
app in **source-based dev mode** (`quarkus:dev`), which is why it needs a full JDK base and is larger
than the Spring sample's distroless image. `BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO` lets
the host browser reach BootUI through Docker's bridge gateway while the Host allow-list and CSRF defenses stay
in force.

The sample Flyway/Liquibase migrations are off by default for a faster boot (Hibernate still creates the demo
tables). Re-enable them to populate the Flyway/Liquibase panels:

```bash
docker run --rm -p 8080:8080 \
  -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
  -e QUARKUS_FLYWAY_MIGRATE_AT_START=true -e QUARKUS_LIQUIBASE_MIGRATE_AT_START=true \
  bootui-quarkus-sample-app
```

## Not published

Like `bootui-spring-sample-app`, this module sets `<maven.deploy.skip>true</maven.deploy.skip>` and is never released
to Maven Central. The Docker image above is built from this repository; unlike the Spring sample images it is
not (yet) published to Docker Hub.
