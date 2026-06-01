// @ts-check
import {expect, test} from './fixtures.js'

const inventoryReport = {
  scanningEnabled: true,
  total: 4,
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
    dependency('org.zeta', 'critical-lib', '1.0.0'),
    dependency('org.example', 'vulnerable-lib', '1.0.0'),
    dependency('org.alpha', 'critical-lib', '1.0.0')
  ]
}

const scannedReport = {
  ...inventoryReport,
  vulnerable: 3,
  severityCounts: [
    {severity: 'CRITICAL', count: 2},
    {severity: 'HIGH', count: 1},
    {severity: 'MEDIUM', count: 0},
    {severity: 'LOW', count: 1},
    {severity: 'UNKNOWN', count: 0}
  ],
  scan: {
    scanner: 'OSV.dev',
    status: 'SCANNED',
    message: 'Scan completed against OSV.dev.',
    scannedAt: Date.now(),
    packagesScanned: 4,
    vulnerabilitiesFound: 4
  },
  dependencies: [
    dependency('org.springframework.boot', 'spring-boot', '4.0.6'),
    {
      ...dependency('org.zeta', 'critical-lib', '1.0.0'),
      vulnerabilityCount: 1,
      highestSeverity: 'CRITICAL',
      vulnerabilities: [vulnerability('GHSA-ZZZZ-0000-0000', 'CRITICAL', 'Critical vulnerability in zeta-lib', '1.0.4')]
    },
    {
      ...dependency('org.example', 'vulnerable-lib', '1.0.0'),
      vulnerabilityCount: 1,
      highestSeverity: 'HIGH',
      vulnerabilities: [
        vulnerability('GHSA-1234-5678-9012', 'HIGH', 'Example vulnerability', '1.0.1', ['CVE-2026-0001'])
      ]
    },
    {
      ...dependency('org.alpha', 'critical-lib', '1.0.0'),
      vulnerabilityCount: 2,
      highestSeverity: 'CRITICAL',
      vulnerabilities: [
        vulnerability('GHSA-ALOW-0000-0000', 'LOW', 'Low vulnerability in alpha-lib', '1.0.3'),
        vulnerability('GHSA-AAAA-0000-0000', 'CRITICAL', 'Critical vulnerability in alpha-lib', '1.0.2')
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
    await expect(page.getByText('4 of 4 dependencies')).toBeVisible()
    await expect(page.getByText('Not scanned yet')).toBeVisible()
    await expect(page.getByText('No vulnerability scan data yet')).toBeVisible()
    await expect(page.getByText('Run Scan with OSV.dev to populate the severity breakdown.')).toBeVisible()

    await page.getByRole('button', {name: 'Scan with OSV.dev'}).click()
    await expect(page.getByText('Scan complete', {exact: true})).toBeVisible()
    await expect(page.getByText('No vulnerability scan data yet')).toHaveCount(0)
    await expect(page.locator('#vulnerableOnly')).toBeChecked()
    await expect(page.getByText('3 of 4 dependencies')).toBeVisible()
    await expect(page.locator('tbody tr td:first-child code')).toHaveText([
      'org.alpha:critical-lib',
      'org.zeta:critical-lib',
      'org.example:vulnerable-lib'
    ])
    await expect(
      page.locator('tbody tr', {hasText: 'org.alpha:critical-lib'}).locator('.vulnerability-list .badge')
    ).toHaveText(['CRITICAL', 'LOW'])
    await expect(page.getByText('GHSA-1234-5678-9012')).toBeVisible()
    await expect(page.getByText('fixed in 1.0.1')).toBeVisible()

    await page.getByPlaceholder('Search group, artifact, or version').fill('vulnerable')
    await expect(page.getByText('1 of 4 dependencies')).toBeVisible()
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

function vulnerability(id, severity, summary, fixedVersion, aliases = []) {
  return {
    id,
    summary,
    details: null,
    severity,
    score: null,
    aliases,
    references: ['https://example.com/advisory'],
    fixedVersions: [fixedVersion]
  }
}
