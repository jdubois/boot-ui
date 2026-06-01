import {nextTick} from 'vue'
import {describe, expect, it, vi} from 'vitest'

import {useRefreshState} from './useRefreshState.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return {promise, resolve, reject}
}

describe('useRefreshState', () => {
  it('shows initial loading only until the first refresh attempt completes', async () => {
    const first = deferred()
    const second = deferred()
    const callback = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const state = useRefreshState(callback)

    expect(state.loading.value).toBe(true)
    expect(state.initialLoading.value).toBe(true)

    const firstRun = state.refresh()
    await nextTick()

    expect(state.loading.value).toBe(true)
    expect(state.initialLoading.value).toBe(true)

    first.resolve()
    await firstRun
    await nextTick()

    expect(state.loading.value).toBe(false)
    expect(state.hasLoaded.value).toBe(true)
    expect(state.initialLoading.value).toBe(false)

    const secondRun = state.refresh()
    await nextTick()

    expect(state.loading.value).toBe(true)
    expect(state.initialLoading.value).toBe(false)

    second.resolve()
    await secondRun
    await nextTick()

    expect(state.loading.value).toBe(false)
  })

  it('marks the initial load complete when the first refresh fails', async () => {
    const callback = vi.fn().mockRejectedValue(new Error('boom'))
    const state = useRefreshState(callback)

    await expect(state.refresh()).rejects.toThrow('boom')

    expect(state.loading.value).toBe(false)
    expect(state.hasLoaded.value).toBe(true)
    expect(state.initialLoading.value).toBe(false)
  })

  it('keeps loading true until overlapping refreshes finish', async () => {
    const first = deferred()
    const second = deferred()
    const callback = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const state = useRefreshState(callback)

    const firstRun = state.refresh()
    const secondRun = state.refresh()
    await nextTick()

    first.resolve()
    await firstRun
    await nextTick()

    expect(state.loading.value).toBe(true)

    second.resolve()
    await secondRun
    await nextTick()

    expect(state.loading.value).toBe(false)
  })
})
