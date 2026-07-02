// @ts-check
import {spawn} from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {expect, test} from './fixtures.js'

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
const sampleAppDir = path.resolve(e2eDir, '..')
const repoRoot = path.resolve(sampleAppDir, '..')
const mvnw = path.join(repoRoot, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
// Quarkus dev-mode has to augment the app and let Dev Services pull/start a throwaway PostgreSQL
// (and Ollama) container, which is much slower than a Spring Boot start on a cold CI runner -
// mirrors playwright.config.js's own WEBSERVER_TIMEOUT default.
const startupTimeoutMs = 300_000

test.describe.configure({mode: 'serial'})
test.describe('Read-only properties (Quarkus)', () => {
  test.setTimeout(360_000)

  test('global read-only locks action panels in the API and browser UI', async ({page, request}) => {
    const app = await startSampleApp({'bootui.read-only': 'true'})

    try {
      const panels = await fetchPanels(request, app.baseUrl)
      const overviewPanel = panels.find((panel) => panel.id === 'overview')
      const probePanel = panels.find((panel) => panel.id === 'http-probe')
      const heapDumpPanel = panels.find((panel) => panel.id === 'heap-dump')

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

      const probeResponse = await request.post(`${app.baseUrl}/bootui/api/http-probe`, {
        data: {method: 'GET', path: '/api/sample/hello', headers: {}, body: ''}
      })
      expect(probeResponse.status()).toBe(403)
      await assertBlockedPanelAccess(probeResponse, 'http-probe', 'BootUI is read-only via bootui.read-only=true')

      const heapDumpResponse = await request.post(`${app.baseUrl}/bootui/api/heap-dump/capture`)
      expect(heapDumpResponse.status()).toBe(403)
      await assertBlockedPanelAccess(heapDumpResponse, 'heap-dump', 'BootUI is read-only via bootui.read-only=true')

      await assertHttpProbeReadOnly(page, app.baseUrl, 'BootUI is read-only via bootui.read-only=true')
      await assertHeapDumpReadOnly(page, app.baseUrl, 'BootUI is read-only via bootui.read-only=true')
    } finally {
      await app.stop()
    }
  })

  test('per-panel read-only locks only the configured action panel', async ({page, request}) => {
    const app = await startSampleApp({'bootui.panels.http-probe.read-only': 'true'})

    try {
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
 */
async function startSampleApp(properties) {
  const port = await findAvailablePort()
  const baseUrl = `http://127.0.0.1:${port}`
  const output = createOutputBuffer()

  if (!fs.existsSync(mvnw)) {
    throw new Error(`Maven Wrapper not found at ${mvnw}`)
  }

  const child = spawn(
    mvnw,
    [
      '-f',
      path.join(sampleAppDir, 'pom.xml'),
      '-q',
      'quarkus:dev',
      `-Dquarkus.http.port=${port}`,
      '-Dquarkus.test.continuous-testing=disabled',
      '-Dquarkus.analytics.disabled=true',
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
