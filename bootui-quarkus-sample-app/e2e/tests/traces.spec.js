// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * Traces captures local application spans in-process via a CDI SpanProcessor (no OTLP receiver needed
 * on Quarkus). We exercise the sample's /api/sample/chained endpoint, which creates nested @WithSpan
 * spans, to populate the retained buffer, then prove the Quarkus TracesResource's DELETE clears it for
 * real.
 */
test.describe('Traces (Quarkus)', () => {
  test.beforeEach(async ({page}) => {
    await page.request.get('/api/sample/chained').catch(() => {})
  })

  test('renders captured spans and clears the retained buffer', async ({openView, page}) => {
    await openView('traces', 'Traces')

    const clearButton = page.getByRole('button', {name: 'Clear'})
    await expect(clearButton).toBeEnabled({timeout: 15_000})
    await expect(page.locator('table tbody tr').first()).toBeVisible()

    await clearButton.click()
    await acceptConfirm(page)

    await expect(page.locator('.alert-success')).toContainText('Cleared retained traces.')
    await expect(clearButton).toBeDisabled()
  })
})
