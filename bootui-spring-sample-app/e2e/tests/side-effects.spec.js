// @ts-check
import {expect, switchAgentSensor, test} from './fixtures.js'

/**
 * The Side Effects view (docs/PLAN-v2.md §5.16, M5-5a). The default suites run the sample without the agent, so the
 * panel is unavailable with the Java Agent panel's reason and links there; the agent suite (playwright.agent.config.js)
 * sets the `agentAttached` fixture option and asserts the seeded route's `java` process shows by its file name only,
 * and the counterexample's route not at all; and (M5-5b) that an SDK's own socket is a Network row not captured by any
 * panel, while a call through the recorded REST client never is.
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
    // The opt-in environment sensor, switched on at run time when the suite does not configure it (M5-14), and put
    // back afterwards.
    const environmentWasOn = await switchAgentSensor(page.request, 'environment', true)
    try {
      await assertSeededSideEffects(openView, page)
    } finally {
      if (!environmentWasOn) {
        await switchAgentSensor(page.request, 'environment', false)
      }
    }
  })
})

/**
 * The seeded side effects with the agent attached.
 *
 * @param {(route: string, heading: string) => Promise<import('@playwright/test').Page>} openView
 * @param {import('@playwright/test').Page} page
 */
async function assertSeededSideEffects(openView, page) {
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
  expect(JSON.stringify(files) + JSON.stringify(environment)).not.toContain('sample-side-effects-contents-never-shown')

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
  await expect(filesSection.locator('details.side-effects-apart summary')).toContainText('Class path, JDK, and logging')
  await page.getByRole('tab', {name: /Environment/}).click()
  await expect(page.locator('main')).toContainText('sample.report.title')
}
