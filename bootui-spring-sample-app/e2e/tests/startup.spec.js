// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Startup timeline view', () => {
  test('renders startup steps and supports filtering', async ({openView, page}) => {
    await openView('startup', 'Startup timeline')

    // Wait until the page is no longer in the loading state.
    await expect(page.locator('text=Loading startup data…')).toHaveCount(0)

    await page.getByRole('button', {name: 'Expand all'}).click()

    // The sample app records a non-trivial number of startup steps.
    const steps = page.locator('.list-group .list-group-item')
    await expect.poll(async () => steps.count()).toBeGreaterThan(5)

    await page.getByRole('button', {name: 'Collapse all'}).click()
    const collapsedCount = await steps.count()
    expect(collapsedCount).toBeGreaterThan(0)
    await page.getByRole('button', {name: 'Expand all'}).click()
    await expect.poll(async () => steps.count()).toBeGreaterThan(collapsedCount)

    await page.getByPlaceholder(/Filter by step name/).fill('spring.boot')
    // Either the filter produces a smaller list, or the explicit "no matches" state.
    await expect(async () => {
      const visible = await steps.count()
      const noMatch = await page.locator('text=No startup steps match').count()
      expect(visible + noMatch).toBeGreaterThan(0)
    }).toPass()

    await page.getByPlaceholder(/Filter by step name/).clear()
    await page.getByRole('button', {name: /Slowest/}).click()
    await expect(page.getByRole('button', {name: /Slowest/})).toHaveAttribute('aria-pressed', 'true')
    await expect.poll(async () => steps.count()).toBeGreaterThan(0)
    await expect(page.locator('.startup-duration-label--slowest').first()).toBeVisible()
    const allDurationsButton = page.getByRole('button', {name: 'All', exact: true})
    await allDurationsButton.click()
    await expect(allDurationsButton).toHaveAttribute('aria-pressed', 'true')
  })
})
