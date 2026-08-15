// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Database Advisor view', () => {
  test('runs physical-schema checks and shows a real finding', async ({openView, page}) => {
    await openView('database-advisor', 'Database Advisor')

    // The pre-scan empty state is not asserted because the advisor caches the last scan, so a
    // reused or retried server (or an earlier advisor test) may already have scan data on mount.
    await page.getByRole('button', {name: 'Run Database checks'}).click()

    // After the scan the findings render and the empty state disappears.
    await expect(page.getByText('No Database Advisor data yet')).toHaveCount(0, {timeout: 30_000})

    // The sample app's @ManyToMany/@ElementCollection join tables (e.g. sample_advisor_orders_tags,
    // sample_app_preferences_enabled_features) have no primary key, a real, deterministic finding from
    // plain JDBC DatabaseMetaData introspection.
    await expect(page.getByText('Tables without a primary key')).toBeVisible()
    await expect(page.getByText(/has no primary key/).first()).toBeVisible()
    await expect(page.getByRole('link', {name: 'Learn more'}).first()).toBeVisible()
  })
})
