// @ts-check

export function registerStaleAssetTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  const recoveryAlert = (page) => page.getByRole('alert').filter({hasText: 'BootUI could not load this panel'})
  const heading = (page, name) => page.locator('main h2').filter({hasText: name}).first()

  function recordRequests(page) {
    const documents = []
    const writes = []
    page.on('request', (request) => {
      if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documents.push(request.url())
      if (!['GET', 'HEAD'].includes(request.method())) writes.push(request.url())
    })
    return {documents, writes}
  }

  async function openHibernate(page) {
    const link = page.locator('aside a[href="#/hibernate"]')
    const group = page
      .locator('aside .bootui-nav-section')
      .filter({has: page.locator('a[href="#/hibernate"]')})
      .getByRole('button')
    if ((await group.getAttribute('aria-expanded')) !== 'true') await group.click()
    await link.click()
  }

  test.describe('stale UI assets', () => {
    for (const [theme, width] of [
      ['light', 1600],
      ['dark', 390]
    ]) {
      test(`offers visible keyboard recovery in ${theme} at ${width}px`, async ({page}) => {
        await page.setViewportSize({width, height: 900})
        await page.addInitScript((value) => localStorage.setItem('bootui.theme', value), theme)
        await page.route(
          (url) => url.pathname.startsWith(`${uiPath}/assets/Hibernate-`) && url.pathname.endsWith('.js'),
          (route) => route.fulfill({status: 404, body: ''})
        )
        await page.goto(`${uiPath}/#/hibernate`)
        const alert = recoveryAlert(page)
        await expect(alert).toHaveCount(1)
        const reload = alert.getByRole('button', {name: 'Reload BootUI', exact: true})
        await expect(reload).toBeVisible()
        await reload.focus()
        await expect(reload).toBeFocused()
        const bounds = await alert.boundingBox()
        expect(bounds.x).toBeGreaterThanOrEqual(0)
        expect(bounds.x + bounds.width).toBeLessThanOrEqual(width)
        expect(bounds.y + bounds.height).toBeLessThanOrEqual(900)
      })
    }

    test('keeps unsaved input until consent, then reloads the current UI into the intended panel', async ({page}) => {
      const {documents, writes} = recordRequests(page)
      const missingAssets = []
      const staleChunk = (url) => url.pathname.startsWith(`${uiPath}/assets/Hibernate-`) && url.pathname.endsWith('.js')
      await page.route(staleChunk, async (route) => {
        missingAssets.push(route.request().url())
        await route.fulfill({status: 404, contentType: 'text/plain', body: 'Asset removed after rebuild'})
      })
      await page.goto(`${uiPath}/?mode=dev#/http-probe`)
      await heading(page, /^HTTP Probe/).waitFor()
      await page.getByLabel('Path', {exact: true}).fill('/keep-my-unsent-request')
      await openHibernate(page)

      const alert = recoveryAlert(page)
      await expect(alert).toHaveCount(1)
      await expect(alert).toContainText('Could not open Hibernate')
      await expect(alert).toContainText('Reloading discards unsaved input')
      await expect(heading(page, /^HTTP Probe/)).toBeVisible()
      await expect(page.getByLabel('Path', {exact: true})).toHaveValue('/keep-my-unsent-request')
      expect(missingAssets).toHaveLength(1)
      expect(documents).toHaveLength(1)
      expect(writes).toEqual([])

      await page.unroute(staleChunk)
      const reload = alert.getByRole('button', {name: 'Reload BootUI', exact: true})
      await reload.focus()
      await expect(reload).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(heading(page, /^Hibernate$/)).toBeVisible()
      await expect(page).toHaveURL(new RegExp(`${uiPath}/\\?mode=dev#/hibernate$`))
      await expect(alert).toHaveCount(0)
      expect(documents).toHaveLength(2)
      expect(writes).toEqual([])
    })

    test('preserves a deep link and shows a persistent failure without a reload loop', async ({page}) => {
      const {documents, writes} = recordRequests(page)
      const staleChunk = (url) => url.pathname.startsWith(`${uiPath}/assets/Hibernate-`) && url.pathname.endsWith('.js')
      await page.route(staleChunk, (route) =>
        route.fulfill({status: 404, contentType: 'text/plain', body: 'Asset still missing'})
      )
      const destination = `${uiPath}/?mode=dev#/hibernate?tab=findings#details`
      await page.goto(destination)
      const alert = recoveryAlert(page)
      await expect(alert).toBeVisible()
      expect(documents).toHaveLength(1)
      await alert.getByRole('button', {name: 'Reload BootUI', exact: true}).click()
      await expect(alert).toBeVisible()
      await expect(page).toHaveURL(new RegExp(`${uiPath}/\\?mode=dev#/hibernate\\?tab=findings#details$`))
      expect(documents).toHaveLength(2)
      // An unrelated, healthy navigation remains usable and clears the stale failure.
      await page.getByRole('link', {name: 'Overview', exact: true}).click()
      await expect(heading(page, /^Overview/)).toBeVisible()
      await expect(alert).toHaveCount(0)
      expect(documents).toHaveLength(2)
      expect(writes).toEqual([])
    })

    test('recovers a missing lazy stylesheet', async ({page}) => {
      const {documents} = recordRequests(page)
      const stylesheet = (url) => url.pathname.startsWith(`${uiPath}/assets/Health-`) && url.pathname.endsWith('.css')
      await page.route(stylesheet, (route) => route.fulfill({status: 404, body: ''}))
      await page.goto(`${uiPath}/#/health`)
      await expect(recoveryAlert(page)).toContainText('Could not open Health')
      expect(documents).toHaveLength(1)
      await page.unroute(stylesheet)
      await recoveryAlert(page).getByRole('button', {name: 'Reload BootUI', exact: true}).click()
      await expect(heading(page, /^Health/)).toBeVisible()
      await expect(recoveryAlert(page)).toHaveCount(0)
      expect(documents).toHaveLength(2)
    })

    test('does not reload or lose input when the application is offline', async ({page, context}) => {
      const {documents, writes} = recordRequests(page)
      await page.goto(`${uiPath}/#/http-probe`)
      await heading(page, /^HTTP Probe/).waitFor()
      await page.getByLabel('Path', {exact: true}).fill('/unsent')
      await context.setOffline(true)
      await openHibernate(page)
      await expect(recoveryAlert(page)).toBeVisible()
      await openHibernate(page)
      await expect(page.getByLabel('Path', {exact: true})).toHaveValue('/unsent')
      expect(documents).toHaveLength(1)
      expect(writes).toEqual([])
      await context.setOffline(false)
    })

    test('leaves API errors to the panel instead of offering a stale-asset reload', async ({page}) => {
      const {documents, writes} = recordRequests(page)
      await page.route(
        (url) => url.pathname === `${apiPath}/health`,
        (route) => route.fulfill({status: 500, contentType: 'application/json', body: '{"error":"Report failed"}'})
      )
      await page.goto(`${uiPath}/#/health`)
      await expect(heading(page, /^Health$/)).toBeVisible()
      await expect(page.locator('main')).toContainText('500')
      await expect(recoveryAlert(page)).toHaveCount(0)
      expect(documents).toHaveLength(1)
      expect(writes).toEqual([])
    })

    test('does not treat module evaluation bugs as stale assets', async ({page}) => {
      const errors = []
      page.on('console', (message) => {
        if (message.type() === 'error') errors.push(message.text())
      })
      const {documents} = recordRequests(page)
      await page.route(
        (url) => url.pathname.startsWith(`${uiPath}/assets/Hibernate-`) && url.pathname.endsWith('.js'),
        (route) =>
          route.fulfill({
            contentType: 'application/javascript',
            body: 'throw new Error("Panel module evaluation failed"); export default {}'
          })
      )
      await page.goto(`${uiPath}/#/http-probe`)
      await heading(page, /^HTTP Probe/).waitFor()
      await openHibernate(page)
      await expect.poll(() => errors.some((message) => message.includes('Panel module evaluation failed'))).toBe(true)
      await expect(recoveryAlert(page)).toHaveCount(0)
      await expect(heading(page, /^HTTP Probe/)).toBeVisible()
      expect(documents).toHaveLength(1)
    })
  })
}
