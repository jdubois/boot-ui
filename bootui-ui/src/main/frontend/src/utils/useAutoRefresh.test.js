import {flushPromises, mount} from '@vue/test-utils'
import {nextTick, ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import {useAutoRefresh} from './useAutoRefresh.js'

const mounted = []

function harness(callback, options) {
  let api
  const wrapper = mount({
    setup() {
      api = useAutoRefresh(callback, options)
      return () => null
    }
  })
  mounted.push(wrapper)
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
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
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

  it('coalesces explicit post-action reads after a slow read even with auto-refresh off', async () => {
    let finishRead
    const callback = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishRead = resolve
          })
      )
      .mockResolvedValue()
    const {api, wrapper} = harness(callback, {defaultEnabled: false})
    await flushPromises()
    api.loadAfterCurrent()
    api.loadAfterCurrent()
    await vi.advanceTimersByTimeAsync(2_500)
    expect(callback).toHaveBeenCalledTimes(1)
    finishRead()
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(2)
    expect(api.loading.value).toBe(false)
    wrapper.unmount()
  })

  it('still drops ordinary overlapping reads and does not start queued work after unmount', async () => {
    let finishRead
    const callback = vi.fn(
      () =>
        new Promise((resolve) => {
          finishRead = resolve
        })
    )
    const {api, wrapper} = harness(callback, {defaultEnabled: false})
    await flushPromises()
    await api.load()
    finishRead()
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)
    const next = api.load()
    api.loadAfterCurrent()
    wrapper.unmount()
    finishRead()
    await next
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(2)
  })

  it('awaits the coalesced post-action read with its latest arguments', async () => {
    let finishInitial
    let finishFollowup
    const callback = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          })
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishFollowup = resolve
          })
      )
    const {api, wrapper} = harness(callback, {defaultEnabled: false})
    const first = api.loadAfterCurrent({preserveActionMessage: false})
    const second = api.loadAfterCurrent({preserveActionMessage: true})
    let settled = false
    Promise.resolve(second).then(() => {
      settled = true
    })
    await flushPromises()
    expect(settled).toBe(false)
    finishInitial()
    await flushPromises()
    expect(callback).toHaveBeenLastCalledWith({preserveActionMessage: true})
    expect(settled).toBe(false)
    finishFollowup('fresh')
    expect(await first).toBe('fresh')
    expect(await second).toBe('fresh')
    wrapper.unmount()
  })

  it.each(['unmount', 'disable'])('settles a queued post-action read on %s without invoking it', async (reason) => {
    let finishInitial
    const enabled = ref(true)
    const callback = vi.fn(
      () =>
        new Promise((resolve) => {
          finishInitial = resolve
        })
    )
    const {api, wrapper} = harness(callback, {enabled, defaultEnabled: false})
    const pending = api.loadAfterCurrent()
    if (reason === 'unmount') wrapper.unmount()
    else enabled.value = false
    await nextTick()
    let settled = false
    Promise.resolve(pending).then(() => {
      settled = true
    })
    await flushPromises()
    expect(settled).toBe(true)
    finishInitial()
    await flushPromises()
    expect(callback).toHaveBeenCalledTimes(1)
    if (reason !== 'unmount') wrapper.unmount()
  })

  it('delivers a queued read failure to its caller', async () => {
    let finishInitial
    const callback = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          })
      )
      .mockRejectedValueOnce(new Error('follow-up failed'))
    const {api, wrapper} = harness(callback, {defaultEnabled: false})
    const pending = api.loadAfterCurrent()
    expect(pending).toBeInstanceOf(Promise)
    const outcome = Promise.resolve(pending).then(
      () => null,
      (error) => error
    )
    finishInitial()
    expect(await outcome).toEqual(new Error('follow-up failed'))
    wrapper.unmount()
  })
})
