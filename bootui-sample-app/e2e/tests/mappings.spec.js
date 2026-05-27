// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP mappings view', () => {
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
  })
})
