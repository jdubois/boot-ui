// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Hibernate Advisor view', () => {
  test('runs mapped-entity checks and shows the sample advisor fixtures', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    // The pre-scan empty state is not asserted because the advisor caches the last scan, so a
    // reused or retried server (or an earlier advisor test) may already have scan data on mount.
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()

    // After the scan the findings render and the empty state disappears.
    await expect(page.getByText('No Hibernate Advisor data yet')).toHaveCount(0, {timeout: 30_000})
    await expect(page.getByText('Eager fetching should stay explicit and bounded')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer is mapped as FetchType.EAGER/)).toBeVisible()
    await expect(
      page.getByText(/SampleAppPreferences#enabledFeatures is an @ElementCollection mapped as FetchType.EAGER/)
    ).toBeVisible()
    await expect(page.getByText('Generated identifiers should avoid GenerationType.IDENTITY')).toBeVisible()
    await expect(page.getByText(/Product#id uses GenerationType.IDENTITY/)).toBeVisible()
    await expect(page.getByText('One-to-many associations should be bidirectional or join-column based')).toBeVisible()
    await expect(page.getByText(/SampleCustomer#invoices is unidirectional @OneToMany/)).toBeVisible()
    await expect(page.getByText('Many-to-many associations should use Set semantics')).toBeVisible()
    await expect(page.getByText(/SampleOrder#tags is @ManyToMany and declared as a List/)).toBeVisible()
    await expect(page.getByText('Enum attributes should declare an explicit storage strategy')).toBeVisible()
    await expect(page.getByText(/SampleOrder#status relies on JPA's default ORDINAL enum storage/)).toBeVisible()
    await expect(page.getByText('Collection fetch joins should not be paged directly')).toBeVisible()
    await expect(page.getByText(/SampleOrderRepository#findPageWithTags pages a collection JOIN FETCH/)).toBeVisible()
    await expect(page.getByText('Generated identifiers should avoid GenerationType.TABLE')).toBeVisible()
    await expect(page.getByText(/SampleLegacyTicket#id uses GenerationType.TABLE/)).toBeVisible()
    await expect(page.getByText('@SequenceGenerator should use pooled allocation')).toBeVisible()
    await expect(page.getByText(/SampleOrder#id declares @SequenceGenerator\(allocationSize=1\)/)).toBeVisible()
    await expect(page.getByText('Many-to-many associations should not cascade remove')).toBeVisible()
    await expect(page.getByText(/SampleOrder#tags cascades REMOVE\/ALL across @ManyToMany/)).toBeVisible()
    await expect(page.getByText('Many-to-one associations should not cascade remove')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer cascades REMOVE\/ALL across @ManyToOne/)).toBeVisible()
    await expect(page.getByText('One-to-one associations should prefer shared primary keys')).toBeVisible()
    await expect(page.getByText(/SampleOrder#details is an owning @OneToOne without @MapsId/)).toBeVisible()
    await expect(page.getByText('@NotFound(IGNORE) should be reviewed')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer uses @NotFound\(action=IGNORE\)/)).toBeVisible()
    await expect(page.getByText('@Lob attributes should be loaded lazily')).toBeVisible()
    await expect(page.getByText(/SampleAuditEntry#payload is annotated with @Lob/)).toBeVisible()
    await expect(page.getByText('BigDecimal columns should declare precision and scale')).toBeVisible()
    await expect(
      page.getByText(/SampleAuditEntry#amount is a BigDecimal column without explicit precision/)
    ).toBeVisible()
    await expect(page.getByText('@Modifying queries should clear or flush the persistence context')).toBeVisible()
    await expect(
      page.getByText(/SampleOrderRepository#markAllAs is @Modifying without clearAutomatically/)
    ).toBeVisible()
    await expect(page.getByText('Native paged @Query must declare countQuery')).toBeVisible()
    await expect(page.getByText(/SampleOrderRepository#findPageNative/)).toBeVisible()
    await expect(page.getByRole('link', {name: 'Learn more'}).first()).toBeVisible()
  })
})
