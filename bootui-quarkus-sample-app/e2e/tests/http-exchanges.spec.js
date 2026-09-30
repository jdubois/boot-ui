// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP Exchanges view (Quarkus)', () => {
  test('shows recent sample app requests', async ({openView, page}) => {
    const apiResponse = await page.request.get('/api/sample/hello')
    expect(apiResponse.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')

    await expect(page.locator('.http-exchanges-table')).toContainText('/api/sample/hello', {timeout: 15_000})
    await expect(page.locator('.http-exchanges-table')).toContainText('GET')
    await expect(page.locator('.http-exchanges-table')).toContainText('200')

    const sampleRow = page.locator('.http-exchanges-table tbody tr', {hasText: '/api/sample/hello'}).first()
    const detailsButton = sampleRow.locator('.http-exchanges-detail-toggle')
    await expect(detailsButton).toBeVisible()
    await detailsButton.click()
    await expect(page.locator('.http-exchanges-detail').first()).toContainText('Request headers')
  })

  test('states the failure-preserving retention window', async ({openView, page}) => {
    const apiResponse = await page.request.get('/api/sample/hello')
    expect(apiResponse.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')

    const retention = page.locator('.capture-retention')
    await expect(retention).toContainText('Retention:', {timeout: 15_000})
    await expect(retention).toContainText(/keeping \d+ of 200 exchanges/)
    await expect(retention).toContainText(/of 50 reserved for recent 5xx or slow exchanges \(slow means ≥ 1,?000 ms\)/)
    await expect(page.locator('table')).not.toContainText('/bootui/api/http-exchanges')
  })

  test('shows security failures recorded before the Quarkus security filter short-circuits', async ({
    openView,
    page
  }) => {
    const secureResponse = await page.request.get('/api/secure')
    expect(secureResponse.status()).toBe(401)

    await openView('http-exchanges', 'HTTP Exchanges')

    await expect(page.locator('.http-exchanges-table')).toContainText('/api/secure', {timeout: 15_000})
    await expect(page.locator('.http-exchanges-table')).toContainText('401')
  })

  test('copies a safe cURL template without values, secrets, or a replayed request', async ({
    browserName,
    context,
    openView,
    page
  }) => {
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }

    const probeResponse = await page.request.get('/api/sample/products?curlProbe=alpha&curlProbe=beta', {
      headers: {accept: 'application/json', 'x-api-key': 'e2e-must-not-be-copied'}
    })
    expect(probeResponse.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')
    await page.locator('#http-exchanges-filter').fill('curlProbe')

    const probeRow = page.locator('.http-exchanges-table tbody tr', {hasText: 'curlProbe'}).first()
    await expect(probeRow).toBeVisible({timeout: 15_000})
    await probeRow.locator('.http-exchanges-detail-toggle').click()

    const detail = page.locator('.http-exchanges-detail').first()
    // Precondition: the header really was recorded, so the absence assertions below cannot pass vacuously.
    await expect(detail).toContainText(/x-api-key/i)

    const curlAction = page.locator('.http-exchanges-curl').first()
    await expect(curlAction).toContainText('BootUI never captures request bodies')
    await expect(curlAction).toContainText('query parameter names are kept')

    const copyButton = curlAction.locator('.http-exchanges-curl-copy')
    await expect(copyButton).toBeEnabled()
    await copyButton.click()
    await expect(copyButton).toContainText('Copied')
    await expect(page.locator('.http-exchanges-copy-status')).toContainText('cURL template copied')

    const copied = await page.evaluate(() => navigator.clipboard.readText())
    // textContent, not toHaveText: the rendered command must match the clipboard byte for byte.
    expect(await curlAction.locator('.http-exchanges-curl-command').textContent()).toBe(copied)

    const lines = copied.split(' \\\n')
    expect(lines[0]).toMatch(
      /^curl --globoff 'http:\/\/[^']+\/api\/sample\/products\?curlProbe=VALUE&curlProbe=VALUE'$/
    )
    for (const line of lines.slice(1)) {
      expect(line).toMatch(/^ {2}-H '(Accept|Accept-Language|Cache-Control|Content-Type|User-Agent): [^']*'$/)
    }
    expect(lines).toContain("  -H 'Accept: application/json'")
    expect(copied).not.toContain('alpha')
    expect(copied).not.toContain('beta')
    expect(copied.toLowerCase()).not.toContain('x-api-key')
    expect(copied).not.toContain('e2e-must-not-be-copied')
  })

  test('ranks routes over the retained window and drills down to one route and a request profile', async ({
    openView,
    page
  }) => {
    for (let i = 0; i < 3; i++) {
      expect((await page.request.get('/api/sample/hello')).ok()).toBeTruthy()
    }

    await openView('http-exchanges', 'HTTP Exchanges')

    const routeRow = page.locator('.http-routes-table tbody tr[data-route-id="GET /api/sample/hello"]')
    await expect(routeRow).toBeVisible({timeout: 15_000})
    await expect(routeRow.locator('.http-routes-source')).toHaveText(/declared/)
    await expect(page.locator('.http-routes-window')).toContainText('retained')
    await expect(page.locator('.http-routes-window')).toContainText('BootUI')
    await expect(page.locator('.http-routes-table')).not.toContainText('/bootui')

    await routeRow.locator('.http-routes-exchanges-link').click()
    await expect(page.locator('.http-exchanges-route-filter')).toContainText('GET /api/sample/hello')
    const rows = page.locator('.http-exchanges-table tbody tr')
    await expect(rows.first()).toContainText('/api/sample/hello')
    await expect(page.locator('.http-exchanges-table')).not.toContainText('/api/secure')

    await rows.first().locator('.http-exchanges-profile-link').click()
    await expect(page).toHaveURL(/\/activity\?request=/)
    await expect(page.locator('.activity-drawer')).toBeVisible()
  })
})
