// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * The Spring MVC sample with the BootUI agent attached (docs/PLAN-v2.md M5-2, M5-4a): the claim is armed, the executors
 * and code-paths sensors passed their self-tests, and the panel shows their hooks and counters.
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
    await expect(page.locator('.java-agent-counters[data-sensor="executors"]')).toContainText('Never applied')
  })

  // Every agent leg, a companion agent beside BootUI's included (agent-config.js): no sensor may fail to install or to
  // pass its self-test, and no class may fail to transform, which is how an agent conflict shows (PLAN-v2 §5.13).
  test('installs every claimed sensor, each passing its self-test, with no failed transformation', async ({page}) => {
    const response = await page.request.get('/bootui/api/java-agent')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.state).toBe('ARMED')
    expect(report.counters.errors).toBe(0)
    expect(report.retransformation.state).toBe('installed')
    expect(report.retransformation.failed).toBe(0)
    expect(report.sensors.map((sensor) => sensor.id)).toEqual(
      expect.arrayContaining(['executors', 'inventory', 'code-paths', 'processes', 'network', 'files', 'environment', 'blocking'])
    )
    for (const sensor of report.sensors) {
      expect.soft(sensor.state, sensor.id).toBe('installed')
      expect.soft(sensor.selfTestPassed, `${sensor.id}: ${sensor.selfTestError}`).toBe(true)
      expect.soft(sensor.failures ?? [], sensor.id).toEqual([])
      expect.soft(sensor.failedTypes, sensor.id).toBe(0)
    }
  })

  test('shows the code-paths sensor recording the requests through the sample beans', async ({openView, page}) => {
    expect((await page.request.get('/api/hello')).ok()).toBeTruthy()
    const response = await page.request.get('/bootui/api/java-agent')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    const sensor = report.sensors.find((candidate) => candidate.id === 'code-paths')
    expect(sensor.selfTestPassed).toBe(true)
    expect(sensor.active).toBe(true)
    expect(sensor.codePaths.fragmentsFlushed).toBeGreaterThan(0)
    expect(sensor.codePaths.errors).toBe(0)

    await openView('java-agent', 'Java Agent')
    await expect(page.getByRole('table', {name: /code-paths\s+hooks/})).toContainText('times bean methods per request')
    await expect(page.locator('.java-agent-counters[data-sensor="code-paths"]')).toContainText('Fragments recorded')
  })
})
