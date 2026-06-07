// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Security Advisor view', () => {
  test('runs the read-only scan and reports rules, chains, and findings', async ({openView, page}) => {
    await openView('security', 'Security')

    // The read-only disclaimer renders once the report loads. The pre-scan empty state is not
    // asserted because the advisor caches the last scan, so a reused or retried server may
    // already have scan data on mount.
    await expect(page.locator('main')).toContainText('Heuristic Spring Security rules.')

    await page.getByRole('button', {name: 'Run security checks'}).click()

    // After the scan the severity card is populated and the empty state disappears.
    await expect(page.getByText('No Security Advisor data yet')).toHaveCount(0, {timeout: 30_000})

    const rulesEvaluated = page.locator('.card', {hasText: 'Rules evaluated'}).locator('.display-6')
    await expect
      .poll(async () => Number.parseInt((await rulesEvaluated.innerText()).trim(), 10) || 0, {timeout: 15_000})
      .toBeGreaterThan(0)

    const filterChainsAnalyzed = page.locator('.card', {hasText: 'Filter chains analysed'}).locator('.display-6')
    await expect
      .poll(async () => Number.parseInt((await filterChainsAnalyzed.innerText()).trim(), 10) || 0)
      .toBeGreaterThan(0)

    // The Spring Security filter chains discovered by the scan are listed (the
    // summary "Filter chains analysed" card is excluded via its display number).
    const filterChainsCard = page
      .locator('.card', {hasText: 'Filter chains'})
      .filter({hasNot: page.locator('.display-6')})
    await expect(filterChainsCard.locator('li.font-monospace').first()).toBeVisible()
    await expect(filterChainsCard).toContainText('/api/secure')

    // The scan-status card surfaces a status badge for the completed scan.
    const scanStatusCard = page.locator('.card', {hasText: 'Scan status'}).first()
    await expect(scanStatusCard.locator('.badge').first()).toBeVisible()

    // The rule-results section renders once the scan completes.
    await expect(page.getByText('Rule results')).toBeVisible()
  })
})
