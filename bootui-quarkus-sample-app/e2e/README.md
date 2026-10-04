# BootUI Quarkus sample app — end-to-end tests

[Playwright](https://playwright.dev/) end-to-end tests that drive the BootUI console served by the
[`bootui-quarkus-sample-app`](../) Quarkus module from a real Chromium browser. They are the Quarkus
analogue of [`bootui-spring-sample-app/e2e`](../../bootui-spring-sample-app/e2e) and exist to prove that the **one
shared Vue UI** works end to end against the **Quarkus** adapter, not just Spring Boot.

The suite is deliberately focused rather than a 1:1 copy of the ~40 Spring specs. Per-panel UI logic is
already covered by the Spring e2e suite (same Vue code) and the Quarkus API/response contract is covered
by the `bootui-quarkus-integration-tests` `@QuarkusTest` modules. What only a browser against a live
Quarkus backend can prove is what these tests cover:

- **`app-shell.spec.js`** — the parity proof. Reads the real `GET /bootui/api/panels`, then navigates to
  **every panel the Quarkus adapter reports as available** and asserts each renders its heading. Also
  checks the topbar reports `Quarkus <version>`, the framework-aware **Quarkus** advisor label in the
  sidebar, and that Spring-only panels are collected in the "Disabled / unavailable" group.
- **`quarkus-advisor.spec.js`** — the Spring advisor is replaced by a framework-aware **Quarkus** advisor
  (same shared view, `platform`-driven copy): heading, "Run Quarkus checks", Quarkus idiom rules.
- **`dev-services.spec.js`** — the Dev Services panel renders the build-time Dev Services snapshot (the
  throwaway PostgreSQL container) and explains that live logs/restart are managed by Quarkus, not BootUI.
- **`cache.spec.js`** — the renamed **Cache** panel lists the Quarkus caches and clears one.
- **`not-applicable.spec.js`** — Spring-only panels (DevTools, Conditions, HTTP Sessions, …) degrade
  honestly with a "Not applicable on Quarkus" explanation instead of breaking.

The specs below go deeper: each drives one action-capable panel's real state-changing endpoint (a scan, a
toggle, a database check, …) through the browser, proving the **Quarkus backend** — not just the shared Vue
rendering already covered by Spring's e2e suite — performs the action end to end.

- **`architecture.spec.js`** — runs the Architecture advisor and asserts real ArchUnit violations from the
  sample's `ArchitectureIssuesResource` demo, then dismisses and restores a rule.
- **`hibernate.spec.js`** — runs the Hibernate advisor and asserts real entity-mapping findings from the
  sample's `Sample*` demo entities (identifier, mapping, fetch-strategy rules).
- **`security.spec.js`** — renders the Quarkus-specific security copy, then runs the security advisor and
  asserts real findings from the sample's `SecureResource` / `AdminResource` demo endpoints.
- **`pentesting.spec.js`** — runs the Pentesting advisor's synthetic loopback probes and asserts a real
  CORS-misconfiguration finding against the running sample app.
- **`memory.spec.js`** — runs the Memory advisor and asserts a real JVM runtime snapshot (heap, threads, GC)
  with plausible values.
- **`rest-api.spec.js`** — runs the REST API advisor and asserts real counts of the sample's own JAX-RS
  resources and endpoints.
- **`vulnerabilities.spec.js`** — lists the real build-time dependency inventory, then scans it through a
  deterministic loopback OSV fixture. The fixture validates the real Maven query payload and returns stable
  out-of-order advisories so parsing, aggregation, ordering, and configured limits are exercised without a
  public-network dependency.
- **`vulnerabilities-live.spec.js`** — a live OSV protocol smoke that runs only from the scheduled
  `OSV live smoke` workflow. It is bounded to five packages, ten advisory details, and ten seconds per
  request, and it never runs in normal PR CI.
- **`loggers.spec.js`** — filters the live JBoss LogManager loggers, sets a sample logger to `DEBUG` through
  the real logging backend, and resets it.
- **`heap-dump.spec.js`** — analyzes the live heap histogram, then captures and deletes a real heap dump
  file on disk.
- **`http-probe.spec.js`** — sends a real loopback GET probe to the sample app's own port and clears the
  response.
