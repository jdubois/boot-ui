// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The shared Vue UI renders the same panel headings on Quarkus as on Spring Boot, with one
 * platform-aware exception: the Spring/Quarkus advisor (route/id `spring`) renders "Quarkus" here.
 *
 * The navigation test below only consults an entry when the Quarkus adapter actually reports that
 * panel available, so Spring-only panels that are unavailable on Quarkus (the Overview dashboard,
 * Spring Data, Spring Security, GraalVM, CRaC, Conditions, Startup Timeline, HTTP Sessions, DevTools)
 * are simply skipped — they are covered by not-applicable.spec.js instead.
 */
const PANEL_HEADINGS = {
  overview: /^Overview/,
  activity: /Live Activity/,
  github: /^GitHub/,
  health: /^Health/,
  'http-sessions': /^HTTP Sessions/,
  metrics: /^Metrics/,
  'live-memory': /^Live Memory/,
  'jvm-tuning': /^JVM Tuning/,
  'heap-dump': /^Heap Dump/,
  threads: /^Threads/,
  memory: /^Memory/,
  startup: /Startup timeline/,
  graalvm: /^GraalVM/,
  crac: /^CRaC/,
  config: /^Configuration/,
  'profile-diff': /Profile Diff/,
  loggers: /^Loggers/,
  beans: /^Beans/,
  conditions: /Auto-configuration conditions/,
  mappings: /HTTP mappings/,
  'database-connection-pools': /Database Connection Pools/,
  'sql-trace': /SQL Trace/,
  data: /Spring Data repositories/,
  hibernate: /^Hibernate/,
  flyway: /Flyway migrations/,
  liquibase: /Liquibase change sets/,
  'spring-security': /Spring Security/,
  'security-logs': /Security Logs/,
  security: /^Security/,
  pentesting: /^Pentesting/,
  vulnerabilities: /^Vulnerabilities/,
  scheduled: /Scheduled Tasks/,
  cache: /^Cache$/,
  ai: /AI Usage/,
  traces: /^Traces/,
  'log-tail': /Log Tail/,
  exceptions: /^Exceptions/,
  'http-exchanges': /HTTP Exchanges/,
  'http-probe': /HTTP Probe/,
  architecture: /^Architecture/,
  'rest-api': /^REST API/,
  spring: /^Quarkus/,
  'mcp-server': /^MCP Server/,
  devtools: /^DevTools/,
  'dev-services': /^Dev Services/,
  copilot: /^Copilot/,
  'claude-code': /^Claude Code/
}

test.describe('BootUI app shell (Quarkus)', () => {
  test('navbar shows the application name and Quarkus / Java versions', async ({page}) => {
    await page.goto('/bootui/')

    await expect(page.locator('.brand-name')).toHaveText('BootUI')
    await expect(page.locator('.topbar-title')).toContainText('bootui-quarkus-sample')
    const subtitle = page.locator('.topbar-subtitle')
    await expect(subtitle).toContainText(/Quarkus \d+\.\d+/)
    await expect(subtitle).toContainText(/Java /)
  })

  test('footer reflects the Quarkus platform', async ({page}) => {
    await page.goto('/bootui/')
    await expect(page.locator('.bootui-footer')).toContainText('developer UI for Quarkus')
  })

  test('sidebar links to the BootUI GitHub project', async ({page}) => {
    await page.goto('/bootui/')

    const contributeLink = page.getByRole('link', {name: /Contribute to the project/})
    await expect(contributeLink).toHaveAttribute('href', 'https://github.com/jdubois/boot-ui')
    await expect(contributeLink.locator('.bi-github')).toBeVisible()
  })

  test('the Advisors sidebar group uses the framework-aware Quarkus label', async ({page}) => {
    await page.goto('/bootui/')

    // The advisor panels are all available on the sample app, so the Advisors group lists them in
    // registry order. The Spring/Quarkus advisor renders "Quarkus" (driven by the panels platform),
    // which is the whole point of keeping one shared, platform-aware view instead of forking it.
    await expect(page.getByRole('group', {name: 'Advisors panels'}).locator('.bootui-nav-link__label')).toHaveText([
      'Architecture',
      'REST API',
      'Quarkus',
      'Hibernate',
      'Memory',
      'Security',
      'Pentesting',
      'Vulnerabilities'
    ])
  })

  test('every panel the Quarkus adapter reports available renders in the browser', async ({page}) => {
    const response = await page.request.get('/bootui/api/panels')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.platform).toBe('quarkus')

    const available = report.panels.filter((panel) => panel.available)
    // Sanity check: the Quarkus surface should light up a substantial set of panels, not a handful.
    expect(available.length).toBeGreaterThan(25)

    for (const panel of available) {
      const heading = PANEL_HEADINGS[panel.id]
      expect(heading, `no heading mapping for available Quarkus panel "${panel.id}"`).toBeTruthy()
      await page.goto(`/bootui/#/${panel.id}`)
      await expect(page.locator('main h2').filter({hasText: heading}).first()).toBeVisible({timeout: 15_000})
      // An available panel renders its own view, never the generic "panel unavailable" placeholder.
      await expect(page.locator('.panel-availability-alert')).toHaveCount(0)
    }
  })

  test('redirects the root path to /overview', async ({page}) => {
    await page.goto('/bootui/')
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
  })
})
