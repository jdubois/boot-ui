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

    await expect(page.getByRole('button', {name: 'Export JSON'})).toBeVisible()

    const comparison = page.locator('.insight-comparison')
    await expect(comparison.getByRole('heading', {name: 'Compared with the previous run'})).toBeVisible()
    await expect(comparison).toContainText(/Compared|Needs more traffic|Not comparable|No previous run/)

    const products = await page.request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()
    const impact = page.locator('.insight-impact')
    const symbol = impact.getByRole('combobox', {name: /Symbol to check/})
    await symbol.fill('ProductRepository')
    await impact.getByRole('button', {name: 'Check impact'}).click()
    await expect(impact.locator('.insight-impact-node')).toContainText('productRepository')
    await expect(impact.locator('[data-list="observed"]')).toContainText('GET /api/sample/products')

    // Typing suggests what the run's model holds, each with its kind; picking one checks exactly that node.
    await symbol.fill('productRepo')
    const suggestion = impact.getByRole('option', {name: /productRepository/})
    await expect(suggestion).toContainText('repository')
    await expect(symbol).toHaveAttribute('aria-expanded', 'true')
    await symbol.press('ArrowDown')
    await expect(symbol).toHaveAttribute('aria-activedescendant', 'insight-impact-option-0')
    await symbol.press('Enter')
    await expect(symbol).toHaveValue('productRepository')
    await expect(symbol).toHaveAttribute('aria-expanded', 'false')
    await expect(impact.getByRole('listbox')).toHaveCount(0)
    await expect(impact.locator('.insight-impact-node')).toContainText('productRepository')

    await symbol.fill('/api/sample/products')
    const route = impact.getByRole('option', {name: /^GET \/api\/sample\/products route$/})
    await expect(route).toBeVisible()
    await route.click()
    await expect(impact.locator('.insight-impact-node')).toContainText('route')
    await expect(impact.locator('.insight-impact-node')).toContainText('GET /api/sample/products')
    await expect(impact.locator('[data-list="observed"]')).toContainText('GET /api/sample/products')

    // A method is a symbol too (M5-7a): without the agent, a handler method is checked through the routes mapped to it.
    await symbol.fill('SampleController#products')
    await impact.getByRole('button', {name: 'Check impact'}).click()
    await expect(impact.locator('.insight-impact-node')).toContainText('method')
    await expect(impact.locator('.insight-impact-node')).toContainText('SampleController#products')
    await expect(impact.locator('[data-list="observed"]')).toContainText('GET /api/sample/products')

    await page.locator('.insight-search').fill('no-such-route-xyz')
    await expect(page.getByText('No observation matches this search.')).toBeVisible()
  })

  test('profiles resources only when asked, and splits the samples by route', async ({openView, page}) => {
    await openView('runtime-insights', 'Runtime Insights')

    const profile = page.locator('.insight-profile')
    await expect(profile.getByRole('heading', {name: 'Profile resources'})).toBeVisible()
    await expect(profile.getByRole('button', {name: /Profile (resources|again)/})).toBeEnabled()

    await profile.getByRole('button', {name: /Profile (resources|again)/}).click()
    await expect(profile.getByRole('progressbar')).toBeVisible()
    // Allocation samples join requests as CPU samples do, so enough requests always leave a route in the table.
    for (let i = 0; i < 40; i += 1) {
      const search = await page.request.get('/api/sample/product-search')
      expect(search.ok()).toBeTruthy()
    }
    await profile.getByRole('button', {name: 'Stop now'}).click()

    await expect(profile.locator('.insight-profile-summary')).toContainText('CPU samples', {timeout: 30_000})
    await expect(profile.locator('.insight-profile-table')).toContainText('/api/sample/product-search')

    // Starting the session was a BootUI action, so Live Activity explains it with a marker (docs/PLAN-v2.md M4-7).
    await expect
      .poll(async () => {
        const activity = await (await page.request.get('/bootui/api/activity')).json()
        return activity.entries
          .filter((entry) => entry.type === 'MARKER')
          .map((entry) => entry.detail)
          .join(' | ')
      })
      .toContain('runtime-insights: POST /runtime-insights/resource-profile')
  })
})
