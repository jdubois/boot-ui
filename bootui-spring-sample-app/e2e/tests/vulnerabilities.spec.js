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
    packagesSkipped: 0,
    vulnerabilitiesFound: 0
  },
  coverage: coverage(),
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
    packagesSkipped: 0,
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
  test('renders inventory and on-demand vulnerability scan results', async ({openView, page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/vulnerabilities',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify(inventoryReport)
        })
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/vulnerabilities/scan',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify(scannedReport)
        })
      }
    )

    await openView('vulnerabilities', /^Vulnerabilities/)
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

  test('warns that unidentified JARs were not scanned instead of reading as a clean result', async ({
    openView,
    page
  }) => {
    const incompleteReport = {
      ...inventoryReport,
      coverage: {
        status: 'INCOMPLETE',
        archivesFound: 325,
        archivesIdentified: 186,
        archivesUnidentified: 139,
        unidentifiedArchives: ['spring-core-7.0.9.jar', 'tomcat-embed-core-11.0.24.jar'],
        unidentifiedArchivesTruncated: true
      }
    }
    await page.route(
      (url) => url.pathname === '/bootui/api/vulnerabilities',
      async (route) => {
        await route.fulfill({contentType: 'application/json', body: JSON.stringify(incompleteReport)})
      }
    )

    await openView('vulnerabilities', /^Vulnerabilities/)
    await expect(page.getByText('139 of 325 JARs could not be identified and were not scanned')).toBeVisible()
    const coverageMetric = page.locator('.advisor-summary__metric', {hasText: 'Unidentified JARs'})
    await expect(coverageMetric.locator('dd')).toHaveText('139')
    // The archive names stay out of the scannable dependency table until explicitly requested.
    await expect(page.getByText('spring-core-7.0.9.jar')).toHaveCount(0)

    await page.getByRole('button', {name: 'Show unidentified JARs'}).click()
    await expect(page.getByText('spring-core-7.0.9.jar')).toBeVisible()
    await expect(page.getByText('Only the first 2 names are listed.')).toBeVisible()
  })
})

function coverage() {
  return {
    status: 'COMPLETE',
    archivesFound: 4,
    archivesIdentified: 4,
    archivesUnidentified: 0,
    unidentifiedArchives: [],
    unidentifiedArchivesTruncated: false
  }
}

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

function vulnerability(id, severity, summary, fixedVersion, aliases = [], fixAvailable = true) {
  return {
    id,
    summary,
    details: null,
    severity,
    score: null,
    aliases,
    references: ['https://example.com/advisory'],
    fixedVersions: [fixedVersion],
    // Every call site here uses a fixedVersion strictly newer than the mocked dependency's current
    // version, i.e. a genuine upgrade target -- so fixAvailable defaults to true, matching what the
    // real DependencyReports.fixAvailable() derivation would compute for these fixtures. Override it
    // explicitly for a scenario that should render "No newer fixed version reported by OSV" or
    // "No fixed version reported by OSV".
    fixAvailable
  }
}
