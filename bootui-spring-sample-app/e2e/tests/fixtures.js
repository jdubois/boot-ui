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

export {expect}
