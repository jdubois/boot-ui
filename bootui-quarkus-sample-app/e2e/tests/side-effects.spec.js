// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Side Effects view on Quarkus (docs/PLAN-v2.md §5.16, M5-5a). The default suite runs the sample without the BootUI
 * agent, so the panel is unavailable with the Java Agent panel's reason and links there, and its reads answer the
 * unavailable shape. The agent suite (playwright.agent.config.js) sets the `agentAttached` fixture option and asserts
 * the processes sensor records while the sensors this version does not ship say so.
 */
test.describe('Side Effects view (Quarkus)', () => {
  test('lists every sensor with its coverage, or says why it cannot', async ({openView, page, agentAttached}) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'side-effects')

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent/)
      for (const path of ['', '/sensor?sensor=processes']) {
        const response = await page.request.get(`/bootui/api/side-effects${path}`)
        expect(response.ok()).toBeTruthy()
        const body = await response.json()
        expect(body.available).toBe(false)
        expect(body.unavailableReason).toMatch(/^Requires the BootUI agent/)
      }
      expect((await page.request.get('/bootui/api/side-effects/sensor?sensor=nope')).status()).toBe(400)

      await openView('side-effects', 'Side Effects')
      await expect(page.locator('.panel-availability-alert')).toContainText('Requires the BootUI agent')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).first().click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    await expect
      .poll(
        async () =>
          (await (await page.request.get('/bootui/api/side-effects')).json()).sensors.find(
            (sensor) => sensor.id === 'processes'
          ).state,
        {timeout: 30_000}
      )
      .toBe('recording')
    const report = await (await page.request.get('/bootui/api/side-effects')).json()
    expect(report.sensors.find((sensor) => sensor.id === 'blocking').reason).toBe('Not available in this version.')

    expect((await page.request.get(`/api/side-effects/java-version`)).ok()).toBeTruthy()
    expect((await page.request.get(`/api/side-effects/runtime-version`)).ok()).toBeTruthy()
    const seed = 'GET /api/side-effects/java-version'
    await expect
      .poll(
        async () => {
          const rows = (await (await page.request.get(`/bootui/api/side-effects/sensor?sensor=processes`)).json()).rows
          return rows?.find((row) => row.attribution === seed && row.target === 'java')?.completed ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(1)
    const processRows = await (await page.request.get(`/bootui/api/side-effects/sensor?sensor=processes`)).json()
    const row = processRows.rows.find((candidate) => candidate.attribution === seed)
    expect(row.callSite).toMatch(/JavaVersionReporter#version$/)
    expect(JSON.stringify(processRows)).not.toContain('never-shown-by-bootui')
    expect(
      processRows.rows.some((candidate) => candidate.attribution === 'GET /api/side-effects/runtime-version')
    ).toBe(false)

    // The sample's ScheduledJavaVersion, which this leg turns on (playwright.agent.config.js), starts the same process
    // from a scheduled run every 20 s: a row of that run, named as the runtime journal names it, with no request.
    const scheduled = /^scheduled .*ScheduledJavaVersion[#.]report$/
    await expect
      .poll(
        async () => {
          const rows = (await (await page.request.get(`/bootui/api/side-effects/sensor?sensor=processes`)).json()).rows
          return rows?.find((candidate) => scheduled.test(candidate.attribution))?.scope ?? null
        },
        {timeout: 60_000}
      )
      .toBe('execution')
    const scheduledRows = await (await page.request.get(`/bootui/api/side-effects/sensor?sensor=processes`)).json()
    const scheduledRow = scheduledRows.rows.find((candidate) => scheduled.test(candidate.attribution))
    expect(scheduledRow.target).toBe('java')
    expect(scheduledRow.callSite).toMatch(/JavaVersionReporter#version$/)
    expect(scheduledRow.exemplarRequestIds).toEqual([])
    expect(JSON.stringify(scheduledRows)).not.toContain('never-shown-by-bootui')

    const GET = (path) => page.request.get(path)
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

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Files and processes/}).click()
    await expect(page.locator('main')).toContainText('Processes the application starts')
  })
})
