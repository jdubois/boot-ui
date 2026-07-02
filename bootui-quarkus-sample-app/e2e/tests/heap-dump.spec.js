// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * Heap Dump captures/analyzes a real JVM heap snapshot through the shared engine's HeapDumpService.
 * "Analyze live heap" computes a histogram without writing a file (no confirm); "Capture heap dump"
 * writes a real .hprof to disk (confirmed, danger) and "Delete" removes it again (confirmed,
 * danger+irreversible) - we always delete what we capture so dumps don't accumulate on disk across runs.
 */
test.describe('Heap Dump (Quarkus)', () => {
  test('analyzes the live heap and renders a class histogram', async ({openView, page}) => {
    await openView('heap-dump', 'Heap Dump')

    await page.getByRole('button', {name: 'Analyze live heap'}).click()

    await expect(page.locator('.badge', {hasText: 'ANALYZED'})).toBeVisible({timeout: 20_000})
    await expect(page.locator('main')).toContainText('objects')
    await expect(page.locator('table').filter({hasText: 'Class'}).locator('tbody tr').first()).toBeVisible()
  })

  test('captures a heap dump and deletes it', async ({openView, page}) => {
    await openView('heap-dump', 'Heap Dump')

    const captureButton = page.getByRole('button', {name: 'Capture heap dump'})
    await expect(captureButton).toBeEnabled()

    const dumpsMetric = page.locator('.card', {hasText: 'Dumps on disk'}).locator('.fs-4')
    const before = (await dumpsMetric.textContent())?.trim()

    await captureButton.click()
    await acceptConfirm(page)

    // Capturing forces a full GC and writes a real .hprof file; both the disk count and the captured
    // dumps table reflect the real backend state, not a client-side optimistic update.
    await expect(page.locator('.badge', {hasText: 'CAPTURED'})).toBeVisible({timeout: 20_000})
    await expect(dumpsMetric).not.toHaveText(before ?? '')

    const dumpsCard = page.locator('.card', {hasText: 'Captured dumps'})
    const firstRow = dumpsCard.locator('tbody tr').first()
    await expect(firstRow).toBeVisible()

    await firstRow.getByRole('button', {name: 'Delete'}).click()
    await acceptConfirm(page)
    await expect(dumpsMetric).toHaveText(before ?? '')
  })
})
