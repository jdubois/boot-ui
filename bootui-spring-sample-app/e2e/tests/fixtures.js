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

export {expect}
