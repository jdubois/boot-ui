// @ts-check

export function registerDiagnosticAcknowledgementTests(test, expect, acceptConfirm) {
  async function nativeResourceProfile(page) {
    const manifestResponse = await page.request.get('/bootui/api/panels')
    expect(manifestResponse.ok()).toBeTruthy()
    const manifest = await manifestResponse.json()
    const capability = manifest.panels.find((panel) => panel.id === 'runtime-insights')
    expect(capability).toBeTruthy()
    test.skip(capability.available === false || capability.enabled === false, capability.unavailableReason)
    test.skip(capability.readOnly, capability.readOnlyReason)
    const response = await page.request.get('/bootui/api/runtime-insights/resource-profile')
    expect(response.ok()).toBeTruthy()
    const native = await response.json()
    test.skip(native.state === 'UNAVAILABLE', native.reason)
    expect(Array.isArray(native.routes)).toBe(true)
    expect(Array.isArray(native.limitations)).toBe(true)
    return native
  }

  async function pauseClock(page) {
    const time = new Date()
    await page.clock.install({time})
    await page.clock.pauseAt(new Date(time.getTime() + 1000))
    return time.getTime() + 1000
  }

  test.describe('Diagnostic acknowledgements', () => {
    test('keeps an accepted exception triage status after a deferred manual read with auto-refresh off', async ({
      page,
      openView
    }) => {
      const manifestResponse = await page.request.get('/bootui/api/panels')
      expect(manifestResponse.ok()).toBeTruthy()
      const manifest = await manifestResponse.json()
      const capability = manifest.panels.find((panel) => panel.id === 'exceptions')
      expect(capability).toBeTruthy()
      test.skip(capability.available === false || capability.enabled === false, capability.unavailableReason)
      test.skip(capability.readOnly, capability.readOnlyReason)
      const failure = await page.request.get('/api/sample/boom')
      expect(failure.status()).toBe(500)
      const response = await page.request.get('/bootui/api/exceptions')
      expect(response.ok()).toBeTruthy()
      const native = await response.json()
      expect(native.available).toBe(true)
      const group = native.groups.find((candidate) => candidate.lastRequestPath === '/api/sample/boom')
      expect(group).toBeTruthy()
      const initial = {...native, groups: [{...group, status: 'OPEN'}]}
      const acknowledged = {...group, status: 'RESOLVED'}
      let reads = 0
      let writes = 0
      let stale
      let fresh
      await page.route('**/api/exceptions{,/**}', (route) => {
        const path = new URL(route.request().url()).pathname
        if (route.request().method() !== 'GET') {
          expect(route.request().method()).toBe('POST')
          expect(path).toBe(`/bootui/api/exceptions/${encodeURIComponent(group.id)}/status`)
          expect(route.request().postDataJSON()).toEqual({status: 'RESOLVED'})
          writes++
          return route.fulfill({json: acknowledged})
        }
        if (path === '/bootui/api/exceptions/stream')
          return route.fulfill({contentType: 'text/event-stream', body: ': test snapshot\n\n'})
        if (path !== '/bootui/api/exceptions') return route.fallback()
        reads++
        if (reads === 1) return route.fulfill({json: initial})
        if (reads === 2) stale = route
        else fresh = route
      })
      await openView('exceptions', 'Exceptions')
      const row = page.locator('table tbody tr', {hasText: '/api/sample/boom'})
      await expect(row).toHaveCount(1)
      const autoRefresh = page.getByRole('checkbox', {name: 'Toggle auto-refresh'})
      await autoRefresh.uncheck()
      expect(writes).toBe(0)
      await page.getByRole('button', {name: 'Refresh panel', exact: true}).click()
      await expect.poll(() => Boolean(stale)).toBe(true)
      await row.getByRole('button', {name: 'Resolved', exact: true}).click()
      await expect(row.locator('.badge').filter({hasText: /^Resolved$/})).toBeVisible()
      await expect(row.getByRole('button', {name: 'Resolved', exact: true})).toHaveClass(/active/)
      await expect(page.getByRole('status').filter({hasText: 'Status changed to Resolved.'})).toBeVisible()
      await stale.fulfill({json: initial})
      await expect.poll(() => Boolean(fresh)).toBe(true)
      await expect(row.locator('.badge').filter({hasText: /^Resolved$/})).toBeVisible()
      await fresh.fulfill({status: 503, json: {error: 'Exception snapshot temporarily unavailable'}})
      await expect(page.getByRole('alert').filter({hasText: 'Unable to load exceptions: HTTP 503'})).toBeVisible()
      await expect(row.locator('.badge').filter({hasText: /^Resolved$/})).toBeVisible()
      await expect(row.getByRole('button', {name: 'Resolved', exact: true})).toBeEnabled()
      await expect(row.getByRole('button', {name: 'Resolved', exact: true})).toHaveClass(/active/)
      await expect(autoRefresh).not.toBeChecked()
      expect(reads).toBe(3)
      expect(writes).toBe(1)
    })

    test('keeps an accepted resource-profile stop after a deferred running poll', async ({page, openView}) => {
      const native = await nativeResourceProfile(page)
      const now = await pauseClock(page)
      const running = {...native, state: 'RUNNING', reason: null, startedAt: now, endsAt: now + 30000}
      const completed = {
        ...native,
        state: 'COMPLETED',
        reason: null,
        sampler: 'jdk.ExecutionSample',
        startedAt: now,
        endsAt: now + 30000,
        finishedAt: now + 2000,
        cpuSamples: 400,
        outsideSamples: 100,
        requests: 12,
        routes: [],
        routesOmitted: 0,
        limitations: []
      }
      let reads = 0
      let writes = 0
      let stale
      await page.route('**/api/runtime-insights/resource-profile', (route) => {
        expect(route.request().method()).toBe('GET')
        reads++
        if (reads === 2) {
          stale = route
          return
        }
        return route.fulfill({json: running})
      })
      await page.route('**/api/runtime-insights/resource-profile/stop', (route) => {
        expect(route.request().method()).toBe('POST')
        writes++
        return route.fulfill({json: completed})
      })
      await openView('runtime-insights?tab=profile', 'Runtime Insights')
      const profile = page.locator('.insight-profile')
      await expect(profile.getByRole('button', {name: 'Stop now', exact: true})).toBeVisible()
      await page.clock.runFor(2000)
      await expect.poll(() => Boolean(stale)).toBe(true)
      await profile.getByRole('button', {name: 'Stop now', exact: true}).click()
      await expect(profile.locator('.insight-profile-summary')).toContainText('12 requests')
      await stale.fulfill({json: running})
      await page.clock.runFor(6000)
      await expect(profile.locator('.insight-profile-summary')).toContainText('12 requests')
      await expect(profile.getByRole('button', {name: 'Stop now', exact: true})).toHaveCount(0)
      expect(reads).toBe(2)
      expect(writes).toBe(1)
    })

    for (const phase of ['initial', 'poll', 'action']) {
      test(`does not restart resource-profile polling after unmount with a pending ${phase}`, async ({
        page,
        openView
      }) => {
        const native = await nativeResourceProfile(page)
        const now = await pauseClock(page)
        const running = {...native, state: 'RUNNING', reason: null, startedAt: now, endsAt: now + 30000}
        const errors = []
        page.on('pageerror', (error) => errors.push(error.message))
        let reads = 0
        let writes = 0
        let pending
        await page.route('**/api/runtime-insights/resource-profile', (route) => {
          expect(route.request().method()).toBe('GET')
          reads++
          if ((phase === 'initial' && reads === 1) || (phase === 'poll' && reads === 2)) {
            pending = route
            return
          }
          return route.fulfill({json: running})
        })
        await page.route('**/api/runtime-insights/resource-profile/stop', (route) => {
          expect(route.request().method()).toBe('POST')
          writes++
          pending = route
        })
        await openView('runtime-insights?tab=profile', 'Runtime Insights')
        if (phase === 'poll') {
          await expect(page.locator('.insight-profile-running')).toBeVisible()
          await page.clock.runFor(2000)
        }
        if (phase === 'action') {
          await page.locator('.insight-profile').getByRole('button', {name: 'Stop now', exact: true}).click()
        }
        await expect.poll(() => Boolean(pending)).toBe(true)
        await page.evaluate(() => {
          location.hash = '#/health'
        })
        await page.clock.runFor(1000)
        await expect(
          page
            .locator('main h2')
            .filter({hasText: /^Health/})
            .first()
        ).toBeVisible()
        await expect(page.locator('.insight-profile')).toHaveCount(0)
        await pending.fulfill({json: running})
        await page.clock.runFor(6000)
        expect(reads).toBe(phase === 'poll' ? 2 : 1)
        expect(writes).toBe(phase === 'action' ? 1 : 0)
        expect(errors).toEqual([])
      })
    }

    test('retains sampled request totals when resource-profile routes are withheld by policy', async ({
      page,
      openView
    }) => {
      const native = await nativeResourceProfile(page)
      const reason =
        "The http-exchanges panel is disabled, so the samples are not listed by route; the session's totals still count every sampled request."
      const completed = {
        ...native,
        state: 'COMPLETED',
        reason: null,
        sampler: 'jdk.ExecutionSample',
        startedAt: 1000,
        endsAt: 31000,
        finishedAt: 20000,
        cpuSamples: 400,
        outsideSamples: 100,
        requests: 12,
        routes: [],
        routesOmitted: 0,
        limitations: [reason]
      }
      let reads = 0
      let writes = 0
      await page.route('**/api/runtime-insights/resource-profile{,/*}', (route) => {
        if (route.request().method() !== 'GET') {
          writes++
          return route.fulfill({status: 500, json: {error: 'Unexpected recording mutation'}})
        }
        reads++
        return route.fulfill({json: completed})
      })
      await openView('runtime-insights?tab=profile', 'Runtime Insights')
      const profile = page.locator('.insight-profile')
      await expect(profile.locator('.insight-profile-summary')).toContainText('12 requests')
      await expect(profile.locator('.insight-profile-limitations')).toContainText(reason)
      await expect(profile).not.toContainText('No request ran')
      await expect(profile).not.toContainText('No request samples were attributed')
      await expect(profile.locator('.insight-profile-table')).toHaveCount(0)
      expect(reads).toBe(1)
      expect(writes).toBe(0)
    })

    for (const [id, title, readPath, action, label] of [
      ['flyway', 'Flyway migrations', 'migrations', 'migrate', 'Migrate'],
      ['liquibase', 'Liquibase change sets', 'changesets', 'update', 'Update']
    ]) {
      for (const outcome of ['failed', 'lost', 'malformed', 'blocked', 'failed-follow-up']) {
        test(`reconciles the native ${title} ${outcome} outcome without retrying a database action`, async ({
          page,
          openView
        }) => {
          const manifestResponse = await page.request.get('/bootui/api/panels')
          expect(manifestResponse.ok()).toBeTruthy()
          const manifest = await manifestResponse.json()
          const capability = manifest.panels.find((panel) => panel.id === id)
          expect(capability).toBeTruthy()
          test.skip(capability.available === false || capability.enabled === false, capability.unavailableReason)
          test.skip(capability.readOnly, capability.readOnlyReason)
          const response = await page.request.get(`/bootui/api/${id}/${readPath}`)
          expect(response.ok()).toBeTruthy()
          const native = await response.json()
          expect(id === 'flyway' ? native.flywayPresent : native.liquibasePresent).toBe(true)
          const enabled = id === 'flyway' ? 'migrateEnabled' : 'updateEnabled'
          const database = native.databases.find((candidate) => candidate[enabled])
          test.skip(!database, `No native ${action} target is enabled on ${manifest.platform}.`)
          const rows = id === 'flyway' ? 'migrations' : 'changeSets'
          expect(database[rows].length).toBeGreaterThan(0)
          const initial = {...native, total: database.total, databases: [database]}
          const committed =
            id === 'flyway'
              ? {
                  ...database.migrations[0],
                  version: '999',
                  script: 'V999__round4_committed.sql',
                  description: 'Earlier migration committed before failure',
                  state: 'Success'
                }
              : {
                  ...database.changeSets[0],
                  id: 'round4-committed',
                  description: 'Earlier change set committed before failure',
                  execType: 'EXECUTED',
                  orderExecuted: database.applied + 1
                }
          const updated = {
            ...initial,
            total: initial.total + 1,
            databases: [
              {
                ...database,
                total: database.total + 1,
                applied: database.applied + 1,
                ...(id === 'flyway' ? {currentVersion: '999'} : {}),
                [rows]: [...database[rows], committed]
              }
            ]
          }
          const failure = `${title} failed after an earlier change committed.`
          const reason = `Panel '${id}' is read-only (bootui.panels.${id}.read-only=true)`
          const failed =
            id === 'flyway'
              ? {
                  status: 'failed',
                  message: failure,
                  beanName: database.name,
                  migrationsExecuted: null,
                  schemasCleaned: [],
                  schemasDropped: [],
                  migrationPath: null,
                  warnings: []
                }
              : {
                  status: 'failed',
                  message: failure,
                  beanName: database.name,
                  pendingBefore: null,
                  pendingAfter: null,
                  changeSetsApplied: null,
                  warnings: []
                }
          let reads = 0
          let writes = 0
          await page.route(`**/api/${id}/*`, (route) => {
            if (route.request().method() !== 'GET') {
              expect(route.request().method()).toBe('POST')
              expect(new URL(route.request().url()).pathname).toBe(`/bootui/api/${id}/${action}`)
              expect(route.request().postDataJSON()).toEqual({beanName: database.name, confirm: true})
              writes++
              if (outcome === 'lost') return route.abort('failed')
              if (outcome === 'malformed')
                return route.fulfill({status: 200, contentType: 'application/json', body: '{'})
              if (outcome === 'blocked')
                return route.fulfill({status: 403, json: {error: 'BootUI panel access denied', panel: id, reason}})
              return route.fulfill({status: 500, json: failed})
            }
            expect(new URL(route.request().url()).pathname).toBe(`/bootui/api/${id}/${readPath}`)
            reads++
            if (reads === 1) return route.fulfill({json: initial})
            if (outcome === 'failed-follow-up')
              return route.fulfill({status: 503, json: {error: 'History temporarily unavailable'}})
            return route.fulfill({json: updated})
          })
          await openView(id, title)
          const button = page.getByRole('button', {name: label, exact: true})
          await expect(button).toBeEnabled()
          expect(writes).toBe(0)
          await button.click()
          await acceptConfirm(page)
          const message =
            outcome === 'blocked'
              ? reason
              : outcome === 'lost' || outcome === 'malformed'
                ? 'action outcome is unknown'
                : failure
          await expect(page.locator('.alert', {hasText: message})).toBeVisible()
          await expect(page.locator('.alert-success')).toHaveCount(0)
          await expect.poll(() => reads).toBe(outcome === 'blocked' ? 1 : 2)
          const card = page.locator('.card', {
            has: page.locator('.card-header code').filter({hasText: database.name})
          })
          if (outcome === 'blocked' || outcome === 'failed-follow-up') {
            await expect(card.getByText(`${database.applied} applied`, {exact: true})).toBeVisible()
            await expect(card).not.toContainText(committed.description)
            if (outcome === 'failed-follow-up')
              await expect(page.locator('.alert-danger', {hasText: 'HTTP 503'})).toBeVisible()
          } else {
            await expect(card.getByText(`${database.applied + 1} applied`, {exact: true})).toBeVisible()
            if (id === 'flyway') await expect(card).toContainText(committed.description)
            else await expect(card).toContainText(committed.id)
          }
          await expect(button).toBeEnabled()
          expect(writes).toBe(1)
        })
      }
    }

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
            page.getByRole('alert').filter({hasText: outcome === 'unavailable' ? reason : 'unchanged'})
          ).toBeVisible()
          await expect(page.locator('.alert-success')).toHaveCount(0)
          const pause = page.getByRole('button', {name: 'Pause', exact: true})
          const resume = page.getByRole('button', {name: 'Resume', exact: true})
          if (outcome === 'unavailable') {
            await expect(pause).toHaveCount(0)
            if (id === 'websockets') await expect(resume).toHaveCount(0)
            else await expect(resume).toBeDisabled()
          } else await expect(pause).toBeEnabled()
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
