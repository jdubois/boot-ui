// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Live Memory view', () => {
  test('renders heap and non-heap live memory cards without tuning panels', async ({openView, page}) => {
    await openView('live-memory', 'Live Memory')

    await expect(page.locator('.card', {hasText: 'Heap Memory'}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: /Non[- ]?Heap/i}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Recommended JVM Options'})).toHaveCount(0)
    await expect(page.locator('.card', {hasText: 'Kubernetes calculator'})).toHaveCount(0)
  })

  test('renders the memory pools table with usage values', async ({openView, page}) => {
    await openView('live-memory', 'Live Memory')

    const poolsCard = page.locator('.card', {hasText: 'Memory Pools'})
    await expect(poolsCard).toBeVisible()
    await expect(poolsCard.locator('thead')).toContainText('Pool')
    await expect(poolsCard.locator('thead')).toContainText('Usage')

    const rows = poolsCard.locator('tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(0)
    await expect(rows.first().locator('td').nth(0)).not.toBeEmpty()
    await expect(rows.first().locator('td').nth(4)).toContainText(/%/)
  })
})
