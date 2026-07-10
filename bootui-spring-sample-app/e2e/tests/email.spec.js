// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe.serial('Email view', () => {
  /**
   * Ensures at least one "order shipped" email (the one carrying a PDF attachment) is captured.
   * The sample "send-email" endpoint alternates between the HTML+attachment message and the plain
   * "welcome" message, so a few calls guarantee the attachment-bearing one exists.
   * @param {import('@playwright/test').Page} page
   */
  async function seedOrderShipped(page) {
    for (let i = 0; i < 3; i++) {
      const res = await page.request.get('/api/sample/send-email')
      expect(res.ok()).toBeTruthy()
      if ((await res.text()).includes('order has shipped')) return
    }
    throw new Error('sample app did not produce an order-shipped email')
  }

  test('lists captured outgoing emails and explains dev-trap mode', async ({openView, page}) => {
    await page.request.get('/api/sample/send-email')

    await openView('email', 'Email')

    // The sample app runs with bootui.email.dev-trap=true, so the panel explains that captured
    // messages are recorded but never actually sent.
    await expect(page.getByText(/Dev-trap mode is enabled/)).toBeVisible()

    const rows = page.locator('main table tbody tr')
    await expect(rows.first()).toBeVisible()
    // Captured-but-not-sent messages carry a dev-trap status badge rather than a "sent" one.
    await expect(page.locator('main table tbody .badge.text-bg-warning').first()).toHaveText('dev-trap')

    // bootui.email.mask-content defaults to false, so subjects are revealed, not masked (******).
    await expect(page.locator('main table tbody .email-subject-cell').first()).not.toHaveText('******')
  })

  test('opens a message with its attachment and body in the detail drawer', async ({openView, page}) => {
    await seedOrderShipped(page)

    await openView('email', 'Email')

    // Target the row with an attachment badge (the order-shipped message). Attachment filenames are
    // not masked, so they are a stable, non-sensitive anchor for the assertion.
    const attachmentRow = page
      .locator('main table tbody tr')
      .filter({has: page.locator('td i.bi-paperclip')})
      .first()
    await expect(attachmentRow).toBeVisible()
    await attachmentRow.getByRole('button', {name: 'View'}).click()

    const drawer = page.locator('.email-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('heading', {name: 'Attachments'})).toBeVisible()
    await expect(drawer).toContainText('invoice-1042.pdf')
    await expect(page.frameLocator('iframe.email-html-frame').locator('body')).toContainText('order #1042 has shipped')

    await drawer.getByRole('button', {name: 'Close'}).click()
    await expect(drawer).toHaveCount(0)
  })

  test('offers a per-message .eml download link', async ({openView, page}) => {
    await page.request.get('/api/sample/send-email')

    await openView('email', 'Email')

    const download = page.locator('main table tbody tr a[title="Download .eml"]').first()
    await expect(download).toBeVisible()
    await expect(download).toHaveAttribute('href', /^api\/email\/.+\/eml$/)
  })

  test('clears the captured emails', async ({openView, page}) => {
    await page.request.get('/api/sample/send-email')

    await openView('email', 'Email')
    await expect(page.locator('main table tbody tr').first()).toBeVisible()

    await page.getByRole('button', {name: 'Clear'}).click()
    await acceptConfirm(page)

    // The in-memory buffer is emptied, so the panel drops to its empty state.
    await expect(page.getByText(/No outgoing emails captured yet/)).toBeVisible()

    // Re-seed so the shared in-memory buffer is not left empty for subsequent runs.
    await page.request.get('/api/sample/send-email')
  })
})
