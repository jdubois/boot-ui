# BootUI Quarkus Sample App

A reference [Quarkus](https://quarkus.io/) application that demonstrates the BootUI developer console,
the Quarkus analogue of [`bootui-sample-app`](../bootui-sample-app). It exists so every BootUI panel that
ships on Quarkus has realistic, non-sensitive data to show, and as the integration target for the Quarkus
e2e suite.

> **Phase-1 scaffold.** This module depends on the forthcoming **`bootui-quarkus`** extension (see
> [`docs/QUARKUS-SUPPORT.md`](../docs/QUARKUS-SUPPORT.md)). That extension does **not** exist yet, so the
> module is intentionally **not** listed in the root `pom.xml` `<modules>` and is skipped by
> `./mvnw install`. It will not build until the extension is published locally. Treat the code here as a
> faithful, idiomatic starting point rather than something that compiles today.

## What it wires up

Each ingredient maps to a BootUI panel, mirroring the Spring sample's demo intent:

| Ingredient (Quarkus extension)                  | Panels it feeds                          |
| ----------------------------------------------- | ---------------------------------------- |
| `quarkus-rest` (+ Jackson)                      | REST API advisor, Mappings, demo endpoints |
| `quarkus-hibernate-orm-panache`                 | Hibernate advisor, Database, SQL Trace   |
| `quarkus-jdbc-postgresql` + Dev Services        | Database Connection Pools, Dev Services   |
| `quarkus-flyway` / `quarkus-liquibase`          | Flyway, Liquibase                        |
| `quarkus-cache`                                 | Quarkus Cache                            |
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

## Running (once `bootui-quarkus` exists)

Quarkus Dev Services starts a throwaway PostgreSQL container, so **Docker (or Podman) must be running**:

```bash
./mvnw -pl bootui-quarkus-sample-app -am quarkus:dev
```

Then open the console at <http://localhost:8080/bootui/> and the landing page at <http://localhost:8080/>.
Ollama is optional: the chat endpoint returns a clear "AI unavailable" response when it is not reachable.

## Not published

Like `bootui-sample-app`, this module sets `<maven.deploy.skip>true</maven.deploy.skip>` and must never be
released to Maven Central.
