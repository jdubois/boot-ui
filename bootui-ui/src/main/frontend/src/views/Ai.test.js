import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import {resetLowFootprintForTests} from '../utils/useLowFootprint.js'
import Ai from './Ai.vue'
import FlashBanner from './components/FlashBanner.vue'

const viewsRoot = path.dirname(fileURLToPath(import.meta.url))
const appSource = fs.readFileSync(path.join(viewsRoot, '../App.vue'), 'utf8')
const {descriptor: appDescriptor} = parseSfc(appSource, {filename: 'App.vue'})
const appCss = appDescriptor.styles.map((style) => style.content).join('\n')

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function deferred() {
  let resolve
  const promise = new Promise((res) => {
    resolve = res
  })
  return {promise, resolve}
}

function overviewWithChats() {
  return {
    enabled: true,
    springAiDetected: true,
    langChain4jDetected: false,
    totalChats: 2,
    errorCount: 0,
    totalInputTokens: 30,
    totalOutputTokens: 12,
    tokensByModel: {},
    callsByModel: {},
    recent: [
      {spanId: 'first', provider: 'test', requestModel: 'first-model', statusCode: 'OK'},
      {spanId: 'second', provider: 'test', requestModel: 'second-model', statusCode: 'OK'}
    ]
  }
}

function cssBlock(marker) {
  const markerIndex = appCss.indexOf(marker)
  if (markerIndex < 0) throw new Error(`Missing CSS block: ${marker}`)
  const openingBrace = appCss.indexOf('{', markerIndex)
  let depth = 0
  for (let index = openingBrace; index < appCss.length; index += 1) {
    if (appCss[index] === '{') depth += 1
    if (appCss[index] === '}') depth -= 1
    if (depth === 0) return appCss.slice(openingBrace + 1, index)
  }
  throw new Error(`Unclosed CSS block: ${marker}`)
}

