// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Hibernate Advisor view', () => {
  test('runs mapped-entity checks and shows the sample advisor fixtures', async ({openView, page}) => {
    await openView('hibernate-advisor', 'Hibernate Advisor')

    await expect(page.getByText('No Hibernate Advisor data yet')).toBeVisible()
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()

    await expect(page.getByText('Associations should avoid eager fetching by default')).toBeVisible()
    await expect(page.getByText(/SampleOrder#customer is mapped as FetchType.EAGER/)).toBeVisible()
    await expect(page.getByText('Generated identifiers should avoid GenerationType.IDENTITY')).toBeVisible()
    await expect(page.getByText(/Product#id uses GenerationType.IDENTITY/)).toBeVisible()
    await expect(page.getByText('One-to-many associations should be bidirectional or join-column based')).toBeVisible()
    await expect(page.getByText(/SampleCustomer#invoices is unidirectional @OneToMany/)).toBeVisible()
    await expect(page.getByText('Many-to-many associations should use Set semantics')).toBeVisible()
    await expect(page.getByText(/SampleOrder#tags is @ManyToMany and declared as a List/)).toBeVisible()
    await expect(page.getByText('Enum attributes should be stored as strings')).toBeVisible()
    await expect(page.getByText(/SampleOrder#status stores enum values as ORDINAL/)).toBeVisible()
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
    await expect(page.getByRole('link', {name: 'Learn more'}).first()).toBeVisible()
  })
})
