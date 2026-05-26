// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Overview view', () => {

  test('renders the application, runtime and activation cards', async ({ openView }) => {
    const page = await openView('overview', 'Overview')

    await expect(page.locator('.topbar-title')).toContainText('bootui-sample')
    await expect(page.locator('.topbar-subtitle')).toContainText(/Spring Boot/)
    await expect(page.locator('.topbar-subtitle')).toContainText(/Java/)

    const appCard = page.locator('.app-map-card')
    await expect(appCard).toContainText('bootui-sample')
    await expect(appCard).toContainText(/Server port/)
    await expect(appCard).toContainText('SERVLET')
    await expect(appCard).toContainText(/Spring Boot/)

    const safetyCard = page.locator('.card', { hasText: 'Safety posture' })
    await expect(safetyCard).toContainText(/Development activation/)
    await expect(safetyCard).toContainText(/Loopback enforcement/)
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
