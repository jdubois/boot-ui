// @ts-check
import {expect, test} from './fixtures.js'

test.describe('DevTools view', () => {
  test('renders LiveReload and restart status cards without triggering restart', async ({openView, page}) => {
    await openView('devtools', 'DevTools')

    const liveReloadCard = page.locator('.card', {hasText: 'Trigger LiveReload'})
    const restartCard = page.locator('.card', {hasText: 'Restart application'})

    await expect(liveReloadCard).toBeVisible()
    await expect(restartCard).toBeVisible()
    await expect(liveReloadCard.locator('.badge')).toContainText(/Available|Unavailable/)
    await expect(restartCard.locator('.badge')).toContainText(/Available|Unavailable|Pending/)
    await expect(page.getByRole('button', {name: /Restart app/})).toBeVisible()
    await expect(page.getByText('Restart interrupts the current JVM context')).toBeVisible()
  })

  test('triggering LiveReload shows action feedback and keeps restart guarded', async ({openView, page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/devtools',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            restartAvailable: false,
            restartUnavailableReason: 'Spring Boot DevTools Restarter is not initialized.',
            restartPending: false,
            liveReloadAvailable: true,
            liveReloadPort: 35729,
            liveReloadConnections: 2,
            liveReloadUnavailableReason: null
          })
        })
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/devtools/livereload',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            action: 'livereload',
            status: 'triggered',
            message: 'LiveReload command sent to 2 connected clients.'
          })
        })
      }
    )

    await openView('devtools', /^DevTools/)

    await expect(page.getByText('LiveReload port:')).toBeVisible()
    await expect(page.getByText('Connected clients:')).toBeVisible()
    await expect(page.getByRole('button', {name: /Restart app/})).toBeDisabled()

    await page.getByRole('button', {name: /Trigger LiveReload/}).click()
    await expect(page.locator('.alert-success')).toContainText('LiveReload command sent')
  })

  test('warns when LiveReload has no connected clients', async ({openView, page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/devtools',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            restartAvailable: false,
            restartUnavailableReason: 'Spring Boot DevTools Restarter is not initialized.',
            restartPending: false,
            liveReloadAvailable: true,
            liveReloadPort: 35729,
            liveReloadConnections: 0,
            liveReloadUnavailableReason: null
          })
        })
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/devtools/livereload',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            action: 'livereload',
            status: 'no_clients',
            message: 'LiveReload command sent, but no browsers are connected on port 35729, so nothing reloaded.'
          })
        })
      }
    )

    await openView('devtools', /^DevTools/)

    const liveReloadCard = page.locator('.card', {hasText: 'Trigger LiveReload'})
    await expect(liveReloadCard).toContainText('No browsers are connected to the LiveReload server')

    await page.getByRole('button', {name: /Trigger LiveReload/}).click()
    await expect(page.locator('.alert-warning')).toContainText('no browsers are connected')
  })
})
