// @ts-check
import {expect, test} from '../tests/fixtures.js'

const METHOD = 'io.github.jdubois.bootui.sample.codepaths.SlowPricingService#quote(Ljava/lang/String;)I'
const ROUTE = 'GET /api/quotes/{sku}'

/**
 * Method probes on Quarkus with the BootUI agent attached (docs/PLAN-v2.md §5.14): Probe this method on the seeded
 * slow blocking route's SlowPricingService.quote, after a confirmation, records the next calls' durations, request ids,
 * and calling frames, metadata only, and a stop ends it.
 */
test.describe('Method probes on Quarkus, agent attached', () => {
  test('probes the slow method from its Code Paths node, metadata only, and stops it', async ({page}) => {
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
    await page
      .locator('.code-paths-tree')
      .locator('.code-paths-node')
      .filter({hasText: 'SlowPricingService.quote'})
      .click()
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
