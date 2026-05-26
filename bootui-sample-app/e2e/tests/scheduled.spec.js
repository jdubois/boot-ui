// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Scheduled tasks view', () => {

  test('renders the sample echo scheduled task', async ({ openView, page }) => {
    await openView('scheduled', 'Scheduled Tasks')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    await expect(page.locator('table tbody tr', { hasText: 'FIXED_RATE' })).toBeVisible()
    await expect(page.locator('table tbody tr', { hasText: '30 s' })).toBeVisible()
  })
})
