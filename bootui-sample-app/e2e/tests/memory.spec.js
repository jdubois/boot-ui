// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Memory view', () => {
  test('renders tuning advisor recommendations and copy action', async ({openView, page, browserName, context}) => {
    // Grant clipboard permissions for the copy button. Not all browsers expose them
    // through the same API; ignore failures gracefully.
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }

    await openView('tuning-advisor', 'Tuning advisor')

    const jvmOptionsCard = page.locator('.card', {hasText: 'Recommended JVM Options'}).first()
    const kubernetesCard = page.locator('.card', {hasText: 'Kubernetes sizing'}).first()
    await expect(jvmOptionsCard).toBeVisible()
    await expect(kubernetesCard).toBeVisible()
    await expect(kubernetesCard).toContainText('Guaranteed')
    await expect(kubernetesCard.locator('.options-box code')).toContainText('JAVA_TOOL_OPTIONS')
    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    await expect(optionsBlock).toContainText(/-Xmx|-XX:/)

    // The copy button gives feedback after being clicked.
    const copyButton = jvmOptionsCard.getByRole('button', {name: /Copy/})
    await copyButton.click()
    await expect(page.getByRole('button', {name: /Copied!/})).toBeVisible({timeout: 5_000})
  })

  test('renders the memory pools table with usage values', async ({openView, page}) => {
    await openView('memory', 'Memory')

    const poolsCard = page.locator('.card', {hasText: 'Memory Pools'})
    await expect(poolsCard).toBeVisible()
    await expect(poolsCard.locator('thead')).toContainText('Pool')
    await expect(poolsCard.locator('thead')).toContainText('Usage')

    const rows = poolsCard.locator('tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(0)
    await expect(rows.first().locator('td').nth(0)).not.toBeEmpty()
    await expect(rows.first().locator('td').nth(4)).toContainText(/%/)
  })

  test('editing total memory updates the recommended -Xmx', async ({openView, page}) => {
    await openView('tuning-advisor', 'Tuning advisor')

    const calculatorCard = page.locator('.card', {hasText: 'JVM memory calculator'})
    const jvmOptionsCard = page.locator('.card', {hasText: 'Recommended JVM Options'}).first()
    await expect(calculatorCard).toBeVisible()

    const totalInput = calculatorCard.locator('input[type="number"]').first()
    await expect(totalInput).toBeVisible()
    await expect(totalInput).not.toHaveValue('')

    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    // Capture the current -Xmx value before editing
    const before = await optionsBlock.innerText()
    const beforeMatch = before.match(/-Xmx(\d+)m/)
    expect(beforeMatch).not.toBeNull()
    const beforeXmx = parseInt(beforeMatch[1], 10)

    // Pick a clearly different total: jump by 256 MB up or down to dodge clamping.
    const currentTotal = parseInt(await totalInput.inputValue(), 10)
    const newTotal = currentTotal > 1024 ? 512 : currentTotal + 512
    await totalInput.fill(String(newTotal))
    await totalInput.blur()

    // Debounced re-fetch is 300 ms; allow up to 5 s.
    await expect
      .poll(
        async () => {
          const text = await optionsBlock.innerText()
          const m = text.match(/-Xmx(\d+)m/)
          return m ? parseInt(m[1], 10) : 0
        },
        {timeout: 5_000}
      )
      .not.toBe(beforeXmx)
  })
})
