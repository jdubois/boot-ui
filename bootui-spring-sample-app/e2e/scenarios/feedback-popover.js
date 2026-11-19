// @ts-check

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 */
export function registerFeedbackPopoverTests(test, expect) {
  test.describe('Feedback & contributions', () => {
    test('stays passive until opened and opens the survey in a separate tab', async ({page, context}) => {
      const surveyUrl =
        'https://docs.google.com/forms/d/e/1FAIpQLScFH1nLhhcOStIBi_PFKSj3nqSVZ-PRajCrXeLx3pb3HySbSg/viewform?usp=dialog'
      const externalRequests = []
      await context.route(/^https:\/\/(?:docs\.google\.com|github\.com)\//, async (route) => {
        externalRequests.push(route.request().url())
        await route.fulfill({contentType: 'text/html', body: '<title>Feedback destination</title>'})
      })
      await page.goto('/bootui/#/scorecard')
      const trigger = page.getByRole('button', {name: 'Help improve BootUI', exact: true})
      const popover = page.locator('.feedback-popover')
      await expect(trigger).toHaveCount(1)
      await expect(trigger).toHaveAttribute('aria-expanded', 'false')
      await expect(popover).toBeHidden()
      expect(externalRequests).toEqual([])

      await trigger.click()
      const region = page.getByRole('region', {name: 'Feedback & contributions', exact: true})
      await expect(region).toHaveCount(1)
      await expect(region).toBeVisible()
      const survey = region.getByRole('link', {name: /Take the user survey.*opens in a new tab/})
      const github = region.getByRole('link', {name: /Contribute on GitHub.*opens in a new tab/})
      await expect(survey).toHaveAttribute('href', surveyUrl)
      await expect(github).toHaveAttribute('href', 'https://github.com/jdubois/boot-ui')
      for (const link of [survey, github]) {
        await expect(link).toHaveAttribute('target', '_blank')
        await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
      }
      expect(externalRequests).toEqual([])

      const popupPromise = page.waitForEvent('popup')
      await survey.click()
      const popup = await popupPromise
      await popup.waitForLoadState('domcontentloaded')
      expect(popup.url()).toBe(surveyUrl)
      expect(externalRequests).toEqual([surveyUrl])
      await popup.close()
      await expect(page).toHaveURL(/#\/scorecard$/)
      await expect(popover).toBeHidden()
      await expect(trigger).toBeFocused()
    })

    test('supports keyboard entry, focus dismissal, click-away, and the collapsed rail', async ({page}) => {
      await page.goto('/bootui/#/scorecard')
      const trigger = page.getByRole('button', {name: 'Help improve BootUI', exact: true})
      const popover = page.locator('.feedback-popover')
      await trigger.focus()
      await trigger.press('ArrowDown')
      await expect(popover.getByRole('link', {name: /Take the user survey/})).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(popover).toBeHidden()
      await expect(trigger).toBeFocused()

      await trigger.press('ArrowUp')
      await expect(popover.getByRole('link', {name: /Contribute on GitHub/})).toBeFocused()
      await page.locator('.cp-trigger').focus()
      await expect(popover).toBeHidden()
      await expect(page.locator('.cp-trigger')).toBeFocused()

      await trigger.click()
      await expect(popover).toBeVisible()
      await page.locator('.topbar-title').click()
      await expect(popover).toBeHidden()

      await page.locator('.sidebar-toggle').click()
      await expect(page.locator('aside')).toHaveClass(/bootui-sidebar--collapsed/)
      await expect(trigger).toHaveAccessibleName('Help improve BootUI')
      await expect(trigger.locator('.feedback-trigger__label')).toBeHidden()
      await trigger.click()
      await expect(popover).toBeVisible()
      await expect(popover.getByRole('link')).toHaveCount(2)

      await page.keyboard.press('Control+k')
      await expect(page.getByRole('dialog', {name: 'Command palette'})).toBeVisible()
      await expect(popover).toBeHidden()
    })

    test('keeps mobile focus contained and dismisses feedback before navigation', async ({page}) => {
      await page.setViewportSize({width: 390, height: 844})
      await page.emulateMedia({reducedMotion: 'reduce'})
      await page.goto('/bootui/#/scorecard')
      const hamburger = page.locator('.nav-hamburger')
      const drawer = page.locator('#bootui-mobile-navigation')
      const trigger = page.getByRole('button', {name: 'Help improve BootUI', exact: true})
      const popover = page.locator('.feedback-popover')
      const brand = drawer.locator('.brand-card')

      await hamburger.click()
      await brand.focus()
      await page.keyboard.press('Shift+Tab')
      await expect(trigger).toBeFocused()
      await page.keyboard.press('Tab')
      await expect(brand).toBeFocused()

      await trigger.focus()
      await trigger.press('ArrowDown')
      await expect(popover.getByRole('link', {name: /Take the user survey/})).toBeFocused()
      const bounds = await popover.boundingBox()
      expect(bounds.x).toBeGreaterThanOrEqual(12)
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(378)
      expect(bounds.y).toBeGreaterThanOrEqual(12)
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(832)
      expect(await popover.evaluate((element) => getComputedStyle(element).transitionDuration)).toBe('0s')

      await page.keyboard.press('Escape')
      await expect(trigger).toBeFocused()
      await expect(popover).toBeHidden()
      await expect(drawer).toHaveAttribute('aria-modal', 'true')
      await expect(hamburger).toHaveAttribute('aria-expanded', 'true')

      await page.keyboard.press('Escape')
      await expect(hamburger).toHaveAttribute('aria-expanded', 'false')
      await expect(hamburger).toBeFocused()

      await hamburger.click()
      await trigger.click()
      await expect(popover).toBeVisible()
      await drawer.locator('.sidebar-toggle').click()
      await expect(popover).toBeHidden()
      await expect(drawer).toHaveAttribute('inert', '')
      await expect(hamburger).toBeFocused()
    })

    for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
      test(`fits the viewport with visible focus in ${theme}`, async ({page}) => {
        await page.setViewportSize({width: 1280, height: 900})
        await page.addInitScript((theme) => localStorage.setItem('bootui.theme', theme), theme)
        await page.goto('/bootui/#/scorecard')
        const trigger = page.getByRole('button', {name: 'Help improve BootUI', exact: true})
        await trigger.press('ArrowDown')
        const popover = page.locator('.feedback-popover')
        const survey = popover.getByRole('link', {name: /Take the user survey/})
        await expect(survey).toBeFocused()
        expect(await survey.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none')
        await expect(popover).toHaveCSS('opacity', '1')
        const bounds = await popover.boundingBox()
        expect(bounds.x).toBeGreaterThanOrEqual(12)
        expect(bounds.x + bounds.width).toBeLessThanOrEqual(1268)
        expect(bounds.y).toBeGreaterThanOrEqual(12)
        expect(bounds.y + bounds.height).toBeLessThanOrEqual(888)
        expect(await popover.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
      })
    }
  })
}
