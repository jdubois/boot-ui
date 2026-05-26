// @ts-check
import { test, expect } from './fixtures.js'

test.describe('BootUI app shell', () => {

  test('navbar shows the application name and Spring Boot / Java versions', async ({ page }) => {
    await page.goto('/bootui/')

    await expect(page.getByRole('heading', { name: /BootUI/, level: 1 })).toBeVisible()
    const subtitle = page.locator('.navbar .text-light.small')
    await expect(subtitle).toContainText('bootui-sample')
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
      { title: 'Metrics',           heading: /^Metrics/ },
      { title: 'Scheduled Tasks',   heading: /Scheduled Tasks/ },
      { title: 'HTTP Probe',        heading: /HTTP Probe/ },
      { title: 'Log Tail',          heading: /Log Tail/ },
      { title: 'Profile Diff',      heading: /Profile Diff/ },
      { title: 'Security',          heading: /Spring Security/ },
      { title: 'Memory',            heading: /^Memory/ }
    ]

    await page.goto('/bootui/')

    for (const link of links) {
      await page.locator('aside .nav-link', { hasText: link.title }).click()
      await expect(page.getByRole('heading', { level: 2, name: link.heading }))
        .toBeVisible({ timeout: 15_000 })
    }
  })

  test('redirects the root path to /overview', async ({ page }) => {
    await page.goto('/bootui/')
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
  })
})
