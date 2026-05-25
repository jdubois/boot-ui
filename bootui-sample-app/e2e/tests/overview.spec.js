// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Overview view', () => {

  test('renders the application, runtime and activation cards', async ({ openView }) => {
    const page = await openView('overview', 'Overview')

    const appCard = page.locator('.card', { hasText: 'Application' })
    await expect(appCard).toContainText('bootui-sample')
    await expect(appCard).toContainText(/Server port/)
    await expect(appCard).toContainText('SERVLET')

    const runtimeCard = page.locator('.card', { hasText: 'Runtime' })
    await expect(runtimeCard).toContainText(/Spring Boot/)
    await expect(runtimeCard).toContainText(/Java/)

    const activationCard = page.locator('.card', { hasText: 'Activation' })
    await expect(activationCard.locator('.badge', { hasText: 'Enabled' })).toBeVisible()
    await expect(activationCard).toContainText(/Loopback-only access enforced/i)
  })

  test('refresh button re-fetches the overview', async ({ openView, page }) => {
    await openView('overview', 'Overview')
    const requestPromise = page.waitForResponse(res =>
      res.url().endsWith('/bootui/api/overview') && res.request().method() === 'GET')
    await page.getByRole('button', { name: /Refresh/ }).click()
    const response = await requestPromise
    expect(response.ok()).toBeTruthy()
  })
})
