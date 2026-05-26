// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Health view', () => {

  test('renders friendly health summary and tree', async ({ openView, page }) => {
    await openView('health', 'Health')

    await expect(page.getByText('Overall status')).toBeVisible()
    await expect(page.getByText('Component tree')).toBeVisible()
    const rootCard = page.locator('main .card').first()
    await expect(rootCard).toBeVisible()
    await expect(rootCard.locator('.badge')).toHaveText(/UP|DOWN|UNKNOWN|OUT_OF_SERVICE/)
    await expect(page.locator('main pre')).toHaveCount(0)
  })
})
