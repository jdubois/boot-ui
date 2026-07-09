import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RestClientTrace from './RestClientTrace.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))
vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function traceReport(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    captureHeaders: true,
    bufferSize: 200,
    totalCaptured: 8,
    slowCallThresholdMillis: 100,
    clientTypes: ['RestClient'],
    stats: {
      totalCalls: 2,
      totalDurationMillis: 160,
      maxDurationMillis: 120,
      avgDurationMillis: 80,
      slowCalls: 1,
      failedCalls: 0,
      errorStatusCalls: 0,
      getCount: 1,
      postCount: 1,
      putCount: 0,
      deleteCount: 0,
      otherCount: 0,
      evicted: 0
    },
    entries: [
      {
        id: 2,
        timestamp: 1700000000000,
        method: 'GET',
        uri: 'https://api.example.com/orders/42',
        host: 'https://api.example.com',
        path: '/orders/42',
        status: 200,
        durationMillis: 120,
        success: true,
        errorMessage: null,
        slow: true,
        clientType: 'RestClient',
        requestHeaders: {Accept: 'application/json'},
        traceId: 'trace-42',
        thread: 'http-nio-1',
        callSite: 'com.example.OrderClient.getOrder(OrderClient.java:10)'
      },
      {
        id: 1,
        timestamp: 1700000000000,
        method: 'POST',
        uri: 'https://api.example.com/customers',
        host: 'https://api.example.com',
        path: '/customers',
        status: 201,
        durationMillis: 40,
        success: true,
        errorMessage: null,
        slow: false,
        clientType: 'RestClient',
        requestHeaders: {},
        traceId: null,
        thread: 'http-nio-2',
        callSite: null
      }
    ],
    topCalls: [
      {
        method: 'GET',
        host: 'https://api.example.com',
        path: '/orders/42',
        executions: 6,
        totalDurationMillis: 600,
        maxDurationMillis: 120,
        chatty: true,
        callSites: ['com.example.OrderClient.getOrder(OrderClient.java:10)']
      }
    ],
    warnings: ['Captured header values are shown in clear text.'],
    ...overrides
  }
}

describe('RestClientTrace', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the unavailable reason when no REST client is instrumented', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          available: false,
          unavailableReason: 'No instrumented RestClient, RestTemplate, or WebClient bean is available',
          capturing: false,
          captureHeaders: false,
          bufferSize: 0,
          totalCaptured: 0,
          slowCallThresholdMillis: 0,
          clientTypes: [],
          stats: {totalCalls: 0},
          entries: [],
          topCalls: [],
          warnings: []
        })
      )
    )

    wrapper = mount(RestClientTrace, {props: {panel: {id: 'rest-client-trace'}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/rest-client-trace', expect.anything())
    expect(wrapper.text()).toContain('No instrumented RestClient, RestTemplate, or WebClient bean is available')
  })

  it('renders captured calls, stats, warnings, and the chatty hint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(RestClientTrace, {props: {panel: {id: 'rest-client-trace'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('/orders/42')
    expect(text).toContain('/customers')
    expect(text).toContain('GET')
    expect(text).toContain('POST')
    expect(text).toContain('Most frequent calls')
    expect(text).toContain('chatty')
    expect(text).toContain('captured since startup')
    expect(text).toContain('clear text')
    expect(text).toContain('com.example.OrderClient.getOrder(OrderClient.java:10)')
    expect(text).toContain('RestClient')
  })

  it('reveals headers, client type, thread, and call site when a row is expanded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(RestClientTrace, {props: {panel: {id: 'rest-client-trace'}}})
    await flushPromises()

    expect(wrapper.text()).not.toContain('http-nio-1')
    await wrapper.get('tr.rest-row').trigger('click')
    const text = wrapper.text()
    expect(text).toContain('Accept: application/json')
    expect(text).toContain('http-nio-1')
    expect(text).toContain('com.example.OrderClient.getOrder(OrderClient.java:10)')
    expect(text).toContain('trace-42')
  })

  it('filters calls by URI text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(RestClientTrace, {props: {panel: {id: 'rest-client-trace'}}})
    await flushPromises()

    await wrapper.get('input.trace-filter').setValue('customers')
    const calls = wrapper.get('table.rest-table').text()
    expect(calls).toContain('/customers')
    expect(calls).not.toContain('/orders/42')
  })

  it('toggles recording when the pause action is clicked', async () => {
    const paused = traceReport({capturing: false})
    const fetchMock = vi.fn((url) => {
      if (url === 'api/rest-client-trace/recording') return Promise.resolve(jsonResponse(paused))
      return Promise.resolve(jsonResponse(traceReport()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(RestClientTrace, {props: {panel: {id: 'rest-client-trace'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-warning').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/rest-client-trace/recording',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: false})})
    )
  })

  it('clears the trace when the clear action is confirmed', async () => {
    const cleared = traceReport({stats: {...traceReport().stats, totalCalls: 0}, entries: [], topCalls: []})
    const fetchMock = vi.fn((url) => {
      if (url === 'api/rest-client-trace/clear') return Promise.resolve(jsonResponse(cleared))
      return Promise.resolve(jsonResponse(traceReport()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(RestClientTrace, {props: {panel: {id: 'rest-client-trace'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/rest-client-trace/clear', {method: 'POST'})
    expect(wrapper.text()).toContain('No REST client calls have been captured yet')
  })
})
