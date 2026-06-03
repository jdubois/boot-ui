import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Threads from './Threads.vue'

function thread(overrides = {}) {
  return {
    id: 1,
    name: 'main',
    state: 'RUNNABLE',
    priority: 5,
    daemon: false,
    virtual: false,
    cpuTimeMillis: 1200,
    userTimeMillis: 800,
    blockedCount: 0,
    waitedCount: 0,
    inNative: false,
    suspended: false,
    deadlocked: false,
    lockName: null,
    lockOwnerId: null,
    lockOwnerName: null,
    stackTrace: ['com.example.App.main(App.java:10)'],
    ...overrides
  }
}

function report(overrides = {}) {
  const threads = overrides.threads || [thread()]
  return {
    available: true,
    unavailableReason: null,
    capturedAt: 1_700_000_000_000,
    totalThreads: threads.length,
    daemonThreads: 0,
    peakThreads: threads.length,
    startedThreadCount: threads.length,
    virtualThreadsSupported: true,
    cpuTimeSupported: true,
    deadlockDetected: false,
    deadlockedThreadIds: [],
    stateCounts: [{state: 'RUNNABLE', count: threads.length}],
    threads,
    page: {
      total: threads.length,
      matched: threads.length,
      offset: 0,
      limit: 200,
      returned: threads.length,
      hasMore: false
    },
    ...overrides
  }
}

async function mountWithReport(body, panel = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {status: 200})))
  )
  const wrapper = mount(Threads, {props: {panel}})
  await flushPromises()
  return wrapper
}

describe('Threads', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the live thread snapshot with a state summary', async () => {
    const wrapper = await mountWithReport(report())

    expect(wrapper.text()).toContain('Threads')
    expect(wrapper.text()).toContain('RUNNABLE: 1')
    expect(wrapper.find('code').text()).toBe('main')
  })

  it('expands and collapses the stack trace for a thread', async () => {
    const wrapper = await mountWithReport(report())

    expect(wrapper.text()).not.toContain('com.example.App.main(App.java:10)')
    await wrapper.find('.btn-link').trigger('click')
    expect(wrapper.text()).toContain('com.example.App.main(App.java:10)')
  })

  it('omits the stack toggle for threads with no stack frames', async () => {
    const wrapper = await mountWithReport(report({threads: [thread({stackTrace: []})]}))

    expect(wrapper.find('.btn-link').exists()).toBe(false)
  })

  it('flags deadlocks prominently', async () => {
    const wrapper = await mountWithReport(report({deadlockDetected: true, deadlockedThreadIds: [1, 2]}))

    expect(wrapper.text()).toContain('Deadlock detected.')
    expect(wrapper.text()).toContain('1, 2')
  })

  it('renders an unavailable state without a table', async () => {
    const wrapper = await mountWithReport({
      available: false,
      unavailableReason: 'ThreadMXBean is not available on this JVM',
      threads: [],
      stateCounts: [],
      deadlockedThreadIds: [],
      page: {total: 0, matched: 0, offset: 0, limit: 0, returned: 0, hasMore: false}
    })

    expect(wrapper.text()).toContain('ThreadMXBean is not available on this JVM')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('disables the download button when the panel is read-only', async () => {
    const wrapper = await mountWithReport(report(), {readOnly: true, readOnlyReason: 'BootUI is read-only'})

    const button = wrapper.find('button.btn-outline-primary')
    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Thread-dump download is read-only')
  })

  it('posts to the download endpoint when triggered', async () => {
    const fetchMock = vi.fn((url) => {
      if (typeof url === 'string' && url.includes('download')) {
        return Promise.resolve(new Response('dump', {status: 200}))
      }
      return Promise.resolve(new Response(JSON.stringify(report()), {status: 200}))
    })
    vi.stubGlobal('fetch', fetchMock)
    URL.createObjectURL = vi.fn(() => 'blob:dump')
    URL.revokeObjectURL = vi.fn()

    const wrapper = mount(Threads, {props: {panel: {}}})
    await flushPromises()

    await wrapper.find('button.btn-outline-primary').trigger('click')
    await flushPromises()

    expect(
      fetchMock.mock.calls.some(([url, init]) => String(url).includes('threads/download') && init?.method === 'POST')
    ).toBe(true)
  })
})
