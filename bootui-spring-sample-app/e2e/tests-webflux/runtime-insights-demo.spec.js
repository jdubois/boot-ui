// @ts-check
import {expect, test} from '../tests/fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'

/**
 * The scripted Runtime Insights demo on Spring WebFlux (docs/PLAN-v2.md M3-6): blocking JDBC on the event loop is
 * listed; the per-note loop, whose kind had too few facts to validate (M4-20), and a route's time breakdown are reached
 * through Show all routes, the breakdown opens with its evidence, and its request opens in Live Activity.
 */
test.describe('Runtime Insights demo on Spring WebFlux', () => {
  test('shows each seeded observation and follows a breakdown to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers, body) => {
      const response = await page.request.fetch(path, {method, headers, data: body})
      return response.status()
    }, 'webflux')

    await openView('runtime-insights', 'Runtime Insights')
    await expect(page.getByRole('heading', {name: 'Blocking on event loops', level: 2, exact: true})).toBeVisible({
      timeout: 15_000
    })
    await expect(page.locator('.insight-item', {hasText: '/api/insights/notes/on-event-loop'}).first()).toBeVisible()

    // Repeated SELECTs and route time breakdowns are not listed by default since their external validation (M4-20):
    // Show all routes reaches them.
    const toggle = page.locator('.insight-show-all')
    if ((await toggle.count()) > 0 && (await toggle.getAttribute('aria-pressed')) === 'false') {
      await toggle.click()
    }
    await expect(page.getByRole('heading', {name: 'Repeated SELECTs', level: 2, exact: true})).toBeVisible()
    await expect(page.locator('.insight-item', {hasText: '/api/insights/notes/one-by-one'}).first()).toBeVisible()
    await page.locator('.insight-item', {hasText: 'GET /api/notes'}).first().click()
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
