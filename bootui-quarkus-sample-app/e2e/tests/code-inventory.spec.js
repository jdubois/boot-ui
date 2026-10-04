// @ts-check
import {expect, test} from './fixtures.js'

const PREFIX = 'io.github.jdubois.bootui.sample.inventory.GreetingService#'
const GREET = `${PREFIX}greet(Ljava/lang/String;)Ljava/lang/String;`
const FAREWELL = `${PREFIX}farewell(Ljava/lang/String;)Ljava/lang/String;`

/**
 * The Code Inventory view on Quarkus (docs/PLAN-v2.md §5.15). The default suite runs the sample without the BootUI
 * agent, so the panel is unavailable with the Java Agent panel's reason and links there, and every read answers the
 * unavailable shape. The agent suite (playwright.agent.config.js) sets the `agentAttached` fixture option and asserts
 * the scan of dev mode's classes completes, the greeting `GET /api/hello` runs is executed with that route, and its
 * never-called sibling is not.
 */
test.describe('Code Inventory view (Quarkus)', () => {
  test('reports the run’s executed and never-executed seeds, or why it cannot', async ({
    openView,
    page,
    agentAttached
  }) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-inventory')

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)

      for (const path of ['', '/changes', '/methods', '/dependencies']) {
        const response = await page.request.get(`/bootui/api/code-inventory${path}`)
        expect(response.ok()).toBeTruthy()
        const body = await response.json()
        expect(body.available).toBe(false)
        expect(body.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)
      }

      await openView('code-inventory', 'Code Inventory')
      await expect(page.locator('.panel-availability-alert')).toContainText('inventory sensor')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    expect((await page.request.get('/api/hello')).ok()).toBeTruthy()
    await expect
      .poll(async () => (await (await page.request.get('/bootui/api/code-inventory')).json()).scan?.status, {
        timeout: 60_000
      })
      .toBe('COMPLETE')
    const summary = await (await page.request.get('/bootui/api/code-inventory')).json()
    expect(summary.methods.executed).toBeGreaterThan(0)
    expect(summary.methods.neverExecuted).toBeGreaterThan(0)
    expect(summary.dependencies.declared).toBeGreaterThan(0)
    const methods = await (await page.request.get('/bootui/api/code-inventory/methods?class=GreetingService')).json()
    const greet = methods.methods.find((method) => method.key === GREET)
    expect(greet).toMatchObject({status: 'EXECUTED', firstRoute: 'GET /api/hello'})
    // Never called. When ArC loaded GreetingService before the inventory sensor installed (the sensor installs off the
    // claiming thread), the method is honestly not tracked rather than never executed: it may have run unseen.
    const farewell = methods.methods.find((method) => method.key === FAREWELL)
    expect(['NEVER_EXECUTED', 'NOT_TRACKED']).toContain(farewell.status)

    await openView('code-inventory', 'Code Inventory')
    await expect(page.locator('#code-inventory-headline')).toHaveText(/\d+ of \d+ application methods executed/)
    await expect(page.getByRole('tab').first()).toHaveText('Application code')
    await page.getByRole('button', {name: /io\.github\.jdubois\.bootui\.sample\.inventory/}).click()
    const classes = page.locator('.code-inventory-classes')
    await classes.locator('summary', {hasText: 'GreetingService'}).click()
    await expect(classes).toContainText('greet(Ljava/lang/String;)Ljava/lang/String;')

    await page.getByRole('tab', {name: 'Dependencies'}).click()
    const core = page
      .getByRole('tabpanel')
      .getByRole('row')
      .filter({hasText: /io\.quarkus:quarkus-core(?!-)/})
    await expect(core).toContainText(/Loaded (in|before) this run/)
  })
})
