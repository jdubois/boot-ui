// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Live Activity (Quarkus).
 *
 * Quarkus now has a per-request profile drawer too (`GET /bootui/api/activity/request/{id}` — see the shared
 * `ExecutionProfileAssembler`), but it is a deliberately *reduced*, trace-id-only profile: unlike Spring's
 * tiered profiler (trace id, then method+path+time-window+thread heuristics), Quarkus's reactive
 * event-loop/worker model has no per-request serving-thread identity to fall back on, so a request only
 * ever profiles when it carries a distributed trace id, SQL/security correlation is always exact (never
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

  test('opens a per-request profile drawer with a reduced, trace-id-only profile', async ({openView, page}) => {
    // product-search always runs SQL, so the request reliably has SQL to correlate in the drawer.
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

    // Quarkus's reduced profile has no time-window/thread heuristic to fall back on, so a
    // trace-id-correlated SQL execution is always "exact", never the "approximate" fallback Spring's
    // fuller profiler can show.
    await expect(drawer.getByText('exact', {exact: true})).toBeVisible()
    await expect(drawer.getByText('approximate', {exact: true})).toHaveCount(0)

    // The reduced-profile explanation is real, load-bearing UI copy (ExecutionProfileAssembler's notes),
    // not an internal implementation detail — a developer reads this to know why Quarkus's profile is
    // narrower than Spring's.
    await expect(drawer).toContainText('reduced, trace-id-only profile')

    // The REST client section is present, and the cache section honestly reports that Quarkus has no
    // cache-access capture seam; the serving-thread and time-window tiers are reported unavailable.
    await expect(drawer.getByRole('heading', {name: /^REST client calls/})).toBeVisible()
    const cache = drawer.locator('section', {has: page.getByRole('heading', {name: /^Cache accesses/})})
    await expect(cache).toContainText('not available on Quarkus')
    await expect(drawer).toContainText('Serving thread and time window correlation are unavailable on this adapter')

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('never attributes a REST client call to either request sharing its trace id', async ({openView, page}) => {
    // The sample REST client calls this same app, so the inbound POST and the downstream GET it triggers
    // are two captured requests carrying one propagated trace id. The cross-anchor uniqueness guard then
    // attributes the outbound call to neither and counts it in the notes.
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
    await expect(rest).toContainText('No REST client calls correlated to this request.')
    await expect(drawer).toContainText('shared by more than one captured request')
    await expect(drawer).toContainText('could equally belong to another captured request')

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('correlates a security event to the profiled request by trace id, never claiming a thread-exact match', async ({
    openView,
    page
  }) => {
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

    // Reduced correlation: Quarkus's reactive event-loop/worker model has no per-request serving-thread
    // identity, so ExecutionProfileAssembler can only ever claim a "principal" match here (the event's
    // principal equals the request's), never the stronger "exact" (thread-matched) badge Spring can show
    // for the same scenario. Scoped to the security section specifically, since the SQL section on this
    // same request legitimately does show "exact" (see the previous test).
    await expect(security.getByText('principal', {exact: true})).toBeVisible()
    await expect(security.getByText('exact', {exact: true})).toHaveCount(0)

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

  test('Copy for AI previews the profile Markdown with its SQL and copies it exactly', async ({
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
    await expect(preview).toHaveValue(/## SQL \(exact, trace id\)/)
    await expect(preview).toHaveValue(/```sql\n/)

    await drawer.getByRole('button', {name: 'Copy Markdown'}).click()
    await expect(drawer.getByRole('button', {name: 'Copied'})).toBeVisible()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(await preview.inputValue())
    expect(writes).toEqual([])
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
})
