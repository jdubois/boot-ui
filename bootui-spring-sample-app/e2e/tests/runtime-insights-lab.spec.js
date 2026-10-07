// @ts-check
import {expect, test} from './fixtures.js'

/** The findings a default run lists in the panel, as [card, title, subject] (docs/PLAN-v2.md M4-20). */
const LISTED = [
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

/** Seeds whose findings the panel leaves out of its default list, reached with Show all routes. */
const UNLISTED = [
  'repeated-selects',
  'lazy-sql-after-handler',
  'connections-per-request',
  'anonymous-data-reach',
  'framework-warnings-by-route',
  'exception-hotspots',
  'route-time-breakdown'
]

/**
 * The sample home page's Runtime Insights buttons: each card says which finding to expect and whether the panel lists
 * it by default, from BootUI's own report; a card whose check cannot run on this application is disabled with the
 * reason BootUI gives; and Generate all findings makes the default-listed findings appear in the panel.
 */
test.describe('Runtime Insights buttons on the sample home page', () => {
  test('generate every finding this app can produce and link each to the panel', async ({page, agentAttached}) => {
    test.setTimeout(150_000)
    await page.goto('/')
    const section = page.locator('#runtime-insights-lab')
    const card = (/** @type {string} */ key) => section.locator(`[data-family="${key}"]`)
    const generateAll = section.getByRole('button', {name: 'Generate all findings'})
    await expect(generateAll).toBeEnabled()

    // Unavailable here: BootUI's report says why, and the card says so instead of sending traffic.
    await expect(card('event-loop-blocking').getByRole('button')).toBeDisabled()
    await expect(card('event-loop-blocking').locator('.insight-requirement')).toContainText('Spring MVC')
    await expect(card('ai-usage-by-route').getByRole('button')).toBeDisabled()
    await expect(card('ai-usage-by-route').locator('.insight-requirement')).toContainText('chat model')
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

    // Each card says whether its finding is listed by default, from the kind's external validation.
    for (const [key] of LISTED) {
      await expect(card(key).locator('.insight-tag')).toHaveText('listed by default')
    }
    for (const key of UNLISTED) {
      await expect(card(key).locator('.insight-tag').first()).toContainText('only with Show all routes')
    }

    const runnable = agentAttached ? 17 : 16
    await generateAll.click()
    await expect(section.locator('#insights-status')).toContainText(
      `Generated all findings: ${runnable} of ${runnable} families reported`,
      {timeout: 120_000}
    )
    for (const [key, title] of LISTED) {
      await expect(card(key).locator('.insight-result')).toContainText('listed by default')
      await expect(card(key).getByRole('link', {name: new RegExp(`^${title}:`)})).toBeVisible()
    }
    for (const key of UNLISTED) {
      await expect(card(key).locator('.insight-result')).toContainText('not listed by default')
    }
    await expect(
      card('repeated-selects').getByRole('link', {name: 'Repeated SELECTs: GET /api/insights/orders'})
    ).toHaveAttribute('href', /^\/bootui\/#\/runtime-insights\?insight=repeated-selects%3A/)

    // A card's link opens its finding, even one the default list leaves out.
    const lazy = card('lazy-sql-after-handler')
    const lazyHref = await lazy.getByRole('link').first().getAttribute('href')

    // The panel lists every default finding the buttons generated.
    await section.getByRole('link', {name: 'Open Runtime Insights'}).click()
    const main = page.locator('main')
    const listed = agentAttached
      ? [...LISTED, ['work-after-response', 'Work after the response', 'GET /api/insights/orders/after-response']]
      : LISTED
    for (const [, title, subject] of listed) {
      await expect(main.getByRole('heading', {name: title, exact: true})).toBeVisible({timeout: 15_000})
      await expect(main.getByText(subject).first()).toBeVisible()
    }

    await page.goto(String(lazyHref))
    await expect(page).toHaveURL(/#\/runtime-insights\?insight=lazy-sql-after-handler/)
    await expect(main.getByRole('heading', {name: 'SQL after the handler returned', exact: true})).toBeVisible()
    await expect(main.getByText('GET /api/insights/orders/report').first()).toBeVisible()
  })
})
