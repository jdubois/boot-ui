// @ts-check
import {defineConfig, devices} from '@playwright/test'

/**
 * Playwright configuration for the BootUI Quarkus sample app integration test suite.
 *
 * The tests drive the BootUI console served at `http://localhost:8080/bootui/`
 * by the `bootui-quarkus-sample-app` Quarkus module, using a real Chromium browser.
 * It is the Quarkus analogue of `bootui-spring-sample-app/e2e` and proves that the one shared
 * Vue UI works end-to-end against the Quarkus adapter, not just the Spring one.
 *
 * By default Playwright boots the sample app for you via `./mvnw quarkus:dev` (requires a prior
 * `./mvnw install` so the `bootui-quarkus` extension is in the local Maven repository, plus a
 * supported JDK — the sample is wired into the reactor only on JDK 17/21). Quarkus Dev Services
 * starts a throwaway PostgreSQL container, so **Docker (or Podman) must be running**. If you already
 * have the app running on port 8080 it is reused automatically.
 */
const PORT = Number(process.env.BOOTUI_SAMPLE_PORT || 8080)
const BASE_URL = process.env.BOOTUI_BASE_URL || `http://localhost:${PORT}`

// Quarkus dev mode has to augment the application and let Dev Services pull/start the PostgreSQL
// container, which on a cold CI runner is much slower than a Spring Boot start, so allow the
// web-server startup timeout to be raised from the environment.
const WEBSERVER_TIMEOUT = Number(process.env.BOOTUI_WEBSERVER_TIMEOUT || 300_000)

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
    : {
        // Run the Quarkus sample in dev mode through the root Maven Wrapper. BootUI activates
        // automatically under `quarkus:dev`. Continuous testing is disabled so the dev server only
        // serves HTTP, and analytics prompts are suppressed for non-interactive CI.
        command:
          `../../mvnw -f ../pom.xml quarkus:dev -Dquarkus.http.port=${PORT}` +
          ' -Dquarkus.test.continuous-testing=disabled -Dquarkus.analytics.disabled=true',
        url: `${BASE_URL}/bootui/api/overview`,
        reuseExistingServer: !process.env.CI,
        stdout: 'pipe',
        stderr: 'pipe',
        timeout: WEBSERVER_TIMEOUT
      }
})
