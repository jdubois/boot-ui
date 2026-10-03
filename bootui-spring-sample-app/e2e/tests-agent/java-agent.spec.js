// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * The Spring MVC sample with the BootUI agent attached (docs/PLAN-v2.md M5-2): the claim is armed, the executors sensor
 * passed its self-test, and the panel shows its hooks and counters.
 */
test.describe('Java Agent, attached', () => {
  test('shows the armed claim and the executors sensor with its hooks and counters', async ({openView, page}) => {
    const response = await page.request.get('/bootui/api/java-agent')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.state).toBe('ARMED')
    const sensor = report.sensors.find((candidate) => candidate.id === 'executors')
    expect(sensor.selfTestPassed).toBe(true)
    expect(sensor.hooks.length).toBeGreaterThan(0)
    expect(sensor.executors).not.toBeNull()

    await openView('java-agent', 'Java Agent')
    await expect(page.getByRole('heading', {name: 'Armed'})).toBeVisible()
    await expect(page.locator('#java-agent-hooks-executors')).toBeVisible()
    await expect(page.getByRole('table', {name: /executors\s+hooks/})).toContainText('ThreadPoolExecutor.runWorker')
    await expect(page.locator('.java-agent-counters')).toContainText('Never applied')
  })
})
