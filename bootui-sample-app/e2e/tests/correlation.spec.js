// @ts-check
import {expect, test} from './fixtures.js'

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736'

test.describe('Trace correlation', () => {
  test('shows a correlation banner and pivots between diagnostics panels', async ({openView, page}) => {
    // Deep-link into HTTP Exchanges already filtered by a trace id.
    await openView(`http-exchanges?trace=${TRACE_ID}`, 'HTTP Exchanges')

    const banner = page.locator('.correlation-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toContainText('Correlated with trace')

    // Pivot to the Traces panel, carrying the same trace id.
    await banner.getByRole('button', {name: /Traces/}).click()
    await expect(page.locator('main h2').filter({hasText: 'Traces'})).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`trace=${TRACE_ID}`))
    await expect(page.locator('.correlation-banner')).toContainText('Correlated with trace')

    // Pivot onward to the Log Tail panel.
    await page.locator('.correlation-banner').getByRole('button', {name: /Logs/}).click()
    await expect(page.locator('main h2').filter({hasText: 'Log Tail'})).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`trace=${TRACE_ID}`))

    // Clearing the filter drops the trace banner.
    await page
      .locator('.correlation-banner')
      .getByRole('button', {name: /Clear filter/})
      .click()
    await expect(page.locator('.correlation-banner')).toHaveCount(0)
  })

  test('focuses a trace from an HTTP exchange row', async ({openView, page}) => {
    // A request that carries W3C trace context records the trace id on the exchange.
    const apiResponse = await page.request.get('/api/sample/hello', {
      headers: {traceparent: `00-${TRACE_ID}-00f067aa0ba902b7-01`}
    })
    expect(apiResponse.ok()).toBeTruthy()

    await openView('http-exchanges', 'HTTP Exchanges')

    const sampleRow = page.locator('tbody tr', {hasText: '/api/sample/hello'}).first()
    await expect(sampleRow).toBeVisible({timeout: 15_000})
    const traceButton = sampleRow.locator('.correlation-id-btn')
    await expect(traceButton).toBeVisible()
    await expect(traceButton).toContainText('id: 4bf92f3577b3…')
    await traceButton.click()

    await expect(page.locator('.correlation-banner')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`trace=${TRACE_ID}`))
  })
})
