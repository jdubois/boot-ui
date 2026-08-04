// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('REST Client view (Quarkus)', () => {
  test.beforeEach(async ({page}) => {
    await page.request.post('/bootui/api/rest-client-trace/recording', {data: {enabled: true}})
    await page.request.post('/bootui/api/rest-client-trace/clear')
  })

  test('streams captured metadata into the panel and Live Activity', async ({openView, page}) => {
    await openView('rest-client-trace', 'REST Client')
    const autoRefresh = page.getByLabel('Auto-refresh')
    if (!(await autoRefresh.isChecked())) {
      await autoRefresh.check()
    }

    const response = await page.request.post('/api/sample/rest-client-capture')
    expect(response.ok()).toBeTruthy()

    const recentCalls = page.locator('section').filter({hasText: 'Recent calls'})
    const row = recentCalls.locator('tbody tr.rest-row', {hasText: '/api/sample/products'}).first()
    await expect(row).toBeVisible({timeout: 15_000})
    await expect(row).toContainText('Quarkus REST Client Reactive')

    const activityResponse = await page.request.get('/bootui/api/activity')
    expect(activityResponse.ok()).toBeTruthy()
    const activity = await activityResponse.json()
    // The sample calls its own HTTP server, so the inbound trigger and downstream target both create
    // REQUEST entries with the same trace id. The assembler correctly leaves the REST entry top-level
    // rather than choosing an ambiguous parent, but the propagated correlation id must still be present.
    expect(activity.entries.some((entry) => entry.type === 'REST_CLIENT' && entry.correlationId)).toBeTruthy()
  })

  test('pauses, resumes, and clears capture', async ({openView, page}) => {
    await openView('rest-client-trace', 'REST Client')

    await page.getByRole('button', {name: 'Pause'}).click()
    await expect(page.locator('.alert-success')).toContainText(/paused/i)
    await page.request.post('/bootui/api/rest-client-trace/clear')
    await page.request.post('/api/sample/rest-client-capture')
    await page.getByTitle('Refresh', {exact: true}).click()
    await expect(page.locator('section').filter({hasText: 'Recent calls'}).locator('tbody tr.rest-row')).toHaveCount(0)

    await page.getByRole('button', {name: 'Resume'}).click()
    await expect(page.getByRole('button', {name: 'Pause'})).toBeVisible()
    await page.request.post('/api/sample/rest-client-capture')
    await page.getByTitle('Refresh', {exact: true}).click()
    await expect(page.locator('tbody tr.rest-row', {hasText: '/api/sample/products'}).first()).toBeVisible()

    await page.getByRole('button', {name: 'Clear'}).click()
    await acceptConfirm(page)
    await expect(page.locator('tbody tr.rest-row')).toHaveCount(0)
  })
})
