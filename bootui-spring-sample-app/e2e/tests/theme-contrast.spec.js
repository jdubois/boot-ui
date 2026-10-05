// @ts-check
import {expect, test} from './fixtures.js'

function parseColor(value) {
  const channels = value.match(/[\d.]+/g)?.map(Number)
  if (!channels || channels.length < 3) throw new Error(`Unsupported computed color: ${value}`)
  return {
    red: channels[0],
    green: channels[1],
    blue: channels[2],
    alpha: channels[3] ?? 1
  }
}

function composite(foreground, background) {
  const alpha = foreground.alpha + background.alpha * (1 - foreground.alpha)
  return {
    red: (foreground.red * foreground.alpha + background.red * background.alpha * (1 - foreground.alpha)) / alpha,
    green: (foreground.green * foreground.alpha + background.green * background.alpha * (1 - foreground.alpha)) / alpha,
    blue: (foreground.blue * foreground.alpha + background.blue * background.alpha * (1 - foreground.alpha)) / alpha,
    alpha
  }
}

function linearChannel(channel) {
  const srgb = channel / 255
  return srgb <= 0.04045 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4
}

function relativeLuminance(color) {
  return 0.2126 * linearChannel(color.red) + 0.7152 * linearChannel(color.green) + 0.0722 * linearChannel(color.blue)
}

function contrastRatio(foreground, background) {
  const foregroundLuminance = relativeLuminance(foreground)
  const backgroundLuminance = relativeLuminance(background)
  return (
    (Math.max(foregroundLuminance, backgroundLuminance) + 0.05) /
    (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
  )
}

test('keeps placeholders, helper text, and selected identifiers readable in every theme', async ({page, openView}) => {
  // Every opt-in skin re-skins these same surfaces, so all of them are held to the same bar.
  for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
    await page.goto('/bootui/')
    await page.evaluate((value) => localStorage.setItem('bootui.theme', value), theme)
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)

    await openView('loggers', 'Loggers')
    const placeholderColors = await page.locator('input[placeholder="Filter loggers by name…"]').evaluate((input) => {
      const rootStyle = getComputedStyle(document.documentElement)
      return {
        background: getComputedStyle(input).backgroundColor,
        placeholder: getComputedStyle(input, '::placeholder').color,
        subtleToken: rootStyle.getPropertyValue('--bootui-text-subtle').trim(),
        surfaceSolid: rootStyle.getPropertyValue('--bootui-surface-solid').trim()
      }
    })
    expect(placeholderColors.placeholder).toBe(
      await page.evaluate((token) => {
        const probe = document.createElement('span')
        probe.style.color = token
        document.body.append(probe)
        const computed = getComputedStyle(probe).color
        probe.remove()
        return computed
      }, placeholderColors.subtleToken)
    )
    const inputBackground = composite(
      parseColor(placeholderColors.background),
      parseColor(
        await page.evaluate((token) => {
          const probe = document.createElement('span')
          probe.style.color = token
          document.body.append(probe)
          const computed = getComputedStyle(probe).color
          probe.remove()
          return computed
        }, placeholderColors.surfaceSolid)
      )
    )
    expect(contrastRatio(parseColor(placeholderColors.placeholder), inputBackground)).toBeGreaterThanOrEqual(4.5)

    await openView('health', 'Health')
    await expect(page.locator('.last-fetched-text')).toBeVisible()
    const helperUsesSubtleToken = await page.locator('.last-fetched-text').evaluate((helper) => {
      const rootStyle = getComputedStyle(document.documentElement)
      const probe = document.createElement('span')
      probe.style.color = rootStyle.getPropertyValue('--bootui-text-subtle')
      document.body.append(probe)
      const tokenColor = getComputedStyle(probe).color
      probe.remove()
      return getComputedStyle(helper).color === tokenColor
    })
    expect(helperUsesSubtleToken).toBe(true)

    await openView('metrics', 'Metrics')
    const selectedMeter = page.locator('.meter-list .list-group-item-action.active').first()
    await expect(selectedMeter).toBeVisible()
    const selectedIdentifierColors = await selectedMeter.evaluate((row) => ({
      background: getComputedStyle(row).backgroundColor,
      foreground: getComputedStyle(row.querySelector('code')).color
    }))
    expect(
      contrastRatio(parseColor(selectedIdentifierColors.foreground), parseColor(selectedIdentifierColors.background))
    ).toBeGreaterThanOrEqual(4.5)
  }
})

/* The picker floats over dense panel content with no backdrop-filter behind it, so a
   translucent surface token lets page text read straight through the options. Every
   skin had independently patched this in its own stylesheet while the shared light and
   dark defaults stayed see-through, which is exactly the kind of drift a shared
   assertion catches. */
