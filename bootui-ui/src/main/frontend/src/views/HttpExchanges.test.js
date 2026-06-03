import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import HttpExchanges from './HttpExchanges.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'

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

    expect(fetch).toHaveBeenCalledWith('api/http-exchanges?offset=0&limit=200', {})
    expect(wrapper.text()).toContain('HTTP Exchanges')
    expect(wrapper.text()).toContain('/api/orders?token=******&page=1')
    expect(wrapper.text()).toContain('201')
    expect(wrapper.text()).toContain('37 ms')
    expect(wrapper.text()).toContain('42 B')
    expect(wrapper.text()).toContain('4bf92f3577b34da6a3ce929d0e0e4736')
    expect(wrapper.text()).toContain('Authorization')
    expect(wrapper.text()).toContain('******')
    expect(wrapper.text()).not.toContain('BootUI self-request')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
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

    expect(fetchMock).toHaveBeenLastCalledWith('api/http-exchanges?method=POST&statusClass=4xx&offset=0&limit=200', {})
  })
})
