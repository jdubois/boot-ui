// @ts-check
import {expect, test} from './fixtures.js'

const allPanelLinks = [
  {id: 'overview', title: 'Overview', heading: /^Overview/},
  {id: 'health', title: 'Health', heading: /^Health/},
  {id: 'metrics', title: 'Metrics', heading: /^Metrics/},
  {id: 'memory', title: 'Memory', heading: /^Memory/},
  {id: 'heap-dump', title: 'Heap Dump', heading: /^Heap Dump/},
  {id: 'startup', title: 'Startup Timeline', heading: /Startup timeline/},
  {id: 'config', title: 'Configuration', heading: /^Configuration/},
  {id: 'profiles', title: 'Profile Diff', heading: /Profile Diff/},
  {id: 'loggers', title: 'Loggers', heading: /^Loggers/},
  {id: 'beans', title: 'Beans', heading: /^Beans/},
  {id: 'conditions', title: 'Conditions', heading: /Auto-configuration conditions/},
  {id: 'mappings', title: 'Mappings', heading: /HTTP mappings/},
  {id: 'scheduled', title: 'Scheduled Tasks', heading: /Scheduled Tasks/},
  {id: 'database-connection-pools', title: 'Database Connection Pools', heading: /Database Connection Pools/},
  {id: 'data', title: 'Spring Data', heading: /Spring Data repositories/},
  {id: 'cache', title: 'Cache', heading: /Spring Cache/},
  {id: 'security', title: 'Security', heading: /Spring Security/},
  {id: 'ai', title: 'AI Usage', heading: /AI Usage/},
  {id: 'traces', title: 'Traces', heading: /^Traces/},
  {id: 'log-tail', title: 'Log Tail', heading: /Log Tail/},
  {id: 'http-probe', title: 'HTTP Probe', heading: /HTTP Probe/},
  {id: 'architecture', title: 'Architecture', heading: /^Architecture/},
  {id: 'pentest', title: 'Pentesting', heading: /^Pentesting/},
  {id: 'vulnerabilities', title: 'Vulnerabilities', heading: /^Vulnerabilities/},
  {id: 'devtools', title: 'DevTools', heading: /^DevTools/},
  {id: 'dev-services', title: 'Dev Services', heading: /^Dev Services/},
  {id: 'copilot', title: 'Copilot', heading: /^Copilot/},
  {id: 'claude-code', title: 'Claude Code', heading: /^Claude Code/}
]

async function mockPanelAvailability(page, overrides = {}) {
  await page.route(
    (url) => url.pathname === '/bootui/api/panels',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          panels: allPanelLinks.map((link) => ({
            id: link.id,
            title: link.title,
            available: overrides[link.id]?.available ?? true,
            unavailableReason: overrides[link.id]?.unavailableReason ?? null
          }))
        })
      })
    }
  )
}

async function expandAllSidebarGroups(page) {
  const toggles = page.locator('aside .bootui-nav-group__toggle')
  const count = await toggles.count()
  for (let index = 0; index < count; index += 1) {
    const toggle = toggles.nth(index)
    if ((await toggle.getAttribute('aria-expanded')) !== 'true') {
      await toggle.click()
    }
  }
}

test.describe('BootUI app shell', () => {
  test('navbar shows the application name and Spring Boot / Java versions', async ({page}) => {
    await page.goto('/bootui/')

    await expect(page.locator('.brand-name')).toHaveText('BootUI')
    await expect(page.locator('.topbar-title')).toContainText('bootui-sample')
    const subtitle = page.locator('.topbar-subtitle')
    await expect(subtitle).toContainText(/Spring Boot \d+\.\d+/)
    await expect(subtitle).toContainText(/Java /)
  })

  test('sidebar links to the BootUI GitHub project', async ({page}) => {
    await page.goto('/bootui/')

    const contributeLink = page.getByRole('link', {name: /Contribute to the project/})
    await expect(contributeLink).toHaveAttribute('href', 'https://github.com/jdubois/boot-ui')
    await expect(contributeLink.locator('.bi-github')).toBeVisible()
  })

  test('sidebar groups panels into collapsible sections', async ({page}) => {
    await mockPanelAvailability(page)
    await page.goto('/bootui/')

    const groups = [
      {title: 'Runtime', count: 5},
      {title: 'Configuration', count: 6},
      {title: 'Services', count: 6},
      {title: 'Diagnostics', count: 6},
      {title: 'Developer tools', count: 4}
    ]

    for (const group of groups) {
      const toggle = page.getByRole('button', {name: new RegExp(`${group.title}\\s+${group.count}`)})
      await expect(toggle).toBeVisible()
      await expect(toggle).toHaveAttribute('aria-expanded', group.title === 'Runtime' ? 'true' : 'false')
    }

    await page.getByRole('button', {name: /Services\s+6/}).click()
    await expect(page.getByRole('group', {name: 'Services panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Scheduled Tasks',
      'Database Connection Pools',
      'Spring Data',
      'Cache',
      'Security',
      'AI Usage'
    ])
  })

  test('sidebar dims unavailable panels and the active panel explains why', async ({page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/panels',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            panels: [
              {
                id: 'overview',
                title: 'Overview',
                available: false,
                unavailableReason: 'Overview support is unavailable in this test state'
              }
            ]
          })
        })
      }
    )
    await page.goto('/bootui/')

    const overviewLink = page.locator('aside .nav-link', {hasText: 'Overview'})
    await expect(overviewLink).toHaveClass(/bootui-nav-link--unavailable/)
    await expect(overviewLink).toHaveAttribute(
      'aria-label',
      'Overview - unavailable: Overview support is unavailable in this test state'
    )
    await expect(overviewLink).toHaveAttribute(
      'title',
      'Overview - unavailable: Overview support is unavailable in this test state'
    )
    await expect(overviewLink).not.toContainText('Unavailable')
    await expect(page.locator('.panel-availability-alert')).toContainText('Panel unavailable')
    await expect(page.locator('.panel-availability-alert')).toContainText(
      'Overview support is unavailable in this test state'
    )
  })

  test('sidebar collects unavailable non-overview panels in a collapsed group', async ({page}) => {
    await mockPanelAvailability(page, {
      ai: {
        available: false,
        unavailableReason: 'Spring AI is not available in this test state'
      }
    })
    await page.goto('/bootui/')

    const unavailableToggle = page.getByRole('button', {name: /Disabled \/ unavailable\s+1/})
    await expect(unavailableToggle).toBeVisible()
    await expect(unavailableToggle).toHaveAttribute('aria-expanded', 'false')
    await expect(page.locator('aside .nav-link', {hasText: 'AI Usage'})).not.toBeVisible()

    await unavailableToggle.click()

    const aiLink = page.locator('aside .nav-link', {hasText: 'AI Usage'})
    await expect(aiLink).toBeVisible()
    await expect(aiLink).toHaveClass(/bootui-nav-link--unavailable/)
    await expect(aiLink).toHaveAttribute(
      'aria-label',
      'AI Usage - unavailable: Spring AI is not available in this test state'
    )
  })

  test('sidebar links open every BootUI section', async ({page}) => {
    await mockPanelAvailability(page)
    await page.goto('/bootui/')
    await expandAllSidebarGroups(page)

    for (const link of allPanelLinks) {
      await page.locator('aside .nav-link', {hasText: link.title}).click()
      await expect(page.locator('main h2').filter({hasText: link.heading}).first()).toBeVisible({timeout: 15_000})
    }
  })

  test('redirects the root path to /overview', async ({page}) => {
    await page.goto('/bootui/')
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
  })
})
