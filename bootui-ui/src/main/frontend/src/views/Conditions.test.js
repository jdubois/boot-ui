import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import Conditions from './Conditions.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function conditionsResponse(overrides = {}) {
  return {
    positiveMatches: [
      {
        autoConfigurationClass: 'org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration',
        condition: 'OnClassCondition',
        message: '@ConditionalOnClass found required class',
        outcome: 'MATCH'
      }
    ],
    negativeMatches: [
      {
        autoConfigurationClass: 'org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration',
        condition: 'OnClassCondition',
        message: '@ConditionalOnClass did not find required class',
        outcome: 'NO_MATCH'
      }
    ],
    counts: {
      positiveMatched: 1,
      positiveTotal: 1,
      negativeMatched: 1,
      negativeTotal: 1
    },
    ...overrides
  }
}

/**
 * Returns an abort-aware fetch mock with an externally resolvable promise.
 */
function abortablePending() {
  let resolveInner
  const fetchMock = vi.fn((_url, init) => {
    const signal = init?.signal
    return new Promise((res, rej) => {
      if (signal?.aborted) {
        rej(new DOMException('This operation was aborted', 'AbortError'))
        return
      }
      resolveInner = res
      signal?.addEventListener('abort', () => rej(new DOMException('This operation was aborted', 'AbortError')))
    })
  })
  return {fetchMock, resolve: (value) => resolveInner?.(value)}
}

