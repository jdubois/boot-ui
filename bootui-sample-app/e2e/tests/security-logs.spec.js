// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Security Logs view', () => {
  test('lists Spring Boot audit events with masked sensitive data', async ({openView, page}) => {
    await openView('security-logs', 'Security Logs')

    await expect(page.getByLabel('Auto-refresh')).toBeVisible()
    await expect(page.locator('main')).toContainText('This application returns up to 500 recent audit events')
    await expect(page.locator('main')).toContainText('bootui.security-logs.max-logs')
    await expect(page.getByText('AUTHENTICATION_SUCCESS').first()).toBeVisible()
    await expect(page.getByText('AUTHORIZATION_DENIED', {exact: true})).toBeVisible()
    await expect(page.locator('main')).toContainText('sessionId=******')
    await expect(page.locator('main')).toContainText('remoteAddress=******')
  })
})
