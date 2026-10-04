// @ts-check
import {expect, test} from '@playwright/test'

/**
 * Runtime Insights on WebFlux (docs/PLAN-v2.md §5.5). WebFlux marks no request phases, so a route's breakdown names its
 * time around the calls as unattributed, and the observations needing a phase or a servlet say why they do not apply.
 */
test.describe('Runtime Insights on Spring WebFlux', () => {
  test('lists a route time breakdown and the checks that do not apply on WebFlux', async ({page, request, baseURL}) => {
    for (let i = 0; i < 7; i += 1) {
      const greeting = await request.get(`${baseURL}/api/greetings/Ada`)
      expect(greeting.ok()).toBeTruthy()
    }

    await page.goto('/bootui/#/runtime-insights')
    await expect(page.getByText('What this run did that no single panel shows.')).toBeVisible()

    const item = page.locator('.insight-item', {hasText: '/api/greetings/{name}'}).first()
    await expect(item).toBeVisible({timeout: 15_000})
    await item.click()
    await expect(page.locator('#insight-sentence')).toContainText('warm median')
    await expect(page.locator('.insight-detail')).toContainText('WebFlux marks no phases')
    await expect(page.locator('.insight-unrun')).toContainText('SQL after the handler returned')

    const comparison = page.locator('.insight-comparison')
    await expect(comparison.getByRole('heading', {name: 'Compared with the previous run'})).toBeVisible()
    await expect(comparison).toContainText(/Compared|Needs more traffic|Not comparable|No previous run/)

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

    const profile = page.locator('.insight-profile')
    await expect(profile.getByRole('heading', {name: 'Profile resources'})).toBeVisible()
    await expect(profile.getByRole('button', {name: /Profile (resources|again)/})).toBeEnabled()
  })
})
