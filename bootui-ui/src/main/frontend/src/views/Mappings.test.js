import {flushPromises, mount} from '@vue/test-utils'
import {nextTick, ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import Mappings from './Mappings.vue'
import FlashBanner from './components/FlashBanner.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

function mountMappings(options = {}) {
  return mount(Mappings, {
    ...options,
    global: {
      ...options.global,
      stubs: {
        RouterLink: RouterLinkStub,
        ...(options.global?.stubs || {})
      }
    }
  })
}

function response(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(mappings, matched = mappings.length, total = matched, basePath = '') {
  return {basePath, mappings, page: {matched, total}}
}

describe('Mappings', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows only an accessible skeleton during the initial load', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {}))
    )

    wrapper = mountMappings()
    await nextTick()

    expect(wrapper.getComponent(PanelSkeleton).attributes('aria-label')).toBe('Loading HTTP mappings…')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('0 of 0')
    expect(wrapper.text()).not.toContain('No HTTP mappings')
    expect(wrapper.text()).not.toContain('No mappings match')
  })

  it('distinguishes true empty results from filtered empty results', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(response(report([], 0, 0)))
      .mockResolvedValueOnce(response(report([], 0, 3)))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountMappings()
    await flushPromises()

    expect(wrapper.getComponent(UnavailableState).text()).toContain('No HTTP mappings were reported')
    expect(wrapper.find('table').exists()).toBe(false)

    await wrapper.get('input').setValue('admin')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.getComponent(UnavailableState).text()).toContain('No mappings match admin')
    expect(wrapper.getComponent(UnavailableState).text()).toContain('Clear filter')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('uses the panel manifest to distinguish unavailable from true empty', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountMappings({
      props: {
        panel: {
          id: 'mappings',
          available: false,
          unavailableReason: 'HTTP mappings are not available for this application.'
        }
      }
    })
    await flushPromises()

    expect(wrapper.getComponent(UnavailableState).text()).toContain(
      'HTTP mappings are not available for this application.'
    )
    expect(wrapper.text()).not.toContain('No HTTP mappings were reported')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('preserves successful results and marks them stale when refresh fails', async () => {
    const mapping = {method: 'GET', pattern: '/api/hello', handler: 'SampleController#hello'}
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(response(report([mapping])))
      .mockResolvedValueOnce(response(null, false, 503))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountMappings()
    await flushPromises()

    expect(wrapper.text()).toContain('/api/hello')
    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('/api/hello')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Showing the last successful results')
    expect(wrapper.getComponent(FlashBanner).find('button.btn-close').exists()).toBe(false)
  })

  it('offers retry after an initial error and renders data after recovery', async () => {
    const mapping = {method: 'GET', pattern: '/api/hello', handler: 'SampleController#hello'}
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(response(null, false, 503))
      .mockResolvedValueOnce(response(report([mapping])))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountMappings()
    await flushPromises()

    const retry = wrapper.get('button.btn-outline-danger')
    expect(retry.text()).toContain('Retry')
    expect(wrapper.find('table').exists()).toBe(false)

    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('/api/hello')
    expect(wrapper.find('button.btn-outline-danger').exists()).toBe(false)
  })

  it('links each concrete mapping to HTTP Probe with composed basePath and hides the link when http-probe is read-only', async () => {
    const mapping = {
      method: 'POST',
      pattern: '/api/orders/{id}',
      handler: 'OrderController#create',
      produces: 'application/json',
      consumes: 'application/json'
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(report([mapping], 1, 1, '/host'))))

    wrapper = mountMappings({
      global: {
        provide: {
          panels: ref({
            panels: [{id: 'http-probe', enabled: true, available: true, readOnly: false}]
          })
        }
      }
    })
    await flushPromises()

    const link = wrapper.get('.mappings-probe-link')
    expect(link.text()).toContain('Open in HTTP Probe')
    expect(JSON.parse(link.attributes('data-to'))).toEqual({
      path: '/http-probe',
      query: {
        method: 'POST',
        path: '/host/api/orders/{id}',
        accept: 'application/json',
        contentType: 'application/json'
      }
    })

    wrapper.unmount()
    wrapper = mountMappings({
      global: {
        provide: {
          panels: ref({
            panels: [{id: 'http-probe', enabled: true, available: true, readOnly: true}]
          })
        }
      }
    })
    await flushPromises()
    expect(wrapper.find('.mappings-probe-link').exists()).toBe(false)
  })
})
