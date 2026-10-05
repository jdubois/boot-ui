// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * The Side Effects view on Spring WebFlux (docs/PLAN-v2.md §5.16, M5-5a). Without the BootUI agent the panel is
 * unavailable with the Java Agent panel's reason, and its reads answer the unavailable shape. With it (the
 * `agentAttached` fixture option of playwright.webflux-agent.config.js), the processes sensor records and the sensors
 * this version does not ship say so.
 */
test.describe('Side Effects view on Spring WebFlux', () => {
  test('lists every sensor with its coverage, or says why it cannot', async ({
    page,
    request,
    baseURL,
    agentAttached
  }) => {
    const panels = await (await request.get(`${baseURL}/bootui/api/panels`)).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'side-effects')

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent/)
      for (const path of ['', '/sensor?sensor=processes']) {
        const body = await (await request.get(`${baseURL}/bootui/api/side-effects${path}`)).json()
        expect(body.available).toBe(false)
      }
      expect((await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=nope`)).status()).toBe(400)

      await page.goto('/bootui/#/side-effects')
      await expect(page.locator('main h2').filter({hasText: /^Side Effects/})).toBeVisible()
      await expect(page.locator('.panel-availability-alert')).toContainText('Requires the BootUI agent')
      return
    }

    expect(panel.available).toBe(true)
    const report = await (await request.get(`${baseURL}/bootui/api/side-effects`)).json()
    const processes = report.sensors.find((sensor) => sensor.id === 'processes')
    expect(processes.hooks.map((hook) => hook.id)).toContain('ProcessBuilder.start')
    await expect
      .poll(
        async () =>
          (await (await request.get(`${baseURL}/bootui/api/side-effects`)).json()).sensors.find(
            (sensor) => sensor.id === 'processes'
          ).state,
        {timeout: 30_000}
      )
      .toBe('recording')
    await expect
      .poll(
        async () =>
          (await (await request.get(`${baseURL}/bootui/api/side-effects`)).json()).sensors.find(
            (sensor) => sensor.id === 'network'
          ).state,
        {timeout: 30_000}
      )
      .toBe('recording')
    const network = (await (await request.get(`${baseURL}/bootui/api/side-effects`)).json()).sensors.find(
      (sensor) => sensor.id === 'network'
    )
    expect(network.hooks.map((hook) => hook.id)).toContain('Socket.connect')

    expect((await request.get(`${baseURL}/api/side-effects/java-version`)).ok()).toBeTruthy()
    expect((await request.get(`${baseURL}/api/side-effects/runtime-version`)).ok()).toBeTruthy()
    const seed = 'GET /api/side-effects/java-version'
    await expect
      .poll(
        async () => {
          const rows = (await (await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=processes`)).json())
            .rows
          return rows?.find((row) => row.attribution === seed && row.target === 'java')?.completed ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(1)
    const processRows = await (await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=processes`)).json()
    const row = processRows.rows.find((candidate) => candidate.attribution === seed)
    expect(row.callSite).toMatch(/JavaVersionReporter#version$/)
    expect(JSON.stringify(processRows)).not.toContain('never-shown-by-bootui')
    expect(
      processRows.rows.some((candidate) => candidate.attribution === 'GET /api/side-effects/runtime-version')
    ).toBe(false)

    // M5-5b: an SDK's own socket is a Network row not captured by any panel; the recorded REST client's connection
    // never is.
    expect((await request.get(`${baseURL}/api/side-effects/sdk-call`)).ok()).toBeTruthy()
    expect((await request.get(`${baseURL}/api/side-effects/rest-call`)).ok()).toBeTruthy()
    const sdk = 'GET /api/side-effects/sdk-call'
    const restCall = 'GET /api/side-effects/rest-call'
    const networkRows = async () =>
      (await (await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=network&limit=500`)).json()).rows ??
      []
    await expect
      .poll(
        async () =>
          (await networkRows()).find((candidate) => candidate.attribution === sdk && candidate.kind === 'connect')
            ?.capture ?? null,
        {timeout: 30_000}
      )
      .toBe('not-captured')
    const hidden = (await networkRows()).find(
      (candidate) => candidate.attribution === sdk && candidate.kind === 'connect'
    )
    expect(hidden.target).toMatch(/^localhost:\d+$/)
    expect(hidden.callSite).toMatch(/LicenseSdkClient#check$/)
    expect(hidden.count).toBeGreaterThanOrEqual(1)
    // The REST call's connect, when it opened one, waits a little longer for its call to be recorded.
    await new Promise((resolve) => setTimeout(resolve, 3_000))
    const rows = await networkRows()
    expect(JSON.stringify(rows)).not.toContain('never-shown-by-bootui')
    expect(
      rows.filter((candidate) => candidate.attribution === restCall && candidate.capture === 'not-captured')
    ).toEqual([])

    await page.goto('/bootui/#/side-effects')
    await expect(page.locator('main h2').filter({hasText: /^Side Effects/})).toBeVisible()
    await expect(
      page.locator('.side-effects-table tbody tr').filter({hasText: sdk}).filter({hasText: 'connect'}).first()
    ).toContainText('Not captured by any panel')
    await page.getByRole('tab', {name: /Files and processes/}).click()
    await expect(page.locator('main')).toContainText('Processes the application starts')
  })
})
