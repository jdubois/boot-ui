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

  it('transitions to reconnecting on first error and opens a new source after backoff', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    const firstSource = latestSource()
    firstSource.emit('open')
    await nextTick()

    firstSource.emit('error')
    await nextTick()

    expect(api.connectionState.value).toBe('reconnecting')
    expect(firstSource.closed).toBe(true)
    // No second source yet — waiting for backoff.
    expect(instances.length).toBe(1)

    // Advance past the first backoff window (1 s).
    await vi.advanceTimersByTimeAsync(1_000)

    expect(instances.length).toBe(2)
    expect(latestSource().url).toBe('api/exceptions/stream')

    wrapper.unmount()
  })

  it('closes existing source before opening a new one (no duplicate instances)', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    const first = latestSource()
    first.emit('error')
    await nextTick()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(first.closed).toBe(true)
    expect(instances.length).toBe(2)

    wrapper.unmount()
  })

  it('transitions to unavailable after MAX_RETRIES consecutive errors', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    // Emit 5 consecutive errors on the same source without advancing time between them.
    // Each error increments retryCount; on the 5th the state flips to unavailable.
    const source = latestSource()
    for (let i = 0; i < 5; i++) {
      source.emit('error')
      await nextTick()
    }

    expect(api.connectionState.value).toBe('unavailable')

    wrapper.unmount()
  })

  it('resets retry count after stream stays stable', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    // Cause one error and recover.
    latestSource().emit('error')
    await nextTick()
    await vi.advanceTimersByTimeAsync(1_000)

    latestSource().emit('open')
    await nextTick()
    expect(api.connectionState.value).toBe('connected')

    // Advance past the stability window (5 s).
    await vi.advanceTimersByTimeAsync(5_000)

    // Now trigger 5 more errors — if retries were reset the state should be reconnecting (not unavailable).
    for (let i = 0; i < 4; i++) {
      latestSource().emit('error')
      await nextTick()
      await vi.advanceTimersByTimeAsync(60_000)
    }
    expect(api.connectionState.value).toBe('reconnecting')

    wrapper.unmount()
  })

  it('reconnects from unavailable state after the long delay', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    const source = latestSource()
    for (let i = 0; i < 5; i++) {
      source.emit('error')
      await nextTick()
    }
    expect(api.connectionState.value).toBe('unavailable')
    const countBeforeLongDelay = instances.length

    // Advance past the long delay (60 s) — the long-delay retry should open a new EventSource.
    await vi.advanceTimersByTimeAsync(60_000)

    expect(instances.length).toBeGreaterThan(countBeforeLongDelay)

    wrapper.unmount()
  })

  it('coalesces rapid update events — no concurrent loads', async () => {
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

    // Only one additional load despite two ticks.
    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('clears all timers and closes the source on unmount', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness('api/exceptions/stream', callback)

    const source = latestSource()
    source.emit('error')
    await nextTick()

    // Unmount before the retry timer fires.
    wrapper.unmount()
    expect(source.closed).toBe(true)

    // Advancing time must not open a new source.
    const countAtUnmount = instances.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(instances.length).toBe(countAtUnmount)
  })

  it('transitions to reconnecting (not connecting) on retry', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness('api/exceptions/stream', callback)

    latestSource().emit('error')
    await nextTick()
    expect(api.connectionState.value).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(1_000)
    // After the retry opens a new source the state should still be reconnecting until open fires.
    expect(api.connectionState.value).toBe('reconnecting')

    wrapper.unmount()
  })
})
