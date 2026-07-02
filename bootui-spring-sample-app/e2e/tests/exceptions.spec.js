// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

const DEMO_MESSAGE = 'Sample failure for the BootUI Exceptions panel demo'

test.describe('Exceptions view', () => {
  test('captures a thrown exception with masked message and cause chain', async ({openView, page}) => {
    // The sample endpoint throws an IllegalStateException caused by a NumberFormatException.
    await page.request.get('/api/sample/boom')
    await page.request.get('/api/sample/boom')

    await openView('exceptions', 'Exceptions')

    const row = page.locator('tbody tr', {hasText: DEMO_MESSAGE}).first()
    await expect(row).toBeVisible({timeout: 15_000})
    await expect(row).toContainText('IllegalStateException')
    // The secret-like assignment in the message is masked under the default MASKED policy.
    await expect(row).toContainText('apiToken=******')
    await expect(row).not.toContainText('sample-secret-token')

    await row.getByRole('button', {name: 'Details'}).click()

    const drawer = page.locator('.exception-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('Caused by: java.lang.NumberFormatException')
    await expect(drawer.locator('.stack-pane')).toContainText('SampleController')
    await expect(drawer).toContainText('Recent occurrences')
  })

  test('filters the captured exceptions by text', async ({openView, page}) => {
    await page.request.get('/api/sample/boom')

    await openView('exceptions', 'Exceptions')
    const row = page.locator('tbody tr', {hasText: DEMO_MESSAGE})
    await expect(row.first()).toBeVisible({timeout: 15_000})

    const filterBox = page.getByPlaceholder(/Filter by exception type/)
    await filterBox.fill('IllegalStateException')
    await expect(row.first()).toBeVisible()

    await filterBox.fill('no-such-exception-xyz')
    await expect(row).toHaveCount(0)
  })

  test('clear empties the captured exceptions', async ({openView, page}) => {
    await page.request.get('/api/sample/boom')

    await openView('exceptions', 'Exceptions')
    await expect(page.locator('tbody tr', {hasText: DEMO_MESSAGE}).first()).toBeVisible({timeout: 15_000})

    await page.getByRole('button', {name: /Clear/}).click()
    await acceptConfirm(page)

    await expect(page.locator('tbody tr', {hasText: DEMO_MESSAGE})).toHaveCount(0)
  })

  test('acknowledges, resolves, and detects a regression on the same exception group', async ({openView, page}) => {
    await openView('exceptions', 'Exceptions')

    // Start from an empty buffer so the group below is unambiguous.
    const clearButton = page.getByRole('button', {name: /Clear/})
    if (await clearButton.isEnabled()) {
      await clearButton.click()
      await acceptConfirm(page)
      await expect(page.locator('.alert-success')).toBeVisible()
    }

    // /api/sample/boom always throws from the same catch block, so repeated calls share one
    // fingerprint/group (see ExceptionStore's fingerprinting) — the group is created OPEN.
    await page.request.get('/api/sample/boom')
    await page.getByTitle('Refresh', {exact: true}).click()

    const row = page.locator('tbody tr', {hasText: DEMO_MESSAGE}).first()
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
