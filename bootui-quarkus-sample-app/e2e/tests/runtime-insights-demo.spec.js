// @ts-check
import {expect, test} from './fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'

/**
 * The scripted Runtime Insights demo on Quarkus (docs/PLAN-v2.md M3-6, §5.5): with tracing off, the seeded traffic shows
 * one observation per seed this stack records, the secured route's time breakdown opens with its evidence, and its
 * exemplar request opens in Live Activity.
 */
test.describe('Runtime Insights demo', () => {
  test('shows each seeded observation and follows the secured route to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers) => {
      const response = await page.request.fetch(path, {method, headers})
      return response.status()
    })

    await openView('runtime-insights', 'Runtime Insights')
    for (const [title, subject] of [
      ['Repeated SELECTs', '/api/insights/orders'],
      ['Writes in GET requests', '/api/insights/orders/{id}'],
      ['Blocking on event loops', '/api/insights/orders/on-event-loop'],
      ['Anonymous writes', '/api/insights/debug/reset-totals'],
      ['Anonymous success on a restricted route', '/api/insights/reports/{name}']
    ]) {
      await expect(page.getByRole('heading', {name: title, level: 2, exact: true})).toBeVisible({timeout: 15_000})
      await expect(page.locator('.insight-item', {hasText: subject}).first()).toBeVisible()
    }

    // The archive's CDI observer is recorded with its request, bound at build time (docs/PLAN-v2.md M4-8).
    const activity = await (await page.request.get('/bootui/api/activity')).json()
    expect(activity.entries.filter((entry) => entry.type === 'APP_EVENT').map((entry) => entry.summary)).toContain(
      'InsightOrderEvents#audit'
    )

    await page.locator('.insight-item', {hasText: 'GET /api/secure/products'}).first().click()
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
