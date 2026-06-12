// @ts-check
import {expect, test} from './fixtures.js'

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

    await row.getByRole('button', {name: 'Open'}).click()

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

    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', {name: /Clear/}).click()

    await expect(page.locator('tbody tr', {hasText: DEMO_MESSAGE})).toHaveCount(0)
  })
})
