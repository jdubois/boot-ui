import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Traces from './Traces.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function emptyEnabledReport() {
  return {enabled: true, retained: 0, capacity: 1000}
}

function mountWithPlatform(platform) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyEnabledReport())))
  return mount(Traces, {
    global: {provide: {panels: ref({platform, panels: []})}}
  })
}

describe('Traces empty state', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('points cooperating services to the embedded OTLP receiver on Spring Boot', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('No traces received yet')
    expect(text).toContain('BootUI starter')
    expect(text).toContain('/bootui/api/otlp/v1/traces')
  })

  it('describes in-process capture with no OTLP endpoint on Quarkus', async () => {
    wrapper = mountWithPlatform('quarkus')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('No traces received yet')
    expect(text).toContain('quarkus-opentelemetry')
    expect(text).toContain('in-process')
    expect(text).not.toContain('/bootui/api/otlp/v1/traces')
    expect(text).not.toContain('BootUI starter')
  })
})

describe('Traces BootUI enrichment', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  function reportWithOneTrace() {
    return {
      enabled: true,
      retained: 1,
      capacity: 1000,
      traces: [
        {
          traceId: 'abc123def456',
          rootSpanName: 'GET /orders',
          httpPath: '/orders',
          services: ['orders-service'],
          spanCount: 2,
          hasError: true,
          hasAi: false,
          startEpochMillis: 1000
        }
      ]
    }
  }

  function detailWithEnrichedSpans() {
    return {
      traceId: 'abc123def456',
      spans: [
        {
          spanId: 's1',
          name: 'GET /orders',
          serviceName: 'orders-service',
          kind: 'SERVER',
          statusCode: 'ERROR',
          startEpochNanos: 1000000,
          endEpochNanos: 5000000,
          durationNanos: 4000000,
          attributes: [
            {key: 'bootui.enriched', type: 'BOOLEAN', value: true},
            {key: 'bootui.service', type: 'STRING', value: 'orders-service'},
            {key: 'bootui.sql.queries', type: 'LONG', value: 12},
            {key: 'bootui.sql.n_plus_one', type: 'BOOLEAN', value: true},
            {key: 'bootui.exceptions', type: 'LONG', value: 1},
            {key: 'bootui.exception.type', type: 'STRING', value: 'java.lang.IllegalStateException'}
          ]
        },
        {
          spanId: 's2',
          name: 'select orders',
          serviceName: 'orders-service',
          kind: 'CLIENT',
          statusCode: 'OK',
          startEpochNanos: 2000000,
          endEpochNanos: 3000000,
          durationNanos: 1000000,
          attributes: []
        }
      ]
    }
  }

  it('surfaces a BootUI-enriched indicator and bootui.* depth attributes in the trace drawer', async () => {
    const fetchMock = vi.fn().mockImplementation((url) => {
      if (url.endsWith('/traces/abc123def456') || url.endsWith('api/traces/abc123def456')) {
        return Promise.resolve(jsonResponse(detailWithEnrichedSpans()))
      }
      return Promise.resolve(jsonResponse(reportWithOneTrace()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Traces, {
      global: {provide: {panels: ref({platform: 'spring-boot', panels: []})}}
    })
    await flushPromises()

    const openButton = wrapper.findAll('button').find((b) => b.text() === 'Open')
    expect(openButton).toBeTruthy()
    await openButton.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('BootUI-enriched')
    expect(text).toContain('12 SQL')
    expect(text).toContain('N+1 suspected')
    expect(text).toContain('1 exception')
    expect(text).toContain('java.lang.IllegalStateException')
  })

  it('shows no enrichment indicator when spans carry no bootui.* attributes', async () => {
    const plainDetail = {
      traceId: 'abc123def456',
      spans: [
        {
          spanId: 's1',
          name: 'GET /orders',
          serviceName: 'orders-service',
          kind: 'SERVER',
          statusCode: 'OK',
          startEpochNanos: 1000000,
          endEpochNanos: 2000000,
          durationNanos: 1000000,
          attributes: []
        }
      ]
    }
    const fetchMock = vi.fn().mockImplementation((url) => {
      if (url.includes('/traces/abc123def456')) {
        return Promise.resolve(jsonResponse(plainDetail))
      }
      return Promise.resolve(jsonResponse(reportWithOneTrace()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Traces, {
      global: {provide: {panels: ref({platform: 'spring-boot', panels: []})}}
    })
    await flushPromises()

    const openButton = wrapper.findAll('button').find((b) => b.text() === 'Open')
    await openButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('BootUI-enriched')
  })
})
