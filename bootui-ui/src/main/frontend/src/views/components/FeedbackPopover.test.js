import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import FeedbackPopover from './FeedbackPopover.vue'

let wrapper

function mountFeedback() {
  wrapper = mount(FeedbackPopover, {attachTo: document.body})
  const popover = wrapper.get('.feedback-popover')
  const trigger = wrapper.get('.feedback-trigger')
  // jsdom does not implement the Popover API; dispatch the browser's lifecycle events.
  function nativeToggle(newState) {
    const event = new Event('beforetoggle', {cancelable: true})
    Object.defineProperty(event, 'newState', {value: newState})
    popover.element.dispatchEvent(event)
  }
  const hide = vi.fn(() => nativeToggle('closed'))
  popover.element.hidePopover = hide
  return {popover, trigger, hide, open: () => nativeToggle('open')}
}

afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})

describe('FeedbackPopover', () => {
  it('handles Escape only while open, restoring focus without dismissing the containing drawer', async () => {
    const {popover, trigger, hide, open} = mountFeedback()
    const parentKeydown = vi.fn()
    document.body.addEventListener('keydown', parentKeydown)
    try {
      expect(popover.attributes()).toHaveProperty('inert')
      await trigger.trigger('click')
      expect(document.activeElement).toBe(trigger.element)
      open()
      await flushPromises()
      expect(trigger.attributes('aria-expanded')).toBe('true')

      const link = wrapper.find('a')
      link.element.focus()
      await link.trigger('keydown', {key: 'Escape'})

      expect(hide).toHaveBeenCalledOnce()
      expect(trigger.attributes('aria-expanded')).toBe('false')
      expect(popover.element.inert).toBe(true)
      expect(document.activeElement).toBe(trigger.element)
      expect(parentKeydown).not.toHaveBeenCalled()

      await trigger.trigger('keydown', {key: 'Escape'})
      expect(parentKeydown).toHaveBeenCalledOnce()
    } finally {
      document.body.removeEventListener('keydown', parentKeydown)
    }
  })

  it('does not dismiss on Safari-style blur, but closes without stealing focus when focus moves outside', async () => {
    const {hide, open} = mountFeedback()
    open()
    await flushPromises()
    const link = wrapper.find('a')
    link.element.focus()
    await link.trigger('focusout', {relatedTarget: null})
    expect(hide).not.toHaveBeenCalled()

    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    await flushPromises()
    expect(hide).toHaveBeenCalledOnce()
    expect(document.activeElement).toBe(outside)
  })

  it('closes and makes links inert when the shell disables its sidebar', async () => {
    const {popover, trigger, hide, open} = mountFeedback()
    open()
    await flushPromises()

    await wrapper.setProps({disabled: true})
    expect(hide).toHaveBeenCalledOnce()
    expect(trigger.attributes()).toHaveProperty('disabled')
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(popover.element.inert).toBe(true)

    open()
    await flushPromises()
    expect(trigger.attributes('aria-expanded')).toBe('false')
  })
})
