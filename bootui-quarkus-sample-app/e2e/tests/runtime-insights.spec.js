// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Runtime Insights (docs/PLAN-v2.md §5.5): the runtime journal's retained requests projected into observations.
 * Seven calls to one route give it the five warm requests `route-time-breakdown` needs, so the panel always has one
 * observation to open, whatever ran before; a short route is reached with **Show all routes** (M4-19).
 */
test.describe('Runtime Insights view', () => {
  test('lists a route time breakdown with its window, coverage, checks, and evidence', async ({
    openView,
    page,
    agentAttached
  }) => {
    for (let i = 0; i < 7; i += 1) {
      const search = await page.request.get('/api/sample/product-search')
      expect(search.ok()).toBeTruthy()
    }

    // ?all=1 is Show all routes (M4-19): a short route's breakdown is reachable but not listed by default.
    await openView('runtime-insights?all=1', 'Runtime Insights')

    await expect(page.getByText('What this run did that no single panel shows.')).toBeVisible()
    await expect(page.locator('#insight-verdict-title')).toContainText('across')

    await expect(page.locator('.insight-show-all')).toHaveAttribute('aria-pressed', 'true')
    const item = page
      .locator('.insight-item', {hasText: 'Route time breakdown'})
      .filter({hasText: '/api/sample/product-search'})
      .first()
    await expect(item).toBeVisible({timeout: 15_000})
    await item.click()
    await expect(item).toHaveAttribute('aria-expanded', 'true')

    const detail = page.locator('.insight-detail')
    await expect(detail.locator('#insight-sentence')).toContainText('warm median')
    await expect(detail.getByRole('heading', {name: 'What to check'})).toBeVisible()
    await expect(detail.locator('.insight-evidence')).toContainText('Phase')

    const deepDives = detail.locator('.insight-performance-deep-dives')
    await expect(deepDives.getByRole('heading', {name: 'Performance deep dives'})).toBeVisible()
    const codePathsLink = deepDives.getByRole('link', {name: /Open GET .* in Code Paths/})
    if (agentAttached) {
      await expect(codePathsLink).toHaveAttribute('href', /#\/code-paths\?route=GET\+\/api\/sample\/product-search$/)
    } else {
      await expect(codePathsLink).toHaveCount(0)
      await expect(deepDives.locator('.insight-agent-tip')).toContainText('method-level timing')
      await expect(deepDives.getByRole('link', {name: 'Set up the Java agent'})).toHaveAttribute(
        'href',
        /#\/java-agent$/
      )
    }
    await deepDives.getByRole('button', {name: 'Open the JFR profile tab'}).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('tab', {name: 'JFR profile'})).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tab', {name: 'JFR profile'})).toBeFocused()
    await expect(page.locator('.insight-profile-running')).toHaveCount(0)
    await expect(page.locator('.insight-profile').getByRole('button', {name: /Profile resources/})).toBeVisible()
    await page.getByRole('tab', {name: /^Findings/}).click()
    if (agentAttached) {
      await codePathsLink.click()
      await expect(page).toHaveURL(/#\/code-paths\?route=GET\+\/api\/sample\/product-search$/)
      await expect(page.locator('.code-paths-route', {hasText: 'GET /api/sample/product-search'})).toHaveAttribute(
        'aria-expanded',
        'true'
      )
      await expect(page.locator('.code-paths-route-detail')).toBeVisible()
      await page.goBack()
    }

    await expect(page.getByRole('button', {name: 'Export JSON'})).toBeVisible()

    await page.getByRole('tab', {name: /^Coverage & limits/}).click()
    await expect(page.locator('.insight-window')).toContainText('This run')
    await expect(page.locator('.insight-coverage-legend')).toContainText('request id')

    await page.getByRole('tab', {name: /^Changes/}).click()
    const comparison = page.locator('.insight-comparison')
    await expect(comparison.getByRole('heading', {name: 'Compared with the previous run'})).toBeVisible()
    await expect(comparison).toContainText(/Compared|Needs more traffic|Not comparable|No previous run/)

    await page.getByRole('tab', {name: /^Change impact/}).click()
    const impact = page.locator('.insight-impact')
    const symbol = impact.getByRole('combobox', {name: /Symbol to check/})
    await symbol.fill('noSuchSymbolAnywhere')
    await impact.getByRole('button', {name: 'Check impact'}).click()
    await expect(impact.locator('.insight-impact-reason')).toBeVisible()

    // Typing suggests what the run's model holds, each with its kind; picking one checks exactly that node.
    await symbol.fill('/api/sample/product-search')
    const route = impact.getByRole('option', {name: /^GET \/api\/sample\/product-search route$/})
    await expect(route).toBeVisible()
    await route.click()
    await expect(symbol).toHaveValue('GET /api/sample/product-search')
    await expect(impact.locator('.insight-impact-node')).toContainText('GET /api/sample/product-search')
    await expect(impact.locator('[data-list="observed"]')).toContainText('GET /api/sample/product-search')

    await page.getByRole('tab', {name: /^Findings/}).click()
    await page.locator('.insight-search').fill('no-such-route-xyz')
    await expect(page.getByText('No observation matches this search.')).toBeVisible()
  })

  test('profiles resources only when asked, and splits the samples by route', async ({openView, page}) => {
    await openView('runtime-insights', 'Runtime Insights')
    await page.getByRole('tab', {name: /^JFR profile/}).click()

    const profile = page.locator('.insight-profile')
    const start = profile.getByRole('button', {name: /Profile (resources|again)/})
    await expect(start).toBeEnabled()

    await start.click()
    await expect(profile.getByRole('progressbar')).toBeVisible()
    // Allocation samples join requests as CPU samples do, so enough requests always leave a route in the table.
    for (let i = 0; i < 40; i += 1) {
      const search = await page.request.get('/api/sample/product-search')
      expect(search.ok()).toBeTruthy()
    }
    await profile.getByRole('button', {name: 'Stop now'}).click()

    await expect(profile.locator('.insight-profile-summary')).toContainText('CPU samples', {timeout: 30_000})
    await expect(profile.locator('.insight-profile-table')).toContainText('/api/sample/product-search')
  })
})
