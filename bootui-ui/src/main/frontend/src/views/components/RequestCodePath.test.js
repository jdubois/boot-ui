import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import RequestCodePath from './RequestCodePath.vue'

const panels = {
  panels: [
    {id: 'runtime-insights', enabled: true, available: true},
    {id: 'code-paths', enabled: true, available: true}
  ]
}

const tree = {
  available: true,
  unavailableReason: null,
  requestId: 'request-1',
  found: true,
  route: 'GET /api/orders/{id}',
  assemblyOnly: false,
  topMethods: [{method: 'com.example.OrderService#load()', selfMillis: 12, share: 60}],
  limitations: []
}

function mountSection(panelManifest = panels, requestId = 'request-1', route = null) {
  return mount(RequestCodePath, {
    props: {requestId, route},
    global: {
      provide: {panels: ref(panelManifest)},
      stubs: {
        'router-link': {
          props: ['to'],
          template: '<a :data-to="JSON.stringify(to)"><slot /></a>'
        }
      }
    }
  })
}

function response(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('RequestCodePath', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('offers the JFR tab and the route-level Code Paths tree without starting JFR', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(tree))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountSection()
    await flushPromises()

    const section = wrapper.get('.request-code-path')
    expect(section.get('h3').text()).toBe('Performance deep dives')
    expect(section.text()).toContain('OrderService.load')
    expect(section.text()).toContain('not an exact replay of this request')
    expect(JSON.parse(section.findAll('a')[0].attributes('data-to'))).toEqual({
      path: '/runtime-insights',
      query: {tab: 'profile'}
    })
    expect(JSON.parse(section.findAll('a')[1].attributes('data-to'))).toEqual({
      path: '/code-paths',
      query: {route: 'GET /api/orders/{id}'}
    })
    for (const link of section.findAll('a')) {
      expect(link.classes()).toEqual(expect.arrayContaining(['btn', 'btn-outline-secondary', 'btn-sm']))
      expect(link.attributes('role')).toBeUndefined()
    }
    expect(section.text()).toContain('recording starts only if you choose Profile resources')
    expect(fetchMock.mock.calls[0][1]?.method ?? 'GET').toBe('GET')
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  })

  it('explains a disabled Code Paths panel and does not read it', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountSection({
      panels: [
        {id: 'runtime-insights', enabled: true, available: true},
        {id: 'code-paths', enabled: false, available: true}
      ]
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Code Paths unavailable')
    expect(wrapper.text()).toContain('bootui.panels.code-paths.enabled=false')
    expect(wrapper.get('a').text()).toBe('Open the JFR profile in Runtime Insights')
  })

  it('distinguishes a missing retained request tree from unavailable capture', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({...tree, found: false, route: null})))
    wrapper = mountSection(panels, 'request-1', 'GET /api/orders/{id}')
    await flushPromises()

    expect(wrapper.text()).toContain('No retained code-path tree was found for this request.')
    expect(wrapper.text()).toContain('may not have been captured or may no longer be retained')
    expect(wrapper.findAll('a')).toHaveLength(2)
    expect(JSON.parse(wrapper.findAll('a')[1].attributes('data-to'))).toEqual({
      path: '/code-paths',
      query: {route: 'GET /api/orders/{id}'}
    })
  })

  it('shows the reason when the request-tree endpoint reports unavailable', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          response({...tree, available: false, unavailableReason: "Requires the BootUI agent's code-paths sensor."})
        )
    )
    wrapper = mountSection()
    await flushPromises()

    expect(wrapper.text()).toContain("Code Paths unavailable: Requires the BootUI agent's code-paths sensor.")
    expect(wrapper.text()).not.toContain('No retained code-path tree was found')
  })

  it('surfaces a failed tree read instead of silently omitting the section', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Request failed with status 500')))
    wrapper = mountSection()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Unable to load this request’s Code Paths tree: Request failed with status 500'
    )
  })

  it('explains when the request id or Runtime Insights destination is unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountSection(
      {
        panels: [
          {id: 'runtime-insights', enabled: false, available: true},
          {id: 'code-paths', enabled: true, available: true}
        ]
      },
      null
    )
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('JFR profile unavailable')
    expect(wrapper.text()).toContain('bootui.panels.runtime-insights.enabled=false')
    expect(wrapper.text()).toContain('No request ID is available for this activity entry.')
    expect(wrapper.findAll('a')).toHaveLength(0)
  })
})
