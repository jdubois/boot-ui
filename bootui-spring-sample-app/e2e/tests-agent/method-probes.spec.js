// @ts-check
import {expect, test} from '../tests/fixtures.js'

const SLOW = 'io.github.jdubois.bootui.sample.codepaths.SlowPricingService#quote(Ljava/lang/String;)I'
const ROUTE = 'GET /api/quotes/{sku}'

/**
 * Method probes on the Spring MVC sample with the BootUI agent attached (docs/PLAN-v2.md §5.14, M5-8): Probe this method
 * on the seeded slow route's SlowPricingService.quote, after a confirmation, records the next calls' durations, request
 * ids, and calling frames, metadata only, and a stop ends it; the API refuses a method it cannot probe.
 */
test.describe('Method probes, agent attached', () => {
  test('probes the slow method from its Code Paths node and records its next calls', async ({page}) => {
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get(`/api/quotes/probe-${i}`)).ok()).toBeTruthy()
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
    await page.locator('.code-paths-tree').getByRole('button', {name: 'SlowPricingService.quote'}).click()
    await expect(page.locator('#code-paths-probe-target')).toContainText(SLOW)

    await page.getByRole('button', {name: 'Probe this method'}).click()
    const dialog = page.getByRole('dialog', {name: 'Probe this method?'})
    await expect(dialog).toContainText('never argument or return values')
    await dialog.getByRole('button', {name: 'Start probe'}).click()

    const probe = page.locator('.code-paths-probe').first()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('active', {timeout: 15_000})
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get(`/api/quotes/probed-${i}`)).ok()).toBeTruthy()
    }
    await expect(probe).toContainText(/[3-9] of 20 invocations/, {timeout: 15_000})
    const hits = page.locator('.code-paths-probe-hits tbody tr')
    await expect(hits.first()).toBeVisible()
    await expect(hits.first()).toContainText('returned')
    await expect(hits.first()).toContainText('QuoteService#quote')
    await expect(hits.first().getByRole('link')).toHaveText(/^[0-9a-f]{16}$/)

    await probe.getByRole('button', {name: 'Stop'}).click()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('ended', {timeout: 15_000})
    await expect(probe).toContainText('stopped')

    const listed = await (await page.request.get('/bootui/api/code-paths/probes')).json()
    const ended = listed.probes.find((candidate) => candidate.method === SLOW)
    expect(ended.removal).toBe('removed')
    expect(ended.hits.length).toBeGreaterThanOrEqual(3)
    for (const hit of ended.hits) {
      expect(Object.keys(hit).sort()).toEqual(
        ['caller', 'durationMicros', 'exceptionType', 'outcome', 'requestId', 'threadKind', 'time'].sort()
      )
    }
  })

  test('refuses a method it cannot probe, and a write without the XSRF token', async ({page}) => {
    await page.goto('/bootui/')
    const unknown = await page.evaluate(async () => {
      const token = document.cookie
        .split(';')
        .map((part) => part.trim())
        .find((part) => part.startsWith('XSRF-TOKEN='))
        ?.substring('XSRF-TOKEN='.length)
      const response = await fetch('api/code-paths/probes', {
        method: 'POST',
        headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(token ?? '')},
        body: JSON.stringify({method: 'org.example.Elsewhere#run'})
      })
      return {status: response.status, body: await response.json()}
    })
    expect(unknown.status).toBe(400)
    expect(unknown.body.error).toContain("not in the application's packages")

    const forged = await page.request.post('/bootui/api/code-paths/probes', {data: {method: SLOW}})
    expect(forged.status()).toBe(403)
  })
})
