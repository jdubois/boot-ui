// @ts-check
import {expect, test} from './fixtures.js'

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
 * @param {import('@playwright/test').Locator} card
 * @returns {Promise<number>}
 */
async function findingsCount(card) {
  return Number.parseInt((await card.locator('.display-6').innerText()).trim(), 10) || 0
}

/**
 * Reads the numeric advisor score rendered by the shared AdvisorScoreCard.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<number>}
 */
async function scoreValue(page) {
  return Number.parseInt((await page.locator('.advisor-score-gauge__value').innerText()).trim(), 10)
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

  test('dismisses a finding server-side so it leaves the score, then restores it', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()

    // Wait for the rule-results list to populate with at least one dismissible finding.
    const activeItems = page.locator('.list-group-item').filter({has: page.getByRole('button', {name: 'Dismiss'})})
    await expect(activeItems.first()).toBeVisible({timeout: 30_000})

    const findingsCard = page.locator('.card', {hasText: 'Advisor findings'})
    const before = await findingsCount(findingsCard)
    expect(before).toBeGreaterThan(0)
    // Nothing is dismissed yet, so the "N dismissed" subline is absent.
    await expect(findingsCard).not.toContainText('dismissed')

    // The advisor score card renders once a scan has produced findings.
    await expect(page.locator('.advisor-score-card')).toBeVisible()
    const scoreBefore = await scoreValue(page)

    // Capture the rule id of the first active finding so we can target it precisely.
    const firstActive = activeItems.first()
    const ruleId = (await firstActive.locator('span.text-muted.small').first().innerText()).trim()
    expect(ruleId).not.toEqual('')

    // Match the rule by its id badge exactly: some rule descriptions cross-reference
    // other rule ids in prose, so a substring match on the whole item is not reliable.
    const activeItemFor = (id) => activeItems.filter({has: page.getByText(id, {exact: true})})
    const dismissedItemFor = (id) =>
      page.locator('.list-group-item.opacity-50').filter({has: page.getByText(id, {exact: true})})

    await firstActive.getByRole('button', {name: 'Dismiss'}).click()

    // The rule moves into the "Dismissed rules" list, the score subtracts it, and
    // the dismissed subline appears. Because we cleared dismissals first, it reads "1 dismissed".
    const dismissedItem = dismissedItemFor(ruleId)
    await expect(dismissedItem).toBeVisible()
    await expect(page.getByText('— not counted in score')).toBeVisible()
    await expect(findingsCard).toContainText('1 dismissed')
    await expect.poll(async () => findingsCount(findingsCard)).toBe(before - 1)

    // Dismissing a finding removes its weighted penalty. The score therefore rises or
    // holds: the sample app's Hibernate model has enough findings to clamp the raw score
    // at 0, so dropping a single rule can leave the displayed score unchanged. The strict
    // "dismiss raises the score" guarantee is unit-tested in Spring.test.js against a
    // non-clamped report; here we assert it never *decreases* and that the score card
    // notes the exclusion. The exact-restore check below is what pins the wiring down.
    await expect(page.getByText('excluded from this score')).toBeVisible()
    await expect.poll(async () => scoreValue(page)).toBeGreaterThanOrEqual(scoreBefore)

    // The rule is no longer offered as an active (dismissible) finding.
    await expect(activeItemFor(ruleId)).toHaveCount(0)

    // Restoring returns the finding to the active list and the score to its original value.
    await dismissedItem.getByRole('button', {name: 'Restore'}).click()

    await expect(dismissedItemFor(ruleId)).toHaveCount(0)
    await expect(findingsCard).not.toContainText('dismissed')
    await expect(page.getByText('excluded from this score')).toHaveCount(0)
    await expect.poll(async () => findingsCount(findingsCard)).toBe(before)
    await expect.poll(async () => scoreValue(page)).toBe(scoreBefore)
    await expect(activeItemFor(ruleId)).toHaveCount(1)
  })
})
