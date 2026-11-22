// @ts-check

export function registerArchivedRouteBreakdownTest(test, expect) {
  test('loads route timing for the persisted request recording', async ({openView, page}) => {
    const response = await page.request.get('/bootui/api/activity')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    const requestId = 'archived-route-breakdown'
    const path = '/api/archived-orders'
    const routeName = `GET ${path}`
    const scope = {namespace: 'test-team', application: 'orders sample', run: 'archived-run'}
    const insightReads = []

    await page.route('**/api/activity{,?*}', (route) =>
      route.fulfill({
        json: {
          ...report,
          entries: [
            {
              id: requestId,
              type: 'REQUEST',
              timestamp: Date.now(),
              severity: 'OK',
              summary: routeName,
              durationMs: 120,
              method: 'GET',
              path,
              status: 200,
              profileable: true
            }
          ],
          origins: [{...scope, entryId: requestId, instanceId: 'old-instance', globalSequence: 1}],
          pageInfo: {persistent: true, hasMore: false, nextCursor: null}
        }
      })
    )
    await page.route(`**/api/activity/request/${requestId}/journal?*`, (route) =>
      route.fulfill({
        json: {available: true, requestId, route: routeName, status: 200, durationMicros: 120000, timeline: []}
      })
    )
    await page.route('**/api/runtime-insights{,?*}', (route) => {
      const query = new URL(route.request().url()).searchParams
      insightReads.push(Object.fromEntries(query))
      const archived = Object.entries(scope).every(([key, value]) => query.get(key) === value)
      return route.fulfill({
        json: {
          available: true,
          observations: [
            {
              id: 'route-breakdown',
              kind: 'route-time-breakdown',
              subject: routeName,
              sentence: archived ? 'Archived route took 120 ms.' : 'Current route took 9 ms.'
            }
          ]
        }
      })
    })

    await openView('activity', 'Live Activity')
    await page
      .getByRole('row', {name: new RegExp(path)})
      .getByRole('button', {name: /Profile/})
      .click()
    const drawer = page.getByRole('dialog', {name: 'Request profile'})
    const button = drawer.getByRole('button', {name: 'Why this route is slow'})
    await expect(button).toBeVisible()
    expect(insightReads).toEqual([])
    await button.click()
    await expect(drawer.getByText('Archived route took 120 ms.')).toBeVisible()
    await expect(drawer.getByRole('link', {name: 'Open in Runtime Insights'})).toHaveCount(0)
    expect(insightReads).toEqual([scope])
  })
}
