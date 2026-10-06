// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Side Effects view on Quarkus (docs/PLAN-v2.md §5.16, M5-5a). The default suite runs the sample without the BootUI
 * agent, so the panel is unavailable with the Java Agent panel's reason and links there, and its reads answer the
 * unavailable shape. The agent suite (playwright.agent.config.js) sets the `agentAttached` fixture option and asserts
 * the processes sensor records while the sensors this version does not ship say so, and that a sleep on the Vert.x event
 * loop is a blocking row while the same sleep on a worker is not.
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
    expect(report.sensors.find((sensor) => sensor.id === 'resources').reason).toBe('Not available in this version.')

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
    await expect(
      page.locator('.side-effects-table tbody tr').filter({hasText: sdk}).filter({hasText: 'connect'}).first()
    ).toContainText('Not captured by any panel')
    await page.getByRole('tab', {name: /Files and processes/}).click()
    await expect(page.locator('main')).toContainText('Processes the application starts')
  })

  test('reports a sleep on the Vert.x event loop and never the same sleep on a worker', async ({
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
      .toBe('recording')

    const seed = await (await page.request.get('/api/side-effects/event-loop-sleep')).json()
    expect(seed.thread).toMatch(/^vert\.x-eventloop-thread-/)
    const counterexample = await (await page.request.get('/api/side-effects/worker-sleep')).json()
    expect(counterexample.thread).not.toMatch(/eventloop/)

    const seedRoute = 'GET /api/side-effects/event-loop-sleep'
    await expect
      .poll(
        async () => {
          const rows = (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=blocking')).json()).rows
          return rows?.find((row) => row.attribution === seedRoute && row.kind === 'sleep')?.count ?? 0
        },
        {timeout: 30_000}
      )
      .toBeGreaterThanOrEqual(1)
    const report = await (await page.request.get('/bootui/api/side-effects/sensor?sensor=blocking')).json()
    const row = report.rows.find((candidate) => candidate.attribution === seedRoute && candidate.kind === 'sleep')
    expect(row.target).toBe('vert.x-eventloop-thread-{n}')
    expect(row.callSite).toMatch(/EventLoopSleeper#sleepOnEventLoop$/)
    expect(row.maxMillis).toBeGreaterThanOrEqual(40)
    expect(report.rows.some((candidate) => candidate.attribution === 'GET /api/side-effects/worker-sleep')).toBe(false)

    // An idle Vert.x event loop waits in epoll, never in LockSupport.park: traffic and a short idle add no park row.
    await page.waitForTimeout(2_000)
    const idle = await (await page.request.get('/bootui/api/side-effects/sensor?sensor=blocking')).json()
    expect(idle.rows.filter((candidate) => candidate.kind === 'park' && /eventloop/.test(candidate.target))).toEqual([])

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Blocking/}).click()
    await expect(page.locator('.side-effects-table')).toContainText('EventLoopSleeper#sleepOnEventLoop')
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
