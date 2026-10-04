// @ts-check
import {expect, test} from '../tests/fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'

/**
 * The scripted Runtime Insights demo on Spring WebFlux (docs/PLAN-v2.md M3-6): blocking JDBC on the event loop and a
 * per-note loop are listed, a route's time breakdown opens with its evidence through Show all routes, and its request
 * opens in Live Activity.
 */
test.describe('Runtime Insights demo on Spring WebFlux', () => {
  test('shows each seeded observation and follows a breakdown to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers, body) => {
      const response = await page.request.fetch(path, {method, headers, data: body})
      return response.status()
    }, 'webflux')

    await openView('runtime-insights', 'Runtime Insights')
    for (const [title, subject] of [
      ['Blocking on event loops', '/api/insights/notes/on-event-loop'],
      ['Repeated SELECTs', '/api/insights/notes/one-by-one']
    ]) {
      await expect(page.getByRole('heading', {name: title, level: 2, exact: true})).toBeVisible({timeout: 15_000})
      await expect(page.locator('.insight-item', {hasText: subject}).first()).toBeVisible()
    }

    // A short route is not listed by default (M4-19): Show all routes reaches its breakdown.
    const toggle = page.locator('.insight-show-all')
    if ((await toggle.count()) > 0 && (await toggle.getAttribute('aria-pressed')) === 'false') {
      await toggle.click()
    }
    await page.locator('.insight-item', {hasText: 'GET /api/notes'}).first().click()
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
