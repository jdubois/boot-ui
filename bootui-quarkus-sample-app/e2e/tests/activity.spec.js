// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * Live Activity (Quarkus).
 *
 * Quarkus now has a per-request profile drawer too (`GET /bootui/api/activity/request/{id}` — see the shared
 * `ExecutionProfileAssembler`), but it is a deliberately *reduced* profile: unlike Spring's tiered profiler
 * (request id and trace id, then method+path+time-window+thread heuristics), Quarkus's reactive
 * event-loop/worker model has no per-request serving-thread identity to fall back on, so a request profiles
 * by BootUI's request id or its distributed trace id only, SQL/security correlation is always exact (never
 * "approximate", and never thread-matched), and the drawer surfaces explicit reduced-profile notes instead
 * of Spring's exact/approximate badges. The dedicated profile-drawer tests below assert exactly that
 * reduced (not absent, not full-parity) behavior.
 *
 * Beyond the drawer, this spec's other focus is the merged feed, OpenTelemetry-trace-id-based
 * nesting/correlation, KPI deep-links, and a regression guard for the bug fixed in #492.
 *
 * That bug: a Quarkus-captured exception's `method`/`path` were deterministically `null`. Root cause:
 * `ExceptionStore` dedups by throwable identity across the whole cause chain, keeping only the first
 * feeder's context. `QuarkusErrorHandler` logs an unhandled failure synchronously, before the response is
 * finalized and before `QuarkusExceptionCaptureFilter`'s `addBodyEndHandler` callback ever runs, so the
 * no-HTTP-context log-handler capture always won the dedup race against the filter's full-context (but too
 * late) capture. It went unnoticed because no dedicated spec exercised this panel's real data. The second
 * test below asserts the exact wire shape the fix guarantees, not just that the page renders.
 */
