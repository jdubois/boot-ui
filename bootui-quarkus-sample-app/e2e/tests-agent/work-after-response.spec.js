// @ts-check
import {expect, test} from '../tests/fixtures.js'

const SEED = '/api/insights/orders/after-response'

/**
 * Work a resource hands to a raw thread pool, with the BootUI agent attached (docs/PLAN-v2.md M5-2, §5.17): the agent's
 * executors sensor carries the request into the pool, the journal records the query as the request's handoff, badged
 * after the response, and Runtime Insights reports the seed but not its counterexample, whose resource waits.
 */
test.describe('Work after the response (Quarkus)', () => {
  test('is recorded as the request’s handoff and observed', async ({openView, page}) => {
    expect((await page.request.get(SEED)).ok()).toBeTruthy()
    expect((await page.request.get(`${SEED}/waits`)).ok()).toBeTruthy()

    const seedRequest = async () => {
      const requests = await (
        await page.request.get('/bootui/api/activity?source=journal&type=REQUEST&limit=200')
      ).json()
      return requests.entries.find((entry) => entry.path === SEED)
    }
    await expect.poll(async () => (await seedRequest())?.id, {timeout: 15_000}).toBeTruthy()
    const seed = await seedRequest()
    // The seeded task queries 200 ms after the response: wait, bounded, until its handoff ended and was recorded.
    await expect
      .poll(
        async () =>
          (await (await page.request.get(`/bootui/api/activity/request/${seed.id}/journal`)).json()).handoffs?.length ??
          0,
        {timeout: 15_000}
      )
      .toBe(1)
    const journal = await (await page.request.get(`/bootui/api/activity/request/${seed.id}/journal`)).json()
    expect(journal.handoffs[0]).toMatchObject({afterResponse: true, sqlCount: 1, failed: false})
    const feed = await (await page.request.get('/bootui/api/activity?source=journal&type=ASYNC&limit=50')).json()
    expect(
      feed.entries.some((entry) => entry.parentId === seed.id && (entry.badges ?? []).includes('AFTER_RESPONSE'))
    ).toBe(true)

    await expect
      .poll(
        async () => {
          const report = await (await page.request.get('/bootui/api/runtime-insights')).json()
          return report.observations
            .filter((observation) => observation.kind === 'work-after-response')
            .map((observation) => observation.subject)
        },
        {timeout: 15_000}
      )
      .toContain(`GET ${SEED}`)
    const report = await (await page.request.get('/bootui/api/runtime-insights')).json()
    expect(
      report.observations.filter(
        (observation) => observation.kind === 'work-after-response' && observation.subject === `GET ${SEED}/waits`
      )
    ).toHaveLength(0)

    await openView('runtime-insights', 'Runtime Insights')
    const group = page.locator('.insight-row').filter({has: page.getByText('Work after the response', {exact: true})})
    await expect(group.locator('.insight-item', {hasText: SEED}).first()).toBeVisible({timeout: 15_000})
    await expect(group.locator('.insight-item', {hasText: `${SEED}/waits`})).toHaveCount(0)
  })
})
