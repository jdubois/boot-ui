// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Auto-configuration conditions view', () => {
  test('shows positive matches by default and lets the user switch to negative ones', async ({openView}) => {
    const page = await openView('conditions', 'Auto-configuration conditions')

    await expect(page.getByRole('tablist', {name: 'Condition outcomes'})).toHaveCount(1)
    const positiveTab = page.getByRole('tab', {name: /Positive/})
    const negativeTab = page.getByRole('tab', {name: /Negative/})

    await expect(positiveTab).toHaveAttribute('aria-selected', 'true')
    await expect(positiveTab).toContainText(/Positive \(\d+\)/)

    // At least one positive entry must render with the green outcome badge.
    await expect(page.locator('.badge.bg-success').first()).toBeVisible()

    await negativeTab.click()
    await expect(negativeTab).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tabpanel', {name: /Negative/})).toBeVisible()
    await expect(page.locator('.badge.bg-secondary').first()).toBeVisible()

    // A real tablist: the arrow keys move the selection and the focus together.
    await negativeTab.press('ArrowLeft')
    await expect(positiveTab).toHaveAttribute('aria-selected', 'true')
    await expect(positiveTab).toBeFocused()
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
