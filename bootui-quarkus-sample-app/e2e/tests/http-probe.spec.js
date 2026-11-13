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

  test('picks a declared Quarkus route from Mappings without auto-sending', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    const picker = page.getByLabel('Pick a declared route')
    await expect(picker).toBeVisible()
    await picker.fill('/api/sample/hello')

    const option = page.locator('.http-probe-route-option', {hasText: '/api/sample/hello'}).first()
    await expect(option).toBeVisible()
    await picker.press('ArrowDown')
    await picker.press('Enter')

    await expect(page.getByLabel('Method')).toHaveValue('GET')
    await expect(page.getByLabel('Path', {exact: true})).toHaveValue('/api/sample/hello')
    await expect(page.locator('.card', {hasText: 'Response'})).toHaveCount(0)
  })
})
