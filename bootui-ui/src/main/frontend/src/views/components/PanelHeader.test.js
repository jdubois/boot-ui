import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import PanelHeader from './PanelHeader.vue'

describe('PanelHeader', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-06-01T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('emits refresh from the header refresh button', async () => {
    const onRefresh = vi.fn()
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Health',
        lastFetched: Date.now(),
        onRefresh
      }
    })

    await wrapper.get('button[title="Refresh"]').trigger('click')

    expect(onRefresh).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('renders shared auto-refresh controls and emits auto-refresh updates', async () => {
    const onUpdateAutoRefresh = vi.fn()
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Health',
        autoRefresh: true,
        'onUpdate:autoRefresh': onUpdateAutoRefresh
      }
    })

    expect(wrapper.text()).toContain('Auto-refresh')

    await wrapper.get('input[type="checkbox"]').setValue(false)

    expect(onUpdateAutoRefresh).toHaveBeenCalledWith(false)

    wrapper.unmount()
  })

  it('hides refresh controls when refresh is not available', () => {
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Database Connection Pools',
        refreshable: false,
        onRefresh: vi.fn()
      }
    })

    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps the relative last-fetched text current', async () => {
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Health',
        lastFetched: Date.now()
      }
    })

    expect(wrapper.text()).toContain('just now')

    await vi.advanceTimersByTimeAsync(6000)
    await flushPromises()

    expect(wrapper.text()).toContain('6s ago')

    wrapper.unmount()
  })

  it('shows a server-not-running tip for browser network failures', () => {
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Health',
        error: 'Load failed'
      }
    })

    expect(wrapper.text()).toContain('Server unreachable')
    expect(wrapper.text()).toContain('The server may have been stopped')
    expect(wrapper.text()).not.toContain('Load failed')

    wrapper.unmount()
  })
})
