import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Traces from './Traces.vue'

const route = vi.hoisted(() => ({query: {}}))
const push = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({push})
}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    enabled: true,
    retained: 1,
    capacity: 100,
    traces: [
      {
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
        rootSpanName: 'GET /api/orders',
        services: ['bootui-sample-app'],
        spanCount: 2,
        durationNanos: 37_000_000,
        hasError: false,
        hasAi: false,
        startEpochNanos: 1_779_900_000_000_000_000
      }
    ],
    ...overrides
  }
}

describe('Traces', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    route.query = {}
    push.mockReset()
  })

  it('renders the root span separately from the trace id tag and focuses the trace on click', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(Traces)
    await flushPromises()

    expect(wrapper.text()).toContain('GET /api/orders')
    const tag = wrapper.find('.correlation-id-tag')
    expect(tag.exists()).toBe(true)
    expect(tag.text()).toContain('id: 4bf92f3577b3…')

    await tag.trigger('click')

    expect(push).toHaveBeenCalledWith({
      name: 'traces',
      query: {trace: '4bf92f3577b34da6a3ce929d0e0e4736'}
    })
  })
})
