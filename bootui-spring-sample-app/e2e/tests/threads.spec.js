// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'
import {registerThreadAnalysisTests} from '../scenarios/thread-analysis.js'

registerThreadAnalysisTests(test, expect, acceptConfirm)

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

  test('requires confirmation before requesting a thread dump', async ({openView, page}) => {
    const downloadRequests = []
    page.on('request', (request) => {
      if (request.url().includes('/bootui/api/threads/download')) downloadRequests.push(request)
    })
    await openView('threads', 'Threads')

    await page.getByRole('button', {name: /Download dump/}).click()

    await expect(page.getByRole('heading', {name: 'Download thread dump?'})).toBeVisible()
    expect(downloadRequests).toHaveLength(0)
    await page.getByRole('button', {name: 'Cancel'}).click()
    await expect(page.getByRole('heading', {name: 'Download thread dump?'})).toBeHidden()
    expect(downloadRequests).toHaveLength(0)
  })
})
