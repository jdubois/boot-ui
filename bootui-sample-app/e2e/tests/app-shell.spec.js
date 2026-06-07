// @ts-check
import {expect, test} from './fixtures.js'

const allPanelLinks = [
  {id: 'overview', title: 'Overview', heading: /^Overview/},
  {id: 'github', title: 'GitHub', heading: /^GitHub/},
  {id: 'health', title: 'Health', heading: /^Health/},
  {id: 'http-sessions', title: 'HTTP Sessions', heading: /^HTTP Sessions/},
  {id: 'metrics', title: 'Metrics', heading: /^Metrics/},
  {id: 'live-memory', title: 'Live Memory', heading: /^Live Memory/},
  {id: 'jvm-tuning', title: 'JVM Tuning', heading: /^JVM Tuning/},
  {id: 'heap-dump', title: 'Heap Dump', heading: /^Heap Dump/},
  {id: 'threads', title: 'Threads', heading: /^Threads/},
  {id: 'memory', title: 'Memory', heading: /^Memory/},
  {id: 'startup', title: 'Startup Timeline', heading: /Startup timeline/},
  {id: 'graalvm', title: 'GraalVM', heading: /^GraalVM/},
  {id: 'config', title: 'Configuration', heading: /^Configuration/},
  {id: 'profiles', title: 'Profile Diff', heading: /Profile Diff/},
  {id: 'loggers', title: 'Loggers', heading: /^Loggers/},
  {id: 'beans', title: 'Beans', heading: /^Beans/},
  {id: 'conditions', title: 'Conditions', heading: /Auto-configuration conditions/},
  {id: 'mappings', title: 'Mappings', heading: /HTTP mappings/},
  {id: 'database-connection-pools', title: 'Database Connection Pools', heading: /Database Connection Pools/},
  {id: 'data', title: 'Spring Data', heading: /Spring Data repositories/},
  {id: 'hibernate', title: 'Hibernate', heading: /^Hibernate/},
  {id: 'flyway', title: 'Flyway', heading: /Flyway migrations/},
  {id: 'liquibase', title: 'Liquibase', heading: /Liquibase change sets/},
  {id: 'spring-security', title: 'Spring Security', heading: /Spring Security/},
  {id: 'security-logs', title: 'Security Logs', heading: /Security Logs/},
  {id: 'security', title: 'Security', heading: /^Security/},
  {id: 'pentesting', title: 'Pentesting', heading: /^Pentesting/},
  {id: 'vulnerabilities', title: 'Vulnerabilities', heading: /^Vulnerabilities/},
  {id: 'scheduled', title: 'Scheduled Tasks', heading: /Scheduled Tasks/},
  {id: 'spring-cache', title: 'Spring Cache', heading: /Spring Cache/},
  {id: 'ai', title: 'AI Usage', heading: /AI Usage/},
  {id: 'traces', title: 'Traces', heading: /^Traces/},
  {id: 'log-tail', title: 'Log Tail', heading: /Log Tail/},
  {id: 'http-exchanges', title: 'HTTP Exchanges', heading: /HTTP Exchanges/},
  {id: 'http-probe', title: 'HTTP Probe', heading: /HTTP Probe/},
  {id: 'architecture', title: 'Architecture', heading: /^Architecture/},
  {id: 'rest-api', title: 'REST API', heading: /^REST API/},
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
      {title: 'Advisors', count: 8},
      {title: 'Runtime', count: 9},
      {title: 'Configuration', count: 6},
      {title: 'Database', count: 4},
      {title: 'Security', count: 2},
      {title: 'Services', count: 3},
      {title: 'Diagnostics', count: 4},
      {title: 'Developer tools', count: 4}
    ]

    for (const group of groups) {
      const toggle = page.getByRole('button', {name: new RegExp(`${group.title}\\s+${group.count}`)})
      await expect(toggle).toBeVisible()
      await expect(toggle).toHaveAttribute('aria-expanded', group.title === 'Advisors' ? 'true' : 'false')
    }

    await expect(page.getByRole('group', {name: 'Advisors panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Architecture',
      'REST API',
      'Spring',
      'Hibernate',
      'Memory',
      'Security',
      'Pentesting',
      'Vulnerabilities'
    ])

    await page.getByRole('button', {name: /Database\s+4/}).click()
    await expect(page.getByRole('group', {name: 'Database panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Database Connection Pools',
      'Spring Data',
      'Flyway',
      'Liquibase'
    ])

    await page.getByRole('button', {name: /Security\s+2/}).click()
    await expect(page.getByRole('group', {name: 'Security panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Spring Security',
      'Security Logs'
    ])

    await page.getByRole('button', {name: /Services\s+3/}).click()
    await expect(page.getByRole('group', {name: 'Services panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Scheduled Tasks',
      'Spring Cache',
      'AI Usage'
    ])

    await page.getByRole('button', {name: /Diagnostics\s+4/}).click()
    await expect(page.getByRole('group', {name: 'Diagnostics panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Traces',
      'Log Tail',
      'HTTP Exchanges',
      'HTTP Probe'
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
      const navLink = page.locator(`aside a.bootui-nav-link[href$="#/${link.id}"]`)
      await expect(navLink).toHaveCount(1)
      await navLink.click()
      await expect(page.locator('main h2').filter({hasText: link.heading}).first()).toBeVisible({timeout: 15_000})
    }
  })

  test('redirects the root path to /overview', async ({page}) => {
    await page.goto('/bootui/')
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
  })
})
