// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * The Quarkus Cache panel (renamed from "Spring Cache", route and endpoint now `/cache`) lists the
 * Quarkus caches and clears one. The sample uses the same cache names as the Spring sample
 * (sample-products / sample-greetings) through io.quarkus.cache annotations. We seed them first so the
 * list has entries, though Quarkus registers the cache names at build time so they list even when empty.
 */
test.describe('Cache view (Quarkus)', () => {
  test.beforeEach(async ({page}) => {
    // Best-effort seed: populate the @CacheResult caches via the sample's own endpoints.
    await page.request.get('/api/sample/products').catch(() => {})
    await page.request.get('/api/sample/hello').catch(() => {})
  })

  test('lists the Quarkus caches and clears one', async ({openView, page}) => {
    await openView('cache', 'Cache')

    const cacheSection = page.locator('section', {hasText: 'Caches'}).first()
    await expect(cacheSection).toContainText('sample-products')
    await expect(cacheSection).toContainText('sample-greetings')

    const productsRow = cacheSection.locator('tbody tr', {hasText: 'sample-products'}).first()
    const clearButton = productsRow.getByRole('button', {name: 'Clear'})
    await expect(clearButton).toBeEnabled()

    await clearButton.click()
    await acceptConfirm(page)
    // The success text comes from the shared bootui-engine CacheService, so it is identical on Quarkus.
    await expect(page.locator('.alert-success')).toContainText('Cleared cache')
  })
})
