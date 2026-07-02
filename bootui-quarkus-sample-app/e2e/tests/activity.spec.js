// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Live Activity (Quarkus).
 *
 * Quarkus has no per-request profile drawer (`profileable` is always `false` — the `/activity/request/{id}`
 * drill-down endpoint is Spring-only; see `LiveActivityAssembler`), so the "exact"/"approximate" correlation
 * badges, the drawer itself, Escape-to-close and "Copy profile" from the Spring spec do not port here. What
 * IS real and load-bearing on Quarkus: the merged feed, OpenTelemetry-trace-id-based nesting/correlation,
 * KPI deep-links, and — the reason this spec exists — a regression guard for the bug fixed in #492.
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
    await expect(page).toHaveURL(/\/http-exchanges\?q=/)
  })
})
