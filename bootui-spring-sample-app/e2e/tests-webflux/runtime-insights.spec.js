// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * @param {string} url
 * @param {string} route
 */
function matchesCodePathsRoute(url, route) {
  const destination = new URL(new URL(url, 'http://localhost').hash.slice(1), 'http://localhost')
  return destination.pathname === '/code-paths' && destination.searchParams.get('route') === route
}

/**
 * Runtime Insights on WebFlux (docs/PLAN-v2.md §5.5). WebFlux marks no request phases, so a route's breakdown names its
 * time around the calls as unattributed, and the observations needing a phase or a servlet say why they do not apply.
 */
test.describe('Runtime Insights on Spring WebFlux', () => {
  test('decodes Code Paths route identity independently of query encoding', () => {
    const route = 'GET /api/greetings/{name}'
    for (const query of ['GET+/api/greetings/{name}', 'GET+%2Fapi%2Fgreetings%2F%7Bname%7D']) {
      expect(matchesCodePathsRoute(`#/code-paths?route=${query}`, route)).toBe(true)
    }
    const reservedRoute = 'GET /api/greetings/{name}?label=A+B&C=#fragment%25'
    expect(
      matchesCodePathsRoute(
        `http://localhost/bootui/#/code-paths?route=${encodeURIComponent(reservedRoute)}`,
        reservedRoute
      )
    ).toBe(true)
    expect(matchesCodePathsRoute(`#/runtime-insights?route=${encodeURIComponent(route)}`, route)).toBe(false)
    expect(matchesCodePathsRoute('#/code-paths?route=GET+/api/greetings/other', route)).toBe(false)
    expect(matchesCodePathsRoute('#/code-paths', route)).toBe(false)
  })

  test('lists a route time breakdown and the checks that do not apply on WebFlux', async ({
    page,
    request,
    baseURL,
    agentAttached
  }) => {
    for (let i = 0; i < 7; i += 1) {
      const greeting = await request.get(`${baseURL}/api/greetings/Ada`)
      expect(greeting.ok()).toBeTruthy()
    }

    // ?all=1 is Show all routes (M4-19): a short route without phases is reachable but not listed by default.
    await page.goto('/bootui/#/runtime-insights?all=1')
    await expect(page.getByText('What this run did that no single panel shows.')).toBeVisible()

    const item = page.locator('.insight-item', {hasText: '/api/greetings/{name}'}).first()
    await expect(item).toBeVisible({timeout: 15_000})
    await item.click()
    await expect(page.locator('#insight-sentence')).toContainText('warm median')
    await expect(page.locator('.insight-detail')).toContainText('WebFlux marks no phases')

    const deepDives = page.locator('.insight-performance-deep-dives')
    await expect(deepDives.getByRole('heading', {name: 'Performance deep dives'})).toBeVisible()
    const codePathsLink = deepDives.getByRole('link', {name: /Open GET .* in Code Paths/})
    if (agentAttached) {
      await expect
        .poll(async () => {
          const href = await codePathsLink.getAttribute('href')
          return href !== null && matchesCodePathsRoute(href, 'GET /api/greetings/{name}')
        })
        .toBe(true)
    } else {
      await expect(codePathsLink).toHaveCount(0)
      await expect(deepDives.locator('.insight-agent-tip')).toContainText('method-level timing')
      await expect(deepDives.getByRole('link', {name: 'Set up the Java agent'})).toHaveAttribute(
        'href',
        /#\/java-agent$/
      )
    }
    await deepDives.getByRole('button', {name: 'Open the JFR profile tab'}).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('tab', {name: 'JFR profile'})).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tab', {name: 'JFR profile'})).toBeFocused()
    await expect(page.locator('.insight-profile-running')).toHaveCount(0)
    await expect(page.locator('.insight-profile').getByRole('button', {name: /Profile resources/})).toBeVisible()
    await page.getByRole('tab', {name: /^Findings/}).click()
    if (agentAttached) {
      await codePathsLink.click()
      await expect(page).toHaveURL((url) => matchesCodePathsRoute(url.href, 'GET /api/greetings/{name}'))
      await expect(page.locator('.code-paths-route', {hasText: 'GET /api/greetings/{name}'})).toHaveAttribute(
        'aria-expanded',
        'true'
      )
      await expect(page.locator('.code-paths-route-detail')).toBeVisible()
      await page.goBack()
    }

    await page.getByRole('tab', {name: /^Coverage & limits/}).click()
    await expect(page.locator('.insight-unrun')).toContainText('SQL after the handler returned')

    await page.getByRole('tab', {name: /^Changes/}).click()
    const comparison = page.locator('.insight-comparison')
    await expect(comparison.getByRole('heading', {name: 'Compared with the previous run'})).toBeVisible()
    await expect(comparison).toContainText(/Compared|Needs more traffic|Not comparable|No previous run/)

    await page.getByRole('tab', {name: /^Change impact/}).click()
    const impact = page.locator('.insight-impact')
    const symbol = impact.getByRole('combobox', {name: /Symbol to check/})
    await symbol.fill('noSuchSymbolAnywhere')
    await impact.getByRole('button', {name: 'Check impact'}).click()
    await expect(impact.locator('.insight-impact-reason')).toBeVisible()

    // Typing suggests what the run's model holds, each with its kind; picking one checks exactly that node.
    await symbol.fill('/api/greetings')
    const route = impact.getByRole('option', {name: /^GET \/api\/greetings\/\{name\} route$/})
    await expect(route).toBeVisible()
    await route.click()
    await expect(symbol).toHaveValue('GET /api/greetings/{name}')
    await expect(impact.locator('[data-list="observed"]')).toContainText('GET /api/greetings/{name}')

    await page.getByRole('tab', {name: /^JFR profile/}).click()
    const profile = page.locator('.insight-profile')
    await expect(profile.getByRole('heading', {name: 'Profile resources'})).toBeVisible()
    await expect(profile.getByRole('button', {name: /Profile (resources|again)/})).toBeEnabled()
  })
})