test.describe('Live Activity view (Quarkus)', () => {
  test('merges requests, SQL and exceptions into one live stream', async ({openView, page}) => {
    // product-search always runs SQL (unlike the cached products endpoint), so the merged feed
    // reliably has a SQL entry to assert on alongside the request/exception entries.
    const search = await page.request.get('/api/sample/product-search')
    expect(search.ok()).toBeTruthy()
    const boom = await page.request.get('/api/sample/boom')
    expect(boom.status()).toBe(500)

    await openView('activity', 'Live Activity')

    const table = page.locator('.activity-table')
    await expect(table).toContainText('/api/sample/product-search', {timeout: 15_000})
    await expect(table).toContainText('REQUEST')
    await expect(table).toContainText('SQL')
    // The failing request shows up as an error-severity row.
    await expect(table.locator('tbody tr.table-danger').first()).toBeVisible()
  })

  test('keeps a journal request visible when the HTTP-exchange profile is unavailable', async ({page}) => {
    const search = await page.request.get('/api/sample/product-search')
    expect(search.ok()).toBeTruthy()
    const activity = await page.request.get('/bootui/api/activity?source=journal')
    expect(activity.ok()).toBeTruthy()
    const exemplar = (await activity.json()).entries.find(
      (entry) => entry.type === 'REQUEST' && entry.path === '/api/sample/product-search'
    )
    expect(exemplar?.id).toBeTruthy()

    await page.route('**/api/activity/request/*', async (route) => {
      if (route.request().url().endsWith('/journal')) return route.continue()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          available: false,
          unavailableReason: `Request ${exemplar.id} is no longer in the buffer.`
        })
      })
    })
    await page.goto(`/bootui/#/activity?request=${encodeURIComponent(exemplar.id)}`)

    const drawer = page.locator('.activity-drawer')
    await expect(drawer.locator('.request-journal')).toContainText('GET /api/sample/product-search')
    await expect(drawer).toContainText('HTTP-exchange details unavailable')
  })

  test('opens the runtime journal status on demand and clears the recording after confirmation', async ({
    openView,
    page
  }) => {
    const traffic = await page.request.get('/api/sample/product-search')
    expect(traffic.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')

    const toggle = page.getByRole('button', {name: 'Recording', exact: true})
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(page.locator('#activity-runtime-journal')).toHaveCount(0)
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const journal = page.locator('#activity-runtime-journal')
    await expect(page.getByRole('heading', {name: 'Runtime journal'})).toHaveCount(1)
    await expect(journal).toContainText('Recorded this run')
    await expect(journal.locator('code', {hasText: /^http$/})).toBeVisible()
    await expect(journal).toContainText('None: every event was recorded.')
    await expect(journal).toContainText('Previous runs')

    await journal.getByRole('button', {name: 'Clear recording'}).click()
    await acceptConfirm(page)
    await expect(
      page.locator('.alert', {hasText: /Cleared \d+ recorded events? and the aggregates of this run/})
    ).toBeVisible()
  })
  test('captures the failing request with its real method and path, not null placeholders', async ({
    openView,
    page
  }) => {
    const boom = await page.request.get('/api/sample/boom')
    expect(boom.status()).toBe(500)

    await openView('activity', 'Live Activity')

    // What a developer actually sees: the request row's summary embeds the real method + path.
    const requestRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/boom'}).first()
    await expect(requestRow).toBeVisible({timeout: 15_000})
    await expect(requestRow).toContainText('GET /api/sample/boom')

    // Regression coverage for #492: assert the underlying wire data the UI binds to directly, so a
    // future regression is caught even if it stops being visibly obvious in the merged row's text.
    const activity = await page.request.get('/bootui/api/activity')
    expect(activity.ok()).toBeTruthy()
    const body = await activity.json()

    const request = body.entries.find((e) => e.type === 'REQUEST' && e.path === '/api/sample/boom')
    expect(request, 'the /api/sample/boom call must surface as a REQUEST entry').toBeTruthy()
    expect(request.correlationId, 'OpenTelemetry is enabled, so the request must carry a trace id').toBeTruthy()

    const exception = body.entries.find((e) => e.type === 'EXCEPTION')
    expect(exception, 'the thrown failure must surface as an EXCEPTION entry').toBeTruthy()
    expect(exception.method, 'regression #492: the exception must carry its owning request method, not null').toBe(
      'GET'
    )
    expect(exception.path, 'regression #492: the exception must carry its owning request path, not null').toBe(
      '/api/sample/boom'
    )
    expect(exception.parentId, 'the exception must nest under the request it belongs to').toBe(request.id)
  })

  test('nests the correlated exception under its owning request row', async ({openView, page}) => {
    const boom = await page.request.get('/api/sample/boom')
    expect(boom.status()).toBe(500)

    await openView('activity', 'Live Activity')

    const requestRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/boom'}).first()
    await expect(requestRow).toBeVisible({timeout: 15_000})

    // The request row carries a disclosure control because the correlated exception is nested under
    // it (via the shared OpenTelemetry trace id), expanded by default.
    const disclosure = requestRow.locator('.activity-disclosure')
    await expect(disclosure).toBeVisible()
    await expect(disclosure).toHaveAttribute('aria-expanded', 'true')

    // The exception appears as an indented child row rather than a flat sibling.
    const childRows = page.locator('.activity-table tbody tr.activity-child-row')
    await expect(childRows.filter({hasText: 'IllegalStateException'}).first()).toBeVisible({timeout: 15_000})

    // Collapsing the request folds its children away.
    await disclosure.click()
    await expect(disclosure).toHaveAttribute('aria-expanded', 'false')
  })

  test('marks an authenticated request with a lock and the principal tag', async ({openView, page}) => {
    // QuarkusHttpExchangeCaptureFilter resolves the principal straight off the request's own
    // SecurityIdentity, so this works without relying on trace-based security-event correlation.
    const secure = await page.request.get('/api/secure/products', {
      headers: {Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64')}
    })
    expect(secure.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')

    const secureRow = page.locator('.activity-table tbody tr', {hasText: '/api/secure/products'}).first()
    await expect(secureRow).toBeVisible({timeout: 15_000})

    await expect(secureRow.locator('i.bi-lock-fill')).toBeVisible()
    await expect(secureRow.locator('.activity-principal-tag')).toContainText('admin')
  })

  test('opens a per-request profile drawer with a reduced profile', async ({openView, page}) => {
    // ORM inspection records preparation, not a confirmed JDBC execution.
    const search = await page.request.get('/api/sample/product-search')
    expect(search.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')

    const searchRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/product-search'}).first()
    await expect(searchRow).toBeVisible({timeout: 15_000})

    await searchRow.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('Request profile')
    await expect(drawer).toContainText('/api/sample/product-search')

    const sql = drawer.locator('section', {has: page.getByRole('heading', {name: /^SQL/})}).first()
    await expect(sql).toContainText('SQL preparation is not execution evidence')
    await expect(sql.getByText('exact', {exact: true})).toHaveCount(0)
    await expect(drawer.getByText('approximate', {exact: true})).toHaveCount(0)
    await expect(drawer).toContainText('SQL execution timing unavailable')
    await expect(drawer).not.toContainText('0 SQL statement(s)')

    // The reduced-profile explanation is real, load-bearing UI copy (ExecutionProfileAssembler's notes),
    // not an internal implementation detail — a developer reads this to know why Quarkus's profile is
    // narrower than Spring's.
    await expect(drawer).toContainText('This is a reduced profile')

    // The REST client section is present, and the cache section honestly reports that Quarkus has no
    // cache-access capture seam; the serving-thread and time-window tiers are reported unavailable.
    await expect(drawer.getByRole('heading', {name: /^REST client calls/})).toBeVisible()
    const cache = drawer.locator('section', {has: page.getByRole('heading', {name: /^Cache accesses/})})
    await expect(cache).toContainText('not available on Quarkus')
    await expect(drawer).toContainText('Serving thread and time window correlation are unavailable on this adapter')

    // The runtime journal's record of the same request (docs/PLAN-v2.md §5.3): its route and the SQL on its timeline.
    const journal = drawer.locator('.request-journal')
    await expect(journal.getByRole('heading', {name: 'Recorded by the runtime journal'})).toHaveCount(1)
    await expect(journal).toContainText('GET /api/sample/product-search')
    await expect(journal.locator('.request-journal__source', {hasText: /^sql$/}).first()).toBeVisible()
    await expect(journal).toContainText('SQL preparation; execution not observed')

    const deepDives = drawer.locator('.request-code-path')
    await expect(deepDives.getByRole('link', {name: 'Open the JFR profile in Runtime Insights'})).toBeVisible()
    await expect(deepDives).toContainText(/Code Paths unavailable|Open GET \/api\/sample\/product-search in Code Paths/)
    await deepDives.getByRole('link', {name: 'Open the JFR profile in Runtime Insights'}).click()
    await expect(page).toHaveURL(/#\/runtime-insights\?tab=profile/)
    await expect(page.getByRole('tab', {name: 'JFR profile'})).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.insight-profile').getByRole('button', {name: /Profile resources/})).toBeVisible()
    await expect(page.locator('.insight-profile-running')).toHaveCount(0)
  })

  test('attributes a REST client call to its caller by request id when two requests share a trace id', async ({
    openView,
    page
  }) => {
    // The sample REST client calls this same app, so the inbound POST and the downstream GET it triggers
    // are two captured requests carrying one propagated trace id. The trace id cannot decide between them,
    // but the outbound call carries the calling request's BootUI request id, so it is attributed exactly.
    await page.request.post('/bootui/api/rest-client-trace/recording', {data: {enabled: true}})
    const capture = await page.request.post('/api/sample/rest-client-capture')
    expect(capture.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')
    const captureRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/rest-client-capture'}).first()
    await expect(captureRow).toBeVisible({timeout: 15_000})
    await captureRow.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    const rest = drawer.locator('section', {has: page.getByRole('heading', {name: /^REST client calls/})})
    await expect(rest).toContainText('/api/sample/products')
    await expect(rest.locator('.activity-tier')).toHaveText('request id')
    await expect(drawer).toContainText('shared by more than one captured request')

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('correlates a security event to the profiled request exactly by its request id', async ({openView, page}) => {
    // /api/secure/products deliberately also runs a live SQL SELECT (see SecureResource), so this
    // request's drawer exercises SQL correlation and security correlation together.
    const secure = await page.request.get('/api/secure/products', {
      headers: {Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64')}
    })
    expect(secure.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')

    const secureRow = page.locator('.activity-table tbody tr', {hasText: '/api/secure/products'}).first()
    await expect(secureRow).toBeVisible({timeout: 15_000})

    await secureRow.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()

    const security = drawer.locator('section', {has: page.getByRole('heading', {name: 'Security events'})})
    await expect(security).toBeVisible({timeout: 15_000})
    await expect(security).toContainText('AuthenticationSuccessEvent')

    // Quarkus has no per-request serving thread, but the security event carries the request's BootUI request
    // id, stamped on the request's Vert.x context, so the event is attributed exactly, not just by principal.
    await expect(security.getByText('exact', {exact: true})).toBeVisible()
    await expect(security.locator('.activity-tier')).toHaveText('request id')

    await drawer.getByRole('button', {name: 'Close'}).click()
  })

  test('closes the profile drawer with the Escape key and offers a copy action', async ({openView, page}) => {
    await page.request.get('/api/sample/product-search')

    await openView('activity', 'Live Activity')
    const searchRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/product-search'}).first()
    await expect(searchRow).toBeVisible({timeout: 15_000})

    await searchRow.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('button', {name: /Copy profile/})).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(drawer).toHaveCount(0)
  })

  test('Copy for AI qualifies ORM preparation and copies the profile exactly', async ({
    browserName,
    context,
    openView,
    page
  }) => {
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }
    await page.request.get('/api/sample/product-search')

    await openView('activity', 'Live Activity')
    const searchRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/product-search'}).first()
    await expect(searchRow).toBeVisible({timeout: 15_000})
    await searchRow.getByRole('button', {name: /Profile/}).click()
    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()

    const writes = []
    page.on('request', (request) => {
      if (request.method() !== 'GET') writes.push(`${request.method()} ${request.url()}`)
    })
    await drawer.getByRole('button', {name: 'Copy for AI'}).click()

    const preview = drawer.getByRole('textbox', {name: 'Markdown export preview'})
    await expect(preview).toHaveValue(/# BootUI request profile: `GET \/api\/sample\/product-search`/)
    await expect(preview).toHaveValue(/## SQL \(unavailable\)/)
    await expect(preview).toHaveValue(/SQL preparation is not execution evidence/)
    await expect(preview).toHaveValue(/SQL execution timing unavailable/)
    await expect(preview).not.toHaveValue(/```sql\n/)
    await expect(preview).not.toHaveValue(/0 SQL statements/)

    await drawer.getByRole('button', {name: 'Copy Markdown'}).click()
    await expect(drawer.getByRole('button', {name: 'Copied'})).toBeVisible()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(await preview.inputValue())
    expect(writes).toEqual([])
  })

  test('manual JDBC retains exact execution evidence and SQL in the copied profile', async ({openView, page}) => {
    const joined = await page.request.get('/api/insights/orders/joined')
    expect(joined.ok()).toBeTruthy()
    await openView('activity', 'Live Activity')
    const row = page.locator('.activity-table tbody tr', {hasText: '/api/insights/orders/joined'}).first()
    await expect(row).toBeVisible({timeout: 15_000})
    await row.getByRole('button', {name: /Profile/}).click()
    const drawer = page.locator('.activity-drawer')
    const sql = drawer.locator('section', {has: page.getByRole('heading', {name: /^SQL/})}).first()
    await expect(sql.getByText('exact', {exact: true})).toBeVisible()
    await expect(sql).toContainText('request id')
    await expect(sql).toContainText('insight_orders')
    await expect(drawer).toContainText('Only confirmed JDBC executions are included')
    await drawer.getByRole('button', {name: 'Copy for AI'}).click()
    const preview = drawer.getByRole('textbox', {name: 'Markdown export preview'})
    await expect(preview).toHaveValue(/## SQL \(exact, request id\)/)
    await expect(preview).toHaveValue(/```sql\n/)
    await expect(preview).toHaveValue(/insight_orders/)
  })

  test('pauses and resumes the live feed', async ({openView, page}) => {
    await openView('activity', 'Live Activity')

    const pauseButton = page.getByRole('button', {name: /Pause/})
    await expect(pauseButton).toBeVisible()
    await pauseButton.click()
    await expect(page.getByRole('button', {name: /Resume/})).toBeVisible()
  })

  test('filters to errors only and persists the choice across a reload', async ({openView, page}) => {
    await page.request.get('/api/sample/product-search')
    const boom = await page.request.get('/api/sample/boom')
    expect(boom.status()).toBe(500)

    await openView('activity', 'Live Activity')
    await expect(page.locator('.activity-table tbody tr').first()).toBeVisible({timeout: 15_000})

    const errorsOnly = page.locator('#activity-errors-only')
    await errorsOnly.check()
    // Every visible severity badge is now ERROR.
    const badges = page.locator('.activity-table tbody tr td .badge.text-bg-danger')
    await expect(badges.first()).toBeVisible()

    await page.reload()
    await expect(page.locator('#activity-errors-only')).toBeChecked()
  })

  test('deep-links a request row to the HTTP Exchanges panel', async ({openView, page}) => {
    await page.request.get('/api/sample/products')

    await openView('activity', 'Live Activity')
    const productsRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/products'}).first()
    await expect(productsRow).toBeVisible({timeout: 15_000})

    await productsRow.getByTitle('Open in HTTP Exchanges').click()

    await expect(page.getByRole('heading', {name: 'HTTP Exchanges'})).toBeVisible()
    await expect(page).toHaveURL(/\/http-exchanges/)
  })

  test('links KPI cards to their dedicated panels', async ({openView, page}) => {
    await page.request.get('/api/sample/products')

    await openView('activity', 'Live Activity')
    const kpis = page.locator('.activity-kpis')
    await expect(kpis).toBeVisible({timeout: 15_000})

    await kpis.getByTitle('Open the Health panel').click()
    await expect(page).toHaveURL(/\/health/)

    await openView('activity', 'Live Activity')
    await page.locator('.activity-kpis').getByTitle('Open the Exceptions panel').click()
    await expect(page).toHaveURL(/\/exceptions/)

    await openView('activity', 'Live Activity')
    await page.locator('.activity-kpis').getByTitle('Open the Heap Dump panel').click()
    await expect(page).toHaveURL(/\/heap-dump/)

    await openView('activity', 'Live Activity')
    await page
      .locator('.activity-kpis')
      .getByTitle(/in HTTP Exchanges$/)
      .click()
    await expect(page).toHaveURL(/\/http-exchanges\?route=/)
    // The KPI opens on the slowest request's route row, ranked by slowest request, with its exchanges listed.
    await expect(page.locator('.http-routes-row-active')).toBeVisible({timeout: 15_000})
    await expect(page.locator('.http-exchanges-route-filter')).toBeVisible()
  })

  test('shows a Live flow service map of dependencies derived from retained evidence', async ({openView, page}) => {
    await page.request.get('/api/sample/product-search')

    await openView('activity', 'Live Activity')

    const map = page.locator('.flow-map')
    await expect(map).toBeVisible({timeout: 15_000})
    await expect(page.locator('.activity-table')).toBeVisible()
    await expect(map.locator('.flow-node--app')).toBeVisible()
    await expect(map).toContainText('contacts nothing and probes nothing')

    // Quarkus serves the same contract, so the Agroal datasource is drawn exactly like Spring's pool.
    const database = map.locator('.flow-node--jdbc').first()
    await expect(database).toBeVisible()
    await expect(database).toHaveAttribute('aria-label', /JDBC/)

    await database.click()
    await expect(map.locator('.flow-detail')).toContainText('Retained interactions')

    const textual = map.locator('ul[aria-label="Service map relationships as text"]')
    await expect(textual).toHaveCount(1)
  })

  test('opens the CPU ledger of work outside requests on demand', async ({openView, page}) => {
    await openView('activity', 'Live Activity')
    const toggle = page.getByRole('button', {name: 'Resources', exact: true})
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const resources = page.locator('#activity-runtime-resources')
    await expect(page.getByRole('heading', {name: 'Work outside requests'})).toHaveCount(1)
    // The sampler needs two sweeps before it has an interval to split, so refresh until it does.
    await expect(async () => {
      await resources.getByRole('button', {name: 'Refresh'}).click()
      await expect(resources.getByRole('rowheader', {name: 'JVM internals (GC, JIT, VM)'})).toBeVisible({timeout: 1000})
    }).toPass({timeout: 15_000})
    await expect(resources).toContainText('of CPU time went to work outside requests')
  })
})
