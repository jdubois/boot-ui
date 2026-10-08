// @ts-check
import {expect, test as base} from '@playwright/test'

/**
 * Test fixture that opens a given BootUI view via the hash router and waits
 * for the matching <h2> heading to be visible.
 *
 * Usage:
 *   test('…', async ({ openView }) => {
 *     const page = await openView('beans', 'Beans')
 *   })
 */
export const test = base.extend({
  // Whether the suite's sample runs with the BootUI agent attached: false for the default suites, true in
  // playwright.agent.config.js, which starts the sample with -javaagent. Specs whose expectations differ (the Java
  // Agent view) read it instead of guessing from the server's answer, so neither leg can pass on the other's state.
  agentAttached: [false, {option: true}],
  // The JVM arguments of the suite's sample, for specs that start samples of their own (the read-only spec), so the agent
  // suite starts them with the agent attached too.
  sampleJvmArguments: ['-Dspring.devtools.restart.enabled=false', {option: true}],
  // The agent attached beside BootUI's in the agent companion suites (agent-config.js), or null.
  agentCompanion: [/** @type {AgentCompanion | null} */ (null), {option: true}],

  openView: async ({page}, use) => {
    /**
     * @param {string} route hash route, e.g. 'overview' or 'config'
     * @param {string | RegExp} heading expected <h2> heading text
     */
    async function openView(route, heading) {
      await page.goto(`/bootui/#/${route}`)
      const matcher = typeof heading === 'string' ? new RegExp(heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) : heading
      await expect(page.locator('main h2').filter({hasText: matcher}).first()).toBeVisible()
      return page
    }

    await use(openView)
  }
})

/**
 * Accept the branded ConfirmDialog that replaced native window.confirm() for
 * destructive actions. Clicks the confirm action (danger or primary variant),
 * then waits for the dialog to close so callers can assert on the result.
 *
 * @param {import('@playwright/test').Page} page
 */
export async function acceptConfirm(page) {
  const dialog = page.locator('dialog.confirm-dialog')
  await dialog.waitFor({state: 'visible'})
  await dialog.locator('.confirm-actions button:not(.btn-outline-secondary)').click()
  await expect(dialog).toBeHidden()
}

/**
 * @typedef {{name: string, serviceName?: string, jacocoPort?: number}} AgentCompanion
 */

/**
 * Switches an opt-in BootUI agent sensor on or off at run time (docs/PLAN-v2.md M5-14), as the Java Agent panel does,
 * echoing the XSRF-TOKEN cookie a GET primes; then waits until the sensor's switch reads `installed` when switched on,
 * or `off` when switched off. Returns whether it was enabled before, so a spec can put it back.
 *
 * @param {import('@playwright/test').APIRequestContext} request
 * @param {string} sensor such as `environment`
 * @param {boolean} enabled
 */
export async function switchAgentSensor(request, sensor, enabled) {
  const toggleOf = async () =>
    ((await (await request.get('/bootui/api/java-agent')).json()).toggles ?? []).find(
      (candidate) => candidate.id === sensor
    )
  const before = await toggleOf()
  expect(before, `the ${sensor} switch is offered while the agent is armed`).toBeTruthy()
  if (before.enabled !== enabled) {
    const {cookies} = await request.storageState()
    const xsrf = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')
    const response = await request.post(`/bootui/api/java-agent/sensors/${sensor}`, {
      headers: {'Content-Type': 'application/json', ...(xsrf ? {'X-XSRF-TOKEN': xsrf.value} : {})},
      data: {enabled}
    })
    expect(response.status(), await response.text()).toBe(200)
  }
  await expect.poll(async () => (await toggleOf())?.state, {timeout: 30_000}).toBe(enabled ? 'installed' : 'off')
  return before.enabled
}

/**
 * Switches the `security-sinks` agent sensor off from the Side Effects panel and back on from the Java Agent panel at run
 * time (docs/PLAN-v2.md M5-14), in a sample whose `bootui.agent.sensors` claims it beside `files`: the MD5 the sample's
 * digest seed asks for is recorded only while the sensor is on. While it is off, a `files` read of the sinks seed issued
 * after the MD5 marks that the off window's records were drained before the count is compared. The switch is put back
 * on in any case, and `files` recording again after the shared transformer's reinstall.
 *
 * @param {import('@playwright/test').Page} page
 * @param {(route: string, heading: string | RegExp) => Promise<import('@playwright/test').Page>} openView
 */
