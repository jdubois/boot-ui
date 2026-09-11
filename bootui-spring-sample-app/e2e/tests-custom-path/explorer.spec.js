// @ts-check
import {expect, test} from '@playwright/test'

test('Explorer keeps its reads, stream and lazy Three asset under custom UI/API mounts', async ({page, request}) => {
  const manifest = await (await request.get('/host/internal/bootui-api/panels')).json()
  const explorer = manifest.panels.find((panel) => panel.id === 'explorer')
  if (!explorer.available) {
    const reads = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.includes('/explorer')) reads.push(request.url())
    })
    await page.goto('/host/dev-console/#/explorer')
    await expect(page.locator('main')).toContainText(explorer.unavailableReason)
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(0)
    expect(reads).toEqual([])
    return
  }
  expect((await request.get('/host/api/explorer-demo/1')).ok()).toBeTruthy()
  const paths = []
  page.on('request', (request) => paths.push(new URL(request.url()).pathname))
  await page.goto('/host/dev-console/#/explorer')
  await expect(page.getByRole('heading', {name: '3D Explorer', exact: true})).toBeVisible()
  await expect(page.locator('canvas[data-explorer-canvas]')).toBeVisible()
  expect(paths).toContain('/host/internal/bootui-api/explorer')
  expect(paths).toContain('/host/internal/bootui-api/activity/stream')
  expect(paths.some((path) => path.startsWith('/bootui/'))).toBe(false)
  expect(paths.some((path) => path.includes('ExplorerScene') && path.startsWith('/host/dev-console/assets/'))).toBe(
    true
  )
})
