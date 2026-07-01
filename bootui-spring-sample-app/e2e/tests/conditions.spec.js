// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Auto-configuration conditions view', () => {
  test('shows positive matches by default and lets the user switch to negative ones', async ({openView}) => {
    const page = await openView('conditions', 'Auto-configuration conditions')

    const positiveTab = page.locator('.nav-tabs .nav-link', {hasText: /Positive/})
    const negativeTab = page.locator('.nav-tabs .nav-link', {hasText: /Negative/})

    await expect(positiveTab).toHaveClass(/active/)
    await expect(positiveTab).toContainText(/Positive \(\d+\)/)

    // At least one positive entry must render with the green outcome badge.
    await expect(page.locator('.badge.bg-success').first()).toBeVisible()

    await negativeTab.click()
    await expect(negativeTab).toHaveClass(/active/)
    await expect(page.locator('.badge.bg-secondary').first()).toBeVisible()
  })

  test('filter narrows the visible auto-configuration entries', async ({openView}) => {
    const page = await openView('conditions', 'Auto-configuration conditions')
    const entries = page.locator('div.mb-2')

    const initial = await entries.count()
    expect(initial).toBeGreaterThan(0)

    await page.getByPlaceholder('Filter…').fill('DataSourceAutoConfiguration')
    await expect.poll(async () => entries.count()).toBeLessThan(initial)
    await expect(entries.first()).toContainText('DataSource')
  })
})
