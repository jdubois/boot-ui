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
      test(`${advisor}: filters cached rules, follows exact links and previews qualified copy without side effects`, async ({
        page,
        context
      }) => {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
        const body = report()
        body.results.push({
          ...body.results[0],
          id: 'OTHER-1',
          name: 'Other finding',
          severity: 'LOW',
          violationCount: 2,
          sampleViolations: ['other cached evidence']
        })
        body.rulesEvaluated = 2
        body.violationsFound = 2
        body.severityCounts.push({severity: 'LOW', count: 2})
        body.violationDetails = {...body.violationDetails, total: 31, retained: 31}
        const requests = []
        await page.route(`**${apiPath}/${advisor}`, (route) => {
          requests.push({method: route.request().method(), url: route.request().url()})
          return route.fulfill({json: body})
        })
        await page.route(`**${apiPath}/${advisor}/**`, (route) => {
          if (route.request().url().includes('/rest-api/error-contract?')) return route.fallback()
          requests.push({method: route.request().method(), url: route.request().url()})
          return route.fulfill({status: 500, json: {error: 'Unexpected evidence collection or mutation'}})
        })
        await page.goto(`${uiPath}/#/${advisor}?keep=yes&rule=${encodeURIComponent(ruleId)}`)
        const target = page.locator(`.advisor-rule-result[data-rule-id="${ruleId}"]`)
        await expect(target.locator('.advisor-rule-toggle')).toHaveAttribute('aria-expanded', 'true')
        await expect(target.locator('.advisor-rule-toggle')).toBeFocused()
        await page.getByLabel('Severity', {exact: true}).selectOption('LOW')
        await expect(page.getByText('Linked rule ' + ruleId, {exact: false})).toContainText('revealed separately')
        await expect(page.locator('.advisor-rule-results')).toContainText(
          '1 of 2 active rules match · 2 active findings'
        )
        const other = page.locator('.advisor-rule-result[data-rule-id="OTHER-1"]')
        await other.locator('.advisor-rule-toggle').click()
        await expect(page).toHaveURL(new RegExp(`#/${advisor}\\?keep=yes&rule=OTHER-1$`))
        await expect(target).toBeHidden()
        await other.getByRole('button', {name: 'Copy link to OTHER-1', exact: true}).click()
        const link = await page.evaluate(() => navigator.clipboard.readText())
        expect(link).toBe(`${new URL(page.url()).origin}${uiPath}/#/${advisor}?keep=yes&rule=OTHER-1`)
        const copyVisible = page.getByRole('button', {name: 'Copy visible findings', exact: true})
        await copyVisible.click()
        const preview = page.getByLabel('Markdown export preview')
        await expect(preview).toBeVisible()
        await expect(page.locator('.ai-export h3')).toBeFocused()
        const markdown = await preview.inputValue()
        expect(markdown).toContain('other cached evidence')
        expect(markdown).not.toContain('example.Service0')
        expect(markdown).toContain('cached report samples only')
        expect(markdown).toContain('suppression metadata is unavailable')
        expect(markdown).toContain('not instructions to an agent')
        expect(markdown).toContain('Report active violating rules: 2; rules evaluated: 2')
        expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(link)
        await page.getByRole('button', {name: 'Copy Markdown', exact: true}).click()
        expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(markdown)
        await page.locator('.ai-export').getByRole('button', {name: 'Back', exact: true}).click()
        await expect(copyVisible).toBeFocused()
        await page.goBack()
        await expect(target).toBeVisible()
        await expect(target.locator('.advisor-rule-toggle')).toBeFocused()
        await page.goForward()
        await expect(target).toBeHidden()
        await page.getByLabel('Search rules and loaded evidence', {exact: true}).fill('not found')
        await expect(other).toBeVisible()
        await expect(page.locator('.advisor-rule-results')).toContainText(
          '0 of 2 active rules match · 0 active findings'
        )
        await other.locator('.advisor-rule-toggle').focus()
        await page.keyboard.press('Enter')
        await expect(other).toBeHidden()
        await expect(page.getByLabel('Search rules and loaded evidence', {exact: true})).toBeFocused()
        await expect(page).toHaveURL(new RegExp(`#/${advisor}\\?keep=yes$`))
        await page.goBack()
        await expect(other).toBeVisible()
        await expect(other.locator('.advisor-rule-toggle')).toBeFocused()
        await page.evaluate((hash) => {
          window.location.hash = hash
        }, `#/${advisor}?keep=yes&rule=UNKNOWN`)
        await expect(page.locator('.advisor-rule-results')).toContainText('not present in this cached report')
        await expect(other).toBeHidden()
        expect(requests).toHaveLength(1)
        expect(requests[0].method).toBe('GET')
        expect(new URL(requests[0].url).pathname).toBe(`${apiPath}/${advisor}`)
      })

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
        const toggle = page.locator('.advisor-rule-toggle')
        await expect(toggle).toHaveAttribute('aria-expanded', 'false')
        await expect(page.getByRole('button', {name: `View violations for ${ruleId}`})).toHaveCount(0)
        await toggle.focus()
        await page.keyboard.press('Enter')
        await expect(toggle).toHaveAttribute('aria-expanded', 'true')
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

    test('retains a dismissed page through link history and restores preview focus only to usable controls', async ({
      page
    }) => {
      const body = report()
      body.results[0].dismissed = true
      body.results.push({
        ...body.results[0],
        id: 'OTHER-1',
        name: 'Other finding',
        dismissed: false,
        violationCount: 2,
        sampleViolations: ['other cached evidence']
      })
      body.rulesEvaluated = 2
      body.violationsFound = 1
      body.severityCounts = [
        {severity: 'HIGH', count: 0},
        {severity: 'LOW', count: 2}
      ]
      body.results[1].severity = 'LOW'
      body.violationDetails = {...body.violationDetails, total: 31, retained: 31}
      const requests = []
      await page.route(`**${apiPath}/architecture`, (route) => {
        requests.push({method: route.request().method(), kind: 'report'})
        return route.fulfill({json: body})
      })
      await page.route(`**${apiPath}/architecture/**`, (route) => {
        requests.push({method: route.request().method(), kind: 'unexpected'})
        return route.fulfill({status: 500, json: {error: 'Unexpected acquisition or mutation'}})
      })
      await page.route(`**${apiPath}/architecture/rules/*/violations?*`, (route) => {
        requests.push({method: route.request().method(), kind: 'page'})
        return route.fulfill({json: detailPage()})
      })
      await page.goto(`${uiPath}/#/architecture?keep=yes&rule=${ruleId}`)
      const row = page.locator(`.advisor-rule-result[data-rule-id="${ruleId}"]`)
      const detail = row.locator('.advisor-rule-violations')
      await row.getByRole('button', {name: `View violations for ${ruleId}`, exact: true}).click()
      await expect(detail.locator('li')).toHaveText(strings(29))
      await row.locator('.advisor-rule-toggle').focus()
      await page.keyboard.press('Enter')
      const search = page.getByLabel('Search rules and loaded evidence', {exact: true})
      await expect(row).toBeHidden()
      await expect(search).toBeFocused()
      await page.goBack()
      await expect(detail.locator('li')).toHaveText(strings(29))
      await expect(row.locator('.advisor-rule-toggle')).toBeFocused()
      const copyRule = row.locator(`button[aria-label="Copy ${ruleId} for AI"]`)
      await copyRule.click()
      const preview = page.getByLabel('Markdown export preview')
      await expect(preview).toHaveValue(/current loaded page, offset 0/)
      await expect(preview).toHaveValue(/example.Service28/)
      await page.evaluate(() => {
        window.location.hash = '#/architecture?keep=yes'
      })
      await expect(row).toBeHidden()
      // v-show keeps the old button connected; it must not receive focus while hidden.
      expect(await copyRule.evaluate((element) => element.isConnected)).toBe(true)
      await page.locator('.ai-export').getByRole('button', {name: 'Back', exact: true}).click()
      await expect(search).toBeFocused()
      await page.goBack()
      await expect(detail.locator('li')).toHaveText(strings(29))
      await page.getByRole('button', {name: 'Copy visible findings', exact: true}).click()
      await page.evaluate(() => {
        window.location.hash = '#/architecture?keep=yes'
      })
      await search.fill('no matching rules')
      await expect(page.getByRole('button', {name: 'Copy visible findings', exact: true})).toBeDisabled()
      await page.locator('.ai-export').getByRole('button', {name: 'Back', exact: true}).click()
      await expect(search).toBeFocused()
      expect(requests).toEqual([
        {method: 'GET', kind: 'report'},
        {method: 'GET', kind: 'page'}
      ])
    })

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
        await page.locator('.advisor-rule-toggle').click()
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
      await page.locator('.advisor-rule-toggle').click()
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
      await page.locator('.advisor-rule-toggle').click()
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
      test(`${advisor}: preserves a hidden loaded page without refreshing it after another rule dismissal`, async ({
        page
      }) => {
        await page.route(`**${apiPath}/panels`, async (route) => {
          const response = await route.fetch()
          const body = await response.json()
          body.panels = body.panels.map((panel) =>
            panel.id === advisor ? {...panel, available: true, enabled: true, readOnly: false} : panel
          )
          await route.fulfill({response, json: body})
        })
        const body = report('scan-1', 3)
        body.results.push({...body.results[0], id: 'RULE-B', name: 'Other rule'})
        body.results[0].sampleViolations = strings(3)
        body.results[1].sampleViolations = ['B one', 'B two', 'B three']
        body.results.forEach((rule) => (rule.suppressedCount = 0))
        body.rulesEvaluated = 2
        body.violationsFound = 2
        body.severityCounts = [{severity: 'HIGH', count: 6}]
        body.suppression = {suppressedFindings: 0, omitted: 0, incompleteRules: []}
        body.violationDetails = {...body.violationDetails, total: 6, retained: 6}
        let dismissed = false
        const reads = []
        const mutations = []
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: body}))
        await page.route(`**${apiPath}/${advisor}/rules/*/violations?*`, (route) => {
          const other = route.request().url().includes('/RULE-B/')
          reads.push(other ? 'B' : 'A')
          return route.fulfill({
            json: {
              ...detailPage({count: 3}),
              ruleId: other ? 'RULE-B' : ruleId,
              violations: other ? ['B one', 'B two', 'B three'] : strings(3),
              identities: ['v1:one', 'v1:two', 'v1:three'],
              suppressed: other && dismissed ? [true, false, false] : [],
              suppressedCount: other && dismissed ? 1 : 0
            }
          })
        })
        await page.route(`**${apiPath}/dismissed-findings`, (route) => {
          mutations.push([route.request().method(), route.request().postDataJSON()])
          dismissed = true
          body.results[1] = {...body.results[1], violationCount: 2, suppressedCount: 1}
          body.suppression.suppressedFindings = 1
          body.severityCounts[0].count = 5
          return route.fulfill({json: {}})
        })
        await page.goto(`${uiPath}/#/${advisor}?rule=${ruleId}`)
        const first = page.locator(`.advisor-rule-result[data-rule-id="${ruleId}"]`)
        const other = page.locator('.advisor-rule-result[data-rule-id="RULE-B"]')
        await first.getByRole('button', {name: `View violations for ${ruleId}`, exact: true}).click()
        await expect(first.locator('.advisor-rule-violations li')).toHaveCount(3)
        await first.locator('.advisor-rule-toggle').click()
        await other.locator('.advisor-rule-toggle').click()
        await other.getByRole('button', {name: 'View violations for RULE-B', exact: true}).click()
        await expect(other.locator('.advisor-rule-violations li')).toHaveCount(3)
        await other.getByRole('button', {name: 'Dismiss finding 1 of RULE-B', exact: true}).click()
        await page.getByRole('dialog').getByRole('button', {name: 'Dismiss finding', exact: true}).click()
        await expect(other.getByTestId('suppressed-finding')).toHaveCount(1)
        expect(reads).toEqual(['A', 'B', 'B'])
        expect(mutations).toEqual([['POST', {ruleId: 'RULE-B', identity: 'v1:one'}]])
        await first.locator('.advisor-rule-toggle').click()
        await expect(first.locator('.advisor-rule-violations li')).toHaveCount(3)
        await expect(first).toContainText('Dismissal state may be outdated')
        await expect(first.getByRole('button', {name: `Dismiss finding 1 of ${ruleId}`, exact: true})).toBeDisabled()
        await first.getByRole('button', {name: `Copy ${ruleId} for AI`, exact: true}).click()
        await expect(page.getByLabel('Markdown export preview')).toHaveValue(/Dismissal state may be outdated/)
        await page.locator('.ai-export').getByRole('button', {name: 'Back', exact: true}).click()
        expect(reads).toEqual(['A', 'B', 'B'])
        await first.getByRole('button', {name: `Refresh dismissal state for ${ruleId}`, exact: true}).click()
        await expect(first).not.toContainText('Dismissal state may be outdated')
        await expect(first.getByRole('button', {name: `Dismiss finding 1 of ${ruleId}`, exact: true})).toBeEnabled()
        expect(reads).toEqual(['A', 'B', 'B', 'A'])
      })

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
        await page.locator('.advisor-rule-toggle').click()
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
