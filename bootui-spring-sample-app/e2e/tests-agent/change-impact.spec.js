// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * Change impact by method with the BootUI agent attached (docs/PLAN-v2.md §5.7, §5.17, M5-7a): a method's observed
 * routes are those whose requests' own call trees ran it, read from the route trees, and the run comparison leads with
 * the code changes, unavailable with the reason in a first run, then the side effects outside the JVM (M5-7b).
 */
test.describe('Change impact by method, with the agent', () => {
  test('lists the route whose call trees ran the method, and the comparison says what it knows of code changes', async ({
    openView,
    page
  }) => {
    for (let i = 0; i < 3; i += 1) {
      expect((await page.request.get('/api/sample/products')).ok()).toBeTruthy()
    }
    // Trees settle about two seconds after their last fragment.
    await expect
      .poll(
        async () => {
          const response = await page.request.get(
            `/bootui/api/runtime-insights/impact?symbol=${encodeURIComponent('SampleController#products')}`
          )
          const impact = await response.json()
          return impact.observed.find((route) => route.route === 'GET /api/sample/products')?.executedRequests ?? 0
        },
        {timeout: 20_000}
      )
      .toBeGreaterThan(0)

    await openView('runtime-insights', 'Runtime Insights')
    await page.getByRole('tab', {name: /^Change impact/}).click()
    const impact = page.locator('.insight-impact')
    await impact.getByRole('combobox', {name: /Symbol to check/}).fill('SampleController#products')
    await impact.getByRole('button', {name: 'Check impact'}).click()
    await expect(impact.locator('.insight-impact-node')).toContainText('method')
    await expect(impact.locator('.insight-impact-node')).toContainText('SampleController#products')
    const observed = impact.locator('[data-list="observed"]')
    await expect(observed.getByRole('heading', {name: /Ran it in this run/})).toBeVisible()
    await expect(observed).toContainText('GET /api/sample/products')
    await expect(observed).toContainText(/ran it of \d+ requests?/)
    await expect(impact.locator('[data-list="not-observed"]')).toBeVisible()

    await page.getByRole('tab', {name: /^Changes/}).click()
    const comparison = page.locator('.insight-comparison')
    await expect(
      comparison.locator('[data-section="code-changes"], [data-testid="code-changes-unavailable"]')
    ).toBeVisible()
    // Side effects follow (M5-7b): compared per sensor, or unavailable with the reason, as in a first run.
    await expect(
      comparison.locator('[data-section="side-effects"], [data-testid="side-effects-unavailable"]')
    ).toBeVisible()
    const json = await (await page.request.get('/bootui/api/runtime-insights/comparison')).json()
    expect(typeof json.sideEffects?.available).toBe('boolean')
    if (json.sideEffects.available) {
      expect(json.sideEffects.sensors.map((sensor) => sensor.sensor)).toEqual([
        'network',
        'files',
        'processes',
        'environment'
      ])
    } else {
      expect(json.sideEffects.unavailableReason).toBeTruthy()
    }
  })

  test("opens Change impact on a changed method from the comparison's See its impact", async ({openView, page}) => {
    expect((await page.request.get('/api/sample/products')).ok()).toBeTruthy()
    // The suite runs without DevTools restarts, so no run has a previous one with code changes: the real comparison is
    // given a changed constructor, which change impact cannot check, and a changed handler, which it checks for real.
    const changed = (name, descriptor) => ({
      key: `io.github.jdubois.bootui.sample.catalog.SampleController#${name}${descriptor}`,
      className: 'io.github.jdubois.bootui.sample.catalog.SampleController',
      name,
      descriptor,
      change: 'CHANGED',
      status: 'EXECUTED',
      notTrackedReason: null,
      routes: ['GET /api/sample/products'],
      routesTotal: 1,
      routesNote: null
    })
    await page.route('**/api/runtime-insights/comparison*', async (route) => {
      const response = await route.fetch()
      const comparison = await response.json()
      comparison.codeChanges = {
        available: true,
        unavailableReason: null,
        counts: {changed: 2, added: 0, removed: 0, executed: 2, notExecuted: 0},
        methods: [
          changed('<init>', '(Lio/github/jdubois/bootui/sample/catalog/ProductCatalog;)V'),
          changed('products', '()Ljava/util/List;')
        ],
        methodsTotal: 2,
        limitations: []
      }
      await route.fulfill({response, json: comparison})
    })

    await openView('runtime-insights', 'Runtime Insights')
    await page.getByRole('tab', {name: /^Changes/}).click()
    const rows = page.locator('[data-section="code-changes"] .insight-comparison-row')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(0).locator('.insight-comparison-impact')).toHaveCount(0)
    await rows.nth(1).getByRole('button', {name: 'See its impact: SampleController#products'}).click()

    await expect(page.getByRole('tab', {name: /^Change impact/})).toHaveAttribute('aria-selected', 'true')
    const impact = page.locator('.insight-impact')
    await expect(impact.getByRole('combobox', {name: /Symbol to check/})).toHaveValue('SampleController#products')
    await expect(impact.locator('.insight-impact-node')).toContainText('method')
    await expect(impact.locator('.insight-impact-node')).toContainText('SampleController#products')
  })
})
