// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * Exceptions captures thrown exceptions via QuarkusExceptionLogHandler / QuarkusExceptionCaptureFilter.
 * The sample's /api/sample/boom endpoint throws an IllegalStateException whose message embeds an
 * "apiToken=..." assignment, so this also proves the shared engine's default MASKED value-exposure
 * policy scrubs it before the message ever reaches the browser.
 */
test.describe('Exceptions (Quarkus)', () => {
  test.beforeEach(async ({page}) => {
    await page.request.get('/api/sample/boom').catch(() => {})
  })

  test('captures a thrown exception with a masked secret and clears the buffer', async ({openView, page}) => {
    await openView('exceptions', 'Exceptions')

    const clearButton = page.getByRole('button', {name: 'Clear'})
    await expect(clearButton).toBeEnabled({timeout: 15_000})

    const row = page.locator('tbody tr', {hasText: 'IllegalStateException'}).first()
    await expect(row).toBeVisible()
    await expect(row).toContainText('apiToken=******')
    await expect(row).not.toContainText('sample-secret-token')

    await clearButton.click()
    await acceptConfirm(page)

    await expect(page.locator('.alert-success')).toContainText('Cleared captured exceptions.')
    await expect(clearButton).toBeDisabled()
  })
})
