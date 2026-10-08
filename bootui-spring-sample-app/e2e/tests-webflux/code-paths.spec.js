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
      for (const path of ['', '/route?route=GET%20%2F', '/requests/0000000000000000', '/beans', '/probes']) {
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

    // M5-8: a probe on the handler records its next calls, and says it times the Mono's assembly only; asked for
    // shapes (D44), it names the argument's and the returned Mono's types, and MCP never sees them.
    await page
      .locator('.code-paths-tree')
      .locator('.code-paths-node')
      .filter({hasText: 'GreetingController.greet'})
      .click()
    await page.getByLabel('Record argument and return shapes').check()
    await page.getByRole('button', {name: 'Probe this method'}).click()
    await page.getByRole('dialog', {name: 'Probe this method?'}).getByRole('button', {name: 'Start probe'}).click()
    const probe = page.locator('.code-paths-probe').first()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('active', {timeout: 15_000})
    await expect(probe).toContainText('assembly only')
    for (let i = 0; i < 3; i++) {
      expect((await request.get(`${baseURL}/api/greetings/probed-${i}`)).ok()).toBeTruthy()
    }
    await expect(probe).toContainText(/[3-9] of 20 invocations/, {timeout: 15_000})
    await expect(
      page
        .locator('.code-paths-probe-hits tbody tr')
        .first()
        .locator('.code-paths-probe-arguments .code-paths-probe-shape')
    ).toHaveText(['String'])
    await probe.getByRole('button', {name: 'Stop'}).click()
    await expect(probe.locator('.code-paths-probe-state')).toHaveText('ended', {timeout: 15_000})

    const listed = await (await request.get(`${baseURL}/bootui/api/code-paths/probes`)).json()
    const shaped = listed.probes.find((candidate) => candidate.recordShapes)
    expect(shaped.hits[0].arguments).toMatchObject([{kind: 'string', type: 'java.lang.String', size: null}])
    expect(shaped.hits[0].returned).toMatchObject({kind: 'type', declaredType: 'reactor.core.publisher.Mono'})
    expect(shaped.hits[0].returned.type).toMatch(/^reactor\.core\.publisher\.Mono/)
    const forAgents = await callMcpTool(page, 'get_method_probe', {id: shaped.id})
    expect(forAgents.shapesHiddenReason).toContain('never to MCP or the CLI')
    expect(forAgents.hits.every((hit) => hit.arguments.length === 0 && hit.returned === null)).toBe(true)
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
