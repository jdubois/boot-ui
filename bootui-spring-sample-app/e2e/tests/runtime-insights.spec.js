// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Runtime Insights (docs/PLAN-v2.md §5.5): the runtime journal's retained requests projected into observations.
 * Seven calls to one route give it the five warm requests `route-time-breakdown` needs, so the panel always has one
 * observation to open, whatever ran before.
 */
test.describe('Runtime Insights view', () => {
  test('lists a route time breakdown with its window, coverage, checks, and evidence', async ({openView, page}) => {
    for (let i = 0; i < 7; i += 1) {
      const search = await page.request.get('/api/sample/product-search')
      expect(search.ok()).toBeTruthy()
    }

    await openView('runtime-insights', 'Runtime Insights')

    await expect(page.getByText('What this run did that no single panel shows.')).toBeVisible()
    await expect(page.locator('.insight-window')).toContainText('This run')
    await expect(page.locator('.insight-coverage-legend')).toContainText('request id')

    const breakdowns = page.getByRole('heading', {name: 'Route time breakdown', level: 2})
    await expect(breakdowns).toBeVisible({timeout: 15_000})
    const item = page.locator('.insight-item', {hasText: '/api/sample/product-search'}).first()
    await item.click()
    await expect(item).toHaveAttribute('aria-current', 'true')

    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await expect(detail.getByRole('heading', {name: 'What to check'})).toBeVisible()
    await expect(detail.locator('.insight-evidence')).toContainText('Phase')

    await page.locator('.insight-search').fill('no-such-route-xyz')
    await expect(page.getByText('No observation matches this search.')).toBeVisible()
  })
})
