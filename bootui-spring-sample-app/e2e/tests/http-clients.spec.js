// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP Clients view', () => {
  test('renders the declared HTTP Interface clients without calling them', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const products = page.locator('.card', {hasText: 'sample-products'})
    await expect(products).toBeVisible()
    await expect(products).toContainText('HTTP Interface')
    await expect(products).toContainText('io.github.jdubois.bootui.sample.httpclient.SampleProductClient')
    await expect(products).toContainText('spring.http.serviceclient.sample-products.base-url')

    const inventory = page.locator('.card', {hasText: 'sample-inventory'})
    await expect(inventory).toBeVisible()
    await expect(inventory).toContainText('io.github.jdubois.bootui.sample.httpclient.SampleInventoryClient')
  })

  test('masks a secret query value in a declared base URL', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const products = page.locator('.card', {hasText: 'sample-products'})
    await expect(products).toContainText('api_key=******')
    await expect(products).not.toContainText('super-secret-value')
  })

  test('distinguishes a client-specific override from an inherited application default', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const products = page.locator('.card', {hasText: 'sample-products'})
    await products.getByRole('button', {name: /effective settings/}).click()
    await expect(products.locator('tr', {hasText: 'Connect timeout'})).toContainText('Client override')

    const inventory = page.locator('.card', {hasText: 'sample-inventory'})
    await inventory.getByRole('button', {name: /effective settings/}).click()
    const inherited = inventory.locator('tr', {hasText: 'Connect timeout'})
    await expect(inherited).toContainText('Application default')
    await expect(inherited).toContainText('spring.http.clients.connect-timeout')
  })

  test('reports a setting the application never configured as explicitly not exposed', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const inventory = page.locator('.card', {hasText: 'sample-inventory'})
    await inventory.getByRole('button', {name: /effective settings/}).click()
    await expect(inventory.locator('tr', {hasText: 'SSL bundle'})).toContainText('Not exposed')
  })

  test('shows the framework-managed builder beans without inventing a base URL', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const builder = page.locator('.card', {hasText: 'restClientBuilder'}).first()
    await expect(builder).toBeVisible()
    await expect(builder).toContainText('RestClient builder')
    await expect(builder).toContainText('Not declared')
  })

  test('filtering narrows the registry and reports an empty result honestly', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const filter = page.getByLabel('Filter HTTP clients')

    await filter.fill('sample-products')
    await expect(page.locator('.card', {hasText: 'sample-products'})).toBeVisible()
    await expect(page.locator('.card', {hasText: 'sample-orders'})).toHaveCount(0)

    await filter.fill('no-such-http-client-xyz')
    await expect(page.locator('text=No HTTP client matches this filter.')).toBeVisible()
  })
})
