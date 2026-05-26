// @ts-check
import { test, expect } from './fixtures.js'

test.describe('Memory view', () => {

  test('renders the JVM options panel and heap/non-heap cards', async ({ openView, page, browserName, context }) => {
    // Grant clipboard permissions for the copy button. Not all browsers expose them
    // through the same API; ignore failures gracefully.
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch { /* no-op */ }
    }

    await openView('memory', 'Memory')

    await expect(page.locator('.card', { hasText: 'Recommended JVM Options' }).first()).toBeVisible()
    await expect(page.locator('.card', { hasText: 'Heap Memory' }).first()).toBeVisible()
    await expect(page.locator('.card', { hasText: /Non[- ]?Heap/i }).first()).toBeVisible()

    const optionsBlock = page.locator('.options-box code')
    await expect(optionsBlock).toContainText(/-Xmx|-XX:/)

    // The copy button gives feedback after being clicked.
    const copyButton = page.getByRole('button', { name: /Copy/ })
    await copyButton.click()
    await expect(page.getByRole('button', { name: /Copied!/ })).toBeVisible({ timeout: 5_000 })
  })
})
