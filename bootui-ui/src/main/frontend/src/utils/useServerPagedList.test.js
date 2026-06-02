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
    // A newer scheduled reload bumps the request id while the first load is in flight.
    api.scheduleReload()
    await vi.advanceTimersByTimeAsync(250)

    // Now the stale first request resolves; its data must be discarded.
    resolveFirst(jsonResponse({things: [{id: 1}], page: {matched: 1, total: 1}}))
    await firstLoad

    expect(api.items.value).toEqual([{id: 99}])
  })

  it('captures an error message on a non-ok response', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse(null, false, 503))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()
    expect(api.error.value).toBe('HTTP 503')
    expect(api.loading.value).toBe(false)
  })

  it('normalizes backend-down fetch failures', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const {api} = harness('api/things', 'things', () => ({}))
    await api.load()
    expect(api.error.value).toBe(
      'Server unreachable: BootUI could not reach the Spring Boot app. The server may have been stopped. Start it again, then retry or refresh this page.'
    )
  })
})