function declarations(block) {
  return Object.fromEntries(
    [...block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((match) => [match[1], match[2].trim()])
  )
}

const lightTokens = declarations(cssBlock(':global(:root) {'))
const darkTokens = {...lightTokens, ...declarations(cssBlock(":global(:root[data-bootui-theme='dark']) {"))}

function parseColor(value) {
  const hex = value.slice(1)
  return {
    red: Number.parseInt(hex.slice(0, 2), 16),
    green: Number.parseInt(hex.slice(2, 4), 16),
    blue: Number.parseInt(hex.slice(4, 6), 16)
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
  const foregroundLuminance = relativeLuminance(parseColor(foreground))
  const backgroundLuminance = relativeLuminance(parseColor(background))
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

function aiOverview() {
  return {
    enabled: true,
    springAiDetected: true,
    langChain4jDetected: false,
    totalChats: 2,
    errorCount: 0,
    totalInputTokens: 120,
    totalOutputTokens: 30,
    averageDurationNanos: 1_000_000,
    tokensByModel: {zeta: 100, alpha: 50},
    callsByModel: {zeta: 1, alpha: 5},
    recent: []
  }
}

function tokenSeries() {
  return {
    minutes: 60,
    buckets: [
      {epochMinute: 30_000_000, inputTokens: 40, outputTokens: 10, callCount: 1},
      {epochMinute: 30_000_001, inputTokens: 80, outputTokens: 20, callCount: 2}
    ]
  }
}

describe('Ai', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    resetLowFootprintForTests()
    vi.unstubAllGlobals()
  })

  it('explains that telemetry is paused when low-footprint mode is active', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(
            jsonResponse({
              enabled: true,
              springAiDetected: true,
              langChain4jDetected: false,
              totalChats: 0,
              totalInputTokens: 0,
              totalOutputTokens: 0,
              tokensByModel: {},
              callsByModel: {},
              recent: []
            })
          )
        }
        if (url.startsWith('api/ai/tokens')) return Promise.resolve(jsonResponse({buckets: []}))
        if (url === 'api/live-memory/footprint') {
          return Promise.resolve(jsonResponse({active: true, components: [], warnings: []}))
        }
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai, {
      global: {provide: {panels: ref({panels: [{id: 'live-memory', enabled: true, available: true}]})}}
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Telemetry paused')
    expect(wrapper.text()).not.toContain("BootUI's telemetry capture is active")
  })

  it('uses native buttons and aria-sort for sortable table headers', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(jsonResponse(aiOverview()))
        }
        return Promise.resolve(jsonResponse({buckets: []}))
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    const modelHeader = wrapper.findAll('th').find((header) => header.text().trim() === 'Model')
    const sortButton = modelHeader.get('button.sort-button')
    expect(sortButton.element.tagName).toBe('BUTTON')
    expect(modelHeader.attributes('aria-sort')).toBe('none')

    await sortButton.trigger('click')
    expect(modelHeader.attributes('aria-sort')).toBe('descending')
    expect(wrapper.get('table tbody tr code').text()).toBe('zeta')

    await sortButton.trigger('click')
    expect(modelHeader.attributes('aria-sort')).toBe('ascending')
    expect(wrapper.get('table tbody tr code').text()).toBe('alpha')
    expect(wrapper.get('[role="progressbar"][aria-label="alpha token share"]').attributes('aria-valuetext')).toBe(
      '50% of tokens'
    )
  })

  it('keeps overview data visible and announces partial token history', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(
            jsonResponse({
              enabled: true,
              springAiDetected: true,
              langChain4jDetected: false,
              totalChats: 1,
              totalInputTokens: 42,
              totalOutputTokens: 7,
              tokensByModel: {'qwen2.5:0.5b': 49},
              callsByModel: {'qwen2.5:0.5b': 1},
              recent: []
            })
          )
        }
        return Promise.resolve({ok: false, status: 503, json: () => Promise.resolve({})})
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    expect(wrapper.text()).toContain('Total tokens')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Partial AI usage data')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Token history could not be refreshed (HTTP 503)')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Overview data remains available')
    expect(wrapper.getComponent(FlashBanner).find('button.btn-close').exists()).toBe(false)
  })

  it('treats a rejected token request as partial when the overview succeeds', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(
            jsonResponse({
              enabled: true,
              springAiDetected: true,
              langChain4jDetected: false,
              totalChats: 1,
              totalInputTokens: 42,
              totalOutputTokens: 7,
              tokensByModel: {},
              callsByModel: {},
              recent: []
            })
          )
        }
        return Promise.reject(new TypeError('Failed to fetch'))
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    expect(wrapper.text()).toContain('Total tokens')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Partial AI usage data')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Token history could not be refreshed')
    expect(wrapper.text()).not.toContain('Unable to load AI usage data')
  })

  it('ignores delayed token JSON after the selected time window changes', async () => {
    const staleSeries = deferred()
    let token60Requests = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') return Promise.resolve(jsonResponse(overviewWithChats()))
        if (url === 'api/ai/tokens?minutes=60') {
          token60Requests += 1
          if (token60Requests === 1) {
            return Promise.resolve(
              jsonResponse({
                minutes: 60,
                buckets: [{epochMinute: 1, inputTokens: 1, outputTokens: 1, callCount: 1}]
              })
            )
          }
          return Promise.resolve({ok: true, status: 200, json: () => staleSeries.promise})
        }
        if (url === 'api/ai/tokens?minutes=15') {
          return Promise.resolve(
            jsonResponse({
              minutes: 15,
              buckets: [{epochMinute: 1, inputTokens: 2, outputTokens: 1, callCount: 1}]
            })
          )
        }
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    await wrapper.get('select[aria-label="Token usage time window"]').setValue('15')
    await flushPromises()
    expect(wrapper.text()).toContain('Token usage (last 15 min)')

    staleSeries.resolve({
      minutes: 60,
      buckets: [{epochMinute: 1, inputTokens: 100, outputTokens: 50, callCount: 10}]
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Token usage (last 15 min)')
    expect(wrapper.text()).not.toContain('Token usage (last 60 min)')
  })

  it('ignores a stale chat detail response after another chat is selected', async () => {
    const firstDetail = deferred()
    const secondDetail = deferred()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') return Promise.resolve(jsonResponse(overviewWithChats()))
        if (url === 'api/ai/tokens?minutes=60') return Promise.resolve(jsonResponse({buckets: []}))
        if (url === 'api/ai/chats/first') return firstDetail.promise
        if (url === 'api/ai/chats/second') return secondDetail.promise
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    const detailButtons = wrapper.findAll('button[aria-label="Toggle chat details"]')
    await detailButtons[0].trigger('click')
    await detailButtons[1].trigger('click')

    secondDetail.resolve(jsonResponse({summary: {requestModel: 'second-detail'}}))
    await flushPromises()
    expect(wrapper.text()).toContain('second-detail')

    firstDetail.resolve(jsonResponse({summary: {requestModel: 'stale-first-detail'}}))
    await flushPromises()
    expect(wrapper.text()).toContain('second-detail')
    expect(wrapper.text()).not.toContain('stale-first-detail')
  })

  it('does not reopen chat detail after the drawer is closed while loading', async () => {
    const firstDetail = deferred()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') return Promise.resolve(jsonResponse(overviewWithChats()))
        if (url === 'api/ai/tokens?minutes=60') return Promise.resolve(jsonResponse({buckets: []}))
        if (url === 'api/ai/chats/first') return firstDetail.promise
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    const detailButton = wrapper.findAll('button[aria-label="Toggle chat details"]')[0]
    await detailButton.trigger('click')
    await detailButton.trigger('click')

    firstDetail.resolve(jsonResponse({summary: {requestModel: 'closed-detail'}}))
    await flushPromises()
    expect(wrapper.text()).not.toContain('closed-detail')
    expect(wrapper.find('.chat-detail-row').exists()).toBe(false)
  })

  it('renders chart colors through BootUI theme tokens', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(url === 'api/ai/overview' ? aiOverview() : tokenSeries())))
    )

    wrapper = mount(Ai)
    await flushPromises()

    expect(wrapper.get('.ai-chart-grid').attributes('stroke')).toBe('var(--bootui-chart-grid)')
    expect(wrapper.get('.ai-chart-grid').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('.ai-chart-axis-label').map((label) => label.attributes('fill'))).toEqual([
      'var(--bootui-chart-axis)',
      'var(--bootui-chart-axis)',
      'var(--bootui-chart-axis)'
    ])
    expect(wrapper.get('.ai-chart-input-area').attributes('fill')).toBe('var(--bootui-chart-input)')
    expect(wrapper.get('.ai-chart-output-area').attributes('fill')).toBe('var(--bootui-chart-output)')
    expect(wrapper.get('.ai-chart-output-line').attributes('stroke')).toBe('var(--bootui-chart-output)')
    expect(wrapper.get('.ai-chart-calls-line').attributes('stroke')).toBe('var(--bootui-chart-calls)')

    const chartContainer = wrapper.get('.ai-chart-container')
    chartContainer.element.getBoundingClientRect = () => ({left: 0, width: 600})
    await chartContainer.trigger('mousemove', {clientX: 600})

    expect(wrapper.get('.ai-chart-selection').attributes('stroke')).toBe('var(--bootui-chart-selection)')
    expect(wrapper.get('.ai-chart-selection').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('.ai-chart-tooltip').classes()).not.toContain('bg-white')
    expect(wrapper.get('.ai-chart-tooltip-input').text()).toBe('In: 80')
    expect(wrapper.get('.ai-chart-tooltip-output').text()).toBe('Out: 20')
    expect(wrapper.get('.ai-chart-tooltip-calls').text()).toBe('Calls: 2')
  })

  it.each([
    ['light', lightTokens],
    ['dark', darkTokens]
  ])('keeps meaningful chart text AA-compliant in the %s theme', (_theme, tokens) => {
    const tooltipBackground = tokens['--bootui-chart-tooltip-bg']
    const textPairs = [
      [tokens['--bootui-chart-axis'], tokens['--bootui-surface-solid']],
      [tokens['--bootui-chart-tooltip-text'], tooltipBackground],
      [tokens['--bootui-chart-input'], tooltipBackground],
      [tokens['--bootui-chart-output'], tooltipBackground],
      [tokens['--bootui-chart-calls'], tooltipBackground]
    ]

    for (const [foreground, background] of textPairs) {
      expect(contrastRatio(foreground, background)).toBeGreaterThanOrEqual(4.5)
    }
    expect(contrastRatio(tokens['--bootui-chart-selection'], tokens['--bootui-surface-solid'])).toBeGreaterThanOrEqual(
      3
    )
  })
})
