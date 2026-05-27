// @ts-check
import {expect, test} from './fixtures.js'

const inventoryReport = {
  scanningEnabled: true,
  total: 2,
  vulnerable: 0,
  severityCounts: [
    {severity: 'CRITICAL', count: 0},
    {severity: 'HIGH', count: 0},
    {severity: 'MEDIUM', count: 0},
    {severity: 'LOW', count: 0},
    {severity: 'UNKNOWN', count: 0}
  ],
  scan: {
    scanner: 'OSV.dev',
    status: 'NOT_SCANNED',
    message: 'Dependency inventory loaded.',
    scannedAt: null,
    packagesScanned: 0,
    vulnerabilitiesFound: 0
  },
  dependencies: [
    dependency('org.springframework.boot', 'spring-boot', '4.0.6'),
    dependency('org.example', 'vulnerable-lib', '1.0.0')
  ]
}

const scannedReport = {
  ...inventoryReport,
  vulnerable: 1,
  severityCounts: [
    {severity: 'CRITICAL', count: 0},
    {severity: 'HIGH', count: 1},
    {severity: 'MEDIUM', count: 0},
    {severity: 'LOW', count: 0},
    {severity: 'UNKNOWN', count: 0}
  ],
  scan: {
    scanner: 'OSV.dev',
    status: 'SCANNED',
    message: 'Scan completed against OSV.dev.',
    scannedAt: Date.now(),
    packagesScanned: 2,
    vulnerabilitiesFound: 1
  },
  dependencies: [
    dependency('org.springframework.boot', 'spring-boot', '4.0.6'),
    {
      ...dependency('org.example', 'vulnerable-lib', '1.0.0'),
      vulnerabilityCount: 1,
      highestSeverity: 'HIGH',
      vulnerabilities: [
        {
          id: 'GHSA-1234-5678-9012',
          summary: 'Example vulnerability',
          details: null,
          severity: 'HIGH',
          score: null,
          aliases: ['CVE-2026-0001'],
          references: ['https://example.com/advisory'],
          fixedVersions: ['1.0.1']
        }
      ]
    }
  ]
}

test.describe('Vulnerabilities view', () => {
  test('renders inventory and on-demand vulnerability scan results', async ({page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/dependencies',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify(inventoryReport)
        })
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/dependencies/scan',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify(scannedReport)
        })
      }
    )

    await page.goto('/bootui/#/vulnerabilities')
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /^Vulnerabilities/})
        .first()
    ).toBeVisible()
    await expect(page.getByText('2 of 2 dependencies')).toBeVisible()
    await expect(page.getByText('NOT_SCANNED')).toBeVisible()
    await expect(page.getByText('No vulnerability scan data yet')).toBeVisible()
    await expect(page.getByText('Run Scan with OSV.dev to populate the severity breakdown.')).toBeVisible()

    await page.getByRole('button', {name: 'Scan with OSV.dev'}).click()
    await expect(page.getByText('SCANNED')).toBeVisible()
    await expect(page.getByText('No vulnerability scan data yet')).toHaveCount(0)
    await expect(page.locator('#vulnerableOnly')).toBeChecked()
    await expect(page.getByText('1 of 2 dependencies')).toBeVisible()
    await expect(page.getByText('GHSA-1234-5678-9012')).toBeVisible()
    await expect(page.getByText('fixed in 1.0.1')).toBeVisible()

    await page.getByPlaceholder('Search group, artifact, or version').fill('vulnerable')
    await expect(page.getByText('1 of 2 dependencies')).toBeVisible()
    await expect(page.locator('tbody tr', {hasText: 'org.example:vulnerable-lib'})).toBeVisible()
  })
})

function dependency(groupId, artifactId, version) {
  return {
    groupId,
    artifactId,
    version,
    packageName: `${groupId}:${artifactId}`,
    source: 'test',
    vulnerabilityCount: 0,
    highestSeverity: 'NONE',
    vulnerabilities: []
  }
}
