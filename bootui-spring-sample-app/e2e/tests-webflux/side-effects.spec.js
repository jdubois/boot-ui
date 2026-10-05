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

    const GET = (path) => request.get(`${baseURL}${path}`)
    // The files and environment seeds (M5-5d): a report written outside the temporary directory and a property read
    // during the request, with a temporary file, a logging handler's file, and class loading as counterexamples.
    for (const path of ['report', 'scratch', 'log']) {
      expect((await GET(`/api/side-effects/${path}`)).ok()).toBeTruthy()
    }
    const reportRoute = 'GET /api/side-effects/report'
    const reportRow = (rows) =>
      rows?.find(
        (candidate) =>
          candidate.attribution === reportRoute &&
          candidate.target.endsWith('/bootui-side-effects/report-{n}-{n}-{n}.csv')
      )
    await expect
      .poll(
        async () =>
          reportRow((await (await GET('/bootui/api/side-effects/sensor?sensor=files&limit=500')).json()).rows)?.kind,
        {
          timeout: 30_000
        }
      )
      .toBe('write')
    await expect
      .poll(
        async () =>
          (await (await GET('/bootui/api/side-effects/sensor?sensor=environment&limit=500')).json()).rows?.find(
            (candidate) => candidate.attribution === reportRoute && candidate.target === 'sample.report.title'
          )?.kind,
        {timeout: 30_000}
      )
      .toBe('system property')
    const files = await (await GET('/bootui/api/side-effects/sensor?sensor=files&limit=500')).json()
    const environment = await (await GET('/bootui/api/side-effects/sensor?sensor=environment&limit=500')).json()
    const written = reportRow(files.rows)
    expect(written.origin).toBe('application')
    expect(written.location).not.toBe('temporary-directory')
    expect(written.callSite).toMatch(/ReportWriter#writeReport$/)
    const scratch = files.rows.filter((candidate) => candidate.target.includes('bootui-scratch-'))
    expect(scratch.length).toBeGreaterThan(0)
    for (const candidate of scratch) {
      expect(candidate.target.startsWith('$TMPDIR/')).toBe(true)
      expect(candidate.location).toBe('temporary-directory')
    }
    const logged = files.rows.filter((candidate) => candidate.target.includes('bootui-sample-'))
    expect(logged.length).toBeGreaterThan(0)
    for (const candidate of logged) expect(candidate.origin).toBe('logging')
    expect(files.rows.some((candidate) => /\.(class|jar)$/.test(candidate.target))).toBe(false)
    expect(JSON.stringify(files) + JSON.stringify(environment)).not.toContain(
      'sample-side-effects-contents-never-shown'
    )

    await page.goto('/bootui/#/side-effects')
    await expect(page.locator('main h2').filter({hasText: /^Side Effects/})).toBeVisible()
    await page.getByRole('tab', {name: /Files and processes/}).click()
    await expect(page.locator('main')).toContainText('Processes the application starts')
  })
})
