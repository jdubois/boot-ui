// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Code Paths view on Quarkus (docs/PLAN-v2.md §5.14). The default suite runs the sample without the BootUI agent,
 * so the panel is unavailable with the Java Agent panel's reason and links there, and every read answers the
 * unavailable shape. The agent suite (playwright.agent.config.js) sets the `agentAttached` fixture option and asserts
 * the seeded slow blocking route's tree names SlowPricingService.quote under the two bean layers that call it.
 */
test.describe('Code Paths view (Quarkus)', () => {
  test('names the slow method of the seeded route, or says why it cannot', async ({openView, page, agentAttached}) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-paths')

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)

      for (const path of ['', '/route?route=GET%20%2Fapi%2Fhello', '/requests/0000000000000000', '/beans', '/probes']) {
        const response = await page.request.get(`/bootui/api/code-paths${path}`)
        expect(response.ok()).toBeTruthy()
        const body = await response.json()
        expect(body.available).toBe(false)
        expect(body.unavailableReason).toMatch(/^Requires the BootUI agent's code-paths sensor/)
      }
      // The seeded slow route answers without the agent too.
      expect((await page.request.get('/api/quotes/e2e')).ok()).toBeTruthy()

      await openView('code-paths', 'Code Paths')
      await expect(page.locator('.panel-availability-alert')).toContainText('code-paths sensor')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    for (let i = 0; i < 4; i++) {
      expect((await page.request.get(`/api/quotes/e2e-${i}`)).ok()).toBeTruthy()
    }
    // Each request's tree settles about two seconds after its last fragment.
    await expect
      .poll(
        async () => {
          const report = await (await page.request.get('/bootui/api/code-paths')).json()
          return report.routes?.find((route) => route.route === 'GET /api/quotes/{sku}')?.warmRequests ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(3)
    const report = await (await page.request.get('/bootui/api/code-paths')).json()
    const row = report.routes.find((route) => route.route === 'GET /api/quotes/{sku}')
    // A blocking resource method on a worker is timed as executed, not assembly only.
    expect(row.assemblyOnly).toBe(false)
    expect(row.topMethods[0].method).toBe(
      'io.github.jdubois.bootui.sample.codepaths.SlowPricingService#quote(Ljava/lang/String;)I'
    )

    await page.goto('/bootui/#/code-paths?route=GET%20%2Fapi%2Fquotes%2F%7Bsku%7D')
    await expect(page.locator('main h2').filter({hasText: /^Code Paths/})).toBeVisible()
    await expect(page.locator('#code-paths-headline')).toHaveText(/\d+ routes? with a call tree/)
    await expect(page.locator('#code-paths-tree-heading')).toHaveText('GET /api/quotes/{sku}')
    const tree = page.locator('.code-paths-tree')
    await expect(tree).toContainText('QuoteResource.quote')
    await expect(tree).toContainText('QuoteService.quote')
    await expect(tree).toContainText('SlowPricingService.quote')

    await tree.getByRole('button', {name: 'SlowPricingService.quote'}).click()
    const detail = page.locator('.code-paths-method-detail')
    await expect(detail.locator('.code-paths-callers')).toContainText('QuoteService.quote')
    await expect(detail.locator('.code-paths-reach')).toContainText('GET /api/quotes/{sku}')

    // M5-8: Probe this method advises the current dev-mode run's copy and records the next calls; asked for shapes
    // (D44), it records the argument's and the return value's types, which MCP never sees.
    await page.getByLabel('Record argument and return shapes').check()
    await page.getByRole('button', {name: 'Probe this method'}).click()
    const dialog = page.getByRole('dialog', {name: 'Probe this method?'})
    await dialog.getByRole('button', {name: 'Start probe'}).click()
    const probe = page.locator('.code-paths-probe').first()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('active', {timeout: 15_000})
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get(`/api/quotes/probed-${i}`)).ok()).toBeTruthy()
    }
    await expect(probe).toContainText(/[3-9] of 20 invocations/, {timeout: 15_000})
    const hit = page.locator('.code-paths-probe-hits tbody tr').first()
    await expect(hit).toContainText('QuoteService#quote')
    await expect(hit.locator('.code-paths-probe-arguments .code-paths-probe-shape')).toHaveText(['String'])
    await expect(page.locator('.code-paths-probe-withheld')).toContainText('bootui.expose-values=FULL')
    await expect(hit.locator('.code-paths-probe-shape').first()).toHaveText('int')
    await probe.getByRole('button', {name: 'Stop'}).click()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('ended', {timeout: 15_000})

    const listed = await (await page.request.get('/bootui/api/code-paths/probes')).json()
    const shaped = listed.probes.find((candidate) => candidate.recordShapes)
    expect(shaped.hits[0].arguments).toMatchObject([{kind: 'string', type: 'java.lang.String', size: null}])
    expect(shaped.hits[0].returned).toMatchObject({kind: 'primitive', declaredType: 'int'})
    const forAgents = await callMcpTool(page, 'get_method_probe', {id: shaped.id})
    expect(forAgents.shapesHiddenReason).toContain('never to MCP or the CLI')
    expect(forAgents.hits.every((recorded) => recorded.arguments.length === 0 && recorded.returned === null)).toBe(true)
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
