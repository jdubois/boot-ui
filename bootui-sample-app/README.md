# BootUI sample application

This module is a small Spring Boot 4 application that demonstrates the
[BootUI](../README.md) developer console end to end. It is the same app that
the Playwright suite under `e2e/` exercises.

## What it shows

- The `bootui-spring-boot-starter` dependency on a real Spring Boot 4 app.
- BootUI auto-activating when the `dev` profile is active.
- A PostgreSQL-backed Spring Data repository so the Data panel has data to
  show.
- A Docker Compose service (`compose.yaml`) so the Dev Services panel has a
  snapshot to show.
- Spring Security, scheduled tasks, custom metrics, and a small static welcome
  page so the corresponding BootUI panels are populated.

## Prerequisites

- Java 25
- Docker (or any Docker-compatible engine) for the Postgres container
- The repository's Maven Wrapper (`./mvnw`) — no global Maven install needed

## Run it

From the repository root:

```bash
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev
```

Spring Boot will start Docker Compose, wait for Postgres, and then bind the
sample app to `http://localhost:8080`.

## Visit BootUI

Open <http://localhost:8080/bootui> in a browser running on the same machine.

Useful URLs:

| URL | What you see |
|---|---|
| <http://localhost:8080/> | Sample application welcome page |
| <http://localhost:8080/bootui> | BootUI console |
| <http://localhost:8080/bootui/api/overview> | Stable BootUI JSON DTO for the Overview panel |
| <http://localhost:8080/api/echo> | Sample REST endpoint surfaced by the Mappings panel |

## Suggested walkthrough

1. **Overview** — confirm the activation reason is `profile=dev` and that
   localhost-only is `true`.
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
7. **Health, Metrics, Memory** — sanity-check the live values; the Memory panel
   includes suggested JVM options computed from the current container limits.
8. **Data** — open `BootUiSampleRepository` to inspect its query methods and
   domain type.
9. **Dev Services** — verify the Postgres Docker Compose entry is present and
   its service-connection metadata matches the actual mapped port.
10. **HTTP Probe** — send a request to `/api/echo`, then try to send one to an
    external host and confirm it is rejected as non-loopback.
11. **Log Tail** — generate a few log lines from the sample endpoints and watch
    them appear in the bounded tail buffer.
12. **DevTools** — confirm the panel shows DevTools as available and that
    restart controls require explicit confirmation.

## Stop it

`Ctrl-C` the Spring Boot process. Spring Boot will stop Docker Compose.

## Playwright suite

The Playwright end-to-end tests in [`e2e/`](e2e/README.md) drive the same
sample app and assert that every visible BootUI route loads. See the e2e
README for the run instructions.
