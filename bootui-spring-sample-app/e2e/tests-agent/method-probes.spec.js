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
    await page
      .locator('.code-paths-tree')
      .locator('.code-paths-node')
      .filter({hasText: 'SlowPricingService.quote'})
      .click()
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
        [
          'arguments',
          'argumentsNotRecorded',
          'caller',
          'durationMicros',
          'exceptionType',
          'outcome',
          'requestId',
          'returned',
          'shapesIncomplete',
          'threadKind',
          'time'
        ].sort()
      )
      // A metadata-only probe: no shape.
      expect(hit.arguments).toEqual([])
      expect(hit.returned).toBeNull()
    }
  })

  test('records argument and return shapes when asked, and never shows them to MCP', async ({page}) => {
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get(`/api/quotes/shapes-${i}`)).ok()).toBeTruthy()
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
    await page
      .locator('.code-paths-tree')
      .locator('.code-paths-node')
      .filter({hasText: 'SlowPricingService.quote'})
      .click()
    await expect(page.locator('#code-paths-probe-target')).toContainText(SLOW)
    await page.getByLabel('Record argument and return shapes').check()
    await page.getByRole('button', {name: 'Probe this method'}).click()
    const dialog = page.getByRole('dialog', {name: 'Probe this method?'})
    await expect(dialog).toContainText('shapes of the arguments and the return value')
    await dialog.getByRole('button', {name: 'Start probe'}).click()

    const probe = page.locator('.code-paths-probe').first()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('active', {timeout: 15_000})
    await expect(probe.locator('.code-paths-probe-shapes-badge')).toHaveText('shapes')
    for (let i = 0; i < 2; i++) {
      expect((await page.request.get(`/api/quotes/sku-shaped-${i}`)).ok()).toBeTruthy()
    }
    await expect(probe).toContainText(/[2-9] of 20 invocations/, {timeout: 15_000})
    const hit = page.locator('.code-paths-probe-hits tbody tr').first()
    await expect(hit.locator('.code-paths-probe-arguments .code-paths-probe-shape')).toHaveText(['String'])
    await expect(page.locator('.code-paths-probe-withheld')).toContainText('bootui.expose-values=FULL')
    await expect(hit.locator('.code-paths-probe-shape').first()).toHaveText('int')

    // The UI's read, under the sample's default MASKED exposure: types and nullness, never a string's length.
    const listed = await (await page.request.get('/bootui/api/code-paths/probes')).json()
    expect(listed.shapesAvailable).toBe(true)
    const shaped = listed.probes.find((candidate) => candidate.method === SLOW && candidate.recordShapes)
    expect(shaped.shapesHiddenReason).toBeNull()
    expect(shaped.hits[0].arguments).toEqual([
      {
        kind: 'string',
        declaredType: 'java.lang.String',
        type: 'java.lang.String',
        size: null,
        present: null,
        constant: null,
        withheld: true
      }
    ])
    expect(shaped.hits[0].returned).toMatchObject({kind: 'primitive', declaredType: 'int'})
    expect(JSON.stringify(shaped)).not.toContain('sku-shaped')
    await probe.getByRole('button', {name: 'Stop'}).click()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('ended', {timeout: 15_000})

    // MCP's get_method_probe, as the CLI's bootui probe show: the same probe, never a shape.
    const forAgents = await callMcpTool(page, 'get_method_probe', {id: shaped.id})
    expect(forAgents.recordShapes).toBe(true)
    expect(forAgents.shapesHiddenReason).toContain('never to MCP or the CLI')
    expect(forAgents.hits.length).toBeGreaterThanOrEqual(2)
    for (const recorded of forAgents.hits) {
      expect(recorded.arguments).toEqual([])
      expect(recorded.returned).toBeNull()
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

/** Calls one BootUI MCP tool from the page, with the MCP server enabled for the call and restored after it. */
async function callMcpTool(page, name, args) {
  await page.goto('/bootui/')
  return page.evaluate(
    async ({name, args}) => {
      const token = decodeURIComponent(
        document.cookie
          .split(';')
          .map((part) => part.trim())
          .find((part) => part.startsWith('XSRF-TOKEN='))
          ?.substring('XSRF-TOKEN='.length) ?? ''
      )
      const headers = {'Content-Type': 'application/json', 'X-XSRF-TOKEN': token}
      const toggle = (enabled) =>
        fetch('api/mcp-server/toggle', {method: 'POST', headers, body: JSON.stringify({enabled})})
      const before = await (await fetch('api/mcp-server')).json()
      await toggle(true)
      try {
        const response = await fetch('api/mcp', {
          method: 'POST',
          headers,
          body: JSON.stringify({jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name, arguments: args}})
        })
        const envelope = await response.json()
        return JSON.parse(envelope.result.content[0].text)
      } finally {
        await toggle(before.enabled)
      }
    },
    {name, args}
  )
}
