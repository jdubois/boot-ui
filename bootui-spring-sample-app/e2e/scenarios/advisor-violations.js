// @ts-check

const advisors = ['architecture', 'hibernate', 'spring', 'rest-api', 'memory', 'security', 'database-advisor']
const ruleId = 'ARCH-SPRING-004'
const strings = (count, offset = 0) =>
  Array.from({length: count}, (_, index) => `example.Service${offset + index} violates ${ruleId}`)

function report(scanId = 'scan-1', count = 29) {
  return {
    scan: {status: 'SCANNED', scannedAt: 1700000000000},
    evidence: {usable: true, coverageComplete: true, limitations: []},
    severityCounts: [{severity: 'HIGH', count}],
    rulesEvaluated: 1,
    violationsFound: 1,
    basePackages: [],
    entityPackages: [],
    inspected: [],
    violationDetails: {scanId, total: count, retained: count, retentionLimit: 10000, truncated: false},
    results: [
      {
        id: ruleId,
        name: 'Retained finding',
        status: 'VIOLATION',
        severity: 'HIGH',
        category: 'TEST',
        violationCount: count,
        sampleViolations: strings(10),
        recommendation: 'Review these findings.'
      }
    ]
  }
}

function detailPage({scanId = 'scan-1', count = 29, retained = count, offset = 0} = {}) {
  const returned = Math.max(0, Math.min(100, retained - offset))
  return {
    scanId,
    ruleId,
    violationCount: count,
    retainedCount: retained,
    truncated: retained < count,
    violations: strings(returned, offset),
    page: {total: retained, matched: retained, offset, limit: 100, returned, hasMore: offset + returned < retained}
  }
}

const located = ['architecture', 'rest-api', 'hibernate']
const location = (index) => ({
  className: `example.Service${index}`,
  memberName: 'handle',
  kind: 'METHOD',
  sourceFile: `Service${index}.java`,
  line: index + 40,
  sourcePath: `/work/app/src/main/java/example/Service${index}.java`,
  precision: 'LINE'
})
const locations = (count, offset = 0) =>
  Array.from({length: count}, (_, index) => ((offset + index) % 4 === 3 ? null : location(offset + index)))

/**
 * Deterministic UI contracts; the adapter/conformance suites test real collection.
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} paths
 */
