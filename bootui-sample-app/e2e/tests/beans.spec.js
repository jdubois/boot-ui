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
    expect(classifications.every(c => c === 'BOOTUI')).toBeTruthy()
  })
})
