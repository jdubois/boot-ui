// @ts-check
import {expect, test} from './fixtures.js'

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

    // The SQL-backed request is correlated exactly by its serving thread (no distributed trace id
    // required), so the drawer shows the "exact" badge rather than the "approximate" fallback.
    await expect(drawer.getByText('exact', {exact: true})).toBeVisible()
    await expect(drawer.getByText('approximate', {exact: true})).toHaveCount(0)

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('correlates a security event to the request exactly by serving thread', async ({openView, page}) => {
    // An authenticated, SQL-backed admin request publishes an AUTHENTICATION_SUCCESS audit event on
    // the request's serving thread, so the profiler can pin it to this exact request rather than to
    // any other concurrent request that happens to share the principal.
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
    // Captured on the request's own serving thread, so the event is badged exact, not just principal.
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
    // A correlated security event flags the request row as authenticated: a lock icon plus a grey pill
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
    await productsRow.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('button', {name: /Copy profile/})).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(drawer).toHaveCount(0)
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
