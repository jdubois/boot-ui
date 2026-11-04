// @ts-check
import {expect, test} from './fixtures.js'
import {seedInsights} from '../scripts/insights-demo.mjs'
import {openInsightObservation} from '../../../bootui-spring-sample-app/e2e/scenarios/runtime-insights.js'

/**
 * The scripted Runtime Insights demo on Quarkus (docs/PLAN-v2.md M3-6, §5.5): with tracing off, the seeded traffic lists
 * every observation this stack records without widening the list; the secured route's time breakdown opens with its
 * evidence, and its exemplar request opens in Live Activity.
 */
test.describe('Runtime Insights demo', () => {
  test('shows each seeded observation and follows the secured route to its request', async ({openView, page}) => {
    await seedInsights(async (method, path, headers) => {
      const response = await page.request.fetch(path, {method, headers})
      return response.status()
    })

    // Quarkus records no transactions, so a read-write transaction that only reads cannot be told from any other: the
    // check says so, with its reason, rather than staying silent or reporting nothing found.
    const report = await (await page.request.get('/bootui/api/runtime-insights')).json()
    const readOnlyCandidates = report.checks.find((check) => check.kind === 'readonly-candidate-transaction')
    expect(readOnlyCandidates.status).toBe('NOT_APPLICABLE')
    expect(readOnlyCandidates.reason).toContain('Quarkus')

    await openView('runtime-insights', 'Runtime Insights')
    await expect(page.locator('.insight-detail')).toHaveCount(0)
    for (const [title, subject] of [
      ['Writes in GET requests', '/api/insights/orders/{id}'],
      ['Blocking on event loops', '/api/insights/orders/on-event-loop'],
      ['Anonymous success on a restricted route', '/api/insights/reports/{name}']
    ]) {
      await openInsightObservation(page, expect, title, subject)
    }

    // The archive's CDI observer is recorded with its request, bound at build time (docs/PLAN-v2.md M4-8).
    const activity = await (await page.request.get('/bootui/api/activity')).json()
    expect(activity.entries.filter((entry) => entry.type === 'APP_EVENT').map((entry) => entry.summary)).toContain(
      'InsightOrderEvents#audit'
    )

    await expect(page.locator('.insight-show-all')).toHaveCount(0)
    for (const [title, subject] of [
      ['Repeated SELECTs', '/api/insights/orders'],
      ['Paged without ORDER BY', '/api/insights/orders/page'],
      ['Unbatched write loops', '/api/insights/audit/one-by-one'],
      ['Anonymous writes', '/api/insights/debug/reset-totals']
    ]) {
      await openInsightObservation(page, expect, title, subject)
    }
    // Internal validation history is not a user-facing classification.
    await expect(page.locator('.insight-list')).not.toContainText(/validat/i)
    await openInsightObservation(page, expect, 'Route time breakdown', 'GET /api/secure/products')
    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await detail.locator('a[href*="activity"]').first().click()
    await expect(page).toHaveURL(/#\/activity\?request=/)
  })
})
