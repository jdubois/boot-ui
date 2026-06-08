import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {mount} from '@vue/test-utils'

import {useFlashMessage} from './useFlashMessage.js'

function harness(timeoutMs) {
  let api
  const wrapper = mount({
    setup() {
      api = useFlashMessage(timeoutMs)
      return () => null
    }
  })
  return {wrapper, api}
}

describe('useFlashMessage', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('flashes a message and auto-dismisses after the default timeout', () => {
    const {api} = harness()
    api.flash('Saved', 'success')
    expect(api.message.value).toEqual({text: 'Saved', type: 'success'})
    vi.advanceTimersByTime(5999)
    expect(api.message.value).not.toBeNull()
    vi.advanceTimersByTime(1)
    expect(api.message.value).toBeNull()
  })

  it('honours a per-instance timeout', () => {
    const {api} = harness(8000)
    api.flash('Hi')
    vi.advanceTimersByTime(6000)
    expect(api.message.value).not.toBeNull()
    vi.advanceTimersByTime(2000)
    expect(api.message.value).toBeNull()
  })

  it('defaults the type to info', () => {
    const {api} = harness()
    api.flash('Hello')
    expect(api.message.value).toEqual({text: 'Hello', type: 'info'})
  })

  it('show() keeps the message until cleared (no auto-dismiss)', () => {
    const {api} = harness()
    api.show('Boom', 'danger')
    vi.advanceTimersByTime(60_000)
    expect(api.message.value).toEqual({text: 'Boom', type: 'danger'})
    api.clear()
    expect(api.message.value).toBeNull()
  })

  it('replacing a flashed message cancels the previous timer', () => {
    const {api} = harness()
    api.flash('first', 'info')
    vi.advanceTimersByTime(3000)
    api.show('second', 'danger')
    vi.advanceTimersByTime(10_000)
    // The first timer must not wipe the persistent second message.
    expect(api.message.value).toEqual({text: 'second', type: 'danger'})
  })

  it('clears the pending timer on unmount', () => {
    const {wrapper, api} = harness()
    api.flash('bye')
    wrapper.unmount()
    expect(() => vi.advanceTimersByTime(6000)).not.toThrow()
  })
})
