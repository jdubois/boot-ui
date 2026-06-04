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
    await expect(page.getByRole('link', {name: 'Learn more'}).first()).toBeVisible()
  })
})
