import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import ReadOnlyNotice from './ReadOnlyNotice.vue'

describe('ReadOnlyNotice', () => {
  it('renders the lock icon, slot text and reason', () => {
    const wrapper = mount(ReadOnlyNotice, {
      props: {reason: 'BootUI is read-only'},
      slots: {default: 'Flyway actions are read-only.'}
    })
    const alert = wrapper.get('div')
    expect(alert.classes()).toContain('alert')
    expect(alert.classes()).toContain('alert-warning')
    expect(alert.classes()).toContain('small')
    expect(wrapper.find('i.bi.bi-lock').exists()).toBe(true)
    expect(wrapper.text()).toContain('Flyway actions are read-only.')
    expect(wrapper.text()).toContain('BootUI is read-only')
  })

  it('renders without a reason', () => {
    const wrapper = mount(ReadOnlyNotice, {slots: {default: 'Actions are read-only.'}})
    expect(wrapper.text()).toContain('Actions are read-only.')
  })
})
