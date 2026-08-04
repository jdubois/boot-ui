import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import LiveActivity from './LiveActivity.vue'
import Exceptions from './Exceptions.vue'
import RestClientTrace from './RestClientTrace.vue'
import SqlTrace from './SqlTrace.vue'
import SecurityLogs from './SecurityLogs.vue'
import LogTail from './LogTail.vue'

// Exceptions/SqlTrace read the router query in onMounted; the others ignore vue-router.
vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))

// Captures every EventSource opened during a mount so the test can assert which SSE endpoint a
// streaming panel subscribes to and which named event it listens for. Mirrors the mock used by
// useEventStreamRefresh.test.js, tolerating the onopen/onerror property assignments LogTail sets.
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

function setVisibilityState(value) {
  Object.defineProperty(document, 'visibilityState', {configurable: true, value})
}

beforeEach(() => {
  instances.length = 0
  // The auto-refresh composable only opens the stream while the document is visible.
  setVisibilityState('visible')
  vi.stubGlobal('EventSource', MockEventSource)
  // Streaming views fire an initial REST load on mount. The SSE subscription is opened synchronously
  // during mount (before this resolves), so a never-settling fetch leaves each view in its safe
  // initial render and avoids a post-assertion re-render against a partial payload.
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise(() => {}))
  )
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('streaming panel SSE wiring', () => {
  const tickPanels = [
    ['Live Activity', 'api/activity/stream', LiveActivity],
    ['Exceptions', 'api/exceptions/stream', Exceptions],
    ['REST Client', 'api/rest-client-trace/stream', RestClientTrace],
    ['SQL Trace', 'api/sql-trace/stream', SqlTrace],
    ['Security Logs', 'api/security-logs/stream', SecurityLogs]
  ]

  function mountView(view) {
    return mount(view, {global: {stubs: {RouterLink: true}}})
  }

  // Each tick panel must subscribe to its own /stream endpoint and refresh on the shared `update`
  // event. A regression here (wrong endpoint, or listening for the wrong event name) silently
  // breaks live auto-refresh against the Quarkus adapter, which only emits `update` ticks.
  it.each(tickPanels)('%s subscribes to %s and refreshes on the update event', (_label, streamUrl, view) => {
    const wrapper = mountView(view)

    expect(instances).toHaveLength(1)
    expect(instances[0].url).toBe(streamUrl)
    expect(instances[0].listeners.update).toBeTruthy()

    wrapper.unmount()
  })

  it.each(tickPanels)('%s renders connection degradation from its stream', async (_label, _streamUrl, view) => {
    const wrapper = mountView(view)

    instances[0].emit('error')
    await nextTick()

    expect(wrapper.get('.stream-status-indicator').text()).toContain('Reconnecting')

    wrapper.unmount()
  })

  it('retries an unavailable stream immediately from the shared status control', async () => {
    vi.useFakeTimers()
    const wrapper = mountView(Exceptions)

    for (const delay of [1_000, 2_000, 4_000, 8_000]) {
      instances.at(-1).emit('error')
      await nextTick()
      await vi.advanceTimersByTimeAsync(delay)
    }
    instances.at(-1).emit('error')
    await nextTick()

    const retry = wrapper.get('.stream-status-retry')
    expect(retry.text()).toBe('Retry now')
    expect(instances).toHaveLength(5)

    await retry.trigger('click')

    expect(instances).toHaveLength(6)
    expect(wrapper.get('.stream-status-indicator').text()).toContain('Reconnecting')

    wrapper.unmount()
  })

  it('Log Tail subscribes to api/log-tail/stream and listens for the log event', () => {
    const wrapper = mountView(LogTail)

    expect(instances).toHaveLength(1)
    expect(instances[0].url).toBe('api/log-tail/stream')
    expect(instances[0].listeners.log).toBeTruthy()

    wrapper.unmount()
  })
})
