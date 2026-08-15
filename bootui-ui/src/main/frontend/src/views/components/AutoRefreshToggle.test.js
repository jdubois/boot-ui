import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import {describe, expect, it, vi} from 'vitest'

import AutoRefreshToggle from './AutoRefreshToggle.vue'

function render(connectionState = null) {
  return mount(AutoRefreshToggle, {props: {modelValue: true, connectionState}})
}

describe('AutoRefreshToggle', () => {
  it('keeps healthy auto-refresh quiet', () => {
    const wrapper = render('connected')

    expect(wrapper.text()).toBe('Auto-refresh')
    expect(wrapper.find('.auto-refresh-dot--live').exists()).toBe(true)
  })

  it('shows stream reconnection as part of auto-refresh', () => {
    const wrapper = render('reconnecting')

    expect(wrapper.text()).toContain('Auto-refresh')
    expect(wrapper.text()).toContain('Reconnecting')
    expect(wrapper.find('.auto-refresh-dot--reconnecting').exists()).toBe(true)
  })

  it('shows an unavailable stream and emits retry from the same control', async () => {
    const wrapper = render('unavailable')

    expect(wrapper.text()).toContain('Auto-refresh')
    expect(wrapper.text()).toContain('Stream unavailable')

    await wrapper.get('button[aria-label="Retry auto-refresh stream connection now"]').trigger('click')

    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('announces degradation and recovery through one live region', async () => {
    const wrapper = render('unavailable')
    const liveRegion = wrapper.get('[role="status"][aria-live="polite"]')

    expect(wrapper.findAll('[role="status"][aria-live="polite"]')).toHaveLength(1)
    expect(liveRegion.text()).toContain('unavailable')

    await wrapper.setProps({connectionState: 'connected'})
    await nextTick()

    expect(liveRegion.text()).toContain('connected')
  })

  it('still emits switch changes independently of stream status', async () => {
    const onUpdateModelValue = vi.fn()
    const wrapper = mount(AutoRefreshToggle, {
      props: {
        modelValue: true,
        connectionState: 'unavailable',
        'onUpdate:modelValue': onUpdateModelValue
      }
    })

    await wrapper.get('input[type="checkbox"]').setValue(false)

    expect(onUpdateModelValue).toHaveBeenCalledWith(false)
  })
})
