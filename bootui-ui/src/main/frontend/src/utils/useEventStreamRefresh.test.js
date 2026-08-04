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

async function failUntilUnavailable() {
  for (const delay of [1_000, 2_000, 4_000, 8_000]) {
    latestSource().emit('error')
    await nextTick()
    await vi.advanceTimersByTimeAsync(delay)
  }
  latestSource().emit('error')
  await nextTick()
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
    expect(callback).toHaveBeenCalledTimes(2)

    latestSource().emit('update', '{"ts":3}')
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(3)

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

describe('useEventStreamRefresh – connectionState', () => {
  beforeEach(() => {
    instances.length = 0
    setVisibilityState('visible')
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('starts in connecting state and transitions to connected on open', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    expect(api.connectionState.value).toBe('connecting')

    latestSource().emit('open')
    await nextTick()

    expect(api.connectionState.value).toBe('connected')

    wrapper.unmount()
  })

  it('sets paused when the tab is hidden', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    latestSource().emit('open')
    await nextTick()
    expect(api.connectionState.value).toBe('connected')

    setVisibilityState('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()

    expect(api.connectionState.value).toBe('paused')
    expect(latestSource().closed).toBe(true)

    wrapper.unmount()
  })

  it('sets paused when auto-refresh is disabled', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    latestSource().emit('open')
    await nextTick()
    expect(api.connectionState.value).toBe('connected')

    api.autoRefresh.value = false
    await nextTick()

    expect(api.connectionState.value).toBe('paused')

    wrapper.unmount()
  })

  it('sets unavailable when EventSource is not supported', async () => {
    vi.stubGlobal('EventSource', undefined)
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    expect(api.connectionState.value).toBe('unavailable')

    wrapper.unmount()
  })

  it('stays paused when auto-refresh is disabled and EventSource is unsupported', () => {
    vi.stubGlobal('EventSource', undefined)
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback, {defaultEnabled: false})

    expect(api.connectionState.value).toBe('paused')

    wrapper.unmount()
  })

  it('owns reconnection with exponential backoff after an error', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    const firstSource = latestSource()
    firstSource.emit('open')
    await nextTick()

    firstSource.emit('error')
    await nextTick()

    expect(api.connectionState.value).toBe('reconnecting')
    expect(firstSource.closed).toBe(true)
    expect(instances.length).toBe(1)

    await vi.advanceTimersByTimeAsync(999)
    expect(instances.length).toBe(1)

    await vi.advanceTimersByTimeAsync(1)
    expect(instances.length).toBe(2)
    expect(latestSource().url).toBe('api/exceptions/stream')
    expect(api.connectionState.value).toBe('reconnecting')

    wrapper.unmount()
  })

  it('ignores events from a source after it has been closed', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)
    await flushPromises()

    const first = latestSource()
    first.emit('error')
    await nextTick()

    first.emit('open')
    first.emit('update')
    first.emit('error')
    await flushPromises()

    expect(api.connectionState.value).toBe('reconnecting')
    expect(callback).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(1)

    wrapper.unmount()
  })

  it('transitions to unavailable after five failed connection attempts', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    await failUntilUnavailable()

    expect(api.connectionState.value).toBe('unavailable')
    expect(instances).toHaveLength(5)
    expect(instances.every((source) => source.closed)).toBe(true)
    expect(vi.getTimerCount()).toBe(1)

    wrapper.unmount()
  })

  it('resets backoff after the recovered stream stays stable', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    latestSource().emit('error')
    await nextTick()
    await vi.advanceTimersByTimeAsync(1_000)

    latestSource().emit('open')
    await nextTick()
    expect(api.connectionState.value).toBe('connected')

    await vi.advanceTimersByTimeAsync(5_000)

    const countBeforeError = instances.length
    latestSource().emit('error')
    await nextTick()
    expect(api.connectionState.value).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(999)
    expect(instances).toHaveLength(countBeforeError)

    await vi.advanceTimersByTimeAsync(1)
    expect(instances).toHaveLength(countBeforeError + 1)

    wrapper.unmount()
  })

  it('reconnects from unavailable state after the long delay', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    await failUntilUnavailable()
    expect(api.connectionState.value).toBe('unavailable')
    const countBeforeLongDelay = instances.length

    await vi.advanceTimersByTimeAsync(59_999)
    expect(instances).toHaveLength(countBeforeLongDelay)

    await vi.advanceTimersByTimeAsync(1)
    expect(instances).toHaveLength(countBeforeLongDelay + 1)
    expect(api.connectionState.value).toBe('reconnecting')

    wrapper.unmount()
  })

  it('retries immediately on user request and cancels the long-delay retry', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)
    await flushPromises()
    await failUntilUnavailable()
    const countBeforeRetry = instances.length
    const loadsBeforeRetry = callback.mock.calls.length

    await api.retryConnection()

    expect(instances).toHaveLength(countBeforeRetry + 1)
    expect(api.connectionState.value).toBe('reconnecting')
    expect(callback).toHaveBeenCalledTimes(loadsBeforeRetry + 1)

    latestSource().emit('error')
    await nextTick()
    expect(api.connectionState.value).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(999)
    expect(instances).toHaveLength(countBeforeRetry + 1)

    await vi.advanceTimersByTimeAsync(1)
    expect(instances).toHaveLength(countBeforeRetry + 2)

    latestSource().emit('open')
    await nextTick()
    expect(api.connectionState.value).toBe('connected')

    wrapper.unmount()
  })

  it('cancels a pending retry while hidden and reconnects exactly once when visible', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    latestSource().emit('error')
    await nextTick()

    setVisibilityState('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()
    expect(api.connectionState.value).toBe('paused')

    await vi.advanceTimersByTimeAsync(1_000)
    expect(instances).toHaveLength(1)

    setVisibilityState('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()
    expect(instances).toHaveLength(2)
    expect(api.connectionState.value).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(1_000)
    expect(instances).toHaveLength(2)

    wrapper.unmount()
  })

  it('cancels a pending retry when auto-refresh is toggled off', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    latestSource().emit('error')
    await nextTick()

    api.autoRefresh.value = false
    await nextTick()
    expect(api.connectionState.value).toBe('paused')

    await vi.advanceTimersByTimeAsync(1_000)
    expect(instances).toHaveLength(1)

    api.autoRefresh.value = true
    await nextTick()
    expect(instances).toHaveLength(2)
    expect(api.connectionState.value).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(1_000)
    expect(instances).toHaveLength(2)

    wrapper.unmount()
  })

  it('closes the current source before an explicit restart', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    const first = latestSource()
    api.startAutoRefresh()

    expect(first.closed).toBe(true)
    expect(instances).toHaveLength(2)

    first.emit('open')
    first.emit('error')
    await nextTick()
    expect(api.connectionState.value).toBe('connecting')
    expect(vi.getTimerCount()).toBe(0)

    wrapper.unmount()
  })

  it('backs off when the EventSource constructor throws', async () => {
    class ThrowingEventSource {
      constructor() {
        throw new Error('invalid stream URL')
      }
    }
    vi.stubGlobal('EventSource', ThrowingEventSource)
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    expect(api.connectionState.value).toBe('reconnecting')
    expect(vi.getTimerCount()).toBe(1)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(api.connectionState.value).toBe('reconnecting')
    expect(vi.getTimerCount()).toBe(1)

    wrapper.unmount()
  })

  it('coalesces rapid update events without concurrent loads', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    const source = latestSource()
    source.emit('open')
    await nextTick()

    // Fire two update ticks synchronously. The first triggers a load (inFlight=true);
    // the second arrives before any microtasks run and is coalesced (load returns early).
    source.emit('update')
    source.emit('update')
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('clears all timers and closes the source on unmount', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    const source = latestSource()
    source.emit('error')
    await nextTick()

    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
    expect(source.closed).toBe(true)
    expect(vi.getTimerCount()).toBe(0)

    const countAtUnmount = instances.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(instances.length).toBe(countAtUnmount)
  })

  it('sets paused and invalidates the source when stopped explicitly', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    const source = latestSource()
    source.emit('open')
    await nextTick()
    expect(api.connectionState.value).toBe('connected')

    api.stopAutoRefresh()
    expect(source.closed).toBe(true)
    expect(api.connectionState.value).toBe('paused')

    source.emit('error')
    await nextTick()
    expect(api.connectionState.value).toBe('paused')
    expect(vi.getTimerCount()).toBe(0)

    wrapper.unmount()
  })
})
