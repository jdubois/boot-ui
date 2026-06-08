import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import FlashBanner from './FlashBanner.vue'

describe('FlashBanner', () => {
  it('renders nothing when there is no message', () => {
    const wrapper = mount(FlashBanner, {props: {message: null}})
    expect(wrapper.find('.alert').exists()).toBe(false)
  })

  it('renders the message text with the type-specific alert class', () => {
    const wrapper = mount(FlashBanner, {props: {message: {text: 'Saved', type: 'success'}}})
    const alert = wrapper.get('.alert')
    expect(alert.classes()).toContain('alert-success')
    expect(alert.text()).toContain('Saved')
  })

  it('omits the icon by default', () => {
    const wrapper = mount(FlashBanner, {props: {message: {text: 'Hi', type: 'info'}}})
    expect(wrapper.find('i.bi').exists()).toBe(false)
  })

  it('shows a type-specific icon when withIcon is set', () => {
    const wrapper = mount(FlashBanner, {props: {message: {text: 'Oops', type: 'danger'}, withIcon: true}})
    expect(wrapper.find('i.bi.bi-exclamation-triangle-fill').exists()).toBe(true)
  })

  it('emits dismiss when the close button is clicked', async () => {
    const wrapper = mount(FlashBanner, {props: {message: {text: 'Saved', type: 'success'}}})
    await wrapper.get('button.btn-close').trigger('click')
    expect(wrapper.emitted('dismiss')).toHaveLength(1)
  })
})
