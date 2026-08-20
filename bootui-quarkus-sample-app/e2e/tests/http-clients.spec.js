// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP Clients view (Quarkus)', () => {
  test('renders the @RegisterRestClient interfaces captured at build time', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const api = page.locator('.card', {hasText: 'sample-api-client'})
    await expect(api).toBeVisible()
    await expect(api).toContainText('REST Client interface')
    await expect(api).toContainText('io.github.jdubois.bootui.sample.restclient.SampleApiClient')
    await expect(api).toContainText('quarkus.rest-client.sample-api-client.url')

    // Never injected, never called: the panel reports registrations, not traffic.
    const inventory = page.locator('.card', {hasText: 'sample-inventory-client'})
    await expect(inventory).toBeVisible()
    await expect(inventory).toContainText('io.github.jdubois.bootui.sample.restclient.SampleInventoryClient')
  })

  test('masks a secret query value in a declared base URL', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const inventory = page.locator('.card', {hasText: 'sample-inventory-client'})
    await expect(inventory).toContainText('api-key=******')
    await expect(inventory).not.toContainText('super-secret-value')
  })

  test('distinguishes a client-specific override from an inherited global default', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const api = page.locator('.card', {hasText: 'sample-api-client'})
    await api.getByRole('button', {name: /effective settings/}).click()
    const override = api.locator('tr', {hasText: 'Connect timeout'})
    await expect(override).toContainText('Client override')
    await expect(override).toContainText('quarkus.rest-client.sample-api-client.connect-timeout')

    const inventory = page.locator('.card', {hasText: 'sample-inventory-client'})
    await inventory.getByRole('button', {name: /effective settings/}).click()
    const inherited = inventory.locator('tr', {hasText: 'Connect timeout'})
    await expect(inherited).toContainText('Application default')
    await expect(inherited).toContainText('quarkus.rest-client.connect-timeout')
  })

  test('reports a setting the application never configured as explicitly not exposed', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const api = page.locator('.card', {hasText: 'sample-api-client'})
    await api.getByRole('button', {name: /effective settings/}).click()
    await expect(api.locator('tr', {hasText: 'TLS configuration'})).toContainText('Not exposed')
  })

  test('filtering narrows the registry and reports an empty result honestly', async ({openView, page}) => {
    await openView('http-clients', 'HTTP Clients')

    const filter = page.getByLabel('Filter HTTP clients')

    await filter.fill('sample-inventory-client')
    await expect(page.locator('.card', {hasText: 'sample-inventory-client'})).toBeVisible()
    await expect(page.locator('.card', {hasText: 'sample-api-client'})).toHaveCount(0)

    await filter.fill('no-such-http-client-xyz')
    await expect(page.locator('text=No HTTP client matches this filter.')).toBeVisible()
  })
})
