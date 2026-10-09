// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('Live Activity view', () => {
  test('merges requests, SQL and exceptions into one live stream', async ({openView, page}) => {
    // Generate traffic: a successful SQL-backed request and a failing request.
    const products = await page.request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()
    const boom = await page.request.get('/api/sample/boom')
    expect(boom.status()).toBe(500)

    await openView('activity', 'Live Activity')

    const table = page.locator('.activity-table')
    await expect(table).toContainText('/api/sample/products', {timeout: 15_000})
    await expect(table).toContainText('REQUEST')
    // The failing request shows up as an error-severity row.
    await expect(table.locator('tbody tr.table-danger').first()).toBeVisible()
  })

  test('opens the runtime journal status on demand and clears the recording after confirmation', async ({
    openView,
    page
  }) => {
    const products = await page.request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()

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
  test('opens a per-request profile drawer with correlated signals', async ({openView, page}) => {
    // product-search runs SQL on every call (unlike the cached products endpoint), so the request
    // reliably has SQL to correlate.
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

    // The SQL-backed request is correlated exactly by BootUI's request id (no distributed trace id
    // required), so the drawer shows the "exact" badge rather than the "approximate" fallback.
    await expect(drawer.getByText('exact', {exact: true})).toBeVisible()
    await expect(drawer.getByText('approximate', {exact: true})).toHaveCount(0)

    // The runtime journal's record of the same request (docs/PLAN-v2.md §5.3): its route and the SQL on its timeline.
    const journal = drawer.locator('.request-journal')
    await expect(journal.getByRole('heading', {name: 'Recorded by the runtime journal'})).toHaveCount(1)
    await expect(journal).toContainText('GET /api/sample/product-search')
    await expect(journal.locator('.request-journal__source', {hasText: /^sql$/}).first()).toBeVisible()

    const deepDives = drawer.locator('.request-code-path')
    await expect(deepDives.getByRole('link', {name: 'Open the JFR profile in Runtime Insights'})).toBeVisible()
    await expect(deepDives).toContainText(/Code Paths unavailable|Open GET \/api\/sample\/product-search in Code Paths/)
    await deepDives.getByRole('link', {name: 'Open the JFR profile in Runtime Insights'}).click()
    await expect(page).toHaveURL(/#\/runtime-insights\?tab=profile/)
    await expect(page.getByRole('tab', {name: 'JFR profile'})).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.insight-profile').getByRole('button', {name: /Profile resources/})).toBeVisible()
    await expect(page.locator('.insight-profile-running')).toHaveCount(0)
  })

  test('opens a journal exemplar after HTTP-exchange detail is unavailable', async ({page}) => {
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

  test('profiles the cache accesses and REST client calls a request made', async ({openView, page}) => {
    // /products reads through the sample-products cache on the request thread, and
    // /quarkus-secure-products calls the companion Quarkus app, which is not running in this suite, so
    // the outbound RestClient call fails fast but is still captured on the request thread. Both correlate
    // exactly: by BootUI's request id, stamped on both when they are recorded.
    const products = await page.request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()
    const crossService = await page.request.get('/api/sample/quarkus-secure-products')
    expect(crossService.status()).toBe(500)

    await openView('activity', 'Live Activity')
    const drawer = page.locator('.activity-drawer')

    const productsRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/products'}).first()
    await expect(productsRow).toBeVisible({timeout: 15_000})
    await productsRow.getByRole('button', {name: /Profile/}).click()
    await expect(drawer).toBeVisible()
    const cache = drawer.locator('section', {has: page.getByRole('heading', {name: /^Cache accesses/})})
    await expect(cache).toContainText('sample-products')
    await expect(cache.locator('.activity-tier')).toHaveText('request id')
    await expect(drawer.getByRole('heading', {name: /^REST client calls/})).toBeVisible()
    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)

    const crossServiceRow = page
      .locator('.activity-table tbody tr', {hasText: '/api/sample/quarkus-secure-products'})
      .first()
    await expect(crossServiceRow).toBeVisible({timeout: 15_000})
    await crossServiceRow.getByRole('button', {name: /Profile/}).click()
    await expect(drawer).toBeVisible()
    const rest = drawer.locator('section', {has: page.getByRole('heading', {name: /^REST client calls/})})
    await expect(rest).toContainText('/api/secure/products → failed')
    await expect(rest.locator('.activity-tier')).toHaveText('request id')
    await expect(drawer.getByRole('button', {name: /Copy profile/})).toBeVisible()
    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('correlates a security event to the request exactly by its request id', async ({openView, page}) => {
    // An authenticated, SQL-backed admin request publishes an AUTHENTICATION_SUCCESS audit event while
    // the request is served, stamped with its BootUI request id, so the profiler can pin it to this exact
    // request rather than to any other concurrent request that happens to share the principal.
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
    await expect(security).toContainText('AUTHENTICATION_SUCCESS')
    // Stamped with the request's own id, so the event is badged exact, not just principal.
    await expect(security.getByText('exact', {exact: true})).toBeVisible()

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('nests correlated SQL and security events under the request row', async ({openView, page}) => {
    // A secure, SQL-backed admin request produces a SQL statement and an AUTHENTICATION_SUCCESS audit
    // event, both pinned to the request's serving thread, so they nest beneath the request row.
    const secure = await page.request.get('/api/secure/products', {
      headers: {Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64')}
    })
    expect(secure.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')

    const secureRow = page.locator('.activity-table tbody tr', {hasText: '/api/secure/products'}).first()
    await expect(secureRow).toBeVisible({timeout: 15_000})

    // The request row carries a disclosure control because correlated children are nested under it,
    // expanded by default.
    const disclosure = secureRow.locator('.activity-disclosure')
    await expect(disclosure).toBeVisible()
    await expect(disclosure).toHaveAttribute('aria-expanded', 'true')

    // The security event appears as an indented child row rather than a flat sibling.
    const childRows = page.locator('.activity-table tbody tr.activity-child-row')
    await expect(childRows.filter({hasText: 'AUTHENTICATION_SUCCESS'}).first()).toBeVisible({timeout: 15_000})

    // Collapsing the request folds its children away.
    await disclosure.click()
    await expect(disclosure).toHaveAttribute('aria-expanded', 'false')
  })

  test('marks an authenticated request with a lock and the principal tag', async ({openView, page}) => {
    // A correlated security event flags the request row as authenticated: a lock icon plus a gray pill
    // carrying the caller's principal, so a secured call and who made it are obvious at a glance.
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
    await page.request.get('/api/sample/products')
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

  test('closes the profile drawer with the Escape key and offers a copy action', async ({openView, page}) => {
    await page.request.get('/api/sample/products')

    await openView('activity', 'Live Activity')
    const productsRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/products'}).first()
    await expect(productsRow).toBeVisible({timeout: 15_000})
    const profileButton = productsRow.getByRole('button', {name: /Profile/})
    await profileButton.click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('button', {name: /Copy profile/})).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(drawer).toHaveCount(0)
    await expect(profileButton).toBeFocused()
  })

  test('Copy for AI previews the profile Markdown with the exception detail and copies it exactly', async ({
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
    const boom = await page.request.get('/api/sample/boom')
    expect(boom.status()).toBe(500)

    await openView('activity', 'Live Activity')
    const boomRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/boom'}).first()
    await expect(boomRow).toBeVisible({timeout: 15_000})
    await boomRow.getByRole('button', {name: /Profile/}).click()
    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()

    // Preparing the preview may only read through existing endpoints.
    const writes = []
    page.on('request', (request) => {
      if (request.method() !== 'GET') writes.push(`${request.method()} ${request.url()}`)
    })
    await drawer.getByRole('button', {name: 'Copy for AI'}).click()

    const preview = drawer.getByRole('textbox', {name: 'Markdown export preview'})
    await expect(preview).toHaveValue(/# BootUI request profile: `GET \/api\/sample\/boom`/)
    await expect(preview).toHaveValue(/Caused by: java\.lang\.NumberFormatException/)
    await expect(preview).toHaveValue(/apiToken=\*{6}/)
    await expect(preview).not.toHaveValue(/sample-secret-token/)
    await expect(drawer.locator('.ai-export-omissions')).toContainText('masked by BootUI')

    await drawer.getByRole('button', {name: 'Copy Markdown'}).click()
    await expect(drawer.getByRole('button', {name: 'Copied'})).toBeVisible()
    const copied = await page.evaluate(() => navigator.clipboard.readText())
    expect(copied).toBe(await preview.inputValue())
    expect(writes).toEqual([])

    await drawer.getByRole('button', {name: 'Back'}).click()
    await expect(drawer.getByRole('button', {name: 'Copy for AI'})).toBeVisible()
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
    // product-search always runs SQL, so the map has real JDBC evidence to attribute.
    await page.request.get('/api/sample/product-search')

    await openView('activity', 'Live Activity')

    const map = page.locator('.flow-map')
    await expect(map).toBeVisible({timeout: 15_000})
    // The map is shown inline without replacing the event feed.
    await expect(page.locator('.activity-table')).toBeVisible()
    // The running application is the centre of the map, and the map states plainly that it contacts nothing.
    await expect(map.locator('.flow-node--app')).toBeVisible()
    await expect(map).toContainText('contacts nothing and probes nothing')

    // The configured datasource is drawn as a dependency in both the dev (H2) and Docker (PostgreSQL) profiles.
    const database = map.locator('.flow-node--jdbc').first()
    await expect(database).toBeVisible()
    await expect(database).toHaveAttribute(
      'aria-label',
      /jdbc:(?:h2:mem:bootui_sample|postgresql:\/\/[^/]+\/bootui_sample)/
    )

    // Selecting it opens the evidence detail with a deep link back to the source panel.
    await database.click()
    const detail = map.locator('.flow-detail')
    await expect(detail).toContainText('Retained interactions')
    await expect(detail).toContainText('Declared by configuration')
    await detail.locator('.flow-detail__link').click()
    await expect(page).toHaveURL(/\/(database-connection-pools|sql-trace)/)
  })

  test('keeps the Live flow map usable by keyboard and readable as text', async ({openView, page}) => {
    await page.request.get('/api/sample/product-search')

    await openView('activity', 'Live Activity')

    const map = page.locator('.flow-map')
    await expect(map.locator('.flow-node[role="button"]').first()).toBeVisible({timeout: 15_000})

    // Arrow keys move between nodes and Enter selects, without needing a pointer.
    await map.locator('.flow-node[role="button"]').first().focus()
    await page.keyboard.press('Enter')
    await expect(map.locator('.flow-node--selected')).toHaveCount(1)

    // Everything the graph conveys is also available as text for assistive technology.
    const textual = map.locator('ul[aria-label="Service map relationships as text"]')
    await expect(textual).toHaveCount(1)
    await expect(textual).toContainText('retained')
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
