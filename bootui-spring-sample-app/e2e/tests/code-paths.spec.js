// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Code Paths view (docs/PLAN-v2.md §5.14). The default suites run the sample without the agent, so the panel is
 * unavailable with the Java Agent panel's reason and links there; the agent suite (playwright.agent.config.js) sets the
 * `agentAttached` fixture option and asserts the seeded slow route's tree names SlowPricingService.quote.
 */
test.describe('Code Paths view', () => {
  test('names the slow method of the seeded route, or says why it cannot', async ({openView, page, agentAttached}) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-paths')
    expect(panel).toBeTruthy()

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)
      for (const path of ['', '/route?route=GET%20%2Fapi%2Fhello', '/requests/0000000000000000']) {
        const response = await page.request.get(`/bootui/api/code-paths${path}`)
        expect(response.ok()).toBeTruthy()
        const body = await response.json()
        expect(body.available).toBe(false)
        expect(body.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)
      }

      await openView('code-paths', 'Code Paths')
      await expect(page.locator('.panel-availability-alert')).toContainText('code-paths sensor')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    for (let i = 0; i < 4; i++) {
      expect((await page.request.get(`/api/quotes/e2e-${i}`)).ok()).toBeTruthy()
    }
    // Each request's tree settles about two seconds after its last fragment.
    await expect
      .poll(
        async () => {
          const report = await (await page.request.get('/bootui/api/code-paths')).json()
          return report.routes?.find((route) => route.route === 'GET /api/quotes/{sku}')?.warmRequests ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(3)

    await page.goto('/bootui/#/code-paths?route=GET%20%2Fapi%2Fquotes%2F%7Bsku%7D')
    await expect(page.locator('main h2').filter({hasText: /^Code Paths/})).toBeVisible()
    await expect(page.locator('#code-paths-headline')).toHaveText(/\d+ routes? with a call tree/)
    await expect(page.locator('#code-paths-tree-heading')).toHaveText('GET /api/quotes/{sku}')
    const tree = page.locator('.code-paths-tree')
    await expect(tree).toContainText('QuoteController.quote')
    await expect(tree).toContainText('QuoteService.quote')
    await expect(tree).toContainText('SlowPricingService.quote')
    // Node medians are approximate, from log2 buckets, and say so.
    await expect(tree.locator('thead')).toContainText('Median (≈ ms)')
    await expect(tree.locator('.code-paths-median').filter({hasText: '≈'}).first()).toBeVisible()
    await expect(page.locator('.code-paths-routes thead')).toContainText('First request (ms)')

    await tree.getByRole('button', {name: 'SlowPricingService.quote'}).click()
    const detail = page.locator('.code-paths-method-detail')
    await expect(detail.locator('.code-paths-callers')).toContainText('QuoteService.quote')
    await expect(detail.locator('.code-paths-reach')).toContainText('GET /api/quotes/{sku}')
  })
})