export async function assertSecuritySinksSwitch(page, openView) {
  const rowsOf = async (sensor) =>
    (await (await page.request.get(`/bootui/api/side-effects/sensor?sensor=${sensor}&limit=500`)).json()).rows ?? []
  const sum = (rows) => rows.reduce((total, row) => total + (row.count ?? 0), 0)
  const md5 = async () =>
    sum(
      (await rowsOf('security-sinks')).filter(
        (row) => row.kind === 'weak digest' && row.target === 'MD5' && row.callSite?.includes('SecurityCheckSeeds')
      )
    )
  const reports = async () =>
    sum((await rowsOf('files')).filter((row) => row.attribution?.includes('/api/sinks/reports')))
  const stateOf = async (id) =>
    ((await (await page.request.get('/bootui/api/side-effects')).json()).sensors ?? []).find(
      (sensor) => sensor.id === id
    )?.state
  const toggleOf = async () =>
    ((await (await page.request.get('/bootui/api/java-agent')).json()).toggles ?? []).find(
      (candidate) => candidate.id === 'security-sinks'
    )

  expect(await toggleOf()).toMatchObject({configured: true, enabled: true, overridden: false, available: true})
  expect((await page.request.get('/api/sinks/checks/digest')).ok()).toBeTruthy()
  await expect.poll(md5, {timeout: 30_000}).toBeGreaterThan(0)
  try {
    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Security sinks/}).click()
    const control = page.getByTestId('agent-sensor-toggle-security-sinks')
    await expect(control).toContainText('bootui.agent.security-sinks.request-values=true')
    const toggle = control.getByRole('switch', {name: /security-sinks/})
    await expect(toggle).toBeChecked()
    await toggle.click()
    await expect(toggle).not.toBeChecked()
    await expect(control.getByTestId('agent-sensor-overridden')).toBeVisible()
    await expect.poll(async () => (await toggleOf())?.state, {timeout: 30_000}).toBe('off')
    await expect(page.locator('.side-effects-state-note')).toContainText('Switched off at run time', {
      timeout: 15_000
    })

    // Switching off reinstalls the transformer the files sensor shares: the marker needs it recording again.
    await expect.poll(() => stateOf('files'), {timeout: 30_000}).toBe('recording')
    const before = await md5()
    const marker = await reports()
    expect((await page.request.get('/api/sinks/checks/digest')).ok()).toBeTruthy()
    // Read again until one is recorded: a first read may land while the reinstall still pauses the files sensor.
    await expect
      .poll(
        async () => {
          expect((await page.request.get('/api/sinks/reports/switchmarker')).ok()).toBeTruthy()
          return reports()
        },
        {timeout: 30_000}
      )
      .toBeGreaterThan(marker)
    await page.waitForTimeout(1_000)
    expect(await md5(), 'no MD5 is recorded while the sensor is switched off').toBe(before)

    await openView('java-agent', 'Java Agent')
    const agentControl = page.getByTestId('agent-sensor-toggle-security-sinks')
    await expect(agentControl).toContainText('Configured: on')
    await expect(agentControl.getByTestId('agent-sensor-overridden')).toBeVisible()
    const agentToggle = agentControl.getByRole('switch', {name: /security-sinks/})
    await expect(agentToggle).not.toBeChecked()
    await agentToggle.click()
    await expect(agentToggle).toBeChecked()
    await expect(agentControl.getByTestId('agent-sensor-overridden')).toHaveCount(0)
    await expect.poll(async () => (await toggleOf())?.state, {timeout: 30_000}).toBe('installed')

    expect((await page.request.get('/api/sinks/checks/digest')).ok()).toBeTruthy()
    await expect.poll(md5, {timeout: 30_000}).toBeGreaterThan(before)
  } finally {
    if (!(await toggleOf())?.enabled) {
      await switchAgentSensor(page.request, 'security-sinks', true)
    }
    await expect.poll(() => stateOf('files'), {timeout: 30_000}).toBe('recording')
  }
}

export {expect}
