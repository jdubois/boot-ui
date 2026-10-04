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
      for (const path of ['', '/route?route=GET%20%2Fapi%2Fhello', '/requests/0000000000000000', '/beans']) {
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

    // M5-4c: the seeded N+1 route's statements show under the service method that issued them.
    for (let i = 0; i < 4; i++) {
      expect((await page.request.get('/api/insights/orders')).ok()).toBeTruthy()
    }
    const ordersRoute = 'GET /api/insights/orders'
    await expect
      .poll(
        async () => {
          const report = await (
            await page.request.get(`/bootui/api/code-paths/route?route=${encodeURIComponent(ordersRoute)}`)
          ).json()
          return (report.nodes ?? []).some(
            (node) =>
              node.method?.includes('InsightOrderService#ordersLineByLine') &&
              node.calls?.some((call) => call.kind === 'SQL')
          )
        },
        {timeout: 30_000}
      )
      .toBe(true)
    await page.goto(`/bootui/#/code-paths?route=${encodeURIComponent(ordersRoute)}`)
    await expect(page.locator('#code-paths-tree-heading')).toHaveText(ordersRoute)
    await expect(page.locator('.code-paths-call').first()).toContainText(
      'SQL statements, issued while InsightOrderService.ordersLineByLine was open'
    )

    // Beans at runtime: the controller's observed calls into the service beside its declared dependency.
    await page.getByRole('tab', {name: 'Beans at runtime'}).click()
    await expect(page.getByRole('tab', {name: 'Beans at runtime'})).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tabpanel')).toHaveCount(1)
    const beans = page.locator('.code-paths-beans-table')
    await expect(beans).toBeVisible()
    const edge = beans
      .locator('tbody tr')
      .filter({hasText: 'insightSeedController'})
      .filter({hasText: 'insightOrderService'})
    await expect(edge).toHaveCount(1)
    await expect(edge.locator('td').nth(2)).toHaveText('Yes')
    await expect(page.locator('.code-paths-beans')).not.toContainText(/unused/i)
    await page.getByLabel('Only declared dependencies not called in this run').check()
    await expect(
      beans.locator('tbody tr').filter({hasText: 'insightSeedController'}).filter({hasText: 'insightOrderService'})
    ).toHaveCount(0)
  })
})
