// @ts-check

const scannerIds = [
  'architecture',
  'memory',
  'rest-api',
  'spring',
  'database-advisor',
  'hibernate',
  'security',
  'pentesting',
  'vulnerabilities',
  'github'
]

function architectureReport(status) {
  return {
    scan: {status, scannedAt: 1700000000000, message: 'Available evidence retained.'},
    severityCounts: [{severity: 'HIGH', count: 1}],
    basePackages: ['example.app'],
    rulesEvaluated: 1,
    violationsFound: 1,
    classesAnalyzed: 2,
    disclaimer: 'Heuristic findings.',
    results: [
      {
        id: 'ARCH-TEST-1',
        name: 'Retained architecture finding',
        description: 'Available evidence.',
        status: 'VIOLATION',
        severity: 'HIGH',
        category: 'TEST',
        violationCount: 1,
        violations: [],
        dismissed: false,
        recommendation: 'Review the finding.'
      }
    ]
  }
}

function vulnerabilityReport(dismissed, coverageStatus = 'COMPLETE') {
  return {
    total: 1,
    vulnerable: dismissed ? 0 : 1,
    scanningEnabled: true,
    scan: {
      status: 'SCANNED',
      scanner: 'OSV.dev',
      message: 'Cached test report.',
      packagesScanned: 1,
      packagesSkipped: 0
    },
    coverage: coverageStatus
      ? {
          status: coverageStatus,
          archivesFound: 1,
          archivesIdentified: 1,
          archivesUnidentified: 0,
          unidentifiedArchives: []
        }
      : null,
    severityCounts: [
      {severity: 'UNKNOWN', count: dismissed ? 0 : 1},
      {severity: 'NONE', count: 0}
    ],
    dependencies: [
      {
        packageName: 'example:library',
        groupId: 'example',
        artifactId: 'library',
        version: '1.0.0',
        source: 'test',
        highestSeverity: dismissed ? 'NONE' : 'UNKNOWN',
        vulnerabilityCount: dismissed ? 0 : 1,
        vulnerabilities: [
          {
            id: 'GHSA-test-unknown',
            summary: 'Retained vulnerability finding',
            severity: 'UNKNOWN',
            dismissed,
            aliases: [],
            references: [],
            fixedVersions: [],
            fixAvailable: false
          }
        ]
      }
    ]
  }
}

/**
 * Run the same report/status transitions against each runtime's real shell, with bounded local fixtures
 * instead of external vulnerability queries or app-specific advisor findings.
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} paths
 */
export function registerAdvisorScoringTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test.describe('Shared advisor scoring eligibility', () => {
    test.beforeEach(async ({page}) => {
      await page.route(`**${apiPath}/panels`, async (route) => {
        const response = await route.fetch()
        const body = await response.json()
        body.panels = body.panels.map((panel) =>
          scannerIds.includes(panel.id)
            ? {...panel, available: ['architecture', 'vulnerabilities'].includes(panel.id)}
            : panel
        )
        await route.fulfill({response, json: body})
      })
    })

    test('retains incomplete findings and removes only their score from the aggregate', async ({page}) => {
      let status = 'SCANNED'
      let scans = 0
      await page.route(`**${apiPath}/architecture{,/scan}`, async (route) => {
        if (route.request().method() === 'POST') scans++
        await route.fulfill({json: architectureReport(status)})
      })
      await page.goto(`${uiPath}/#/overview`)
      const overall = page.locator('.overall-card')
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      await expect(card).toBeVisible()
      expect(scans).toBe(0)
      await card.getByRole('button', {name: 'Run scan', exact: true}).click()
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(overall).toContainText('1 of 2 scanners scored')
      for (const next of ['PARTIAL', 'ERROR', 'DISABLED', 'NOT_SCANNED']) {
        status = next
        await card.getByRole('button', {name: 'Re-run scan', exact: true}).click()
        await expect(card.locator('.scanner-score')).toHaveCount(0)
        await expect(card).toContainText('1 high')
        await expect(overall).toContainText('0 of 2 scanners scored')
        await expect(overall.locator('.overall-gauge')).toHaveCount(0)
      }
      status = 'PARTIAL'
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-score-card')).toContainText('Incomplete')
      await expect(page.getByText('Retained architecture finding', {exact: true})).toBeVisible()
      await expect(page.locator('.advisor-summary__gauge')).toHaveCount(0)
    })

    test('refreshes UNKNOWN dismissal and restore eligibility using only cached report GETs', async ({page}) => {
      let dismissed = false
      let coverageStatus = 'COMPLETE'
      let scans = 0
      await page.route(`**${apiPath}/vulnerabilities{,/scan}`, async (route) => {
        if (route.request().method() === 'POST') scans++
        await route.fulfill({json: vulnerabilityReport(dismissed, coverageStatus)})
      })
      await page.route(`**${apiPath}/dismissed-rules/**`, async (route) => {
        dismissed = route.request().method() === 'POST'
        await route.fulfill({json: {dismissed: dismissed ? ['GHSA-test-unknown::example:library'] : []}})
      })
      await page.goto(`${uiPath}/#/overview`)
      const card = page.locator('.scanner-card').filter({hasText: 'Vulnerabilities'})
      await expect(card).toBeVisible()
      expect(scans).toBe(0)
      await card.getByRole('button', {name: 'Run scan', exact: true}).click()
      await expect(card).toContainText('Active findings have unknown severity')
      await expect(card).toContainText('1 unknown')
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-summary__gauge')).toHaveCount(0)
      await page.getByRole('button', {name: /Dismiss$/}).click()
      await expect(page.locator('.advisor-summary__value')).toHaveText('100')
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card.locator('.scanner-score')).toHaveText('100')
      await expect(page.locator('.overall-card')).toContainText('1 of 2 scanners scored')
      await card.getByRole('link', {name: 'Open panel'}).click()
      await page.getByRole('button', {name: /Restore$/}).click()
      await expect(page.locator('.advisor-summary__gauge')).toHaveCount(0)
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      await expect(card).toContainText('1 unknown')
      await expect(page.locator('.overall-card')).toContainText('0 of 2 scanners scored')
      for (const next of ['INCOMPLETE', 'UNAVAILABLE', '']) {
        dismissed = true
        coverageStatus = next
        await card.getByRole('link', {name: 'Open panel'}).click()
        await expect(page.locator('.advisor-score-card')).toContainText('coverage')
        await expect(page.locator('.advisor-summary__gauge')).toHaveCount(0)
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveCount(0)
      }
      expect(scans).toBe(1)
    })

    for (const [theme, width] of [
      ['light', 1600],
      ['dark', 390]
    ]) {
      test(`keeps incomplete assessments readable in ${theme} at ${width}px`, async ({page}) => {
        await page.setViewportSize({width: Number(width), height: 900})
        await page.emulateMedia({colorScheme: theme === 'dark' ? 'dark' : 'light', reducedMotion: 'reduce'})
        await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: architectureReport('PARTIAL')}))
        await page.goto(`${uiPath}/#/architecture`)
        const summary = page.locator('.advisor-score-card')
        await expect(summary).toContainText('Incomplete')
        await expect(summary).toContainText('no score is calculated')
        await expect(summary.locator('[role="img"]')).toHaveCount(0)
        await expect(page.getByText('Retained architecture finding', {exact: true})).toBeVisible()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      })
    }
  })
}
