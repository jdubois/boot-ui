# BootUI sample app — Playwright end-to-end tests

This module hosts a full integration test suite that drives the **BootUI
console** served by `bootui-sample-app` using a real Chromium browser. It
validates every BootUI screen from the perspective of a developer using the
console against a running Spring Boot application.

## What is covered

Each Playwright spec file targets one of the BootUI views (or a cross-cutting
flow) exposed by the sample app:

| Spec                     | Verifies                                                                                                              |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| `app-shell.spec.js`      | Top navbar, sidebar links, deep-linking, navigation between every section                                             |
| `overview.spec.js`       | Application / Runtime / Activation cards, refresh button                                                              |
| `health.spec.js`         | Health tree renders with an overall status badge                                                                      |
| `metrics.spec.js`        | Micrometer meter browser, live graph, measurements, and type filtering                                                |
| `memory.spec.js`         | Heap / non-heap cards and memory pools render without tuning controls                                                 |
| `tuning-advisor.spec.js` | JVM options, Kubernetes calculator, copy feedback, and calculator updates                                             |
| `startup.spec.js`        | Startup timeline displays step rows                                                                                   |
| `config.spec.js`         | Property search, add an override (`sample.greeting`), confirm + delete it                                             |
| `profile-diff.spec.js`   | Profile sources & properties render with filtering                                                                    |
| `loggers.spec.js`        | Logger search, change `io.github.jdubois.bootui.sample` to `WARN`, reset                                              |
| `beans.spec.js`          | Bean list rendering, name filter, classification filter                                                               |
| `conditions.spec.js`     | Positive / negative auto-config tabs, filtering                                                                       |
| `mappings.spec.js`       | HTTP mappings include the sample app routes, filter narrows the list                                                  |
| `scheduled.spec.js`      | Scheduled tasks view lists the sample echo scheduler                                                                  |
| `data.spec.js`           | `ProductRepository` is listed, detail panel shows `searchByName`                                                      |
| `spring-cache.spec.js`   | Spring Cache managers, cache details, annotations, metrics, and guarded clear actions                                 |
| `security.spec.js`       | Filter chains list `/api/secure`, explain endpoint returns a match                                                    |
| `ai.spec.js`             | AI Usage summaries, token charts, content-capture guidance, and disabled states                                       |
| `traces.spec.js`         | Local trace list, waterfall details, OTLP ingest behavior, and clear action                                           |
| `log-tail.spec.js`       | Log Tail connects, streams new events, pause / resume / clear controls work                                           |
| `http-probe.spec.js`     | Probe `/api/hello` and assert the response body is shown                                                              |
| `pentesting.spec.js`     | OWASP hygiene report rendering, check details, and explicit scan controls                                             |
| `dependencies.spec.js`   | Vulnerability inventory, severity ordering, OSV scan states, and read-only behavior                                   |
| `devtools.spec.js`       | DevTools LiveReload / restart status cards and guarded action feedback                                                |
| `dev-services.spec.js`   | Dev Services snapshot, filtering, details, log viewing, and disabled restart controls                                 |
| `copilot.spec.js`        | Copilot dashboard, session explorer, sanitized events, raw reveal gating, and auto-refresh controls                   |
| `claude-code.spec.js`    | Claude Code dashboard, project-log parsing, sanitized events, raw reveal gating, and auto-refresh controls            |
| `read-only.spec.js`      | Global and per-panel read-only properties block unsafe APIs and lock mutating browser controls                        |
| `sample-api.spec.js`     | Sample REST API (`/api/hello`, `/api/secure`, `/api/sample/hello`, `/api/sample/products`) and basic-auth on `/admin` |

## Prerequisites

- Node.js 20+
- Java 17 and the repository Maven Wrapper (used to build & run the sample app)
- The BootUI parent build must be installed locally so the sample app can
  resolve its modules:

  ```bash
  cd ../..            # back to the repository root
  ./mvnw -DskipTests install
  ```

## Install

```bash
cd bootui-sample-app/e2e
npm install
npx playwright install --with-deps chromium
```

## Run

```bash
# Lets Playwright start the sample app for you (via ./mvnw spring-boot:run)
npm test
```

If you prefer to start the sample app yourself, run it on port 8080 and then
launch the tests — Playwright will reuse the running server:

```bash
# terminal 1
cd ../..
./mvnw -pl bootui-sample-app spring-boot:run
# terminal 2
cd bootui-sample-app/e2e
npm test
```

## Configuration

- `BOOTUI_BASE_URL` — override the base URL (default `http://localhost:8080`).
- `BOOTUI_SAMPLE_PORT` — override the port used to build the default base URL.

## Artefacts

Playwright writes HTML reports to `playwright-report/` and traces / screenshots
/ videos for failed tests under `test-results/`. Both folders are git-ignored.
