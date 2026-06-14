// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP Exchanges view', () => {
  test('shows recent sample app requests', async ({openView, page}) => {
    const apiResponse = await page.request.get('/api/sample/hello')
    expect(apiResponse.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')

    await expect(page.locator('table')).toContainText('/api/sample/hello', {timeout: 15_000})
    await expect(page.locator('table')).toContainText('GET')
    await expect(page.locator('table')).toContainText('200')

    const sampleRow = page.locator('tbody tr', {hasText: '/api/sample/hello'}).first()
    const detailsButton = sampleRow.locator('.http-exchanges-detail-toggle')
    await expect(detailsButton).toBeVisible()
    await detailsButton.click()
    await expect(page.locator('.http-exchanges-detail').first()).toContainText('Request headers')
  })

  test('shows security failures recorded before Spring Security short-circuits', async ({openView, page}) => {
    const secureResponse = await page.request.get('/api/secure')
    expect(secureResponse.status()).toBe(401)

    await openView('http-exchanges', 'HTTP Exchanges')

    await expect(page.locator('table')).toContainText('/api/secure', {timeout: 15_000})
    await expect(page.locator('table')).toContainText('401')
  })

  test('updates live over SSE when a new request is recorded', async ({openView, page}) => {
    await openView('http-exchanges', 'HTTP Exchanges')

    // Trigger a uniquely identifiable request only after the panel is already open, then assert it
    // appears without any manual refresh — relying solely on the Server-Sent Events push.
    const marker = `sse-live-${Date.now()}`
    const apiResponse = await page.request.get(`/api/sample/hello?probe=${marker}`)
    expect(apiResponse.ok()).toBeTruthy()

    await expect(page.locator('table')).toContainText(marker, {timeout: 15_000})
  })
})