test('keeps the theme picker opaque in every theme', async ({page}) => {
  for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
    await page.goto('/bootui/')
    await page.evaluate((value) => localStorage.setItem('bootui.theme', value), theme)
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)

    await page.getByRole('button', {name: /^Theme:/}).click()
    const menu = page.getByRole('menu', {name: 'Theme'})
    await expect(menu).toBeVisible()

    const background = await menu.evaluate((element) => getComputedStyle(element).backgroundColor)
    expect(parseColor(background).alpha, `${theme} theme picker background: ${background}`).toBe(1)

    await page.keyboard.press('Escape')
  }
})

test('keeps the request profile drawer opaque in every theme', async ({page, openView}) => {
  const search = await page.request.get('/api/sample/product-search')
  expect(search.ok()).toBeTruthy()

  for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
    await page.goto('/bootui/')
    await page.evaluate((value) => localStorage.setItem('bootui.theme', value), theme)
    await page.reload()
    await openView('activity', 'Live Activity')
    await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)

    const searchRow = page.locator('.activity-table tbody tr', {hasText: '/api/sample/product-search'}).first()
    await expect(searchRow).toBeVisible()
    await searchRow.getByRole('button', {name: /Profile/}).click()
    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()

    const background = await drawer.evaluate((element) => {
      const style = getComputedStyle(element)
      return {color: style.backgroundColor, image: style.backgroundImage}
    })
    expect(parseColor(background.color).alpha, `${theme} request profile background: ${background.color}`).toBe(1)
    expect(background.image, `${theme} request profile background image`).toBe('none')
  }
})

/* Panel tabs once inherited Bootstrap's link blue and ignored every skin. This reads the
   rendered strip, so a stray Bootstrap rule or an unthemed override fails here even when
   the tokens themselves are sound. */
test('keeps panel tabs legible and their focus visible in every theme', async ({page, openView}) => {
  for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
    await page.goto('/bootui/')
    await page.evaluate((value) => localStorage.setItem('bootui.theme', value), theme)
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)

    await openView('conditions', 'Auto-configuration conditions')
    const selected = page.getByRole('tab', {name: /Positive/})
    const unselected = page.getByRole('tab', {name: /Negative/})
    await expect(selected).toHaveAttribute('aria-selected', 'true')
    await page.mouse.move(0, 0)

    const paint = (tab) =>
      tab.evaluate((element) => {
        const layers = []
        for (let node = element; node; node = node.parentElement) {
          layers.push(getComputedStyle(node).backgroundColor)
        }
        const style = getComputedStyle(element)
        return {
          color: style.color,
          image: style.backgroundImage,
          layers,
          textDecoration: style.textDecorationLine
        }
      })
    const background = ({image, layers}) => {
      // Composite the element's own fill over every ancestor's, down to the white canvas.
      let color = {red: 255, green: 255, blue: 255, alpha: 1}
      for (const layer of [...layers].reverse()) color = composite(parseColor(layer), color)
      const stops = [...image.matchAll(/rgba?\([^)]*\)/g)].map((match) => composite(parseColor(match[0]), color))
      return stops.length ? stops : [color]
    }
    const ratios = (state) => background(state).map((fill) => contrastRatio(parseColor(state.color), fill))

    const rest = await paint(unselected)
    expect(rest.textDecoration, `${theme} unselected tab decoration`).toBe('none')
    for (const ratio of ratios(rest)) expect(ratio, `${theme} unselected tab`).toBeGreaterThanOrEqual(4.5)
    for (const ratio of ratios(await paint(selected))) {
      expect(ratio, `${theme} selected tab`).toBeGreaterThanOrEqual(4.5)
    }

    await unselected.hover()
    for (const ratio of ratios(await paint(unselected))) {
      expect(ratio, `${theme} hovered tab`).toBeGreaterThanOrEqual(4.5)
    }

    await selected.focus()
    await page.keyboard.press('ArrowRight')
    await expect(unselected).toBeFocused()
    await expect(unselected).toHaveAttribute('aria-selected', 'true')
    const focusRing = await unselected.evaluate((element) => {
      const style = getComputedStyle(element)
      return {visible: element.matches(':focus-visible'), outline: style.outlineStyle, width: style.outlineWidth}
    })
    expect(focusRing.visible, `${theme} keyboard focus`).toBe(true)
    expect(focusRing.outline, `${theme} focus outline`).not.toBe('none')
    expect(Number.parseFloat(focusRing.width), `${theme} focus outline width`).toBeGreaterThan(0)
  }
})
