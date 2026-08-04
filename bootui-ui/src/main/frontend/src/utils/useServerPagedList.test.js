import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {mount} from '@vue/test-utils'
import {useServerPagedList} from './useServerPagedList'

function harness(...args) {
  let api
  const wrapper = mount({
    setup() {
      api = useServerPagedList(...args)
      return () => null
    }
  })
  return {wrapper, api}
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

/**
 * Returns a fetch mock whose pending promise rejects with an AbortError when
 * the supplied signal fires.  Resolves normally once `resolve` is called.
 */
function abortablePending() {
  let resolve
  const fetchMock = vi.fn((_url, init) => {
    const signal = init?.signal
    return new Promise((res, rej) => {
      if (signal?.aborted) {
        rej(new DOMException('This operation was aborted', 'AbortError'))
        return
      }
      resolve = res
      signal?.addEventListener('abort', () => rej(new DOMException('This operation was aborted', 'AbortError')))
    })
  })
  return {fetchMock, resolve: (value) => resolve?.(value)}
}

describe('useServerPagedList', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('requests offset/limit and only non-empty query params', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 5, total: 9}}))
    const {api} = harness('api/things', 'things', () => ({q: 'foo', blank: '', missing: null}), {pageSize: 50})

    await api.load()

    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('q=foo')
    expect(url).not.toContain('blank')
    expect(url).not.toContain('missing')
    expect(url).toContain('offset=0')
    expect(url).toContain('limit=50')
    expect(api.items.value).toEqual([{id: 1}])
    expect(api.matchedCount.value).toBe(5)
    expect(api.totalCount.value).toBe(9)
    expect(api.hiddenCount.value).toBe(4)
  })

  it('appends the next page and offsets by current item count', async () => {
    global.fetch = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({things: [{id: 1}, {id: 2}], page: {matched: 4, total: 4}}))
      .mockResolvedValueOnce(jsonResponse({things: [{id: 3}, {id: 4}], page: {matched: 4, total: 4}}))
    const {api} = harness('api/things', 'things', () => ({}), {pageSize: 2})

    await api.load()
    await api.loadMore()

    expect(global.fetch.mock.calls[1][0]).toContain('offset=2')
    expect(api.items.value).toEqual([{id: 1}, {id: 2}, {id: 3}, {id: 4}])
  })

  it('does not loadMore when nothing is hidden', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()
    await api.loadMore()
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })

  it('ignores a stale response when a newer request supersedes it', async () => {
    let resolveFirst
    global.fetch = vi
      .fn()
      .mockImplementationOnce(() => new Promise((resolve) => (resolveFirst = resolve)))
      .mockResolvedValueOnce(jsonResponse({things: [{id: 99}], page: {matched: 1, total: 1}}))
    const {api} = harness('api/things', 'things', () => ({}))

    const firstLoad = api.load()
    // A newer scheduled reload supersedes the first load while it is in flight.
    api.scheduleReload()
    await vi.advanceTimersByTimeAsync(250)

    // Now the stale first request resolves; its data must be discarded.
    resolveFirst(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    await firstLoad

    expect(api.items.value).toEqual([{id: 99}])
  })

  it('discards a response when query values change before the watcher cancels it', async () => {
    let query = 'old'
    let resolveRequest
    global.fetch = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveRequest = resolve
        })
    )
    const {api} = harness('api/things', 'things', () => ({q: query}))

    const pendingLoad = api.load()
    query = 'new'
    resolveRequest(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    await pendingLoad

    expect(api.items.value).toEqual([])
    expect(api.loading.value).toBe(false)
  })

  it('captures an error message on a non-ok response', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse(null, false, 503))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()
    expect(api.error.value.message).toBe('Unable to load data: HTTP 503')
    expect(api.loading.value).toBe(false)
  })

  it('uses the errorContext option in error messages', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse(null, false, 503))
    const {api} = harness('api/things', 'things', () => ({}), {errorContext: 'Could not load things'})
    await api.load()
    expect(api.error.value.message).toBe('Could not load things: HTTP 503')
  })

  it('normalizes backend-down fetch failures', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()
    expect(api.error.value.serverUnreachable).toBe(true)
    expect(api.error.value.title).toBe('Server unreachable')
  })

  // AbortController lifecycle tests

  it('passes an AbortSignal to fetch', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [], page: {matched: 0, total: 0}}))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()
    const init = global.fetch.mock.calls[0][1]
    expect(init?.signal).toBeInstanceOf(AbortSignal)
  })

  it('scheduleReload aborts the in-flight base request', async () => {
    const {fetchMock} = abortablePending()
    global.fetch = fetchMock
    const {api} = harness('api/things', 'things', () => ({}))

    api.load()
    const capturedSignal = fetchMock.mock.calls[0][1]?.signal
    expect(capturedSignal?.aborted).toBe(false)

    api.scheduleReload()

    expect(capturedSignal?.aborted).toBe(true)
  })

  it('loading remains true while debounce timer is pending after aborting in-flight base', async () => {
    const {fetchMock, resolve} = abortablePending()
    global.fetch = fetchMock
    const {api} = harness('api/things', 'things', () => ({}))

    api.load()
    expect(api.loading.value).toBe(true)

    api.scheduleReload()
    // The aborted request's catch returns silently; the timer has not fired yet.
    await Promise.resolve()
    expect(api.loading.value).toBe(true)

    // Let the timer fire and the replacement fetch complete.
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    await vi.advanceTimersByTimeAsync(250)
    resolve() // old fetch can settle (its result is discarded)
    expect(api.loading.value).toBe(false)
  })

  it('marks a scheduled reload as loading and blocks append until the replacement finishes', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 2, total: 2}}))
    const {api} = harness('api/things', 'things', () => ({}), {pageSize: 1})
    await api.load()

    api.scheduleReload()
    const append = api.loadMore()

    expect(api.loading.value).toBe(true)
    expect(global.fetch).toHaveBeenCalledTimes(1)
    await append

    await vi.advanceTimersByTimeAsync(250)
    expect(global.fetch).toHaveBeenCalledTimes(2)
    expect(api.loading.value).toBe(false)
  })

  it('an explicit base load replaces and clears a pending debounced reload', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()

    api.scheduleReload()
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 2}], page: {matched: 1, total: 1}}))
    await api.load()
    await vi.advanceTimersByTimeAsync(250)

    expect(global.fetch).toHaveBeenCalledTimes(1)
    expect(api.items.value).toEqual([{id: 2}])
  })

  it('does not start an append while a base request owns the list', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 2, total: 2}}))
    const {api} = harness('api/things', 'things', () => ({}), {pageSize: 1})
    await api.load()

    const {fetchMock} = abortablePending()
    global.fetch = fetchMock
    const baseLoad = api.load()
    await api.load({append: true})

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toContain('offset=0')
    api.scheduleReload()
    await baseLoad
  })

  it('unmount aborts an in-flight base request', async () => {
    const {fetchMock} = abortablePending()
    global.fetch = fetchMock
    const {wrapper, api} = harness('api/things', 'things', () => ({}))

    api.load()
    const capturedSignal = fetchMock.mock.calls[0][1]?.signal
    expect(capturedSignal?.aborted).toBe(false)

    wrapper.unmount()

    expect(capturedSignal?.aborted).toBe(true)
  })

  it('unmount aborts an in-flight append request', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 2, total: 2}}))
    const {wrapper, api} = harness('api/things', 'things', () => ({}), {pageSize: 1})
    await api.load()
    expect(api.hiddenCount.value).toBe(1)

    const {fetchMock} = abortablePending()
    global.fetch = fetchMock
    api.loadMore()

    const capturedSignal = fetchMock.mock.calls[0][1]?.signal
    expect(capturedSignal?.aborted).toBe(false)

    wrapper.unmount()

    expect(capturedSignal?.aborted).toBe(true)
    expect(api.loadingMore.value).toBe(false)
  })

  it('base reload aborts and discards an in-flight append', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 2, total: 2}}))
    const {api} = harness('api/things', 'things', () => ({}), {pageSize: 1})
    await api.load()

    const {fetchMock: appendFetchMock} = abortablePending()
    global.fetch = appendFetchMock
    api.loadMore()

    const appendSignal = appendFetchMock.mock.calls[0][1]?.signal

    // Issue a base reload; it should abort the in-flight append.
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 99}], page: {matched: 1, total: 1}}))
    await api.load()

    expect(appendSignal?.aborted).toBe(true)
    expect(api.loadingMore.value).toBe(false)
    expect(api.items.value).toEqual([{id: 99}])
  })

  it('discards an append when its query values become stale before cancellation', async () => {
    let query = 'old'
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 2, total: 2}}))
    const {api} = harness('api/things', 'things', () => ({q: query}), {pageSize: 1})
    await api.load()

    let resolveAppend
    global.fetch = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveAppend = resolve
        })
    )
    const pendingAppend = api.loadMore()
    query = 'new'
    resolveAppend(jsonResponse({things: [{id: 2}], page: {matched: 2, total: 2}}))
    await pendingAppend

    expect(api.items.value).toEqual([{id: 1}])
    expect(api.loadingMore.value).toBe(false)
  })

  it('scheduleReload aborts and clears an in-flight append', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({things: [{id: 1}], page: {matched: 2, total: 2}}))
    const {api} = harness('api/things', 'things', () => ({}), {pageSize: 1})
    await api.load()

    const {fetchMock: appendFetchMock} = abortablePending()
    global.fetch = appendFetchMock
    api.loadMore()

    expect(api.loadingMore.value).toBe(true)
    const appendSignal = appendFetchMock.mock.calls[0][1]?.signal

    api.scheduleReload()

    expect(appendSignal?.aborted).toBe(true)
    expect(api.loadingMore.value).toBe(false)
  })

  it('AbortError is silent and does not surface an error or mutate items', async () => {
    const {fetchMock} = abortablePending()
    global.fetch = fetchMock
    const {api} = harness('api/things', 'things', () => ({}))

    const loadPromise = api.load()
    api.scheduleReload()
    await loadPromise

    expect(api.error.value).toBeNull()
    expect(api.items.value).toEqual([])
  })

  it('genuine HTTP failure still surfaces an error after abort-cancelled load is replaced', async () => {
    const {fetchMock} = abortablePending()
    global.fetch = fetchMock
    const {api} = harness('api/things', 'things', () => ({}))

    api.load()
    api.scheduleReload()

    // The replacement request resolves with an error status.
    global.fetch = vi.fn().mockResolvedValue(jsonResponse(null, false, 500))
    await vi.advanceTimersByTimeAsync(250)

    expect(api.error.value?.message).toBe('Unable to load data: HTTP 500')
    expect(api.loading.value).toBe(false)
  })

  it('concurrent base loads keep only the last result', async () => {
    let resolveFirst
    global.fetch = vi
      .fn()
      .mockImplementationOnce(() => new Promise((res) => (resolveFirst = res)))
      .mockResolvedValueOnce(jsonResponse({things: [{id: 2}], page: {matched: 1, total: 1}}))
    const {api} = harness('api/things', 'things', () => ({}))

    const p1 = api.load()
    const p2 = api.load()

    resolveFirst(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    await p1
    await p2

    expect(api.items.value).toEqual([{id: 2}])
  })
})
