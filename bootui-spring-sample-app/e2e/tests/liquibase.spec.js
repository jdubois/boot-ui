// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Liquibase view', () => {
  test('lists inventory change sets with applied/pending state and filters by id', async ({openView, page}) => {
    await openView('liquibase', 'Liquibase change sets')

    // The sample app exposes a single `liquibase` bean managing the inventory schema.
    await expect(page.locator('code', {hasText: /^liquibase$/})).toBeVisible()
    await expect(page.locator('main')).toContainText(/change set\(s\) across \d+ database\(s\)/)
    await expect(page.getByRole('button', {name: /Update/})).toBeVisible()

    // All four inventory change sets are always listed (applied history + pending changelog),
    // regardless of how many have been applied against the persistent sample database.
    const liquibaseCard = page.locator('.card', {has: page.locator('.card-header code', {hasText: /^liquibase$/})})
    await expect(liquibaseCard.locator('tbody tr')).toHaveCount(4)
    await expect(liquibaseCard.locator('tbody td code', {hasText: /^1$/})).toBeVisible()
    await expect(liquibaseCard.locator('tbody td code', {hasText: /^4$/})).toBeVisible()

    // Filtering by change-set id narrows the table to the single matching change set.
    await page.getByPlaceholder(/Filter by id/).fill('3')
    await expect(liquibaseCard.locator('tbody tr')).toHaveCount(1)
    await expect(liquibaseCard.locator('tbody td code', {hasText: /^3$/})).toBeVisible()
    await expect(liquibaseCard.locator('tbody td code', {hasText: /^1$/})).toHaveCount(0)
  })
})
