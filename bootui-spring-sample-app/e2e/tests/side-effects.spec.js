// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Side Effects view (docs/PLAN-v2.md §5.16, M5-5a). The default suites run the sample without the agent, so the
 * panel is unavailable with the Java Agent panel's reason and links there; the agent suite (playwright.agent.config.js)
 * sets the `agentAttached` fixture option and asserts the seeded route's `java` process shows by its file name only,
 * and the counterexample's route not at all, and the blocking sensor not applicable, Spring MVC running no event loop.
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

    await openView('side-effects', 'Side Effects')
    await page.getByRole('tab', {name: /Files and processes/}).click()
    const table = page.locator('.side-effects-table')
    const row = table.locator('tbody tr').filter({hasText: seed})
    await expect(row).toContainText('java')
    await expect(row).toContainText('JavaVersionReporter#version')
    await expect(table).not.toContainText('never-shown-by-bootui')
    await page.getByRole('tab', {name: /Network/}).click()
    await expect(page.locator('main')).toContainText('Not available in this version.')
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
})
