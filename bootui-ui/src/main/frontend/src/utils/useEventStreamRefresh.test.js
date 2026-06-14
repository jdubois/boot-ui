import {flushPromises, mount} from '@vue/test-utils'
import {nextTick, ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import {useEventStreamRefresh} from './useEventStreamRefresh.js'

const instances = []

class MockEventSource {
  constructor(url) {
    this.url = url
    this.listeners = {}
    this.closed = false
    instances.push(this)
  }

  addEventListener(type, handler) {
    ;(this.listeners[type] ||= []).push(handler)
  }

  emit(type, data) {
    for (const handler of this.listeners[type] || []) {
      handler({data})
    }
  }

  close() {
    this.closed = true
  }
}

function harness(streamUrl, callback, options) {
  let api
  const wrapper = mount({
    setup() {
      api = useEventStreamRefresh(streamUrl, callback, options)
      return () => null
    }
  })
  return {api, wrapper}
}

function setVisibilityState(value) {
  Object.defineProperty(document, 'visibilityState', {configurable: true, value})
}

function latestSource() {
  return instances[instances.length - 1]
}

describe('useEventStreamRefresh', () => {
  beforeEach(() => {
    instances.length = 0
    setVisibilityState('visible')
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads immediately on mount and opens the stream', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(1)
    expect(api.loading.value).toBe(false)
    expect(api.hasLoaded.value).toBe(true)
    expect(latestSource().url).toBe('api/exceptions/stream')

    wrapper.unmount()
  })

  it('refreshes when the server pushes an update tick', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    latestSource().emit('update', '{"ts":1}')
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('ignores update ticks while hidden but allows manual refresh', async () => {
    setVisibilityState('hidden')
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)
    // No stream is opened while hidden.
    expect(latestSource()).toBeUndefined()

    await api.load()
    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('reconnects and refreshes when a hidden tab becomes visible again', async () => {
    setVisibilityState('hidden')
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    setVisibilityState('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)
    expect(latestSource().url).toBe('api/exceptions/stream')

    wrapper.unmount()
  })

  it('closes the stream while auto-refresh is disabled and reopens when re-enabled', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    const opened = latestSource()

    api.autoRefresh.value = false
    await nextTick()
    expect(opened.closed).toBe(true)

    opened.emit('update', '{"ts":2}')
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    api.autoRefresh.value = true
    await nextTick()
    await flushPromises()

    latestSource().emit('update', '{"ts":3}')
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('closes the stream on unmount', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    const opened = latestSource()

    wrapper.unmount()
    expect(opened.closed).toBe(true)
  })

  it('waits for the enabled flag before loading or opening the stream', async () => {
    const enabled = ref(false)
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback, {enabled, initialLoading: false})

    await flushPromises()
    expect(callback).not.toHaveBeenCalled()
    expect(latestSource()).toBeUndefined()

    enabled.value = true
    await nextTick()
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(1)
    expect(latestSource().url).toBe('api/exceptions/stream')

    wrapper.unmount()
  })

  it('still performs the initial load when EventSource is unavailable', async () => {
    vi.stubGlobal('EventSource', undefined)
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})
