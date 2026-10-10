// @ts-check

export function registerDiagnosticAcknowledgementTests(test, expect, acceptConfirm) {
  test.describe('Diagnostic acknowledgements', () => {
    test('qualifies positive outside-JVM changes when framework comparison rows are empty', async ({
      page,
      openView
    }) => {
      const response = await page.request.get('/bootui/api/runtime-insights')
      expect(response.ok()).toBeTruthy()
      const report = await response.json()
      await page.route('**/api/runtime-insights', (route) =>
        route.fulfill({
          json: {
            ...report,
            available: true,
            window: {...report.window, runId: 'run-5', requests: 9, retainedEvents: 9},
            observations: [],
            checks: [],
            limitations: [],
            notExercised: [],
            notExercisedOmitted: 0
          }
        })
      )
      await page.route('**/api/runtime-insights/comparison', (route) =>
        route.fulfill({
          json: {
            status: 'COMPARED',
            reason: null,
            current: {runId: 'run-5', ordinal: 5, requests: 9, source: 'CURRENT'},
            previous: {runId: 'run-4', ordinal: 4, requests: 9, source: 'MEMORY'},
            runs: [],
            behavior: [],
            edges: [],
            latency: [],
            limitations: [],
            sideEffects: {
              available: true,
              unavailableReason: null,
              partial: false,
              changesTotal: 1,
              sensors: [{sensor: 'network', status: 'COMPARED', reason: null, added: 1, removed: 0, notExercised: 0}],
              limitations: [],
              changes: [
                {
                  sensor: 'network',
                  owner: 'GET /orders',
                  kind: 'connect',
                  target: 'api.example.com:443',
                  scope: 'route',
                  change: 'ADDED',
                  count: 1,
                  client: null,
                  sentence: '`GET /orders` now connects to `api.example.com:443`.'
                }
              ]
            }
          }
        })
      )
      await openView('runtime-insights', 'Runtime Insights')
      const summary = page.locator('.insight-comparison-link')
      await expect(summary).toContainText('outside-JVM changes recorded')
      await expect(summary).not.toContainText('No change in behavior')
      await summary.click()
      await expect(page.locator('[data-section="side-effects"]')).toContainText('api.example.com:443')
      await expect(page.locator('.insight-comparison')).toContainText(
        'No changes were found in the compared framework behavior or runtime-model edges.'
      )
    })

    test('preserves MCP status and reports unknown after malformed acknowledgement and failed follow-up', async ({
      page,
      openView
    }) => {
      const response = await page.request.get('/bootui/api/mcp-server')
      expect(response.ok()).toBeTruthy()
      const status = await response.json()
      let reads = 0
      let writes = 0
      await page.route('**/api/mcp-server', (route) => {
        reads++
        return reads === 1
          ? route.fulfill({json: status})
          : route.fulfill({status: 503, json: {error: 'Status temporarily unavailable'}})
      })
      await page.route('**/api/mcp-server/toggle', (route) => {
        writes++
        return route.fulfill({status: 200, contentType: 'application/json', body: '{'})
      })
      await openView('mcp-server', 'MCP Server')
      const toggle = page.locator('#mcp-enabled-toggle')
      await expect(toggle).toBeVisible()
      await page.getByRole('checkbox', {name: 'Toggle auto-refresh'}).uncheck()
      await toggle.click()
      await expect(page.locator('.alert-danger', {hasText: 'action outcome is unknown'})).toBeVisible()
      await expect(toggle).toBeChecked({checked: status.enabled})
      await expect(page.locator('.alert-success')).toHaveCount(0)
      await expect.poll(() => reads).toBe(2)
      expect(writes).toBe(1)
    })

    test('keeps the acknowledged MCP state when a pre-action poll completes late with auto-refresh off', async ({
      page,
      openView
    }) => {
      const response = await page.request.get('/bootui/api/mcp-server')
      expect(response.ok()).toBeTruthy()
      const status = {...(await response.json()), enabled: false}
      let reads = 0
      let writes = 0
      let stale
      let fresh
      await page.route('**/api/mcp-server', (route) => {
        reads++
        if (reads === 1) return route.fulfill({json: status})
        if (reads === 2) stale = route
        else fresh = route
      })
      await page.route('**/api/mcp-server/toggle', (route) => {
        writes++
        return route.fulfill({json: {...status, enabled: true, overridden: true}})
      })
      await openView('mcp-server', 'MCP Server')
      const toggle = page.locator('#mcp-enabled-toggle')
      await expect(toggle).toBeVisible()
      await page.getByRole('checkbox', {name: 'Toggle auto-refresh'}).uncheck()
      await page.getByRole('button', {name: 'Refresh panel', exact: true}).click()
      await expect.poll(() => Boolean(stale)).toBe(true)
      await toggle.click()
      await expect(toggle).toBeChecked()
      await stale.fulfill({json: status})
      await expect.poll(() => Boolean(fresh)).toBe(true)
      await expect(toggle).toBeChecked()
      await fresh.fulfill({json: {...status, enabled: true, overridden: true, callCount: 9876}})
      await expect(page.getByTestId('mcp-call-stats')).toContainText('9876')
      await expect(page.locator('.toggle-card')).toContainText('currently overridden')
      expect(reads).toBe(3)
      expect(writes).toBe(1)
    })

    test('re-reads after a bodyless trace clear without accepting an old poll or discarding new traces', async ({
      page,
      openView
    }) => {
      const report = (path) => ({
        enabled: true,
        retained: 1,
        capacity: 500,
        traces: [
          {
            traceId: path,
            httpPath: path,
            rootSpanName: path,
            services: ['sample'],
            spanCount: 1,
            hasError: false,
            hasAi: false,
            startEpochNanos: 1000000000,
            endEpochNanos: 2000000000,
            durationNanos: 1000000000
          }
        ]
      })
      let reads = 0
      let writes = 0
      let stale
      let fresh
      await page.route('**/api/traces', (route) => {
        if (route.request().method() === 'DELETE') {
          writes++
          return route.fulfill({status: 204, body: ''})
        }
        reads++
        if (reads === 1) return route.fulfill({json: report('/initial')})
        if (reads === 2) stale = route
        else fresh = route
      })
      await openView('traces', 'Traces')
      await expect(page.locator('table')).toContainText('/initial')
      await page.getByRole('checkbox', {name: 'Toggle auto-refresh'}).uncheck()
      await page.getByRole('button', {name: 'Refresh panel', exact: true}).click()
      await expect.poll(() => Boolean(stale)).toBe(true)
      await page.getByRole('button', {name: 'Clear', exact: true}).click()
      await acceptConfirm(page)
      await expect.poll(() => writes).toBe(1)
      await stale.fulfill({json: report('/obsolete')})
      await expect.poll(() => Boolean(fresh)).toBe(true)
      await expect(page.locator('table')).not.toContainText('/obsolete')
      await fresh.fulfill({json: report('/captured-after-clear')})
      await expect(page.locator('table')).toContainText('/captured-after-clear')
      await expect(page.locator('.alert-success')).toContainText('Cleared retained traces.')
      expect(reads).toBe(3)
      expect(writes).toBe(1)
    })

    test('does not acknowledge cache clearing from an empty JSON object', async ({page, openView}) => {
      const response = await page.request.get('/bootui/api/cache')
      expect(response.ok()).toBeTruthy()
      const report = await response.json()
      let reads = 0
      let writes = 0
      await page.route('**/api/cache', (route) => {
        reads++
        return route.fulfill({json: report})
      })
      await page.route('**/api/cache/clear', (route) => {
        writes++
        return route.fulfill({json: {}})
      })
      await openView('cache', 'Cache')
      const clear = page.getByRole('button', {name: 'Clear all', exact: true})
      await expect(clear).toBeEnabled()
      await page.getByRole('checkbox', {name: 'Toggle auto-refresh'}).uncheck()
      await clear.click()
      await acceptConfirm(page)
      await expect(page.locator('.alert-danger', {hasText: 'action outcome is unknown'})).toBeVisible()
      await expect(page.locator('.alert-success')).toHaveCount(0)
      await expect.poll(() => reads).toBe(2)
      expect(writes).toBe(1)
    })

    for (const [state, body, code] of [
      ['recording', {enabled: true}, 200],
      ['disabled', {enabled: false}, 200],
      ['unknown', {}, 200],
      ['unavailable', {error: 'Journal unavailable'}, 503]
    ]) {
      test(`checks the journal only on database disclosure and qualifies ${state} capture`, async ({
        page,
        openView
      }) => {
        const response = await page.request.get('/bootui/api/activity')
        expect(response.ok()).toBeTruthy()
        const report = {
          ...(await response.json()),
          pageInfo: {persistent: false, hasMore: false, nextCursor: null},
          persistenceOption: {active: false, dataSourceAvailable: true, tableName: 'bootui_activity'}
        }
        let journalReads = 0
        let writes = 0
        await page.route('**/api/activity{,?*}', (route) => route.fulfill({json: report}))
        await page.route('**/api/activity/journal', (route) => {
          journalReads++
          return route.fulfill({status: code, json: body})
        })
        await page.route('**/api/activity/use-existing-datasource', (route) => {
          writes++
          return route.fulfill({status: 500, json: {error: 'Unexpected write in a disclosure-only test'}})
        })
        await openView('activity', 'Live Activity')
        const disclosure = page.getByRole('button', {name: 'Use a database', exact: true})
        await expect(disclosure).toBeVisible()
        expect(journalReads).toBe(0)
        await disclosure.click()
        const action = page.getByRole('button', {name: 'Use the existing datasource', exact: true})
        await expect(page.locator('.activity-database-info')).toContainText('already configured')
        if (state === 'recording') await expect(action).toBeEnabled()
        else {
          await expect(page.locator('.activity-database-info')).toContainText(
            state === 'disabled' ? 'The runtime journal is disabled.' : 'Could not verify the runtime journal status'
          )
          await expect(action).toBeDisabled()
        }
        await expect.poll(() => journalReads).toBe(1)
        expect(writes).toBe(0)
      })
    }
  })
}
