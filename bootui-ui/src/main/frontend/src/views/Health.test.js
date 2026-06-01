import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Health from './Health.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import PanelHeader from './components/PanelHeader.vue'

function healthRoot(overrides = {}) {
  return {
    status: 'UP',
    details: null,
    components: [],
    ...overrides
  }
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('Health', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the skeleton on the first load only', async () => {
    let resolveFirst
    const fetchMock = vi.fn().mockImplementationOnce(() => new Promise((resolve) => (resolveFirst = resolve)))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Health)

    // Before any data has arrived, the skeleton is visible.
    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(true)

    resolveFirst(jsonResponse(healthRoot()))
    await flushPromises()

    // Once data is present the skeleton is gone and content is rendered.
    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
    expect(wrapper.text()).toContain('Overall status')

    // Simulate an auto-refresh tick: a new in-flight request must not bring the skeleton back.
    let resolveRefresh
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => (resolveRefresh = resolve)))
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
    expect(wrapper.text()).toContain('Overall status')

    resolveRefresh(jsonResponse(healthRoot()))
    await flushPromises()

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
  })

  it('keeps the last good data visible when a refresh fails', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(healthRoot()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Health)
    await flushPromises()
    expect(wrapper.text()).toContain('Overall status')

    fetchMock.mockResolvedValueOnce(jsonResponse({}, false, 503))
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()

    // The skeleton never reappears and the previously rendered content stays visible.
    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
    expect(wrapper.text()).toContain('Overall status')
  })
})
