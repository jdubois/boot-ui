// @ts-check
import {expect, test} from '../tests/fixtures.js'

const ROUTE = 'GET /api/greetings/{name}'

/**
 * The Code Paths view on Spring WebFlux (docs/PLAN-v2.md §5.14). Without the BootUI agent the panel is unavailable with
 * the Java Agent panel's reason, and every read answers the unavailable shape. With it (the `agentAttached` fixture
 * option of playwright.webflux-agent.config.js), every WebFlux route is assembly only: its tree times the pipeline's
 * assembly on the subscribing thread, and the panel says so.
 */
test.describe('Code Paths view on Spring WebFlux', () => {
  test('records assembly-only route trees, or says why it cannot', async ({page, request, baseURL, agentAttached}) => {
    const panels = await (await request.get(`${baseURL}/bootui/api/panels`)).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-paths')

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)
      for (const path of ['', '/route?route=GET%20%2F', '/requests/0000000000000000', '/beans']) {
        const body = await (await request.get(`${baseURL}/bootui/api/code-paths${path}`)).json()
        expect(body.available).toBe(false)
      }

      await page.goto('/bootui/#/code-paths')
      await expect(page.locator('main h2').filter({hasText: /^Code Paths/})).toBeVisible()
      await expect(page.locator('.panel-availability-alert')).toContainText('code-paths sensor')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    for (let i = 0; i < 4; i++) {
      expect((await request.get(`${baseURL}/api/greetings/code-paths-${i}`)).ok()).toBeTruthy()
    }
    // Each request's tree settles about two seconds after its last fragment.
    await expect
      .poll(
        async () => {
          const report = await (await request.get(`${baseURL}/bootui/api/code-paths`)).json()
          return report.routes?.find((route) => route.route === ROUTE)?.warmRequests ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(3)
    const report = await (await request.get(`${baseURL}/bootui/api/code-paths`)).json()
    expect(report.available).toBe(true)
    expect(report.routes.length).toBeGreaterThan(0)
    expect(report.routes.every((route) => route.assemblyOnly)).toBe(true)
    const tree = await (
      await request.get(`${baseURL}/bootui/api/code-paths/route?route=${encodeURIComponent(ROUTE)}`)
    ).json()
    expect(tree.available).toBe(true)

    await page.goto(`/bootui/#/code-paths?route=${encodeURIComponent(ROUTE)}`)
    await expect(page.locator('main h2').filter({hasText: /^Code Paths/})).toBeVisible()
    await expect(page.locator('#code-paths-headline')).toHaveText(/\d+ routes? with a call tree/)
    await expect(page.locator('#code-paths-tree-heading')).toHaveText(ROUTE)
    await expect(page.locator('.code-paths-tree')).toContainText('GreetingController.greet')
    await expect(page.locator('.code-paths-assembly-note')).toContainText('Assembly only.')
    await expect(page.locator('.code-paths-routes .code-paths-assembly').first()).toBeVisible()
  })
})
