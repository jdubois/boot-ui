// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Security panel replaces Spring's Spring-Security-coupled advisor with a Quarkus-native ruleset
 * (Elytron/OIDC, quarkus.http.auth.permission.*, CORS, @RolesAllowed - see docs/QUARKUS-CHECKS.md). It
 * shares the Security.vue view with Spring, switching copy via the panels manifest `platform` field. The
 * sample app's application.properties intentionally enables HTTP Basic auth without TLS redirection and a
 * wildcard-CORS-with-credentials combo, so a real scan has deterministic, high-severity findings.
 */
test.describe('Security advisor (Quarkus)', () => {
  test('renders the Quarkus-specific security copy', async ({openView, page}) => {
    await openView('security', 'Security')

    await page.getByRole('button', {name: 'Run security checks'}).click()

    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})
    await expect(page.locator('main')).toContainText('Heuristic Quarkus rules')
    await expect(page.locator('main')).toContainText('Permission policies')
  })

  test('runs security checks and finds the sample app misconfigurations', async ({openView, page}) => {
    await openView('security', 'Security')

    await page.getByRole('button', {name: 'Run security checks'}).click()
    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})

    // quarkus.http.auth.basic=true with insecure-requests=enabled (the sample's default).
    const basicAuthRow = page.locator('.list-group-item', {hasText: 'QS-AUTH-002'})
    await expect(basicAuthRow).toContainText('Basic authentication without TLS')

    // quarkus.http.cors.origins=* combined with access-control-allow-credentials=true.
    const corsRow = page.locator('.list-group-item', {hasText: 'QS-CORS-002'})
    await expect(corsRow).toContainText('CORS wildcard origin with credentials')
  })
})
