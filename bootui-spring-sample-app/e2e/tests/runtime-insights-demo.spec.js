// @ts-check
import {expect, test} from './fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'

/**
 * The scripted Runtime Insights demo on Spring MVC (docs/PLAN-v2.md M3-6, §5.5): with tracing off, the seeded traffic
 * lists each seed's observation whose kind is listed by default since its external validation (M4-20), marking those
 * not externally validated; **Show all routes** reaches the others with their reason; the secured route's time
 * breakdown opens with its evidence, and its exemplar request opens in Live Activity.
 */
test.describe('Runtime Insights demo', () => {
  test('shows each seeded observation and follows the secured route to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers, body) => {
      const response = await page.request.fetch(path, {method, headers, data: body})
      return response.status()
    }, 'mvc')

    await openView('runtime-insights', 'Runtime Insights')
    for (const [title, subject] of [
      ['Writes in GET requests', '/api/insights/orders/{id}'],
      ['Transactions open across remote calls', '/api/insights/orders/{id}/price-check'],
      ['Proxy bypass', '/api/insights/orders/{id}/recalculate'],
      ['Errors behind 2xx responses', '/api/insights/orders/{id}/import'],
      ['Anonymous success on a restricted route', '/api/insights/reports/{name}'],
      ['Transactional listeners skipped', '/api/insights/orders/{id}/notify'],
      ['Writes after commit', '/api/insights/orders/{id}/archive'],
      ['Hibernate auto-flushes', '/api/insights/tags/auto-flush']
    ]) {
      await expect(page.getByRole('heading', {name: title, level: 2, exact: true})).toBeVisible({timeout: 15_000})
      await expect(page.locator('.insight-item', {hasText: subject}).first()).toBeVisible()
    }

    const group = (title) =>
      page
        .locator('nav[aria-label="Observations"] > div')
        .filter({has: page.getByRole('heading', {name: title, level: 2, exact: true})})
    // A kind that stayed silent on the validation applications is listed, marked as not externally validated; one
    // that passed is not marked.
    await expect(group('Writes in GET requests').locator('.insight-validation')).toHaveText('Not externally validated')
    await expect(group('Errors behind 2xx responses').locator('.insight-validation')).toHaveCount(0)

    // Left out of the default list: the kinds that did not pass their external validation, or had too few facts to
    // judge (M4-20). Show all routes lists them, each in its own group, marked and explained.
    const leftOut = [
      ['Repeated SELECTs', '/api/insights/orders', 'too few facts'],
      ['Connections held together', '/api/insights/orders/{id}/confirm', 'did not pass its external validation'],
      ['Writes split across transactions', '/api/insights/orders/{id}/confirm', 'too few facts'],
      ['Exception hotspots', 'GET /api/sample/boom', 'Exceptions panel'],
      ['Anonymous writes', '/api/insights/debug/reset-totals', 'too few facts'],
      ['SQL after the handler returned', '/api/insights/orders/report', 'too few facts']
    ]
    for (const [title] of leftOut) {
      await expect(group(title)).toHaveCount(0)
    }
    await expect(page.locator('.insight-unlisted')).toContainText('not listed by default')
    await page.locator('.insight-show-all').click()
    for (const [title, subject, reason] of leftOut) {
      const item = group(title).locator('.insight-item', {hasText: subject}).first()
      await expect(item).toContainText('Not listed by default')
      await item.click()
      await expect(page.locator('.insight-unlisted-reason')).toContainText(reason)
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
