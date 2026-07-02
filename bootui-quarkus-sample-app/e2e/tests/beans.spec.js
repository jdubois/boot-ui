// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Beans view (Quarkus)', () => {
  test('lists real Arc/CDI beans and supports filtering by name and classification', async ({openView, page}) => {
    const beansPage = await openView('beans', 'Beans')

    const rows = beansPage.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    // Name filter narrows the list down to the sample app's own Panache repository bean.
    await beansPage.getByPlaceholder(/Filter by name or type/).fill('productRepository')
    await expect.poll(async () => rows.count()).toBeLessThan(10)
    const repositoryRow = beansPage.locator('table tbody tr', {hasText: 'productRepository'})
    await expect(repositoryRow).toContainText('io.github.jdubois.bootui.sample.catalog.ProductRepository')
    await expect(repositoryRow).toContainText('ApplicationScoped')

    // Classification filter restricts to a single category (BootUI internals are hidden by default,
    // and Arc-managed app beans like the sample's catalog service classify as APPLICATION).
    await beansPage.getByPlaceholder(/Filter by name or type/).fill('')
    await beansPage.locator('select.form-select').selectOption('APPLICATION')
    const badges = beansPage.locator('table tbody tr td:nth-child(4) .badge')
    await expect
      .poll(async () => {
        const values = await badges.allInnerTexts()
        return values.length > 0 && values.every((c) => c === 'APPLICATION')
      })
      .toBeTruthy()
    await expect(beansPage.locator('table tbody')).toContainText('catalogService')

    // BootUI's own beans never leak into the inventory, on any classification.
    const response = await page.request.get('/bootui/api/beans?limit=400', {
      headers: {'X-Forwarded-For': '127.0.0.1'}
    })
    const body = await response.json()
    const bootUiLeak = body.beans.filter(
      (bean) => bean.type && (bean.type.includes('bootui.quarkus') || bean.type.includes('bootui.core'))
    )
    expect(bootUiLeak).toEqual([])
  })

  test('keeps large bean lists responsive while filters search the full set', async ({openView, page}) => {
    const beans = Array.from({length: 205}, (_, index) => ({
      name: `demoBean${index}`,
      type: `com.example.DemoBean${index}`,
      scope: 'ApplicationScoped',
      classification: 'APPLICATION',
      dependencies: []
    }))

    await page.route('**/bootui/api/beans?*', (route) => {
      const url = new URL(route.request().url())
      const query = (url.searchParams.get('q') || '').toLowerCase()
      const offset = Number(url.searchParams.get('offset') || 0)
      const limit = Number(url.searchParams.get('limit') || 200)
      const matched = query
        ? beans.filter((bean) => bean.name.toLowerCase().includes(query) || bean.type.toLowerCase().includes(query))
        : beans
      const pageBeans = matched.slice(offset, offset + limit)

      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          total: beans.length,
          beans: pageBeans,
          page: {
            total: beans.length,
            matched: matched.length,
            offset,
            limit,
            returned: pageBeans.length,
            hasMore: offset + pageBeans.length < matched.length
          }
        })
      })
    })

    await openView('beans', 'Beans')

    const rows = page.locator('table tbody tr')
    await expect(rows).toHaveCount(200)
    await expect(page.getByText(/Showing 200 of 205 beans/)).toBeVisible()
    await expect(page.getByRole('button', {name: /Load next 5/})).toBeVisible()

    await page.getByPlaceholder(/Filter by name or type/).fill('demoBean204')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('demoBean204')
  })
})
