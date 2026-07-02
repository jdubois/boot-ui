// @ts-check
import {expect, test} from './fixtures.js'

function basicAuthHeader(user, password) {
  return {Authorization: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64')}
}

test.describe('Security Logs view (Quarkus)', () => {
  test('captures real authentication/authorization CDI security events with masked data', async ({openView, page}) => {
    // Three real security outcomes against the sample app's role-protected /api/secure resource
    // (@RolesAllowed("admin")), each producing a distinct CDI SecurityEvent that
    // QuarkusSecurityEventCapture records: a successful admin login, a wrong-password
    // authentication failure, and a valid-but-insufficient-role authorization failure.
    const success = await page.request.get('/api/secure', {headers: basicAuthHeader('admin', 'admin')})
    expect(success.ok()).toBeTruthy()

    const authnFailure = await page.request.get('/api/secure', {headers: basicAuthHeader('admin', 'wrong-password')})
    expect(authnFailure.status()).toBe(401)

    const authzFailure = await page.request.get('/api/secure', {headers: basicAuthHeader('developer', 'developer')})
    expect(authzFailure.status()).toBe(403)

    await openView('security-logs', 'Security Logs')

    // Quarkus-specific copy: no AuditEventRepository exists on this platform, so the panel explains
    // its CDI-security-event sourcing and the logout/session-event gap explicitly.
    await expect(page.locator('main')).toContainText(
      'Read-only Quarkus security events captured from CDI authentication/authorization events'
    )
    await expect(page.locator('main')).toContainText('This application returns up to 500 recent events per response')
    await expect(page.locator('main')).toContainText('bootui.security-logs.max-logs')
    await expect(page.locator('main')).toContainText('logout and session events have no Quarkus equivalent')

    // Type summaries reflect the real event classes Quarkus fires (class simple names, not Spring's
    // AUTHENTICATION_SUCCESS-style enum strings).
    await expect(page.locator('.badge', {hasText: /AuthenticationSuccessEvent \d+/})).toBeVisible()
    await expect(page.locator('.badge', {hasText: /AuthenticationFailureEvent \d+/})).toBeVisible()
    await expect(page.locator('.badge', {hasText: /AuthorizationFailureEvent \d+/})).toBeVisible()

    // Filter down to the developer authorization failure and inspect its real event data.
    await page.locator('#security-log-principal').fill('developer')
    await page.locator('#security-log-type').fill('AuthorizationFailureEvent')
    await page.getByRole('button', {name: 'Apply'}).click()

    const table = page.locator('table')
    await expect(table).toBeVisible()
    const developerRow = page.locator('tbody tr', {hasText: 'developer'}).first()
    await expect(developerRow).toBeVisible()
    // Columns are Time / Principal / Type / Event data -- scope to the Type cell specifically,
    // since the event-data cell also contains the raw property key
    // "...AuthorizationFailureEvent.CONTEXT" which would otherwise ambiguously match the same text.
    await expect(developerRow.locator('td').nth(2).locator('.badge')).toHaveClass(/bg-danger/)
    const developerData = developerRow.locator('td').nth(3)
    // The failed-role exception name is a plain class name, not a secret -- rendered unmasked.
    await expect(developerData).toContainText('failure=ForbiddenException')
    // The raw Quarkus event-property key names the class "AuthorizationFailureEvent", which contains
    // "auth" -- the shared SecretMasker keyword-pattern heuristic conservatively masks it.
    await expect(developerData.locator('.bi-shield-lock')).toBeVisible()
    await expect(developerData).toContainText('******')

    // Clear the principal filter and narrow to the authentication failure instead: Quarkus reports
    // an unresolved identity's principal as the literal string "anonymous" (distinct from the empty
    // principal an entirely credential-less request produces).
    await page.locator('#security-log-principal').fill('anonymous')
    await page.locator('#security-log-type').fill('AuthenticationFailureEvent')
    await page.getByRole('button', {name: 'Apply'}).click()

    const anonymousRow = page.locator('tbody tr', {hasText: 'anonymous'}).first()
    await expect(anonymousRow).toBeVisible()
    await expect(anonymousRow.locator('td').nth(2).locator('.badge')).toHaveClass(/bg-danger/)
    await expect(anonymousRow.locator('td').nth(3)).toContainText('failure=AuthenticationFailedException')
  })
})
