// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Vulnerabilities panel lists the local runtime JAR inventory (captured at Quarkus build time from
 * the application model) network-free, then only calls the real OSV.dev service when the user clicks
 * Scan. This test performs a real network round-trip - no page.route mocking - so it proves the Quarkus
 * backend's OsvVulnerabilityScanner actually reaches OSV.dev end-to-end. It deliberately avoids asserting
 * on specific CVE findings, since those change over time; it only asserts the scan completes and the
 * local dependency inventory is real.
 */
test.describe('Vulnerabilities (Quarkus)', () => {
  test('lists the local dependency inventory and scans it with OSV.dev', async ({openView, page}) => {
    await openView('vulnerabilities', 'Vulnerabilities')

    const dependenciesMetric = page.locator('.advisor-summary__metric', {hasText: 'Dependencies'})
    const totalBefore = Number((await dependenciesMetric.locator('dd').textContent())?.trim())
    expect(totalBefore).toBeGreaterThan(0)

    await page.getByRole('button', {name: 'Scan with OSV.dev'}).click()

    // Real network call to OSV.dev, so give it a generous timeout. The sample app ships 300+
    // dependencies, comfortably over the default bootui.vulnerabilities.max-packages=250 limit, so a
    // real scan reports "Partial scan" rather than "Scan complete" - accept either so the assertion
    // doesn't break if the sample's dependency count ever drops below the limit.
    await expect(page.locator('.badge', {hasText: /^(Scan complete|Partial scan)$/})).toBeVisible({
      timeout: 45_000
    })

    const scannerMetric = page.locator('.advisor-summary__metric', {hasText: 'Scanner'})
    await expect(scannerMetric.locator('dd')).toHaveText('OSV.dev')
  })
})
