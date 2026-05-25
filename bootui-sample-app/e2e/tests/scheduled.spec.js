// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Scheduled tasks view', () => {

  test('renders the scheduled tasks view (table or empty-state)', async ({ openView, page }) => {
    await openView('scheduled', 'Scheduled Tasks')

    // The sample app does not declare @Scheduled methods, so the empty-state alert
    // is the expected outcome — but the view must always render successfully.
    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const tableVisible = await page.locator('table tbody tr').count()
    const emptyVisible = await page.locator('.alert', { hasText: /No scheduled tasks registered|No Spring Scheduling detected/ }).count()
    expect(tableVisible + emptyVisible).toBeGreaterThan(0)
  })
})
