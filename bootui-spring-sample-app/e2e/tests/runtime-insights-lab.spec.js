// @ts-check
import {fileURLToPath} from 'node:url'
import {expect, test} from './fixtures.js'
import {openInsightObservation} from '../scenarios/runtime-insights.js'

// The docker profile runs Ollama, so the sample has a Spring AI chat model and AI usage can be generated.
const profiles = (process.env.BOOTUI_SAMPLE_PROFILES || '').split(',').map((profile) => profile.trim())
const chatModel = profiles.includes('docker')

/** Seeded observations, as [card, title, subject]. */
const FINDINGS = [
  ['errors-behind-2xx', 'Errors behind 2xx responses', 'POST /api/insights/orders/{id}/import'],
  ['safe-method-dml', 'Writes in GET requests', 'GET /api/insights/orders/{id}'],
  ['transaction-across-remote-call', 'Transactions open across remote calls', '/api/insights/orders/{id}/price-check'],
  ['proxy-bypass', 'Proxy bypass', 'POST /api/insights/orders/{id}/recalculate'],
  ['anonymous-success-on-restricted-route', 'Anonymous success on a restricted route', '/api/insights/reports/{name}'],
  ['transactional-listener-skipped', 'Transactional listeners skipped', 'POST /api/insights/orders/{id}/notify'],
  ['after-commit-writes', 'Writes after commit', 'POST /api/insights/orders/{id}/archive'],
  ['orm-auto-flush', 'Hibernate auto-flushes', 'POST /api/insights/tags/auto-flush'],
  ['large-persistence-context', 'Large persistence contexts', 'GET /api/insights/tags/export']
]

const ADDITIONAL_FAMILIES = [
  'repeated-selects',
  'eager-repeated-selects',
  'lazy-sql-after-handler',
  'connections-per-request',
  'connection-reacquisition',
  'unbatched-write-loop',
  'readonly-candidate-transaction',
  'paged-without-order',
  'anonymous-data-reach',
  'framework-warnings-by-route',
  'exception-hotspots',
  'route-time-breakdown',
  'route-latency-outliers'
]

/**
 * The sample home page's Runtime Insights buttons name the observation to expect and link to it. A check that cannot
 * run is disabled with BootUI's reason; every generated observation appears in the panel without widening its list.
 */
