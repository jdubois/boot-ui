// @ts-check

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} [options]
 */
export function registerJvmSizingInputTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test.describe('JVM sizing input provenance', () => {
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
