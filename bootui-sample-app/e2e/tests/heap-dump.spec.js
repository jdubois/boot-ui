// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Heap Dump view', () => {
  test('renders the safety warning, summary cards, and action buttons', async ({openView, page}) => {
    await openView('heap-dump', 'Heap Dump')

    await expect(page.locator('.alert-warning', {hasText: 'Local-only and sensitive'})).toBeVisible()

    await expect(page.locator('.card', {hasText: 'Last action'}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Live heap used'}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Dumps on disk'}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Free disk'}).first()).toBeVisible()

    await expect(page.locator('.card-header', {hasText: 'Top classes by retained size'})).toBeVisible()
    await expect(page.locator('.card-header', {hasText: 'Capture options'})).toBeVisible()
    await expect(page.locator('.card-header', {hasText: 'Captured dumps'})).toBeVisible()

    await expect(page.getByRole('button', {name: 'Analyze live heap'})).toBeEnabled()
    await expect(page.getByRole('button', {name: /Capture heap dump/})).toBeEnabled()
  })

  test('analyzing the live heap populates the class histogram', async ({openView, page}) => {
    await openView('heap-dump', 'Heap Dump')

    const histogramCard = page.locator('.card', {hasText: 'Top classes by retained size'})
    await expect(histogramCard).toContainText('No heap analysis yet')

    await page.getByRole('button', {name: 'Analyze live heap'}).click()

    const rows = histogramCard.locator('tbody tr')
    await expect.poll(async () => rows.count(), {timeout: 15_000}).toBeGreaterThan(0)
    await expect(histogramCard).not.toContainText('No heap analysis yet')

    await expect(page.locator('.card', {hasText: 'Last action'}).first().locator('.badge')).toHaveText('ANALYZED')
  })

  test('keeps the class prefix filter editable when no classes match', async ({openView, page}) => {
    await openView('heap-dump', 'Heap Dump')

    const histogramCard = page.locator('.card', {hasText: 'Top classes by retained size'})
    await page.getByRole('button', {name: 'Analyze live heap'}).click()

    const rows = histogramCard.locator('tbody tr')
    await expect.poll(async () => rows.count(), {timeout: 15_000}).toBeGreaterThan(0)

    const filter = histogramCard.getByPlaceholder('Filter by class prefix…')
    await filter.fill('zzzz.bootui.NoSuchClass')

    await expect(histogramCard).toContainText('No classes match the current filter')
    await expect(filter).toBeEnabled()

    await filter.fill('')

    await expect.poll(async () => rows.count(), {timeout: 15_000}).toBeGreaterThan(0)
    await expect(histogramCard).not.toContainText('No classes match the current filter')
  })
})