export function registerAdvisorViolationTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test.describe('On-demand advisor violations', () => {
    test.afterEach(async ({page}) => {
      // Finish in-flight route.fetch handlers before context teardown disposes their responses.
      await page.unrouteAll({behavior: 'wait'})
    })

    test.beforeEach(async ({page}) => {
      await page.route(`**${apiPath}/panels`, async (route) => {
        const response = await route.fetch()
        const body = await response.json()
        body.panels = body.panels.map((panel) =>
          advisors.includes(panel.id) ? {...panel, available: true, enabled: true, readOnly: true} : panel
        )
        await route.fulfill({response, json: body})
      })
      await page.route(`**${apiPath}/rest-api/error-contract?*`, (route) =>
        route.fulfill({json: {available: true, entries: [], total: 0}})
      )
    })

    for (const advisor of advisors) {
      test(`${advisor}: keeps previews and reads 29 details only on keyboard activation`, async ({page}) => {
        const reads = []
        const scans = []
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: report()}))
        await page.route(`**${apiPath}/${advisor}/scan`, (route) => {
          scans.push(route.request().url())
          return route.fulfill({json: report()})
        })
        await page.route(`**${apiPath}/${advisor}/rules/*/violations?*`, (route) => {
          reads.push(new URL(route.request().url()))
          return route.fulfill({json: detailPage()})
        })
        await page.goto(`${uiPath}/#/${advisor}`)
        const detail = page.locator('.advisor-rule-violations')
        await expect(detail.locator('li')).toHaveCount(10)
        expect(reads).toHaveLength(0)
        const view = detail.getByRole('button', {name: `View violations for ${ruleId}`, exact: true})
        await expect(view).toBeEnabled()
        await view.focus()
        await page.keyboard.press('Enter')
        await expect(detail.locator('li')).toHaveText(strings(29))
        expect(reads).toHaveLength(1)
        expect(reads[0].pathname).toBe(`${apiPath}/${advisor}/rules/${ruleId}/violations`)
        expect(Object.fromEntries(reads[0].searchParams)).toEqual({scanId: 'scan-1', offset: '0', limit: '100'})
        await expect(detail.locator('[tabindex="-1"]')).toBeFocused()
        await expect(detail.locator('[role="status"], [role="alert"], [aria-live]')).toHaveCount(1)
        await expect(detail.getByRole('status')).toContainText('Showing 1–29 of 29 retained violations')
        await page.keyboard.press('Tab')
        await expect(detail.getByRole('button', {name: `Back to samples for ${ruleId}`})).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(view).toBeFocused()
        await expect(detail.locator('li')).toHaveCount(10)
        expect(reads).toHaveLength(1)
        expect(scans).toHaveLength(0)
      })
    }

    for (const advisor of located) {
      test(`${advisor}: shows copyable locations and opt-in editor links`, async ({page, context}) => {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
        const body = report()
        body.results[0].sampleLocations = locations(10)
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: body}))
        await page.route(`**${apiPath}/${advisor}/rules/*/violations?*`, (route) =>
          route.fulfill({json: {...detailPage(), locations: locations(29)}})
        )
        await page.goto(`${uiPath}/#/${advisor}`)
        const detail = page.locator('.advisor-rule-violations')
        const rows = detail.locator('li')
        await expect(rows).toHaveCount(10)
        await expect(rows.nth(0)).toContainText('example.Service0#handle (Service0.java:40)')
        await expect(rows.nth(3).locator('.advisor-violation-location')).toHaveCount(0)

        const openIn = page.getByLabel('Open locations in')
        await expect(openIn).toHaveValue('none')
        await expect(detail.locator('a')).toHaveCount(0)

        const copy = detail.getByRole('button', {name: 'Copy location example.Service0#handle (Service0.java:40)'})
        await copy.focus()
        await page.keyboard.press('Enter')
        await expect(copy).toHaveText('Copied')
        expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
          'example.Service0#handle (Service0.java:40)'
        )
        await expect(detail.getByRole('status')).toHaveText(
          'Copied location example.Service0#handle (Service0.java:40).'
        )

        await openIn.focus()
        await openIn.selectOption('vscode')
        const link = detail.getByRole('link', {name: 'Open example.Service0#handle (Service0.java:40) in VS Code'})
        await expect(link).toHaveAttribute('href', 'vscode://file/work/app/src/main/java/example/Service0.java:40')
        const hrefs = await detail.locator('a').evaluateAll((links) => links.map((link) => link.getAttribute('href')))
        expect(hrefs.length).toBeGreaterThan(0)
        expect(hrefs.every((href) => href.startsWith('vscode://file/'))).toBe(true)

        await page.reload()
        await expect(page.getByLabel('Open locations in')).toHaveValue('vscode')
        await detail.getByRole('button', {name: `View violations for ${ruleId}`}).click()
        await expect(rows).toHaveCount(29)
        await expect(rows.nth(28)).toContainText('example.Service28#handle (Service28.java:68)')
        await expect(rows.nth(27).locator('.advisor-violation-location')).toHaveCount(0)
        await page.getByLabel('Open locations in').selectOption('idea')
        await expect(
          detail.getByRole('link', {name: 'Open example.Service28#handle (Service28.java:68) in IntelliJ IDEA'})
        ).toHaveAttribute(
          'href',
          'idea://open?file=%2Fwork%2Fapp%2Fsrc%2Fmain%2Fjava%2Fexample%2FService28.java&line=68'
        )
        await page.getByLabel('Open locations in').selectOption('none')
        await expect(detail.locator('a')).toHaveCount(0)
      })
    }

    test('advisors without locations offer no Open in preference', async ({page}) => {
      await page.route(`**${apiPath}/spring`, (route) => route.fulfill({json: report()}))
      await page.goto(`${uiPath}/#/spring`)
      await expect(page.locator('.advisor-rule-violations li')).toHaveCount(10)
      await expect(page.getByLabel('Open locations in')).toHaveCount(0)
      await expect(page.locator('.advisor-violation-location')).toHaveCount(0)
    })

    test('pages without appending and keeps the accepted page through retry', async ({page}) => {
      const offsets = []
      let failNext = true
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: report('scan-1', 201)}))
      await page.route(`**${apiPath}/architecture/rules/*/violations?*`, (route) => {
        const offset = Number(new URL(route.request().url()).searchParams.get('offset'))
        offsets.push(offset)
        if (offset === 100 && failNext) {
          failNext = false
          return route.fulfill({status: 503, json: {error: 'Unavailable'}})
        }
        return route.fulfill({json: detailPage({count: 201, offset})})
      })
      await page.goto(`${uiPath}/#/architecture`)
      const detail = page.locator('.advisor-rule-violations')
      await detail.getByRole('button', {name: `View violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveCount(100)
      await detail.getByRole('button', {name: `Next violations for ${ruleId}`}).click()
      await expect(detail).toContainText('Unable to load violations: HTTP 503')
      await expect(detail.locator('li')).toHaveText(strings(100))
      await detail.getByRole('button', {name: `Retry violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveText(strings(100, 100))
      await detail.getByRole('button', {name: `Next violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveText(strings(1, 200))
      await expect(detail.getByRole('button', {name: `Next violations for ${ruleId}`})).toBeDisabled()
      await detail.getByRole('button', {name: `Previous violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveText(strings(100, 100))
      expect(offsets).toEqual([0, 100, 100, 200, 100])
    })

    test('refreshes a replaced cached report explicitly and discloses retention overflow', async ({page}) => {
      let reportReads = 0
      let detailReads = 0
      const methods = []
      await page.route(`**${apiPath}/architecture`, (route) => {
        reportReads++
        methods.push(route.request().method())
        const body = report(reportReads === 1 ? 'scan-1' : 'scan-2')
        if (reportReads > 1) body.violationDetails = {...body.violationDetails, retained: 12, truncated: true}
        return route.fulfill({json: body})
      })
      await page.route(`**${apiPath}/architecture/rules/*/violations?*`, (route) => {
        detailReads++
        return detailReads === 1
          ? route.fulfill({status: 409, json: {error: 'Stale snapshot'}})
          : route.fulfill({json: detailPage({scanId: 'scan-2', retained: 12})})
      })
      await page.goto(`${uiPath}/#/architecture`)
      const detail = page.locator('.advisor-rule-violations')
      const view = detail.getByRole('button', {name: `View violations for ${ruleId}`})
      await view.click()
      await expect(detail).toContainText('No scan will run')
      expect(reportReads).toBe(1)
      await detail.getByRole('button', {name: `Refresh cached report for ${ruleId}`}).click()
      await expect(view).toBeEnabled()
      expect(reportReads).toBe(2)
      expect(detailReads).toBe(1)
      await view.click()
      await expect(detail.locator('li')).toHaveCount(12)
      await expect(detail).toContainText('Incomplete details: 12 of 29 violations retained')
      await expect(detail.getByRole('button', {name: `Next violations for ${ruleId}`})).toHaveCount(0)
      expect(methods).toEqual(['GET', 'GET'])
    })

    const tracked = ['hibernate', 'database-advisor']
    const scanButton = {hibernate: 'Run Hibernate checks', 'database-advisor': 'Run Database checks'}
    const changes = (overrides = {}) => ({
      previousScanId: 'scan-0',
      complete: true,
      newCount: 0,
      resolvedCount: 0,
      unchangedCount: 29,
      unkeyedCount: 0,
      resolved: [],
      notes: [],
      unverifiedCount: 0,
      rules: [],
      ...overrides
    })
    const withChanges = (value, scanId = 'scan-1') => {
      const body = report(scanId)
      body.violationDetails = {...body.violationDetails, changes: value}
      return body
    }

    for (const advisor of tracked) {
      test(`${advisor}: says a first scan has nothing to compare, then reports what moved`, async ({page}) => {
        // The beforeEach mocks every advisor read-only; this test runs a scan, so lift that for the tracked one.
        await page.route(`**${apiPath}/panels`, async (route) => {
          const response = await route.fetch()
          const body = await response.json()
          body.panels = body.panels.map((panel) =>
            panel.id === advisor ? {...panel, available: true, enabled: true, readOnly: false} : panel
          )
          await route.fulfill({response, json: body})
        })
        let current = withChanges(null)
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: current}))
        await page.route(`**${apiPath}/${advisor}/scan`, (route) => route.fulfill({json: current}))
        await page.goto(`${uiPath}/#/${advisor}`)
        const region = page.getByTestId('advisor-changes')
        await expect(region).toContainText('Since previous scan')
        await expect(region).toContainText('First scan: nothing to compare yet.')

        const live = page.locator('.advisor-score-card [role="status"]')
        await expect(live).toHaveCount(1)
        await expect(live).toHaveAttribute('aria-live', 'polite')
        await expect(live).toHaveText('')

        current = withChanges(
          changes({
            newCount: 2,
            resolvedCount: 1,
            unchangedCount: 27,
            resolved: [{ruleId, text: 'example.Service30 violates ' + ruleId}],
            rules: [{ruleId, newCount: 2, resolvedCount: 1, unverifiedCount: 0}]
          }),
          'scan-2'
        )
        await page.getByRole('button', {name: scanButton[advisor]}).click()
        await expect(region).toContainText('2 new')
        await expect(region).toContainText('1 resolved')
        await expect(region).toContainText('27 unchanged')
        await expect(live).toHaveText('2 new, 1 resolved, 27 unchanged since the previous scan.')
        await expect(region.getByText('Comparison incomplete')).toHaveCount(0)

        const resolved = region.locator('details.advisor-summary__resolved')
        await expect(resolved).not.toHaveAttribute('open', '')
        await resolved.locator('summary').focus()
        await page.keyboard.press('Enter')
        await expect(resolved).toContainText('example.Service30 violates ' + ruleId)
      })

      test(`${advisor}: marks the new findings of a rule, even one whose findings are all samples`, async ({page}) => {
        const body = withChanges(
          changes({
            newCount: 2,
            unchangedCount: 1,
            rules: [{ruleId, newCount: 2, resolvedCount: 0, unverifiedCount: 0}]
          })
        )
        body.results[0].violationCount = 3
        body.results[0].sampleViolations = strings(3)
        body.violationDetails.total = 3
        body.violationDetails.retained = 3
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: body}))
        await page.route(`**${apiPath}/${advisor}/rules/*/violations?*`, (route) =>
          route.fulfill({
            json: {
              ...detailPage({count: 3}),
              identities: ['v1:a', 'v1:b', 'v1:c'],
              changes: ['UNCHANGED', 'NEW', 'NEW']
            }
          })
        )
        await page.goto(`${uiPath}/#/${advisor}`)
        const detail = page.locator('.advisor-rule-violations')
        await expect(detail).toContainText('2 new since the previous scan')
        await expect(detail.getByTestId('new-finding')).toHaveCount(0)
        const view = detail.getByRole('button', {name: `View violations for ${ruleId}`})
        await view.focus()
        await page.keyboard.press('Enter')
        await expect(detail.locator('li')).toHaveCount(3)
        await expect(detail.getByTestId('new-finding')).toHaveCount(2)
        await expect(detail.locator('li').nth(0).getByTestId('new-finding')).toHaveCount(0)
        await expect(detail.locator('li').nth(1).getByTestId('new-finding')).toHaveText('New')
      })

      test(`${advisor}: never calls an incomplete comparison complete`, async ({page}) => {
        const body = withChanges(
          changes({
            complete: false,
            newCount: 1,
            unchangedCount: 20,
            unverifiedCount: 3,
            notes: ['3 finding(s) were not compared because a scan did not fully evaluate their rule.']
          })
        )
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: body}))
        await page.goto(`${uiPath}/#/${advisor}`)
        const region = page.getByTestId('advisor-changes')
        await expect(region).toContainText('Comparison incomplete: 3 could not be compared.')
        await expect(region).not.toContainText('No changes since the previous scan')
        const why = region.locator('details', {hasText: 'Why is this comparison incomplete?'})
        await why.locator('summary').click()
        await expect(why).toContainText('did not fully evaluate their rule')
      })

      test(`${advisor}: shows an unchanged rescan as one calm sentence`, async ({page}) => {
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: withChanges(changes())}))
        await page.goto(`${uiPath}/#/${advisor}`)
        await expect(page.getByTestId('advisor-changes')).toContainText(
          'No changes since the previous scan. 29 unchanged.'
        )
      })

      test(`${advisor}: stays readable on a narrow screen`, async ({page}) => {
        await page.setViewportSize({width: 390, height: 844})
        const body = withChanges(
          changes({
            complete: false,
            newCount: 12,
            resolvedCount: 3,
            unchangedCount: 140,
            unverifiedCount: 2,
            resolved: [{ruleId, text: 'example.VeryLongServiceName'.repeat(6) + ' violates ' + ruleId}],
            notes: ['2 finding(s) were not compared because a scan did not fully evaluate their rule.'],
            rules: [{ruleId, newCount: 12, resolvedCount: 3, unverifiedCount: 2}]
          })
        )
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: body}))
        await page.goto(`${uiPath}/#/${advisor}`)
        const region = page.getByTestId('advisor-changes')
        await expect(region).toContainText('12 new')
        await region.locator('details.advisor-summary__resolved summary').click()
        await expect(region).toBeVisible()
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
        expect(overflow).toBeLessThanOrEqual(1)
      })
    }

    test('advisors that do not key their findings show no comparison and no extra live region', async ({page}) => {
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: report()}))
      await page.goto(`${uiPath}/#/architecture`)
      await expect(page.locator('.advisor-rule-violations li')).toHaveCount(10)
      await expect(page.getByTestId('advisor-changes')).toHaveCount(0)
      await expect(page.locator('.advisor-score-card [role="status"]')).toHaveCount(0)
    })

    test('command-line catalogue spells required scan identity and optional page flags', async ({page}) => {
      await page.route(`**${apiPath}/cli`, (route) =>
        route.fulfill({
          json: {
            enabled: true,
            serverName: 'bootui',
            serverVersion: 'dev',
            toolCount: 1,
            maxResults: 200,
            tools: [
              {
                name: 'get_architecture_rule_violations',
                command: 'architecture violations',
                description: 'Read retained violations from a completed scan.',
                panel: 'architecture',
                action: false,
                schema: 'RULE_VIOLATIONS',
                arguments: ['id', 'scanId', 'offset', 'limit'],
                panelEnabled: true,
                panelReadOnly: true
              }
            ]
          }
        })
      )
      await page.goto(`${uiPath}/#/cli`)
      await expect(page.getByText('bootui architecture violations', {exact: true})).toBeVisible()
      await expect(
        page.getByText('<id> --scan-id <scan-id> [--offset <offset>] [--limit <limit>]', {exact: true})
      ).toBeVisible()
    })
  })
}
