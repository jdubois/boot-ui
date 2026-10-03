// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Live Memory view', () => {
  test('renders heap and non-heap live memory cards without tuning panels', async ({openView, page}) => {
    await openView('live-memory', 'Live Memory')

    await expect(page.locator('.card', {hasText: 'Heap memory'}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: /Non[- ]?Heap/i}).first()).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Recommended JVM Options'})).toHaveCount(0)
    await expect(page.locator('.card', {hasText: 'Kubernetes calculator'})).toHaveCount(0)
  })

  test('renders the memory pools table with usage values', async ({openView, page}) => {
    await openView('live-memory', 'Live Memory')

    const poolsCard = page.locator('.card', {hasText: 'Memory Pools'})
    await expect(poolsCard).toBeVisible()
    await expect(poolsCard.locator('thead')).toContainText('Pool')
    await expect(poolsCard.locator('thead')).toContainText('Usage')

    const rows = poolsCard.locator('tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(0)
    await expect(rows.first().locator('td').nth(0)).not.toBeEmpty()
    await expect(rows.first().locator('td').nth(4)).toContainText(/%/)
  })

  test('explains the shared Free BootUI memory action and lets the user cancel it', async ({openView, page}) => {
    await openView('live-memory', 'Live Memory')

    const info = page.getByRole('button', {name: 'What does Free BootUI memory do?'})
    await expect(info).toHaveAttribute('aria-expanded', 'false')
    await info.click()
    await expect(info).toHaveAttribute('aria-expanded', 'true')
    const explanation = page.getByTestId('memory-offload-panel')
    await expect(explanation).toContainText('garbage collection')
    await expect(explanation).toContainText('only a hint')
    await page.keyboard.press('Escape')
    await expect(explanation).toBeHidden()

    // Cancel rather than confirm: the E2E servers are shared by parallel specs that rely on captured data.
    await page.getByTestId('memory-offload').click()
    const dialog = page.getByRole('dialog', {name: 'Free BootUI memory?'})
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', {name: 'Cancel'}).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByTestId('memory-offload-result')).toHaveCount(0)
  })
})
