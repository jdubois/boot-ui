// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Metrics view', () => {
  test('renders meter browser, measurements and live graph', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const meters = page.locator('.meter-list .list-group-item')
    await expect.poll(async () => meters.count()).toBeGreaterThan(0)

    await page.getByPlaceholder('Search meters').fill('jvm.memory.used')
    const meter = page.locator('.meter-list .list-group-item', {hasText: 'jvm.memory.used'}).first()
    if (await meter.count()) {
      await meter.click()
    } else {
      await page.getByPlaceholder('Search meters').fill('')
      await meters.first().click()
    }

    await expect(page.locator('.card', {hasText: /Current/})).toBeVisible()
    await expect(page.locator('svg[aria-label="Live metric value graph"]')).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Samples'})).toBeVisible()
    await expect(page.locator('table tbody tr').first()).toBeVisible()
  })

  test('filters meters by type', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const typeSelect = page.locator('.card-body.border-bottom select')
    await expect.poll(async () => await typeSelect.locator('option').count()).toBeGreaterThan(1)
    const firstType = await typeSelect.locator('option').nth(1).getAttribute('value')
    expect(firstType).toBeTruthy()

    await typeSelect.selectOption(firstType)
    const firstMeter = page.locator('.meter-list .list-group-item').first()
    await expect(firstMeter.locator('.badge')).toHaveText(firstType)
  })
})
