// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Health view', () => {
  test('renders friendly health summary and tree', async ({openView, page}) => {
    await openView('health', 'Health')

    await expect(page.getByText('Overall status')).toBeVisible()
    await expect(page.getByText('Component tree')).toBeVisible()
    const rootCard = page.locator('main .card').first()
    await expect(rootCard).toBeVisible()
    await expect(rootCard.locator('.badge')).toHaveText(/UP|DOWN|UNKNOWN|OUT_OF_SERVICE|DISABLED/)
    await expect(page.locator('main pre')).toHaveCount(0)
  })

  test('renders nested component details in a readable table', async ({openView, page}) => {
    await openView('health', 'Health')

    const diskSpaceSummary = page.locator('details.card > summary', {hasText: /^diskSpace/}).first()
    const diskSpaceNode = diskSpaceSummary.locator('xpath=..')
    await expect(diskSpaceNode).toBeVisible()
    await expect(diskSpaceNode).toContainText('Details')
    await expect(diskSpaceNode.locator('table')).toBeVisible()
    await expect(diskSpaceNode).toContainText(/Path|Exists|Free|Total/)
  })
})
