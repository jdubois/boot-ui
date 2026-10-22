// @ts-check
import {expect, test} from './fixtures.js'
import {expectedAdvisorScore} from '../scenarios/advisor-scoring.js'

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

  const findings = await (await request.get('/bootui/api/dismissed-findings')).json()
  for (const {ruleId, identity} of findings.dismissed ?? []) {
    await request.delete('/bootui/api/dismissed-findings', {headers, data: {ruleId, identity}})
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
async function expectIncompleteAssessment(page, report) {
  await expect(page.locator('.advisor-summary__metric--status .badge')).toHaveText('Results available')
  await expect(page.locator('.advisor-summary__value')).toHaveText(String(expectedAdvisorScore(report)))
  await expect(page.getByRole('img', {name: /Known-findings score: .*Scan notes available/})).toHaveCount(1)
  await expect(page.locator('details.advisor-summary__notes')).toHaveJSProperty('open', false)
  await expect(page.locator('.advisor-summary__assessment')).toHaveCount(0)
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

  test('dismisses and restores a real partial finding with exact score changes', async ({openView, page}) => {
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

    // Wait for the rule-results list to populate with at least one dismissible finding.
    const activeItems = page.locator('.list-group-item').filter({has: page.getByRole('button', {name: 'Dismiss'})})
    await expect(activeItems.first()).toBeVisible({timeout: 30_000})

    const findingsCard = page.locator('.advisor-summary__metric', {hasText: 'Advisor findings'})
    const before = await findingsCount(findingsCard)
    expect(before).toBeGreaterThan(0)
    // Nothing is dismissed yet, so the dismissed-note line is absent.
    await expect(page.locator('.advisor-summary__dismissed')).toHaveCount(0)

    expect(report.evidence.usable).toBe(true)
    await expectIncompleteAssessment(page, report)

    // Capture the rule id of the first active finding so we can target it precisely.
    const firstActive = activeItems.first()
    const ruleId = (await firstActive.locator('span.text-muted.small').first().innerText()).trim()
    expect(ruleId).not.toEqual('')

    // Match the rule by its id badge exactly: some rule descriptions cross-reference
    // other rule ids in prose, so a substring match on the whole item is not reliable.
    const activeItemFor = (id) => activeItems.filter({has: page.getByText(id, {exact: true})})
    const dismissedItemFor = (id) =>
      page.locator('.list-group-item.opacity-50').filter({has: page.getByText(id, {exact: true})})

    const dismissedReportResponse = page.waitForResponse(
      (response) => response.request().method() === 'GET' && /\/hibernate$/.test(new URL(response.url()).pathname)
    )
    await firstActive.getByRole('button', {name: 'Dismiss'}).click()
    const dismissedReport = await (await dismissedReportResponse).json()

    // Dismissal removes penalties but preserves observed evidence and partial qualification.
    const dismissedItem = dismissedItemFor(ruleId)
    await expect(dismissedItem).toBeVisible()
    await expect(page.getByText('— not counted in score')).toBeVisible()
    await expect(page.locator('.advisor-summary__dismissed')).toContainText(
      '1 dismissed rule(s) excluded from active findings'
    )
    await expect.poll(async () => findingsCount(findingsCard)).toBe(before - 1)
    await expectIncompleteAssessment(page, dismissedReport)
    expect(expectedAdvisorScore(dismissedReport)).toBeGreaterThanOrEqual(expectedAdvisorScore(report))

    // The rule is no longer offered as an active (dismissible) finding.
    await expect(activeItemFor(ruleId)).toHaveCount(0)

    // Restoring returns the original penalties while retaining partial coverage.
    await dismissedItem.getByRole('button', {name: 'Restore'}).click()

    await expect(dismissedItemFor(ruleId)).toHaveCount(0)
    await expect(page.locator('.advisor-summary__dismissed')).toHaveCount(0)
    await expect.poll(async () => findingsCount(findingsCard)).toBe(before)
    await expectIncompleteAssessment(page, report)
    await expect(activeItemFor(ruleId)).toHaveCount(1)
  })

  test('dismisses and restores one finding of a rule without touching its other findings', async ({
    openView,
    page,
    request
  }) => {
    await openView('hibernate', 'Hibernate')
    const scanResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && /\/hibernate\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()
    const report = await (await scanResponse).json()
    const rule = report.results.find((result) => result.violationCount >= 2)
    expect(rule, 'a rule with at least two findings').toBeTruthy()
    const ruleCard = page
      .locator('.list-group-item')
      .filter({has: page.getByText(rule.id, {exact: true})})
      .first()
    await expect(ruleCard).toBeVisible({timeout: 30_000})
    const findingsMetric = page.locator('.advisor-summary__metric', {hasText: 'Advisor findings'})
    const rulesBefore = await findingsCount(findingsMetric)

    await ruleCard.getByRole('button', {name: `View violations for ${rule.id}`}).click()
    const firstDismiss = ruleCard
      .getByRole('button', {name: new RegExp(`^Dismiss finding \\d+ of ${rule.id}$`)})
      .first()
    await expect(firstDismiss).toBeVisible()
    const rows = ruleCard.locator('.advisor-rule-violations li')
    const rowsBefore = await rows.count()
    expect(rowsBefore).toBe(rule.violationCount)

    await firstDismiss.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toContainText('changes score penalties, not application safety')
    await dialog.getByRole('button', {name: 'Dismiss finding'}).click()

    // The report is recomputed on the next read: one finding fewer, the rule and the others still active.
    await expect(ruleCard).toContainText(
      `${rule.violationCount - 1} ${rule.violationCount - 1 === 1 ? 'finding' : 'findings'} found for this rule.`
    )
    await expect(ruleCard).toContainText('1 more dismissed one by one.')
    await expect(page.locator('[data-testid="suppressed-summary"]')).toContainText('1 finding dismissed one by one')
    await expect(ruleCard.locator('[data-testid="suppressed-finding"]')).toHaveCount(1)
    await expect(ruleCard.locator('.advisor-rule-violations li')).toHaveCount(rowsBefore)
    await expect.poll(async () => findingsCount(findingsMetric)).toBe(rulesBefore)

    const listed = await (await request.get('/bootui/api/dismissed-findings')).json()
    expect(listed.dismissed).toHaveLength(1)

    // The dismissed finding is listed, collapsed, under the rule list, and restoring it brings every count back.
    const dismissedList = page.getByTestId('dismissed-findings')
    await expect(dismissedList).toContainText('Dismissed findings (1)')
    await expect(dismissedList.locator('input')).toBeHidden()
    await ruleCard.getByRole('button', {name: new RegExp(`^Restore finding \\d+ of ${rule.id}$`)}).click()

    await expect(ruleCard).toContainText(
      `${rule.violationCount} ${rule.violationCount === 1 ? 'finding' : 'findings'} found for this rule.`
    )
    await expect(page.locator('[data-testid="suppressed-summary"]')).toHaveCount(0)
    await expect(page.getByTestId('dismissed-findings')).toHaveCount(0)
    expect((await (await request.get('/bootui/api/dismissed-findings')).json()).dismissed).toHaveLength(0)
  })

  test('keeps a note on a dismissed finding out of the report and lets a rule-level dismissal still work', async ({
    openView,
    page,
    request
  }) => {
    await openView('hibernate', 'Hibernate')
    const scanResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && /\/hibernate\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()
    const report = await (await scanResponse).json()
    const rule = report.results.find((result) => result.violationCount >= 1)
    const ruleCard = page
      .locator('.list-group-item')
      .filter({has: page.getByText(rule.id, {exact: true})})
      .first()
    await ruleCard.getByRole('button', {name: `View violations for ${rule.id}`}).click()
    await ruleCard
      .getByRole('button', {name: new RegExp(`^Dismiss finding \\d+ of ${rule.id}$`)})
      .first()
      .click()
    await page.getByRole('dialog').getByRole('button', {name: 'Dismiss finding'}).click()

    const dismissedList = page.getByTestId('dismissed-findings')
    await dismissedList.locator('summary').click()
    const note = dismissedList.locator('input[type="text"]').first()
    await note.fill('Intentional: legacy "table" # kept')
    await dismissedList
      .getByRole('button', {name: /^Save note/})
      .first()
      .click()
    await expect(dismissedList.getByRole('button', {name: /^Save note/}).first()).toBeDisabled()

    const listed = await (await request.get('/bootui/api/dismissed-findings')).json()
    expect(listed.dismissed[0].note).toBe('Intentional: legacy "table" # kept')
    const cached = await (await request.get('/bootui/api/hibernate')).text()
    expect(cached).not.toContain('Intentional')

    // Rule-level dismissal keeps its meaning and takes precedence over the dismissed finding.
    await ruleCard.getByRole('button', {name: 'Dismiss'}).first().click()
    await expect(
      page.locator('.list-group-item.opacity-50').filter({has: page.getByText(rule.id, {exact: true})})
    ).toBeVisible()
  })
})
