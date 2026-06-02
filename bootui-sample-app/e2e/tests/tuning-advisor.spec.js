// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Tuning Advisor view', () => {
  test('renders JVM options and Kubernetes calculator recommendations', async ({
    openView,
    page,
    browserName,
    context
  }) => {
    // Grant clipboard permissions for the copy button. Not all browsers expose them
    // through the same API; ignore failures gracefully.
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }

    await openView('tuning-advisor', 'Tuning Advisor')

    const jvmOptionsCard = page.locator('.card', {hasText: 'Recommended JVM Options'}).first()
    const kubernetesCard = page.locator('.card', {hasText: 'Kubernetes calculator'}).first()
    await expect(jvmOptionsCard).toBeVisible()
    await expect(kubernetesCard).toBeVisible()
    await expect(kubernetesCard).toContainText('Guaranteed')
    await expect(kubernetesCard.locator('.options-box code')).toContainText('JAVA_TOOL_OPTIONS')

    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    await expect(optionsBlock).toContainText(/-Xmx|-XX:/)

    const copyButton = jvmOptionsCard.getByRole('button', {name: /Copy/})
    await copyButton.click()
    await expect(page.getByRole('button', {name: /Copied!/})).toBeVisible({timeout: 5_000})
  })

  test('editing total memory updates the recommended -Xmx', async ({openView, page}) => {
    await openView('tuning-advisor', 'Tuning Advisor')

    const calculatorCard = page.locator('.card', {hasText: 'JVM memory calculator'})
    const jvmOptionsCard = page.locator('.card', {hasText: 'Recommended JVM Options'}).first()
    await expect(calculatorCard).toBeVisible()

    const totalInput = calculatorCard.locator('input[type="number"]').first()
    await expect(totalInput).toBeVisible()
    await expect(totalInput).not.toHaveValue('')

    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    const before = await optionsBlock.innerText()
    const beforeMatch = before.match(/-Xmx(\d+)m/)
    expect(beforeMatch).not.toBeNull()
    const beforeXmx = parseInt(beforeMatch[1], 10)

    const currentTotal = parseInt(await totalInput.inputValue(), 10)
    const newTotal = currentTotal > 1024 ? 512 : currentTotal + 512
    await totalInput.fill(String(newTotal))
    await totalInput.blur()

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
