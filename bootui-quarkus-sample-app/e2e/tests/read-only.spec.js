// @ts-check
import {spawn} from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {expect, test} from './fixtures.js'
import {assertScorecardReadOnly} from '../../../bootui-spring-sample-app/e2e/scenarios/action-policy.js'

/**
 * The Quarkus analogue of `bootui-spring-sample-app/e2e/tests/read-only.spec.js`, proving
 * `QuarkusPanelAccessFilter` / `QuarkusPanelAccessConfig` (bootui.read-only /
 * bootui.panels.<id>.read-only) behave at parity with the Spring adapter's `PanelAccessFilter`
 * from a real browser, not just the backend unit/integration suites.
 *
 * Unlike the rest of this suite (which shares the single `quarkus:dev` instance Playwright's
 * `webServer` config starts), each test case here needs a *different* property override, so it
 * spawns its own fresh, throwaway Quarkus dev-mode instance on a random port - the same strategy
 * `read-only.spec.js` uses on the Spring side, adapted for the Quarkus Maven goal and config keys.
 * Properties are passed straight through as JVM system properties (`-Dname=value`) on the
 * `quarkus:dev` command line: MicroProfile Config picks up JVM system properties as a config
 * source automatically, exactly like the shared `playwright.config.js` already relies on for
 * `-Dquarkus.http.port`.
 */
