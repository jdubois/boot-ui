// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Correlation-ID filtering (Quarkus).
 *
 * Correlation-identifier capture lives in the framework-neutral engine and reads headers the adapter had
 * already captured, so Quarkus behaves exactly like Spring here: the same bounded header set, the same
 * masking under the exposure policy, and the same opaque lookup identity derived in the browser. This spec
 * asserts that parity on the Quarkus runtime rather than assuming it.
 */
test.describe('Correlation-ID filtering (Quarkus)', () => {
  const correlationValue = 'e2e-correlation-quarkus-42'
  // Each test needs its own identifier: the sample application is not restarted between tests, so a
  // shared value would legitimately match requests an earlier test made and defeat the negative assertions.
  const streamValue = 'e2e-correlation-quarkus-stream-77'

  test('captures masked correlation identifiers and links them into Live Activity', async ({openView, page}) => {
    const response = await page.request.get('/api/sample/product-search', {
      headers: {'X-Correlation-ID': correlationValue}
    })
    expect(response.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')
    await expect(page.locator('table')).toContainText('/api/sample/product-search', {timeout: 15_000})

    const row = page.locator('tbody tr', {hasText: '/api/sample/product-search'}).first()
    await row.locator('.http-exchanges-detail-toggle').click()

    const detail = page.locator('.http-exchanges-detail').first()
    await expect(detail).toContainText('Correlation identifiers')
    await expect(detail).toContainText('x-correlation-id')
    await expect(detail).not.toContainText(correlationValue)

    const link = detail.getByRole('link', {name: /Live Activity/}).first()
    const href = await link.getAttribute('href')
    expect(href).toContain('correlationLookupId=')
    expect(href).not.toContain(correlationValue)
  })

  test('narrows the live stream to one distributed request, in the browser', async ({openView, page}) => {
    const filtered = await page.request.get('/api/sample/product-search', {
      headers: {'X-Correlation-ID': streamValue}
    })
    expect(filtered.ok()).toBeTruthy()
    const other = await page.request.get('/api/sample/products')
    expect(other.ok()).toBeTruthy()

    /** @type {string[]} */
    const bootuiRequests = []
    page.on('request', (request) => {
      if (request.url().includes('/bootui/api/')) bootuiRequests.push(request.url())
    })

    await openView('activity', 'Live Activity')
    const table = page.locator('.activity-table')
    await expect(table).toContainText('/api/sample/product-search', {timeout: 15_000})

    await page.locator('#activity-correlation-filter').fill(streamValue)

    await expect(table).toContainText('/api/sample/product-search', {timeout: 15_000})
    await expect(table).not.toContainText('/api/sample/products')
    await expect(page.locator('.alert-info')).toContainText(streamValue)

    expect(bootuiRequests.some((url) => url.includes(streamValue))).toBe(false)
    expect(page.url()).not.toContain(streamValue)

    await page.getByRole('button', {name: 'Clear correlation filter'}).click()
    await expect(table).toContainText('/api/sample/products', {timeout: 15_000})
  })
})
