// @ts-check

// Cached CONNECTED data must not grant this page permission to refresh externally.
function cachedDashboard() {
  return {
    available: true,
    connected: true,
    status: 'CONNECTED',
    refreshedAt: Date.now() - 45_000,
    repository: {
      fullName: 'jdubois/boot-ui',
      htmlUrl: 'https://github.com/jdubois/boot-ui',
      localBranch: 'main'
    },
    credential: {source: 'GITHUB_TOKEN', authenticated: true},
    metrics: [{label: 'Open pull requests', value: '2', tone: 'primary'}]
  }
}

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} paths
 */
export function registerGitHubConsentTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test.describe('GitHub consent', () => {
    test('requires keyboard Connect before refreshing, keeps consent on return, and clears it on reload', async ({
      page
    }) => {
      await page.clock.install()
      const payload = JSON.stringify(cachedDashboard())
      let refreshCount = 0
      let cacheReads = 0
      await page.route(`**${apiPath}/github`, async (route) => {
        expect(route.request().method()).toBe('GET')
        cacheReads += 1
        await route.fulfill({contentType: 'application/json', body: payload})
      })
      await page.route(`**${apiPath}/github/refresh`, async (route) => {
        expect(route.request().method()).toBe('POST')
        refreshCount += 1
        await route.fulfill({contentType: 'application/json', body: payload})
      })

      await page.goto(`${uiPath}/#/github`)
      const connect = page.getByRole('button', {name: 'Connect', exact: true})
      await expect(connect).toBeEnabled()
      await expect(page.getByText('Cached data only.', {exact: true})).toBeVisible()
      await expect(page.getByRole('link', {name: 'jdubois/boot-ui'})).toBeVisible()
      await page.clock.runFor(61_000)
      expect(refreshCount).toBe(0)
      await connect.focus()
      await page.keyboard.press('Enter')
      await expect.poll(() => refreshCount).toBe(1)
      await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeFocused()
      await expect(page.getByRole('button', {name: 'Refresh panel', exact: true})).toBeEnabled()
      await page.clock.runFor(61_000)
      await expect.poll(() => cacheReads).toBe(2)
      await expect.poll(() => refreshCount).toBe(2)

      await page.evaluate(() => (window.location.hash = '#/beans'))
      await expect(page.locator('main h2').filter({hasText: /Beans/})).toBeVisible()
      await page.clock.runFor(180_000)
      expect(refreshCount).toBe(2)
      await page.evaluate(() => (window.location.hash = '#/github'))
      await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeVisible()
      await expect.poll(() => cacheReads).toBe(2)
      expect(refreshCount).toBe(2)
      await page.clock.runFor(30_000)
      expect(refreshCount).toBe(2)
      await page.clock.runFor(31_000)
      await expect.poll(() => cacheReads).toBe(4)
      await expect.poll(() => refreshCount).toBe(3)

      await page.evaluate(() => {
        Object.defineProperty(document, 'visibilityState', {configurable: true, get: () => 'hidden'})
        document.dispatchEvent(new Event('visibilitychange'))
      })
      await page.clock.runFor(180_000)
      expect(refreshCount).toBe(3)
      await page.evaluate(() => {
        Object.defineProperty(document, 'visibilityState', {configurable: true, get: () => 'visible'})
        document.dispatchEvent(new Event('visibilitychange'))
      })
      expect(refreshCount).toBe(3)
      await page.clock.runFor(30_000)
      expect(refreshCount).toBe(3)
      await page.clock.runFor(31_000)
      await expect.poll(() => cacheReads).toBe(5)
      await expect.poll(() => refreshCount).toBe(4)

      await page.getByRole('button', {name: 'Stop', exact: true}).click()
      await page.clock.runFor(120_000)
      expect(refreshCount).toBe(4)
      await page.getByRole('button', {name: 'Connect', exact: true}).click()
      await expect.poll(() => refreshCount).toBe(5)
      await page.reload()
      await expect(connect).toBeEnabled()
      await page.clock.runFor(120_000)
      expect(refreshCount).toBe(5)
    })

    test('preserves the cached snapshot and timestamp when refresh fails', async ({page}) => {
      const dashboard = cachedDashboard()
      await page.route(`**${apiPath}/github`, (route) =>
        route.fulfill({contentType: 'application/json', body: JSON.stringify(dashboard)})
      )
      await page.route(`**${apiPath}/github/refresh`, (route) =>
        route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            available: true,
            connected: false,
            status: 'ERROR',
            message: 'GitHub credentials were rejected.',
            refreshedAt: Date.now(),
            repository: dashboard.repository
          })
        })
      )
      await page.goto(`${uiPath}/#/github`)
      const snapshot = page.locator('main time')
      await expect(snapshot).toHaveAttribute('datetime', new Date(dashboard.refreshedAt).toISOString())
      await page.getByRole('button', {name: 'Connect', exact: true}).click()
      await expect(page.getByRole('alert')).toContainText('GitHub credentials were rejected.')
      await expect(snapshot).toHaveAttribute('datetime', new Date(dashboard.refreshedAt).toISOString())
      await expect(page.getByRole('button', {name: /Open pull requests/})).toContainText('2')
      await expect(page.getByText('The previous snapshot is still shown; the refresh did not succeed.')).toBeVisible()
    })
  })
}
