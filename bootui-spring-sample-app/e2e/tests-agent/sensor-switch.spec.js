// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * Switching an opt-in agent sensor at run time (docs/PLAN-v2.md M5-14), in the Spring MVC sample with the BootUI agent
 * attached and `environment` left out of `bootui.agent.sensors`: the Side Effects panel switches it on without a
 * restart, a request's property read then shows as a row, the Java Agent panel marks the switch overridden against the
 * configured default, and switching it off from there stops it again.
 */
test.describe('Opt-in sensor switch, attached', () => {
  test('switches the environment sensor on from Side Effects, records a read, and switches it off', async ({
    openView,
    page
  }) => {
    const toggleOf = async () =>
      ((await (await page.request.get('/bootui/api/java-agent')).json()).toggles ?? []).find(
        (candidate) => candidate.id === 'environment'
      )
    const configured = await toggleOf()
    expect(configured).toMatchObject({configured: false, enabled: false, overridden: false, state: 'off'})

    try {
      await openView('side-effects', 'Side Effects')
      await page.getByRole('tab', {name: /Environment/}).click()
      const control = page.getByTestId('agent-sensor-toggle-environment')
      await expect(control).toContainText('System.getProperty')
      await expect(page.locator('.side-effects-state-note')).toContainText('switch it on above for this JVM')
      const toggle = control.getByRole('switch', {name: /environment/})
      await expect(toggle).not.toBeChecked()
      await toggle.click()
      await expect(toggle).toBeChecked()
      await expect(control.getByTestId('agent-sensor-overridden')).toBeVisible()
      await expect.poll(async () => (await toggleOf())?.state, {timeout: 30_000}).toBe('installed')

      expect((await page.request.get('/api/side-effects/report')).ok()).toBeTruthy()
      await expect
        .poll(
          async () =>
            (
              await (await page.request.get('/bootui/api/side-effects/sensor?sensor=environment&limit=500')).json()
            ).rows?.find(
              (row) => row.attribution === 'GET /api/side-effects/report' && row.target === 'sample.report.title'
            )?.kind,
          {timeout: 30_000}
        )
        .toBe('system property')
      await page
        .getByRole('button', {name: /Refresh/})
        .first()
        .click()
      await expect(page.locator('main')).toContainText('sample.report.title')

      await openView('java-agent', 'Java Agent')
      const agentControl = page.getByTestId('agent-sensor-toggle-environment')
      await expect(agentControl).toContainText('Configured: off')
      await expect(agentControl.getByTestId('agent-sensor-overridden')).toBeVisible()
      const agentToggle = agentControl.getByRole('switch', {name: /environment/})
      await expect(agentToggle).toBeChecked()
      await agentToggle.click()
      await expect(agentToggle).not.toBeChecked()
      await expect(agentControl.getByTestId('agent-sensor-overridden')).toHaveCount(0)
      expect(await toggleOf()).toMatchObject({enabled: false, overridden: false, state: 'off'})

      const sensor = (await (await page.request.get('/bootui/api/side-effects')).json()).sensors.find(
        (candidate) => candidate.id === 'environment'
      )
      expect(sensor.state).toBe('not-claimed')
    } finally {
      if ((await toggleOf())?.enabled) {
        await page.request.get('/bootui/api/overview')
        const {cookies} = await page.request.storageState()
        const xsrf = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')
        await page.request.post('/bootui/api/java-agent/sensors/environment', {
          headers: {'Content-Type': 'application/json', ...(xsrf ? {'X-XSRF-TOKEN': xsrf.value} : {})},
          data: {enabled: false}
        })
      }
    }
  })
})
