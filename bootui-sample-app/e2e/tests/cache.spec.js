// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Cache view', () => {

  test('lists caches, cache annotations, and clears a cache', async ({ openView, page }) => {
    page.on('dialog', dialog => dialog.accept())

    await openView('cache', 'Spring Cache')

    const cacheSection = page.locator('section', { hasText: 'Caches' }).first()
    await expect(cacheSection).toContainText('sample-products')
    await expect(cacheSection).toContainText('sample-greetings')

    const productsRow = cacheSection.locator('tbody tr', { hasText: 'sample-products' }).first()
    await expect(productsRow).toContainText(/Redis/)
    await expect(productsRow.getByRole('button', { name: 'Clear' })).toBeEnabled()

    const operationsSection = page.locator('section', { hasText: 'Annotation operations' }).first()
    await expect(operationsSection).toContainText('@Cacheable')
    await expect(operationsSection).toContainText('@CacheEvict')
    await expect(operationsSection).toContainText('sample-products')

    await productsRow.getByRole('button', { name: 'Clear' }).click()
    await expect(page.locator('.alert-success')).toContainText('Cleared cache')
  })
})
