// @ts-check
import {expect, test} from './fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'
import {openInsightObservation} from '../scenarios/runtime-insights.js'

/**
 * The scripted Runtime Insights demo on Spring MVC (docs/PLAN-v2.md M3-6, §5.5): with tracing off, the seeded traffic
 * shows every seeded observation without widening the list; the secured route's time breakdown opens with its
 * evidence, and its exemplar request opens in Live Activity.
 */
test.describe('Runtime Insights demo', () => {
  test('shows each seeded observation and follows the secured route to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers, body) => {
      const response = await page.request.fetch(path, {method, headers, data: body})
      return response.status()
    }, 'mvc')

    const seeded = [
      ['safe-method-dml', 'Writes in GET requests', '/api/insights/orders/{id}'],
      [
        'transaction-across-remote-call',
        'Transactions open across remote calls',
        '/api/insights/orders/{id}/price-check'
      ],
      ['proxy-bypass', 'Proxy bypass', '/api/insights/orders/{id}/recalculate'],
      ['errors-behind-2xx', 'Errors behind 2xx responses', '/api/insights/orders/{id}/import'],
      [
        'anonymous-success-on-restricted-route',
        'Anonymous success on a restricted route',
        '/api/insights/reports/{name}'
      ],
      ['transactional-listener-skipped', 'Transactional listeners skipped', '/api/insights/orders/{id}/notify'],
      ['after-commit-writes', 'Writes after commit', '/api/insights/orders/{id}/archive'],
      ['orm-auto-flush', 'Hibernate auto-flushes', '/api/insights/tags/auto-flush'],
      ['large-persistence-context', 'Large persistence contexts', '/api/insights/tags/export']
    ]
    // The journal records the seeded traffic asynchronously, so a freshly started server may answer before it has
    // caught up: wait, bounded, until the report holds every expected finding rather than reading the panel once.
    await expect
      .poll(
        async () => {
          const report = await (await page.request.get('/bootui/api/runtime-insights')).json()
          const observations = report.observations ?? []
          return seeded
            .filter(
              ([kind, , subject]) =>
                !observations.some(
                  (observation) => observation.kind === kind && JSON.stringify(observation).includes(subject)
                )
            )
            .map(([kind, , subject]) => `${kind} ${subject}`)
        },
        {timeout: 60_000, intervals: [500, 1_000, 2_000]}
      )
      .toEqual([])

    await openView('runtime-insights', 'Runtime Insights')
    await expect(page.locator('.insight-detail')).toHaveCount(0)
    for (const [, title, subject] of seeded) {
      await openInsightObservation(page, expect, title, subject)
    }

    // Internal validation history is not a user-facing classification.
    await expect(page.locator('.insight-list')).not.toContainText(/validat/i)

    const additional = [
      ['Repeated SELECTs', '/api/insights/orders'],
      ['Connections held together', '/api/insights/orders/{id}/confirm'],
      ['Connections re-acquired in a session', '/api/insights/tags/reacquire'],
      ['Writes split across transactions', '/api/insights/orders/{id}/confirm'],
      ['Unbatched write loops', '/api/insights/audit/one-by-one'],
      ['Read-write transactions that only read', 'InsightTagService.summary'],
      ['Paged without ORDER BY', 'GET /api/insights/tags/unsorted-page'],
      ['Exception hotspots', 'GET /api/sample/boom'],
      ['Anonymous writes', '/api/insights/debug/reset-totals'],
      ['SQL after the handler returned', '/api/insights/orders/report'],
      ['Slow route outliers vs. baseline', '/api/insights/orders/{id}/invoice']
    ]
    await expect(page.locator('.insight-show-all')).toHaveCount(0)
    for (const [title, subject] of additional) {
      await openInsightObservation(page, expect, title, subject)
      await expect(page.locator('.insight-detail')).not.toContainText('Not listed by default')
      await expect(page.locator('.insight-detail')).not.toContainText(/validat/i)
    }

    await openInsightObservation(page, expect, 'Route time breakdown', 'GET /api/secure/products')
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await expect(detail.locator('.insight-evidence')).toContainText('Authentication')

    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
