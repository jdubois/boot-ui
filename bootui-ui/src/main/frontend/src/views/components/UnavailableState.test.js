import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import UnavailableState from './UnavailableState.vue'

describe('UnavailableState', () => {
  it('renders the message with the default secondary variant and info icon', () => {
    const wrapper = mount(UnavailableState, {props: {message: 'Nothing here yet.'}})

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toBe('Nothing here yet.')
    expect(wrapper.find('i.bi.bi-info-circle').exists()).toBe(true)
  })

  it('applies the requested variant and icon', () => {
    const wrapper = mount(UnavailableState, {
      props: {message: 'Add the dependency.', variant: 'info', icon: 'bi-plug'}
    })

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.classes()).not.toContain('alert-secondary')
    expect(wrapper.find('i.bi.bi-plug').exists()).toBe(true)
  })

  it('prefers default slot content over the message prop', () => {
    const wrapper = mount(UnavailableState, {
      props: {message: 'fallback message'},
      slots: {default: '<code>flyway-core</code> required'}
    })

    expect(wrapper.find('code').exists()).toBe(true)
    expect(wrapper.text()).toContain('flyway-core required')
    expect(wrapper.text()).not.toContain('fallback message')
  })
})
