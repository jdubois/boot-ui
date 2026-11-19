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

  it('makes the icon and title one native action only when requested', async () => {
    const onTitleAction = vi.fn()
    const wrapper = mount(PanelHeader, {
      props: {title: 'Home', icon: 'bi-house', onTitleAction}
    })

    const action = wrapper.get('h2 button')
    expect(action.attributes('type')).toBe('button')
    expect(action.text()).toBe('Home')
    await action.get('.bi-house').trigger('click')
    expect(onTitleAction).toHaveBeenCalledTimes(1)

    await wrapper.setProps({loading: true})
    expect(action.attributes('disabled')).toBeDefined()
    wrapper.unmount()
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

  it('communicates refresh activity when reduced motion leaves the icon static', () => {
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Health',
        loading: true,
        onRefresh: vi.fn()
      }
    })

    const refresh = wrapper.get('button[title="Refresh"]')
    expect(refresh.attributes('aria-busy')).toBe('true')
    expect(refresh.attributes('aria-label')).toBe('Refreshing panel')

    wrapper.unmount()
  })

  it('disables the error retry action while a refresh is pending', async () => {
    const onRefresh = vi.fn()
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'GitHub',
        error: {title: 'Load failed', message: 'Refresh failed', serverUnreachable: false},
        loading: true,
        onRefresh
      }
    })

    const retry = wrapper.get('[role="alert"] button')
    expect(retry.attributes('disabled')).toBeDefined()
    expect(retry.attributes('aria-busy')).toBe('true')
    await retry.trigger('click')
    expect(onRefresh).not.toHaveBeenCalled()

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

  it('keeps stream errors and retry inside the auto-refresh control', async () => {
    const onRetryAutoRefresh = vi.fn()
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Exceptions',
        autoRefresh: true,
        autoRefreshState: 'unavailable',
        onRetryAutoRefresh
      }
    })

    expect(wrapper.find('.auto-refresh-control').text()).toContain('Updates paused')
    expect(wrapper.find('.auto-refresh-paused').exists()).toBe(true)
    expect(wrapper.find('.stream-status-indicator').exists()).toBe(false)

    await wrapper.get('button[aria-label="Retry auto-refresh stream connection now"]').trigger('click')

    expect(onRetryAutoRefresh).toHaveBeenCalledTimes(1)

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
    expect(wrapper.find('.panel-header__title-action').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps subtitle actions outside the descriptive paragraph', () => {
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'Live Activity',
        subtitle: 'Recent application signals.'
      },
      slots: {
        'subtitle-actions': '<button type="button">Open stream settings</button>'
      }
    })

    expect(wrapper.get('.panel-header__subtitle').text()).toBe('Recent application signals.')
    expect(wrapper.get('.panel-header__subtitle').find('button').exists()).toBe(false)
    expect(wrapper.get('.panel-header__subtitle-row').find('button').exists()).toBe(true)

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

  it('can identify what the relative timestamp describes', () => {
    const wrapper = mount(PanelHeader, {
      props: {
        title: 'WebSockets',
        lastFetched: Date.now(),
        lastFetchedLabel: 'Snapshot'
      }
    })

    expect(wrapper.get('.last-fetched-text').text()).toBe('Snapshot just now')

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
