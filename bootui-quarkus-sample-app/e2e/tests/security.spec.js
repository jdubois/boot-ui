// @ts-check
import {expect, test} from './fixtures.js'
import {expectPartialAdvisorScore} from '../../../bootui-spring-sample-app/e2e/scenarios/advisor-scoring.js'

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

    await expect(page.locator('.advisor-summary__metric--status .badge')).toHaveText('Incomplete', {timeout: 20_000})
    await expectPartialAdvisorScore(page, expect)
    await expect(page.locator('main')).toContainText('Heuristic Quarkus rules')
    await expect(page.locator('main')).toContainText('Permission policies')
  })

  test('runs security checks and finds the sample app misconfigurations', async ({openView, page}) => {
    await openView('security', 'Security')

    const scanResponse = page.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/security\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run security checks'}).click()
    const response = await scanResponse
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.scan.status).toBe('PARTIAL')
    expect(report.scan.message).toBeTruthy()
    await expect(page.locator('.advisor-summary__metric--status .badge')).toHaveText('Incomplete')
    expect(report.assessmentEvidence).toEqual({usable: true, incomplete: true})
    await expectPartialAdvisorScore(page, expect, report.severityCounts)

    // quarkus.http.auth.basic=true with insecure-requests=enabled (the sample's default).
    const basicAuthRow = page.locator('.list-group-item', {hasText: 'QS-AUTH-002'})
    await expect(basicAuthRow).toContainText('Basic authentication without TLS')

    // Embedded demo credentials are explicitly configured as plain text.
    const plainTextPasswordRow = page.locator('.list-group-item', {hasText: 'QS-AUTH-013'})
    await expect(plainTextPasswordRow).toContainText('Embedded users stored with plain-text passwords')

    // quarkus.http.cors.origins=* combined with access-control-allow-credentials=true.
    const corsRow = page.locator('.list-group-item', {hasText: 'QS-CORS-002'})
    await expect(corsRow).toContainText('CORS wildcard origin with credentials')
  })
})
