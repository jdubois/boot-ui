// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP mappings view (Quarkus)', () => {
  test('lists the sample app endpoints and filters them', async ({openView, page}) => {
    await openView('mappings', 'HTTP mappings')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    // The sample JAX-RS resources and admin endpoint must be present (these were previously hidden by an
    // over-broad self-filter that also swallowed the sample app's own io.github.jdubois.bootui.sample
    // package; this asserts the fix rather than just "the page renders").
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
    await expect(rows.first()).toContainText('SampleResource#hello')
  })

  test('never leaks BootUI\u2019s own /bootui/api routes into the inventory', async ({openView, page}) => {
    await openView('mappings', 'HTTP mappings')

    await page.getByPlaceholder('Filter…').fill('/bootui')
    await expect(page.locator('table tbody tr')).toHaveCount(0)

    const response = await page.request.get('/bootui/api/mappings/flat', {
      headers: {'X-Forwarded-For': '127.0.0.1'}
    })
    const body = await response.json()
    const bootUiLeak = body.mappings.filter((m) => m.pattern.startsWith('/bootui'))
    expect(bootUiLeak).toEqual([])
  })
})
