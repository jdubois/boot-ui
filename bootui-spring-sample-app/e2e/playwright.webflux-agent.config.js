// @ts-check
import {defineConfig, devices} from '@playwright/test'
import {agentJar} from './agent-jar.js'

/**
 * Playwright configuration for the Spring WebFlux sample with the BootUI agent attached (docs/PLAN-v2.md M5-12).
 *
 * Runs the whole WebFlux suite (`tests-webflux/`) against the reactive sample started with `-javaagent`, so every spec
 * must hold with the agent attached as without it, plus the agent-only specs in `tests-webflux-agent/`. The Java Agent,
 * Code Inventory, and Code Paths specs read the `agentAttached` fixture option, which only this configuration sets, and
 * assert the armed claim, the run's inventory, and the assembly-only route trees instead of the unavailable state. The
 * agent jar is `BOOTUI_AGENT_JAR`, or the one `./mvnw install` built in `bootui-agent/target`. Set
 * `BOOTUI_WEBFLUX_AGENT_PORT` to run it beside another sample: unlike the default WebFlux configuration, this one passes
 * the port to the application too.
 */
const PORT = Number(process.env.BOOTUI_WEBFLUX_AGENT_PORT || 8081)
const BASE_URL = process.env.BOOTUI_WEBFLUX_AGENT_BASE_URL || `http://localhost:${PORT}`
const MAVEN_REPO = process.env.BOOTUI_MAVEN_REPO_LOCAL
const REPO_ARG = MAVEN_REPO ? ` -Dmaven.repo.local=${MAVEN_REPO}` : ''
const WEBSERVER_TIMEOUT = Number(process.env.BOOTUI_WEBSERVER_TIMEOUT || 240_000)

// The default sensors, the opt-in files, environment, and thread-activity sensors, and blocking, named whatever its
// default, and the opt-in thread-locals sensor, whose Side Effects seeds the side-effects spec asserts (M5-5c, M5-5d,
// M5-5e, M5-5f), and the opt-in caught-exceptions
// sensor, whose Exceptions panel seeds the caught-exceptions spec asserts (M5-6a2).
const JVM_ARGUMENTS = `-javaagent:${agentJar()} -Dspring.devtools.restart.enabled=false -Dserver.port=${PORT} -Dbootui.agent.sensors=executors,inventory,code-paths,processes,network,files,environment,blocking,thread-activity,thread-locals,resources,caught-exceptions`

export default defineConfig({
  testDir: '.',
  testMatch: ['tests-webflux/**/*.spec.js', 'tests-webflux-agent/**/*.spec.js'],
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  outputDir: 'test-results-webflux-agent',
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', {open: 'never', outputFolder: 'playwright-report-webflux-agent'}],
        ['junit', {outputFile: 'test-results-webflux-agent/junit/results.xml'}]
      ]
    : 'list',
  timeout: 60_000,
  expect: {timeout: 10_000},

  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    extraHTTPHeaders: {'X-Forwarded-For': '127.0.0.1'},
    agentAttached: true
  },

  projects: [
    {
      name: 'chromium',
      use: {...devices['Desktop Chrome']}
    }
  ],

  webServer: process.env.BOOTUI_SKIP_WEBSERVER
    ? undefined
    : {
        command:
          `../../mvnw${REPO_ARG} -f ../../bootui-spring-webflux-sample-app/pom.xml -q spring-boot:run ` +
          `"-Dspring-boot.run.jvmArguments=${JVM_ARGUMENTS}"`,
        url: `${BASE_URL}/bootui/api/overview`,
        reuseExistingServer: !process.env.CI,
        stdout: 'pipe',
        stderr: 'pipe',
        timeout: WEBSERVER_TIMEOUT,
        gracefulShutdown: {signal: 'SIGTERM', timeout: 30_000}
      }
})
