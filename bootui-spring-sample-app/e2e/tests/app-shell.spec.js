// @ts-check
import {expect, test} from './fixtures.js'

const allPanelLinks = [
  {id: 'overview', title: 'Overview', heading: /^Overview/},
  {id: 'activity', title: 'Live Activity', heading: /Live Activity/},
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
  {id: 'crac', title: 'CRaC', heading: /^CRaC/},
  {id: 'config', title: 'Configuration', heading: /^Configuration/},
  {id: 'profile-diff', title: 'Profile Diff', heading: /Profile Diff/},
  {id: 'loggers', title: 'Loggers', heading: /^Loggers/},
  {id: 'beans', title: 'Beans', heading: /^Beans/},
  {id: 'conditions', title: 'Conditions', heading: /Auto-configuration conditions/},
  {id: 'mappings', title: 'Mappings', heading: /HTTP mappings/},
  {id: 'database-connection-pools', title: 'Database Connection Pools', heading: /Database Connection Pools/},
  {id: 'sql-trace', title: 'SQL Trace', heading: /SQL Trace/},
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
  {id: 'rest-client-trace', title: 'REST Client', heading: /^REST Client$/},
  {id: 'ai', title: 'AI Framework', heading: /AI Framework/},
  {id: 'cache', title: 'Cache', heading: /^Cache$/},
  {id: 'traces', title: 'Traces', heading: /^Traces/},
  {id: 'log-tail', title: 'Log Tail', heading: /Log Tail/},
  {id: 'exceptions', title: 'Exceptions', heading: /^Exceptions/},
  {id: 'http-exchanges', title: 'HTTP Exchanges', heading: /HTTP Exchanges/},
  {id: 'http-probe', title: 'HTTP Probe', heading: /HTTP Probe/},
  {id: 'email', title: 'Email', heading: /^Email/},
  {id: 'kafka', title: 'Kafka', heading: /^Kafka/},
  {id: 'rabbitmq', title: 'RabbitMQ', heading: /^RabbitMQ/},
  {id: 'jms', title: 'JMS', heading: /^JMS/},
  {id: 'architecture', title: 'Architecture', heading: /^Architecture/},
  {id: 'rest-api', title: 'REST API', heading: /^REST API/},
  {id: 'mcp-server', title: 'MCP Server', heading: /^MCP Server/},
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

  test('shell and current-page controls work when browser storage is denied before startup', async ({page}) => {
    await page.addInitScript(() => {
      Object.defineProperty(window, 'localStorage', {
        configurable: true,
        get() {
          throw new DOMException('Storage denied by browser policy', 'SecurityError')
        }
      })
    })
    await page.goto('/bootui/')

    await expect(page.locator('.bootui-shell')).toBeVisible()
    const root = page.locator('html')
    const themeToggle = page.locator('.theme-toggle')
    const initialTheme = await root.getAttribute('data-bs-theme')
    await themeToggle.click()
    await expect(root).toHaveAttribute('data-bs-theme', initialTheme === 'dark' ? 'light' : 'dark')

    const sidebar = page.locator('aside.bootui-sidebar')
    await expect(sidebar).not.toHaveClass(/bootui-sidebar--collapsed/)
    await page.locator('.sidebar-toggle').click()
    await expect(sidebar).toHaveClass(/bootui-sidebar--collapsed/)
  })

  test('mobile navigation contains focus and closes without stranding it', async ({page}) => {
    await page.setViewportSize({width: 390, height: 844})
    await page.goto('/bootui/')

    const drawer = page.locator('#bootui-mobile-navigation')
    const toggle = page.locator('.nav-hamburger')
    await expect(drawer).toHaveAttribute('aria-hidden', 'true')
    await expect(drawer).toHaveAttribute('inert', '')
    await expect(toggle).toHaveAttribute('aria-controls', 'bootui-mobile-navigation')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(toggle).toHaveAccessibleName('Open navigation menu')

    await page.keyboard.press('Tab')
    await expect(toggle).toBeFocused()

    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', {name: 'Close navigation menu'}).first()).toBeFocused()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(toggle).toHaveAttribute('aria-label', 'Close navigation menu')
    await expect(drawer).not.toHaveAttribute('aria-hidden', 'true')
    await expect(drawer).toHaveAttribute('aria-modal', 'true')
    await expect(page.locator('.bootui-workspace')).toHaveAttribute('inert', '')
    await page.keyboard.press('Control+K')
    await expect(page.getByRole('dialog', {name: 'Command palette'})).toHaveCount(0)
    await expect(drawer).toHaveAttribute('aria-modal', 'true')

    const firstDrawerLink = drawer.locator('.brand-card')
    const lastDrawerLink = drawer.locator('.contribute-card')
    await firstDrawerLink.focus()
    await page.keyboard.press('Shift+Tab')
    await expect(lastDrawerLink).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(firstDrawerLink).toBeFocused()

    await page.keyboard.press('Escape')
    await expect(toggle).toBeFocused()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(drawer).toHaveAttribute('aria-hidden', 'true')

    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', {name: 'Close navigation menu'}).first()).toBeFocused()
    const backdropBounds = await page.locator('.bootui-nav-backdrop').boundingBox()
    if (!backdropBounds) throw new Error('Navigation backdrop is not visible')
    await page.mouse.click(backdropBounds.x + backdropBounds.width - 4, backdropBounds.y + backdropBounds.height / 2)
    await expect(toggle).toBeFocused()
    await expect(drawer).toHaveAttribute('aria-hidden', 'true')

    await page.keyboard.press('Enter')
    const architectureLink = drawer.getByRole('link', {name: 'Architecture'})
    await architectureLink.focus()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/#\/architecture$/)
    await expect(page.locator('main.content-stage')).toBeFocused()
    await expect(drawer).toHaveAttribute('aria-hidden', 'true')
    expect(await drawer.evaluate((element) => element.contains(document.activeElement))).toBe(false)
  })

  test('command palette contains focus, tracks its active option, and hands route focus to content', async ({page}) => {
    await page.setViewportSize({width: 1280, height: 800})
    await page.goto('/bootui/')

    const trigger = page.getByRole('button', {name: /Go to panel/})
    await trigger.focus()
    await page.keyboard.press('Enter')

    const palette = page.getByRole('dialog', {name: 'Command palette'})
    const input = palette.getByRole('combobox', {name: 'Search panels'})
    const listbox = palette.getByRole('listbox', {name: 'Panel results'})
    await expect(input).toBeFocused()
    await expect(input).toHaveAttribute('aria-controls', await listbox.getAttribute('id'))
    await expect(page.locator('.bootui-workspace')).toHaveAttribute('inert', '')
    await expect(page.locator('#bootui-mobile-navigation')).toHaveAttribute('inert', '')

    const firstActiveId = await input.getAttribute('aria-activedescendant')
    await page.keyboard.press('ArrowDown')
    await expect(input).not.toHaveAttribute('aria-activedescendant', firstActiveId)
    const secondActiveId = await input.getAttribute('aria-activedescendant')
    await expect(page.locator(`#${secondActiveId}`)).toHaveAttribute('aria-selected', 'true')

    await page.keyboard.press('Tab')
    await expect(input).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(input).toBeFocused()

    await input.fill('no-panel-has-this-name')
    await expect(input).not.toHaveAttribute('aria-activedescendant', /.+/)

    await page.keyboard.press('Escape')
    await expect(palette).toHaveCount(0)
    await expect(trigger).toBeFocused()

    await page.keyboard.press('Control+K')
    await expect(input).toBeFocused()
    await input.fill('Architecture')
    await page.keyboard.press('Enter')

    await expect(page).toHaveURL(/#\/architecture$/)
    await expect(palette).toHaveCount(0)
    await expect(page.locator('main.content-stage')).toBeFocused()
  })

  test('unknown hashes provide accessible recovery without leaving the mounted console', async ({page}) => {
    await page.goto('/bootui/#/missing-panel')

    await expect(page.getByRole('heading', {name: 'Page not found'})).toBeVisible()
    await expect(page).toHaveTitle('Not Found · bootui-sample · BootUI')

    await page.getByRole('button', {name: 'Search panels'}).click()
    const palette = page.getByRole('dialog', {name: 'Command palette'})
    await expect(palette.getByRole('combobox', {name: 'Search panels'})).toBeFocused()
    await page.keyboard.press('Escape')

    await page.getByRole('link', {name: 'Go to Overview'}).click()
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
    await expect(page).toHaveTitle('Overview · bootui-sample · BootUI')
  })

  test('mobile command palette restores shortcut focus on cancel', async ({page}) => {
    await page.setViewportSize({width: 390, height: 844})
    await page.goto('/bootui/')

    const themeToggle = page.locator('.theme-toggle')
    await themeToggle.focus()
    await page.keyboard.press('Control+K')

    const palette = page.getByRole('dialog', {name: 'Command palette'})
    const input = palette.getByRole('combobox', {name: 'Search panels'})
    await expect(input).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(input).toBeFocused()
    await page.keyboard.press('Escape')

    await expect(palette).toHaveCount(0)
    await expect(themeToggle).toBeFocused()
  })

  test('main content scrolls while the sidebar stays fixed', async ({page}) => {
    // A short viewport guarantees the main content overflows the window.
    await page.setViewportSize({width: 1280, height: 400})
    await page.goto('/bootui/')
    await page.locator('main .page-panel h2').first().waitFor()

    const layout = await page.evaluate(() => {
      const shell = document.querySelector('.bootui-shell')
      const workspace = document.querySelector('.bootui-workspace')
      const sidebar = document.querySelector('aside.bootui-sidebar')
      const doc = document.scrollingElement
      return {
        // The page itself must not scroll: scrolling lives inside the app, not the document.
        documentScrollable: doc.scrollHeight > doc.clientHeight + 1,
        shellOverflowY: getComputedStyle(shell).overflowY,
        workspaceOverflowY: getComputedStyle(workspace).overflowY,
        workspaceScrollable: workspace.scrollHeight > workspace.clientHeight,
        sidebarOverflowY: getComputedStyle(sidebar).overflowY,
        sidebarOverscroll: getComputedStyle(sidebar).overscrollBehaviorY
      }
    })

    expect(layout.documentScrollable).toBe(false)
    expect(layout.shellOverflowY).toBe('hidden')
    expect(layout.workspaceOverflowY).toBe('auto')
    expect(layout.workspaceScrollable).toBe(true)
    // The sidebar is its own scroll region and never chains into the rest of the page.
    expect(layout.sidebarOverflowY).toBe('auto')
    expect(layout.sidebarOverscroll).toBe('contain')

    // Scrolling the main content moves only the content, not the sidebar or the document.
    const sidebar = page.locator('aside.bootui-sidebar')
    const sidebarTopBefore = await sidebar.evaluate((el) => el.getBoundingClientRect().top)
    const contentScrollTop = await page.locator('.bootui-workspace').evaluate((el) => {
      el.scrollTop = el.scrollHeight
      return el.scrollTop
    })
    expect(contentScrollTop).toBeGreaterThan(0)
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
    expect(await sidebar.evaluate((el) => el.getBoundingClientRect().top)).toBe(sidebarTopBefore)
  })

  test('sidebar groups panels into collapsible sections', async ({page}) => {
    await mockPanelAvailability(page)
    await page.goto('/bootui/')

    const groups = [
      {title: 'Advisors', count: 8},
      {title: 'Runtime', count: 10},
      {title: 'Configuration', count: 6},
      {title: 'Database', count: 7},
      {title: 'Security', count: 2},
      {title: 'Services', count: 8},
      {title: 'Diagnostics', count: 5},
      {title: 'Developer tools', count: 5}
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

    await page.getByRole('button', {name: /Database\s+7/}).click()
    await expect(page.getByRole('group', {name: 'Database panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Database Connection Pools',
      'SQL Trace',
      'Transactions',
      'Spring Data',
      'Flyway',
      'Liquibase',
      'Database Advisor'
    ])

    await page.getByRole('button', {name: /Security\s+2/}).click()
    await expect(page.getByRole('group', {name: 'Security panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Spring Security',
      'Security Logs'
    ])

    await page.getByRole('button', {name: /Services\s+8/}).click()
    await expect(page.getByRole('group', {name: 'Services panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Scheduled Tasks',
      'REST Client',
      'AI Framework',
      'Cache',
      'Email',
      'Kafka',
      'RabbitMQ',
      'JMS'
    ])

    await page.getByRole('button', {name: /Diagnostics\s+5/}).click()
    await expect(page.getByRole('group', {name: 'Diagnostics panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Traces',
      'Log Tail',
      'Exceptions',
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
    await expect(page.locator('aside .nav-link', {hasText: 'AI Framework'})).not.toBeVisible()

    await unavailableToggle.click()

    const aiLink = page.locator('aside .nav-link', {hasText: 'AI Framework'})
    await expect(aiLink).toBeVisible()
    await expect(aiLink).toHaveClass(/bootui-nav-link--unavailable/)
    await expect(aiLink).toHaveAttribute(
      'aria-label',
      'AI Framework - unavailable: Spring AI is not available in this test state'
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
