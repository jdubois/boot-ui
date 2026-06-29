// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('Cache view', () => {
  test('lists caches, cache annotations, and clears a cache', async ({openView, page}) => {
    await openView('cache', 'Cache')

    const cacheSection = page.locator('section', {hasText: 'Caches'}).first()
    await expect(cacheSection).toContainText('sample-products')
    await expect(cacheSection).toContainText('sample-greetings')

    const productsRow = cacheSection.locator('tbody tr', {hasText: 'sample-products'}).first()
    // The cache provider depends on how the sample app was started: an in-memory ConcurrentHashMap in
    // the default Docker-free (`dev`) profile, or Redis with the full Docker (`docker`) profile.
    await expect(productsRow).toContainText(/Redis|ConcurrentHashMap/)
    await expect(productsRow.getByRole('button', {name: 'Clear'})).toBeEnabled()

    const operationsSection = page.locator('section', {hasText: 'Annotation operations'}).first()
    await expect(operationsSection).toContainText('@Cacheable')
    await expect(operationsSection).toContainText('@CacheEvict')
    await expect(operationsSection).toContainText('sample-products')

    await productsRow.getByRole('button', {name: 'Clear'}).click()
    await acceptConfirm(page)
    await expect(page.locator('.alert-success')).toContainText('Cleared cache')
  })
})
