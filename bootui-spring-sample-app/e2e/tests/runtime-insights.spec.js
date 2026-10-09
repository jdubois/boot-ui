// @ts-check
import {expect, test} from './fixtures.js'

for (const platform of ['spring-boot', 'spring-boot-reactive', 'quarkus']) {
  for (const state of ['available', 'detached', 'disabled']) {
    test(`observation deep dives: ${platform}, ${state}`, async ({page, request, agentAttached}) => {
      test.skip(state === 'available' && !agentAttached, 'Route trees need the agent-backed browser suite.')
      const route = 'GET /api/sample/product-search'
      for (let i = 0; i < 7; i += 1) {
        expect((await request.get('/api/sample/product-search')).ok()).toBeTruthy()
      }
      const report = await (await request.get('/bootui/api/runtime-insights')).json()
      const observation = report.observations.find(
        (item) => item.kind === 'route-time-breakdown' && item.subject === route
      )
      expect(observation).toBeTruthy()

      // Exercise the shared UI with each adapter's manifest discriminator without changing the running agent.
      await page.route('**/api/panels', async (intercept) => {
        const response = await intercept.fetch()
        const manifest = await response.json()
        manifest.platform = platform
        const panel = manifest.panels.find((item) => item.id === 'code-paths')
        panel.enabled = state !== 'disabled'
        panel.available = state !== 'detached'
        panel.unavailableReason =
          state === 'detached' ? "Requires the BootUI agent's code-paths sensor: no agent attached." : null
        await intercept.fulfill({response, json: manifest})
      })
      const recordings = []
      page.on('request', (request) => {
        if (request.method() === 'POST' && request.url().includes('/resource-profile')) recordings.push(request.url())
      })
      await page.goto(
        `/bootui/#/runtime-insights?q=${encodeURIComponent(route)}&insight=${encodeURIComponent(observation.id)}`
      )
      const section = page.locator('.insight-performance-deep-dives')
      await expect(section).toBeVisible()
      await expect(page.locator('.insight-item[aria-expanded="true"]')).toContainText(route)
      const action = section.getByRole('button', {name: 'Open the JFR profile tab'})
      await action.focus()
      await page.keyboard.press('Enter')
      const tab = page.getByRole('tab', {name: 'JFR profile'})
      await expect(tab).toHaveAttribute('aria-selected', 'true')
      await expect(tab).toBeFocused()
      expect(recordings).toEqual([])
      await page.getByRole('tab', {name: /^Findings/}).click()

      const link = section.getByRole('link', {name: `Open ${route} in Code Paths`})
      if (state === 'available') {
        await expect(link).toHaveAttribute('href', /#\/code-paths\?route=GET\+\/api\/sample\/product-search$/)
        await link.focus()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(/#\/code-paths\?route=GET\+\/api\/sample\/product-search$/)
        await expect(page.locator('.code-paths-route', {hasText: route})).toHaveAttribute('aria-expanded', 'true')
        await expect(page.locator('.code-paths-route-detail')).toBeVisible()
      } else {
        await expect(link).toHaveCount(0)
        if (state === 'detached') {
          await expect(section.locator('.insight-agent-tip')).toContainText('method-level timing')
          await expect(section.getByRole('link', {name: 'Set up the Java agent'})).toBeVisible()
        } else {
          await expect(section).toContainText('bootui.panels.code-paths.enabled=false')
          await expect(section.locator('.insight-agent-tip')).toHaveCount(0)
        }
      }
      expect(recordings).toEqual([])
    })
  }
}

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

    // Change impact is a tab of its own, search first.
    const products = await page.request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()
    await page.getByRole('tab', {name: /^Change impact/}).click()
    const impact = page.locator('.insight-impact')
    await expect(page.getByRole('tab', {name: /^Change impact/})).toHaveAttribute('aria-selected', 'true')
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

    await page.getByRole('tab', {name: /^Findings/}).click()
    await page.locator('.insight-search').fill('no-such-route-xyz')
    await expect(page.getByText('No observation matches this search.')).toBeVisible()

    // ?impact=<symbol> opens the Change impact tab already checked.
    await openView('runtime-insights?impact=ProductRepository', 'Runtime Insights')
    await expect(page.getByRole('tab', {name: /^Change impact/})).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.insight-impact .insight-impact-node')).toContainText('productRepository')
  })

  test('profiles resources only when asked, and splits the samples by route', async ({openView, page}) => {
    await openView('runtime-insights', 'Runtime Insights')
    await page.getByRole('tab', {name: /^JFR profile/}).click()

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
