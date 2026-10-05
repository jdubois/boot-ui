// @ts-check
import {expect, test} from '../tests/fixtures.js'

const SEED = '/api/insights/orders/after-response'

/**
 * Work a request hands to a raw thread pool, with the BootUI agent attached (docs/PLAN-v2.md M5-2, §5.17): Live
 * Activity nests it under its request as an ASYNC entry badged "after response", the request profile lists it under
 * Handoffs, and Runtime Insights reports the seed but not its counterexample.
 */
test.describe('Work after the response', () => {
  test('is nested under its request, profiled, and observed', async ({openView, page}) => {
    expect((await page.request.get(SEED)).ok()).toBeTruthy()
    expect((await page.request.get(`${SEED}/waits`)).ok()).toBeTruthy()

    await expect
      .poll(
        async () => {
          const feed = await (await page.request.get('/bootui/api/activity?source=journal&type=ASYNC&limit=50')).json()
          // Its handoff once it ended: while it runs, the feed shows it as a RUNNING entry.
          return feed.entries.some(
            (entry) => (entry.badges ?? []).includes('AFTER_RESPONSE') && !(entry.badges ?? []).includes('RUNNING')
          )
        },
        {timeout: 15_000}
      )
      .toBe(true)

    const requests = await (await page.request.get('/bootui/api/activity?source=journal&type=REQUEST&limit=200')).json()
    const seed = requests.entries.find((entry) => entry.path === SEED)
    // Another request's after-response work can satisfy the poll above, so this request's own handoff is waited for.
    const journalOf = async () => (await page.request.get(`/bootui/api/activity/request/${seed.id}/journal`)).json()
    await expect.poll(async () => (await journalOf()).handoffs?.[0]?.sqlCount ?? 0, {timeout: 15_000}).toBe(1)
    const journal = await journalOf()
    expect(journal.handoffs).toHaveLength(1)
    expect(journal.handoffs[0]).toMatchObject({afterResponse: true, sqlCount: 1, failed: false})
    const profile = await (await page.request.get(`/bootui/api/activity/request/${seed.id}`)).json()
    expect(profile.correlationTiers.find((tier) => tier.tier === 'PROPAGATED')).toMatchObject({available: true})
    // The propagated statement ran while the response was written, but it is the handoff's work, not lazy loading.
    const report = await (await page.request.get('/bootui/api/runtime-insights')).json()
    expect(
      report.observations.filter(
        (observation) => observation.kind === 'lazy-sql-after-handler' && observation.subject.includes(SEED)
      )
    ).toHaveLength(0)

    // The seed request's profile drawer lists the handoff, badged after response.
    await openView('activity', 'Live Activity')
    const seedRow = page
      .locator('.activity-table tbody tr', {hasText: SEED})
      .filter({hasNotText: `${SEED}/waits`})
      .filter({has: page.getByRole('button', {name: /Profile/})})
      .first()
    await expect(seedRow).toBeVisible({timeout: 15_000})
    await seedRow.getByRole('button', {name: /Profile/}).click()
    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    const handoff = drawer.locator('.request-journal__handoff').first()
    await expect(handoff).toBeVisible({timeout: 15_000})
    await expect(handoff).toContainText('1 SQL statement')
    await expect(handoff.locator('.badge', {hasText: 'after response'})).toBeVisible()
    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)

    await openView('activity', 'Live Activity')
    const type = page.getByLabel('Type', {exact: true})
    await type.selectOption('ASYNC')
    // The seed's runAsync task ran after the response; the counterexample's task, which the handler waited for, did not.
    const asyncRow = page.locator('tr', {hasText: 'CompletableFuture$AsyncRun'}).first()
    await expect(asyncRow).toBeVisible()
    await expect(asyncRow.locator('.activity-entry-badge', {hasText: 'after response'})).toBeVisible()
    await expect(
      page.locator('tr', {hasText: 'FutureTask'}).first().locator('.activity-entry-badge', {hasText: 'after response'})
    ).toHaveCount(0)

    await openView('runtime-insights', 'Runtime Insights')
    await expect(page.getByRole('heading', {name: 'Work after the response', level: 2, exact: true})).toBeVisible({
      timeout: 15_000
    })
    const group = page
      .locator('nav[aria-label="Observations"] > div')
      .filter({has: page.getByRole('heading', {name: 'Work after the response', level: 2, exact: true})})
    await expect(group.locator('.insight-item', {hasText: SEED}).first()).toBeVisible()
    await expect(group.locator('.insight-item', {hasText: `${SEED}/waits`})).toHaveCount(0)
  })
})
