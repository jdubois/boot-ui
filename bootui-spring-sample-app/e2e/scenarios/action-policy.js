// @ts-check

const scannerIds = [
  'architecture',
  'memory',
  'rest-api',
  'spring',
  'database-advisor',
  'hibernate',
  'security',
  'pentesting',
  'vulnerabilities',
  'github'
]
const reason = 'BootUI is read-only via bootui.read-only=true'
const report = {
  scan: {status: 'SCANNED'},
  evidence: {usable: true, coverageComplete: true, limitations: []},
  severityCounts: [{severity: 'HIGH', count: 1}],
  results: [{severity: 'HIGH', status: 'VIOLATION'}],
  coverage: {status: 'COMPLETE'},
  dependencies: [
    {assessment: {queryComplete: true, detailAssessmentComplete: true}, vulnerabilities: [{severity: 'HIGH'}]}
  ]
}
const githubReport = {
  available: true,
  connected: true,
  status: 'CONNECTED',
  credential: {authenticated: true},
  securitySignals: ['Dependabot alerts', 'Code scanning alerts', 'Secret scanning alerts'].map((label) => ({
    label,
    status: 'AVAILABLE',
    count: 0
  }))
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {string[]} visible
 * @param {string[]} writable
 * @param {string | null} denial
 */
async function stubPolicies(page, visible, writable, denial = null) {
  const writes = []
  await page.route('**/bootui/api/panels', async (route) => {
    const response = await route.fetch()
    const body = await response.json()
    body.panels = body.panels.map((panel) =>
      scannerIds.includes(panel.id)
        ? {
            ...panel,
            enabled: true,
            available: visible.includes(panel.id),
            readOnly: !writable.includes(panel.id),
            readOnlyReason: writable.includes(panel.id) ? null : reason
          }
        : panel.id === 'overview'
          ? {...panel, readOnly: false}
          : panel
    )
    await route.fulfill({response, json: body})
  })
  await page.route('**/bootui/api/**', (route) => {
    const request = route.request()
    const id = new URL(request.url()).pathname.split('/')[3]
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      return scannerIds.includes(id) ? route.fulfill({json: id === 'github' ? githubReport : report}) : route.fallback()
    }
    writes.push(new URL(request.url()).pathname.replace('/bootui/api/', ''))
    return route.fulfill(
      denial
        ? {status: 403, json: {error: 'BootUI panel access denied', panel: id, reason: denial}}
        : {json: id === 'github' ? githubReport : report}
    )
  })
  return writes
}

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 */
export function registerActionPolicyTests(test, expect) {
  test.describe('Scorecard action policy', () => {
    for (const theme of ['light', 'dark']) {
      test(`read-only actions explain their policy without hiding cached results (${theme})`, async ({page}) => {
        const writes = await stubPolicies(page, ['architecture', 'memory', 'github'], [])
        await page.goto('/bootui/#/scorecard')
        await page.locator('.scanner-card').first().waitFor()
        await page.evaluate((value) => document.documentElement.setAttribute('data-bootui-theme', value), theme)
        const cards = page.locator('.scanner-card')
        await expect(cards).toHaveCount(3)
        await expect(page.locator('.overall-card')).toContainText('Average of 2 scores')
        const controls = page.locator('.overview-scores button[aria-describedby]')
        await expect(controls).toHaveCount(4)
        const ids = await controls.evaluateAll((elements) =>
          elements.map((element) => element.getAttribute('aria-describedby'))
        )
        expect(new Set(ids).size).toBe(4)
        for (const control of await controls.all()) {
          await expect(control).toBeDisabled()
          const description = page.locator(`[id="${await control.getAttribute('aria-describedby')}"]`)
          await expect(description).toHaveCount(1)
          await expect(description).toContainText(/read-only|No scanners can run/)
        }
        const card = cards.filter({hasText: 'Architecture'})
        await expect(card).toContainText('90')
        const link = card.getByRole('link', {name: /Open panel/})
        await link.focus()
        await expect(link).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(/#\/architecture$/)
        expect(writes).toEqual([])
      })
    }

    test('run-all skips read-only targets and uses only permitted action endpoints', async ({page}) => {
      const writes = await stubPolicies(page, ['architecture', 'memory', 'github'], ['memory'])
      await page.goto('/bootui/#/scorecard')
      const architecture = page.locator('.scanner-card', {hasText: 'Architecture'})
      await expect(architecture.getByRole('button', {name: /scan/i})).toBeDisabled()
      await expect(
        page.locator('.scanner-card', {hasText: 'GitHub'}).getByRole('button', {name: /Connect to GitHub|Refresh/})
      ).toBeDisabled()
      await page
        .locator('.overall-card')
        .getByRole('button', {name: /all scanners/})
        .click()
      await expect.poll(() => writes).toEqual(['memory/scan'])
      await expect(page.locator('.overall-card').getByRole('button', {name: /all scanners/})).toBeEnabled()
      await expect(architecture).toContainText('90')
    })

    for (const writable of [false, true]) {
      test(`GitHub-only run-all is ${writable ? 'enabled' : 'disabled'} by its own policy`, async ({page}) => {
        const writes = await stubPolicies(page, ['github'], writable ? ['github'] : [])
        await page.goto('/bootui/#/scorecard')
        const runAll = page.locator('.overall-card').getByRole('button', {name: /all scanners/})
        await expect(page.locator('.scanner-card')).toHaveCount(1)
        if (writable) {
          await expect(runAll).toBeEnabled()
          await runAll.click()
          await expect.poll(() => writes).toEqual(['github/refresh'])
          await expect(runAll).toBeEnabled()
        } else {
          await expect(runAll).toBeDisabled()
          expect(writes).toEqual([])
        }
      })
    }

    test('late policy denials stay readable and escaped without dropping the cached score', async ({page}) => {
      const denial = 'Panel is read-only via bootui.panels.architecture.read-only=true <img src=x onerror=alert(1)>'
      const writes = await stubPolicies(page, ['architecture'], ['architecture'], denial)
      await page.goto('/bootui/#/scorecard')
      const card = page.locator('.scanner-card', {hasText: 'Architecture'})
      await expect(card).toContainText('90')
      await card.getByRole('button', {name: /scan/i}).click()
      await expect(card).toContainText(denial)
      await expect(card).toContainText('HTTP 403')
      await expect(card).toContainText('90')
      await expect(card.locator('img')).toHaveCount(0)
      await expect(card.getByRole('link', {name: /Open panel/})).toBeVisible()
      expect(writes).toEqual(['architecture/scan'])
    })
  })

  test.describe('Audited action policy', () => {
    test('a late clear denial preserves traces and a cross-panel resume obeys its owner', async ({page, request}) => {
      const manifest = await (await request.get('/bootui/api/panels')).json()
      test.skip(
        !manifest.panels.some((panel) => panel.id === 'traces' && panel.available && panel.enabled !== false),
        'Traces are unavailable on this runtime.'
      )
      const writes = []
      await page.route('**/bootui/api/panels', (route) =>
        route.fulfill({
          json: {
            ...manifest,
            panels: manifest.panels.map((panel) =>
              panel.id === 'live-memory'
                ? {...panel, readOnly: true, readOnlyReason: reason}
                : panel.id === 'traces'
                  ? {...panel, readOnly: false}
                  : panel
            )
          }
        })
      )
      await page.route('**/bootui/api/live-memory/footprint', (route) => {
        if (route.request().method() === 'POST') writes.push('footprint')
        return route.fulfill({json: {active: true, components: [], warnings: []}})
      })
      await page.route('**/bootui/api/traces', (route) => {
        if (route.request().method() === 'DELETE') {
          writes.push('traces')
          return route.fulfill({status: 403, json: {error: 'BootUI panel access denied', panel: 'traces', reason}})
        }
        return route.fulfill({
          json: {
            enabled: true,
            retained: 1,
            capacity: 500,
            traces: [
              {
                traceId: '0123456789abcdef0123456789abcdef',
                rootSpanName: 'Retained trace',
                httpPath: '/retained',
                services: ['example'],
                startEpochNanos: 1700000000000000000,
                durationNanos: 1000000,
                spanCount: 1
              }
            ]
          }
        })
      })
      await page.goto('/bootui/#/traces')
      const row = page.locator('tbody tr', {hasText: '/retained'})
      await expect(row).toBeVisible()
      const resume = page.getByTestId('low-footprint-banner-off')
      await expect(resume).toBeDisabled()
      await expect(page.getByTestId('low-footprint-banner')).toContainText(reason)
      await page.getByRole('button', {name: 'Clear', exact: true}).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await dialog.getByRole('button', {name: 'Clear all', exact: true}).click()
      await expect(page.getByRole('alert')).toContainText(reason)
      await expect(page.getByRole('alert')).toContainText('HTTP 403')
      await expect(row).toBeVisible()
      expect(writes).toEqual(['traces'])
    })

    test('HTTP Probe shows a late policy reason rather than the generic denial discriminator', async ({
      page,
      request
    }) => {
      const manifest = await (await request.get('/bootui/api/panels')).json()
      test.skip(
        !manifest.panels.some((panel) => panel.id === 'http-probe' && panel.available && panel.enabled !== false),
        'HTTP Probe is unavailable on this runtime.'
      )
      const writes = []
      await page.route('**/bootui/api/http-probe', (route) => {
        writes.push(route.request().method())
        return route.fulfill({status: 403, json: {error: 'BootUI panel access denied', panel: 'http-probe', reason}})
      })
      await page.goto('/bootui/#/http-probe')
      await page.getByRole('button', {name: 'Send', exact: true}).click()
      await expect(page.locator('main')).toContainText(reason)
      await expect(page.locator('main')).toContainText('HTTP 403')
      expect(writes).toEqual(['POST'])
    })
  })
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {typeof import('@playwright/test').expect} expect
 * @param {string} baseUrl
 * @param {string} policyReason
 */
export async function assertScorecardReadOnly(page, expect, baseUrl, policyReason) {
  const writes = []
  const track = (request) => {
    if (request.url().includes('/bootui/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      writes.push(request.url())
    }
  }
  page.on('request', track)
  try {
    await page.goto(`${baseUrl}/bootui/#/scorecard`)
    const cards = page.locator('.scanner-card')
    await expect(cards.first()).toBeVisible()
    await expect(page.locator('.overall-card').getByRole('button', {name: /all scanners/})).toBeDisabled()
    for (const card of await cards.all()) {
      await expect(card.getByRole('button')).toBeDisabled()
      await expect(card).toContainText(policyReason)
      await expect(card.getByRole('link')).toBeVisible()
    }
    expect(writes).toEqual([])
  } finally {
    page.off('request', track)
  }
}
