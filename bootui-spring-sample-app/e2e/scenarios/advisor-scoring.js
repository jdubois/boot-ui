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

export function expectedAdvisorScore(counts) {
  const weights = {CRITICAL: 25, HIGH: 10, MEDIUM: 3, LOW: 1, INFO: 0, NONE: 0}
  return Math.max(0, 100 - counts.reduce((penalty, entry) => penalty + (weights[entry.severity] || 0) * entry.count, 0))
}

export async function expectPartialAdvisorScore(page, expect, counts = null) {
  await expect(page.locator('.advisor-score-card')).toContainText('Partial assessment')
  await expect(page.locator('.advisor-score-card')).toContainText('missing checks are not passes')
  await expect(page.locator('.advisor-summary__gauge')).toHaveAttribute('aria-label', /Partial assessment/)
  await expect(page.locator('.advisor-summary__value')).toHaveText(
    counts ? String(expectedAdvisorScore(counts)) : /^(?:100|[1-9]?[0-9])$/
  )
}

function architectureReport(status, dismissed = false) {
  return {
    scan: {status, scannedAt: 1700000000000, message: 'Available evidence retained.'},
    severityCounts: [{severity: 'HIGH', count: dismissed ? 0 : 1}],
    basePackages: ['example.app'],
    rulesEvaluated: 1,
    assessmentEvidence: {usable: true, incomplete: status === 'PARTIAL'},
    violationsFound: dismissed ? 0 : 1,
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
        dismissed,
        recommendation: 'Review the finding.'
      }
    ]
  }
}

function unscannedReport() {
  return {
    ...architectureReport('NOT_SCANNED'),
    scan: {status: 'NOT_SCANNED', scannedAt: null},
    severityCounts: [],
    results: [],
    violationsFound: 0,
    rulesEvaluated: 0,
    assessmentEvidence: {usable: false, incomplete: true}
  }
}

/** @param {import('@playwright/test').Page} page */
export async function stubUnscannedAdvisorReports(page, apiPath = '/bootui/api') {
  for (const id of scannerIds.filter((id) => id !== 'github')) {
    await page.route(`**${apiPath}/${id}`, (route) => route.fulfill({json: unscannedReport()}))
  }
}

