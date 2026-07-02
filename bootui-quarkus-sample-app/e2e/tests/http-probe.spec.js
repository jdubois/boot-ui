// @ts-check
import {expect, test} from './fixtures.js'

/**
 * HTTP Probe sends a real local HTTP request to the host application's own loopback port through the
 * shared engine HttpProbeService (resolved per-probe by QuarkusServerPortSupplier). We target the
 * sample's own /api/sample/hello endpoint (conveniently also the form's placeholder) and assert on its
 * exact, known response text - proving the round-trip actually reached the real running app.
 */
test.describe('HTTP Probe (Quarkus)', () => {
  test('sends a GET probe to the sample app and renders the real response', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await page.getByPlaceholder('/api/sample/hello').fill('/api/sample/hello')
    await page.getByRole('button', {name: 'Send'}).click()

    const responseCard = page.locator('.card', {hasText: 'Response'})
    await expect(responseCard.locator('.badge')).toContainText('200', {timeout: 10_000})
    await expect(page.locator('pre code')).toContainText('Hello, BootUI!')
  })

  test('clears the form and the response', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await page.getByPlaceholder('/api/sample/hello').fill('/api/sample/hello')
    await page.getByRole('button', {name: 'Send'}).click()
    await expect(page.locator('.card', {hasText: 'Response'})).toBeVisible()

    await page.getByRole('button', {name: 'Clear'}).click()
    await expect(page.locator('.card', {hasText: 'Response'})).toHaveCount(0)
  })
})
