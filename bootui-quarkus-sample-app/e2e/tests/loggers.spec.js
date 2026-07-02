// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Loggers reads live logger names from the JBoss LogManager (only loggers that have already been
 * instantiated appear), so we hit a sample endpoint first to make sure a sample-app logger is
 * registered, then filter to it by package prefix rather than pinning one exact class name. The engine
 * refuses to mutate BootUI's own loggers, so this test only ever targets an application-level logger.
 * The level is reset back to inherited afterwards so no state leaks into later tests.
 */
test.describe('Loggers (Quarkus)', () => {
  test.beforeEach(async ({page}) => {
    await page.request.get('/api/sample/hello').catch(() => {})
  })

  test('sets and resets a sample app logger level', async ({openView, page}) => {
    await openView('loggers', 'Loggers')

    await page.getByPlaceholder('Filter loggers by name…').fill('io.github.jdubois.bootui.sample')
    const row = page.locator('tbody tr').first()
    const nameCell = row.locator('code')
    await expect(nameCell).toContainText('io.github.jdubois.bootui.sample', {timeout: 10_000})
    const loggerName = (await nameCell.textContent())?.trim()

    await row.getByRole('button', {name: 'DEBUG', exact: true}).click()
    await expect(page.locator('.alert-success')).toContainText(`Level updated for ${loggerName}`)
    await expect(row.locator('td').nth(1)).toHaveText('DEBUG')

    await row.getByTitle('Reset').click()
    await expect(page.locator('.alert-success')).toContainText(`Level updated for ${loggerName}`)
    await expect(row.locator('td').nth(1)).toHaveText('—')
  })
})
