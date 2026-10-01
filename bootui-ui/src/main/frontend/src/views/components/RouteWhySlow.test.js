import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import RouteWhySlow from './RouteWhySlow.vue'

const report = {
  observations: [
    {
      id: 'route-time-breakdown:abcdef0123',
      kind: 'route-time-breakdown',
      subject: 'GET /api/orders/{id}',
      sentence: '`GET /api/orders/{id}`: warm median 40 ms over 5 requests; SQL 38 %.',
      whatToCheck: ['Most of the time is SQL: compare its statements in SQL Trace.']
    }
  ]
}

function mountWhySlow(route, panels = null) {
  return mount(RouteWhySlow, {
    props: {route},
    global: {
      provide: {panels: ref(panels)},
      stubs: {'router-link': {props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>'}}
    }
  })
}

describe('RouteWhySlow', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  it('loads the route time breakdown only when asked and links to it', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ok: true, status: 200, json: () => Promise.resolve(report)})
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountWhySlow('GET /api/orders/{id}')
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('warm median 40 ms')
    expect(wrapper.text()).not.toContain('`')
    expect(wrapper.text()).toContain('compare its statements in SQL Trace')
    expect(JSON.parse(wrapper.get('a').attributes('data-to'))).toEqual({
      path: '/runtime-insights',
      query: {q: 'GET /api/orders/{id}', insight: 'route-time-breakdown:abcdef0123'}
    })
  })

  it('says so when the route has no breakdown yet', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, status: 200, json: () => Promise.resolve(report)}))
    wrapper = mountWhySlow('GET /api/customers')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('no time breakdown for this route')
  })

  it('stays hidden when the Runtime Insights panel is disabled or unavailable', () => {
    wrapper = mountWhySlow('GET /api/orders/{id}', {
      panels: [{id: 'runtime-insights', available: true, enabled: false}]
    })
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
