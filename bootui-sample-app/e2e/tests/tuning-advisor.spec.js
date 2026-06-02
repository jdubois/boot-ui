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

    const virtualThreadsCard = page.locator('.card', {hasText: 'Spring virtual threads'}).first()
    const jvmOptionsCard = page.locator('.card', {hasText: 'Bare metal JVM options'}).first()
    const kubernetesCard = page.locator('.card', {hasText: 'Kubernetes calculator'}).first()
    const kubernetesYaml = kubernetesCard.locator('.options-box code')
    await expect(virtualThreadsCard).toBeVisible()
    await expect(virtualThreadsCard).toContainText('spring.threads.virtual.enabled=true')
    await expect(virtualThreadsCard.locator('#virtualThreadsEnabled')).not.toBeChecked()
    await expect(jvmOptionsCard).toBeVisible()
    await expect(kubernetesCard).toBeVisible()
    await expect(kubernetesCard).toContainText('Guaranteed')
    await expect(kubernetesCard.locator('#kubernetesBurstableEnabled')).not.toBeChecked()
    await expect(kubernetesCard.locator('#kubernetesActuatorEnabled')).toBeChecked()
    await expect(kubernetesYaml).toContainText('JAVA_TOOL_OPTIONS')
    await expect(kubernetesYaml).toContainText('MaxRAMPercentage')
    await expect(kubernetesYaml).toContainText('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED')
    await expect(kubernetesYaml).toContainText('readinessProbe')
    await expect(kubernetesYaml).not.toContainText('-Xmx')
    await expect(kubernetesCard).toContainText('Garbage collector:')
    await expect(kubernetesCard).toContainText('Sizing notes')

    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    await expect(optionsBlock).toContainText(/-Xmx|-XX:/)
    await expect(optionsBlock).not.toContainText('spring.threads.virtual.enabled=true')

    const copyButton = jvmOptionsCard.getByRole('button', {name: /Copy/})
    await copyButton.click()
    await expect(page.getByRole('button', {name: /Copied!/})).toBeVisible({timeout: 5_000})
  })

  test('Kubernetes toggles update the deployment snippet', async ({openView, page}) => {
    await openView('tuning-advisor', 'Tuning Advisor')

    const kubernetesCard = page.locator('.card', {hasText: 'Kubernetes calculator'}).first()
    const yamlBlock = kubernetesCard.locator('.options-box code')
    const burstableToggle = kubernetesCard.locator('#kubernetesBurstableEnabled')
    const actuatorToggle = kubernetesCard.locator('#kubernetesActuatorEnabled')

    await expect(kubernetesCard).toBeVisible()
    const guaranteedYaml = await yamlBlock.innerText()
    const guaranteedRequest = guaranteedYaml.match(/requests:\s+memory: "([^"]+)"/)?.[1]
    expect(guaranteedRequest).toBeTruthy()

    await burstableToggle.check()
    await expect
      .poll(
        async () => {
          const yaml = await yamlBlock.innerText()
          return yaml.match(/requests:\s+memory: "([^"]+)"/)?.[1] || ''
        },
        {timeout: 5_000}
      )
      .not.toBe(guaranteedRequest)
    await expect(kubernetesCard).toContainText('Burstable')

    await actuatorToggle.uncheck()
    await expect(yamlBlock).not.toContainText('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED')
    await expect(yamlBlock).not.toContainText('startupProbe')
    await expect(yamlBlock).not.toContainText('readinessProbe')
  })

  test('editing total memory updates the recommended -Xmx', async ({openView, page}) => {
    await openView('tuning-advisor', 'Tuning Advisor')

    const calculatorCard = page.locator('.card', {hasText: 'Bare metal JVM calculator'})
    const jvmOptionsCard = page.locator('.card', {hasText: 'Bare metal JVM options'}).first()
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
