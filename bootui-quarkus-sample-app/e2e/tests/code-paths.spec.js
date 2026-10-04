// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Code Paths view on Quarkus (docs/PLAN-v2.md §5.14). The sample runs without the BootUI agent, so the panel is
 * unavailable with the Java Agent panel's reason and links there, and every read answers the unavailable shape.
 */
test.describe('Code Paths view (Quarkus)', () => {
  test('is unavailable without the agent and links to the Java Agent panel', async ({openView, page}) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-paths')
    expect(panel.available).toBe(false)
    expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)

    for (const path of ['', '/route?route=GET%20%2Fapi%2Fhello', '/requests/0000000000000000', '/beans']) {
      const response = await page.request.get(`/bootui/api/code-paths${path}`)
      expect(response.ok()).toBeTruthy()
      const body = await response.json()
      expect(body.available).toBe(false)
      expect(body.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)
    }
    // The seeded slow route answers without the agent too.
    expect((await page.request.get('/api/quotes/e2e')).ok()).toBeTruthy()

    await openView('code-paths', 'Code Paths')
    await expect(page.locator('.panel-availability-alert')).toContainText('code-paths sensor')
    await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
    await expect(page).toHaveURL(/#\/java-agent$/)
  })
})
