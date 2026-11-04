// @ts-check
import {expect, test} from '../tests/fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'
import {openInsightObservation} from '../scenarios/runtime-insights.js'

/**
 * The scripted Runtime Insights demo on Spring WebFlux (docs/PLAN-v2.md M3-6): blocking JDBC on the event loop is
 * shown alongside per-note loops and route time breakdowns. A breakdown opens with its evidence, and its request
 * opens in Live Activity.
 */
test.describe('Runtime Insights demo on Spring WebFlux', () => {
  test('shows each seeded observation and follows a breakdown to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers, body) => {
      const response = await page.request.fetch(path, {method, headers, data: body})
      return response.status()
    }, 'webflux')

    await openView('runtime-insights', 'Runtime Insights')
    await expect(page.locator('.insight-detail')).toHaveCount(0)
    for (const [title, subject] of [
      ['Blocking on event loops', '/api/insights/notes/on-event-loop'],
      ['Repeated SELECTs', '/api/insights/notes/one-by-one'],
      ['Paged without ORDER BY', '/api/insights/notes/unsorted-page'],
      ['Unbatched write loops', '/api/insights/notes/write-one-by-one']
    ]) {
      await openInsightObservation(page, expect, title, subject)
    }
    await expect(page.locator('.insight-show-all')).toHaveCount(0)
    // Internal validation history is not a user-facing classification.
    await expect(page.locator('.insight-list')).not.toContainText(/validat/i)
    await openInsightObservation(page, expect, 'Route time breakdown', 'GET /api/notes')
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