function hibernateReport() {
  return {
    ...architectureReport('PARTIAL'),
    entityPackages: ['example.app'],
    entitiesAnalyzed: 12,
    rulesEvaluated: 70,
    results: [
      {
        ...architectureReport('PARTIAL').results[0],
        id: 'HIB-TEST-1',
        name: 'Retained Hibernate finding'
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
        assessmentComplete: true,
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
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: unscannedReport()}))
      await page.route(`**${apiPath}/vulnerabilities`, (route) =>
        route.fulfill({
          json: {
            ...vulnerabilityReport(false),
            scan: {status: 'NOT_SCANNED'},
            severityCounts: [],
            dependencies: []
          }
        })
      )
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

    for (const initialOverview of [false, true]) {
      test(`discovers a Hibernate panel scan on ${initialOverview ? 'return' : 'first'} Overview navigation`, async ({
        page
      }) => {
        let scanned = false
        let reads = 0
        const writes = []
        page.on('request', (request) => {
          if (request.method() === 'POST') writes.push(new URL(request.url()).pathname)
        })
        await page.route(`**${apiPath}/panels`, async (route) => {
          const response = await route.fetch()
          const body = await response.json()
          body.panels = body.panels.map((panel) =>
            scannerIds.includes(panel.id) ? {...panel, available: panel.id === 'hibernate', enabled: true} : panel
          )
          await route.fulfill({response, json: body})
        })
        await page.route(`**${apiPath}/hibernate{,/scan}`, async (route) => {
          if (route.request().method() === 'POST') scanned = true
          else reads++
          await route.fulfill({json: scanned ? hibernateReport() : unscannedReport()})
        })
        const card = page.locator('.scanner-card').filter({hasText: 'Hibernate'})
        if (initialOverview) {
          await page.goto(`${uiPath}/#/overview`)
          await expect(card).toContainText('Not scanned')
          await expect(card.getByRole('button', {name: 'Run scan', exact: true})).toBeVisible()
          expect(reads).toBe(1)
          expect(writes).toEqual([])
          await card.getByRole('link', {name: 'Open panel'}).click()
        } else {
          await page.goto(`${uiPath}/#/hibernate`)
        }
        await page.getByRole('button', {name: 'Run Hibernate checks', exact: true}).click()
        await expect(page.getByText('Retained Hibernate finding', {exact: true})).toBeVisible()
        await expect(page.locator('.advisor-score-card')).toContainText('Partial assessment')
        await expect(page.locator('.advisor-summary__value')).toHaveText('90')
        const readsBeforeReturn = reads
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card).toContainText('Incomplete')
        await expect(card).toContainText('1 high')
        await expect(card.locator('.scanner-score')).toHaveText('90')
        await expect(page.locator('.overall-card')).toContainText('Partial assessment')
        expect(reads).toBe(readsBeforeReturn + 1)
        expect(writes).toEqual([`${apiPath}/hibernate/scan`])
        // A restarted server explicitly reports no scan; old findings must disappear.
        scanned = false
        await card.getByRole('link', {name: 'Open panel'}).click()
        await expect(page.locator('.advisor-score-card')).toContainText('Not scanned')
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card).toContainText('Not scanned')
        await expect(card).not.toContainText('1 high')
        await expect(card.getByRole('button', {name: 'Run scan', exact: true})).toBeVisible()
        expect(writes).toEqual([`${apiPath}/hibernate/scan`])
      })
    }

    test('waits for availability and reads only enabled advisors on a direct Overview load', async ({page}) => {
      let releaseManifest
      const manifestGate = new Promise((resolve) => {
        releaseManifest = resolve
      })
      const advisorRequests = []
      let manifests = 0
      await page.route(`**${apiPath}/panels`, async (route) => {
        manifests++
        const response = await route.fetch()
        const body = await response.json()
        await manifestGate
        body.panels = body.panels.map((panel) =>
          scannerIds.includes(panel.id)
            ? {...panel, available: ['architecture', 'hibernate'].includes(panel.id), enabled: panel.id !== 'hibernate'}
            : panel
        )
        await route.fulfill({response, json: body})
      })
      page.on('request', (request) => {
        const path = new URL(request.url()).pathname
        if (scannerIds.some((id) => path === `${apiPath}/${id}` || path.startsWith(`${apiPath}/${id}/`)))
          advisorRequests.push({path, method: request.method()})
      })
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: architectureReport('SCANNED')}))
      await page.goto(`${uiPath}/#/overview`)
      await expect(page.locator('.panel-header')).toContainText('Run the advisors')
      expect(advisorRequests).toEqual([])
      releaseManifest()
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(page.locator('.scanner-card')).toHaveCount(1)
      expect(manifests).toBe(1)
      expect(advisorRequests).toEqual([{path: `${apiPath}/architecture`, method: 'GET'}])
    })

    test('defers the return refresh until an in-flight first scan completes', async ({page}) => {
      let releaseScan
      const scanGate = new Promise((resolve) => {
        releaseScan = resolve
      })
      let cached = unscannedReport()
      let reads = 0
      let scans = 0
      await page.route(`**${apiPath}/architecture{,/scan}`, async (route) => {
        if (route.request().method() === 'POST') {
          scans++
          await scanGate
          cached = architectureReport('PARTIAL')
        } else reads++
        await route.fulfill({json: cached})
      })
      await page.goto(`${uiPath}/#/overview`)
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      await expect(card).toContainText('Not scanned')
      await card.getByRole('button', {name: 'Run scan', exact: true}).click()
      await expect(card).toContainText('Scanning')
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-score-card')).toContainText('Not scanned')
      expect(reads).toBe(2)
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card).toContainText('Scanning')
      expect(reads).toBe(2)
      releaseScan()
      await expect(card).toContainText('Incomplete')
      await expect(card).toContainText('1 high')
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(page.locator('.overall-card')).toContainText('Partial assessment')
      await expect.poll(() => reads).toBe(3)
      expect(scans).toBe(1)
    })

    test('includes usable partial findings and removes only unusable scores from the aggregate', async ({page}) => {
      let status = 'NOT_SCANNED'
      let scans = 0
      await page.route(`**${apiPath}/architecture{,/scan}`, async (route) => {
        if (route.request().method() === 'POST') {
          scans++
          if (scans === 1) status = 'SCANNED'
        }
        await route.fulfill({json: scans === 0 ? unscannedReport() : architectureReport(status)})
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
        if (next === 'PARTIAL') {
          await expect(card.locator('.scanner-score')).toHaveText('90')
          await expect(overall).toContainText('Partial assessment')
        } else await expect(card.locator('.scanner-score')).toHaveCount(0)
        await expect(card).toContainText('1 high')
        await expect(overall).toContainText(`${next === 'PARTIAL' ? 1 : 0} of 2 scanners scored`)
        await expect(overall.locator('.overall-gauge')).toHaveCount(next === 'PARTIAL' ? 1 : 0)
      }
      status = 'PARTIAL'
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-score-card')).toContainText('Partial assessment')
      await expect(page.getByText('Retained architecture finding', {exact: true})).toBeVisible()
      await expect(page.locator('.advisor-summary__value')).toHaveText('90')
    })

    for (const status of ['SCANNED', 'PARTIAL']) {
      test(`dismisses and restores a ${status} report finding with exact score changes`, async ({page}) => {
        let dismissed = false
        let scans = 0
        await page.route(`**${apiPath}/architecture{,/scan}`, async (route) => {
          if (route.request().method() === 'POST') scans++
          await route.fulfill({json: architectureReport(status, dismissed)})
        })
        await page.route(`**${apiPath}/dismissed-rules/ARCH-TEST-1`, async (route) => {
          expect(['POST', 'DELETE']).toContain(route.request().method())
          dismissed = route.request().method() === 'POST'
          await route.fulfill({json: {dismissed: dismissed ? ['ARCH-TEST-1'] : []}})
        })
        await page.goto(`${uiPath}/#/architecture`)
        await expect(page.locator('.advisor-summary__value')).toHaveText('90')
        const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveText('90')
        await card.getByRole('link', {name: 'Open panel'}).click()
        await page.getByRole('button', {name: /Dismiss$/}).click()
        await expect(page.locator('.list-group-item.opacity-50')).toContainText('ARCH-TEST-1')
        await expect(page.locator('.advisor-summary__dismissed')).toContainText(
          '1 dismissed rule(s) excluded from this score'
        )
        await expect(page.locator('.advisor-summary__value')).toHaveText('100')
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveText('100')
        await card.getByRole('link', {name: 'Open panel'}).click()
        await page.getByRole('button', {name: /Restore$/}).click()
        await expect(page.locator('.list-group-item.opacity-50')).toHaveCount(0)
        await expect(page.locator('.advisor-summary__dismissed')).toHaveCount(0)
        await expect(page.locator('.advisor-summary__value')).toHaveText('90')
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveText('90')
        if (status === 'PARTIAL') await expect(page.locator('.overall-card')).toContainText('Partial assessment')
        expect(scans).toBe(0)
      })
    }

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
      await expect(card).toContainText('active finding(s) have unknown severity')
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
        await expect(page.locator('.advisor-summary__value')).toHaveText('100')
        await expect(page.locator('.advisor-score-card')).toContainText('Partial assessment')
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveText('100')
        await expect(page.locator('.overall-card')).toContainText('Partial assessment')
      }
      expect(scans).toBe(0)
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
        await expect(summary).toContainText('Partial assessment')
        await expect(summary).toContainText('missing checks are not passes')
        await expect(summary.getByRole('img', {name: /90 out of 100.*Partial assessment/})).toBeVisible()
        await expect(page.getByText('Retained architecture finding', {exact: true})).toBeVisible()
        if (Number(width) < 992) await page.getByRole('button', {name: 'Open navigation menu'}).click()
        await page.locator('a[href$="#/overview"]').first().click()
        const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
        await expect(card).toContainText('Incomplete')
        await expect(card).toContainText('1 high')
        await expect(card.locator('.scanner-score')).toHaveText('90')
        await expect(page.locator('.overall-card')).toContainText('Partial assessment')
        await expect(
          page.locator('.overall-card').getByRole('img', {name: /90 out of 100.*Partial assessment/})
        ).toBeVisible()
        const gauge = await page.locator('.overall-gauge').boundingBox()
        expect(Math.abs(gauge.width - gauge.height)).toBeLessThan(1)
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      })
    }

    for (const [name, status, usable, counts, expected] of [
      ['completed checks without findings', 'SCANNED', true, [], '100'],
      ['partial checks without findings', 'PARTIAL', true, [], '100'],
      ['zero evaluated checks', 'SCANNED', false, [], null],
      ['all skipped checks', 'PARTIAL', false, [], null],
      ['wholly failed checks', 'ERROR', false, [{severity: 'HIGH', count: 1}], null],
      ['disabled advisor', 'DISABLED', false, [{severity: 'HIGH', count: 1}], null],
      ['invalid counts', 'PARTIAL', true, [{severity: 'HIGH', count: -1}], null]
    ]) {
      test(`applies shared evidence policy to ${name} on cached Overview load`, async ({page}) => {
        const writes = []
        page.on('request', (request) => {
          if (request.method() !== 'GET') writes.push(request.method())
        })
        await page.route(`**${apiPath}/architecture`, (route) =>
          route.fulfill({
            json: {
              ...architectureReport(String(status)),
              severityCounts: counts,
              results: [],
              violationsFound: 0,
              rulesEvaluated: name === 'zero evaluated checks' ? 0 : 1,
              assessmentEvidence: {usable, incomplete: status === 'PARTIAL'}
            }
          })
        )
        await page.goto(`${uiPath}/#/overview`)
        const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
        await expect(card).toBeVisible()
        if (expected) {
          await expect(card.locator('.scanner-score')).toHaveText(String(expected))
          if (status === 'PARTIAL') {
            await expect(card).toContainText('No findings in evaluated evidence')
            await expect(page.locator('.overall-card')).toContainText('Partial assessment')
          }
        } else {
          await expect(card.locator('.scanner-score')).toHaveCount(0)
          await expect(card).toContainText('Not scored')
          await expect(page.locator('.overall-card')).toContainText('0 of 2 scanners scored')
        }
        expect(writes).toEqual([])
      })
    }

    test('scores known vulnerability evidence without treating UNKNOWN or incomplete inventory as safe', async ({
      page
    }) => {
      const report = vulnerabilityReport(false, 'INCOMPLETE')
      const known = {...report.dependencies[0].vulnerabilities[0], id: 'GHSA-test-known', severity: 'HIGH'}
      report.dependencies[0].vulnerabilities.push(known)
      report.dependencies[0].vulnerabilityCount = 2
      report.dependencies[0].highestSeverity = 'HIGH'
      report.severityCounts.push({severity: 'HIGH', count: 1})
      const writes = []
      page.on('request', (request) => {
        if (request.method() !== 'GET') writes.push(request.method())
      })
      await page.route(`**${apiPath}/vulnerabilities`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/vulnerabilities`)
      await expect(page.locator('.advisor-summary__value')).toHaveText('90')
      await expect(page.locator('.advisor-score-card')).toContainText('Partial assessment')
      await expect(page.locator('.advisor-score-card')).toContainText('not included in the score or considered safe')
      await expect(page.getByText('GHSA-test-unknown', {exact: true})).toBeVisible()
      await expect(page.getByText('GHSA-test-known', {exact: true})).toBeVisible()
      await page.locator('a[href$="#/overview"]').first().click()
      const card = page.locator('.scanner-card').filter({hasText: 'Vulnerabilities'})
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(card).toContainText('1 high')
      await expect(card).toContainText('1 unknown')
      await expect(page.locator('.overall-card')).toContainText('Partial assessment')
      expect(writes).toEqual([])
    })

    test('uses the exact mean of complete and partial contributions while qualifying the aggregate', async ({page}) => {
      const report = vulnerabilityReport(false, 'INCOMPLETE')
      report.dependencies[0].vulnerabilities = [1, 2, 3].map((index) => ({
        ...report.dependencies[0].vulnerabilities[0],
        id: `GHSA-test-high-${index}`,
        severity: 'HIGH'
      }))
      report.dependencies[0].vulnerabilityCount = 3
      report.dependencies[0].highestSeverity = 'HIGH'
      report.severityCounts = [{severity: 'HIGH', count: 3}]
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: architectureReport('SCANNED')}))
      await page.route(`**${apiPath}/vulnerabilities`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/overview`)
      const overall = page.locator('.overall-card')
      await expect(overall.locator('.overall-gauge__value')).toHaveText('80')
      await expect(overall).toContainText('2 of 2 scanners scored')
      await expect(overall).toContainText('Partial assessment')
      await expect(overall.getByRole('img')).toHaveAttribute('aria-label', /80 out of 100.*Partial assessment/)
      await expect(
        page.locator('.scanner-card').filter({hasText: 'Architecture'}).locator('.scanner-score')
      ).toHaveText('90')
      await expect(
        page.locator('.scanner-card').filter({hasText: 'Vulnerabilities'}).locator('.scanner-score')
      ).toHaveText('70')
    })
  })
}
