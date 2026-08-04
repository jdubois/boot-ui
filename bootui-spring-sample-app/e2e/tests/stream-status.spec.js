// @ts-check
import {expect, test} from './fixtures.js'

const STREAMING_PANELS = [
  ['activity', 'Live Activity', '/bootui/api/activity/stream'],
  ['exceptions', 'Exceptions', '/bootui/api/exceptions/stream'],
  ['rest-client-trace', 'REST Client', '/bootui/api/rest-client-trace/stream'],
  ['security-logs', 'Security Logs', '/bootui/api/security-logs/stream'],
  ['sql-trace', 'SQL Trace', '/bootui/api/sql-trace/stream']
]

/**
 * @param {import('@playwright/test').Page} page
 */
async function installControlledEventSource(page) {
  await page.addInitScript(() => {
    /** @type {ControlledEventSource[]} */
    const sources = []

    class ControlledEventSource {
      /**
       * @param {string | URL} url
       */
      constructor(url) {
        this.url = String(url)
        this.closed = false
        /** @type {Map<string, Array<(event: Event) => void>>} */
        this.listeners = new Map()
        sources.push(this)
      }

      /**
       * @param {string} type
       * @param {(event: Event) => void} listener
       */
      addEventListener(type, listener) {
        const listeners = this.listeners.get(type) ?? []
        listeners.push(listener)
        this.listeners.set(type, listeners)
      }

      close() {
        this.closed = true
      }

      /**
       * @param {string} type
       */
      emit(type) {
        for (const listener of this.listeners.get(type) ?? []) {
          listener(new Event(type))
        }
      }
    }

    Reflect.set(window, '__bootuiEventSources', sources)
    Reflect.set(window, 'EventSource', ControlledEventSource)
  })
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {string} type
 */
async function emitLatestStreamEvent(page, type) {
  await page.evaluate((eventType) => {
    const sources = Reflect.get(window, '__bootuiEventSources')
    sources.at(-1).emit(eventType)
  }, type)
}

/**
 * @param {import('@playwright/test').Page} page
 */
async function streamCount(page) {
  return page.evaluate(() => Reflect.get(window, '__bootuiEventSources').length)
}

/**
 * @param {import('@playwright/test').Page} page
 */
async function latestStreamUrl(page) {
  return page.evaluate(() => Reflect.get(window, '__bootuiEventSources').at(-1)?.url)
}

/**
 * @param {import('@playwright/test').Page} page
 */
async function readStatusStyles(page) {
  return page.locator('.stream-status-indicator').evaluate((indicator) => {
    const retryButton = indicator.querySelector('.stream-status-retry')
    const label = indicator.querySelector('.stream-status-label--unavailable')
    const icon = indicator.querySelector('.stream-status-icon--unavailable')
    if (!(retryButton instanceof HTMLElement) || !(label instanceof HTMLElement) || !(icon instanceof HTMLElement)) {
      throw new Error('Stream status elements are missing')
    }
    const retryStyle = getComputedStyle(retryButton)
    return {
      background: getComputedStyle(indicator).backgroundColor,
      iconColor: getComputedStyle(icon).color,
      labelColor: getComputedStyle(label).color,
      outlineColor: retryStyle.outlineColor,
      outlineStyle: retryStyle.outlineStyle,
      outlineWidth: retryStyle.outlineWidth,
      retryColor: retryStyle.color
    }
  })
}

/**
 * @param {number[]} channels
 */
function relativeLuminance([red, green, blue]) {
  const [r, g, b] = [red, green, blue].map((channel) => {
    const normalized = channel / 255
    return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * @param {string} color
 */
function parseRgb(color) {
  const channels = color.match(/\d+/g)
  if (!channels || channels.length < 3) {
    throw new Error(`Expected an RGB color, got: ${color}`)
  }
  return channels.slice(0, 3).map(Number)
}

/**
 * @param {string} foreground
 * @param {string} background
 */
function contrastRatio(foreground, background) {
  const foregroundLuminance = relativeLuminance(parseRgb(foreground))
  const backgroundLuminance = relativeLuminance(parseRgb(background))
  return (
    (Math.max(foregroundLuminance, backgroundLuminance) + 0.05) /
    (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
  )
}

/**
 * @param {Awaited<ReturnType<typeof readStatusStyles>>} styles
 */
function expectAccessibleStatusStyles(styles) {
  expect(contrastRatio(styles.labelColor, styles.background)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(styles.retryColor, styles.background)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(styles.iconColor, styles.background)).toBeGreaterThanOrEqual(3)
  expect(contrastRatio(styles.outlineColor, styles.background)).toBeGreaterThanOrEqual(3)
  expect(styles.outlineStyle).toBe('solid')
  expect(styles.outlineWidth).toBe('2px')
}

test.describe('stream connection status', () => {
  test('surfaces connection degradation on every managed SSE panel', async ({openView, page}) => {
    await installControlledEventSource(page)

    for (const [route, heading, streamUrl] of STREAMING_PANELS) {
      await openView(route, heading)
      await expect.poll(() => latestStreamUrl(page)).toBe(streamUrl)

      await emitLatestStreamEvent(page, 'error')

      await expect(page.locator('.stream-status-indicator')).toContainText('Reconnecting')
    }
  })

  test('supports accessible manual recovery in dark and reduced-motion modes', async ({openView, page}) => {
    await page.clock.install()
    await page.emulateMedia({reducedMotion: 'reduce'})
    await installControlledEventSource(page)
    await openView('exceptions', 'Exceptions')

    for (const delay of [1_000, 2_000, 4_000, 8_000]) {
      const countBeforeFailure = await streamCount(page)
      await emitLatestStreamEvent(page, 'error')
      await expect(page.locator('.stream-status-indicator')).toContainText('Reconnecting')
      await expect(page.locator('.stream-status-dot')).toHaveCSS('animation-name', 'none')
      await page.clock.fastForward(delay)
      await expect.poll(() => streamCount(page)).toBe(countBeforeFailure + 1)
    }

    await emitLatestStreamEvent(page, 'error')
    await expect(page.locator('.stream-status-indicator')).toContainText('Stream unavailable')

    const retry = page.getByRole('button', {name: 'Retry stream connection now'})
    await retry.focus()
    await expect(retry).toBeFocused()
    expectAccessibleStatusStyles(await readStatusStyles(page))

    await page.evaluate(() => {
      document.documentElement.dataset.bootuiTheme = 'dark'
    })
    await retry.focus()
    await expect(retry).toBeFocused()
    expectAccessibleStatusStyles(await readStatusStyles(page))

    const countBeforeRetry = await streamCount(page)
    await retry.click()
    await expect.poll(() => streamCount(page)).toBe(countBeforeRetry + 1)
    await expect(page.locator('.stream-status-indicator')).toContainText('Reconnecting')

    await emitLatestStreamEvent(page, 'open')
    await expect(page.locator('.stream-status-indicator')).toBeHidden()
    await expect(page.locator('[role="status"][aria-live="polite"]')).toHaveText('Stream connected.')
  })
})
