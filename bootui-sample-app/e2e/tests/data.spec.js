// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Data (Spring Data repositories) view', () => {

  test('lists the ProductRepository and shows its searchByName query', async ({ openView, page }) => {
    await openView('data', 'Spring Data repositories')

    const productRepoEntry = page.locator('.list-group-item-action', { hasText: 'ProductRepository' })
    await expect(productRepoEntry).toBeVisible()
    await expect(productRepoEntry).toContainText('Product')
    await expect(productRepoEntry.locator('.badge', { hasText: 'JPA' })).toBeVisible()

    // Filtering by entity name still keeps the repo visible.
    await page.getByPlaceholder(/Filter by interface, entity, or bean name/).fill('Product')
    await expect(productRepoEntry).toBeVisible()

    // Open the detail panel.
    await productRepoEntry.click()
    const detailCard = page.locator('main .col-md-7 .card')
    await expect(detailCard).toContainText('io.github.bootui.sample.ProductRepository')
    await expect(detailCard).toContainText('searchByName')
    await expect(detailCard).toContainText('select p from Product p')
    await expect(detailCard.locator('.badge', { hasText: 'ANNOTATED' }).first()).toBeVisible()
  })
})
