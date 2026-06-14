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
})
