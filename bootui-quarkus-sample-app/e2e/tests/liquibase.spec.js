// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * Liquibase change sets run automatically at startup (quarkus.liquibase.migrate-at-start=true) against
 * the inventory_* tables, so re-running Update through the UI is a safe, idempotent round-trip against
 * the live QuarkusLiquibaseProvider.
 */
test.describe('Liquibase (Quarkus)', () => {
  test('re-running Update reports the real database is already up to date', async ({openView, page}) => {
    await openView('liquibase', 'Liquibase')

    const card = page.locator('.card').first()
    await expect(card).toBeVisible()
    await expect(card.locator('.card-header .badge.bg-success')).toContainText(/\d+ applied/)

    const updateButton = card.getByRole('button', {name: 'Update'})
    await expect(updateButton).toBeEnabled()
    await updateButton.click()
    await acceptConfirm(page)

    await expect(page.locator('.alert-success')).toContainText('Liquibase database is already up to date.')
  })
})
