// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Vulnerabilities panel lists the local runtime JAR inventory (captured at Quarkus build time from
 * the application model) network-free, then only calls the deterministic loopback OSV fixture when the
 * user clicks Scan. There is no page.route mocking: the browser calls the real Quarkus endpoint, whose
 * OsvVulnerabilityScanner constructs and parses real OSV HTTP payloads. The fixture rejects malformed
 * Maven queries and deliberately returns advisories out of severity order so aggregation and ordering are
 * asserted without making normal CI depend on the public service.
 */
test.describe('Vulnerabilities (Quarkus)', () => {
  test('lists the local dependency inventory and scans it through the deterministic OSV fixture', async ({
    openView,
    page
  }) => {
    await openView('vulnerabilities', 'Vulnerabilities')

    const dependenciesMetric = page.locator('.advisor-summary__metric', {hasText: 'Dependencies'})
    const totalBefore = Number((await dependenciesMetric.locator('dd').textContent())?.trim())
    expect(totalBefore).toBeGreaterThan(3)

    await page.getByRole('button', {name: 'Scan with OSV.dev'}).click()

    // The fixture-backed server is configured with max-packages=3, below the real inventory size.
    await expect(page.getByText('Partial scan', {exact: true})).toBeVisible()
    // A truncated scan must say so rather than letting the result read as a complete one.
    const truncationWarning = page.locator('.alert-warning', {hasText: 'not sent to OSV.dev'})
    await expect(truncationWarning).toBeVisible()
    await expect(truncationWarning).toContainText('bootui.vulnerabilities.max-packages')
    // Quarkus resolves every coordinate from its build-time application model, so coverage is complete.
    const coverageMetric = page.locator('.advisor-summary__metric', {hasText: 'Unidentified JARs'})
    await expect(coverageMetric.locator('dd')).toHaveText('0')
    await expect(page.getByText('could not be identified and were not scanned')).toHaveCount(0)

    const scannerMetric = page.locator('.advisor-summary__metric', {hasText: 'Scanner'})
    await expect(scannerMetric.locator('dd')).toHaveText('OSV.dev')
    await expect(page.locator('[aria-label="CRITICAL vulnerabilities: 1"]')).toBeVisible()
    await expect(page.locator('[aria-label="LOW vulnerabilities: 1"]')).toBeVisible()

    const vulnerableMetric = page.locator('.advisor-summary__metric', {hasText: 'Vulnerable'})
    await expect(vulnerableMetric.locator('dd')).toHaveText('1')
    await expect(page.locator('#vulnerableOnly')).toBeChecked()

    const vulnerableRow = page.locator('tbody tr').filter({hasText: CRITICAL_ADVISORY_ID})
    await expect(vulnerableRow).toBeVisible()
    await expect(vulnerableRow.locator('.vulnerability-list a, .vulnerability-list span.fw-semibold')).toHaveText([
      CRITICAL_ADVISORY_ID,
      LOW_ADVISORY_ID
    ])
    await expect(vulnerableRow.getByText('fixed in 9999.0.0')).toHaveCount(2)
  })
})

const CRITICAL_ADVISORY_ID = 'GHSA-BOOTUI-CRITICAL'
const LOW_ADVISORY_ID = 'GHSA-BOOTUI-LOW'
