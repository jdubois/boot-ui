// @ts-check
import { test, expect } from '@playwright/test'

/**
 * End-to-end checks for the sample application's own REST API. These are the
 * endpoints that BootUI screens (HTTP Probe, Mappings, Security…) reference,
 * so we want explicit coverage of their behaviour from a real client.
 */
test.describe('Sample application REST API', () => {

  test('GET /api/sample/hello returns the configured greeting', async ({ request }) => {
    const response = await request.get('/api/sample/hello')
    expect(response.status()).toBe(200)
    expect(await response.text()).toBe('Hello, BootUI! (retries=3)')
  })

  test('GET /api/sample/products returns only the active sample products', async ({ request }) => {
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
    const names = products.map(p => p.name)
    expect(names).toContain('BootUI Starter')
    expect(names).toContain('Sample Console')
    expect(names).not.toContain('Archived Prototype')
  })

  test('/admin requires basic authentication with the ADMIN role', async ({ request }) => {
    const anonymous = await request.get('/admin', { failOnStatusCode: false })
    expect(anonymous.status()).toBe(401)

    const asUser = await request.get('/admin', {
      headers: { Authorization: 'Basic ' + Buffer.from('developer:developer').toString('base64') },
      failOnStatusCode: false
    })
    expect(asUser.status()).toBe(403)

    const asAdmin = await request.get('/admin', {
      headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') }
    })
    expect(asAdmin.status()).toBe(200)
    expect(await asAdmin.text()).toBe('BootUI sample admin')
  })
})
