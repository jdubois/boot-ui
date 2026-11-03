import {NAVIGATION_GROUPS} from '../../../bootui-ui/src/main/frontend/src/utils/panelNavigation.js'

export function registerSampleLabTests(test, expect, {groups, actions, failureEndpoint, previewGroup = 'Data'}) {
  test.describe('Sample action lab', () => {
    test('leads with Requests then follows menu order with passive, keyboard-accessible, bookmarkable tabs', async ({
      page
    }) => {
      const workloads = []
      const errors = []
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith('/api/')) workloads.push(request.url())
      })
      page.on('pageerror', (error) => errors.push(error.message))
      await page.goto('/')
      const tabs = page.getByRole('tablist', {name: 'Scenario groups'}).getByRole('tab')
      await expect(tabs).toHaveText(groups)
      await expect(tabs.first()).toHaveText('Requests')
      await expect(tabs.first()).toHaveAttribute('aria-selected', 'true')
      await expect(page.getByRole('tabpanel', {name: 'Requests', exact: true})).toBeVisible()
      const menuGroups = {
        Runtime: 'runtime',
        Threads: 'runtime',
        Data: 'database',
        Security: 'security',
        'Integrations & AI': 'services'
      }
      const order = NAVIGATION_GROUPS.map(({key}) => key)
      const positions = groups.slice(1).map((group) => order.indexOf(menuGroups[group]))
      expect(positions.every((position) => position >= 0)).toBe(true)
      expect(positions).toEqual([...positions].sort((a, b) => a - b))
      for (const group of groups) {
        await page.getByRole('tab', {name: group, exact: true}).click()
        await expect(page.getByRole('tabpanel')).toHaveCount(1)
        await expect(page.getByRole('tabpanel', {name: group, exact: true})).toBeVisible()
        await expect(page.locator('[role="tab"][aria-selected="true"]')).toHaveCount(1)
      }
      await page.reload()
      await expect(tabs.last()).toHaveAttribute('aria-selected', 'true')
      await tabs.last().focus()
      await page.keyboard.press('ArrowRight')
      await expect(tabs.first()).toBeFocused()
      await page.keyboard.press('End')
      await expect(tabs.last()).toBeFocused()
      await page.keyboard.press('Home')
      await expect(tabs.first()).toBeFocused()
      await page.keyboard.press('ArrowLeft')
      await expect(tabs.last()).toBeFocused()
      await page.goto('/#lab-threads')
      await expect(page.getByRole('tabpanel', {name: 'Threads', exact: true})).toBeVisible()
      expect(workloads).toEqual([])
      expect(errors).toEqual([])
    })

    test('shares theme and lab assets with compact desktop and reachable mobile actions', async ({
      page,
      request
    }, testInfo) => {
      for (const [asset, contentType] of [
        ['bootui-theme.css', 'text/css'],
        ['sample-lab.css', 'text/css'],
        ['sample-lab.js', 'javascript']
      ]) {
        const response = await request.get(`/${asset}`)
        expect(response.ok(), asset).toBe(true)
        expect(response.headers()['content-type']).toContain(contentType)
      }
      await page.setViewportSize({width: 1600, height: 900})
      await page.goto('/')
      expect(
        await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--bootui-green').trim())
      ).toBe('#198754')
      for (const group of groups) {
        await page.getByRole('tab', {name: group, exact: true}).click()
        if (group === previewGroup) {
          await page.screenshot({path: testInfo.outputPath('sample-lab-desktop.webp'), type: 'webp', quality: 80})
        }
        const buttons = page.getByRole('tabpanel').getByRole('button')
        for (const button of await buttons.all()) {
          const box = await button.boundingBox()
          expect(box?.height).toBeGreaterThanOrEqual(44)
          expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(900)
        }
      }
      await page.setViewportSize({width: 390, height: 844})
      for (const group of groups) {
        await page.getByRole('tab', {name: group, exact: true}).click()
        await page.evaluate(() => window.scrollTo(0, 0))
        if (group === 'Threads') {
          await page.screenshot({path: testInfo.outputPath('sample-lab-mobile.webp'), type: 'webp', quality: 80})
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
        const codeFits = await page
          .getByRole('tabpanel')
          .locator('code')
          .evaluateAll((elements) =>
            elements.every((element) =>
              [...element.getClientRects()].every(
                (rect) => rect.right <= element.parentElement.getBoundingClientRect().right + 1
              )
            )
          )
        expect(codeFits).toBe(true)
        const firstAction = await page.getByRole('tabpanel').getByRole('button').first().boundingBox()
        expect((firstAction?.y ?? 0) + (firstAction?.height ?? 0)).toBeLessThanOrEqual(844)
        for (const button of await page.getByRole('tabpanel').getByRole('button').all()) {
          expect((await button.boundingBox())?.height).toBeGreaterThanOrEqual(44)
        }
      }
    })

    test('preserves every existing action with disclosed responses and real bounded thread work', async ({page}) => {
      await page.goto('/')
      for (const [group, label, expected] of actions) {
        await page.getByRole('tab', {name: group, exact: true}).click()
        const button = page.getByRole('button', {name: label, exact: true})
        await button.click()
        await expect(page.locator('#sample-action-status')).toContainText(expected)
        await expect(button).toBeEnabled()
        await expect(page.locator('#sample-action-feedback')).toBeVisible()
        await expect(page.locator('#sample-action-details')).not.toHaveAttribute('open')
        await page.getByText('Response details', {exact: true}).click()
        await expect(page.locator('#sample-action-result')).toBeVisible()
        if (group === 'Threads') {
          await expect(page.locator('#sample-action-result')).toContainText('Thread source → Live')
          await expect(page.locator('#sample-action-result')).toContainText('Capture snapshot → Contention')
          await expect(page.locator('#sample-action-result')).toContainText('Observe threads → Observe & compare')
        }
      }
    })

    test('opens failures explicitly and lets the same action recover', async ({page}) => {
      const [group, label, expected] = actions[0]
      await page.goto('/')
      await page.getByRole('tab', {name: group, exact: true}).click()
      await page.route(`**${failureEndpoint}`, (route) =>
        route.fulfill({status: 503, contentType: 'application/json', body: '{"error":"Unavailable"}'})
      )
      const button = page.getByRole('button', {name: label, exact: true})
      await button.click()
      await expect(page.locator('#sample-action-status')).toHaveText('Action failed')
      await expect(page.locator('#sample-action-result')).toBeVisible()
      await expect(page.locator('#sample-action-result')).toContainText('503')
      await expect(button).toBeEnabled()
      await page.unroute(`**${failureEndpoint}`)
      await button.click()
      await expect(page.locator('#sample-action-status')).toContainText(expected)
      await expect(page.locator('#sample-action-details')).not.toHaveAttribute('open')
    })
  })
}
