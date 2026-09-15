// @ts-check
import {mysqlDataSource, mysqlReport} from './mysql-fixture.js'

// These fixtures prove browser behavior against each real adapter's shell and manifest.
// They do NOT establish MySQL query correctness or replace live-MySQL HTTP smoke tests.
export function registerMysqlTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  async function openReport(page, body, panelOverrides = {}) {
    const response = await page.request.get(`${apiPath}/panels`)
    expect(response.ok()).toBe(true)
    const manifest = await response.json()
    const panel = manifest.panels.find((candidate) => candidate.id === 'mysql')
    expect(panel, 'The real adapter must register the MySQL panel').toBeTruthy()
    Object.assign(panel, {available: true, enabled: true, readOnly: false, unavailableReason: null}, panelOverrides)
    await page.route(`**${apiPath}/panels`, (route) => route.fulfill({json: manifest}))
    await page.route(`**${apiPath}/mysql`, (route) => route.fulfill({json: body}))
    await page.goto(`${uiPath}/#/mysql`)
    await expect(page.getByRole('heading', {name: 'MySQL', exact: true})).toBeVisible()
  }

  test.describe('MySQL explicit operational read', () => {
    test('opens cached data without a POST and reads only after the explicit action', async ({page}) => {
      let reads = 0
      await page.route(`**${apiPath}/mysql/read`, (route) => {
        expect(route.request().method()).toBe('POST')
        expect(route.request().postData()).toBeNull()
        reads++
        return route.fulfill({json: mysqlReport()})
      })
      await openReport(page, mysqlReport({status: 'NOT_READ', dataSources: [], dataSourcesRead: 0, readAt: null}))
      await expect(page.getByText('No MySQL data yet', {exact: true})).toBeVisible()
      expect(reads).toBe(0)
      await page.getByRole('button', {name: 'Run MySQL read', exact: true}).click()
      await expect(page.getByRole('article', {name: 'orders datasource'})).toBeVisible()
      expect(reads).toBe(1)
      await expect(page).toHaveURL(new RegExp(`${uiPath}/#/mysql$`))
    })

    test('uses manifest absence and never probes an unavailable endpoint', async ({page}) => {
      let requests = 0
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith(`${apiPath}/mysql`)) requests++
      })
      const reason = 'No supported MySQL JDBC datasource is configured. Reactive clients alone are not supported.'
      await openReport(page, null, {available: false, unavailableReason: reason})
      await expect(page.locator('.mysql-panel').getByText(reason, {exact: true})).toBeVisible()
      await expect(page.getByRole('button', {name: 'Run MySQL read', exact: true})).toBeDisabled()
      await expect(page.locator('main [role="alert"], main [role="status"]')).toHaveCount(1)
      expect(requests).toBe(0)
    })

    test('blocks the external action under read-only policy but keeps the cached report', async ({page}) => {
      await openReport(page, mysqlReport(), {
        readOnly: true,
        readOnlyReason: 'External reads are disabled by panel policy.'
      })
      await expect(page.getByRole('button', {name: 'Run MySQL read', exact: true})).toBeDisabled()
      await expect(page.getByRole('article', {name: 'orders datasource'})).toBeVisible()
      await expect(
        page.locator('.mysql-panel').getByText('External reads are disabled by panel policy.', {exact: false})
      ).toBeVisible()
    })

    for (const status of [409, 500]) {
      test(`retains cached evidence on HTTP ${status} with one error announcement`, async ({page}) => {
        await page.route(`**${apiPath}/mysql/read`, (route) =>
          route.fulfill({
            status,
            json:
              status === 409
                ? {error: 'BootUI action already in progress', message: 'A MySQL read is already running.'}
                : {error: 'Read unavailable'}
          })
        )
        await openReport(page, mysqlReport())
        await page.getByRole('button', {name: 'Run MySQL read', exact: true}).click()
        await expect(page.getByText('The last completed report is still shown below.', {exact: false})).toBeVisible()
        await expect(page.getByRole('article', {name: 'orders datasource'})).toBeVisible()
        await expect(page.locator('main [role="alert"], main [role="status"]')).toHaveCount(1)
      })
    }

    test('keeps per-datasource tabs keyboard accessible with one selected panel each', async ({page}) => {
      await openReport(
        page,
        mysqlReport({dataSourcesRead: 2, dataSources: [mysqlDataSource(), mysqlDataSource({name: 'archive'})]})
      )
      const first = page.getByRole('tablist', {name: 'orders sections'})
      const second = page.getByRole('tablist', {name: 'archive sections'})
      await first.getByRole('tab', {name: /^Sessions/}).focus()
      await page.keyboard.press('ArrowRight')
      await expect(first.getByRole('tab', {name: /^Statements/})).toBeFocused()
      await page.keyboard.press('End')
      await expect(first.getByRole('tab', {name: /^Settings/})).toBeFocused()
      await page.keyboard.press('ArrowRight')
      await expect(first.getByRole('tab', {name: /^Sessions/})).toBeFocused()
      await page.keyboard.press('ArrowLeft')
      await expect(first.getByRole('tab', {name: /^Settings/})).toBeFocused()
      await page.keyboard.press('Home')
      await expect(first.getByRole('tab', {name: /^Sessions/})).toBeFocused()
      await expect(second.getByRole('tab', {selected: true})).toHaveText(/^Sessions/)
      await expect(page.getByRole('tab', {selected: true})).toHaveCount(2)
      await expect(page.getByRole('tabpanel')).toHaveCount(2)
      for (const panel of await page.getByRole('tabpanel').all()) {
        const tabId = await panel.getAttribute('aria-labelledby')
        await expect(page.locator(`#${tabId}`)).toHaveAttribute('aria-controls', await panel.getAttribute('id'))
      }
      const ids = await page.locator('[id^="mysql-"]').evaluateAll((elements) => elements.map((element) => element.id))
      expect(new Set(ids).size).toBe(ids.length)
    })

    test('shows every partial reason alongside BootUI row limits, without hiding usable sections', async ({page}) => {
      const source = mysqlDataSource({status: 'PARTIAL', truncated: true})
      Object.assign(
        source.sections.find((part) => part.id === 'statements'),
        {rowCount: 100, truncated: true}
      )
      Object.assign(
        source.sections.find((part) => part.id === 'sessions'),
        {reason: 'Transaction instrumentation is disabled.'}
      )
      Object.assign(
        source.sections.find((part) => part.id === 'replication'),
        {status: 'FAILED', reason: 'Replication access was denied.', hint: 'Grant table-specific SELECT.'}
      )
      await openReport(
        page,
        mysqlReport({
          status: 'PARTIAL',
          truncated: true,
          dataSources: [source],
          limitations: ['The server digest capacity was exhausted.']
        })
      )
      const status = page.getByRole('status').filter({hasText: 'Incomplete read.'})
      await expect(status).toHaveCount(1)
      await expect(status).toHaveClass(/alert-warning/)
      for (const reason of [
        'top 100 statements',
        'Transaction instrumentation is disabled',
        'Replication access was denied',
        'server digest capacity was exhausted'
      ])
        await expect(status).toContainText(reason)
      await page.getByRole('tab', {name: /^Replication/}).click()
      await expect(page.getByRole('tabpanel')).toContainText('Grant table-specific SELECT.')
      await expect(page.getByRole('tabpanel')).toContainText('Missing observations do not establish absence.')
      await expect(page.getByRole('heading', {name: 'Vital signs', exact: true})).toBeVisible()
    })

    test('presents a capped ranking as normal information rather than a warning', async ({page}) => {
      const source = mysqlDataSource({status: 'PARTIAL', truncated: true})
      Object.assign(
        source.sections.find((part) => part.id === 'statements'),
        {rowCount: 100, truncated: true}
      )
      await openReport(
        page,
        mysqlReport({
          status: 'PARTIAL',
          truncated: true,
          dataSources: [source],
          limitations: ['orders: Statements retained 100 rows; additional rows were omitted by BootUI caps.']
        })
      )
      await expect(page.getByRole('status')).toContainText('Limited results')
      await expect(page.getByRole('status')).not.toHaveClass(/alert/)
      await expect(page.locator('.mysql-panel .alert-warning, .mysql-panel .text-bg-warning')).toHaveCount(0)
      await expect(page.getByRole('tab', {name: /^Statements/})).toContainText('Limited')
      await page.getByRole('tab', {name: /^Statements/}).click()
      await expect(page.getByRole('tabpanel')).toContainText('Showing the top 100')
      await expect(page.getByRole('tabpanel').locator('.badge').first()).toHaveClass(/text-bg-secondary/)
      await expect(page.getByText('Incomplete read.', {exact: true})).toHaveCount(0)
      await expect(page.locator('.mysql-panel')).not.toContainText('Partly read')
    })

    test('sorts exact large counters, filters retained rows locally, and distinguishes missing timing', async ({
      page
    }) => {
      const source = mysqlDataSource()
      source.vitalSigns[0].value = null
      source.statements = [
        {...source.statements[0], digestText: 'larger digest', calls: '9007199254740993', totalTimeMs: null},
        {...source.statements[1], digestText: 'smaller digest', calls: '9007199254740992', totalTimeMs: 0}
      ]
      let reads = 0
      page.on('request', (request) => {
        if (request.method() === 'POST' && new URL(request.url()).pathname === `${apiPath}/mysql/read`) reads++
      })
      await openReport(page, mysqlReport({dataSources: [source]}))
      await expect(page.locator('.mysql-panel dd').first()).toHaveText('—')
      await page.getByRole('tab', {name: /^Statements/}).click()
      const panel = page.getByRole('tabpanel')
      await panel.getByRole('button', {name: 'Calls', exact: true}).click()
      await expect(panel.locator('tbody tr').first()).toContainText('smaller digest')
      await expect(panel.getByRole('cell', {name: '9,007,199,254,740,993', exact: true})).toBeVisible()
      await expect(panel.locator('tbody tr').first().locator('td').nth(2)).toHaveText('0 ms')
      await expect(panel.locator('tbody tr').last().locator('td').nth(2)).toHaveText('—')
      await panel.getByLabel('Filter retained orders statements', {exact: true}).fill('larger digest')
      await expect(panel.locator('tbody tr')).toHaveCount(1)
      await expect(panel).toContainText('1 of 2 retained rows · local filter only')
      expect(reads).toBe(0)
    })

    test('preserves call-ranked fallback qualifications without claiming a top-time ranking', async ({page}) => {
      const source = mysqlDataSource({status: 'PARTIAL', truncated: true})
      Object.assign(
        source.sections.find((part) => part.id === 'statements'),
        {
          truncated: true,
          reason: 'Statement timing is unavailable; ranking falls back to calls.'
        }
      )
      await openReport(page, mysqlReport({status: 'PARTIAL', truncated: true, dataSources: [source]}))
      await page.getByRole('tab', {name: /^Statements/}).click()
      await expect(page.getByRole('tabpanel')).toContainText('ranking falls back to calls')
      await expect(page.getByRole('tabpanel')).toContainText('Showing 6 retained rows')
      await expect(page.locator('main')).not.toContainText('statements by total execution time')
    })

    test('shows sanitized metadata-only evidence without raw SQL or lock payloads', async ({page}) => {
      const source = mysqlDataSource()
      source.statements[0].digestText = null
      source.sessions[0].host = null
      // Unknown fields are deliberately ignored, even if a future transport accidentally adds them.
      source.statements[0]['querySampleText'] = 'private-sample-marker'
      source.sessions[0]['processlistInfo'] = 'private-session-marker'
      source.lockWaits[0]['lockData'] = 'private-lock-marker'
      await openReport(page, mysqlReport({dataSources: [source]}))
      await expect(page.getByRole('heading', {name: 'Row-lock waits', exact: true})).toBeVisible()
      await expect(page.getByRole('heading', {name: 'Pending metadata locks', exact: true})).toBeVisible()
      await expect(page.locator('main')).not.toContainText('private-session-marker')
      await expect(page.locator('main')).not.toContainText('private-lock-marker')
      await page.getByRole('tab', {name: /^Statements/}).click()
      await expect(page.getByRole('tabpanel').locator('tbody tr').first().locator('td').first()).toHaveText('—')
      await expect(page.locator('main')).not.toContainText('private-sample-marker')
    })

    for (const outcome of ['AVAILABLE', 'FAILED', 'PARTIAL']) {
      test(`distinguishes empty replication from ${outcome} evidence`, async ({page}) => {
        const source = mysqlDataSource({replication: []})
        Object.assign(
          source.sections.find((part) => part.id === 'replication'),
          {
            status: outcome === 'FAILED' ? 'FAILED' : 'AVAILABLE',
            reason: outcome === 'AVAILABLE' ? null : 'The applier source could not be read.'
          }
        )
        await openReport(page, mysqlReport({dataSources: [source]}))
        await page.getByRole('tab', {name: /^Replication/}).click()
        const panel = page.getByRole('tabpanel')
        if (outcome === 'AVAILABLE') {
          await expect(panel).toContainText('No local replication channels were observed in this successful read.')
        } else {
          await expect(panel).toContainText('The applier source could not be read.')
          await expect(panel).not.toContainText('No local replication channels were observed')
        }
      })
    }

    for (const theme of ['light', 'dark']) {
      test(`keeps narrow ${theme} tabs visible, focused, and free of page overflow`, async ({page}) => {
        await page.setViewportSize({width: 390, height: 844})
        await page.addInitScript((theme) => localStorage.setItem('bootui.theme', theme), theme)
        await openReport(page, mysqlReport())
        await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)
        const tabs = page.getByRole('tablist', {name: 'orders sections'})
        await tabs.getByRole('tab', {name: /^Sessions/}).focus()
        await page.keyboard.press('End')
        const selected = tabs.getByRole('tab', {name: /^Settings/})
        await expect(selected).toBeFocused()
        await expect(selected).toHaveAttribute('aria-selected', 'true')
        const outline = await selected.evaluate((element) => getComputedStyle(element).outlineStyle)
        expect(outline).not.toBe('none')
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
        await expect(page.getByRole('tabpanel')).toHaveCount(1)
      })
    }
  })
}
