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
    const products = await page.request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()

    await openView('activity', 'Live Activity')

    const productsRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/products'}).first()
    await expect(productsRow).toBeVisible({timeout: 15_000})

    await productsRow.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('Request profile')
    await expect(drawer).toContainText('/api/sample/products')

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
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
})
