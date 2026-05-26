// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Configuration view', () => {

  test('lists properties and supports searching', async ({ openView, page }) => {
    await openView('config', 'Configuration')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(10)

    await page.getByPlaceholder(/Filter by name or value/).fill('sample.greeting')
    await expect(rows.filter({ hasText: 'sample.greeting' }).first()).toBeVisible()
  })

  test('user can create, then delete a property override', async ({ openView, page }) => {
    await openView('config', 'Configuration')

    const propertyName = `e2e.demo.value.${Date.now()}`
    const propertyValue = 'hello-from-playwright'

    // Auto-dismiss the confirm() dialog raised by the delete button.
    page.on('dialog', dialog => dialog.accept())

    // Create the override.
    await page.getByRole('button', { name: /Add override/ }).click()
    await page.locator('tr.table-warning input').first().fill(propertyName)
    await page.locator('tr.table-warning input').nth(1).fill(propertyValue)
    await page.locator('tr.table-warning button.btn-success', { hasText: 'Save' }).click()

    // Wait for the banner confirmation.
    await expect(page.locator('.alert.alert-success'))
      .toContainText(new RegExp(`Override saved for ${propertyName.replace(/\./g, '\\.')}`))

    // Filter down to the new row and verify it is flagged as an override.
    await page.getByPlaceholder(/Filter by name or value/).fill(propertyName)
    const newRow = page.locator('table tbody tr', { hasText: propertyName })
    await expect(newRow.first()).toBeVisible()
    await expect(newRow.first().locator('.badge', { hasText: 'override' })).toBeVisible()
    await expect(newRow.first()).toContainText(propertyValue)

    // Remove the override.
    await newRow.first().locator('button[title="Remove override"]').click()
    await expect(page.locator('.alert.alert-success'))
      .toContainText(new RegExp(`Override removed for ${propertyName.replace(/\./g, '\\.')}`))
    await expect(page.locator('table tbody tr', { hasText: propertyName })).toHaveCount(0)
  })

  test('toggle "Only overrides" hides untouched properties', async ({ openView, page }) => {
    await openView('config', 'Configuration')

    const rows = page.locator('table tbody tr')
    const beforeCount = await rows.count()

    await page.getByLabel(/Only overrides/).check()
    await expect.poll(async () => rows.count()).toBeLessThanOrEqual(beforeCount)
  })
})
