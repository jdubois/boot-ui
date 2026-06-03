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
  })

  test('shows security failures recorded before Spring Security short-circuits', async ({openView, page}) => {
    const secureResponse = await page.request.get('/api/secure')
    expect(secureResponse.status()).toBe(401)

    await openView('http-exchanges', 'HTTP Exchanges')

    await expect(page.locator('table')).toContainText('/api/secure', {timeout: 15_000})
    await expect(page.locator('table')).toContainText('401')
  })
})
