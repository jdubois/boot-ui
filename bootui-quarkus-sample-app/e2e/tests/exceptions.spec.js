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

  test('acknowledges, resolves, and detects a regression on the same exception group', async ({openView, page}) => {
    await openView('exceptions', 'Exceptions')

    // Start from an empty buffer so the group tracked below is unambiguous.
    const clearButton = page.getByRole('button', {name: 'Clear'})
    await expect(clearButton).toBeEnabled({timeout: 15_000})
    await clearButton.click()
    await acceptConfirm(page)
    await expect(page.locator('.alert-success')).toBeVisible()

    // /api/sample/boom always throws from the same catch block, so repeated calls share one
    // fingerprint/group (see ExceptionStore's fingerprinting) — the group is created OPEN.
    await page.request.get('/api/sample/boom')
    await page.getByTitle('Refresh', {exact: true}).click()

    const row = page.locator('tbody tr', {hasText: 'IllegalStateException'}).first()
    await expect(row).toBeVisible({timeout: 15_000})
    await expect(row.getByRole('button', {name: 'Open', exact: true})).toHaveClass(/active/)

    await row.getByRole('button', {name: 'Acknowledged', exact: true}).click()
    await expect(row).toContainText('Acknowledged')
    await expect(row.getByRole('button', {name: 'Acknowledged', exact: true})).toHaveClass(/active/)

    await row.getByRole('button', {name: 'Resolved', exact: true}).click()
    await expect(row).toContainText('Resolved')
    await expect(row.getByRole('button', {name: 'Resolved', exact: true})).toHaveClass(/active/)

    // The exact same failure firing again after being marked resolved is a Sentry-style regression:
    // ExceptionStore auto-reopens the group and marks it, instead of silently staying Resolved.
    await page.request.get('/api/sample/boom')
    await page.getByTitle('Refresh', {exact: true}).click()

    await expect(row.getByRole('button', {name: 'Open', exact: true})).toHaveClass(/active/)
    await expect(row).toContainText('Reopened ×1')
  })
})
