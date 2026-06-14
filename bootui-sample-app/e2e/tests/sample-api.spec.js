// @ts-check
import {expect, test} from '@playwright/test'

/**
 * End-to-end checks for the sample application's own REST API. These are the
 * endpoints that BootUI screens (HTTP Probe, Mappings, Security…) reference,
 * so we want explicit coverage of their behaviour from a real client.
 */
test.describe('Sample application REST API', () => {
  test('GET / serves a welcome page that links to BootUI', async ({request}) => {
    const response = await request.get('/')
    expect(response.status()).toBe(200)
    const body = await response.text()
    expect(body).toContain('Welcome to the BootUI sample app')
    expect(body).toContain('href="/bootui/"')
    expect(body).not.toContain('View sample products')
    expect(body).not.toContain('BootUI console')
    expect(body).toContain('Sample action lab')
    expect(body).toContain('GET /api/sample/products')
    expect(body).toContain('Create session data')
    expect(body).toContain('Secure API as admin')
    expect(body).toContain('Ask Spring AI')
  })

  test('homepage action lab exercises sample flows', async ({page}) => {
    await page.goto('/')

    await page.getByRole('button', {name: 'Check products'}).click()
    await expect(page.locator('#sample-action-status')).toContainText(/Loaded \d+ active products/)
    await expect(page.locator('#sample-action-result')).toContainText('BootUI Starter')

    await page.getByRole('button', {name: 'Run an SQL query'}).click()
    await expect(page.locator('#sample-action-status')).toContainText(/Ran a live SQL SELECT and matched \d+ product/)
    await expect(page.locator('#sample-action-result')).toContainText('Sample Console')

    await page.getByRole('button', {name: 'Create session data'}).click()
    await expect(page.locator('#session-data-status')).toContainText('Added 5 attributes')
    await expect(page.locator('#sample-action-result')).toContainText('sampleMessage')

    await page.getByRole('button', {name: 'Secure API as admin'}).click()
    await expect(page.locator('#sample-action-status')).toContainText('Admin access succeeded')
    await expect(page.locator('#sample-action-result')).toContainText('Secure Hello, world')

    await page.getByRole('button', {name: 'Secure API as developer'}).click()
    await expect(page.locator('#sample-action-status')).toContainText('HTTP 403')

    await page.getByRole('button', {name: 'Generate metrics traffic'}).click()
    await expect(page.locator('#sample-action-status')).toContainText(/Recorded \d+ samples/)

    await page.getByRole('button', {name: 'Make a multi-hop request'}).click()
    await expect(page.locator('#sample-action-status')).toContainText('Completed a multi-hop request')

    await page.getByRole('button', {name: 'Fail authentication'}).click()
    await expect(page.locator('#sample-action-status')).toContainText('Authentication failed with HTTP 401')
  })

  test('GET /api/hello returns a simple HTTP probe greeting', async ({request}) => {
    const response = await request.get('/api/hello')
    expect(response.status()).toBe(200)
    expect(await response.text()).toBe('Hello, world')
  })

  test('GET /api/sample/hello returns the configured greeting', async ({request}) => {
    const response = await request.get('/api/sample/hello')
    expect(response.status()).toBe(200)
    expect(await response.text()).toBe('Hello, BootUI! (retries=3)')
  })

  test('GET /api/sample/products returns only the active sample products', async ({request}) => {
    const response = await request.get('/api/sample/products')
    expect(response.status()).toBe(200)
    const products = await response.json()
    expect(Array.isArray(products)).toBeTruthy()
    expect(products.length).toBeGreaterThanOrEqual(2)
    for (const product of products) {
      expect(product).toHaveProperty('name')
      expect(product).toHaveProperty('category')
      expect(product.active).toBe(true)
    }
    const names = products.map((p) => p.name)
    expect(names).toContain('BootUI Starter')
    expect(names).toContain('Sample Console')
    expect(names).not.toContain('Archived Prototype')
  })

  test('GET /api/sample/product-search runs a live SQL query for the given term', async ({request}) => {
    const response = await request.get('/api/sample/product-search?term=console')
    expect(response.status()).toBe(200)
    const products = await response.json()
    expect(Array.isArray(products)).toBeTruthy()
    const names = products.map((p) => p.name)
    expect(names).toContain('Sample Console')
    expect(names).not.toContain('BootUI Starter')
  })

  test('GET /api/sample/metrics-burst records custom meters', async ({request}) => {
    const response = await request.get('/api/sample/metrics-burst?count=3')
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.iterations).toBe(3)
    expect(body.counter).toBe('sample.orders.processed')
    expect(body.timer).toBe('sample.orders.duration')
    expect(body.counterTotal).toBeGreaterThanOrEqual(3)
  })

  test('GET /api/sample/allocate allocates a bounded heap buffer', async ({request}) => {
    const response = await request.get('/api/sample/allocate?mb=8')
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.allocatedMb).toBe(8)
    expect(body.heapUsedMb).toBeGreaterThan(0)
  })

  test('GET /api/sample/slow sleeps for the requested bounded interval', async ({request}) => {
    const response = await request.get('/api/sample/slow?ms=200')
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.sleptMillis).toBe(200)
    expect(typeof body.thread).toBe('string')
  })

  test('GET /api/sample/pool-stress runs concurrent read-only queries', async ({request}) => {
    const response = await request.get('/api/sample/pool-stress?queries=4')
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.queries).toBe(4)
    expect(body.elapsedMillis).toBeGreaterThanOrEqual(0)
  })

  test('GET /api/sample/chained performs a multi-step request', async ({request}) => {
    const response = await request.get('/api/sample/chained')
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.activeProducts).toBeGreaterThanOrEqual(0)
    expect(body.searchMatches).toBeGreaterThanOrEqual(0)
  })

  test('/admin requires basic authentication with the ADMIN role', async ({request}) => {
    const anonymous = await request.get('/admin', {failOnStatusCode: false})
    expect(anonymous.status()).toBe(401)

    const asUser = await request.get('/admin', {
      headers: {Authorization: 'Basic ' + Buffer.from('developer:developer').toString('base64')},
      failOnStatusCode: false
    })
    expect(asUser.status()).toBe(403)

    const asAdmin = await request.get('/admin', {
      headers: {Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64')}
    })
    expect(asAdmin.status()).toBe(200)
    expect(await asAdmin.text()).toBe('BootUI sample admin')
  })

  test('/api/secure requires basic authentication with the ADMIN role', async ({request}) => {
    const anonymous = await request.get('/api/secure', {failOnStatusCode: false})
    expect(anonymous.status()).toBe(401)

    const asUser = await request.get('/api/secure', {
      headers: {Authorization: 'Basic ' + Buffer.from('developer:developer').toString('base64')},
      failOnStatusCode: false
    })
    expect(asUser.status()).toBe(403)

    const asAdmin = await request.get('/api/secure', {
      headers: {Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64')}
    })
    expect(asAdmin.status()).toBe(200)
    expect(await asAdmin.text()).toBe('Secure Hello, world')
  })
})
