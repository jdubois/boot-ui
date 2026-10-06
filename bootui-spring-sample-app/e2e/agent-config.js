// @ts-check
import {existsSync} from 'node:fs'
import {resolve} from 'node:path'
import {defineConfig, devices} from '@playwright/test'
import {agentJar} from './agent-jar.js'

/**
 * The Spring MVC sample with the BootUI agent attached (docs/PLAN-v2.md M5-2, M5-12), shared by the agent suites.
 *
 * Without a companion, the whole MVC suite (`tests/`) runs against the sample started with `-javaagent`, so every spec
 * must hold with the agent attached as without it, plus the agent-only specs in `tests-agent/`. The Java Agent, Code
 * Inventory, Code Paths, and Side Effects specs read the `agentAttached` fixture option and assert the armed claim and
 * the recorded run instead of the unavailable state; the read-only spec starts its own samples with the same JVM
 * arguments through the `sampleJvmArguments` option.
 *
 * With a companion (§5.13's coexistence legs), the sample starts with another agent beside BootUI's, and only the specs
 * that exercise the agent's evidence run (claims and sensor self-tests, Live Activity, Code Paths, Code Inventory, Side
 * Effects), plus the companion's own spec in `tests-agent-companion/`, which proves the other agent still works:
 *
 * - `opentelemetry-first` and `opentelemetry-last`: the OpenTelemetry Java agent before or after BootUI's on the command
 *   line, exporting its spans over OTLP/HTTP to BootUI's own receiver under a service name of its own.
 * - `jacoco`: JaCoCo's agent first, as the Maven plugin's `prepare-agent` puts it, serving its coverage over TCP.
 *
 * The companion jars are the ones the sample's build copies to `target/agent-companions` from Maven Central (pinned in
 * the root POM). The BootUI agent jar is `BOOTUI_AGENT_JAR`, or the one `./mvnw install` built in `bootui-agent/target`.
 * Set `SERVER_PORT` with `BOOTUI_AGENT_SAMPLE_PORT`, and `BOOTUI_JACOCO_PORT`, to run beside another sample.
 *
 * @param {{companion?: 'opentelemetry-first' | 'opentelemetry-last' | 'jacoco'}} [options]
 */
export function agentConfig({companion} = {}) {
  const port = Number(process.env.BOOTUI_AGENT_SAMPLE_PORT || process.env.SERVER_PORT || 8080)
  const baseUrl = process.env.BOOTUI_AGENT_BASE_URL || `http://localhost:${port}`
  const mavenRepo = process.env.BOOTUI_MAVEN_REPO_LOCAL
  const repoArg = mavenRepo ? ` -Dmaven.repo.local=${mavenRepo}` : ''
  const webServerTimeout = Number(process.env.BOOTUI_WEBSERVER_TIMEOUT || 240_000)
  const suffix = companion ? `agent-${companion}` : 'agent'

  // The default sensors, the opt-in files, environment, thread-activity, and thread-locals sensors, and blocking, named
  // whatever its default, whose Side Effects seeds the side-effects spec asserts (M5-5c, M5-5d, M5-5e, M5-5f).
  const bootUi = [
    `-javaagent:${agentJar()}`,
    '-Dbootui.agent.sensors=executors,inventory,code-paths,processes,network,files,environment,blocking,thread-activity,thread-locals'
  ]
  const companionOptions = companionAgent(companion, baseUrl)
  const agents =
    companion === 'opentelemetry-last'
      ? [...bootUi, ...companionOptions.jvmArguments]
      : [...companionOptions.jvmArguments, ...bootUi]
  const jvmArguments = [...agents, '-Dspring.devtools.restart.enabled=false'].join(' ')

  return defineConfig({
    testDir: '.',
    testMatch: companion
      ? [
          'tests-agent/**/*.spec.js',
          'tests/activity.spec.js',
          'tests/java-agent.spec.js',
          'tests/code-inventory.spec.js',
          'tests/code-paths.spec.js',
          'tests/side-effects.spec.js',
          `tests-agent-companion/${companionOptions.spec}.spec.js`
        ]
      : ['tests/**/*.spec.js', 'tests-agent/**/*.spec.js'],
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: 1,
    outputDir: `test-results-${suffix}`,
    reporter: process.env.CI
      ? [
          ['list'],
          ['html', {open: 'never', outputFolder: `playwright-report-${suffix}`}],
          ['junit', {outputFile: `test-results-${suffix}/junit/results.xml`}]
        ]
      : 'list',
    timeout: 60_000,
    expect: {timeout: 10_000},

    use: {
      baseURL: baseUrl,
      trace: 'retain-on-failure',
      screenshot: 'only-on-failure',
      video: 'retain-on-failure',
      extraHTTPHeaders: {'X-Forwarded-For': '127.0.0.1'},
      agentAttached: true,
      sampleJvmArguments: jvmArguments,
      agentCompanion: companionOptions.fixture
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
          command: `../../mvnw${repoArg} -f ../pom.xml -q spring-boot:run "-Dspring-boot.run.jvmArguments=${jvmArguments}"`,
          url: `${baseUrl}/bootui/api/overview`,
          reuseExistingServer: !process.env.CI,
          stdout: 'pipe',
          stderr: 'pipe',
          timeout: webServerTimeout,
          gracefulShutdown: {signal: 'SIGTERM', timeout: 30_000}
        }
  })
}

