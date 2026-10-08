// @ts-check
import {assertSecuritySinksSwitch, expect, test} from './fixtures.js'

/**
 * The Side Effects view on Quarkus (docs/PLAN-v2.md §5.16, M5-5a). The default suite runs the sample without the BootUI
 * agent, so the panel is unavailable with the Java Agent panel's reason and links there, and its reads answer the
 * unavailable shape. The agent suite (playwright.agent.config.js) sets the `agentAttached` fixture option and asserts
 * the processes and resources sensors record, and that a sleep on the Vert.x event
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
    await expect
      .poll(
        async () =>
          (await (await page.request.get('/bootui/api/side-effects')).json()).sensors.find(
            (sensor) => sensor.id === 'resources'
          ).state,
        {timeout: 30_000}
      )
      .toBe('recording')

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
        {timeout: 45_000}
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

  test('shows request input reaching a sink as a fact, with the value redacted (M5-6b)', async ({
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'Security sinks need the BootUI agent and request-value matching')
    // Letters only, so a files pattern, which folds digits, could not hide a value that leaked.
    const letters = () =>
      Date.now()
        .toString(36)
        .replace(/[0-9]/g, (digit) => 'abcdefghij'[Number(digit)])
    const value = `seed${letters()}`
    const bound = `bound${letters()}`
    const sinkRows = async () =>
      (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=security-sinks&limit=500')).json()).rows ??
      []

    // The concatenated statement is a row with the value redacted; the bound one is none.
    for (const path of [`/api/sinks/search?name=${value}`, `/api/sinks/search-bound?name=${bound}`]) {
      expect((await page.request.get(path)).ok()).toBeTruthy()
    }
    await expect
      .poll(async () => (await sinkRows()).some((row) => row.kind === 'SQL text'), {timeout: 30_000})
      .toBe(true)
    const sql = (await sinkRows()).find((row) => row.kind === 'SQL text')
    expect(sql.target).toContain("'{name}'")
    expect(sql.parameter).toBe('name')
    expect(sql.location).toBe('inside a literal')
    expect(sql.detail).toContain('Check that it is bound as a parameter or escaped.')
    expect((await sinkRows()).some((row) => row.attribution?.includes('search-bound'))).toBe(false)

    // A file path and an outbound URL holding the value.
    expect((await page.request.get(`/api/sinks/reports/${value}`)).ok()).toBeTruthy()
    expect((await page.request.get(`/api/sinks/lookup?name=${value}`)).ok()).toBeTruthy()
    await expect
      .poll(async () => (await sinkRows()).filter((row) => ['file path', 'outbound URL'].includes(row.kind)).length, {
        timeout: 30_000
      })
      .toBeGreaterThanOrEqual(2)
    const rows = await sinkRows()
    expect(rows.find((row) => row.kind === 'file path').target).toContain('{name}')
    expect(rows.find((row) => row.kind === 'outbound URL').target).toMatch(/\?(.*&)?user/)
    for (const row of rows) {
      expect(row.detail).not.toMatch(/vulnerab|injection/i)
    }

    // No value reaches any Side Effects read, nor the bound query's statement.
    const everything = JSON.stringify([
      await (await page.request.get('/bootui/api/side-effects')).json(),
      ...(await Promise.all(
        ['processes', 'network', 'files', 'environment', 'security-sinks'].map(async (sensor) =>
          (await page.request.get(`/bootui/api/side-effects/sensor?sensor=${sensor}&limit=500`)).json()
        )
      ))
    ])
    expect(everything).not.toContain(value)
    expect(everything).not.toContain(bound)

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Security sinks/}).click()
    await expect(page.locator('main')).toContainText('Request input reached this')
  })

  test('shows the JDK checks as facts: a weak digest, an unfiltered read, a trust manager (M5-6b2)', async ({
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, "Security sinks' JDK checks need the BootUI agent")
    const checkRows = async () =>
      (
        (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=security-sinks&limit=500')).json())
          .rows ?? []
      ).filter((row) => row.callSite?.includes('SecurityCheckSeeds'))
    for (const path of [
      '/api/sinks/checks/digest',
      '/api/sinks/checks/deserialize',
      '/api/sinks/checks/trust-manager'
    ]) {
      expect((await page.request.get(path)).ok()).toBeTruthy()
    }
    await expect
      .poll(async () => new Set((await checkRows()).map((row) => row.kind)).size, {timeout: 30_000})
      .toBeGreaterThanOrEqual(3)
    const rows = await checkRows()
    const digest = rows.find((row) => row.kind === 'weak digest')
    expect(digest.target).toBe('MD5')
    expect(digest.origin).toBe('application')
    expect(digest.detail).toContain('Weak algorithm MD5 requested by application code')
    const read = rows.find((row) => row.kind === 'deserialization without a filter')
    expect(read.target).toContain('Cart')
    expect(read.count).toBe(1)
    expect(read.detail).toContain('java.util.ArrayList')
    const trust = rows.find((row) => row.kind === 'trust manager')
    expect(trust.target).toContain('DelegatingTrustManager')
    // The counterexamples: SHA-256, AES/GCM/NoPadding, and the filtered read show nothing.
    expect(rows.some((row) => ['SHA-256', 'AES/GCM/NoPadding'].includes(row.target))).toBe(false)
    expect(rows.filter((row) => row.kind === 'deserialization without a filter')).toHaveLength(1)
    for (const row of rows) {
      expect(row.detail).not.toMatch(/vulnerab|injection/i)
    }

    await page.goto('/bootui/#/side-effects')
    await page.getByRole('tab', {name: /Security sinks/}).click()
    await expect(page.locator('main')).toContainText('Deserialization without an ObjectInputFilter')
  })

  test('switches the security-sinks sensor off and on at run time, recording only while it is on (M5-14)', async ({
    page,
    openView,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'The security-sinks switch needs the BootUI agent')
    await assertSecuritySinksSwitch(page, openView)
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

    // An idle Vert.x event loop waits in epoll, never in LockSupport.park: a short idle adds no park. Rows accumulate
    // over the whole run, and earlier traffic can record real parks, as a contended ArC LazyValue or executor lock, so
    // only a park seen during the idle window counts; lastSeen is when the park happened, not when it was flushed.
    const idleStart = Date.now() + 100
    await page.waitForTimeout(2_000)
    const idle = await (await page.request.get('/bootui/api/side-effects/sensor?sensor=blocking')).json()
    expect(
      idle.rows.filter(
        (candidate) =>
          candidate.kind === 'park' && /eventloop/.test(candidate.target) && candidate.lastSeen >= idleStart
      )
    ).toEqual([])

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

  test('shows the thread local a request left set, never one cleared in finally, set to null, or set before', async ({
    openView,
    page,
    agentAttached
  }) => {
    test.skip(!agentAttached, 'the thread-locals sensor needs the BootUI agent')
    // The thread-locals seeds (M5-5f): a tenant on its pooled Quarkus worker left set; counterexamples cleared in finally, set to
    // null, and set by a filter before BootUI's scope; and a withInitial date format, reported with its initial value flagged.
    for (let round = 0; round < 3; round++) {
      for (const path of ['leak', 'cleared', 'nulled', 'cache', 'before']) {
        const response = await page.request.get(`/api/thread-locals/${path}`)
        expect(response.ok()).toBeTruthy()
        test.skip((await response.json()).virtual === true, 'virtual threads are not pooled: never scanned')
      }
    }
    const read = async () =>
      (await (await page.request.get('/bootui/api/side-effects/sensor?sensor=thread-locals&limit=500')).json()).rows ??
      []
    const holder = 'io.github.jdubois.bootui.sample.sideeffects.TenantContext.CURRENT'
    await expect
      .poll(
        async () =>
          (await read()).find((row) => row.attribution === 'GET /api/thread-locals/leak' && row.target === holder)
            ?.count ?? 0,
        {timeout: 30_000}
      )
      .toBeGreaterThan(0)
    // A pooled worker fills the date format at its first cache request: reported once per worker, flagged.
    const format = 'io.github.jdubois.bootui.sample.sideeffects.TenantContext.FORMAT'
    await expect
      .poll(
        async () =>
          (await read()).find((row) => row.attribution === 'GET /api/thread-locals/cache' && row.target === format)
            ?.kind,
        {timeout: 30_000}
      )
      .toBe('left set (with initial value)')
    const rows = await read()
    const leak = rows.find((row) => row.attribution === 'GET /api/thread-locals/leak' && row.target === holder)
    expect(leak.kind).toBe('left set')
    expect(leak.origin).toBe('application')
    expect(leak.callSite).toBeNull()
    expect(leak.requests).toBeGreaterThan(0)
    for (const path of ['cleared', 'nulled', 'before']) {
      expect(rows.filter((row) => row.attribution === `GET /api/thread-locals/${path}`)).toEqual([])
    }
    expect(rows.filter((row) => /RequestContextHolder|LocaleContextHolder|MDC/.test(row.target))).toEqual([])
    expect(JSON.stringify(rows)).not.toContain('tenant-secret')
    // The sample's ScheduledTenant, which this leg turns on, leaves its tenant set on the scheduler's worker every 20 s,
    // even after an earlier spec's Clear recording: a row of that run, named as the runtime journal names it, with no
    // request.
    const job = 'io.github.jdubois.bootui.sample.sideeffects.TenantContext.JOB'
    await expect
      .poll(
        async () =>
          (await read()).find(
            (row) => row.target === job && /^scheduled .*ScheduledTenant[#.]remember$/.test(row.attribution)
          )?.scope ?? null,
        {timeout: 45_000}
      )
      .toBe('execution')
    const scheduledLeak = (await read()).find((row) => row.target === job)
    expect(scheduledLeak.kind).toBe('left set')
    expect(scheduledLeak.exemplarRequestIds).toEqual([])
    expect(JSON.stringify(await read())).not.toContain('tenant-secret')

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Threads and leaks/}).click()
    await expect(
      page.locator('.side-effects-table tbody tr').filter({hasText: 'TenantContext.CURRENT'}).first()
    ).toContainText('set during the request')
  })

  test('shows a stream a request never closed, never one it closed nor a pooled connection as a leak', async ({
    openView,
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

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Threads and leaks/}).click()
    const row = page
      .locator('.side-effects-table tbody tr')
      .filter({hasText: 'GET /api/resources/leaked-stream'})
      .filter({hasText: 'file input stream'})
    await expect(row.first()).toContainText('Opened by the application')
    await expect(row.first().locator('.side-effects-reclaimed')).toBeVisible()
  })
})
