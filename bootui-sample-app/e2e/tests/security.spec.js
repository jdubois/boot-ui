// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Security view', () => {

  test('lists filter chains including the ADMIN-only chain', async ({openView, page}) => {
    await openView('security', 'Spring Security')

    const chains = page.locator('.accordion-item')
    await expect.poll(async () => chains.count()).toBeGreaterThan(0)

    const adminChain = chains.filter({hasText: '/api/secure'}).first()
    await expect(adminChain).toBeVisible()

    // Authentication card mentions our in-memory users.
    await expect(page.locator('main')).toContainText('UserDetailsService')
    await expect(page.locator('main')).toContainText('InMemoryUserDetailsManager')
  })

  test('explain endpoint matches GET /api/secure against the ADMIN-only chain', async ({openView, page}) => {
    await openView('security', 'Spring Security')

    await page.locator('select.form-select').selectOption('GET')
    await page.getByPlaceholder('/api/example').fill('/api/secure')
    await page.getByRole('button', {name: /^Explain/}).click()

    const result = page.locator('.card', {hasText: /Matcher:|Filter pipeline/}).first()
    await expect(result).toBeVisible()
    await expect(result).toContainText('/api/secure')
    await expect(result).toContainText('BasicAuthenticationFilter')
  })
})
