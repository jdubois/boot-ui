// @ts-check

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 */
export function registerMemoryHistogramTests(test, expect) {
  test('Memory histogram selection is explicit and survives histogram, omitted, histogram scans', async ({page}) => {
    const scanRequests = []
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/memory/scan')) {
        scanRequests.push(request.url())
      }
    })
    await page.goto('/bootui/#/memory')
    const choice = page.getByRole('checkbox', {name: 'Include heap histogram'})
    const scan = page.getByRole('button', {name: 'Run memory checks', exact: true})
    await expect(scan).toBeEnabled()
    expect(scanRequests).toHaveLength(0)
    for (const includeHistogram of [true, false, true]) {
      await choice.setChecked(includeHistogram)
      await expect(page.locator('#memory-scan-cost')).toContainText(
        includeHistogram ? 'Requests full GC and may pause the JVM.' : 'No histogram or GC request.'
      )
      await scan.focus()
      const responsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/memory/scan')
      )
      await page.keyboard.press('Enter')
      const response = await responsePromise
      expect(response.status()).toBe(200)
      expect(new URL(response.url()).searchParams.get('includeHistogram')).toBe(String(includeHistogram))
      const report = await response.json()
      expect(report.scan.includeHistogram).toBe(includeHistogram)
      if (!includeHistogram) {
        expect(report.summary.histogramAvailable).toBe(false)
        expect(report.evidence.coverageComplete).toBe(false)
        expect(report.evidence.limitations.join(' ')).toContain('heap histogram omitted')
      }
      await expect(scan).toBeEnabled()
      await expect(page.locator('.card', {hasText: 'Runtime snapshot'})).toContainText(
        includeHistogram ? 'Histogram requested' : 'Histogram omitted'
      )
    }
    expect(scanRequests).toHaveLength(3)
  })
}
