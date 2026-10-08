// @ts-check
import {expect, test} from '../tests/fixtures.js'

const METHOD =
  'io.github.jdubois.bootui.webfluxsample.greeting.GreetingService#greet(Ljava/lang/String;)Ljava/lang/String;'
const ROUTE = 'GET /api/greetings/{name}'

/**
 * Method probes on Spring WebFlux with the BootUI agent attached (docs/PLAN-v2.md §5.14): Probe this method on the
 * service the greeting handler calls while its Mono is subscribed, after a confirmation, records the next calls'
 * durations and calling frames, metadata only, and a stop ends it. The service returns a plain String, so the probe
 * times the call itself rather than an assembly.
 */
test.describe('Method probes on Spring WebFlux, agent attached', () => {
  test('probes a method from its Code Paths node, metadata only, and stops it', async ({page}) => {
    // The service is @Cacheable: a fresh name per request makes each one run the method.
    const run = Date.now()
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get(`/api/greetings/probe-${run}-${i}`)).ok()).toBeTruthy()
    }
    await expect
      .poll(
        async () => {
          const report = await (await page.request.get('/bootui/api/code-paths')).json()
          return report.routes?.some((route) => route.route === ROUTE) ?? false
        },
        {timeout: 30_000}
      )
      .toBe(true)

    await page.goto(`/bootui/#/code-paths?route=${encodeURIComponent(ROUTE)}`)
    await expect(page.locator('#code-paths-tree-heading')).toHaveText(ROUTE)
    await page.locator('.code-paths-tree').getByRole('button', {name: 'GreetingService.greet'}).click()
    await expect(page.locator('#code-paths-probe-target')).toContainText(METHOD)
    await expect(page.getByLabel('Record argument and return shapes')).not.toBeChecked()

    await page.getByRole('button', {name: 'Probe this method'}).click()
    const dialog = page.getByRole('dialog', {name: 'Probe this method?'})
    await expect(dialog).toContainText('never argument or return values')
    await dialog.getByRole('button', {name: 'Start probe'}).click()

    const probe = page.locator('.code-paths-probe').first()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('active', {timeout: 15_000})
    await expect(probe.locator('.code-paths-probe-shapes-badge')).toHaveCount(0)
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get(`/api/greetings/probed-${run}-${i}`)).ok()).toBeTruthy()
    }
    await expect(probe).toContainText(/[3-9] of 20 invocations/, {timeout: 15_000})
    const hits = page.locator('.code-paths-probe-hits tbody tr')
    await expect(hits.first()).toBeVisible()
    await expect(hits.first()).toContainText('returned')

    await probe.getByRole('button', {name: 'Stop'}).click()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('ended', {timeout: 15_000})
    await expect(probe).toContainText('stopped')

    const listed = await (await page.request.get('/bootui/api/code-paths/probes')).json()
    const ended = listed.probes.find((candidate) => candidate.method === METHOD && !candidate.recordShapes)
    expect(ended.async).toBe(false)
    expect(ended.removal).toBe('removed')
    expect(ended.hits.length).toBeGreaterThanOrEqual(3)
    for (const hit of ended.hits) {
      expect(hit.outcome).toBe('returned')
      // A metadata-only probe: no shape.
      expect(hit.arguments).toEqual([])
      expect(hit.returned).toBeNull()
    }
  })
})
