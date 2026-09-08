// @ts-check
import {expect, test} from './fixtures.js'
import {expectPartialAdvisorScore, expectedAdvisorScore} from '../scenarios/advisor-scoring.js'

/**
 * Removes every dismissed advisor rule so each test starts and ends from a
 * clean, deterministic state. Dismissals are persisted server-side in
 * `.bootui/boot-ui.yml`, so without this a crashed run could leave a
 * rule dismissed and skew later assertions.
 *
 * The sample app puts Spring Security CSRF protection in front of the BootUI
 * endpoints, so state-changing calls must echo the `XSRF-TOKEN` cookie back in
 * the `X-XSRF-TOKEN` header (exactly what the browser UI does).
 *
 * @param {import('@playwright/test').APIRequestContext} request
 */
async function clearDismissedRules(request) {
  // A GET primes the XSRF-TOKEN cookie in the request context's cookie jar.
  await request.get('/bootui/api/overview')
  const {cookies} = await request.storageState()
  const xsrf = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')
  const headers = xsrf ? {'X-XSRF-TOKEN': xsrf.value} : {}

  const response = await request.get('/bootui/api/dismissed-rules')
  const body = await response.json()
  for (const id of body.dismissed ?? []) {
    await request.delete(`/bootui/api/dismissed-rules/${encodeURIComponent(id)}`, {headers})
  }
}

/**
 * @param {import('@playwright/test').Locator} metric
 * @returns {Promise<number>}
 */
async function findingsCount(metric) {
  return Number.parseInt((await metric.locator('dd').innerText()).trim(), 10) || 0
}

/**
 * @param {import('@playwright/test').Page} page
 */
async function expectIncompleteAssessment(page, counts) {
  await expect(page.locator('.advisor-summary__metric--status .badge')).toHaveText('Incomplete')
  await expectPartialAdvisorScore(page, expect, counts)
}

test.describe('Advisor rule dismiss/restore', () => {
  // The dismiss/restore mechanism is shared by every advisor panel (Architecture,
  // REST API, Spring, Hibernate, Memory, Security). It is exercised here against
  // the Hibernate Advisor because the sample app's intentionally imperfect entity
  // model guarantees several findings to dismiss; the flow is identical elsewhere.
  test.beforeEach(async ({request}) => {
    await clearDismissedRules(request)
  })

  test.afterEach(async ({request}) => {
    await clearDismissedRules(request)
  })

  test('dismisses and restores a real finding while preserving partial qualification', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    const scanResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && /\/hibernate\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()
    const response = await scanResponse
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.scan.status).toBe('PARTIAL')
    expect(report.scan.message).toBeTruthy()
    expect(report.assessmentEvidence).toEqual({usable: true, incomplete: true})

    // Wait for the rule-results list to populate with at least one dismissible finding.
    const activeItems = page.locator('.list-group-item').filter({has: page.getByRole('button', {name: 'Dismiss'})})
    await expect(activeItems.first()).toBeVisible({timeout: 30_000})

    const findingsCard = page.locator('.advisor-summary__metric', {hasText: 'Advisor findings'})
    const before = await findingsCount(findingsCard)
    expect(before).toBeGreaterThan(0)
    // Nothing is dismissed yet, so the dismissed-note line is absent.
    await expect(page.locator('.advisor-summary__dismissed')).toHaveCount(0)

    await expectIncompleteAssessment(page, report.severityCounts)

    // Capture the rule id of the first active finding so we can target it precisely.
    const firstActive = activeItems.first()
    const ruleId = (await firstActive.locator('span.text-muted.small').first().innerText()).trim()
    expect(ruleId).not.toEqual('')

    // Match the rule by its id badge exactly: some rule descriptions cross-reference
    // other rule ids in prose, so a substring match on the whole item is not reliable.
    const activeItemFor = (id) => activeItems.filter({has: page.getByText(id, {exact: true})})
    const dismissedItemFor = (id) =>
      page.locator('.list-group-item.opacity-50').filter({has: page.getByText(id, {exact: true})})

    const dismissedResponse = page.waitForResponse(
      (response) => response.request().method() === 'GET' && /\/hibernate$/.test(new URL(response.url()).pathname)
    )
    await firstActive.getByRole('button', {name: 'Dismiss'}).click()
    const dismissedReport = await (await dismissedResponse).json()
    expect(dismissedReport.assessmentEvidence).toEqual(report.assessmentEvidence)
    expect(expectedAdvisorScore(dismissedReport.severityCounts)).toBeGreaterThanOrEqual(
      expectedAdvisorScore(report.severityCounts)
    )

    // Dismissal changes the active findings, not the incomplete assessment's eligibility.
    const dismissedItem = dismissedItemFor(ruleId)
    await expect(dismissedItem).toBeVisible()
    await expect(page.getByText('— not counted in score')).toBeVisible()
    await expect(page.locator('.advisor-summary__dismissed')).toContainText(
      '1 dismissed rule(s) excluded from this score'
    )
    await expect.poll(async () => findingsCount(findingsCard)).toBe(before - 1)
    await expectIncompleteAssessment(page, dismissedReport.severityCounts)

    // The rule is no longer offered as an active (dismissible) finding.
    await expect(activeItemFor(ruleId)).toHaveCount(0)

    // Restoring returns both the finding and its original score penalty.
    await dismissedItem.getByRole('button', {name: 'Restore'}).click()

    await expect(dismissedItemFor(ruleId)).toHaveCount(0)
    await expect(page.locator('.advisor-summary__dismissed')).toHaveCount(0)
    await expect.poll(async () => findingsCount(findingsCard)).toBe(before)
    await expectIncompleteAssessment(page, report.severityCounts)
    await expect(activeItemFor(ruleId)).toHaveCount(1)
  })
})