/**
 * The companion agent's JVM arguments, the spec that proves it still works, and what that spec needs to know.
 *
 * @param {string | undefined} companion
 * @param {string} baseUrl
 * @returns {{jvmArguments: string[], spec?: string, fixture: import('./tests/fixtures.js').AgentCompanion | null}}
 */
function companionAgent(companion, baseUrl) {
  switch (companion) {
    case undefined:
      return {jvmArguments: [], fixture: null}
    case 'opentelemetry-first':
    case 'opentelemetry-last': {
      const serviceName = 'bootui-sample-opentelemetry-agent'
      // 127.0.0.1, not the sample's localhost: Side Effects attributes a connect to a configured exporter endpoint to
      // that exporter, and the side-effects spec's SDK seed connects to localhost:<port>, which must stay its own.
      const endpoint = `${baseUrl.replace('//localhost:', '//127.0.0.1:')}/bootui/api/otlp/v1/traces`
      return {
        jvmArguments: [
          `-javaagent:${companionJar('opentelemetry-javaagent.jar')}`,
          `-Dotel.service.name=${serviceName}`,
          '-Dotel.traces.exporter=otlp',
          '-Dotel.exporter.otlp.traces.protocol=http/protobuf',
          `-Dotel.exporter.otlp.traces.endpoint=${endpoint}`,
          '-Dotel.bsp.schedule.delay=500',
          '-Dotel.metrics.exporter=none',
          '-Dotel.logs.exporter=none'
        ],
        spec: 'opentelemetry',
        fixture: {name: companion, serviceName}
      }
    }
    case 'jacoco': {
      const jacocoPort = Number(process.env.BOOTUI_JACOCO_PORT || 6300)
      return {
        jvmArguments: [
          `-javaagent:${companionJar('jacocoagent.jar')}=output=tcpserver,address=127.0.0.1,port=${jacocoPort},includes=io.github.jdubois.bootui.*`
        ],
        spec: 'jacoco',
        fixture: {name: companion, jacocoPort}
      }
    }
    default:
      throw new Error(`Unknown agent companion: ${companion}`)
  }
}

/**
 * @param {string} name the jar's file name in the sample's target/agent-companions
 * @returns {string}
 */
function companionJar(name) {
  const jar = resolve('../target/agent-companions', name)
  if (!existsSync(jar)) {
    throw new Error(
      `No ${jar}: run ./mvnw install -pl bootui-spring-sample-app -am, which copies it from Maven Central`
    )
  }
  return jar
}
