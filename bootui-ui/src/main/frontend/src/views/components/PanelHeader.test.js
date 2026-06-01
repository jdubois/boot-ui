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
})
