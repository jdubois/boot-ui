// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Configuration view (Quarkus)', () => {
  test('lists properties and supports searching', async ({openView, page}) => {
    await openView('config', 'Configuration')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(10)

    await page.getByPlaceholder(/Filter by name or value/).fill('sample.greeting')
    await expect(rows.filter({hasText: 'sample.greeting'}).first()).toBeVisible()
    await expect(rows.filter({hasText: 'sample.greeting'}).first()).toContainText('Hello')
  })

  // Quarkus has no runtime config-override write path (overrides target the Spring bootstrap property
  // sources), so the panel is reported read-only via the /bootui/api/panels manifest. Unlike the Spring
  // spec, there is no save/delete-override flow to exercise here — instead this asserts the read-only
  // gating itself is real: the banner, the disabled "Add override" action, and disabled per-row Edit
  // buttons on genuine rendered properties.
  test('reports read-only with no override write path, disabling all edit actions', async ({openView, page}) => {
    await openView('config', 'Configuration')

    await expect(page.getByRole('button', {name: /Add override/})).toBeDisabled()

    const banner = page.locator('.alert', {hasText: 'Configuration overrides are read-only'})
    await expect(banner).toBeVisible()
    await expect(banner).toContainText('Runtime config overrides are not available on Quarkus')
    await expect(banner).toContainText('Existing properties remain visible, but override edits are disabled')

    // A real, visible property row still renders correctly, but its Edit action is disabled.
    await page.getByPlaceholder(/Filter by name or value/).fill('sample.greeting')
    const row = page.locator('table tbody tr', {hasText: 'sample.greeting'}).first()
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', {name: /Edit/})).toBeDisabled()

    // There is no override write path, so the manifest never reports any overrides.
    const response = await page.request.get('/bootui/api/config?limit=5', {
      headers: {'X-Forwarded-For': '127.0.0.1'}
    })
    const body = await response.json()
    expect(body.overrideCount).toBe(0)
  })
})