- **`traces.spec.js`** — seeds real OpenTelemetry spans through the sample app, asserts they render, then
  clears the retained buffer.
- **`exceptions.spec.js`** — captures a real thrown exception, asserts its message is secret-masked, then
  clears the buffer.
- **`flyway.spec.js`** — re-runs Migrate against the real Postgres database (idempotent "already up to
  date") and confirms the Clean action's configuration without invoking the destructive action.
- **`liquibase.spec.js`** — re-runs Update against the real Postgres database (idempotent "already up to
  date").
- **`mcp-server.spec.js`** — toggles the MCP server bridge on and back off, asserting the live status
  updates.
- **`sql-trace.spec.js`** — beyond the original SQL/Live-Activity capture assertions, also pauses/resumes
  recording and clears the retained buffer through the live `BootUiSqlTraceProducer` wrap.
- **`threads.spec.js`** — beyond the original snapshot/filter assertions, also downloads a real raw thread
  dump generated by the live JVM.

## Prerequisites

1. **A JDK from 17 to 27.** The sample app stays in the Maven reactor on every JDK, but Hibernate ORM
   augmentation is disabled on JDK 28+, whose class files the platform's ByteBuddy does not recognize (see
   `bootui-quarkus-sample-app/pom.xml`).
2. **Docker or Podman running.** Quarkus Dev Services starts a throwaway PostgreSQL container at boot.
3. **The BootUI artifacts installed locally**, so `quarkus:dev` can resolve the `bootui-quarkus`
   extension:

   ```bash
   ./mvnw -pl bootui-quarkus-sample-app -am -DskipTests install
   ```

## Running

```bash
cd bootui-quarkus-sample-app/e2e
npm ci
npx playwright install --with-deps chromium
npm test
```

Playwright boots both a deterministic loopback OSV fixture and the sample app with `./mvnw quarkus:dev`,
then waits for `http://localhost:8082/bootui/api/overview`. Fixture-backed runs always own the Quarkus
process so its test-only OSV base URI and limits are guaranteed to apply.

### With the BootUI agent

`playwright.agent.config.js` runs the whole suite plus `tests-agent/` against `quarkus:dev` started with
`-Djvm.args=-javaagent:...`, the jar `./mvnw install -pl bootui-agent -am` built in `bootui-agent/target`, or
`BOOTUI_AGENT_JAR`. It sets the `agentAttached` fixture option, so the Java Agent, Code Inventory, and Code Paths specs
assert the armed claim, the run's inventory, and the seeded route's tree instead of the unavailable state:

```bash
npm run test:agent
```

## Useful environment variables

| Variable                   | Default                   | Purpose                                                    |
| -------------------------- | ------------------------- | ---------------------------------------------------------- |
| `BOOTUI_SAMPLE_PORT`       | `8082`                    | Port the Quarkus sample listens on.                        |
| `BOOTUI_BASE_URL`          | `http://localhost:<port>` | Base URL the browser hits.                                 |
| `BOOTUI_SKIP_WEBSERVER`    | _(unset)_                 | Set to `1` to test an already-running server / list tests. |
| `BOOTUI_WEBSERVER_TIMEOUT` | `300000`                  | Startup timeout (ms); raise it on slow Dev Services pulls. |
| `BOOTUI_OSV_FIXTURE_PORT`  | `18080`                   | Port for the deterministic loopback OSV fixture.           |
| `BOOTUI_OSV_LIVE`          | _(unset)_                 | Set to `1` only for the focused live OSV smoke.            |
| `BOOTUI_AGENT_JAR`         | `bootui-agent/target`     | The agent jar the agent suite attaches.                    |

## CI

The `quarkus-e2e` job in [`.github/workflows/build.yml`](../../.github/workflows/build.yml) runs this
suite, and in a second leg the agent suite, on JDK 17 (Docker is available on the GitHub-hosted runner), after building the extension and the
sample app. It always uses the deterministic fixture. [`.github/workflows/osv-live-smoke.yml`](../../.github/workflows/osv-live-smoke.yml)
runs the separate live-only spec weekly and on manual dispatch; it is bounded and does not participate in
pull-request status checks.
