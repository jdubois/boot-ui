// @ts-check

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} [options]
 */
export function registerJvmSizingInputTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test.describe('JVM sizing input provenance', () => {
    test('keeps the Metaspace cap coherent and explains its snapshot limits', async ({page}) => {
      await page.goto(`${uiPath}/#/jvm-tuning`)
      await expect(page.getByRole('heading', {name: 'JVM Tuning', exact: true})).toBeVisible()
      const total = page.locator('#jvm-target-memory')
      await expect(total).not.toHaveValue('')
      await page.getByLabel('Toggle auto-refresh', {exact: true}).uncheck()
      const totalMiB = Number(await total.inputValue()) === 65536 ? 32768 : 65536
      const [response] = await Promise.all([
        page.waitForResponse((response) => {
          const url = new URL(response.url())
          return (
            response.request().method() === 'GET' &&
            url.pathname === `${apiPath}/jvm-tuning` &&
            url.searchParams.get('totalMemoryMb') === String(totalMiB)
          )
        }),
        total.fill(String(totalMiB)).then(() => total.blur())
      ])
      expect(response.status()).toBe(200)
      const report = await response.json()
      expect(report.calculation.valid).toBe(true)
      expect(report.calculation.totalMemoryBytes).toBe(totalMiB * 1024 * 1024)
      const metaspaceMiB = report.calculation.metaspaceBytes / (1024 * 1024)
      expect(Number.isInteger(metaspaceMiB)).toBe(true)
      expect(metaspaceMiB).toBeGreaterThan(0)
      const observedMetaspace = report.pools.find((pool) => pool.name === 'Metaspace')
      if (observedMetaspace) {
        expect(Number.isInteger(observedMetaspace.committedBytes)).toBe(true)
        expect(observedMetaspace.committedBytes).toBeGreaterThanOrEqual(0)
        expect(report.calculation.metaspaceBytes).toBeGreaterThanOrEqual(
          Math.ceil(observedMetaspace.committedBytes * 1.25)
        )
      } else {
        expect(report.kubernetes.warnings.join(' ')).toContain('Metaspace committed is unavailable')
      }
      const option = `-XX:MaxMetaspaceSize=${metaspaceMiB}m`
      expect(report.suggestedJvmOptions.split(' ')).toContain(option)
      expect(report.kubernetes.javaToolOptions.split(' ')).toContain(option)
      const calculator = page.locator('.card', {hasText: 'Bare metal JVM calculator'})
      const kubernetes = page.locator('.card', {hasText: 'Kubernetes calculator'})
      await expect(calculator).toContainText('observed committed metadata plus 25%, when available')
      await expect(calculator).not.toContainText('metaspace sized for')
      await expect(
        page.locator('.card', {hasText: 'Bare metal JVM options'}).locator('.options-box code')
      ).toContainText(option)
      await expect(kubernetes.locator('.options-box code')).toContainText(option)
      await expect(kubernetes).toContainText('OutOfMemoryError: Metaspace')
      const heapMiB = (report.calculation.heapBytes / (1024 * 1024)).toFixed(3).replace(/\.?0+$/, '')
      await expect(kubernetes.locator('.kubernetes-heap-model')).toContainText(`MODEL: ${heapMiB} MiB heap`)
      await expect(kubernetes.locator('.kubernetes-heap-model')).toContainText(`Assumes ${totalMiB} MiB memory limit`)
      await expect(kubernetes).toContainText(`Initial RAM percentage: ${report.kubernetes.initialRamPercentage}%`)
      await expect(kubernetes.locator('.kubernetes-heap-guidance')).toContainText(
        'Recalculate and replace the snippet whenever the memory limit changes'
      )
      await expect(kubernetes).toContainText("Paketo Java buildpacks' memory calculator")
      await expect(kubernetes).toContainText('Red Hat UBI OpenJDK run-java.sh')
      await test.info().attach('heap-percentage-model', {
        body: await kubernetes.locator('.kubernetes-heap-model').locator('..').screenshot(),
        contentType: 'image/png'
      })
    })

    test('keeps MODEL heap context tied to the returned limit and hides it for an infeasible fixture', async ({
      page
    }) => {
      const MB = 1024 * 1024
      await page.route(`**${apiPath}/jvm-tuning*`, async (route) => {
        const response = await route.fetch()
        const report = await response.json()
        const limitMiB = Number(new URL(route.request().url()).searchParams.get('totalMemoryMb') ?? 1024)
        const valid = limitMiB !== 128
        const heapMiB = limitMiB === 400 ? 2 : limitMiB === 2048 ? 1536.5 : 512
        const percentage = limitMiB === 400 ? 0.5 : limitMiB === 2048 ? 75.024 : 50
        const javaToolOptions = valid
          ? `-XX:MaxRAMPercentage=${percentage} -XX:MinRAMPercentage=${percentage} -XX:InitialRAMPercentage=${percentage}`
          : ''
        report.jvmInputArguments = ['-Xms256m', '-Xmx256m']
        report.suggestedJvmOptions = valid ? `-Xms${Math.floor(heapMiB)}m -Xmx${Math.floor(heapMiB)}m` : ''
        report.calculation = {
          ...report.calculation,
          totalMemoryBytes: limitMiB * MB,
          heapBytes: valid ? heapMiB * MB : 0,
          jvmOptions: report.suggestedJvmOptions,
          valid,
          error: valid ? null : 'Insufficient heap budget (fixture).'
        }
        report.kubernetes = {
          ...report.kubernetes,
          requestMemoryBytes: valid ? limitMiB * MB : 0,
          requestMemory: valid ? `${limitMiB}Mi` : '',
          limitMemoryBytes: limitMiB * MB,
          limitMemory: `${limitMiB}Mi`,
          detectedContainerLimitBytes: 4096 * MB,
          detectedContainerLimitMemory: '4096Mi',
          maxRamPercentage: valid ? percentage : 0,
          initialRamPercentage: valid ? percentage : 0,
          javaToolOptions,
          qosClass: valid ? 'Depends on CPU' : 'Unavailable',
          warnings: valid ? [] : [report.calculation.error],
          yaml: valid ? `limits:\n  memory: "${limitMiB}Mi"\nJAVA_TOOL_OPTIONS: "${javaToolOptions}"` : ''
        }
        await route.fulfill({response, json: report})
      })
      await page.goto(`${uiPath}/#/jvm-tuning`)
      await expect(page.getByRole('heading', {name: 'JVM Tuning', exact: true})).toBeVisible()
      await expect(page.locator('#jvm-target-memory')).toHaveValue('1024')
      await page.getByLabel('Toggle auto-refresh', {exact: true}).uncheck()
      const kubernetes = page.locator('.card', {hasText: 'Kubernetes calculator'})
      await expect(kubernetes.locator('.kubernetes-heap-model')).toContainText('MODEL: 512 MiB heap')
      for (const [limitMiB, heapMiB, percentage] of [
        [2048, 1536.5, 75.024],
        [400, 2, 0.5]
      ]) {
        await page.locator('#jvm-target-memory').fill(String(limitMiB))
        await page.locator('#jvm-target-memory').blur()
        await expect(kubernetes.locator('.kubernetes-heap-model')).toContainText(`Assumes ${limitMiB} MiB memory limit`)
        await expect(kubernetes.locator('.kubernetes-heap-model')).toContainText(`MODEL: ${heapMiB} MiB heap`)
        await expect(kubernetes).toContainText(`Initial RAM percentage: ${percentage}%`)
        await expect(kubernetes).toContainText('Detected cgroup memory limit: 4096Mi')
        await expect(kubernetes.locator('.kubernetes-heap-guidance')).toContainText(
          'not actual JVM -Xmx or a peak-workload guarantee'
        )
        await expect(kubernetes.locator('.options-box code')).toContainText(`MaxRAMPercentage=${percentage}`)
        await expect(page.locator('.card', {hasText: 'Current JVM Arguments'})).toContainText('-Xmx256m')
      }
      await page.locator('#jvm-target-memory').fill('128')
      await page.locator('#jvm-target-memory').blur()
      await expect(kubernetes.locator('.options-box code')).toHaveText(
        'Adjust calculator inputs until a valid heap is available.'
      )
      await expect(kubernetes.locator('.kubernetes-heap-model')).toHaveCount(0)
      await expect(kubernetes.locator('.kubernetes-heap-guidance')).toHaveCount(0)
      await expect(kubernetes.getByRole('button', {name: /Copy YAML$/})).toBeDisabled()
    })

    test('keeps defaults automatic until a sizing input is edited', async ({page}) => {
      await page.goto(`${uiPath}/#/jvm-tuning`)
      await expect(page.getByRole('heading', {name: 'JVM Tuning', exact: true})).toBeVisible()

      const total = page.locator('#jvm-target-memory')
      const refresh = page.getByRole('button', {name: 'Refresh panel', exact: true})
      await expect(total).not.toHaveValue('')
      await expect(refresh).toBeEnabled()
      await page.getByLabel('Toggle auto-refresh', {exact: true}).uncheck()

      /**
       * @param {() => Promise<unknown>} action
       * @param {(url: URL) => boolean} [matches]
       */
      async function responseFor(action, matches = () => true) {
        const [response] = await Promise.all([
          page.waitForResponse((response) => {
            const url = new URL(response.url())
            return response.request().method() === 'GET' && url.pathname === `${apiPath}/jvm-tuning` && matches(url)
          }),
          action()
        ])
        expect(response.status()).toBe(200)
        await expect(refresh).toBeEnabled()
        return new URL(response.url())
      }

      /** @param {URL} url */
      function expectAutomaticSizing(url) {
        for (const name of ['totalMemoryMb', 'threadCount', 'headRoomPercent']) {
          expect(url.searchParams.has(name), `${name} remains a server default`).toBe(false)
        }
      }

      expectAutomaticSizing(await responseFor(() => refresh.click()))

      const burstable = page.locator('#kubernetesBurstableEnabled')
      const nextBurstable = !(await burstable.isChecked())
      expectAutomaticSizing(
        await responseFor(
          () => burstable.setChecked(nextBurstable),
          (url) => url.searchParams.get('kubernetesBurstableEnabled') === String(nextBurstable)
        )
      )

      const currentTotal = Number(await total.inputValue())
      expect(Number.isFinite(currentTotal)).toBe(true)
      const editedTotal = currentTotal < 65536 ? Math.min(65536, currentTotal + 64) : currentTotal - 64
      const edited = await responseFor(
        async () => {
          await total.fill(String(editedTotal))
          await total.blur()
        },
        (url) => url.searchParams.get('totalMemoryMb') === String(editedTotal)
      )
      expect(edited.searchParams.get('totalMemoryMb')).toBe(String(editedTotal))
      expect(edited.searchParams.has('threadCount')).toBe(false)
      expect(edited.searchParams.has('headRoomPercent')).toBe(false)
    })
  })
}
