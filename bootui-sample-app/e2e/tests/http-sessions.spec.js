// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('HTTP Sessions view', () => {
  test('lists sample Tomcat sessions with masked contents and clears attributes', async ({openView, page}) => {
    await page.goto('/')
    const sessionResponsePromise = page.waitForResponse(
      (response) => response.url().endsWith('/api/sample/session') && response.request().method() === 'GET'
    )
    await page.getByRole('button', {name: 'Create session data'}).click()
    const sessionResponse = await sessionResponsePromise
    expect(sessionResponse.ok()).toBeTruthy()
    const session = await sessionResponse.json()
    await expect(page.locator('#session-data-status')).toContainText('Added 5 attributes')

    await openView('http-sessions', 'HTTP Sessions')

    await expect(page.locator('main')).not.toContainText(session.sessionId)

    const sessionRow = page.locator('tbody tr', {hasText: 'current'}).first()
    await expect(sessionRow).toBeVisible({timeout: 15_000})
    await expect(sessionRow).toContainText('masked')

    await sessionRow.getByRole('button', {name: /Details/}).click()
    const details = page.locator('.http-sessions-detail').first()
    await expect(details).toContainText('sampleMessage')
    await expect(details).toContainText('sampleCount')
    await expect(details).toContainText('sampleClickCount')
    await expect(details).toContainText('sampleGeneratedAt')
    await expect(details).toContainText('apiToken')
    await expect(details).toContainText('******')
    await expect(details).not.toContainText('sample-secret-token')

    await sessionRow.getByRole('button', {name: 'Clear'}).click()
    await acceptConfirm(page)
    await expect(page.locator('.alert-success')).toContainText('Cleared 5 HTTP session attributes.')
  })
})
