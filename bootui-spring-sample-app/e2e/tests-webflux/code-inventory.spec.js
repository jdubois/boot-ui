// @ts-check
import {expect, test} from '../tests/fixtures.js'

const GREET =
  'io.github.jdubois.bootui.webfluxsample.greeting.GreetingService#greet(Ljava/lang/String;)Ljava/lang/String;'

/**
 * The Code Inventory view on Spring WebFlux (docs/PLAN-v2.md §5.15). Without the BootUI agent the panel is unavailable
 * with the Java Agent panel's reason. With it (the `agentAttached` fixture option of playwright.webflux-agent.config.js),
 * the scan of the reactive sample's classes completes, the greeting a request runs is executed with its route, and the
 * dependencies the run loaded are listed.
 */
test.describe('Code Inventory view on Spring WebFlux', () => {
  test('reports the run’s executed methods and loaded dependencies, or why it cannot', async ({
    page,
    request,
    baseURL,
    agentAttached
  }) => {
    const panels = await (await request.get(`${baseURL}/bootui/api/panels`)).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-inventory')
    expect(panel).toBeTruthy()

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)
      for (const path of ['', '/changes', '/methods', '/dependencies']) {
        const body = await (await request.get(`${baseURL}/bootui/api/code-inventory${path}`)).json()
        expect(body.available).toBe(false)
        expect(body.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)
      }

      await page.goto('/bootui/#/code-inventory')
      await expect(page.locator('main h2').filter({hasText: /^Code Inventory/})).toBeVisible()
      await expect(page.locator('.panel-availability-alert')).toContainText('inventory sensor')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    expect((await request.get(`${baseURL}/api/greetings/Inventory`)).ok()).toBeTruthy()
    await expect
      .poll(async () => (await (await request.get(`${baseURL}/bootui/api/code-inventory`)).json()).scan?.status, {
        timeout: 60_000
      })
      .toBe('COMPLETE')
    const summary = await (await request.get(`${baseURL}/bootui/api/code-inventory`)).json()
    expect(summary.methods.executed).toBeGreaterThan(0)
    expect(summary.methods.neverExecuted).toBeGreaterThan(0)
    expect(summary.dependencies.loaded).toBeGreaterThan(0)
    const methods = await (
      await request.get(`${baseURL}/bootui/api/code-inventory/methods?class=GreetingService`)
    ).json()
    const greet = methods.methods.find((method) => method.key === GREET)
    expect(greet).toMatchObject({status: 'EXECUTED', firstRoute: 'GET /api/greetings/{name}'})

    await page.goto('/bootui/#/code-inventory')
    await expect(page.locator('main h2').filter({hasText: /^Code Inventory/})).toBeVisible()
    await expect(page.locator('#code-inventory-headline')).toHaveText(/\d+ of \d+ application methods executed/)
    await page.getByRole('tab', {name: 'Dependencies'}).click()
    const webflux = page.getByRole('tabpanel').getByRole('row').filter({hasText: 'org.springframework:spring-webflux'})
    await expect(webflux).toContainText(/Loaded (in|before) this run/)
  })
})
