// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * Flyway migrations run automatically at startup (quarkus.flyway.migrate-at-start=true) against the
 * catalog_* tables, so re-running Migrate through the UI is a safe, idempotent round-trip against the
 * live QuarkusFlywayProvider / FlywayContainer.
 */
test.describe('Flyway (Quarkus)', () => {
  test('re-running Migrate reports the real database is already up to date', async ({openView, page}) => {
    await openView('flyway', 'Flyway')

    const card = page.locator('.card').first()
    await expect(card).toBeVisible()
    await expect(card.locator('.card-header .badge.bg-success')).toContainText(/\d+ applied/)

    const migrateButton = card.getByRole('button', {name: 'Migrate'})
    await expect(migrateButton).toBeEnabled()
    await migrateButton.click()
    await acceptConfirm(page)

    await expect(page.locator('.alert-success')).toContainText('Flyway schema is already up to date.')
  })

  test('reports the real clean-disabled configuration without invoking the destructive action', async ({
    openView,
    page
  }) => {
    await openView('flyway', 'Flyway')

    // quarkus.flyway.clean-disabled is not overridden in the sample app, and Quarkus' own default for
    // that property is false (unlike Spring Boot's safety default of true), so the live
    // QuarkusFlywayProvider reports Clean as enabled here. We deliberately do not click it: Flyway
    // clean drops every object in the default schema, which the Hibernate/Liquibase demo data also
    // live in, and corrupting that would break every later spec in this sequential suite.
    const card = page.locator('.card').first()
    const cleanButton = card.getByRole('button', {name: 'Clean'})
    await expect(cleanButton).toBeEnabled()
  })
})
