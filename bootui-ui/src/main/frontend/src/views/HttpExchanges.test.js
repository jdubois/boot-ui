import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import HttpExchanges from './HttpExchanges.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    total: 1,
    recorded: 2,
    hiddenSelf: 1,
    unavailableReason: null,
    page: {total: 1, matched: 1, offset: 0, limit: 200, returned: 1, hasMore: false},
    exchanges: [
      {
        id: 'exchange-1',
        timestamp: '2026-06-03T09:15:00Z',
        method: 'POST',
        path: '/api/orders',
        query: 'token=******&page=1',
        uri: 'http://localhost/api/orders?token=******&page=1',
        status: 201,
        statusFamily: '2xx',
        durationMs: 37,
        responseSizeBytes: 42,
        remoteAddress: '127.0.0.1',
        principal: null,
        sessionId: null,
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
        requestHeaders: [
          {name: 'Accept', values: ['application/json'], masked: false},
          {name: 'Authorization', values: ['******'], masked: true}
        ],
        responseHeaders: [{name: 'Content-Length', values: ['42'], masked: false}]
      }
    ],
    ...overrides
  }
}

describe('HTTP Exchanges', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('renders recorded exchanges with masked details and auto-refresh controls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(HttpExchanges)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith(
      'api/http-exchanges?offset=0&limit=200',
      expect.objectContaining({signal: expect.any(AbortSignal)})
    )
    expect(wrapper.text()).toContain('HTTP Exchanges')
    expect(wrapper.text()).toContain('/api/orders?token=******&page=1')
    expect(wrapper.text()).toContain('201')
    expect(wrapper.text()).toContain('37 ms')
    expect(wrapper.text()).toContain('42 B')
    expect(wrapper.text()).toContain('4bf92f3577b34da6a3ce929d0e0e4736')
    expect(wrapper.text()).not.toContain('Authorization')
    expect(wrapper.text()).not.toContain('BootUI self-request')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(true)

    const detailsButton = wrapper.find('.http-exchanges-detail-toggle')
    expect(detailsButton.text()).toContain('View details')
    expect(detailsButton.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.http-exchanges-detail').exists()).toBe(false)

    await detailsButton.trigger('click')

    expect(wrapper.find('.http-exchanges-detail-toggle').text()).toContain('Hide details')
    expect(wrapper.find('.http-exchanges-detail-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.http-exchanges-detail').exists()).toBe(true)
    expect(wrapper.text()).toContain('Authorization')
    expect(wrapper.text()).toContain('******')
  })

  it('sends method and status filters to the server', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(report({exchanges: [], total: 0, recorded: 0, hiddenSelf: 0})))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(HttpExchanges)
    await flushPromises()

    await wrapper.find('select').setValue('POST')
    await wrapper.findAll('select')[1].setValue('4xx')
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/http-exchanges?method=POST&statusClass=4xx&offset=0&limit=200',
      expect.objectContaining({signal: expect.any(AbortSignal)})
    )
  })

  it('shows captured correlation identifiers masked, and cross-links by identity only', async () => {
    const correlated = report()
    correlated.exchanges[0].correlationIds = [
      {name: 'x-correlation-id', value: '******', masked: true, truncated: false, lookupId: '88b87faa5f574f9b'}
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(correlated)))

    const wrapper = mount(HttpExchanges, {
      global: {stubs: {RouterLink: {props: ['to'], template: '<a :href="JSON.stringify(to)"><slot /></a>'}}}
    })
    await flushPromises()
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')

    const detail = wrapper.find('.http-exchanges-detail')
    expect(detail.text()).toContain('Correlation identifiers')
    expect(detail.text()).toContain('x-correlation-id')
    const link = detail.findAll('a').at(-1)
    expect(link.attributes('href')).toContain('/activity')
    expect(link.attributes('href')).toContain('88b87faa5f574f9b')
  })

  it('withholds the identifier value entirely under a metadata-only exposure policy', async () => {
    const correlated = report()
    correlated.exchanges[0].correlationIds = [
      {name: 'x-request-id', value: null, masked: true, truncated: true, lookupId: '74a2f8fde4aec9c7'}
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(correlated)))

    const wrapper = mount(HttpExchanges, {
      global: {stubs: {RouterLink: {props: ['to'], template: '<a><slot /></a>'}}}
    })
    await flushPromises()
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')

    const detail = wrapper.find('.http-exchanges-detail')
    expect(detail.text()).toContain('Withheld')
    expect(detail.text()).toContain('truncated')
  })

  it('shows no correlation section when nothing was captured', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(HttpExchanges, {
      global: {stubs: {RouterLink: {props: ['to'], template: '<a><slot /></a>'}}}
    })
    await flushPromises()
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')

    expect(wrapper.find('.http-exchanges-detail').text()).not.toContain('Correlation identifiers')
  })
  it('offers copy only for a value the exposure policy already revealed', async () => {
    const revealed = report()
    revealed.exchanges[0].correlationIds = [
      {name: 'x-correlation-id', value: 'corr-1', masked: false, truncated: false, lookupId: '88b87faa5f574f9b'},
      {name: 'x-request-id', value: '******', masked: true, truncated: false, lookupId: '74a2f8fde4aec9c7'}
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(revealed)))
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', {clipboard: {writeText}})

    const wrapper = mount(HttpExchanges, {
      global: {stubs: {RouterLink: {props: ['to'], template: '<a><slot /></a>'}}}
    })
    await flushPromises()
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')

    const copyButtons = wrapper
      .find('.http-exchanges-detail')
      .findAll('button')
      .filter((button) => button.text().includes('Copy'))
    expect(copyButtons).toHaveLength(1)

    await copyButtons[0].trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('corr-1')
  })
})
