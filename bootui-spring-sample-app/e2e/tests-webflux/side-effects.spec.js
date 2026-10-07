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
    await expect(
      page.locator('.side-effects-table tbody tr').filter({hasText: sdk}).filter({hasText: 'connect'}).first()
    ).toContainText('Not captured by any panel')
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
    expect(counterexample.thread).toMatch(/boundedElastic-/i)

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

    // An idle Netty event loop waits in epoll, never in LockSupport.park: a short idle adds no park. Rows accumulate
    // over the whole run, and earlier traffic can record real parks on a contended lock, so only a park seen during the
    // idle window counts; lastSeen is when the park happened, not when it was flushed.
    const idleStart = Date.now() + 100
    await page.waitForTimeout(2_000)
    const idle = await (await request.get(`${baseURL}/bootui/api/side-effects/sensor?sensor=blocking`)).json()
    expect(
      idle.rows.filter(
        (candidate) =>
          candidate.kind === 'park' && /^reactor-http-/.test(candidate.target) && candidate.lastSeen >= idleStart
      )
    ).toEqual([])

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Blocking/}).click()
    await expect(page.locator('.side-effects-table')).toContainText('EventLoopSleeper#sleepOnEventLoop')
  })

  test('shows the threads and executors a request left running, never those it joined or shut down', async ({
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

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Threads and leaks/}).click()
    const row = page.locator('.side-effects-table tbody tr').filter({hasText: 'report-refresher-{n}'})
    await expect(row.first()).toContainText('BackgroundWork#startRefresher')
    await expect(row.first().locator('.side-effects-left-running')).toBeVisible()
  })

  test('shows the thread local a request left set, never one cleared in finally, set to null, or set before', async ({
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'the thread-locals sensor needs the BootUI agent')
    // The thread-locals seeds (M5-5f): a tenant on a pooled boundedElastic worker left set; counterexamples cleared in finally, set to
    // null; and a withInitial date format, reported with its initial value flagged.
    for (let round = 0; round < 3; round++) {
      for (const path of ['leak', 'cleared', 'nulled', 'cache']) {
        const response = await page.request.get(`/api/thread-locals/${path}`)
        expect(response.ok()).toBeTruthy()
        test.skip((await response.json()).virtual === true, 'virtual threads are not pooled: never scanned')
      }
    }
    const read = async () =>
      (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=thread-locals&limit=500')).json()).rows ??
      []
    const holder = 'io.github.jdubois.bootui.webfluxsample.sideeffects.TenantContext.CURRENT'
    await expect
      .poll(
        async () =>
          (await read()).find((row) => row.attribution === 'GET /api/thread-locals/leak' && row.target === holder)
            ?.count ?? 0,
        {timeout: 30_000}
      )
      .toBeGreaterThan(0)
    const rows = await read()
    const leak = rows.find((row) => row.attribution === 'GET /api/thread-locals/leak' && row.target === holder)
    expect(leak.kind).toBe('left set')
    expect(leak.origin).toBe('application')
    expect(leak.callSite).toBeNull()
    expect(leak.requests).toBeGreaterThan(0)
    for (const path of ['cleared', 'nulled']) {
      expect(rows.filter((row) => row.attribution === `GET /api/thread-locals/${path}`)).toEqual([])
    }
    const cache = rows.find(
      (row) => row.target === 'io.github.jdubois.bootui.webfluxsample.sideeffects.TenantContext.FORMAT'
    )
    if (cache) expect(cache.kind).toBe('left set (with initial value)')
    expect(rows.filter((row) => /RequestContextHolder|LocaleContextHolder|MDC/.test(row.target))).toEqual([])
    expect(JSON.stringify(rows)).not.toContain('tenant-secret')

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Threads and leaks/}).click()
    await expect(
      page.locator('.side-effects-table tbody tr').filter({hasText: 'TenantContext.CURRENT'}).first()
    ).toContainText('set during the request')
  })

  test('shows a stream a request never closed, never one it closed nor a pooled connection as a leak', async ({
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'the resources sensor needs the BootUI agent')
    // The resources seeds (M5-5g): a FileInputStream dropped without close(), reclaimed by the collector, with a stream
    // closed in try-with-resources and the JDK HttpClient's pooled connection, handed off, as counterexamples.
    for (const path of ['leaked-stream', 'closed-stream', 'pooled-client']) {
      expect((await page.request.get(`/api/resources/${path}`)).ok()).toBeTruthy()
    }
    const rows = async () =>
      (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=resources&limit=500')).json()).rows ?? []
    const seeded = (list, path) => list.filter((candidate) => candidate.attribution === `GET /api/resources/${path}`)
    await expect
      .poll(
        async () => {
          await page.request.get('/api/resources/collect')
          return seeded(await rows(), 'leaked-stream').find((candidate) => candidate.failed > 0)?.failed ?? 0
        },
        {timeout: 60_000}
      )
      .toBeGreaterThan(0)
    const all = await rows()
    const leaked = seeded(all, 'leaked-stream').find((candidate) => candidate.failed > 0)
    expect(leaked.kind).toBe('file input stream')
    expect(leaked.origin).toBe('application')
    expect(leaked.target).toMatch(/\$TMPDIR\/bootui-resource-/)
    expect(leaked.callSite).toMatch(/ResourceSeeds#leakStream$/)
    expect(seeded(all, 'closed-stream')).toEqual([])
    // The JDK HttpClient connects on the request's thread and pools the connection: tracked, handed off, never a leak.
    await expect
      .poll(async () => seeded(await rows(), 'pooled-client').some((candidate) => candidate.leftRunning > 0), {
        timeout: 30_000
      })
      .toBe(true)
    for (const pooled of seeded(await rows(), 'pooled-client')) {
      expect(pooled.failed).toBe(0)
      expect(pooled.origin).toBe('library')
      expect(pooled.kind).toMatch(/^socket/)
    }

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Threads and leaks/}).click()
    const row = page
      .locator('.side-effects-table tbody tr')
      .filter({hasText: 'GET /api/resources/leaked-stream'})
      .filter({hasText: 'file input stream'})
    await expect(row.first()).toContainText('Opened by the application')
    await expect(row.first().locator('.side-effects-reclaimed')).toBeVisible()
  })
})
