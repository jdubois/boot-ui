// @ts-check
import {expect, test} from './fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'

/**
 * The scripted Runtime Insights demo on Spring MVC (docs/PLAN-v2.md M3-6, §5.5): with tracing off, the seeded traffic
 * shows one observation per seed, the secured route's time breakdown opens with its evidence, and its exemplar request
 * opens in Live Activity.
 */
test.describe('Runtime Insights demo', () => {
  test('shows each seeded observation and follows the secured route to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers, body) => {
      const response = await page.request.fetch(path, {method, headers, data: body})
      return response.status()
    }, 'mvc')

    await openView('runtime-insights', 'Runtime Insights')
    for (const [title, subject] of [
      ['Repeated SELECTs', '/api/insights/orders'],
      ['Writes in GET requests', '/api/insights/orders/{id}'],
      ['Connections held together', '/api/insights/orders/{id}/confirm'],
      ['Writes split across transactions', '/api/insights/orders/{id}/confirm'],
      ['Transactions open across remote calls', '/api/insights/orders/{id}/price-check'],
      ['Proxy bypass', '/api/insights/orders/{id}/recalculate'],
      ['Errors behind 2xx responses', '/api/insights/orders/{id}/import'],
      ['SQL after the handler returned', '/api/insights/orders/report'],
      ['Transactional listeners skipped', '/api/insights/orders/{id}/notify'],
      ['Writes after commit', '/api/insights/orders/{id}/archive'],
      ['Anonymous writes', '/api/insights/debug/reset-totals'],
      ['Anonymous success on a restricted route', '/api/insights/reports/{name}']
    ]) {
      await expect(page.getByRole('heading', {name: title, level: 2, exact: true})).toBeVisible({timeout: 15_000})
      await expect(page.locator('.insight-item', {hasText: subject}).first()).toBeVisible()
    }

    const secured = page.locator('.insight-item', {hasText: 'GET /api/secure/products'}).first()
    await secured.click()
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await expect(detail.locator('.insight-evidence')).toContainText('Authentication')

    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
