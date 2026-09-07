// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Metrics view', () => {
  test('renders meter browser, measurements and live graph', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const meters = page.locator('.meter-list .list-group-item')
    await expect.poll(async () => meters.count()).toBeGreaterThan(0)

    const filteredRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return url.pathname.endsWith('/api/metrics') && url.searchParams.get('q') === 'jvm.memory.used'
    })
    await page.getByRole('textbox', {name: 'Search meters'}).fill('jvm.memory.used')
    await filteredRequest
    const meter = page.locator('.meter-list .list-group-item', {hasText: 'jvm.memory.used'}).first()
    if (await meter.count()) {
      await meter.click()
    } else {
      await page.getByRole('textbox', {name: 'Search meters'}).fill('')
      await meters.first().click()
    }

    await expect(page.locator('.card', {hasText: /Current/})).toBeVisible()
    await expect(page.locator('svg[aria-label="Live metric value graph"]')).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Samples'})).toBeVisible()
    await expect(page.locator('table tbody tr').first()).toBeVisible()
    await expect(page.getByText(/Showing \d+–\d+ of \d+/)).toBeVisible()
  })

  test('filters meters by type on the server', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const typeSelect = page.getByLabel('Filter meters by type')
    await expect.poll(async () => await typeSelect.locator('option').count()).toBeGreaterThan(1)
    const firstType = await typeSelect.locator('option').nth(1).getAttribute('value')
    expect(firstType).toBeTruthy()

    const filteredRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return url.pathname.endsWith('/api/metrics') && url.searchParams.get('type') === firstType
    })
    await typeSelect.selectOption(firstType)
    await filteredRequest
    const firstMeter = page.locator('.meter-list .list-group-item').first()
    await expect(firstMeter.locator('.meter-type')).toHaveText(firstType)
    await expect(page.getByText(/Filters run on the server/)).toBeVisible()
  })

  test('groups meters by provenance and filters on a group', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const provenanceCard = page.locator('.card', {hasText: 'Meter provenance'})
    await expect(provenanceCard).toBeVisible()
    await expect(provenanceCard.getByText(/Catalogue \d/)).toBeVisible()

    const jvmChip = provenanceCard.getByRole('button', {name: /^JVM, \d+ meters$/})
    await expect(jvmChip).toBeVisible()
    await expect(jvmChip).toHaveAttribute('aria-pressed', 'false')

    const groupedRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return url.pathname.endsWith('/api/metrics') && url.searchParams.get('group') === 'jvm'
    })
    await jvmChip.click()
    await groupedRequest
    await expect(jvmChip).toHaveAttribute('aria-pressed', 'true')
    await expect(provenanceCard.getByText('Micrometer JVM binders').first()).toBeVisible()

    const firstMeter = page.locator('.meter-list .list-group-item').first()
    await expect(firstMeter.locator('.meter-provenance')).toContainText('JVM')

    await firstMeter.click()
    await expect(page.locator('.provenance-detail').last()).toBeVisible()
  })

  test('filters meters by explanation source on the server', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const explanationResponse = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname.endsWith('/api/metrics') && url.searchParams.get('explanation') === 'CURATED'
    })
    await page.getByLabel('Filter meters by explanation source').selectOption('CURATED')
    const response = await explanationResponse
    expect(response.ok()).toBeTruthy()
    const {meters} = await response.json()
    expect(meters.every((meter) => meter.provenance?.explanationSource === 'CURATED')).toBeTruthy()

    // Which meters carry a registry description depends on third-party metadata, so assert the invariant instead
    // of a specific meter. Wait for the filtered response's rows, not the previous list's size.
    const sources = page.locator('.meter-list .meter-source')
    await expect(sources).toHaveText(meters.map(() => 'BootUI catalogue'))
    if (meters.length === 0) {
      await expect(page.locator('.meter-list')).toContainText('No meters match')
    }
  })
})
