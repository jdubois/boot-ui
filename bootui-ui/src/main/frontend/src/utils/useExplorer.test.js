import {flushPromises, mount} from '@vue/test-utils'
import {defineComponent, ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {detail, event, report} from '../test/explorerFixtures.js'
import {explorerEventVersionKey} from './explorerModel.js'
import {useExplorer} from './useExplorer.js'

const response = (body) => Promise.resolve({ok: true, json: async () => body})
const deferred = () => {
  let resolve
  const promise = new Promise((done) => {
    resolve = done
  })
  return {promise, resolve}
}
let wrappers = [],
  streams = []
function start(enabled = ref(true)) {
  let state
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useExplorer(enabled)
        return () => null
      }
    })
  )
  wrappers.push(wrapper)
  return {state, wrapper, enabled}
}
beforeEach(() => {
  vi.useFakeTimers()
  Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  streams = []
  vi.stubGlobal(
    'EventSource',
    class {
      constructor(url) {
        this.url = url
        this.listeners = {}
        this.close = vi.fn()
        streams.push(this)
      }
      addEventListener(type, callback) {
        this.listeners[type] = callback
      }
    }
  )
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers = []
  vi.useRealTimers()
  vi.unstubAllGlobals()
  Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
})

describe('Explorer reads and selection lifecycle', () => {
  it('pins historical selection to its displayed timestamp rather than a reused source id', async () => {
    const old = event('sql-1', {timestamp: 1234})
    const fetch = vi.fn((url) => response(url === 'api/explorer' ? report([old]) : detail({event: old})))
    vi.stubGlobal('fetch', fetch)
    const {state} = start()
    await flushPromises()
    state.select(old)
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toContain('api/explorer/events/sql-1?timestamp=1234')
  })

  it('does not read, subscribe, or select detail on an unsupported manifest', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const {state} = start(ref(false))
    state.select(event())
    await state.refresh()
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()
    expect(streams).toHaveLength(0)
  })

  it('reads canonical activity with bean capture off and uses one existing activity stream', async () => {
    const fetch = vi.fn(() =>
      response(report([event()], {setup: {beanCaptureEnabled: false, beanDetailAvailable: false}}))
    )
    vi.stubGlobal('fetch', fetch)
    const {state} = start()
    await flushPromises()
    expect(state.visibleEntries.value).toHaveLength(1)
    expect(streams.map((stream) => stream.url)).toEqual(['api/activity/stream'])
    expect(fetch.mock.calls[0][0]).toBe('api/explorer')
    expect(state.freshIds.value).toEqual([])
  })

  it('looks up historical canonical IDs and ignores a late result after changing selection', async () => {
    const first = deferred()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        url === 'api/explorer'
          ? response(report())
          : url.includes('history%2F1')
            ? first.promise
            : response(detail({event: event('second')}))
      )
    )
    const {state} = start()
    await flushPromises()
    state.select(event('history/1'))
    state.select(event('second'))
    await flushPromises()
    first.resolve({ok: true, json: async () => detail({event: event('history/1')})})
    await flushPromises()
    expect(state.selectedId.value).toBe('second')
    expect(state.detail.value.event.id).toBe('second')
  })

  it('does one bounded detail reconciliation, cancels it at pause, and never starts a detail poller', async () => {
    const fetch = vi.fn((url) => response(url === 'api/explorer' ? report() : detail({partial: true})))
    vi.stubGlobal('fetch', fetch)
    const {state} = start()
    await flushPromises()
    state.select(event())
    await flushPromises()
    await vi.advanceTimersByTimeAsync(500)
    await vi.advanceTimersByTimeAsync(10000)
    expect(fetch.mock.calls.filter(([url]) => url.includes('/events/'))).toHaveLength(2)
    state.select(event('other'))
    await flushPromises()
    state.autoRefresh.value = false
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetch.mock.calls.filter(([url]) => url.includes('/events/'))).toHaveLength(3)
  })

  it('preserves selection and baseline-loads initial, pause and hidden-tab backlog before following fresh IDs', async () => {
    let snapshot = report()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => response(url.includes('/events/') ? detail() : snapshot))
    )
    const {state} = start()
    state.follow.value = true
    await flushPromises()
    state.select(event())
    await flushPromises()
    snapshot = report([event('new', {timestamp: 2000}), event()])
    await state.refresh()
    expect(state.freshIds.value).toEqual([explorerEventVersionKey(event('new', {timestamp: 2000}))])
    expect(state.selectedId.value).toBe('request-1')
    state.autoRefresh.value = false
    await flushPromises()
    snapshot = report([event('paused', {timestamp: 3000})])
    state.autoRefresh.value = true
    await flushPromises()
    expect(state.freshIds.value).toEqual([])
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'hidden'})
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    snapshot = report([event('hidden', {timestamp: 4000})])
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(state.visibleEntries.value[0].id).toBe('hidden')
    expect(state.freshIds.value).toEqual([])
  })

  it('pushes filters entered during the initial request into the next canonical API read', async () => {
    const initial = deferred()
    const fetch = vi
      .fn()
      .mockImplementationOnce(() => initial.promise)
      .mockImplementation(() => response(report()))
    vi.stubGlobal('fetch', fetch)
    const {state} = start()
    state.type.value = 'SQL'
    state.severity.value = 'ERROR'
    state.text.value = 'orders'
    await vi.advanceTimersByTimeAsync(300)
    initial.resolve({ok: true, json: async () => report()})
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toContain('api/explorer?type=SQL&severity=ERROR&q=orders')
  })

  it('uses canonical durable filters and cursors without turning older events into fresh effects', async () => {
    const initial = report([event()], {
      activity: {...report().activity, pageInfo: {persistent: true, hasMore: true, nextCursor: 'older/cursor'}}
    })
    const fetch = vi.fn((url) =>
      response(
        url.includes('cursor=')
          ? report([event('old', {timestamp: 100})], {
              activity: {
                ...report([event('old', {timestamp: 100})]).activity,
                pageInfo: {persistent: true, hasMore: false}
              }
            })
          : initial
      )
    )
    vi.stubGlobal('fetch', fetch)
    const {state} = start()
    await flushPromises()
    state.type.value = 'REQUEST'
    state.text.value = 'orders'
    await vi.advanceTimersByTimeAsync(300)
    await state.loadOlder()
    expect(
      fetch.mock.calls.map(([url]) => url).some((url) => url.includes('type=REQUEST') && url.includes('q=orders'))
    ).toBe(true)
    expect(fetch.mock.calls.map(([url]) => url).some((url) => url.includes('cursor=older%2Fcursor'))).toBe(true)
    expect(state.entries.value.map((item) => item.id)).toEqual(['request-1', 'old'])
    expect(state.freshIds.value).toEqual([])
  })

  it('keeps older timestamped versions when a persistent source id is reused', async () => {
    const newest = event('reused', {timestamp: 2000})
    const oldest = event('reused', {timestamp: 1000})
    const initial = report([newest], {
      activity: {
        ...report().activity,
        entries: [newest],
        pageInfo: {persistent: true, hasMore: true, nextCursor: 'next'}
      }
    })
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        response(
          url.includes('cursor=')
            ? report([oldest], {
                activity: {
                  ...report().activity,
                  entries: [oldest],
                  pageInfo: {persistent: true, hasMore: false}
                }
              })
            : initial
        )
      )
    )
    const {state} = start()
    await flushPromises()
    await state.loadOlder()
    expect(state.entries.value).toEqual([newest, oldest])
  })

  it('retains accepted evidence on refresh failure and aborts requests and stream on unmount', async () => {
    const fetch = vi
      .fn()
      .mockImplementationOnce(() => response(report()))
      .mockRejectedValue(new Error('Offline'))
    vi.stubGlobal('fetch', fetch)
    const {state, wrapper} = start()
    await flushPromises()
    await state.refresh()
    expect(state.visibleEntries.value).toHaveLength(1)
    expect(state.error.value).toBeTruthy()
    const signal = fetch.mock.calls.at(-1)[1].signal
    wrapper.unmount()
    wrappers = []
    expect(signal.aborted).toBe(true)
    expect(streams[0].close).toHaveBeenCalled()
  })
})
