// @ts-check
import {expect, test} from '@playwright/test'

/**
 * The Code Paths view on Spring WebFlux (docs/PLAN-v2.md §5.14): without the BootUI agent the panel is unavailable with
 * the Java Agent panel's reason, and every read answers the unavailable shape.
 */
test.describe('Code Paths view on Spring WebFlux', () => {
  test('is unavailable without the agent and links to the Java Agent panel', async ({page, request, baseURL}) => {
    const panels = await (await request.get(`${baseURL}/bootui/api/panels`)).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-paths')
    expect(panel.available).toBe(false)
    expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)
    for (const path of ['', '/route?route=GET%20%2F', '/requests/0000000000000000', '/beans']) {
      const body = await (await request.get(`${baseURL}/bootui/api/code-paths${path}`)).json()
      expect(body.available).toBe(false)
    }

    await page.goto('/bootui/#/code-paths')
    await expect(page.locator('main h2').filter({hasText: /^Code Paths/})).toBeVisible()
    await expect(page.locator('.panel-availability-alert')).toContainText('code-paths sensor')
    await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
    await expect(page).toHaveURL(/#\/java-agent$/)
  })
})
