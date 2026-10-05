// @ts-check
import {defineConfig, devices} from '@playwright/test'
import {agentJar} from './agent-jar.js'

/**
 * Playwright configuration for the Spring MVC sample with the BootUI agent attached (docs/PLAN-v2.md M5-2).
 *
 * The default suites run the sample without the agent and assert the Java Agent panel says so; this one starts it with
 * `-javaagent` and checks the executors sensor, the propagated work in Live Activity and the request profile, and the
 * `work-after-response` observation, then reruns the Live Activity, app-shell, and Java Agent view specs, which must
 * hold with the agent attached too (the last asserts the armed state through the `agentAttached` fixture option). The
 * agent jar is `BOOTUI_AGENT_JAR`, or the one `./mvnw install` built in `bootui-agent/target`. Set
 * `SERVER_PORT` with `BOOTUI_AGENT_SAMPLE_PORT` to run it beside another sample.
 */
const PORT = Number(process.env.BOOTUI_AGENT_SAMPLE_PORT || process.env.SERVER_PORT || 8080)
const BASE_URL = process.env.BOOTUI_AGENT_BASE_URL || `http://localhost:${PORT}`
const MAVEN_REPO = process.env.BOOTUI_MAVEN_REPO_LOCAL
const REPO_ARG = MAVEN_REPO ? ` -Dmaven.repo.local=${MAVEN_REPO}` : ''
const WEBSERVER_TIMEOUT = Number(process.env.BOOTUI_WEBSERVER_TIMEOUT || 240_000)

// The default sensors and the opt-in environment sensor, whose Side Effects seed the side-effects spec asserts (M5-5d).
const JVM_ARGUMENTS = `-javaagent:${agentJar()} -Dspring.devtools.restart.enabled=false -Dbootui.agent.sensors=executors,inventory,code-paths,processes,network,files,environment`

export default defineConfig({
  testDir: '.',
  // The agent's own specs, then the Live Activity and app-shell specs, which hold with the agent attached as without it,
  // and the Java Agent view spec, which asserts the armed state here through the agentAttached option below.
  testMatch: [
    'tests-agent/**/*.spec.js',
    'tests/activity.spec.js',
    'tests/app-shell.spec.js',
    'tests/java-agent.spec.js',
    'tests/code-inventory.spec.js',
    'tests/code-paths.spec.js',
    'tests/side-effects.spec.js'
  ],
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  outputDir: 'test-results-agent',
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', {open: 'never', outputFolder: 'playwright-report-agent'}],
        ['junit', {outputFile: 'test-results-agent/junit/results.xml'}]
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
        command: `../../mvnw${REPO_ARG} -f ../pom.xml -q spring-boot:run "-Dspring-boot.run.jvmArguments=${JVM_ARGUMENTS}"`,
        url: `${BASE_URL}/bootui/api/overview`,
        reuseExistingServer: !process.env.CI,
        stdout: 'pipe',
        stderr: 'pipe',
        timeout: WEBSERVER_TIMEOUT,
        gracefulShutdown: {signal: 'SIGTERM', timeout: 30_000}
      }
})
