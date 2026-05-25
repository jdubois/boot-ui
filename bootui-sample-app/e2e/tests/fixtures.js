// @ts-check
import { test as base, expect } from '@playwright/test'

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
  openView: async ({ page }, use) => {
    /**
     * @param {string} route hash route, e.g. 'overview' or 'config'
     * @param {string | RegExp} heading expected <h2> heading text
     */
    async function openView(route, heading) {
      await page.goto(`/bootui/#/${route}`)
      const matcher = typeof heading === 'string'
        ? new RegExp(heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
        : heading
      await expect(page.getByRole('heading', { level: 2, name: matcher })).toBeVisible()
      return page
    }
    await use(openView)
  }
})

export { expect }
