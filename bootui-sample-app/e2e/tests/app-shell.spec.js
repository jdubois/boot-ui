// @ts-check
import { test, expect } from './fixtures.js'

test.describe('BootUI app shell', () => {

  test('navbar shows the application name and Spring Boot / Java versions', async ({ page }) => {
    await page.goto('/bootui/')

    await expect(page.locator('.brand-name')).toHaveText('BootUI')
    await expect(page.locator('.topbar-title')).toContainText('bootui-sample')
    const subtitle = page.locator('.topbar-subtitle')
    await expect(subtitle).toContainText(/Spring Boot \d+\.\d+/)
    await expect(subtitle).toContainText(/Java /)
  })

  test('sidebar links open every BootUI section', async ({ page }) => {
    const links = [
      { title: 'Overview',          heading: /^Overview/ },
      { title: 'Beans',             heading: /^Beans/ },
      { title: 'Conditions',        heading: /Auto-configuration conditions/ },
      { title: 'Configuration',     heading: /^Configuration/ },
      { title: 'Mappings',          heading: /HTTP mappings/ },
      { title: 'Health',            heading: /^Health/ },
      { title: 'Loggers',           heading: /^Loggers/ },
      { title: 'Data',              heading: /Spring Data repositories/ },
      { title: 'Startup Timeline',  heading: /Startup timeline/ },
      { title: 'Memory',            heading: /^Memory/ },
      { title: 'Metrics',           heading: /^Metrics/ },
      { title: 'DevTools',          heading: /^DevTools/ },
      { title: 'Dev Services',      heading: /^Dev Services/ },
      { title: 'Scheduled Tasks',   heading: /Scheduled Tasks/ },
      { title: 'HTTP Probe',        heading: /HTTP Probe/ },
      { title: 'Log Tail',          heading: /Log Tail/ },
      { title: 'Profile Diff',      heading: /Profile Diff/ },
      { title: 'Security',          heading: /Spring Security/ }
    ]

    await page.goto('/bootui/')

    for (const link of links) {
      await page.locator('aside .nav-link', { hasText: link.title }).click()
      await expect(page.locator('main h2').filter({ hasText: link.heading }).first())
        .toBeVisible({ timeout: 15_000 })
    }
  })

  test('redirects the root path to /overview', async ({ page }) => {
    await page.goto('/bootui/')
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
  })
})
