// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Correlation-ID filtering (Spring MVC).
 *
 * BootUI reads a bounded set of correlation identifiers off the inbound headers it already captured, masks
 * their values under the live exposure policy, and derives an opaque lookup identity so the timeline can be
 * narrowed to one distributed request without the raw identifier ever leaving the browser. These tests
 * exercise the full vertical: capture on HTTP Exchanges, the cross-link into Live Activity, and the
 * in-browser filter — including that the raw identifier never appears in a BootUI URL.
 */
test.describe('Correlation-ID filtering', () => {
  const correlationValue = 'e2e-correlation-spring-42'
  // Each test needs its own identifier: the sample application is not restarted between tests, so a
  // shared value would legitimately match requests an earlier test made and defeat the negative assertions.
  const streamValue = 'e2e-correlation-spring-stream-77'

  test('captures masked correlation identifiers on the exchange and links into Live Activity', async ({
    openView,
    page
  }) => {
    const response = await page.request.get('/api/sample/hello', {
      headers: {'X-Correlation-ID': correlationValue, 'X-Request-ID': 'e2e-request-spring-42'}
    })
    expect(response.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')
    await expect(page.locator('table')).toContainText('/api/sample/hello', {timeout: 15_000})

    const row = page.locator('tbody tr', {hasText: '/api/sample/hello'}).first()
    await row.locator('.http-exchanges-detail-toggle').click()

    const detail = page.locator('.http-exchanges-detail').first()
    await expect(detail).toContainText('Correlation identifiers')
    await expect(detail).toContainText('x-correlation-id')
    // Masked by default: the captured value is never rendered in the clear.
    await expect(detail).not.toContainText(correlationValue)

    const link = detail.getByRole('link', {name: /Live Activity/}).first()
    await expect(link).toBeVisible()
    const href = await link.getAttribute('href')
    expect(href).toContain('correlationLookupId=')
    // The link carries the one-way identity, never the identifier the caller sent.
    expect(href).not.toContain(correlationValue)

    await link.click()
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /Live Activity/})
        .first()
    ).toBeVisible()
    await expect(page.locator('.alert-info')).toContainText('linked identifier')
    await expect(page.locator('.activity-table tbody tr').first()).toBeVisible({timeout: 15_000})
  })

  test('filters the live stream in the browser without sending the identifier to BootUI', async ({openView, page}) => {
    const filtered = await page.request.get('/api/sample/product-search', {
      headers: {'X-Correlation-ID': streamValue}
    })
    expect(filtered.ok()).toBeTruthy()
    const other = await page.request.get('/api/sample/hello')
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
    await expect(table).not.toContainText('/api/sample/hello')
    await expect(page.locator('.alert-info')).toContainText(streamValue)

    // The identifier is matched entirely client-side: no BootUI request may carry it.
    expect(bootuiRequests.some((url) => url.includes(streamValue))).toBe(false)
    expect(page.url()).not.toContain(streamValue)

    await page.getByRole('button', {name: 'Clear correlation filter'}).click()
    await expect(table).toContainText('/api/sample/hello', {timeout: 15_000})
  })

  test('says so plainly when no captured activity carries the identifier', async ({openView, page}) => {
    const seed = await page.request.get('/api/sample/hello')
    expect(seed.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')
    const table = page.locator('.activity-table')
    await expect(table).toContainText('/api/sample/hello', {timeout: 15_000})

    const input = page.locator('#activity-correlation-filter')
    await input.fill('e2e-correlation-that-was-never-sent')
    await expect(page.locator('.alert-info')).toBeVisible()
    await expect(table).toContainText('No activity matches the current filters.')

    // Blank input is not an identifier: the filter simply lifts, with no error and no empty timeline.
    await input.fill('   ')
    await expect(page.locator('.alert-info')).toHaveCount(0)
    await expect(table).toContainText('/api/sample/hello', {timeout: 15_000})
  })
})
