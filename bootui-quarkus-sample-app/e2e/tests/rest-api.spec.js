// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The REST API advisor runs curated, project-agnostic REST design rules against the host application's
 * own JAX-RS resources. The sample app has no rule-specific markers planted (unlike Architecture/
 * Security/Hibernate), so this test proves the Quarkus backend performs a real reflective scan over the
 * sample's own resource classes (SampleResource, AdminResource, SecureResource, ...) by asserting the
 * "Controllers analysed" / handler-method counts are real positive numbers, rather than pinning a
 * specific finding.
 */
test.describe('REST API advisor (Quarkus)', () => {
  test('runs REST API checks and analyses the sample app resources', async ({openView, page}) => {
    await openView('rest-api', 'REST API')

    await page.getByRole('button', {name: 'Run REST API checks'}).click()
    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})

    const controllersMetric = page.locator('.advisor-summary__metric', {hasText: 'Controllers analysed'})
    const controllersAnalyzed = Number((await controllersMetric.locator('dd').textContent())?.trim())
    expect(controllersAnalyzed).toBeGreaterThan(0)
    await expect(controllersMetric.locator('.advisor-summary__hint')).toHaveText(/\d+ handler method\(s\)/)
  })
})
