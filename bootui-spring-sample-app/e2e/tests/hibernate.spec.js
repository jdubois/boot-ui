// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Hibernate Advisor view', () => {
  test('runs mapped-entity checks and shows the sample advisor fixtures', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    // The pre-scan empty state is not asserted because the advisor caches the last scan, so a
    // reused or retried server (or an earlier advisor test) may already have scan data on mount.
    const scanResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && /\/hibernate\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()
    const response = await scanResponse
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.rulesEvaluated).toBe(70)
    expect(report.scan.status).toBe('PARTIAL')
    expect(report.scan.message).toBeTruthy()
    expect(report.entitiesAnalyzed).toBeGreaterThan(0)
    expect(new Set(report.results.map((result) => result.id)).size).toBe(report.results.length)
    for (const id of ['HIB-FETCH-004', 'HIB-MAP-012', 'HIB-MAP-019', 'HIB-ENTITY-003', 'HIB-ENTITY-004']) {
      expect(report.results.map((result) => result.id)).not.toContain(id)
    }

    // After the scan the findings render and the empty state disappears.
    await expect(page.getByText('No Hibernate Advisor data yet')).toHaveCount(0, {timeout: 30_000})
    await expect(page.getByText('Eager fetching should stay explicit and bounded')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer is mapped as FetchType.EAGER/)).toBeVisible()
    await expect(
      page.getByText(/SampleAppPreferences#enabledFeatures is an @ElementCollection mapped as FetchType.EAGER/)
    ).toBeVisible()
    await expect(page.locator('.list-group-item', {hasText: 'HIB-ID-001'})).toHaveCount(0)
    await expect(page.locator('.list-group-item', {hasText: 'HIB-ID-006'})).toContainText(
      'GenerationType.IDENTITY disables JDBC batch inserts'
    )
    await expect(page.getByText(/Product#id uses GenerationType.IDENTITY/)).toBeVisible()
    await expect(page.getByText('One-to-many associations should be bidirectional or join-column based')).toBeVisible()
    await expect(page.getByText(/SampleCustomer#invoices is unidirectional @OneToMany/)).toBeVisible()
    await expect(page.getByText('Review many-to-many list semantics')).toBeVisible()
    await expect(page.getByText(/SampleOrder#tags is @ManyToMany and declared as a List/)).toBeVisible()
    await expect(page.getByText('Enum attributes should declare an explicit storage strategy')).toBeVisible()
    await expect(page.locator('.list-group-item', {hasText: 'HIB-MAP-003'})).toContainText('SampleOrder#status')
    // Hibernate 7.4 can push collection-fetch pagination into SQL, but version alone does not prove
    // the query plan. With no explicit in-memory hint, this fixture contributes unknown coverage.
    await expect(page.getByText('Collection fetch joins should not be paged directly')).toHaveCount(0)
    await expect(page.getByText(/SampleOrderRepository#findPageWithTags pages a collection JOIN FETCH/)).toHaveCount(0)
    await expect(page.getByText('Review table-based identifier allocation')).toBeVisible()
    await expect(page.getByText(/SampleLegacyTicket#id uses GenerationType.TABLE/)).toBeVisible()
    await expect(page.getByText('Review sequence allocation declarations')).toBeVisible()
    await expect(page.getByText(/SampleOrder#id declares @SequenceGenerator\(allocationSize=1\)/)).toBeVisible()
    await expect(page.getByText('Many-to-many associations should not cascade remove')).toBeVisible()
    await expect(page.getByText(/SampleOrder#tags cascades REMOVE\/ALL across @ManyToMany/)).toBeVisible()
    await expect(page.getByText('Many-to-one associations should not cascade remove')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer cascades REMOVE\/ALL across @ManyToOne/)).toBeVisible()
    await expect(page.getByText('One-to-one associations should prefer shared primary keys')).toBeVisible()
    await expect(page.getByText(/SampleOrder#details is an owning @OneToOne without @MapsId/)).toBeVisible()
    await expect(page.getByText('@NotFound(IGNORE) should be reviewed')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer uses @NotFound\(action=IGNORE\)/)).toBeVisible()
    await expect(page.getByText('BigDecimal columns should declare precision and scale')).toBeVisible()
    await expect(
      page.getByText(/SampleAuditEntry#amount is a BigDecimal column without explicit precision/)
    ).toBeVisible()
    await expect(page.getByText('@Modifying bulk queries should clear stale persistence context')).toBeVisible()
    await expect(
      page.getByText(/SampleOrderRepository#markAllAs is @Modifying without clearAutomatically/)
    ).toBeVisible()
    await expect(page.getByText('Native Page queries should review count derivation')).toBeVisible()
    await expect(page.getByText(/SampleOrderRepository#findPageNative/)).toBeVisible()
    await expect(page.getByRole('link', {name: 'Learn more'}).first()).toBeVisible()
  })

  test('does not recommend lazy basic loading without bytecode enhancement', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()

    await expect(page.getByText('No Hibernate Advisor data yet')).toHaveCount(0, {timeout: 30_000})
    await expect(page.getByText('Enhanced @Lob attributes should be loaded lazily')).toHaveCount(0)
    await expect(page.getByText(/SampleAuditEntry#payload is annotated with @Lob/)).toHaveCount(0)
  })
})
