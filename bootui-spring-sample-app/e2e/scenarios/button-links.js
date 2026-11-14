// @ts-check

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 */
export function registerButtonLinkTests(test, expect) {
  test.describe('Button-shaped navigation links', () => {
    for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
      test(`shows a decorative chain-link cue in ${theme}`, async ({page}) => {
        await page.setViewportSize({width: 390, height: 844})
        await page.addInitScript((theme) => localStorage.setItem('bootui.theme', theme), theme)
        await page.goto('/bootui/#/scorecard')

        const panel = page.getByRole('link', {name: 'Open panel: Architecture', exact: true})
        const homepage = page.getByRole('link', {name: 'Application homepage', exact: true})
        await expect(panel).toBeVisible()
        await expect(panel).toHaveAttribute('href', '#/architecture')
        await expect(homepage).toHaveAttribute('href', '/')

        for (const link of [panel, homepage]) {
          const cue = await link.evaluate((element) => {
            const style = getComputedStyle(element, '::before')
            return {
              content: style.content,
              mask: style.maskImage,
              width: style.width,
              height: style.height,
              gap: style.marginInlineEnd,
              inheritsColor: style.backgroundColor === getComputedStyle(element).color
            }
          })
          expect(cue).toMatchObject({
            content: '""',
            width: '14px',
            height: '14px',
            gap: '6px',
            inheritsColor: true
          })
          expect(cue.mask).not.toBe('none')
          await expect(link.locator('.bi')).toBeHidden()
        }

        const action = page.getByRole('button', {name: /^(?:Re-run|Run) all scanners$/})
        expect(await action.evaluate((element) => getComputedStyle(element, '::before').content)).toBe('none')

        await panel.focus()
        await expect(panel).toBeFocused()
        expect(await panel.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none')

        await expect(panel).toBeVisible()
        expect(
          await page
            .locator('html, main, .bootui-workspace')
            .evaluateAll((elements) => elements.every((element) => element.scrollWidth <= element.clientWidth))
        ).toBe(true)
        expect(await panel.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)

        await page.setViewportSize({width: 1600, height: 900})
        await expect(panel).toBeVisible()
        await panel.press('Enter')
        await expect(page).toHaveURL(/#\/architecture$/)
        await expect(page.getByRole('heading', {name: 'Architecture', exact: true})).toBeVisible()
      })
    }
  })
}