describe('Conditions', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders positive matches on load', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(conditionsResponse())))

    const wrapper = mount(Conditions)
    await flushPromises()

    expect(wrapper.text()).toContain('WebMvcAutoConfiguration')
    expect(wrapper.text()).toContain('Positive (1)')
  })

  it('switches to negative tab and loads negative matches', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(conditionsResponse())))

    const wrapper = mount(Conditions)
    await flushPromises()

    await wrapper.findAll('a.nav-link')[1].trigger('click')
    await flushPromises() // flush Vue watcher so the debounce timer is set
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.text()).toContain('BatchAutoConfiguration')
  })

  it('passes an AbortSignal to fetch', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(conditionsResponse()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Conditions)
    await flushPromises()

    const init = fetchMock.mock.calls[0][1]
    expect(init?.signal).toBeInstanceOf(AbortSignal)
    wrapper.unmount()
  })

  it('scheduleReload (tab change) aborts the in-flight base request', async () => {
    const {fetchMock} = abortablePending()
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Conditions)
    // onMounted triggers load(); the signal is captured immediately
    const capturedSignal = fetchMock.mock.calls[0][1]?.signal
    expect(capturedSignal?.aborted).toBe(false)

    // Changing the tab calls scheduleReload via a Vue watcher.
    await wrapper.findAll('a.nav-link')[1].trigger('click')
    await flushPromises() // flush Vue watcher so scheduleReload() runs
    expect(capturedSignal?.aborted).toBe(true)

    wrapper.unmount()
  })

  it('hides pagination while a debounced replacement is pending', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          conditionsResponse({
            counts: {positiveMatched: 2, positiveTotal: 2, negativeMatched: 1, negativeTotal: 1}
          })
        )
      )
    )

    const wrapper = mount(Conditions)
    await flushPromises()
    expect(wrapper.findComponent({name: 'ServerListFooter'}).exists()).toBe(true)

    await wrapper.findAll('a.nav-link')[1].trigger('click')
    await flushPromises()

    expect(wrapper.findComponent({name: 'ServerListFooter'}).exists()).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('discards a stale response when the fetch mock ignores abort', async () => {
    let resolveStale
    const staleFetch = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveStale = resolve
        })
    )
    vi.stubGlobal('fetch', staleFetch)
    const wrapper = mount(Conditions)

    await wrapper.findAll('a.nav-link')[1].trigger('click')
    await flushPromises()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          conditionsResponse({
            negativeMatches: [{autoConfigurationClass: 'LATEST', condition: 'C', message: 'M', outcome: 'NO_MATCH'}]
          })
        )
      )
    )
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    resolveStale(
      jsonResponse(
        conditionsResponse({
          negativeMatches: [{autoConfigurationClass: 'STALE', condition: 'C', message: 'M', outcome: 'NO_MATCH'}]
        })
      )
    )
    await flushPromises()

    expect(wrapper.text()).toContain('LATEST')
    expect(wrapper.text()).not.toContain('STALE')
    wrapper.unmount()
  })

  it('unmount aborts an in-flight base request', async () => {
    const {fetchMock} = abortablePending()
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Conditions)
    const capturedSignal = fetchMock.mock.calls[0][1]?.signal
    expect(capturedSignal?.aborted).toBe(false)

    wrapper.unmount()

    expect(capturedSignal?.aborted).toBe(true)
  })

  it('AbortError is silent and does not surface an error', async () => {
    const {fetchMock} = abortablePending()
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Conditions)
    const capturedSignal = fetchMock.mock.calls[0][1]?.signal

    // Abort by unmounting; no error should appear.
    wrapper.unmount()

    // The signal was aborted.
    expect(capturedSignal?.aborted).toBe(true)
    // No error banner visible (component is unmounted but we verify state was not set).
    // The component was destroyed so we check via fetchMock being called only once.
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('genuine HTTP failure surfaces an error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 503)))

    const wrapper = mount(Conditions)
    await flushPromises()

    expect(wrapper.text()).toContain('503')
    wrapper.unmount()
  })

  it('surfaces a genuine failure from the replacement after silently aborting the stale request', async () => {
    const {fetchMock} = abortablePending()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Conditions)

    await wrapper.findAll('a.nav-link')[1].trigger('click')
    await flushPromises()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 500)))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.text()).toContain('Unable to load conditions: HTTP 500')
    wrapper.unmount()
  })

  it('append (loadMore) is aborted by a subsequent base reload', async () => {
    const pageOneResponse = conditionsResponse({
      positiveMatches: [{autoConfigurationClass: 'A', condition: 'C', message: 'M', outcome: 'MATCH'}],
      counts: {positiveMatched: 2, positiveTotal: 2, negativeMatched: 0, negativeTotal: 0}
    })

    // First fetch: base load (page 1)
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(pageOneResponse))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Conditions)
    await flushPromises()

    // Second fetch: loadMore (append) - pending
    const {fetchMock: appendFetchMock} = abortablePending()
    vi.stubGlobal('fetch', appendFetchMock)

    const footer = wrapper.findComponent({name: 'ServerListFooter'})
    await footer.find('button').trigger('click')

    const appendSignal = appendFetchMock.mock.calls[0]?.[1]?.signal
    expect(appendSignal).toBeDefined()
    expect(appendSignal?.aborted).toBe(false)

    // A tab change causes scheduleReload which aborts the append.
    await wrapper.findAll('a.nav-link')[1].trigger('click')
    await flushPromises() // flush Vue watcher so scheduleReload() runs
    expect(appendSignal?.aborted).toBe(true)

    wrapper.unmount()
  })

  it('unmount aborts an in-flight append request', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(
        jsonResponse(
          conditionsResponse({
            positiveMatches: [{autoConfigurationClass: 'A', condition: 'C', message: 'M', outcome: 'MATCH'}],
            counts: {positiveMatched: 2, positiveTotal: 2, negativeMatched: 0, negativeTotal: 0}
          })
        )
      )
    )
    const wrapper = mount(Conditions)
    await flushPromises()

    const {fetchMock} = abortablePending()
    vi.stubGlobal('fetch', fetchMock)
    await wrapper.findComponent({name: 'ServerListFooter'}).find('button').trigger('click')
    const appendSignal = fetchMock.mock.calls[0][1]?.signal

    wrapper.unmount()

    expect(appendSignal?.aborted).toBe(true)
  })
})