test.describe('Runtime Insights buttons on the sample home page', () => {
  test('names expected observations without obsolete visibility tags', async ({page}) => {
    await page.route(
      (url) => url.pathname === '/',
      (route) =>
        route.fulfill({
          path: fileURLToPath(new URL('../../src/main/resources/static/index.html', import.meta.url)),
          contentType: 'text/html'
        })
    )
    for (const [asset, path, contentType] of [
      ['sample-lab.js', '../../src/main/resources/static/sample-lab.js', 'application/javascript'],
      ['sample-lab.css', '../../src/main/resources/static/sample-lab.css', 'text/css'],
      ['bootui-theme.css', '../../../bootui-ui/src/main/frontend/public/bootui-theme.css', 'text/css']
    ]) {
      await page.route(`**/${asset}`, (route) =>
        route.fulfill({path: fileURLToPath(new URL(path, import.meta.url)), contentType})
      )
    }
    await page.route('**/bootui/api/runtime-insights', (route) =>
      route.fulfill({
        json: {
          available: true,
          checks: [
            {kind: 'errors-behind-2xx', title: 'Errors behind 2xx responses'},
            {kind: 'repeated-selects', title: 'Repeated SELECTs'}
          ],
          observations: []
        }
      })
    )
    await page.route('**/bootui/api/beans?*', (route) => route.fulfill({json: {beans: []}}))
    await page.goto('/')
    await page.getByRole('tab', {name: 'Runtime Insights', exact: true}).click()
    await expect(page.getByRole('tabpanel', {name: 'Runtime Insights', exact: true})).toBeVisible()
    const section = page.locator('#runtime-insights-lab')
    await expect(section.locator('[data-family="errors-behind-2xx"]')).toContainText('Errors behind 2xx responses')
    await expect(section.locator('[data-family="repeated-selects"]')).toContainText('Repeated SELECTs')
    await expect(section.locator('.insight-tag')).toHaveCount(0)
    await expect(section).not.toContainText(/Show all routes|listed by default/)
  })

  test('generate every finding this app can produce and link each to the panel', async ({page, agentAttached}) => {
    test.setTimeout(180_000)
    await page.goto('/')
    await page.getByRole('tab', {name: 'Runtime Insights', exact: true}).click()
    const section = page.locator('#runtime-insights-lab')
    const card = (/** @type {string} */ key) => section.locator(`[data-family="${key}"]`)
    const generateAll = section.getByRole('button', {name: 'Generate all findings'})
    await expect(generateAll).toBeEnabled()

    // Unavailable here: BootUI's report says why, and the card says so instead of sending traffic.
    await expect(card('event-loop-blocking').getByRole('button')).toBeDisabled()
    await expect(card('event-loop-blocking').locator('.insight-requirement')).toContainText('Spring MVC')
    if (chatModel) {
      await expect(card('ai-usage-by-route').getByRole('button')).toBeEnabled()
    } else {
      await expect(card('ai-usage-by-route').getByRole('button')).toBeDisabled()
      await expect(card('ai-usage-by-route').locator('.insight-requirement')).toContainText('chat model')
    }
    await expect(card('changed-code-not-executed').getByRole('button')).toBeDisabled()
    // With the agent, it needs a previous run of the application, or Code Inventory's scan still running says so.
    await expect(card('changed-code-not-executed').locator('.insight-requirement')).toContainText(
      agentAttached ? /previous run|scan/ : 'BootUI agent'
    )
    if (agentAttached) {
      await expect(card('work-after-response').getByRole('button')).toBeEnabled()
    } else {
      await expect(card('work-after-response').getByRole('button')).toBeDisabled()
      await expect(card('work-after-response').locator('.insight-requirement')).toContainText('BootUI agent')
    }

    await expect(section.locator('.insight-tag')).toHaveCount(0)

    // A local model's answers are not timed here, so AI usage is not part of the exact count.
    const runnable = 22 + (agentAttached ? 1 : 0) + (chatModel ? 1 : 0)
    const reported = chatModel
      ? new RegExp(`(${runnable - 1}|${runnable}) of ${runnable}`)
      : `${runnable} of ${runnable}`
    await generateAll.click()
    await expect(section.locator('#insights-status')).toContainText('Generated all findings', {timeout: 140_000})
    await expect(section.locator('#insights-status')).toContainText(reported)
    for (const [key, title] of FINDINGS) {
      await expect(card(key).locator('.insight-result')).toContainText(/\((observed|partial evidence)\)/)
      await expect(card(key).getByRole('link', {name: new RegExp(`^${title}:`)})).toBeVisible()
    }
    for (const key of ADDITIONAL_FAMILIES) {
      await expect(card(key).locator('.insight-result a').first()).toBeVisible()
    }
    await expect(
      card('repeated-selects').getByRole('link', {name: 'Repeated SELECTs: GET /api/insights/orders'})
    ).toHaveAttribute('href', /^\/bootui\/#\/runtime-insights\?insight=repeated-selects%3A/)
    await expect(
      card('eager-repeated-selects').getByRole('link', {name: 'Repeated SELECTs: GET /api/insights/eager-orders'})
    ).toHaveAttribute('href', /^\/bootui\/#\/runtime-insights\?insight=repeated-selects%3A/)

    // Every result link names a row that must be visible without changing filters.
    const lazy = card('lazy-sql-after-handler')
    const lazyHref = await lazy.getByRole('link').first().getAttribute('href')
    const generatedIds = await section
      .locator('.insight-result a')
      .evaluateAll((links) =>
        links.map((link) => new URLSearchParams(link.getAttribute('href')?.split('?')[1]).get('insight'))
      )

    // Every generated observation remains available inside its compact group, including agent-default exclusions.
    await section.getByRole('link', {name: 'Open Runtime Insights'}).click()
    const main = page.locator('main')
    const row = (/** @type {string} */ title) =>
      main.locator('.insight-group').filter({has: page.getByText(title, {exact: true})})
    await expect(main.locator('.insight-group-toggle').first()).toBeVisible()
    await expect(main.locator('.insight-detail')).toHaveCount(0)
    const findings = agentAttached
      ? [...FINDINGS, ['work-after-response', 'Work after the response', 'GET /api/insights/orders/after-response']]
      : FINDINGS
    for (const [, title, subject] of findings) {
      await openInsightObservation(page, expect, title, subject)
    }
    for (const id of generatedIds) {
      expect(id).toBeTruthy()
      await page.goto(`/bootui/#/runtime-insights?insight=${encodeURIComponent(String(id))}`)
      await expect(main.locator(`[id="insight-row-${id}"]`)).toBeVisible()
      await expect(main.locator('.insight-detail')).toHaveCount(1)
    }
    await expect(main).not.toContainText(/Show all routes|Not listed by default/)

    await page.goto(String(lazyHref))
    await expect(page).toHaveURL(/#\/runtime-insights\?insight=lazy-sql-after-handler/)
    // The deep link opens the finding's row in place.
    const opened = main.locator('.insight-row.open')
    await expect(row('SQL after the handler returned').locator('.insight-group-toggle')).toHaveAttribute(
      'aria-expanded',
      'true'
    )
    await expect(opened).toContainText('GET /api/insights/orders/report')
  })
})
