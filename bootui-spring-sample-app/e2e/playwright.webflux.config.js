// @ts-check
import {defineConfig, devices} from '@playwright/test'

/**
 * Playwright configuration for a lightweight WebFlux smoke suite.
 *
 * Reuses this same e2e npm project (rather than a second one) to drive the BootUI console served at
 * `http://localhost:8081/bootui/` by the reactive `bootui-spring-webflux-sample-app` module - the
 * WebFlux/Netty sibling of the servlet `bootui-spring-sample-app` the default `playwright.config.js`
 * targets. This is deliberately a small smoke suite (one spec file), not a full per-panel port of the
 * servlet suite: its job is to prove the reactive adapter serves the same console shell and that the
 * panels that stay unavailable (HTTP Sessions, Spring Security, MCP Server, REST Client) surface their
 * WebFlux-specific "unavailable" copy through the real UI, not to re-verify panel behavior already
 * covered by the shared conformance suite and the servlet e2e spec-per-panel coverage.
 *
 * By default Playwright boots the sample app for you via `./mvnw spring-boot:run` (requires
 * `./mvnw install` of the parent build first, including `bootui-spring-webflux-sample-app`). If you
 * already have it running on the configured port it will be reused automatically.
 */
const PORT = Number(process.env.BOOTUI_WEBFLUX_PORT || 8081)
const BASE_URL = process.env.BOOTUI_WEBFLUX_BASE_URL || `http://localhost:${PORT}`

export default defineConfig({
  testDir: './tests-webflux',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', {open: 'never', outputFolder: 'playwright-report-webflux'}],
        ['junit', {outputFile: 'test-results/junit/results-webflux.xml'}]
      ]
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

  // Set BOOTUI_SKIP_WEBSERVER=1 to disable the auto-started Maven server when running against an
  // already-deployed instance. Like the default config, BOOTUI_WEBFLUX_PORT only changes the URL
  // Playwright polls/targets - the sample app itself listens on the port fixed in its own
  // application.properties (8081 by default), so override both together if you need a non-default port.
  webServer: process.env.BOOTUI_SKIP_WEBSERVER
    ? undefined
    : {
        // Run from the e2e module through the root Maven Wrapper, against the reactive sample app.
        command:
          '../../mvnw -f ../../bootui-spring-webflux-sample-app/pom.xml -q spring-boot:run ' +
          '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false',
        url: `${BASE_URL}/bootui/api/overview`,
        reuseExistingServer: !process.env.CI,
        stdout: 'pipe',
        stderr: 'pipe',
        timeout: 120_000
      }
})
