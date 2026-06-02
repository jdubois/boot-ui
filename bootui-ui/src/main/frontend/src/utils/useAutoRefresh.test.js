import {flushPromises, mount} from '@vue/test-utils'
import {nextTick, ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import {useAutoRefresh} from './useAutoRefresh.js'

function harness(callback, options) {
  let api
  const wrapper = mount({
    setup() {
      api = useAutoRefresh(callback, options)
      return () => null
    }
  })
  return {api, wrapper}
}

function setVisibilityState(value) {
  Object.defineProperty(document, 'visibilityState', {configurable: true, value})
}

describe('useAutoRefresh', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    setVisibilityState('visible')
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('loads immediately on mount with shared loading state', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness(callback)

    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(1)
    expect(api.loading.value).toBe(false)
    expect(api.hasLoaded.value).toBe(true)
    expect(api.initialLoading.value).toBe(false)

    wrapper.unmount()
  })

  it('refreshes every 10 seconds while enabled and visible', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness(callback)

    await flushPromises()
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)
    expect(api.hasLoaded.value).toBe(true)

    wrapper.unmount()
  })

  it('skips interval refreshes while hidden but still allows manual refresh', async () => {
    setVisibilityState('hidden')
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness(callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10_000)
    expect(callback).toHaveBeenCalledTimes(1)

    await api.load()
    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('refreshes immediately when a hidden tab becomes visible again', async () => {
    setVisibilityState('hidden')
    const callback = vi.fn().mockResolvedValue()
    const {wrapper} = harness(callback)

    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)

    setVisibilityState('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('stops interval refreshes while auto-refresh is disabled', async () => {
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness(callback)

    await flushPromises()
    api.autoRefresh.value = false
    await nextTick()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(callback).toHaveBeenCalledTimes(1)

    api.autoRefresh.value = true
    await nextTick()
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('waits for the enabled flag before loading or refreshing', async () => {
    const enabled = ref(false)
    const callback = vi.fn().mockResolvedValue()
    const {api, wrapper} = harness(callback, {enabled, initialLoading: false})

    await flushPromises()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(callback).not.toHaveBeenCalled()
    expect(api.initialLoading.value).toBe(false)

    enabled.value = true
    await nextTick()
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(1)
    expect(api.hasLoaded.value).toBe(true)

    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()

    expect(callback).toHaveBeenCalledTimes(2)

    enabled.value = false
    await nextTick()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(callback).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })
})
