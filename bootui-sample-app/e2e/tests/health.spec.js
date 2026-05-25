// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Health view', () => {

  test('renders the root health node with a status badge', async ({ openView, page }) => {
    await openView('health', 'Health')

    const rootCard = page.locator('main .card').first()
    await expect(rootCard).toBeVisible()
    await expect(rootCard.locator('.badge')).toHaveText(/UP|DOWN|UNKNOWN|OUT_OF_SERVICE/)
  })
})
