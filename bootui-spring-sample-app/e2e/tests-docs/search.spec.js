import {test, expect} from '@playwright/test'

for (const colorScheme of ['light', 'dark']) {
  test(`search has an opaque surface and no backdrop blur in ${colorScheme} mode`, async ({page}, testInfo) => {
    await page.emulateMedia({colorScheme})
    await page.goto('/')
    await page.getByRole('button', {name: 'Decline', exact: true}).click()
    const searchButton = page.getByRole('button', {name: 'Search', exact: true})
    await searchButton.click()

    const modal = page.locator('.vp-search-modal')
    const input = page.getByRole('searchbox')
    await expect(modal).toBeVisible()
    await expect(input).toBeFocused()
    await expect(modal).toHaveCSS('background-color', colorScheme === 'dark' ? 'rgb(30, 41, 59)' : 'rgb(255, 255, 255)')
    await expect(page.locator('.vp-search-mask')).toHaveCSS('backdrop-filter', 'none')
    await expect(page.locator('.vp-search-mask')).toHaveCSS('background-color', 'rgba(15, 23, 42, 0.48)')
    await expect(page.locator('.vp-theme-container')).toHaveCSS('--vp-c-bg', 'transparent')

    await input.fill('health')
    const results = page.locator('.vp-search-record-matches')
    await expect(results.first()).toBeVisible()
    await page.screenshot({path: testInfo.outputPath('search-results.png')})
    await input.fill('')
    await expect(page.locator('.vp-search-suggestions')).toBeHidden()
    await input.press('Escape')
    await expect(modal).toHaveCount(0)

    await searchButton.click()
    await expect(modal).toBeVisible()
    if (testInfo.project.name === 'mobile') {
      await page.getByRole('button', {name: 'Cancel', exact: true}).click()
    } else {
      await page.locator('.vp-search-mask').click({position: {x: 5, y: 5}})
    }
    await expect(modal).toHaveCount(0)
  })
}
