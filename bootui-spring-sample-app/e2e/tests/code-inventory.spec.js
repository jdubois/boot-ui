// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Code Inventory view (docs/PLAN-v2.md §5.15). The default suites run the sample without the agent, so the panel is
 * unavailable with the Java Agent panel's reason and links there; the agent suite (playwright.agent.config.js) sets the
 * `agentAttached` fixture option and asserts the seeded never-called method, the executed one with its first request,
 * and the declared jar the sample never loads.
 */
test.describe('Code Inventory view', () => {
  test('reports the run’s executed and never-executed seeds, or why it cannot', async ({
    openView,
    page,
    agentAttached
  }) => {
    const panels = await (await page.request.get('/bootui/api/panels')).json()
    const panel = panels.panels.find((candidate) => candidate.id === 'code-inventory')
    expect(panel).toBeTruthy()

    if (!agentAttached) {
      expect(panel.available).toBe(false)
      expect(panel.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)
      const report = await (await page.request.get('/bootui/api/code-inventory')).json()
      expect(report.available).toBe(false)
      expect(report.unavailableReason).toMatch(/^Requires the BootUI agent's inventory sensor/)

      await openView('code-inventory', 'Code Inventory')
      await expect(page.locator('.panel-availability-alert')).toContainText('inventory sensor')
      await page.getByRole('link', {name: 'Open the Java Agent panel'}).click()
      await expect(page).toHaveURL(/#\/java-agent$/)
      return
    }

    expect(panel.available).toBe(true)
    expect((await page.request.get('/api/hello')).ok()).toBeTruthy()
    await expect
      .poll(async () => (await (await page.request.get('/bootui/api/code-inventory')).json()).scan?.status, {
        timeout: 60_000
      })
      .toBe('COMPLETE')

    await openView('code-inventory', 'Code Inventory')
    await expect(page.locator('#code-inventory-headline')).toHaveText(/\d+ of \d+ application methods executed/)
    const tabs = page.getByRole('tab')
    await expect(tabs.first()).toHaveText('Application code')
    await expect(tabs.first()).toHaveAttribute('aria-selected', 'true')

    await page.getByLabel('Never executed only').check()
    await page.getByRole('button', {name: /io\.github\.jdubois\.bootui\.sample\.inventory/}).click()
    const classes = page.locator('.code-inventory-classes')
    await expect(classes).toContainText('GreetingService')
    await classes.locator('summary', {hasText: 'GreetingService'}).click()
    await expect(classes).toContainText('farewell(Ljava/lang/String;)Ljava/lang/String;')
    await expect(classes).not.toContainText('greet(Ljava/lang/String;)Ljava/lang/String;')

    await page.getByRole('tab', {name: 'Dependencies'}).click()
    const commonsExec = page.getByRole('tabpanel').getByRole('row').filter({hasText: 'org.apache.commons:commons-exec'})
    await expect(commonsExec).toContainText('Not loaded in this run')
  })

  test('feeds the Vulnerabilities panel’s runtime reach, or says why it cannot', async ({
    openView,
    page,
    agentAttached
  }) => {
    const report = await (await page.request.get('/bootui/api/vulnerabilities')).json()
    expect(typeof report.runtimeReach.available).toBe('boolean')

    await openView('vulnerabilities', 'Vulnerabilities')
    const table = page.locator('table').filter({hasText: 'Dependency'})
    if (!agentAttached) {
      expect(report.runtimeReach.available).toBe(false)
      expect(report.dependencies.every((dependency) => dependency.runtimeReach === null)).toBe(true)
      await expect(page.locator('.runtime-reach-note')).toContainText('Runtime reach is not shown')
      await expect(table.getByRole('columnheader', {name: 'Runtime reach'})).toHaveCount(0)
      return
    }

    expect(report.runtimeReach.available).toBe(true)
    await expect(table.getByRole('columnheader', {name: 'Runtime reach'})).toBeVisible()
    await page.getByLabel('Filter runtime dependencies').fill('commons-exec')
    await expect(table.getByRole('row').filter({hasText: 'org.apache.commons:commons-exec'})).toContainText(
      'Not loaded yet'
    )
    await page.getByLabel('Filter runtime dependencies').fill('spring-webmvc')
    await page.getByLabel('Filter by runtime reach').selectOption('LOADED_ANY')
    await expect(table.getByRole('row').filter({hasText: 'org.springframework:spring-webmvc'})).toContainText('Loaded')
    await page.getByLabel('Filter by runtime reach').selectOption('NOT_LOADED')
    await expect(table.getByRole('row').filter({hasText: 'org.springframework:spring-webmvc'})).toHaveCount(0)
  })
})
