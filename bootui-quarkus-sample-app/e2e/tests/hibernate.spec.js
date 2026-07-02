// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Hibernate advisor runs the shared engine's mapping/identifier/fetch rules against the live JPA
 * metamodel, discovered through the EntityManagerFactory (Quarkus has no repository layer, so the
 * Spring-Data-repository-specific rules never fire here - only entity-mapping findings are asserted).
 * The sample's advisor.hibernate entities intentionally trigger several rules so a real scan has
 * deterministic findings; the same annotations exist on the Spring sample, so this pins parity.
 */
test.describe('Hibernate advisor (Quarkus)', () => {
  test('runs Hibernate checks and renders real entity-mapping findings', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()

    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})
    await expect(page.locator('main')).toContainText('Entities analysed')

    // Product uses GenerationType.IDENTITY.
    const identityRow = page.locator('.list-group-item', {hasText: 'HIB-ID-001'})
    await expect(identityRow).toContainText('Generated identifiers should avoid GenerationType.IDENTITY')

    // SampleLegacyTicket uses GenerationType.TABLE.
    const tableRow = page.locator('.list-group-item', {hasText: 'HIB-ID-002'})
    await expect(tableRow).toContainText('Generated identifiers should avoid GenerationType.TABLE')

    // SampleCustomer#invoices is a unidirectional @OneToMany.
    const mappingRow = page.locator('.list-group-item', {hasText: 'HIB-MAP-001'})
    await expect(mappingRow).toContainText('One-to-many associations should be bidirectional or join-column based')

    // SampleOrder#customer is @ManyToOne(fetch = FetchType.EAGER).
    const fetchRow = page.locator('.list-group-item', {hasText: 'HIB-FETCH-001'})
    await expect(fetchRow).toContainText('Eager fetching should stay explicit and bounded')
  })
})
