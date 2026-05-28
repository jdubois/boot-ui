// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Beans view', () => {
  test('lists beans and supports filtering by name and classification', async ({openView}) => {
    const page = await openView('beans', 'Beans')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    // Name filter narrows the list.
    await page.getByPlaceholder(/Filter by name or type/).fill('productRepository')
    await expect(page.locator('table tbody')).toContainText('productRepository')
    await expect.poll(async () => rows.count()).toBeLessThan(10)

    // Classification filter restricts to BootUI internals.
    await page.getByPlaceholder(/Filter by name or type/).fill('')
    await page.locator('select.form-select').selectOption('BOOTUI')
    await expect(rows.first()).toBeVisible()
    const classifications = await page.locator('table tbody tr td:nth-child(4) .badge').allInnerTexts()
    expect(classifications.every((c) => c === 'BOOTUI')).toBeTruthy()
  })

  test('keeps large bean lists responsive while filters search the full set', async ({openView, page}) => {
    const beans = Array.from({length: 205}, (_, index) => ({
      name: `demoBean${index}`,
      type: `com.example.DemoBean${index}`,
      scope: 'singleton',
      classification: 'APPLICATION',
      dependencies: []
    }))

    await page.route('**/bootui/api/beans', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({total: beans.length, beans})
      })
    )

    await openView('beans', 'Beans')

    const rows = page.locator('table tbody tr')
    await expect(rows).toHaveCount(200)
    await expect(page.getByText('Showing 200 of 205 beans.')).toBeVisible()

    await page.getByPlaceholder(/Filter by name or type/).fill('demoBean204')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('demoBean204')
  })
})
