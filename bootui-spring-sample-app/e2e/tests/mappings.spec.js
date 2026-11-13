// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP mappings view', () => {
  test('separates loading, successful, and filtered-empty states', async ({openView, page}) => {
    let releaseInitial
    const initialReady = new Promise((resolve) => {
      releaseInitial = resolve
    })

    await page.route('**/bootui/api/mappings/flat*', async (route) => {
      const url = new URL(route.request().url())
      if (url.searchParams.get('q') === 'does-not-exist') {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({mappings: [], page: {matched: 0, total: 2}})
        })
        return
      }

      await initialReady
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          mappings: [
            {method: 'GET', pattern: '/api/hello', handler: 'SampleController#hello'},
            {method: 'POST', pattern: '/api/sample/products', handler: 'SampleController#createProduct'}
          ],
          page: {matched: 2, total: 2}
        })
      })
    })

    await openView('mappings', 'HTTP mappings')

    await expect(page.getByLabel('Loading HTTP mappings…')).toBeVisible()
    await expect(page.locator('table')).toHaveCount(0)
    await expect(page.getByText('0 of 0')).toHaveCount(0)
    await expect(page.getByText(/No HTTP mappings|No mappings match/)).toHaveCount(0)

    releaseInitial()
    await expect(page.locator('table tbody tr')).toHaveCount(2)

    await page.getByPlaceholder('Filter…').fill('does-not-exist')
    await expect(page.getByText('No mappings match does-not-exist.')).toBeVisible()
    await expect(page.locator('table')).toHaveCount(0)

    await page.getByRole('button', {name: 'Clear filter'}).click()
    await expect(page.locator('table tbody tr')).toHaveCount(2)
  })

  test('offers keyboard-accessible retry after an initial error', async ({openView, page}) => {
    let requests = 0
    await page.route('**/bootui/api/mappings/flat*', async (route) => {
      requests += 1
      if (requests === 1) {
        await route.fulfill({status: 503, contentType: 'application/json', body: '{}'})
        return
      }
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          mappings: [{method: 'GET', pattern: '/api/recovered', handler: 'SampleController#recovered'}],
          page: {matched: 1, total: 1}
        })
      })
    })

    await openView('mappings', 'HTTP mappings')

    const retry = page.getByRole('button', {name: 'Retry'})
    await expect(retry).toBeVisible()
    await retry.focus()
    await expect(retry).toBeFocused()
    await retry.press('Enter')

    await expect(page.locator('table tbody')).toContainText('/api/recovered')
    await expect(retry).toHaveCount(0)
  })

  test('lists the sample app endpoints and filters them', async ({openView, page}) => {
    await openView('mappings', 'HTTP mappings')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    // The sample API and admin endpoints must be present.
    await expect(page.locator('table tbody')).toContainText('/api/hello')
    await expect(page.locator('table tbody')).toContainText('/api/secure')
    await expect(page.locator('table tbody')).toContainText('/api/sample/hello')
    await expect(page.locator('table tbody')).toContainText('/api/sample/products')
    await expect(page.locator('table tbody')).toContainText('/admin')

    // Filter to a single endpoint.
    await page.getByPlaceholder('Filter…').fill('/api/sample/hello')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('GET')
    await expect(rows.first()).toContainText('/api/sample/hello')

    const probeLink = rows.first().locator('.mappings-probe-link')
    await expect(probeLink).toBeVisible()
    await probeLink.click()
    await expect(page).toHaveURL(/#\/http-probe\?/)
    await expect(page.getByLabel('Method')).toHaveValue('GET')
    await expect(page.getByLabel('Path', {exact: true})).toHaveValue('/api/sample/hello')
    await expect(page.locator('.card', {hasText: /^Response/})).toHaveCount(0)
  })
})
