// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Threads view', () => {
  test('shows a live thread snapshot with state summary and stack traces', async ({openView, page}) => {
    await openView('threads', 'Threads')

    // The sample app always has multiple live threads.
    const rows = page.locator('table.threads-table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(3)

    // A per-state count summary is rendered as badges.
    await expect(page.locator('.badge', {hasText: /RUNNABLE:/}).first()).toBeVisible()

    // Expanding a stack trace reveals frames.
    const stackButton = page.getByRole('button', {name: /View stack \d+/}).first()
    await stackButton.click()
    await expect(page.locator('pre.threads-stack').first()).toBeVisible()
  })

  test('filters threads by name', async ({openView, page}) => {
    await openView('threads', 'Threads')

    const rows = page.locator('table.threads-table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(3)

    await page.getByPlaceholder(/Filter by name, state, or stack frame/).fill('zzz-no-such-thread')
    await expect(page.locator('text=No threads match your filters.')).toBeVisible()
  })
})
