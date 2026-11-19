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

test('Home guides keep text and keyboard focus readable in every theme', async ({page}) => {
  test.setTimeout(60_000)
  await page.emulateMedia({reducedMotion: 'reduce'})
  const choose = async (id) => {
    await page.locator(`[data-home-choice="${id}"]`).click()
    await expect(page.locator('.home-guide-stage')).toHaveAttribute('aria-busy', 'false')
  }
  const checkText = async (theme) => {
    const readings = await page.locator('.home-guide-step').evaluate((guide) =>
      [
        ...guide.querySelectorAll(
          '.home-guide-context, .home-guide-explanation, .home-guide-choice-title, .home-guide-panel-reason, .home-guide-panel-status, .home-guide-resource p, .home-guide-resource li, .home-guide-resource pre, .home-guide-resource h3, .home-guide-copy, .home-guide-documentation, h2:not(.visually-hidden)'
        )
      ]
        .filter((element) => element.checkVisibility())
        .map((element) => {
          const layers = []
          for (let node = element; node; node = node.parentElement) {
            const style = getComputedStyle(node)
            layers.unshift({color: style.backgroundColor, image: style.backgroundImage})
          }
          return {text: element.textContent.trim(), color: getComputedStyle(element).color, layers}
        })
    )
    expect(readings.length).toBeGreaterThan(3)
    for (const reading of readings) {
      let fills = [{red: 255, green: 255, blue: 255, alpha: 1}]
      for (const layer of reading.layers) {
        fills = fills.map((fill) => composite(parseColor(layer.color), fill))
        // Paint lower background images before the translucent gradients above them.
        const images = []
        let depth = 0
        let start = 0
        for (let index = 0; index < layer.image.length; index++) {
          if (layer.image[index] === '(') depth++
          if (layer.image[index] === ')') depth--
          if (layer.image[index] === ',' && depth === 0) {
            images.push(layer.image.slice(start, index))
            start = index + 1
          }
        }
        images.push(layer.image.slice(start))
        for (const image of images.reverse()) {
          const stops = image.match(/rgba?\([^)]*\)/g) || []
          if (stops.length) fills = fills.flatMap((fill) => stops.map((stop) => composite(parseColor(stop), fill)))
        }
      }
      const foreground = parseColor(reading.color)
      const minimumContrast = fills.reduce(
        (minimum, fill) => Math.min(minimum, contrastRatio(foreground, fill)),
        Infinity
      )
      expect(minimumContrast, `${theme}: ${reading.text}`).toBeGreaterThanOrEqual(4.5)
    }
  }
  for (const theme of ['light', 'dark', 'graphite', 'minimal', 'cyberpunk', 'dsfr', 'win95']) {
    await page.goto('/bootui/#/home')
    await page.evaluate((value) => localStorage.setItem('bootui.theme', value), theme)
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)
    await checkText(theme)
    await page.keyboard.press('Tab')
    const choice = page.locator('[data-home-choice="understand"]')
    const header = page.getByRole('button', {name: 'Home', exact: true})
    for (const control of [header, choice]) {
      await control.focus()
      const focus = await control.evaluate((element) => {
        const style = getComputedStyle(element)
        return {style: style.outlineStyle, width: parseFloat(style.outlineWidth)}
      })
      expect(focus.style).toBe('solid')
      expect(focus.width).toBeGreaterThanOrEqual(2)
    }
    const shadows = await header.evaluate((element) => {
      return {
        action: getComputedStyle(element).textShadow,
        heading: getComputedStyle(element.closest('h2')).textShadow
      }
    })
    expect(shadows.action).toBe(shadows.heading)
    await choose('understand')
    await checkText(theme)
    await choose('memory')
    await checkText(theme)
    await page.locator('[data-home-back="root"]').click()
    await expect(page.locator('.home-guide-stage')).toHaveAttribute('aria-busy', 'false')
    await choose('agents')
    await checkText(theme)
    for (const resource of ['agent-skill', 'agent-playbooks']) {
      await choose(resource)
      await checkText(theme)
      if (resource === 'agent-skill') {
        const copy = page.getByRole('button', {name: /^Copy commands:/}).first()
        await page.keyboard.press('Tab')
        await expect(copy).toBeFocused()
        const focus = await copy.evaluate((element) => {
          const style = getComputedStyle(element)
          return {style: style.outlineStyle, width: parseFloat(style.outlineWidth)}
        })
        // Win95 deliberately gives standard buttons the native dotted focus rectangle.
        expect(focus.style).toBe(theme === 'win95' ? 'dotted' : 'solid')
        expect(focus.width).toBeGreaterThanOrEqual(theme === 'win95' ? 1 : 2)
        await copy.hover()
        await checkText(theme)
      }
      await page.locator('[data-home-back="topics"]').click()
      await expect(page.locator('.home-guide-stage')).toHaveAttribute('aria-busy', 'false')
    }
  }
})

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
  // Seven theme reloads plus a Live Activity view each outgrow the default 30s budget on a slow runner.
  test.setTimeout(120_000)
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
  // Tab colors ease over 150ms; under reduced motion PanelTabs drops the transition, so the
  // colors read right after hover() are the settled hover state rather than a midpoint.
  await page.emulateMedia({reducedMotion: 'reduce'})
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
