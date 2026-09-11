// @ts-check
import {expect, test} from '@playwright/test'

test('3D Explorer reports unsupported Quarkus capability without fetching or capturing', async ({page, request}) => {
  const manifest = await (await request.get('/bootui/api/panels')).json()
  const explorer = manifest.panels.find((panel) => panel.id === 'explorer')
  expect(explorer.available).toBe(false)
  expect(explorer.unavailableReason).toBeTruthy()
  const reads = []
  page.on('request', (request) => {
    if (/\/api\/(explorer|activity\/stream)/.test(new URL(request.url()).pathname)) reads.push(request.url())
  })
  await page.goto('/bootui/#/explorer')
  await expect(page.getByRole('heading', {name: '3D Explorer', exact: true})).toBeVisible()
  await expect(page.locator('main')).toContainText(explorer.unavailableReason)
  await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(0)
  expect(reads).toEqual([])
})
