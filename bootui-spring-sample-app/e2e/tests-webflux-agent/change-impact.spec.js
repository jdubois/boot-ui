// @ts-check
import {expect, test} from '../tests/fixtures.js'

const SYMBOL = 'GreetingService#greet'
const ROUTE = 'GET /api/greetings/{name}'

/**
 * Change impact by method on Spring WebFlux, with the BootUI agent attached (docs/PLAN-v2.md §5.7, §5.17): the handler
 * calls GreetingService.greet while its Mono is subscribed, inside the request's assembly-only tree, so a method that no
 * route is mapped to resolves through the code-paths sensor, and the route whose trees ran it is listed as having run it.
 */
test.describe('Change impact by method on Spring WebFlux, with the agent', () => {
  test('?impact= opens a method that ran and lists the route whose call trees ran it', async ({page}) => {
    // The service is @Cacheable: a fresh name per request makes each one run the method.
    const run = Date.now()
    for (let i = 0; i < 3; i += 1) {
      expect((await page.request.get(`/api/greetings/impact-${run}-${i}`)).ok()).toBeTruthy()
    }
    // Trees settle about two seconds after their last fragment.
    await expect
      .poll(
        async () => {
          const response = await page.request.get(
            `/bootui/api/runtime-insights/impact?symbol=${encodeURIComponent(SYMBOL)}`
          )
          const impact = await response.json()
          return impact.observed?.find((route) => route.route === ROUTE)?.executedRequests ?? 0
        },
        {timeout: 20_000}
      )
      .toBeGreaterThan(0)
    const impact = await (
      await page.request.get(`/bootui/api/runtime-insights/impact?symbol=${encodeURIComponent(SYMBOL)}`)
    ).json()
    expect(impact.status).toBe('RESOLVED')
    expect(impact.observedFrom).toBe('ROUTE_TREES')

    await page.goto(`/bootui/#/runtime-insights?impact=${encodeURIComponent(SYMBOL)}`)
    await expect(page.getByRole('tab', {name: /^Change impact/})).toHaveAttribute('aria-selected', 'true')
    const panel = page.locator('.insight-impact')
    await expect(panel.getByRole('combobox', {name: /Symbol to check/})).toHaveValue(SYMBOL)
    await expect(panel.locator('.insight-impact-node')).toContainText('method')
    await expect(panel.locator('.insight-impact-node')).toContainText(SYMBOL)
    const observed = panel.locator('[data-list="observed"]')
    await expect(observed.getByRole('heading', {name: /Ran it in this run/})).toBeVisible()
    await expect(observed).toContainText(ROUTE)
    await expect(observed).toContainText(/ran it of \d+ requests?/)
  })
})