const testDir = path.dirname(fileURLToPath(import.meta.url))
const e2eDir = path.resolve(testDir, '..')
const repoRoot = path.resolve(e2eDir, '../..')
const mvnw = path.join(repoRoot, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
// Quarkus dev-mode has to augment the app and let Dev Services pull/start a throwaway PostgreSQL
// container (Ollama Dev Services are disabled below), which is much slower than a Spring Boot start on a cold CI runner -
// mirrors playwright.config.js's own WEBSERVER_TIMEOUT default.
const startupTimeoutMs = 300_000

test.describe.configure({mode: 'serial'})
test.describe('Read-only properties (Quarkus)', () => {
  test.setTimeout(360_000)

  test('global read-only locks action panels in the API and browser UI', async ({
    page,
    request,
    quarkusGeneration,
    agentAttached
  }, testInfo) => {
    const app = await startSampleApp({'bootui.read-only': 'true'}, quarkusGeneration, agentAttached)

    try {
      await assertSampleIdentity(request, app.baseUrl, quarkusGeneration, agentAttached, testInfo)
      await request.post(`${app.baseUrl}/api/sample/rest-client-capture`)
      const panels = await fetchPanels(request, app.baseUrl)
      const overviewPanel = panels.find((panel) => panel.id === 'overview')
      const probePanel = panels.find((panel) => panel.id === 'http-probe')
      const heapDumpPanel = panels.find((panel) => panel.id === 'heap-dump')
      const restClientPanel = panels.find((panel) => panel.id === 'rest-client-trace')

      expect(overviewPanel?.readOnly).toBe(false)
      expect(probePanel).toMatchObject({
        id: 'http-probe',
        readOnly: true,
        readOnlyReason: 'BootUI is read-only via bootui.read-only=true'
      })
      expect(heapDumpPanel).toMatchObject({
        id: 'heap-dump',
        readOnly: true,
        readOnlyReason: 'BootUI is read-only via bootui.read-only=true'
      })
      expect(restClientPanel).toMatchObject({
        id: 'rest-client-trace',
        available: true,
        readOnly: true,
        readOnlyReason: 'BootUI is read-only via bootui.read-only=true'
      })

      const probeResponse = await request.post(`${app.baseUrl}/bootui/api/http-probe`, {
        data: {method: 'GET', path: '/api/sample/hello', headers: {}, body: ''}
      })
      expect(probeResponse.status()).toBe(403)
      await assertBlockedPanelAccess(probeResponse, 'http-probe', 'BootUI is read-only via bootui.read-only=true')

      const heapDumpResponse = await request.post(`${app.baseUrl}/bootui/api/heap-dump/capture`)
      expect(heapDumpResponse.status()).toBe(403)
      await assertBlockedPanelAccess(heapDumpResponse, 'heap-dump', 'BootUI is read-only via bootui.read-only=true')

      const restClientResponse = await request.post(`${app.baseUrl}/bootui/api/rest-client-trace/recording`, {
        data: {enabled: false}
      })
      expect(restClientResponse.status()).toBe(403)
      await assertBlockedPanelAccess(
        restClientResponse,
        'rest-client-trace',
        'BootUI is read-only via bootui.read-only=true'
      )

      await assertHttpProbeReadOnly(page, app.baseUrl, 'BootUI is read-only via bootui.read-only=true')
      await assertHeapDumpReadOnly(page, app.baseUrl, 'BootUI is read-only via bootui.read-only=true')
      await assertRestClientReadOnly(page, app.baseUrl, 'BootUI is read-only via bootui.read-only=true')
      await assertScorecardReadOnly(page, expect, app.baseUrl, 'BootUI is read-only via bootui.read-only=true')
    } finally {
      await app.stop()
    }
  })

  test('per-panel read-only locks only the configured action panel', async ({
    page,
    request,
    quarkusGeneration,
    agentAttached
  }, testInfo) => {
    const app = await startSampleApp({'bootui.panels.http-probe.read-only': 'true'}, quarkusGeneration, agentAttached)

    try {
      await assertSampleIdentity(request, app.baseUrl, quarkusGeneration, agentAttached, testInfo)
      const panels = await fetchPanels(request, app.baseUrl)
      // NOTE: unlike the Spring spec, this does not use the `config` panel as the "unaffected"
      // control. On Quarkus, Configuration is *always* readOnly (no runtime-override write path
      // exists yet - see QuarkusPanelAvailability), for a reason unrelated to this test, so it
      // would not prove anything about per-panel isolation. Memory is a real action-capable panel
      // with its own write path and no inherent read-only carve-out, so it is a valid control.
      const memoryPanel = panels.find((panel) => panel.id === 'memory')
      const probePanel = panels.find((panel) => panel.id === 'http-probe')

      expect(memoryPanel?.readOnly).toBe(false)
      expect(probePanel).toMatchObject({
        id: 'http-probe',
        readOnly: true,
        readOnlyReason: 'Panel is read-only via bootui.panels.http-probe.read-only=true'
      })

      const probeResponse = await request.post(`${app.baseUrl}/bootui/api/http-probe`, {
        data: {method: 'GET', path: '/api/sample/hello', headers: {}, body: ''}
      })
      expect(probeResponse.status()).toBe(403)
      await assertBlockedPanelAccess(
        probeResponse,
        'http-probe',
        'Panel is read-only via bootui.panels.http-probe.read-only=true'
      )

      const memoryScanResponse = await request.post(`${app.baseUrl}/bootui/api/memory/scan`)
      expect(memoryScanResponse.status()).toBe(200)

      await assertHttpProbeReadOnly(page, app.baseUrl, 'Panel is read-only via bootui.panels.http-probe.read-only=true')
    } finally {
      await app.stop()
    }
  })
})

/**
 * @param {import('@playwright/test').APIRequestContext} request
 * @param {string} baseUrl
 * @param {string} generation
 * @param {boolean} agentAttached
 * @param {import('@playwright/test').TestInfo} testInfo
 */
async function assertSampleIdentity(request, baseUrl, generation, agentAttached, testInfo) {
  const overviewResponse = await request.get(`${baseUrl}/bootui/api/overview`)
  expect(overviewResponse.ok()).toBe(true)
  const overview = await overviewResponse.json()
  const agentResponse = await request.get(`${baseUrl}/bootui/api/java-agent`)
  expect(agentResponse.ok()).toBe(true)
  const agent = await agentResponse.json()
  await testInfo.attach('read-only-server', {
    body: Buffer.from(
      JSON.stringify({
        baseUrl,
        overview,
        agent: {state: agent.state, agentVersion: agent.agentVersion, jdk: agent.jdk, jarPath: agent.jarPath}
      })
    ),
    contentType: 'application/json'
  })
  expect(overview.frameworkName).toBe('Quarkus')
  expect(overview.frameworkVersion).toMatch(new RegExp(`^${generation}\\.`))
  expect(overview.applicationName).toBe(generation === '4' ? 'bootui-quarkus-4-sample' : 'bootui-quarkus-sample')
  expect(agent.state).toBe(agentAttached ? 'ARMED' : 'NOT_ATTACHED')
}

/**
 * @param {import('@playwright/test').APIRequestContext} request
 * @param {string} baseUrl
 */
async function fetchPanels(request, baseUrl) {
  const response = await request.get(`${baseUrl}/bootui/api/panels`)
  expect(response.ok()).toBe(true)
  return (await response.json()).panels
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {string} baseUrl
 * @param {string} reason
 */
async function assertHttpProbeReadOnly(page, baseUrl, reason) {
  await page.goto(`${baseUrl}/bootui/#/http-probe`)
  await expect(
    page
      .locator('main h2')
      .filter({hasText: /^HTTP Probe/})
      .first()
  ).toBeVisible()

  await expect(page.locator('.panel-read-only-alert')).toContainText('Panel read-only')
  await expect(page.locator('.panel-read-only-alert')).toContainText(reason)
  await expect(page.locator('.alert-warning', {hasText: 'HTTP probes are read-only'})).toContainText(reason)
  await expect(page.locator('button.btn-primary', {hasText: 'Send'})).toBeDisabled()
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {string} baseUrl
 * @param {string} reason
 */
async function assertHeapDumpReadOnly(page, baseUrl, reason) {
  await page.goto(`${baseUrl}/bootui/#/heap-dump`)
  await expect(
    page
      .locator('main h2')
      .filter({hasText: /^Heap Dump/})
      .first()
  ).toBeVisible()

  await expect(page.locator('.panel-read-only-alert')).toContainText('Panel read-only')
  await expect(page.locator('.panel-read-only-alert')).toContainText(reason)
  await expect(page.getByRole('button', {name: /Capture heap dump/})).toBeDisabled()
  await expect(page.getByRole('button', {name: 'Analyze live heap'})).toBeDisabled()
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {string} baseUrl
 * @param {string} reason
 */
async function assertRestClientReadOnly(page, baseUrl, reason) {
  await page.goto(`${baseUrl}/bootui/#/rest-client-trace`)
  await expect(
    page
      .locator('main h2')
      .filter({hasText: /^REST Client/})
      .first()
  ).toBeVisible()

  await expect(page.locator('.panel-read-only-alert')).toContainText('Panel read-only')
  await expect(page.locator('.panel-read-only-alert')).toContainText(reason)
  await expect(page.getByRole('button', {name: 'Pause'})).toBeDisabled()
  await expect(page.getByRole('button', {name: 'Clear'})).toBeDisabled()
}

/**
 * @param {import('@playwright/test').APIResponse} response
 * @param {string} panel
 * @param {string} reason
 */
async function assertBlockedPanelAccess(response, panel, reason) {
  expect(await response.json()).toEqual({
    error: 'BootUI panel access denied',
    panel,
    reason
  })
}

/**
 * @param {Record<string, string>} properties
 * @param {string} generation
 * @param {boolean} agentAttached
 */
async function startSampleApp(properties, generation, agentAttached) {
  if (!['3', '4'].includes(generation)) {
    throw new Error('BOOTUI_QUARKUS_GENERATION must be 3 or 4')
  }
  const sampleModule = generation === '4' ? 'bootui-quarkus-4-sample-app' : 'bootui-quarkus-sample-app'
  const sampleAppDir = path.join(repoRoot, sampleModule)
  const jvmArguments = agentAttached ? [`-Djvm.args=-javaagent:${agentJar()}`] : []
  const port = await findAvailablePort()
  const baseUrl = `http://127.0.0.1:${port}`
  const output = createOutputBuffer()

  if (!fs.existsSync(mvnw)) {
    throw new Error(`Maven Wrapper not found at ${mvnw}`)
  }

  const mavenRepo = process.env.BOOTUI_MAVEN_REPO_LOCAL
  const child = spawn(
    mvnw,
    [
      ...(mavenRepo ? [`-Dmaven.repo.local=${mavenRepo}`] : []),
      '-f',
      path.join(sampleAppDir, 'pom.xml'),
      '-q',
      'quarkus:dev',
      `-Dquarkus.http.port=${port}`,
      '-Dquarkus.test.continuous-testing=disabled',
      '-Dquarkus.analytics.disabled=true',
      '-Dquarkus.langchain4j.devservices.enabled=false',
      '-Dquarkus.langchain4j.ollama.devservices.enabled=false',
      ...(generation === '4'
        ? [
            '-Dquarkus.otel.traces.exporter=none',
            '-Dquarkus.otel.metrics.exporter=none',
            '-Dquarkus.otel.logs.exporter=none'
          ]
        : []),
      ...jvmArguments,
      ...Object.entries(properties).map(([name, value]) => `-D${name}=${value}`)
    ],
    {
      cwd: e2eDir,
      env: {...process.env},
      stdio: ['ignore', 'pipe', 'pipe']
    }
  )

  let exitDetails = null
  const exitPromise = new Promise((resolve) => {
    child.once('exit', (code, signal) => {
      exitDetails = {code, signal}
      resolve(exitDetails)
    })
  })
  child.stdout.on('data', (chunk) => output.append(chunk))
  child.stderr.on('data', (chunk) => output.append(chunk))

  async function stop() {
    if (exitDetails) return
    child.kill('SIGTERM')
    await Promise.race([
      exitPromise,
      sleep(15_000).then(() => {
        if (!exitDetails) child.kill('SIGKILL')
        return exitPromise
      })
    ])
  }

  try {
    await waitForServer(`${baseUrl}/bootui/api/overview`, () => exitDetails, output)
  } catch (error) {
    await stop()
    throw error
  }

  return {baseUrl, stop}
}

function agentJar() {
  if (process.env.BOOTUI_AGENT_JAR) {
    if (!fs.existsSync(process.env.BOOTUI_AGENT_JAR)) {
      throw new Error(`BOOTUI_AGENT_JAR names no file: ${process.env.BOOTUI_AGENT_JAR}`)
    }
    return process.env.BOOTUI_AGENT_JAR
  }
  const target = path.join(repoRoot, 'bootui-agent', 'target')
  const jar = fs.existsSync(target)
    ? fs
        .readdirSync(target)
        .find((name) => /^bootui-agent-\d[\w.-]*\.jar$/.test(name) && !/-(tests|sources|javadoc)\.jar$/.test(name))
    : undefined
  if (!jar) {
    throw new Error('No bootui-agent-*.jar: run ./mvnw install -pl bootui-agent -am, or set BOOTUI_AGENT_JAR')
  }
  return path.join(target, jar)
}

async function findAvailablePort() {
  const server = net.createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  const port = typeof address === 'object' && address ? address.port : null
  await new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()))
  })
  if (!port) throw new Error('Unable to allocate a port for the read-only sample app')
  return port
}

async function waitForServer(url, exitDetails, output) {
  const deadline = Date.now() + startupTimeoutMs
  while (Date.now() < deadline) {
    const exit = exitDetails()
    if (exit) {
      throw new Error(
        `Sample app exited before it was ready (code=${exit.code}, signal=${exit.signal}).\n${output.text()}`
      )
    }

    if (await isServerReady(url)) return
    await sleep(500)
  }

  throw new Error(`Timed out waiting for ${url}.\n${output.text()}`)
}

async function isServerReady(url) {
  try {
    const response = await fetch(url, {headers: {'X-Forwarded-For': '127.0.0.1'}})
    return response.ok
  } catch {
    return false
  }
}

function createOutputBuffer() {
  let output = ''
  return {
    append(chunk) {
      output = `${output}${chunk.toString()}`
      if (output.length > 20_000) output = output.slice(-20_000)
    },
    text() {
      return output
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
