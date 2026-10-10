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

    for (const status of [400, 403]) {
      test(`preserves the logger level and canonical HTTP ${status} refusal`, async ({page, openView}) => {
        const response = await page.request.get('/bootui/api/loggers?limit=1')
        expect(response.ok()).toBeTruthy()
        const native = await response.json()
        expect(native.loggers.length).toBeGreaterThan(0)
        const logger = {
          ...native.loggers[0],
          ...(status === 400 ? {name: 'io.github.jdubois.bootui.VisibleLogger'} : {})
        }
        const initial = {...native, loggers: [logger], page: {...native.page, matched: 1, total: 1}}
        const level = native.availableLevels.find((candidate) => candidate !== logger.configuredLevel)
        expect(level).toBeTruthy()
        const reason =
          status === 400
            ? `Refusing to change the level of BootUI's own logger '${logger.name}'.`
            : "Panel 'loggers' is read-only (bootui.panels.loggers.read-only=true)"
        let reads = 0
        let writes = 0
        await page.route('**/api/loggers{,?*}', (route) => {
          reads++
          return route.fulfill({json: initial})
        })
        await page.route(`**/api/loggers/${encodeURIComponent(logger.name)}`, (route) => {
          expect(route.request().method()).toBe('POST')
          writes++
          return route.fulfill({
            status,
            json: status === 400 ? {error: reason} : {error: 'BootUI panel access denied', panel: 'loggers', reason}
          })
        })
        await openView('loggers', 'Loggers')
        const row = page.locator('table tbody tr', {hasText: logger.name})
        await expect(row).toBeVisible()
        expect(writes).toBe(0)
        await row.getByRole('button', {name: level, exact: true}).click()
        await expect(page.locator('.alert-danger', {hasText: reason})).toBeVisible()
        await expect(row.locator('td').nth(1)).toHaveText(logger.configuredLevel || '—')
        await expect(page.locator('.alert-success')).toHaveCount(0)
        expect(reads).toBe(1)
        expect(writes).toBe(1)
      })
    }

    test('preserves a logger after malformed acknowledgement and a failed bounded follow-up', async ({
      page,
      openView
    }) => {
      const response = await page.request.get('/bootui/api/loggers?limit=1')
      expect(response.ok()).toBeTruthy()
      const native = await response.json()
      expect(native.loggers.length).toBeGreaterThan(0)
      const logger = native.loggers[0]
      const initial = {...native, loggers: [logger], page: {...native.page, matched: 1, total: 1}}
      const level = native.availableLevels.find((candidate) => candidate !== logger.configuredLevel)
      expect(level).toBeTruthy()
      let reads = 0
      let writes = 0
      await page.route('**/api/loggers{,?*}', (route) => {
        reads++
        return reads === 1
          ? route.fulfill({json: initial})
          : route.fulfill({status: 503, json: {error: 'Logger snapshot temporarily unavailable'}})
      })
      await page.route(`**/api/loggers/${encodeURIComponent(logger.name)}`, (route) => {
        expect(route.request().method()).toBe('POST')
        writes++
        return route.fulfill({json: {}})
      })
      await openView('loggers', 'Loggers')
      const row = page.locator('table tbody tr', {hasText: logger.name})
      await expect(row).toBeVisible()
      expect(writes).toBe(0)
      await row.getByRole('button', {name: level, exact: true}).click()
      await expect(page.locator('.alert-danger', {hasText: 'action outcome is unknown'})).toBeVisible()
      await expect.poll(() => reads).toBe(2)
      await expect(row.locator('td').nth(1)).toHaveText(logger.configuredLevel || '—')
      await expect(row.getByRole('button', {name: level, exact: true})).toBeEnabled()
      await expect(page.locator('.alert-success')).toHaveCount(0)
      expect(writes).toBe(1)
    })

    for (const [id, title, action] of [
      ['sql-trace', 'SQL Trace', 'recording'],
      ['transactions', 'Transactions', 'recording'],
      ['rest-client-trace', 'REST Client', 'recording'],
      ['websockets', 'WebSockets', 'capture']
    ]) {
      test(`keeps the acknowledged ${title} report after a late GET with auto-refresh off`, async ({
        page,
        openView
      }) => {
        const response = await page.request.get(`/bootui/api/${id}`)
        expect(response.ok()).toBeTruthy()
        const native = await response.json()
        test.skip(!native.available, native.unavailableReason)
        test.skip(id === 'websockets' && !native.frameCaptureSupported, native.frameCaptureUnavailableReason)
        const initial = {...native, capturing: true}
        const acknowledged = {...native, capturing: false}
        let reads = 0
        let writes = 0
        let stale
        let fresh
        await page.route(`**/api/${id}/stream`, (route) =>
          route.fulfill({contentType: 'text/event-stream', body: ': test snapshot\n\n'})
        )
        await page.route(`**/api/${id}`, (route) => {
          expect(route.request().method()).toBe('GET')
          reads++
          if (reads === 1) return route.fulfill({json: initial})
          if (reads === 2) stale = route
          else fresh = route
        })
        await page.route(`**/api/${id}/${action}`, (route) => {
          expect(route.request().method()).toBe('POST')
          expect(route.request().postDataJSON()).toEqual({enabled: false})
          writes++
          return route.fulfill({json: acknowledged})
        })
        await openView(id, title)
        await expect(page.getByRole('button', {name: 'Pause', exact: true})).toBeEnabled()
        const autoRefresh = page.getByRole('checkbox', {name: 'Toggle auto-refresh'})
        await autoRefresh.uncheck()
        expect(writes).toBe(0)
        await page.getByRole('button', {name: 'Refresh panel', exact: true}).click()
        await expect.poll(() => Boolean(stale)).toBe(true)
        await page.getByRole('button', {name: 'Pause', exact: true}).click()
        const resume = page.getByRole('button', {name: 'Resume', exact: true})
        await expect(resume).toBeVisible()
        await stale.fulfill({json: initial})
        await expect.poll(() => Boolean(fresh)).toBe(true)
        await expect(resume).toBeVisible()
        await fresh.fulfill({status: 503, json: {error: 'Capture snapshot temporarily unavailable'}})
        await expect(page.locator('.alert-danger', {hasText: 'HTTP 503'})).toBeVisible()
        await expect(resume).toBeEnabled()
        await expect(page.locator('.alert-success')).toBeVisible()
        await expect(autoRefresh).not.toBeChecked()
        expect(reads).toBe(3)
        expect(writes).toBe(1)
      })

      for (const outcome of ['unavailable', 'unchanged']) {
        test(`reports the actual ${outcome} ${title} action outcome without fake success`, async ({page, openView}) => {
          const response = await page.request.get(`/bootui/api/${id}`)
          expect(response.ok()).toBeTruthy()
          const native = await response.json()
          test.skip(!native.available, native.unavailableReason)
          test.skip(id === 'websockets' && !native.frameCaptureSupported, native.frameCaptureUnavailableReason)
          const initial = {...native, capturing: true}
          // The native unavailable constructors zero counters, flags and lists, retaining the full DTO shape.
          const unavailable = Object.fromEntries(
            Object.entries(native).map(([key, value]) => [
              key,
              key === 'stats'
                ? Object.fromEntries(Object.keys(value).map((counter) => [counter, 0]))
                : Array.isArray(value)
                  ? []
                  : typeof value === 'boolean'
                    ? false
                    : typeof value === 'number'
                      ? 0
                      : null
            ])
          )
          const reason = `${title} recorder disappeared during the action.`
          unavailable.unavailableReason = reason
          const acknowledged = outcome === 'unavailable' ? unavailable : initial
          let reads = 0
          let writes = 0
          await page.route(`**/api/${id}/stream`, (route) =>
            route.fulfill({contentType: 'text/event-stream', body: ': test snapshot\n\n'})
          )
          await page.route(`**/api/${id}`, (route) => {
            reads++
            return route.fulfill({json: initial})
          })
          await page.route(`**/api/${id}/${action}`, (route) => {
            expect(route.request().method()).toBe('POST')
            writes++
            return route.fulfill({json: acknowledged})
          })
          await openView(id, title)
          await expect(page.getByRole('button', {name: 'Pause', exact: true})).toBeEnabled()
          await page.getByRole('checkbox', {name: 'Toggle auto-refresh'}).uncheck()
          expect(writes).toBe(0)
          await page.getByRole('button', {name: 'Pause', exact: true}).click()
          await expect(
            page.locator('.alert-warning', {hasText: outcome === 'unavailable' ? reason : 'unchanged'})
          ).toBeVisible()
          await expect(page.locator('.alert-success')).toHaveCount(0)
          const pause = page.getByRole('button', {name: 'Pause', exact: true})
          if (outcome === 'unavailable' && id === 'websockets') await expect(pause).toHaveCount(0)
          else if (outcome === 'unavailable') await expect(pause).toBeDisabled()
          else await expect(pause).toBeEnabled()
          expect(reads).toBe(1)
          expect(writes).toBe(1)
        })
      }
    }

    test('honors native metadata-only WebSockets without offering a capture mutation', async ({page, openView}) => {
      const response = await page.request.get('/bootui/api/websockets')
      expect(response.ok()).toBeTruthy()
      const native = await response.json()
      test.skip(!native.available, native.unavailableReason)
      test.skip(native.frameCaptureSupported, 'This runtime has a native frame capture seam.')
      let writes = 0
      await page.route('**/api/websockets', (route) => route.fulfill({json: native}))
      await page.route('**/api/websockets/stream', (route) =>
        route.fulfill({contentType: 'text/event-stream', body: ': test snapshot\n\n'})
      )
      await page.route('**/api/websockets/capture', (route) => {
        writes++
        return route.fulfill({status: 500, json: {error: 'Unexpected metadata-only capture write'}})
      })
      await openView('websockets', 'WebSockets')
      await expect(
        page.locator('.alert-secondary', {
          hasText: native.frameCaptureUnavailableReason || 'Frame capture is not supported on this stack.'
        })
      ).toBeVisible()
      await expect(page.getByRole('button', {name: 'Pause', exact: true})).toHaveCount(0)
      await expect(page.getByRole('button', {name: 'Resume', exact: true})).toHaveCount(0)
      expect(writes).toBe(0)
    })

    for (const outcome of ['enabled', 'unavailable']) {
      test(`keeps the actual ${outcome} Hibernate activation report, including a late disabled GET`, async ({
        page,
        openView
      }) => {
        const manifestResponse = await page.request.get('/bootui/api/panels')
        expect(manifestResponse.ok()).toBeTruthy()
        const manifest = await manifestResponse.json()
        const capability = manifest.panels.find((panel) => panel.id === 'hibernate-statistics')
        expect(capability).toBeTruthy()
        test.skip(capability.available === false || capability.enabled === false, capability.unavailableReason)
        const response = await page.request.get('/bootui/api/hibernate-statistics')
        expect(response.ok()).toBeTruthy()
        const native = await response.json()
        expect(native.available).toBe(true)
        expect(native.statistics).not.toBeNull()
        const disabled = {
          ...native,
          available: false,
          enableAvailable: true,
          unavailableReason: 'Hibernate statistics are disabled.',
          statistics: null
        }
        const acknowledged =
          outcome === 'enabled'
            ? {...native, available: true, enableAvailable: false, unavailableReason: null}
            : {...disabled, enableAvailable: false, unavailableReason: 'SessionFactory disappeared during activation.'}
        let reads = 0
        let writes = 0
        let stale
        let fresh
        await page.route('**/api/hibernate-statistics', (route) => {
          reads++
          if (reads === 1) return route.fulfill({json: disabled})
          if (reads === 2) stale = route
          else fresh = route
        })
        await page.route('**/api/hibernate-statistics/enable', (route) => {
          expect(route.request().method()).toBe('POST')
          writes++
          return route.fulfill({json: acknowledged})
        })
        await openView('hibernate-statistics', 'Hibernate Statistics')
        await expect(page.locator('#enable-hibernate-statistics')).toBeEnabled()
        const autoRefresh = page.getByRole('checkbox', {name: 'Toggle auto-refresh'})
        await autoRefresh.uncheck()
        expect(writes).toBe(0)
        await page.getByRole('button', {name: 'Refresh panel', exact: true}).click()
        await expect.poll(() => Boolean(stale)).toBe(true)
        await page.locator('#enable-hibernate-statistics').click()
        const accepted =
          outcome === 'enabled'
            ? page.getByText('Collecting', {exact: true})
            : page.getByText(acknowledged.unavailableReason, {exact: true}).first()
        await expect(accepted).toBeVisible()
        await stale.fulfill({json: disabled})
        await expect.poll(() => Boolean(fresh)).toBe(true)
        await expect(accepted).toBeVisible()
        await fresh.fulfill({status: 503, json: {error: 'Statistics snapshot temporarily unavailable'}})
        await expect(page.locator('.alert-danger', {hasText: 'HTTP 503'})).toBeVisible()
        await expect(accepted).toBeVisible()
        if (outcome === 'enabled') await expect(page.locator('.alert-success')).toBeVisible()
        else {
          await expect(page.locator('.alert-warning', {hasText: acknowledged.unavailableReason})).toBeVisible()
          await expect(page.locator('.alert-success')).toHaveCount(0)
        }
        await expect(autoRefresh).not.toBeChecked()
        expect(reads).toBe(3)
        expect(writes).toBe(1)
      })
    }

    test('catches a lost configuration-removal response without retrying a Spring-only write', async ({
      page,
      openView
    }) => {
      const manifestResponse = await page.request.get('/bootui/api/panels')
      expect(manifestResponse.ok()).toBeTruthy()
      const manifest = await manifestResponse.json()
      const capability = manifest.panels.find((panel) => panel.id === 'config')
      expect(capability).toBeTruthy()
      if (manifest.platform === 'quarkus') expect(capability.readOnly).toBe(true)
      test.skip(capability.readOnly, capability.readOnlyReason || 'Runtime configuration writes are unsupported.')
      const response = await page.request.get('/bootui/api/config?q=sample.greeting&limit=1')
      expect(response.ok()).toBeTruthy()
      const native = await response.json()
      expect(native.properties.length).toBe(1)
      const property = {...native.properties[0], override: true, source: 'bootuiOverrides'}
      const initial = {
        ...native,
        properties: [property],
        overrideCount: 1,
        page: {...native.page, matched: 1, total: 1}
      }
      let reads = 0
      let writes = 0
      await page.route('**/api/config{,?*}', (route) => {
        reads++
        return route.fulfill({json: initial})
      })
      await page.route(`**/api/config/overrides/${encodeURIComponent(property.name)}`, (route) => {
        expect(route.request().method()).toBe('DELETE')
        writes++
        return route.abort('failed')
      })
      await openView('config', 'Configuration')
      const row = page.locator('table tbody tr', {hasText: property.name})
      const remove = row.getByTitle('Remove override', {exact: true})
      await expect(remove).toBeEnabled()
      expect(writes).toBe(0)
      await remove.click()
      await acceptConfirm(page)
      await expect(page.locator('.alert-danger', {hasText: 'action outcome is unknown'})).toBeVisible()
      await expect.poll(() => reads).toBe(2)
      await expect(row.locator('.badge', {hasText: 'override'})).toBeVisible()
      await expect(remove).toBeEnabled()
      await expect(page.locator('.alert-success')).toHaveCount(0)
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
