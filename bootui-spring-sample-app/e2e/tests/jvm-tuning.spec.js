// @ts-check
import {expect, test} from './fixtures.js'
import {registerJvmSizingInputTests} from '../scenarios/jvm-sizing-inputs.js'

registerJvmSizingInputTests(test, expect)

test.describe('JVM Tuning view', () => {
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

    const defaultResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && new URL(response.url()).pathname.endsWith('/api/jvm-tuning')
    )
    await openView('jvm-tuning', 'JVM Tuning')
    const defaultResult = await defaultResponse
    expect(defaultResult.status()).toBe(200)
    const defaultReport = await defaultResult.json()

    const virtualThreadsStatus = page.locator('.virtual-threads-status').first()
    const jvmOptionsCard = page.locator('.card', {hasText: 'Bare metal JVM options'}).first()
    const kubernetesCard = page.locator('.card', {hasText: 'Kubernetes calculator'}).first()
    const kubernetesYaml = kubernetesCard.locator('.options-box code')
    await expect(virtualThreadsStatus).toBeVisible()
    await expect(virtualThreadsStatus).toContainText('Virtual threads enabled')
    await expect(virtualThreadsStatus).toContainText('conservative platform-thread reserve')
    await expect(virtualThreadsStatus).toHaveClass(/alert-info/)
    await expect(page.locator('#virtualThreadsEnabled')).toHaveCount(0)
    await expect(jvmOptionsCard).toBeVisible()
    await expect(kubernetesCard).toBeVisible()
    expect(typeof defaultReport.calculation.valid).toBe('boolean')
    if (defaultReport.calculation.valid) {
      expect(defaultReport.calculation.error).toBeNull()
      expect(defaultReport.suggestedJvmOptions).toMatch(/-Xmx[1-9]\d*m/)
      expect(defaultReport.kubernetes.yaml).toContain('JAVA_TOOL_OPTIONS')
    } else {
      expect(defaultReport.calculation.error).toContain('Insufficient heap budget')
      expect(defaultReport.calculation.heapBytes).toBe(0)
      expect(defaultReport.calculation.jvmOptions).toBe('')
      expect(defaultReport.suggestedJvmOptions).toBe('')
      expect(defaultReport.kubernetes.javaToolOptions).toBe('')
      expect(defaultReport.kubernetes.yaml).toBe('')
      expect(defaultReport.kubernetes.qosClass).toBe('Unavailable')
      expect(defaultReport.kubernetes.warnings).toContain(defaultReport.calculation.error)
      await expect(page.locator('.card', {hasText: 'Bare metal JVM calculator'})).toContainText(
        'Insufficient heap budget'
      )
      await expect(jvmOptionsCard.getByRole('button', {name: /Copy/})).toBeDisabled()
      await expect(kubernetesYaml).toContainText('Adjust calculator inputs until a valid heap is available.')
    }

    await page.locator('#jvm-target-memory').fill('32768')
    await page.locator('#jvm-target-memory').blur()
    await expect(kubernetesYaml).toContainText('memory: "32768Mi"')
    await expect(kubernetesCard).toContainText('Depends on CPU')
    await expect(kubernetesCard.locator('#kubernetesBurstableEnabled')).not.toBeChecked()
    await expect(kubernetesCard.locator('#kubernetesActuatorEnabled')).toBeChecked()
    await expect(kubernetesYaml).toContainText('JAVA_TOOL_OPTIONS')
    await expect(kubernetesYaml).toContainText('MaxRAMPercentage')
    await expect(kubernetesYaml).toContainText('MinRAMPercentage')
    await expect(kubernetesYaml).toContainText('port: http')
    await expect(kubernetesYaml).toContainText('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED')
    await expect(kubernetesYaml).toContainText('readinessProbe')
    await expect(kubernetesYaml).not.toContainText('spring.threads.virtual.enabled=true')
    await expect(kubernetesYaml).not.toContainText('-Xmx')
    await expect(kubernetesCard).not.toContainText('Garbage collector:')
    await expect(kubernetesCard).toContainText('Sizing notes')

    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    await expect(optionsBlock).toContainText(/-Xmx[1-9]\d*m/)
    await expect(optionsBlock).not.toContainText('spring.threads.virtual.enabled=true')

    const copyButton = jvmOptionsCard.getByRole('button', {name: /Copy/})
    await copyButton.click()
    await expect(page.getByRole('button', {name: /Copied!/})).toBeVisible({timeout: 5_000})
  })

  test('Kubernetes toggles update the deployment snippet', async ({openView, page}) => {
    await openView('jvm-tuning', 'JVM Tuning')

    const kubernetesCard = page.locator('.card', {hasText: 'Kubernetes calculator'}).first()
    const yamlBlock = kubernetesCard.locator('.options-box code')
    const burstableToggle = kubernetesCard.locator('#kubernetesBurstableEnabled')
    const actuatorToggle = kubernetesCard.locator('#kubernetesActuatorEnabled')

    await expect(kubernetesCard).toBeVisible()
    // The default calculator total is capped at 2048 MiB, while the burstable request follows the JVM's
    // committed memory. After a long suite a JVM on a large-RAM machine can commit more than that cap,
    // so pin a limit comfortably above any e2e snapshot before asserting that burstable lowers the request.
    await page.locator('#jvm-target-memory').fill('32768')
    await page.locator('#jvm-target-memory').blur()
    await expect(yamlBlock).toContainText('memory: "32768Mi"')
    const equalRequestYaml = await yamlBlock.innerText()
    const equalRequest = equalRequestYaml.match(/requests:\s+memory: "([^"]+)"/)?.[1]
    expect(equalRequest).toBeTruthy()

    await burstableToggle.check()
    await expect
      .poll(
        async () => {
          const yaml = await yamlBlock.innerText()
          const request = yaml.match(/requests:\s+memory: "([^"]+)"/)
          expect(request).not.toBeNull()
          return request[1]
        },
        {timeout: 5_000}
      )
      .not.toBe(equalRequest)
    await expect(kubernetesCard).toContainText('Burstable')

    await actuatorToggle.uncheck()
    await expect(yamlBlock).toContainText('JAVA_TOOL_OPTIONS')
    await expect(yamlBlock).not.toContainText('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED')
    await expect(yamlBlock).not.toContainText('startupProbe')
    await expect(yamlBlock).not.toContainText('readinessProbe')
  })

  test('editing total memory updates the recommended -Xmx', async ({openView, page}) => {
    await openView('jvm-tuning', 'JVM Tuning')

    const calculatorCard = page.locator('.card', {hasText: 'Bare metal JVM calculator'})
    const jvmOptionsCard = page.locator('.card', {hasText: 'Bare metal JVM options'}).first()
    await expect(calculatorCard).toBeVisible()

    const totalInput = calculatorCard.locator('input[type="number"]').first()
    await expect(totalInput).toBeVisible()
    await expect(totalInput).not.toHaveValue('')

    const optionsBlock = jvmOptionsCard.locator('.options-box code')
    const kubernetesYaml = page.locator('.card', {hasText: 'Kubernetes calculator'}).locator('.options-box code')
    await totalInput.fill('32768')
    await totalInput.blur()
    await expect(kubernetesYaml).toContainText('memory: "32768Mi"')
    await expect(optionsBlock).toContainText(/-Xmx[1-9]\d*m/)
    const before = await optionsBlock.innerText()
    const beforeMatch = before.match(/-Xmx([1-9]\d*)m/)
    expect(beforeMatch).not.toBeNull()
    const beforeXmx = parseInt(beforeMatch[1], 10)
    expect(beforeXmx).toBeGreaterThan(0)

    await totalInput.fill('65536')
    await totalInput.blur()
    await expect(kubernetesYaml).toContainText('memory: "65536Mi"')
    await expect(optionsBlock).toContainText(/-Xmx[1-9]\d*m/)
    const afterMatch = (await optionsBlock.innerText()).match(/-Xmx([1-9]\d*)m/)
    expect(afterMatch).not.toBeNull()
    expect(parseInt(afterMatch[1], 10)).toBeGreaterThan(beforeXmx)
  })
})
