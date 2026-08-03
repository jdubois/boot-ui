import {flushPromises, mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import StreamStatusIndicator from './StreamStatusIndicator.vue'

function render(connectionState = 'connected', onRetry = null) {
  return mount(StreamStatusIndicator, {props: {connectionState, onRetry}})
}

describe('StreamStatusIndicator', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders nothing visible when connected', () => {
    const wrapper = render('connected')
    expect(wrapper.find('.stream-status-indicator').exists()).toBe(false)
  })

  it('renders nothing visible when connecting', () => {
    const wrapper = render('connecting')
    expect(wrapper.find('.stream-status-indicator').exists()).toBe(false)
  })

  it('renders nothing visible when paused', () => {
    const wrapper = render('paused')
    expect(wrapper.find('.stream-status-indicator').exists()).toBe(false)
  })

  it('renders the reconnecting chip with dot and label', () => {
    const wrapper = render('reconnecting')
    const indicator = wrapper.find('.stream-status-indicator')
    expect(indicator.exists()).toBe(true)
    expect(indicator.text()).toContain('Reconnecting')
    expect(wrapper.find('.stream-status-dot--reconnecting').exists()).toBe(true)
  })

  it('renders the unavailable chip with retry button', () => {
    const wrapper = render('unavailable')
    const indicator = wrapper.find('.stream-status-indicator')
    expect(indicator.exists()).toBe(true)
    expect(indicator.text()).toContain('unavailable')
    expect(wrapper.find('.stream-status-retry').exists()).toBe(true)
    expect(wrapper.find('.bi-wifi-off').exists()).toBe(true)
  })

  it('has a role=status attribute when visible', () => {
    const wrapper = render('unavailable')
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
  })

  it('the aria-live region is always present for screen-reader registration', () => {
    const wrapper = render('connected')
    expect(wrapper.find('[aria-live="polite"]').exists()).toBe(true)
  })

  it('emits retry when the retry button is clicked', async () => {
    const wrapper = render('unavailable')
    await wrapper.find('.stream-status-retry').trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
  })

  it('calls the onRetry prop function when the retry button is clicked', async () => {
    const onRetry = vi.fn()
    const wrapper = render('unavailable', onRetry)
    await wrapper.find('.stream-status-retry').trigger('click')
    expect(onRetry).toHaveBeenCalled()
  })

  it('announces reconnecting transition to screen readers', async () => {
    const wrapper = render('connected')
    expect(wrapper.find('[aria-live]').text()).toBe('')

    await wrapper.setProps({connectionState: 'reconnecting'})
    await nextTick()

    expect(wrapper.find('[aria-live]').text()).toContain('reconnecting')
  })

  it('announces unavailable transition to screen readers', async () => {
    const wrapper = render('connected')
    await wrapper.setProps({connectionState: 'unavailable'})
    await nextTick()
    expect(wrapper.find('[aria-live]').text()).toContain('unavailable')
  })

  it('announces recovery to screen readers', async () => {
    const wrapper = render('reconnecting')
    await wrapper.setProps({connectionState: 'connected'})
    await nextTick()
    expect(wrapper.find('[aria-live]').text()).toContain('connected')
  })

  it('does not announce connecting → connected as a recovery', async () => {
    const wrapper = render('connecting')
    await wrapper.setProps({connectionState: 'connected'})
    await nextTick()
    // connecting → connected is not a degraded-to-healthy transition; stay silent
    expect(wrapper.find('[aria-live]').text()).toBe('')
  })

  it('does not re-announce the same state on repeated prop updates', async () => {
    const wrapper = render('reconnecting')
    await flushPromises()

    const firstText = wrapper.find('[aria-live]').text()

    await wrapper.setProps({connectionState: 'reconnecting'})
    await nextTick()

    expect(wrapper.find('[aria-live]').text()).toBe(firstText)
  })
})
