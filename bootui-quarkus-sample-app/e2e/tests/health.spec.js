// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Health view (Quarkus)', () => {
  test('renders friendly health summary and tree from real SmallRye Health data', async ({openView, page}) => {
    await openView('health', 'Health')

    await expect(page.getByText('Overall status')).toBeVisible()
    await expect(page.getByText('Component tree')).toBeVisible()
    const rootCard = page.locator('main .card').first()
    await expect(rootCard).toBeVisible()
    await expect(rootCard.locator('.badge')).toHaveText(/UP|DOWN|UNKNOWN|OUT_OF_SERVICE|DISABLED/)
    await expect(page.locator('main pre')).toHaveCount(0)

    // The root node renders the SmallRye "application" aggregate status and the sample app's real
    // Agroal datasource contributor, not a placeholder.
    await expect(page.locator('main')).toContainText('application')
    await expect(page.locator('main')).toContainText('Database connections health check')

    // Cross-check the same data against the raw API so the rendered badge genuinely reflects backend status.
    const response = await page.request.get('/bootui/api/health', {headers: {'X-Forwarded-For': '127.0.0.1'}})
    const body = await response.json()
    expect(body.status).toBe('UP')
    expect(body.components.some((c) => c.name === 'Database connections health check')).toBeTruthy()
  })

  test('renders nested component details in a readable table', async ({openView, page}) => {
    await openView('health', 'Health')

    const dbSummary = page.locator('details.card > summary', {hasText: /Database connections health check/}).first()
    const dbNode = dbSummary.locator('xpath=..')
    await expect(dbNode).toBeVisible()
    await expect(dbNode).toContainText('Details')
    await expect(dbNode.locator('table')).toBeVisible()
    // The Agroal health check reports the named datasource ("<default>") as a detail key/value pair.
    await expect(dbNode.locator('table')).toContainText('default')
    await expect(dbNode.locator('table')).toContainText('UP')
  })
})
