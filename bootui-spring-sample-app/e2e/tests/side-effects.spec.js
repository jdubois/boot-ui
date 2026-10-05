// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Side Effects view (docs/PLAN-v2.md §5.16, M5-5a). The default suites run the sample without the agent, so the
 * panel is unavailable with the Java Agent panel's reason and links there; the agent suite (playwright.agent.config.js)
 * sets the `agentAttached` fixture option and asserts the seeded route's `java` process shows by its file name only,
 * and the counterexample's route not at all; and (M5-5b) that an SDK's own socket is a Network row not captured by any
 * panel, while a call through the recorded REST client never is; and (M5-5c) the blocking sensor not applicable, Spring
 * MVC running no event loop.
 */
test.describe('Side Effects view', () => {
  test('shows the seeded process by its file name only, or says why it cannot', async ({
    openView,
    page,
    agentAttached
  }) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'side-effects')
    expect(panel).toBeTruthy()

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
      const unknown = await page.request.get('/bootui/api/side-effects/sensor?sensor=not-a-sensor')
      expect(unknown.status()).toBe(400)

      await openView('side-effects', 'Side Effects')
      await expect(page.locator('.panel-availability-alert')).toContainText('Requires the BootUI agent')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).first().click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    expect((await page.request.get('/api/side-effects/java-version')).ok()).toBeTruthy()
    expect((await page.request.get('/api/side-effects/runtime-version')).ok()).toBeTruthy()
    const seed = 'GET /api/side-effects/java-version'
    await expect
      .poll(
        async () => {
          const report = await (await page.request.get('/bootui/api/side-effects/sensor?sensor=processes')).json()
          return report.rows?.find((row) => row.attribution === seed)?.completed ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(1)
    const report = await (await page.request.get('/bootui/api/side-effects/sensor?sensor=processes')).json()
    expect(JSON.stringify(report)).not.toContain('never-shown-by-bootui')
    expect(report.rows.some((row) => row.attribution === 'GET /api/side-effects/runtime-version')).toBe(false)

    // M5-5b: an SDK's own socket is a Network row not captured by any panel; the recorded REST client's connection
    // never is.
    expect((await page.request.get('/api/side-effects/sdk-call')).ok()).toBeTruthy()
    expect((await page.request.get('/api/side-effects/rest-call')).ok()).toBeTruthy()
    const sdk = 'GET /api/side-effects/sdk-call'
    const restCall = 'GET /api/side-effects/rest-call'
    const networkRows = async () =>
      (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=network&limit=500')).json()).rows ?? []
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
    const networkRow = page.locator('.side-effects-table tbody tr').filter({hasText: sdk}).filter({hasText: 'connect'})
    await expect(networkRow.first()).toContainText('Not captured by any panel')
    await expect(networkRow.first()).toContainText('LicenseSdkClient#check')
    await page.getByRole('tab', {name: /Files and processes/}).click()
    const table = page
      .locator('#side-effects-sensor-processes')
      .locator('xpath=ancestor::section[1]')
      .locator('.side-effects-table')
    const row = table.locator('tbody tr').filter({hasText: seed})
    await expect(row).toContainText('java')
    await expect(row).toContainText('JavaVersionReporter#version')
    await expect(table).not.toContainText('never-shown-by-bootui')
    const filesSection = page.locator('#side-effects-sensor-files').locator('xpath=ancestor::section[1]')
    const reportTableRow = filesSection
      .locator('.side-effects-table')
      .first()
      .locator('tbody tr')
      .filter({hasText: reportRoute})
    await expect(reportTableRow.first()).toContainText('report-{n}-{n}-{n}.csv')
    await expect(reportTableRow.first()).toContainText('Application')
    await expect(filesSection.locator('details.side-effects-apart summary')).toContainText(
      'Class path, JDK, and logging'
    )
    await page.getByRole('tab', {name: /Environment/}).click()
    await expect(page.locator('main')).toContainText('sample.report.title')
  })

  test('says the blocking sensor is not applicable on Spring MVC, which runs no event loop', async ({
    openView,
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'the blocking sensor needs the BootUI agent')
    await expect
      .poll(
        async () =>
          (await (await page.request.get('/bootui/api/side-effects')).json()).sensors.find(
            (sensor) => sensor.id === 'blocking'
          ).state,
        {timeout: 30_000}
      )
      .toBe('not-applicable')
    const report = await (await page.request.get('/bootui/api/side-effects')).json()
    const blocking = report.sensors.find((sensor) => sensor.id === 'blocking')
    expect(blocking.reason).toContain('Spring MVC')
    expect(blocking.hooks.map((hook) => hook.id)).toEqual(
      expect.arrayContaining(['LockSupport.park', 'Thread.sleep call sites', 'Object.wait call sites'])
    )
    const rows = await (await page.request.get('/bootui/api/side-effects/sensor?sensor=blocking')).json()
    expect(rows.rows).toEqual([])

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Blocking/}).click()
    await expect(page.locator('.side-effects-state')).toHaveText('Not applicable')
    await expect(page.locator('.side-effects-state-note')).toContainText('no event loop to block')
  })

  test('shows the threads and executors a request left running, never those it joined or shut down', async ({
    openView,
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'the thread-activity sensor needs the BootUI agent')
    // The thread-activity seeds (M5-5e): a thread a request leaves running and an executor it never shuts down, with a
    // thread joined and an executor shut down in finally as counterexamples, and the server's own pools never the
    // application's.
    for (const path of ['left-running', 'joined', 'own-pool', 'closed-pool']) {
      expect((await page.request.get(`/api/thread-activity/${path}`)).ok()).toBeTruthy()
    }
    const rows = async () =>
      (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=thread-activity&limit=500')).json())
        .rows ?? []
    const find = (list, path, kind) =>
      list.find(
        (candidate) =>
          candidate.attribution === `GET /api/thread-activity/${path}` &&
          candidate.kind === kind &&
          candidate.origin === 'application'
      )
    await expect
      .poll(async () => find(await rows(), 'left-running', 'thread')?.leftRunning ?? 0, {timeout: 30_000})
      .toBeGreaterThan(0)
    await expect
      .poll(async () => find(await rows(), 'own-pool', 'executor')?.leftRunning ?? 0, {timeout: 30_000})
      .toBeGreaterThan(0)
    await expect
      .poll(async () => find(await rows(), 'closed-pool', 'executor')?.completed ?? 0, {timeout: 30_000})
      .toBeGreaterThan(0)
    const all = await rows()
    const left = find(all, 'left-running', 'thread')
    expect(left.target).toBe('report-refresher-{n}')
    expect(left.callSite).toMatch(/BackgroundWork#startRefresher$/)
    expect(left.requests).toBeGreaterThan(0)
    expect(find(all, 'own-pool', 'executor').target).toBe('java.util.concurrent.ThreadPoolExecutor')
    const joined = find(all, 'joined', 'thread')
    expect(joined).toBeTruthy()
    expect(joined.leftRunning).toBe(0)
    expect(find(all, 'closed-pool', 'executor').leftRunning).toBe(0)
    for (const candidate of all) {
      if (candidate.origin !== 'application') expect(candidate.leftRunning).toBe(0)
    }

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Threads and leaks/}).click()
    const row = page.locator('.side-effects-table tbody tr').filter({hasText: 'report-refresher-{n}'})
    await expect(row.first()).toContainText('BackgroundWork#startRefresher')
    await expect(row.first().locator('.side-effects-left-running')).toBeVisible()
  })
})
