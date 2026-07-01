// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Flyway view', () => {
  test('lists the catalog migrations and filters them by script', async ({openView, page}) => {
    await openView('flyway', 'Flyway migrations')

    // The sample app exposes a single `flyway` bean managing the catalog schema.
    await expect(page.locator('code', {hasText: /^flyway$/})).toBeVisible()
    await expect(page.locator('main')).toContainText(/migration\(s\) across \d+ database\(s\)/)
    await expect(page.getByRole('button', {name: /Migrate/})).toBeVisible()

    // The four catalog migrations are always resolved, regardless of applied/pending state
    // (the persistent sample database may already have later migrations applied).
    await expect(page.getByText('V1__create_catalog.sql')).toBeVisible()
    await expect(page.getByText('V2__seed_catalog.sql')).toBeVisible()
    await expect(page.getByText('V3__add_catalog_tags.sql')).toBeVisible()
    await expect(page.getByText('V4__classify_catalog_books.sql')).toBeVisible()

    // Filtering by script narrows the table to the single matching migration.
    const flywayCard = page.locator('.card', {has: page.locator('.card-header code', {hasText: /^flyway$/})})
    await page.getByPlaceholder(/Filter by version/).fill('tags')
    await expect(flywayCard.locator('tbody tr')).toHaveCount(1)
    await expect(page.getByText('V3__add_catalog_tags.sql')).toBeVisible()
    await expect(page.getByText('V1__create_catalog.sql')).toHaveCount(0)
  })
})
