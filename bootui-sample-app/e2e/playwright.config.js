// @ts-check
import {defineConfig, devices} from '@playwright/test'

/**
 * Playwright configuration for the BootUI sample app integration test suite.
 *
 * The tests drive the BootUI console served at `http://localhost:8080/bootui/`
 * by the `bootui-sample-app` Spring Boot module, using a real Chromium browser.
 *
 * By default Playwright will boot the sample app for you via
 * `./mvnw spring-boot:run` (requires `./mvnw install` of the parent build first so
 * the BootUI artefacts are available in the local Maven repository). If you
 * already have the app running on port 8080 it will be reused automatically.
 */
const PORT = Number(process.env.BOOTUI_SAMPLE_PORT || 8080)
const BASE_URL = process.env.BOOTUI_BASE_URL || `http://localhost:${PORT}`

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', {open: 'never'}]] : 'list',
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
        // Run from the e2e module through the root Maven Wrapper.
        command:
          '../../mvnw -f ../pom.xml -q spring-boot:run -Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false',
        url: `${BASE_URL}/bootui/api/overview`,
        reuseExistingServer: !process.env.CI,
        stdout: 'pipe',
        stderr: 'pipe',
        timeout: 240_000
      }
})
