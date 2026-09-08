// @ts-check
import {expect, test} from './fixtures.js'
import {expectedAdvisorScore} from '../scenarios/advisor-scoring.js'

test.describe('Database view', () => {
  test('runs physical-schema checks and shows a real finding', async ({openView, page}) => {
    await openView('database-advisor', 'Database')

    // The pre-scan empty state is not asserted because the advisor caches the last scan, so a
    // reused or retried server (or an earlier advisor test) may already have scan data on mount.
    const scanResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && /\/database-advisor\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run Database checks'}).click()
    const report = await (await scanResponse).json()
    expect(report.scan.status).toBe('PARTIAL')
    expect(report.evidence.usable).toBe(true)
    expect(report.evidence.coverageComplete).toBe(false)
    await expect(page.locator('.advisor-summary__value')).toHaveText(String(expectedAdvisorScore(report)))
    await expect(page.getByRole('img', {name: /Known-findings score: .*Scan notes available/})).toHaveCount(1)

    // After the scan the findings render and the empty state disappears.
    await expect(page.getByText('No Database data yet')).toHaveCount(0, {timeout: 30_000})

    // The sample app's @ManyToMany/@ElementCollection join tables (e.g. sample_advisor_orders_tags,
    // sample_app_preferences_enabled_features) have no primary key, a real, deterministic finding from
    // plain JDBC DatabaseMetaData introspection.
    await expect(page.getByText('Tables without a primary key')).toBeVisible()
    await expect(page.getByText(/has no primary key/).first()).toBeVisible()
    await expect(page.getByRole('link', {name: 'Learn more'}).first()).toBeVisible()
    await page.locator('a[href$="#/overview"]').first().click()
    const card = page.locator('.scanner-card').filter({hasText: 'Database'})
    await expect(card.locator('.scanner-score')).toHaveText(String(expectedAdvisorScore(report)))
    await expect(card).toContainText('Results available')
    await expect(page.locator('.assessment-summary')).toContainText('scan notes')
  })
})
