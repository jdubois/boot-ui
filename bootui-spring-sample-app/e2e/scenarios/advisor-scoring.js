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

export function expectedAdvisorScore(report) {
  const weights = {CRITICAL: 25, HIGH: 10, MEDIUM: 3, LOW: 1, INFO: 0, NONE: 0, UNKNOWN: 0}
  return Math.max(
    0,
    Math.min(
      100,
      Math.round(100 - report.severityCounts.reduce((sum, entry) => sum + weights[entry.severity] * entry.count, 0))
    )
  )
}

function architectureReport(status, dismissed = false) {
  return {
    scan: {status, scannedAt: 1700000000000, message: 'Available evidence retained.'},
    evidence: {
      usable: true,
      coverageComplete: status === 'SCANNED',
      limitations: status === 'PARTIAL' ? ['Some application metadata could not be inspected.'] : []
    },
    severityCounts: [{severity: 'HIGH', count: dismissed ? 0 : 1}],
    basePackages: ['example.app'],
    rulesEvaluated: 1,
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
    rulesEvaluated: 0
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
    rulesEvaluated: 71,
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
    evidence: {
      usable: false,
      coverageComplete: false,
      limitations: [
        'Findings with unknown severity are excluded from score penalties.',
        ...(coverageStatus === 'COMPLETE' ? [] : ['Dependency inventory coverage is incomplete or unavailable.'])
      ]
    },
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
        assessment: {queryComplete: true, detailAssessmentComplete: true},
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
  test('keeps Overview unscored after only GraalVM and CRaC readiness scans', async ({page}) => {
    await stubUnscannedAdvisorReports(page, apiPath)
    const readinessScans = []
    await page.route(`**${apiPath}/panels`, async (route) => {
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      const body = await response.json()
      body.panels = body.panels.map((panel) =>
        ['graalvm', 'crac'].includes(panel.id) ? {...panel, available: true, enabled: true, readOnly: false} : panel
      )
      await route.fulfill({response, json: body})
    })
    for (const id of ['graalvm', 'crac']) {
      await page.route(`**${apiPath}/${id}{,/scan,/scan?*}`, (route) => {
        const scanned = route.request().method() === 'POST'
        if (scanned) readinessScans.push(id)
        return route.fulfill({
          json: {
            scan: {status: scanned ? 'SCANNED' : 'NOT_SCANNED'},
            checksRun: scanned ? 4 : 0,
            classesAnalyzed: scanned ? 10 : 0,
            severityCounts: scanned ? [{severity: 'CRITICAL', count: 4}] : [],
            findings: []
          }
        })
      })
    }
    await page.goto(`${uiPath}/#/overview`)
    await expect(page.locator('.overall-card').getByText('Not scored', {exact: true})).toBeVisible()
    for (const id of ['graalvm', 'crac']) {
      await page.evaluate((panel) => (window.location.hash = `#/${panel}`), id)
      const completed = page.waitForResponse(
        (response) => response.request().method() === 'POST' && response.url().includes(`${apiPath}/${id}/scan`)
      )
      await page.getByRole('button', {name: 'Run readiness checks', exact: true}).click()
      expect((await completed).ok()).toBe(true)
      await expect(page.getByText('No readiness data yet')).toHaveCount(0)
      await page.getByRole('link', {name: 'Overview', exact: true}).click()
      const summary = page.locator('.overall-card')
      await expect(summary.getByText('Not scored', {exact: true})).toBeVisible()
      await expect(summary.locator('.overall-gauge, .overall-band, .overall-contributions')).toHaveCount(0)
      await expect(summary).not.toContainText('At risk')
    }
    expect(readinessScans).toEqual(['graalvm', 'crac'])
  })

  async function showAdvisors(page, ...ids) {
    await page.route(`**${apiPath}/panels`, async (route) => {
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      const body = await response.json()
      body.panels = body.panels.map((panel) =>
        scannerIds.includes(panel.id) ? {...panel, available: ids.includes(panel.id), enabled: true} : panel
      )
      await route.fulfill({response, json: body})
    })
  }

  async function expectOverallGauge(overall, score, count) {
    const gauge = overall.getByRole('img', {
      name: `Overall score: ${score} out of 100 — Average of ${count} ${count === 1 ? 'score' : 'scores'}`,
      exact: true
    })
    await expect(gauge).toHaveCount(1)
    await expect(overall.getByRole('img')).toHaveCount(1)
    await expect(gauge).toHaveClass(/\boverall-gauge--success\b/)
    await expect(gauge).toHaveCSS('border-radius', '50%')
    const geometry = await gauge.evaluate((element) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return {
        width: rect.width,
        height: rect.height,
        border: parseFloat(style.borderTopWidth),
        color: style.borderTopColor
      }
    })
    expect(geometry.width).toBeGreaterThanOrEqual(90)
    expect(Math.abs(geometry.width - geometry.height)).toBeLessThan(1)
    expect(geometry.border).toBeGreaterThan(0)
    expect(geometry.color).not.toBe('rgba(0, 0, 0, 0)')
    await expect(overall.getByText('Good', {exact: true})).toHaveCount(1)
    await expect(overall.getByText('Points deducted per score', {exact: true})).toBeVisible()
  }

  async function expectCollapsedScanNotes(summary, reason) {
    const notes = summary.locator('details.advisor-summary__notes')
    await expect(notes).toHaveCount(1)
    await expect(notes).toHaveJSProperty('open', false)
    await expect(notes.locator('summary')).toHaveText('Scan notes')
    await expect(notes.locator('p')).toContainText(reason)
    await expect(notes.locator('p')).not.toBeVisible()
    await expect(summary.locator('.advisor-summary__assessment')).toHaveCount(0)
    await expect(summary).not.toContainText('Partial assessment')
    return notes
  }

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
      await showAdvisors(page, 'architecture', 'vulnerabilities')
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
        await showAdvisors(page, 'hibernate')
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
        await expect(page.locator('.advisor-score-card')).toContainText('Results available')
        const readsBeforeReturn = reads
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card).toContainText('Scan complete')
        await expect(card).toContainText('1 high')
        await expect(card.locator('.scanner-score')).toHaveText('90')
        const scoreFontSize = await card
          .locator('.scanner-score')
          .evaluate((el) => window.getComputedStyle(el).fontSize)
        const scoreSizePx = parseFloat(scoreFontSize)
        expect(scoreSizePx).toBeGreaterThanOrEqual(32)
        const scoreBox = await card.locator('.scanner-score').boundingBox()
        const severityBadges = await card.getByText('1 high', {exact: true}).boundingBox()
        expect(scoreBox).toBeTruthy()
        expect(severityBadges).toBeTruthy()
        expect(scoreBox.y + scoreBox.height).toBeLessThanOrEqual(severityBadges.y)
        // Regression: no redundant note sentence visible
        await expect(card).not.toContainText('Scan notes available in panel')
        await expect(card).not.toContainText('Partial assessment')
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

    for (const theme of ['light', 'dark']) {
      test(`keeps unscored advisor cards compact and opens their details in ${theme}`, async ({page}, testInfo) => {
        await page.addInitScript((value) => localStorage.setItem('bootui.theme', value), theme)
        await page.setViewportSize({width: theme === 'dark' ? 390 : 1600, height: 900})
        const ids = scannerIds.filter((id) => id !== 'github')
        const limitations = Array.from(
          {length: 20},
          (_, index) => `Required security observation ${index + 1} could not be inspected in this runtime.`
        )
        const report = {
          ...architectureReport('PARTIAL'),
          scan: {status: 'PARTIAL', scannedAt: 1700000000000, message: 'Security metadata is unavailable.'},
          evidence: {usable: false, coverageComplete: false, limitations},
          severityCounts: [],
          results: [],
          violationsFound: 0,
          rulesEvaluated: 0,
          filterChainsAnalyzed: 0,
          filterChains: []
        }
        const scans = []
        await showAdvisors(page, ...ids)
        for (const id of ids) {
          await page.route(`**${apiPath}/${id}{,/scan}`, (route) => {
            if (route.request().method() === 'POST') scans.push(id)
            return route.fulfill({json: scans.includes(id) ? report : unscannedReport()})
          })
        }
        await page.goto(`${uiPath}/#/overview`)
        const cards = page.locator('.scanner-card')
        await expect(cards).toHaveCount(ids.length)
        expect(scans).toEqual([])
        await page.getByRole('button', {name: 'Run all scanners', exact: true}).click()
        await expect(page.locator('.overall-card')).toContainText('9 of 9 advisors assessed')
        await expect(page.locator('.overall-card').getByRole('img')).toHaveCount(0)
        await expect(page.locator('.assessment-summary')).toContainText('9 advisors have scan notes')
        expect([...scans].sort()).toEqual([...ids].sort())
        for (const card of await cards.all()) {
          await expect(card.locator('.scanner-status')).toHaveText('Incomplete')
          await expect(card.locator('.scanner-assessment')).toHaveText('Not scored')
          await expect(card.locator('.scanner-score')).toHaveCount(0)
          await expect(card).not.toContainText('No usable assessment evidence')
          await expect(card).not.toContainText(report.scan.message)
          for (const reason of limitations) await expect(card).not.toContainText(reason)
          const geometry = await card.evaluate((element) => {
            const label = element.querySelector('.scanner-assessment')
            const style = getComputedStyle(label)
            return {
              height: element.getBoundingClientRect().height,
              labelHeight: label.getBoundingClientRect().height,
              fontSize: parseFloat(style.fontSize),
              fontWeight: Number(style.fontWeight),
              overflow: element.scrollWidth > element.clientWidth
            }
          })
          expect(geometry.height).toBeLessThan(260)
          expect(geometry.labelHeight).toBeLessThan(30)
          expect(geometry.fontSize).toBeLessThanOrEqual(18)
          expect(geometry.fontWeight).toBeGreaterThanOrEqual(600)
          expect(geometry.overflow).toBe(false)
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
        await page.evaluate(() => {
          window.scrollTo(0, 0)
          document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
        })
        await page.screenshot({path: testInfo.outputPath(`overview-unscored-${theme}.png`), animations: 'disabled'})
        const security = cards.filter({hasText: 'Security'})
        await security.screenshot({path: testInfo.outputPath(`security-unscored-${theme}.png`), animations: 'disabled'})
        const open = security.getByRole('link', {name: 'Open panel: Security', exact: true})
        await expect(open).toHaveCount(1)
        await expect(open).toHaveAttribute('href', new RegExp(`#/security$`))
        await security.getByRole('button', {name: 'Re-run scan', exact: true}).focus()
        await page.keyboard.press('Tab')
        await expect(open).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(new RegExp(`#/security$`))
        const assessment = page.locator('.advisor-summary__assessment')
        await expect(assessment.getByText('Not scored', {exact: true})).toBeVisible()
        for (const reason of limitations) await expect(assessment).toContainText(reason)
        await expect(assessment).toContainText(report.scan.message)
        await expect(assessment).toBeVisible()
        expect(scans).toHaveLength(ids.length)
      })

      test(`restores individual score colors in ${theme}`, async ({page}, testInfo) => {
        await page.addInitScript((value) => localStorage.setItem('bootui.theme', value), theme)
        await page.setViewportSize({width: theme === 'dark' ? 390 : 1600, height: 900})
        await showAdvisors(page, 'architecture', 'memory', 'rest-api', 'github')
        const scores = [
          ['architecture', 'Architecture', 80, 'success'],
          ['memory', 'Memory', 50, 'warning'],
          ['rest-api', 'REST API', 49, 'danger']
        ]
        for (const [id, , score] of scores) {
          const report = architectureReport('PARTIAL')
          report.severityCounts = [{severity: 'LOW', count: 100 - Number(score)}]
          await page.route(`**${apiPath}/${id}`, (route) => route.fulfill({json: report}))
        }
        await page.route(`**${apiPath}/github/refresh`, (route) =>
          route.fulfill({
            json: {
              available: true,
              connected: true,
              credential: {authenticated: true},
              status: 'CONNECTED',
              securitySignals: ['Dependabot alerts', 'Code scanning alerts', 'Secret scanning alerts'].map(
                (label, index) => ({
                  label,
                  status: 'AVAILABLE',
                  count: index === 0 ? 5 : 0
                })
              )
            }
          })
        )
        await page.goto(`${uiPath}/#/overview`)
        await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)
        await page
          .locator('.scanner-card')
          .filter({hasText: 'GitHub'})
          .getByRole('button', {name: /Connect to GitHub$/})
          .click()
        for (const [, title, score, tone] of [...scores, ['github', 'GitHub', 50, 'warning']]) {
          const card = page.locator('.scanner-card').filter({hasText: String(title)})
          const number = card.locator('.scanner-score')
          await expect(number).toHaveText(String(score))
          await expect(number).toHaveClass(new RegExp(`\\btext-${tone}-emphasis\\b`))
          await expect(card.locator('.scanner-status')).toHaveText(title === 'GitHub' ? 'Connected' : 'Scan complete')
          if (theme === 'light') {
            await expect(number).toHaveCSS(
              'color',
              {success: 'rgb(25, 135, 84)', warning: 'rgb(153, 116, 4)', danger: 'rgb(220, 53, 69)'}[tone]
            )
          }
          const contrast = await number.evaluate((element) => {
            const luminance = (color) =>
              color
                .match(/[\d.]+/g)
                .slice(0, 3)
                .map(Number)
                .map((v) => {
                  v /= 255
                  return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
                })
                .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0)
            const foreground = luminance(getComputedStyle(element).color)
            const background = luminance(getComputedStyle(element.closest('.card')).backgroundColor)
            return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
          })
          expect(contrast).toBeGreaterThanOrEqual(3)
        }
        const gauge = page.locator('.overall-gauge')
        await expect(gauge).toHaveCSS('border-top-width', '8px')
        if (theme === 'light') {
          await expect(gauge).toHaveCSS('color', 'rgb(153, 116, 4)')
          await expect(gauge).toHaveCSS('border-top-color', 'rgb(153, 116, 4)')
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
        await page.evaluate(() => {
          window.scrollTo(0, 0)
          document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
        })
        await page.screenshot({path: testInfo.outputPath(`overview-score-colors-${theme}.png`), animations: 'disabled'})
      })
    }

    test('scores only complete GitHub signals and keeps unknown counts outside the average', async ({page}) => {
      await showAdvisors(page, 'github')
      let refreshes = 0
      await page.route(`**${apiPath}/github/refresh`, (route) => {
        refreshes++
        return route.fulfill({
          json: {
            available: true,
            connected: true,
            credential: {authenticated: true},
            status: 'CONNECTED',
            securitySignals:
              refreshes === 4
                ? []
                : [
                    {label: 'Dependabot alerts', status: 'AVAILABLE', count: refreshes === 3 ? 0 : 3},
                    {label: 'Secret scanning alerts', status: refreshes === 3 ? 'AVAILABLE' : 'UNAVAILABLE', count: 0},
                    {label: 'Code scanning alerts', status: 'AVAILABLE', count: refreshes === 1 ? null : 0}
                  ]
          }
        })
      })
      await page.goto(`${uiPath}/#/overview`)
      const card = page.locator('.scanner-card').filter({hasText: 'GitHub'})
      await expect(card).toBeVisible()
      expect(refreshes).toBe(0)
      await expect(page.locator('.overall-card')).toContainText('0 of 0 advisors assessed')
      await page.getByRole('button', {name: 'Run all scanners', exact: true}).click()
      await expect(card).toContainText('Connected · Authenticated')
      await expect(card).toContainText('Dependabot alerts: 3 open')
      await expect(card).toContainText('Secret scanning alerts: Unavailable')
      await expect(card).toContainText('Code scanning alerts: Unavailable')
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      await expect(page.locator('.overall-card').getByRole('img')).toHaveCount(0)
      await expect(card).not.toContainText('Good')
      await card.getByRole('button', {name: /Refresh$/}).click()
      await expect(card).toContainText('Code scanning alerts: 0 open')
      await expect(card).toContainText('Secret scanning alerts: Unavailable')
      expect(refreshes).toBe(2)
      await expect(page.locator('.overall-card').getByRole('img')).toHaveCount(0)
      await card.getByRole('button', {name: /Refresh$/}).click()
      await expect(
        card.getByRole('img', {name: 'GitHub security-alert score: 100 out of 100', exact: true})
      ).toHaveCount(1)
      await expect(
        page
          .locator('.overall-card')
          .getByRole('img', {name: 'Overall score: 100 out of 100 — Average of 1 score', exact: true})
      ).toHaveCount(1)
      await card.getByRole('button', {name: /Refresh$/}).click()
      await expect(card).toContainText('Security signals unavailable.')
      await expect(card.getByRole('img')).toHaveCount(0)
      await expect(page.locator('.overall-card').getByRole('img')).toHaveCount(0)
      expect(refreshes).toBe(4)
    })

    for (const [theme, width] of [
      ['light', 1600],
      ['dark', 390]
    ]) {
      test(`scores retained Pentesting findings with limited evidence in ${theme} at ${width}px`, async ({
        page
      }, testInfo) => {
        await page.setViewportSize({width: Number(width), height: 900})
        await page.emulateMedia({colorScheme: theme === 'dark' ? 'dark' : 'light', reducedMotion: 'reduce'})
        await showAdvisors(page, 'pentesting')
        await page.route(`**${apiPath}/pentesting`, (route) =>
          route.fulfill({
            json: {
              scan: {status: 'PARTIAL', message: 'Probe headers truncated.', scannedAt: 1700000000000},
              evidence: {
                usable: true,
                coverageComplete: false,
                limitations: ['Probe headers truncated.']
              },
              checksRun: 70,
              findingsFound: 1,
              coverage: [
                {
                  category: 'A02:2025',
                  title: 'Security Misconfiguration',
                  status: 'REVIEW',
                  description: 'Retained category metadata must not render an OWASP coverage panel.'
                }
              ],
              findings: [{id: 'PT-A05-002', title: 'Observed header finding', severity: 'HIGH'}],
              severityCounts: [{severity: 'HIGH', count: 1}]
            }
          })
        )
        await page.goto(`${uiPath}/#/pentesting`)
        await expect(page.getByRole('img', {name: /Known-findings score: 90.*Scan notes available/})).toHaveCount(1)
        await expect(page.getByText('Observed header finding', {exact: true})).toBeVisible()
        const heading = page.getByRole('heading', {name: 'Findings by severity', exact: true})
        await expect(heading).toHaveCount(1)
        await expect(heading).toBeVisible()
        await expect(page.getByText('OWASP Top 10 coverage', {exact: true})).toHaveCount(0)
        await expect(page.locator('.coverage-card')).toHaveCount(0)
        const chart = page.locator('.card').filter({has: heading})
        await expect(chart.getByRole('img', {name: 'HIGH findings: 1', exact: true})).toHaveCount(1)
        await expect(chart.getByRole('img')).toHaveCount(1)
        await expect(chart.locator('.progress-bar')).toBeVisible()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
        await page.evaluate(() => {
          window.scrollTo(0, 0)
          document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
        })
        await page.screenshot({path: testInfo.outputPath(`pentesting-${theme}-${width}.png`), animations: 'disabled'})
        await page.evaluate(() => {
          window.scrollTo(0, 0)
          document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
        })
        await chart.screenshot({
          path: testInfo.outputPath(`pentesting-severity-${theme}-${width}.png`),
          animations: 'disabled'
        })
        if (Number(width) < 992) await page.getByRole('button', {name: 'Open navigation menu'}).click()
        await page.locator('a[href$="#/overview"]').first().click()
        const card = page.locator('.scanner-card').filter({hasText: 'Pentesting'})
        await expect(
          card.getByRole('img', {name: /Pentesting known-findings score: 90.*Scan notes available/})
        ).toHaveCount(1)
        await expect(card).not.toContainText('Probe headers truncated.')
        await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
        await expect(page.locator('.overall-card')).toContainText('1 of 1 advisors assessed')
      })
    }

    test('distinguishes proven empty evidence from skipped, failed, missing and malformed assessments', async ({
      page
    }) => {
      let report = unscannedReport()
      await page.route(`**${apiPath}/architecture{,/scan}`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/overview`)
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      await expect(card).toContainText('Not scanned')
      for (const [results, evidence, expected] of [
        [[{status: 'SKIPPED'}], {usable: false, coverageComplete: true, limitations: []}, null],
        [[{status: 'ERROR'}], {usable: false, coverageComplete: false, limitations: ['Rule failed.']}, null],
        [[], undefined, null],
        [[], {usable: 'true', coverageComplete: true, limitations: []}, null],
        [
          [],
          {
            usable: true,
            coverageComplete: false,
            limitations: ['One applicable check completed; discovery is incomplete.']
          },
          '100'
        ]
      ]) {
        report = {...architectureReport('PARTIAL'), results, evidence, severityCounts: [], violationsFound: 0}
        const response = page.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            new URL(response.url()).pathname === `${apiPath}/architecture/scan`
        )
        await card.getByRole('button', {name: /^(Run scan|Re-run scan)$/}).click()
        await response
        await expect(card.getByRole('button', {name: 'Re-run scan', exact: true})).toBeEnabled()
        if (expected === null) {
          await expect(card.locator('.scanner-score')).toHaveCount(0)
          await expect(card).not.toContainText('No findings')
        } else {
          await expect(card.locator('.scanner-score')).toHaveText(expected)
          await expect(card).not.toContainText('One applicable check completed')
          await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
          await expect(card).toContainText('No retained findings in the assessed evidence')
          await expect(card).not.toContainText('Good')
          await expect(page.locator('.overall-card')).toContainText('1 of 2 advisors assessed')
        }
      }
    })

    test('keeps confirmed empty scope neutral across scans and return navigation', async ({page}) => {
      const empty = {
        ...architectureReport('SCANNED'),
        evidence: {usable: false, coverageComplete: true, limitations: []},
        results: [],
        severityCounts: [],
        rulesEvaluated: 0,
        violationsFound: 0,
        classesAnalyzed: 0
      }
      let report = empty
      await page.route(`**${apiPath}/architecture{,/scan}`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/overview`)
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      const expectEmpty = async () => {
        await expect(card).toContainText('Not applicable')
        await expect(card.locator('.scanner-score')).toHaveCount(0)
        await expect(page.locator('.assessment-summary')).toHaveCount(0)
        await expect(page.locator('.overall-card')).toContainText('1 of 2 advisors assessed')
        await expect(page.locator('.overall-card').getByRole('img')).toHaveCount(0)
        await expect(page.locator('.overall-card')).toContainText('Not scored')
      }
      await expectEmpty()
      report = {...empty, evidence: undefined}
      await card.getByRole('button', {name: 'Re-run scan', exact: true}).click()
      await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      report = empty
      await card.getByRole('button', {name: 'Re-run scan', exact: true}).click()
      await expectEmpty()

      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-score-card')).toContainText('Not applicable')
      await expect(page.locator('.advisor-score-card')).toContainText('No applicable checks or observed findings')
      report = {...empty, scan: {status: 'PARTIAL'}}
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      await card.getByRole('link', {name: 'Open panel'}).click()
      report = empty
      await page.locator('a[href$="#/overview"]').first().click()
      await expectEmpty()

      // Failed refresh retains the last accepted complete-empty classification.
      await card.getByRole('link', {name: 'Open panel'}).click()
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({status: 503, body: 'Unavailable'}))
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card).toContainText('Showing the last report.')
      await expectEmpty()
    })

    test('scores known vulnerability evidence while retaining UNKNOWN and unqueried dependency rows', async ({
      page
    }) => {
      const report = vulnerabilityReport(false, 'INCOMPLETE')
      report.scan.status = 'PARTIAL'
      report.total = 2
      report.dependencies[0].vulnerabilityCount = 2
      report.dependencies[0].highestSeverity = 'HIGH'
      report.dependencies[0].assessment.detailAssessmentComplete = false
      report.dependencies[0].vulnerabilities.push({
        ...report.dependencies[0].vulnerabilities[0],
        id: 'GHSA-known',
        severity: 'HIGH'
      })
      report.severityCounts.push({severity: 'HIGH', count: 1})
      report.evidence.usable = true
      report.dependencies.push({
        ...report.dependencies[0],
        packageName: 'example:unqueried',
        highestSeverity: 'NONE',
        vulnerabilityCount: 0,
        assessment: {queryComplete: false, detailAssessmentComplete: false},
        vulnerabilities: []
      })
      await page.route(`**${apiPath}/vulnerabilities`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/vulnerabilities`)
      await expect(page.locator('.advisor-summary__value')).toHaveText('90')
      await expect(page.getByRole('img', {name: /Known-findings score: 90.*Scan notes available/})).toHaveCount(1)
      await expect(page.getByText('GHSA-test-unknown', {exact: true})).toBeVisible()
      await expect(page.getByText('Unknown (assessment incomplete)', {exact: true})).toBeVisible()
      await expect(page.locator('main')).not.toContainText('None found')
      await page.locator('a[href$="#/overview"]').first().click()
      const card = page.locator('.scanner-card').filter({hasText: 'Vulnerabilities'})
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(card).toContainText('1 unknown')
      await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
      await expect(card).not.toContainText('Dependency inventory coverage is incomplete or unavailable.')
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expectCollapsedScanNotes(
        page.locator('.advisor-score-card'),
        'Dependency inventory coverage is incomplete or unavailable.'
      )
    })

    test('distinguishes genuine INFO evidence from limitations without interpreting rule IDs', async ({page}) => {
      let report = architectureReport('PARTIAL')
      report.evidence.usable = false
      report.results[0].severity = 'INFO'
      report.severityCounts = [{severity: 'INFO', count: 1}]
      await page.route(`**${apiPath}/architecture{,/scan}`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/overview`)
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      await expect(card).toContainText('1 info')
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      report.evidence.usable = true
      await card.getByRole('button', {name: 'Re-run scan', exact: true}).click()
      await expect(card.locator('.scanner-score')).toHaveText('100')
      await expect(card).not.toContainText('Good')
      await expect(card).not.toContainText('Some application metadata could not be inspected.')
      await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
      const overall = page.locator('.overall-card')
      await expect(overall).toContainText('1 of 2 advisors assessed')
      await expect(overall.getByText('Good', {exact: true})).toHaveCount(1)
      await expect(
        overall.getByRole('img', {name: 'Overall score: 100 out of 100 — Average of 1 score', exact: true})
      ).toHaveCount(1)
      await expect(overall.locator('details')).toHaveCount(0)
      // Display filtering does not erase the original finding fact or manufacture complete coverage.
      report.results = []
      report.severityCounts = []
      await card.getByRole('link', {name: 'Open panel'}).click()
      const summary = page.locator('.advisor-score-card')
      await expect(summary.getByRole('img', {name: /Known-findings score: 100.*Scan notes available/})).toHaveCount(1)
      await expect(summary).not.toContainText('Good')
      await expectCollapsedScanNotes(summary, 'Some application metadata')
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card.locator('.scanner-score')).toHaveText('100')
      await expect(
        page
          .locator('.overall-card')
          .getByRole('img', {name: 'Overall score: 100 out of 100 — Average of 1 score', exact: true})
      ).toHaveCount(1)
      await expect(card).not.toContainText('1 info')
    })

    test('includes Database partial schema evidence in panel and Overview scores', async ({page}) => {
      await showAdvisors(page, 'database-advisor')
      const report = {
        ...architectureReport('PARTIAL'),
        tablesAnalyzed: 5,
        dataSourceNames: ['default'],
        rulesErrored: 1,
        rulesSkipped: 2,
        diagnostics: [{source: 'default', level: 'WARNING', message: 'Index metadata unavailable.'}],
        evidence: {
          usable: true,
          coverageComplete: false,
          limitations: ['Index metadata unavailable.']
        },
        severityCounts: [
          {severity: 'HIGH', count: 8},
          {severity: 'MEDIUM', count: 2}
        ],
        results: [
          {
            ...architectureReport('PARTIAL').results[0],
            id: 'DB-SCHEMA-002',
            name: 'Missing supporting indexes',
            violationCount: 8
          },
          {
            ...architectureReport('PARTIAL').results[0],
            id: 'DB-HIB-002',
            name: 'Mapping mismatch',
            severity: 'MEDIUM',
            violationCount: 2
          }
        ]
      }
      await page.route(`**${apiPath}/database-advisor`, (route) => route.fulfill({json: report}))
      await page.goto(`${uiPath}/#/database-advisor`)
      await expect(page.getByRole('img', {name: /Known-findings score: 14.*Scan notes available/})).toHaveCount(1)
      await expectCollapsedScanNotes(page.locator('.advisor-score-card'), 'Index metadata unavailable.')
      await page.getByRole('button', {name: 'Show diagnostics', exact: true}).focus()
      await page.keyboard.press('Enter')
      await expect(page.getByRole('button', {name: 'Hide diagnostics', exact: true})).toHaveAttribute(
        'aria-expanded',
        'true'
      )
      await page.locator('a[href$="#/overview"]').first().click()
      const card = page.locator('.scanner-card').filter({hasText: 'Database'})
      await expect(card.locator('.scanner-score')).toHaveText('14')
      await expect(card).not.toContainText('Index metadata unavailable.')
      await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
      await expect(
        page
          .locator('.overall-card')
          .getByRole('img', {name: 'Overall score: 14 out of 100 — Average of 1 score', exact: true})
      ).toHaveCount(1)
      await expect(page.locator('.overall-card')).toContainText('1 of 1 advisors assessed')
    })
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
      await expect(page.locator('.panel-header')).toContainText('Inspect retained findings')
      expect(advisorRequests).toEqual([])
      releaseManifest()
      const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(page.locator('.assessment-summary')).toHaveCount(0)
      await expect(card.locator('.scanner-status')).toHaveText('Scan complete')
      await expect(card).not.toContainText('Scan notes available')
      await expect(card).not.toContainText('Some application metadata could not be inspected.')
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
      await expect(card).toContainText('Scan complete')
      await expect(card).toContainText('1 high')
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(card).not.toContainText('Scan notes available in panel')
      await expect(card).not.toContainText('Partial assessment')
      await expect.poll(() => reads).toBe(3)
      expect(scans).toBe(1)
    })

    test('scores usable partial findings and separates failed and unscanned reports', async ({page}) => {
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
      await expect(overall).toContainText('1 of 2 advisors assessed')
      status = 'PARTIAL'
      await card.getByRole('button', {name: 'Re-run scan', exact: true}).click()
      await expect(card.locator('.scanner-score')).toHaveText('90')
      await expect(overall).toContainText('1 of 2 advisors assessed')
      await expect(
        overall.getByRole('img', {name: 'Overall score: 90 out of 100 — Average of 1 score', exact: true})
      ).toHaveCount(1)
      for (const next of ['ERROR', 'DISABLED', 'NOT_SCANNED']) {
        status = next
        await card.getByRole('button', {name: 'Re-run scan', exact: true}).click()
        await expect(card.locator('.scanner-score')).toHaveCount(0)
        await expect(card).toContainText('1 high')
        await expect(overall).toContainText('0 of 2 advisors assessed')
        await expect(overall.getByRole('img')).toHaveCount(0)
      }
      status = 'PARTIAL'
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-score-card')).toContainText('Results available')
      await expect(page.getByText('Retained architecture finding', {exact: true})).toBeVisible()
      await expect(page.getByRole('img', {name: /Known-findings score: 90.*Scan notes available/})).toHaveCount(1)
    })

    for (const status of ['SCANNED', 'PARTIAL']) {
      test(`dismisses and restores a ${status} finding with exact score changes`, async ({page}) => {
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
          '1 dismissed rule(s) excluded from active findings'
        )
        await expect(page.locator('.advisor-summary__dismissed')).toContainText(
          'Dismissal changes score penalties, not application safety.'
        )
        await expect(page.locator('.advisor-summary__value')).toHaveText('100')
        await expect(page.locator('.advisor-score-card')).not.toContainText('Good')
        if (status === 'PARTIAL') {
          await expect(page.getByRole('img', {name: /Known-findings score: 100.*Scan notes available/})).toHaveCount(1)
          await expectCollapsedScanNotes(page.locator('.advisor-score-card'), 'Some application metadata')
        }
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveText('100')
        await card.getByRole('link', {name: 'Open panel'}).click()
        await page.getByRole('button', {name: /Restore$/}).click()
        await expect(page.locator('.list-group-item.opacity-50')).toHaveCount(0)
        await expect(page.locator('.advisor-summary__dismissed')).toHaveCount(0)
        await expect(page.locator('.advisor-summary__value')).toHaveText('90')
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveText('90')
        await expect(
          page
            .locator('.overall-card')
            .getByRole('img', {name: 'Overall score: 90 out of 100 — Average of 1 score', exact: true})
        ).toHaveCount(1)
        expect(scans).toBe(0)
      })
    }

    test('keeps UNKNOWN-only dismissal unscored using only cached report GETs', async ({page}) => {
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
      await expect(card.locator('.scanner-assessment')).toHaveText('Not scored')
      await expect(card).not.toContainText('No usable assessment evidence')
      await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
      await expect(card).toContainText('1 unknown')
      await card.getByRole('link', {name: 'Open panel'}).click()
      await expect(page.locator('.advisor-summary__score')).toHaveCount(0)
      await expect(page.locator('.advisor-summary__assessment')).toContainText('No usable assessment evidence')
      await expect(page.locator('details.advisor-summary__notes')).toHaveCount(0)
      await page.getByRole('button', {name: /Dismiss$/}).click()
      await expect(page.getByRole('button', {name: /Restore$/})).toBeVisible()
      await expect(page.locator('.advisor-summary__score')).toHaveCount(0)
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      await expect(page.locator('.overall-card')).toContainText('1 of 2 advisors assessed')
      await card.getByRole('link', {name: 'Open panel'}).click()
      await page.getByRole('button', {name: /Restore$/}).click()
      await expect(page.locator('.advisor-summary__score')).toHaveCount(0)
      await page.locator('a[href$="#/overview"]').first().click()
      await expect(card.locator('.scanner-score')).toHaveCount(0)
      await expect(card).toContainText('1 unknown')
      await expect(page.locator('.overall-card')).toContainText('1 of 2 advisors assessed')
      for (const next of ['INCOMPLETE', 'UNAVAILABLE', '']) {
        dismissed = true
        coverageStatus = next
        await card.getByRole('link', {name: 'Open panel'}).click()
        await expect(page.locator('.advisor-score-card')).toContainText('coverage')
        await expect(page.locator('.advisor-summary__score')).toHaveCount(0)
        await page.locator('a[href$="#/overview"]').first().click()
        await expect(card.locator('.scanner-score')).toHaveCount(0)
      }
      expect(scans).toBe(0)
    })

    for (const [theme, width] of [
      ['light', 1600],
      ['light', 1024],
      ['dark', 390]
    ]) {
      test(`keeps scan notes readable in ${theme} at ${width}px`, async ({page}, testInfo) => {
        await page.setViewportSize({width: Number(width), height: 900})
        await page.emulateMedia({colorScheme: theme === 'dark' ? 'dark' : 'light', reducedMotion: 'reduce'})
        await showAdvisors(page, 'architecture', 'vulnerabilities', 'github')
        let githubRefreshes = 0
        let signalsAvailable = true
        await page.route(`**${apiPath}/github/refresh`, (route) => {
          githubRefreshes++
          return route.fulfill({
            json: {
              available: true,
              connected: true,
              credential: {authenticated: true},
              status: 'CONNECTED',
              securitySignals: [
                {label: 'Dependabot alerts', status: 'AVAILABLE', count: 2},
                {
                  label: 'Code scanning alerts',
                  status: signalsAvailable ? 'AVAILABLE' : 'UNAVAILABLE',
                  count: signalsAvailable ? 0 : null
                },
                {label: 'Secret scanning alerts', status: 'AVAILABLE', count: 0}
              ]
            }
          })
        })
        const report = architectureReport('PARTIAL')
        report.results = ['Use constructor injection', 'Keep controllers focused', 'Isolate persistence access'].map(
          (name, index) => ({
            ...report.results[0],
            id: `ARCH-TEST-${index + 1}`,
            name,
            severity: 'MEDIUM'
          })
        )
        report.rulesEvaluated = 3
        report.violationsFound = 3
        report.classesAnalyzed = 6
        report.severityCounts = [{severity: 'MEDIUM', count: 3}]
        expect(expectedAdvisorScore(report)).toBe(91)
        expect(report.results.reduce((count, result) => count + result.violationCount, 0)).toBe(report.violationsFound)
        await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: report}))
        const capture = async (state) => {
          await page.evaluate(() => {
            window.scrollTo(0, 0)
            document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
          })
          await page.screenshot({
            path: testInfo.outputPath(`bootui-scan-notes-${testInfo.project.name}-${theme}-${state}.png`),
            type: 'png',
            fullPage: false,
            animations: 'disabled'
          })
        }
        await page.goto(`${uiPath}/#/architecture`)
        const summary = page.locator('.advisor-score-card')
        const expectStableResults = async () => {
          const badge = summary.getByText('Results available', {exact: true})
          await expect(badge).toHaveCount(1)
          await expect(badge).toHaveClass(/\btext-bg-secondary\b/)
          await expect(badge).not.toHaveClass(/\btext-bg-(warning|danger|success)\b/)
          await expect(summary).not.toContainText('Incomplete')
          await expect(summary).not.toContainText('Partial assessment')
          await expect(summary.getByRole('img')).toHaveCount(1)
          await expect(
            summary.getByRole('img', {name: 'Known-findings score: 91 out of 100 — Scan notes available', exact: true})
          ).toHaveCount(1)
          await expect(summary.locator('.advisor-summary__value')).toHaveText('91')
          for (const label of ['Rules evaluated', 'Rule violations']) {
            await expect(summary.locator('.advisor-summary__metric').filter({hasText: label}).locator('dd')).toHaveText(
              '3'
            )
          }
          for (const result of report.results) {
            await expect(page.getByText(result.name, {exact: true})).toBeVisible()
          }
          await expect(page.getByRole('img', {name: 'MEDIUM violations: 3', exact: true})).toHaveCount(1)
        }
        const notes = await expectCollapsedScanNotes(summary, 'Some application metadata could not be inspected.')
        const disclosure = notes.locator('summary')
        await expectStableResults()
        await capture('panel-closed')
        await page.getByRole('button', {name: 'Run architecture checks', exact: true}).focus()
        for (let tabs = 0; tabs < 20; tabs++) {
          await page.keyboard.press('Tab')
          if (await disclosure.evaluate((element) => element === document.activeElement)) break
        }
        await expect(disclosure).toBeFocused()
        for (const key of ['Enter', 'Space']) {
          await page.keyboard.press(key)
          await expect(notes).toHaveJSProperty('open', true)
          await expect(notes.locator('p')).toBeVisible()
          await expect(disclosure).toBeFocused()
          await expectStableResults()
          if (key === 'Enter') await capture('panel-open')
          await page.keyboard.press(key)
          await expect(notes).toHaveJSProperty('open', false)
          await expect(notes.locator('p')).not.toBeVisible()
          await expect(disclosure).toBeFocused()
          await expectStableResults()
        }
        if (Number(width) < 992) await page.getByRole('button', {name: 'Open navigation menu'}).click()
        await page.locator('a[href$="#/overview"]').first().click()
        const card = page.locator('.scanner-card').filter({hasText: 'Architecture'})
        await expect(card.locator('.scanner-status')).toHaveText('Scan complete')
        await expect(card.locator('.scanner-status')).toHaveClass(/\btext-bg-secondary\b/)
        await expect(card).not.toContainText('Incomplete')
        await expect(card).not.toContainText('Partial assessment')
        await expect(card).toContainText('3 medium')
        await expect(card.locator('.scanner-score')).toHaveText('91')
        await expect(page.locator('.assessment-summary')).toContainText('1 advisor has scan notes')
        await expect(card).not.toContainText('Some application metadata could not be inspected.')
        await expect(
          card.getByRole('img', {
            name: 'Architecture known-findings score: 91 out of 100 — Scan notes available',
            exact: true
          })
        ).toHaveCount(1)
        const overall = page.locator('.overall-card')
        await expect(
          overall.getByRole('img', {name: 'Overall score: 91 out of 100 — Average of 1 score', exact: true})
        ).toHaveCount(1)
        await expectOverallGauge(overall, 91, 1)
        await expect(overall.locator('.overall-contributions li span')).toHaveText(['Architecture', '-9'])
        const github = page.locator('.scanner-card').filter({hasText: 'GitHub'})
        expect(githubRefreshes).toBe(0)
        const connect = github.getByRole('button', {name: /Connect to GitHub$/})
        await connect.focus()
        await expect(connect).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(
          github.getByRole('img', {name: 'GitHub security-alert score: 80 out of 100', exact: true})
        ).toHaveCount(1)
        await expect(
          overall.getByRole('img', {name: 'Overall score: 86 out of 100 — Average of 2 scores', exact: true})
        ).toHaveCount(1)
        await expectOverallGauge(overall, 86, 2)
        await expect(overall.locator('.overall-contributions li span')).toHaveText([
          'GitHub',
          '-20',
          'Architecture',
          '-9'
        ])
        await expect(overall.getByRole('img')).toHaveCount(1)
        await expect(github.getByRole('img')).toHaveCount(1)
        await expect(github).not.toContainText('high')
        await expect(overall).toContainText('3 medium')
        await expect(overall).toContainText('1 of 2 advisors assessed')
        await expect(card.locator('.scanner-score')).toHaveText('91')
        await capture('overview')
        signalsAvailable = false
        await github.getByRole('button', {name: /Refresh$/}).click()
        await expect(github).toContainText('Not scored')
        await expect(github).toContainText('Dependabot alerts: 2 open')
        await expect(github).toContainText('Code scanning alerts: Unavailable')
        await expect(github.getByRole('img')).toHaveCount(0)
        await expect(
          overall.getByRole('img', {name: 'Overall score: 91 out of 100 — Average of 1 score', exact: true})
        ).toHaveCount(1)
        expect(githubRefreshes).toBe(2)
        await expect(overall.locator('.overall-contributions li span')).toHaveText(['Architecture', '-9'])
        const run = card.getByRole('button', {name: 'Re-run scan', exact: true})
        await run.focus()
        await expect(run).toBeFocused()
        await page.keyboard.press('Tab')
        await expect(card.getByRole('link', {name: 'Open panel'})).toBeFocused()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      })
    }
  })
}
