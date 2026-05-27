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

  test('sidebar links to the BootUI GitHub project', async ({ page }) => {
    await page.goto('/bootui/')

    const contributeLink = page.getByRole('link', { name: /Contribute to the project/ })
    await expect(contributeLink).toHaveAttribute('href', 'https://github.com/jdubois/boot-ui')
    await expect(contributeLink.locator('.bi-github')).toBeVisible()
  })

  test('sidebar links open every BootUI section', async ({ page }) => {
    const links = [
      { title: 'Overview',          heading: /^Overview/ },
      { title: 'Startup Timeline',  heading: /Startup timeline/ },
      { title: 'Memory',            heading: /^Memory/ },
      { title: 'Health',            heading: /^Health/ },
      { title: 'Metrics',           heading: /^Metrics/ },
      { title: 'Conditions',        heading: /Auto-configuration conditions/ },
      { title: 'Beans',             heading: /^Beans/ },
      { title: 'Mappings',          heading: /HTTP mappings/ },
      { title: 'Configuration',     heading: /^Configuration/ },
      { title: 'Profile Diff',      heading: /Profile Diff/ },
      { title: 'Loggers',           heading: /^Loggers/ },
      { title: 'Log Tail',          heading: /Log Tail/ },
      { title: 'HTTP Probe',        heading: /HTTP Probe/ },
      { title: 'DevTools',          heading: /^DevTools/ },
      { title: 'Dev Services',      heading: /^Dev Services/ },
      { title: 'Scheduled Tasks',   heading: /Scheduled Tasks/ },
      { title: 'Data',              heading: /Spring Data repositories/ },
      { title: 'Cache',             heading: /Spring Cache/ },
      { title: 'Traces',            heading: /^Traces/ },
      { title: 'AI Usage',          heading: /AI Usage/ },
      { title: 'Security',          heading: /Spring Security/ },
      { title: 'Vulnerabilities',   heading: /^Vulnerabilities/ }
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
