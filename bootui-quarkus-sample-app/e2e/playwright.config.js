// @ts-check
import {defineConfig, devices} from '@playwright/test'

/**
 * Playwright configuration for the BootUI Quarkus sample app integration test suite.
 *
 * The tests drive the BootUI console served at `http://localhost:8082/bootui/`
 * by the `bootui-quarkus-sample-app` Quarkus module, using a real Chromium browser.
 * It is the Quarkus analogue of `bootui-spring-sample-app/e2e` and proves that the one shared
 * Vue UI works end-to-end against the Quarkus adapter, not just the Spring one.
 *
 * By default Playwright boots the sample app for you via `./mvnw quarkus:dev` (requires a prior
 * `./mvnw install` so the `bootui-quarkus` extension is in the local Maven repository, plus a
 * JDK from 17 to 27 — Hibernate augmentation is skipped on JDK 28+). Quarkus Dev Services
 * starts a throwaway PostgreSQL container, so **Docker (or Podman) must be running**. Fixture-backed
 * runs always start their own app process so the deterministic OSV configuration cannot be bypassed.
 */
const PORT = Number(process.env.BOOTUI_SAMPLE_PORT || 8082)
const BASE_URL = process.env.BOOTUI_BASE_URL || `http://localhost:${PORT}`
const OSV_FIXTURE_PORT = Number(process.env.BOOTUI_OSV_FIXTURE_PORT || 18080)
const OSV_FIXTURE_BASE_URL = `http://127.0.0.1:${OSV_FIXTURE_PORT}`
const USE_LIVE_OSV = process.env.BOOTUI_OSV_LIVE === '1'

// Quarkus dev mode has to augment the application and let Dev Services pull/start the PostgreSQL
// container, which on a cold CI runner is much slower than a Spring Boot start, so allow the
// web-server startup timeout to be raised from the environment.
const WEBSERVER_TIMEOUT = Number(process.env.BOOTUI_WEBSERVER_TIMEOUT || 300_000)
// The AI spec mocks every AI endpoint, so the LangChain4j Ollama Dev Services are disabled: they
// would otherwise pull a multi-gigabyte Ollama image and llama3 model before the app accepts requests.
const QUARKUS_COMMAND =
  `../../mvnw -f ../pom.xml quarkus:dev -Dquarkus.http.port=${PORT}` +
  ' -Dquarkus.test.continuous-testing=disabled -Dquarkus.analytics.disabled=true' +
  ' -Dquarkus.langchain4j.devservices.enabled=false -Dquarkus.langchain4j.ollama.devservices.enabled=false' +
  ' -Dbootui.vulnerabilities.epss-enabled=false' +
  (USE_LIVE_OSV
    ? ' -Dbootui.vulnerabilities.request-timeout=10s' +
      ' -Dbootui.vulnerabilities.max-packages=5' +
      ' -Dbootui.vulnerabilities.max-advisories=10'
    : ` -Dbootui.vulnerabilities.osv-base-uri=${OSV_FIXTURE_BASE_URL}` +
      ' -Dbootui.vulnerabilities.request-timeout=2s' +
      ' -Dbootui.vulnerabilities.max-packages=3' +
      ' -Dbootui.vulnerabilities.max-advisories=2')

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [['list'], ['html', {open: 'never'}], ['junit', {outputFile: 'test-results/junit/results.xml'}]]
    : 'list',
  timeout: 60_000,
  expect: {timeout: 10_000},

  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // BootUI restricts itself to loopback by default; make absolutely sure we hit it that way.
    extraHTTPHeaders: {'X-Forwarded-For': '127.0.0.1'}
  },

  projects: [
    {
      name: 'chromium',
      use: {...devices['Desktop Chrome']}
    }
  ],

  // Set BOOTUI_SKIP_WEBSERVER=1 to disable the auto-started Maven server when
  // running the tests against an already-deployed instance, listing tests, etc.
  webServer: process.env.BOOTUI_SKIP_WEBSERVER
    ? undefined
    : [
        ...(!USE_LIVE_OSV
          ? [
              {
                command: 'node fixtures/osv-server.js',
                url: `${OSV_FIXTURE_BASE_URL}/health`,
                reuseExistingServer: false,
                stdout: 'pipe',
                stderr: 'pipe',
                timeout: 10_000,
                gracefulShutdown: {signal: 'SIGTERM', timeout: 5_000}
              }
            ]
          : []),
        {
          // Run the Quarkus sample in dev mode through the root Maven Wrapper. BootUI activates
          // automatically under `quarkus:dev`. Continuous testing is disabled so the dev server only
          // serves HTTP, and analytics prompts are suppressed for non-interactive CI.
          command: QUARKUS_COMMAND,
          url: `${BASE_URL}/bootui/api/overview`,
          // A fixture-backed run must own the Quarkus process so its deterministic scanner settings apply.
          reuseExistingServer: USE_LIVE_OSV && !process.env.CI,
          stdout: 'pipe',
          stderr: 'pipe',
          timeout: WEBSERVER_TIMEOUT,
          // Without this Playwright SIGKILLs the process group, so quarkus:dev never shuts down cleanly
          // and leaves stopping its Dev Services containers to Ryuk. SIGTERM reaches the whole group.
          gracefulShutdown: {signal: 'SIGTERM', timeout: 30_000}
        }
      ]
})
