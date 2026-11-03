import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

const {confirm} = vi.hoisted(() => ({confirm: vi.fn()}))
vi.mock('../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))

import Threads from './Threads.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'

let wrappers = []

function threadFetch(fetchMock) {
  return (url, options) => {
    if (String(url).endsWith('threads/captures') && options?.method !== 'POST')
      return Promise.resolve(
        new Response(
          JSON.stringify({
            available: true,
            captures: [],
            captureLimit: 6,
            retainedBytes: 0,
            retainedByteLimit: 16777216,
            observationSamples: 3,
            maxObservationSamples: 3,
            observationIntervalMillis: 5000,
            captureDeadlineMillis: 2000
          }),
          {status: 200}
        )
      )
    return fetchMock(url, options)
  }
}

function downloadButton(wrapper) {
  return wrapper.findAll('button').find((button) => button.text() === 'Download dump')
}

function deferred() {
  let resolve
  const promise = new Promise((resolver) => {
    resolve = resolver
  })
  return {promise, resolve}
}

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

async function mountWithReport(
  body,
  panel = {},
  fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {status: 200})))
) {
  vi.stubGlobal('fetch', threadFetch(fetchMock))
  const wrapper = mount(Threads, {props: {panel}})
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('Threads', () => {
  it('hides BootUI threads by default and reloads the live list when the shared switch changes', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn(
      async (url) =>
        new Response(
          JSON.stringify(
            report({
              threads: String(url).includes('includeBootUi=true')
                ? [thread(), thread({id: 2, name: 'bootui-resources'})]
                : [thread()],
              hiddenBootUiThreads: String(url).includes('includeBootUi=true') ? 0 : 1
            })
          ),
          {status: 200}
        )
    )
    const wrapper = await mountWithReport(report(), {}, fetch)
    const toggle = wrapper.find('#thread-show-bootui')
    expect(toggle.element.checked).toBe(false)
    expect(wrapper.text()).toContain('1 BootUI thread hidden')
    expect(fetch.mock.calls[0][0]).toContain('includeBootUi=false')
    await toggle.setValue(true)
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(fetch.mock.lastCall[0]).toContain('includeBootUi=true')
    expect(wrapper.find('tbody').text()).toContain('bootui-resources')
  })

  beforeEach(() => {
    confirm.mockReset()
    confirm.mockResolvedValue(true)
  })

  afterEach(() => {
    wrappers.forEach((wrapper) => wrapper.unmount())
    wrappers = []
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows the live thread snapshot with a state summary', async () => {
    const wrapper = await mountWithReport(report())

    expect(wrapper.text()).toContain('Threads')
    expect(wrapper.text()).toContain('RUNNABLE: 1')
    expect(wrapper.find('code').text()).toBe('main')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(true)
    const header = wrapper.find('.panel-header__actions')
    expect(header.text()).toContain('Observe threads')
    expect(header.text()).toContain('Capture snapshot')
    expect(wrapper.find('#thread-investigation-tab-threads').attributes('aria-selected')).toBe('true')
    expect(wrapper.find('#thread-capture-selection').element.value).toBe('')
  })

  it('uses one source selector and one keyboard-accessible tablist without starting collection', async () => {
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            String(url).includes('threads/captures')
              ? {
                  available: true,
                  captures: [],
                  captureLimit: 6,
                  retainedBytes: 0,
                  retainedByteLimit: 16777216,
                  observationSamples: 3,
                  maxObservationSamples: 3,
                  observationIntervalMillis: 5000,
                  captureDeadlineMillis: 2000
                }
              : report()
          ),
          {status: 200}
        )
      )
    )
    const wrapper = await mountWithReport(report(), {}, fetchMock)
    const views = wrapper.find('[role="tablist"][aria-label="Thread views"]')
    expect(views.findAll('[role="tab"]').map((tab) => tab.text())).toEqual([
      'Threads',
      'Contention',
      'Observe & compare'
    ])
    const threads = views.find('#thread-investigation-tab-threads')
    const contention = views.find('#thread-investigation-tab-contention')
    expect(threads.attributes('aria-selected')).toBe('true')
    await threads.trigger('keydown', {key: 'ArrowRight'})
    await flushPromises()
    expect(contention.attributes('aria-selected')).toBe('true')
    expect(wrapper.find('#thread-investigation-panel-threads').element.style.display).toBe('none')
    expect(wrapper.find('#thread-investigation-panel-contention').element.style.display).not.toBe('none')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(false)
    expect(wrapper.findAll('[role="tablist"]')).toHaveLength(1)
    const headerActions = wrapper.find('.panel-header__actions').findAll('button')
    expect(headerActions.map((button) => button.text())).toEqual(['Observe threads', 'Capture snapshot'])
    expect(headerActions[0].classes()).toEqual(['btn', 'btn-outline-primary'])
    expect(headerActions[1].classes()).toEqual(['btn', 'btn-primary'])
    expect(fetchMock.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
    await wrapper.find('#thread-capture-selection').setValue('')
    await flushPromises()
    expect(threads.attributes('aria-selected')).toBe('true')
    expect(wrapper.find('#thread-investigation-panel-threads').element.style.display).not.toBe('none')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(wrapper.find('.panel-header__actions').text()).toContain('Capture snapshot')
  })

  it('expands and collapses the stack trace for a thread', async () => {
    const wrapper = await mountWithReport(report())

    expect(wrapper.text()).not.toContain('com.example.App.main(App.java:10)')
    const stackButton = wrapper.find('.threads-stack-toggle')
    expect(stackButton.text()).toContain('View stack')
    await stackButton.trigger('click')
    expect(wrapper.text()).toContain('com.example.App.main(App.java:10)')
    expect(stackButton.text()).toContain('Hide stack')
  })

  it('omits the stack toggle for threads with no stack frames', async () => {
    const wrapper = await mountWithReport(report({threads: [thread({stackTrace: []})]}))

    expect(wrapper.find('.threads-stack-toggle').exists()).toBe(false)
  })

  it('refreshes automatically while enabled', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(report()), {status: 200})))
    vi.stubGlobal('fetch', threadFetch(fetchMock))

    const wrapper = mount(Threads, {props: {panel: {}}})
    wrappers.push(wrapper)
    await flushPromises()

    expect(wrapper.text()).toContain('Auto-refresh')
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('pauses competing live acquisition while observing and resumes after passive status polling completes', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    let completed = false
    const fetchMock = vi.fn(async (url) => {
      if (String(url).includes('/observations'))
        return new Response(
          JSON.stringify({
            id: '11111111-1111-1111-1111-111111111111',
            status: completed ? 'COMPLETED' : 'RUNNING',
            requestedSamples: 2,
            completedSamples: completed ? 2 : 0,
            clearedSamples: 0,
            captureIds: [],
            threads: [],
            page: {total: 0, matched: 0, offset: 0, limit: 25, returned: 0, hasMore: false}
          }),
          {status: 200}
        )
      return new Response(JSON.stringify(report()), {status: 200})
    })
    const wrapper = await mountWithReport(report(), {}, fetchMock)
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    await wrapper.find('#thread-observation-samples').setValue(2)
    await wrapper.find('#thread-observation-interval').setValue(1)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Observe threads')
      .trigger('click')
    await flushPromises()
    await wrapper.find('#thread-investigation-tab-threads').trigger('click')
    expect(wrapper.text()).toContain('Previously acquired rows remain visible')
    expect(wrapper.find('tbody tr code').text()).toBe('main')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(false)
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(false)
    expect(downloadButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('[aria-label="Filter threads"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('#thread-show-bootui').attributes('disabled')).toBeDefined()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === 'Capture snapshot')
        .attributes('disabled')
    ).toBeDefined()
    await downloadButton(wrapper).trigger('click')
    await wrapper.find('[aria-label="Filter threads"]').setValue('main')
    await vi.advanceTimersByTimeAsync(11_000)
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).startsWith('api/threads?'))).toHaveLength(1)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/observations/'))).toBe(true)
    completed = true
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(downloadButton(wrapper).attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[aria-label="Filter threads"]').attributes('disabled')).toBeUndefined()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).startsWith('api/threads?'))).toHaveLength(2)
  })

  it('flags deadlocks prominently', async () => {
    const wrapper = await mountWithReport(report({deadlockDetected: true, deadlockedThreadIds: [1, 2]}))

    expect(wrapper.text()).toContain('Deadlock detected.')
    expect(wrapper.text()).toContain('1, 2')
  })

  it('preserves the prior live rows and capture time when the shared reader is busy', async () => {
    const snapshot = report()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(snapshot), {status: 200}))
      .mockResolvedValueOnce(new Response(JSON.stringify({error: 'Thread capture is already running.'}), {status: 409}))
    const wrapper = await mountWithReport(snapshot, {}, fetchMock)
    const header = wrapper.findComponent({name: 'PanelHeader'})
    expect(header.props('lastFetched')).toBe(snapshot.capturedAt)

    await wrapper.find('button[title="Refresh"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('tbody tr code').text()).toBe('main')
    expect(header.props('lastFetched')).toBe(snapshot.capturedAt)
    expect(header.props('error')).toBeTruthy()
  })

  it('shows the initial busy reason without inventing zero thread counts or an empty table', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          error: 'BootUI action already in progress',
          message: 'A thread capture is already running. Retry after it finishes.'
        }),
        {status: 409, headers: {'Content-Type': 'application/json'}}
      )
    )
    const wrapper = await mountWithReport(null, {}, fetchMock)
    expect(wrapper.text()).toContain('A thread capture is already running')
    expect(wrapper.text()).not.toContain('0 threads')
    expect(wrapper.text()).not.toContain('No threads match')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('preserves the server busy reason for a fresh text download', async () => {
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        String(url).includes('threads/download')
          ? new Response(
              JSON.stringify({
                error: 'BootUI action already in progress',
                message: 'A thread capture is already running.'
              }),
              {status: 409, headers: {'Content-Type': 'application/json'}}
            )
          : new Response(JSON.stringify(report()), {status: 200})
      )
    )
    const wrapper = await mountWithReport(report(), {}, fetchMock)
    await downloadButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('A thread capture is already running.')
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

    const button = downloadButton(wrapper)
    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('dump download are read-only')
  })

  it('waits for confirmation before posting to the download endpoint', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    const fetchMock = vi.fn((url) => {
      if (typeof url === 'string' && url.includes('download')) {
        return Promise.resolve(new Response('dump', {status: 200}))
      }
      return Promise.resolve(new Response(JSON.stringify(report()), {status: 200}))
    })
    vi.stubGlobal('fetch', threadFetch(fetchMock))
    URL.createObjectURL = vi.fn(() => 'blob:dump')
    URL.revokeObjectURL = vi.fn()
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    const wrapper = mount(Threads, {props: {panel: {}}})
    wrappers.push(wrapper)
    await flushPromises()

    await downloadButton(wrapper).trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith({
      title: 'Download thread dump?',
      message: 'Capture a fresh BootUI text thread dump from the running application and download it to this device.',
      resource: 'thread-dump.txt',
      confirmLabel: 'Download'
    })
    expect(
      fetchMock.mock.calls.some(([url, init]) => String(url).includes('threads/download') && init?.method === 'POST')
    ).toBe(false)

    confirmation.resolve(true)
    await flushPromises()

    expect(
      fetchMock.mock.calls.some(([url, init]) => String(url).includes('threads/download') && init?.method === 'POST')
    ).toBe(true)
    expect(anchorClick).toHaveBeenCalledOnce()
  })

  it('does not post a thread dump when confirmation is cancelled', async () => {
    confirm.mockResolvedValueOnce(false)
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(report()), {status: 200})))
    const wrapper = await mountWithReport(report(), {}, fetchMock)

    await downloadButton(wrapper).trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(
      fetchMock.mock.calls.some(([url, init]) => String(url).includes('threads/download') && init?.method === 'POST')
    ).toBe(false)
  })
})
