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
    expect(report.sensors.find((sensor) => sensor.id === 'network').state).toBe('not-available')

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

    await page.goto('/bootui/#/side-effects')
    await expect(page.locator('main h2').filter({hasText: /^Side Effects/})).toBeVisible()
    await page.getByRole('tab', {name: /Files and processes/}).click()
    await expect(page.locator('main')).toContainText('Processes the application starts')
  })

  test('reports a sleep on the event loop and never the same sleep on boundedElastic', async ({
    page,
    request,
    baseURL,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'the blocking sensor needs the BootUI agent')
    await expect
      .poll(
        async () =>
          (await (await request.get(`${baseURL}/bootui/api/side-effects`)).json()).sensors.find(
            (sensor) => sensor.id === 'blocking'
          ).state,
        {timeout: 30_000}
      )
      .toBe('recording')

    const seed = await (await request.get(`${baseURL}/api/side-effects/event-loop-sleep`)).json()
    expect(seed.thread).toMatch(/^reactor-http-/)
    const counterexample = await (await request.get(`${baseURL}/api/side-effects/worker-sleep`)).json()
    expect(counterexample.thread).toMatch(/^boundedElastic-/)

    const seedRoute = 'GET /api/side-effects/event-loop-sleep'
    await expect
      .poll(
        async () => {
          const rows = (await (await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=blocking`)).json())
            .rows
          return rows?.find((row) => row.attribution === seedRoute && row.kind === 'sleep')?.count ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(1)
    const report = await (await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=blocking`)).json()
    const row = report.rows.find((candidate) => candidate.attribution === seedRoute && candidate.kind === 'sleep')
    expect(row.target).toMatch(/^reactor-http-[a-z]+-\{n\}$/)
    expect(row.callSite).toMatch(/EventLoopSleeper#sleepOnEventLoop$/)
    expect(row.maxMillis).toBeGreaterThanOrEqual(40)
    expect(report.rows.some((candidate) => candidate.attribution === 'GET /api/side-effects/worker-sleep')).toBe(false)
    expect(report.rows.some((candidate) => /boundedElastic/.test(candidate.target))).toBe(false)

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Blocking/}).click()
    await expect(page.locator('.side-effects-table')).toContainText('EventLoopSleeper#sleepOnEventLoop')
  })
})
