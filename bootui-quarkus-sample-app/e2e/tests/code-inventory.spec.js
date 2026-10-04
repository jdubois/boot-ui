// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Code Inventory view on Quarkus (docs/PLAN-v2.md §5.15). The sample runs without the BootUI agent, so the panel is
 * unavailable with the Java Agent panel's reason and links there, and every read answers the unavailable shape.
 */
test.describe('Code Inventory view (Quarkus)', () => {
  test('is unavailable without the agent and links to the Java Agent panel', async ({openView, page}) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-inventory')
    expect(panel.available).toBe(false)
    expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)

    for (const path of ['', '/changes', '/methods', '/dependencies']) {
      const response = await page.request.get(`/bootui/api/code-inventory${path}`)
      expect(response.ok()).toBeTruthy()
      const body = await response.json()
      expect(body.available).toBe(false)
      expect(body.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)
    }

    await openView('code-inventory', 'Code Inventory')
    await expect(page.locator('.panel-availability-alert')).toContainText('inventory sensor')
    await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
    await expect(page).toHaveURL(/#\/java-agent$/)
  })
})
