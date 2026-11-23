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

function mountWhySlow(route, panels = null, origin = null, overview = null) {
  return mount(RouteWhySlow, {
    props: {route, origin},
    global: {
      provide: {panels: ref(panels), overview: ref(overview)},
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

  it('keeps the link for a persisted request from the current run without dropping its scope', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ok: true, status: 200, json: () => Promise.resolve(report)})
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve({run: {runId: 'current-run'}})
      })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountWhySlow(
      'GET /api/orders/{id}',
      null,
      {namespace: 'team-a', application: 'orders', run: 'current-run'},
      {run: {runId: 'current-run'}}
    )
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/runtime-insights?namespace=team-a&application=orders&run=current-run',
      expect.anything()
    )
    expect(fetchMock).toHaveBeenCalledWith('api/overview', expect.objectContaining({cache: 'no-store'}))
    expect(wrapper.get('a').text()).toBe('Open in Runtime Insights')
  })

  it('does not use a cached overview to link an old recording to the current run after restart', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ok: true, status: 200, json: () => Promise.resolve(report)})
        .mockResolvedValueOnce({ok: true, status: 200, json: () => Promise.resolve({run: {runId: 'new-run'}})})
    )
    wrapper = mountWhySlow(
      'GET /api/orders/{id}',
      null,
      {namespace: 'team-a', application: 'orders', run: 'old-run'},
      {run: {runId: 'old-run'}}
    )
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('warm median 40 ms')
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('keeps the scoped breakdown when current-run verification fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ok: true, status: 200, json: () => Promise.resolve(report)})
        .mockRejectedValueOnce(new Error('Overview unavailable'))
    )
    wrapper = mountWhySlow('GET /api/orders/{id}', null, {namespace: 'team-a', application: 'orders', run: 'old-run'})
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('warm median 40 ms')
    expect(wrapper.text()).toContain('Unable to verify the current run')
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('says so when the route has no breakdown yet', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, status: 200, json: () => Promise.resolve(report)}))
    wrapper = mountWhySlow('GET /api/customers')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('no time breakdown for this route')
  })

  it('shows why a report is unavailable instead of claiming that the route has no breakdown', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            available: false,
            unavailableReason: 'The durable journal is unavailable.',
            observations: []
          })
      })
    )
    wrapper = mountWhySlow('GET /api/orders/{id}')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toBe('The durable journal is unavailable.')
    expect(wrapper.text()).not.toContain('no time breakdown for this route')
    expect(wrapper.get('button').element.disabled).toBe(false)
  })

  it('does not fall back to the current run when the persisted request lacks a complete scope', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountWhySlow('GET /api/orders/{id}', null, {namespace: 'team-a', application: 'orders'})
    await wrapper.get('button').trigger('click')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="status"]').text()).toContain('does not identify a recording')
  })

  it('stays hidden when the Runtime Insights panel is disabled or unavailable', () => {
    wrapper = mountWhySlow('GET /api/orders/{id}', {
      panels: [{id: 'runtime-insights', available: true, enabled: false}]
    })
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
