// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Java Agent view. The default suites run the sample without the agent and assert the not-attached state; the agent
 * suite (playwright.agent.config.js) sets the `agentAttached` fixture option and asserts the armed claim and its sensor
 * rows instead. Both legs check the setup snippets, their tabs, and Copy, which the panel offers in every state.
 */
test.describe('Java Agent view', () => {
  test('shows the agent status, setup snippets, copy, and API shape', async ({
    openView,
    page,
    browserName,
    context,
    agentAttached
  }) => {
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }

    const response = await page.request.get('/bootui/api/java-agent')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.setup.snippets.length).toBeGreaterThan(0)

    if (agentAttached) {
      expect(report.state).toBe('ARMED')
      const sensor = report.sensors.find((candidate) => candidate.id === 'executors')
      expect(sensor).toBeTruthy()
      expect(sensor.state).toBe('installed')
      expect(sensor.selfTestPassed).toBe(true)
      expect(sensor.failures ?? []).toEqual([])
    } else {
      expect(report.state).toBe('NOT_ATTACHED')
      expect(report.sensors ?? []).toEqual([])
    }

    await openView('java-agent', 'Java Agent')

    const sensors = page.getByRole('region', {name: 'Sensors'})
    if (agentAttached) {
      await expect(page.getByRole('heading', {name: 'Armed'})).toBeVisible()
      await expect(page.getByText('This application holds the agent’s claim.')).toBeVisible()
      const executorsRow = sensors.getByRole('row').filter({has: page.locator('code', {hasText: /^executors$/})})
      await expect(executorsRow).toHaveCount(1)
      await expect(executorsRow).toContainText(/installed/)
      await expect(executorsRow).toContainText('passed')
      await expect(sensors.getByText('No sensor installed')).toHaveCount(0)
    } else {
      await expect(page.getByRole('heading', {name: 'Not attached'})).toBeVisible()
      await expect(page.getByText('This JVM runs without the BootUI agent')).toBeVisible()
      await expect(
        page.getByText('No sensor installed: the agent installs the sensors this application asks for')
      ).toBeVisible()
    }

    const tabs = page.getByRole('tab')
    await expect(tabs.first()).toBeVisible()
    expect(await tabs.count()).toBeGreaterThan(0)

    if ((await tabs.count()) > 1) {
      const secondTab = tabs.nth(1)
      const panelId = await secondTab.getAttribute('aria-controls')
      await secondTab.click()
      await expect(secondTab).toHaveAttribute('aria-selected', 'true')
      await expect(page.locator(`#${panelId}`)).toBeVisible()
    }

    const activeSnippet = page.getByRole('tabpanel').locator('pre code')
    const snippetText = await activeSnippet.textContent()
    await page.getByRole('button', {name: /^Copy$/}).click()
    await expect(page.getByRole('button', {name: /Copied!/})).toBeVisible({timeout: 5_000})
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(snippetText)
  })
})
