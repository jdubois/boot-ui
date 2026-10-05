// @ts-check
import {expect, test} from './fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'

/**
 * The scripted Runtime Insights demo on Spring MVC (docs/PLAN-v2.md M3-6, §5.5): with tracing off, the seeded traffic
 * lists each seed's observation by default, except those the default list leaves out (M4-19), which **Show all routes**
 * reaches with their reason; the secured route's time breakdown opens with its evidence, and its exemplar request opens
 * in Live Activity.
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
      ['Exception hotspots', 'GET /api/sample/boom'],
      ['Exception hotspots', 'Behind 4xx responses'],
      ['Anonymous writes', '/api/insights/debug/reset-totals'],
      ['Anonymous success on a restricted route', '/api/insights/reports/{name}'],
      // D29's kinds are listed by default since their counterexample fixtures pass across observations (M4-18e).
      ['Transactional listeners skipped', '/api/insights/orders/{id}/notify'],
      ['Writes after commit', '/api/insights/orders/{id}/archive'],
      ['Hibernate auto-flushes', '/api/insights/tags/auto-flush']
    ]) {
      await expect(page.getByRole('heading', {name: title, level: 2, exact: true})).toBeVisible({timeout: 15_000})
      await expect(page.locator('.insight-item', {hasText: subject}).first()).toBeVisible()
    }

    // Left out of the default list: a statement Repeated SELECTs already reports. Show all routes lists it, in its own
    // group, marked and explained.
    const group = (title) =>
      page
        .locator('nav[aria-label="Observations"] > div')
        .filter({has: page.getByRole('heading', {name: title, level: 2, exact: true})})
    const leftOut = [['SQL after the handler returned', '/api/insights/orders/report']]
    for (const [title] of leftOut) {
      await expect(group(title)).toHaveCount(0)
    }
    await expect(page.locator('.insight-unlisted')).toContainText('not listed by default')
    await page.locator('.insight-show-all').click()
    for (const [title, subject] of leftOut) {
      const item = group(title).locator('.insight-item', {hasText: subject}).first()
      await expect(item).toContainText('Not listed by default')
    }
    await group('SQL after the handler returned').locator('.insight-item').first().click()
    await expect(page.locator('.insight-unlisted-reason')).toContainText('Repeated SELECTs already reports')
    await page.locator('.insight-show-all').click()

    const secured = page.locator('.insight-item', {hasText: 'GET /api/secure/products'}).first()
    await secured.click()
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await expect(detail.locator('.insight-evidence')).toContainText('Authentication')

    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
