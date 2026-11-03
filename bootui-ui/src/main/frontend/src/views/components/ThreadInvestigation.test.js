import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

const {confirm} = vi.hoisted(() => ({confirm: vi.fn()}))
vi.mock('../../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))
import ThreadInvestigation from './ThreadInvestigation.vue'

const id = '11111111-1111-1111-1111-111111111111'
const otherId = '22222222-2222-2222-2222-222222222222'
const page = {total: 1, matched: 1, offset: 0, limit: 25, returned: 1, hasMore: false}
const capture = {
  id,
  capturedAt: 1700000000000,
  expiresAt: 1700000300000,
  durationMillis: 2,
  quality: {enumeratedThreads: 1, capturedThreads: 1, limitations: []},
  deadlockDetected: false,
  deadlockedThreadIds: []
}
const report = {
  capture,
  rootBlockers: [],
  cycles: [],
  unresolvedWaits: [],
  stackGroups: [],
  blockerPage: page,
  cyclePage: page,
  unresolvedPage: page,
  groupPage: page
}
const row = {
  observationId: `${id}:1`,
  family: 'worker',
  activity: 'RUNNABLE',
  ownedLocks: [],
  ownershipComplete: false,
  stackTruncated: false,
  thread: {id: 1, name: 'worker-1', state: 'RUNNABLE', stackTrace: ['app.Work.run(Work.java:1)'], cpuTimeMillis: 20}
}
let wrappers = []

function response(body, status = 200) {
  return new Response(JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}})
}

async function setup({
  captures = [],
  lowFootprint = false,
  observationId = null,
  observationSamples = 3,
  maxObservationSamples = 3,
  observationIntervalMillis = 5000,
  captureLimit = 6,
  captureDeadlineMillis = 2000,
  selectCapture = true,
  handler,
  props = {}
} = {}) {
  const fetch = vi.fn(async (url, init) => {
    const custom = handler?.(String(url), init)
    if (custom !== undefined) return custom
    if (String(url).endsWith('/captures'))
      return response({
        available: true,
        lowFootprint,
        captureLimit,
        retainedByteLimit: 16777216,
        retainedBytes: 100,
        observationSamples,
        maxObservationSamples,
        observationIntervalMillis,
        observationId,
        captureDeadlineMillis,
        captures,
        page
      })
    if (String(url).includes('/threads?')) return response({captureId: id, threads: [row], page})
    if (/\/captures\/[^/]+\/threads\/[^?]+$/.test(String(url))) return response(row)
    return response(report)
  })
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(ThreadInvestigation, {props})
  wrappers.push(wrapper)
  await flushPromises()
  if (selectCapture && captures.length) {
    await wrapper.find('#thread-capture-selection').setValue(captures[0].id)
    await flushPromises()
  }
  return {wrapper, fetch}
}

function button(wrapper, label) {
  return wrapper.findAll('button').find((item) => item.text() === label)
}

describe('ThreadInvestigation', () => {
  beforeEach(() => {
    confirm.mockReset()
    confirm.mockResolvedValue(true)
  })
  afterEach(() => {
    wrappers.forEach((item) => item.unmount())
    wrappers = []
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('only reads retained metadata on opening and never starts work on tab selection', async () => {
    const {wrapper, fetch} = await setup()
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('api/threads/captures')
    expect(wrapper.text()).toContain('Opening this tab never starts collection')
    expect(confirm).not.toHaveBeenCalled()
  })

  it('makes observation settings contextual without treating a manual capture as a comparison', async () => {
    const {wrapper, fetch} = await setup({captures: [capture]})
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    expect(wrapper.find('#thread-investigation-tab-compare').text()).toBe('Observe & compare')
    expect(wrapper.find('#thread-capture-selection').exists()).toBe(false)
    expect(wrapper.find('#thread-show-bootui').exists()).toBe(false)
    expect(wrapper.find('#thread-observation-samples').element.value).toBe('3')
    expect(wrapper.find('#thread-observation-interval').element.value).toBe('5')
    expect(wrapper.text()).toContain('Single captures belong to Threads and Contention')
    expect(wrapper.findAll('button').filter((item) => item.text() === 'Observe threads')).toHaveLength(1)
    await wrapper.find('#thread-investigation-tab-threads').trigger('click')
    expect(wrapper.find('#thread-capture-selection').element.value).toBe(id)
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('applies the shared BootUI switch to retained paging without changing contention evidence', async () => {
    const {wrapper, fetch} = await setup({
      captures: [capture],
      handler: (url) =>
        url.includes('/threads?')
          ? response({
              captureId: id,
              threads: [row],
              page,
              hiddenBootUiThreads: url.includes('includeBootUi=false') ? 4 : 0
            })
          : undefined
    })
    expect(wrapper.text()).toContain('4 BootUI threads hidden')
    expect(fetch.mock.calls.find(([url]) => url.includes('/threads?'))[0]).toContain('includeBootUi=false')
    await wrapper.find('#thread-show-bootui').setValue(true)
    expect(wrapper.emitted('update:showBootUi').at(-1)).toEqual([true])
    await wrapper.setProps({showBootUi: true})
    await flushPromises()
    expect(fetch.mock.calls.filter(([url]) => url.includes('/threads?')).at(-1)[0]).toContain(
      'offset=0&limit=25&includeBootUi=true'
    )
    await wrapper.find('#thread-investigation-tab-contention').trigger('click')
    expect(wrapper.find('#thread-show-bootui').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Contention always includes all threads')
    expect(fetch.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
  })

  it('ignores a restored observation requested with an obsolete visibility setting', async () => {
    let finishHidden
    let finishIncluded
    const restored = {
      id,
      status: 'COMPLETED',
      requestedSamples: 2,
      completedSamples: 2,
      captureIds: [],
      threads: [],
      page,
      hiddenBootUiThreads: 1
    }
    const {wrapper} = await setup({
      observationId: id,
      handler: (url) => {
        if (url.includes('/observations/'))
          return new Promise((resolve) => {
            if (url.includes('includeBootUi=true')) finishIncluded = resolve
            else finishHidden = resolve
          })
      }
    })
    await wrapper.setProps({showBootUi: true})
    await flushPromises()
    finishHidden(response(restored))
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('COMPLETED')
    finishIncluded(response({...restored, hiddenBootUiThreads: 0}))
    await flushPromises()
    expect(wrapper.text()).toContain('COMPLETED')
    expect(wrapper.text()).toContain('BootUI threads included')
  })

  it('does not reopen pending thread evidence after the visibility switch changes', async () => {
    let finish
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const {wrapper} = await setup({
      captures: [capture],
      handler: (url) => (url.endsWith(`/threads/${encodeURIComponent(row.observationId)}`) ? pending : undefined)
    })
    await button(wrapper, 'worker-1').trigger('click')
    await wrapper.setProps({showBootUi: true})
    finish(response(row))
    await flushPromises()
    expect(wrapper.find('[aria-label="Thread evidence"]').exists()).toBe(false)
  })

  it.each([false, true])(
    'inspects comparison evidence without changing the existing source (retained selected: %s)',
    async (selectCapture) => {
      const waiterId = `${otherId}:2`
      const ownerId = `${otherId}:1`
      const waiter = {
        ...row,
        observationId: waiterId,
        thread: {...row.thread, id: 2, name: 'blocked-worker', lockOwnerId: 1, lockName: 'example.Lock@123'}
      }
      const {wrapper, fetch} = await setup({
        selectCapture,
        captures: [capture, {...capture, id: otherId}],
        observationId: otherId,
        handler: (url) => {
          if (url.includes('/observations/'))
            return response({
              id: otherId,
              status: 'COMPLETED',
              requestedSamples: 2,
              completedSamples: 2,
              captureIds: [otherId],
              threads: [{observationId: waiterId, name: 'blocked-worker'}],
              page
            })
          if (url.includes(`/threads/${encodeURIComponent(waiterId)}`)) return response(waiter)
          if (url.includes(`/threads/${encodeURIComponent(ownerId)}`)) return response({...row, observationId: ownerId})
          if (url.includes(`/locks/${encodeURIComponent(waiterId)}`))
            return response({limitations: [], waiters: [{waiterId}], page})
        }
      })
      await wrapper.find('#thread-investigation-tab-compare').trigger('click')
      await button(wrapper, 'blocked-worker').trigger('click')
      await flushPromises()
      expect(wrapper.find('[aria-label="Thread evidence"]').text()).toContain('blocked-worker')
      await button(wrapper, 'example.Lock@123').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('Captured lock evidence')
      expect(fetch.mock.calls.some(([url]) => String(url).includes(`/captures/${otherId}/locks/`))).toBe(true)
      await button(wrapper, 'Inspect owner #1').trigger('click')
      await flushPromises()
      expect(wrapper.find('[aria-label="Thread evidence"]').text()).toContain('worker-1')
      expect(fetch.mock.calls.some(([url]) => String(url).includes(`/captures/${otherId}/threads?`))).toBe(false)
      await wrapper.find('#thread-investigation-tab-threads').trigger('click')
      expect(wrapper.find('#thread-capture-selection').element.value).toBe(selectCapture ? id : '')
      expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    }
  )

  it('discards pending thread details when the selected source changes', async () => {
    let finish
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const {wrapper} = await setup({
      captures: [capture],
      handler: (url) => (url.endsWith(`/threads/${encodeURIComponent(row.observationId)}`) ? pending : undefined)
    })
    await button(wrapper, 'worker-1').trigger('click')
    await wrapper.find('#thread-capture-selection').setValue('')
    finish(response(row))
    await flushPromises()
    expect(wrapper.find('[aria-label="Thread evidence"]').exists()).toBe(false)
  })

  it('keeps only passive observation status polling active across tabs without acquiring or loading capture contents', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    const {wrapper, fetch} = await setup({
      selectCapture: false,
      captures: [capture],
      observationId: id,
      handler: (url) => {
        if (url.includes('/observations/'))
          return response({
            id,
            status: 'RUNNING',
            requestedSamples: 3,
            completedSamples: 1,
            captureIds: [],
            threads: [],
            page
          })
      }
    })
    expect(button(wrapper, 'Capture snapshot').attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('observation-active').at(-1)).toEqual([true])
    const initialCalls = fetch.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetch).toHaveBeenCalledTimes(initialCalls + 5)
    expect(fetch.mock.calls.slice(initialCalls).every(([url]) => String(url).includes('/observations/'))).toBe(true)
    expect(fetch.mock.calls.some(([url]) => String(url).includes(`/captures/${id}`))).toBe(false)
    await wrapper.find('#thread-capture-selection').setValue(id)
    await flushPromises()
    expect(fetch.mock.calls.some(([url]) => String(url).includes(`/captures/${id}`))).toBe(true)
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    await flushPromises()
    const compareCalls = fetch.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetch.mock.calls.length).toBeGreaterThan(compareCalls)
    await wrapper.find('#thread-investigation-tab-threads').trigger('click')
    await wrapper.find('#thread-capture-selection').setValue('')
    await flushPromises()
    const liveCalls = fetch.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetch).toHaveBeenCalledTimes(liveCalls + 5)
    expect(fetch.mock.calls.slice(liveCalls).every(([url]) => String(url).includes('/observations/'))).toBe(true)
    expect(wrapper.find('#thread-investigation-tab-threads').attributes('aria-selected')).toBe('true')
  })

  it('drops displayed capture evidence when low-footprint mode clears the store', async () => {
    const captures = [capture]
    const {wrapper} = await setup({captures})
    await button(wrapper, 'worker-1').trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="Thread evidence"]').exists()).toBe(true)
    captures.splice(0)
    await wrapper.vm.reload({discard: true})
    await flushPromises()
    expect(wrapper.find('#thread-capture-selection').element.value).toBe('')
    expect(wrapper.find('[aria-label="Thread evidence"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('worker-1')
    expect(button(wrapper, 'Export capture').attributes('disabled')).toBeDefined()
  })

  it('keeps acquisition disabled until settings load and exposes an explicit retry after failure', async () => {
    let failed = true
    const {wrapper} = await setup({
      handler: (url) => {
        if (failed && url.endsWith('/captures')) return response({error: 'Capture settings unavailable.'}, 503)
      }
    })
    expect(button(wrapper, 'Capture snapshot').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="alert"]').text()).toContain('Capture settings unavailable')
    failed = false
    await button(wrapper, 'Retry loading captures').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(button(wrapper, 'Capture snapshot').attributes('disabled')).toBeUndefined()
  })

  it('uses roving tab focus and uniquely associated panels', async () => {
    const {wrapper} = await setup()
    const first = wrapper.find('#thread-investigation-tab-threads')
    await first.trigger('keydown', {key: 'ArrowRight'})
    expect(wrapper.find('#thread-investigation-tab-contention').attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('[role="tab"][tabindex="0"]')).toHaveLength(1)
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(3)
    for (const tab of wrapper.findAll('[role="tab"]')) {
      expect(wrapper.find(`#${tab.attributes('aria-controls')}`).attributes('aria-labelledby')).toBe(
        tab.attributes('id')
      )
    }
  })

  it.each(['RUNNING', 'COMPLETED'])(
    'passively restores a %s observation when the workbench reopens',
    async (status) => {
      const {wrapper, fetch} = await setup({
        observationId: id,
        handler: (url) => {
          if (url.includes('/observations/'))
            return response({
              id,
              status,
              requestedSamples: 3,
              completedSamples: status === 'COMPLETED' ? 3 : 1,
              clearedSamples: 0,
              captureIds: [],
              threads: [],
              page
            })
        }
      })
      await wrapper.find('#thread-investigation-tab-compare').trigger('click')
      expect(wrapper.find('[role="status"]').text()).toContain(status)
      expect(Boolean(button(wrapper, 'Cancel observation'))).toBe(status === 'RUNNING')
      expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    }
  )

  it('initializes next settings from configured defaults when restoring a session and preserves subsequent edits', async () => {
    const restored = {
      id,
      status: 'COMPLETED',
      requestedSamples: 2,
      completedSamples: 2,
      clearedSamples: 0,
      captureIds: [],
      threads: [],
      page
    }
    const {wrapper, fetch} = await setup({
      observationId: id,
      observationSamples: 2,
      observationIntervalMillis: 7000,
      captureLimit: 2,
      handler: (url) => {
        if (url.includes('/observations')) return response(restored)
      }
    })
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    expect(wrapper.find('#thread-observation-samples').element.value).toBe('2')
    expect(wrapper.find('#thread-observation-interval').element.value).toBe('7')
    await button(wrapper, 'Observe threads').trigger('click')
    await flushPromises()
    expect(JSON.parse(fetch.mock.calls.find(([, init]) => init?.method === 'POST')[1].body)).toEqual({
      samples: 2,
      intervalMillis: 7000
    })
    await wrapper.find('#thread-observation-interval').setValue('1.001')
    await wrapper.vm.reload()
    await flushPromises()
    expect(wrapper.find('#thread-observation-interval').element.value).toBe('1.001')
  })

  it('requires confirmation before collecting and shows one stable capture', async () => {
    let approve
    confirm.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          approve = resolve
        })
    )
    const {wrapper, fetch} = await setup({
      handler: (url, init) => {
        if (url.endsWith('/captures') && init?.method === 'POST') return response(capture)
      }
    })
    await button(wrapper, 'Capture snapshot').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    approve(true)
    await flushPromises()
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    expect(wrapper.find('#thread-capture-selection').element.value).toBe(id)
    expect(wrapper.find('#thread-investigation-tab-contention').attributes('aria-selected')).toBe('true')
  })

  it('allows explicit work in low-footprint mode but preserves read-only policy', async () => {
    const held = await setup({lowFootprint: true})
    expect(button(held.wrapper, 'Capture snapshot').attributes('disabled')).toBeUndefined()
    expect(button(held.wrapper, 'Observe threads').attributes('disabled')).toBeUndefined()
    expect(held.wrapper.text()).toContain('you can still capture a snapshot')
    const readonly = await setup({props: {readOnly: true, readOnlyReason: 'Restricted'}})
    expect(button(readonly.wrapper, 'Capture snapshot').attributes('disabled')).toBeDefined()
    expect(button(readonly.wrapper, 'Observe threads').attributes('disabled')).toBeDefined()
    expect(readonly.wrapper.text()).toContain('Restricted')
  })

  it('reads and focuses thread detail without fresh acquisition', async () => {
    const {wrapper, fetch} = await setup({captures: [capture]})
    await button(wrapper, 'worker-1').trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="Thread evidence"]').text()).toContain('app.Work.run(Work.java:1)')
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('keeps an expired explicit id and reports it rather than falling back or recapturing', async () => {
    const {wrapper, fetch} = await setup({
      captures: [capture],
      handler: (url) => {
        if (url.includes(`/captures/${id}`)) return response({error: 'Capture expired or was cleared.'}, 409)
      }
    })
    expect(wrapper.find('[role="alert"]').text()).toContain('Capture expired or was cleared')
    expect(wrapper.find('#thread-capture-selection').element.value).toBe(id)
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('does not publish an older selection over a newly selected capture', async () => {
    let finish
    const delayed = new Promise((resolve) => {
      finish = resolve
    })
    const {wrapper} = await setup({
      captures: [capture, {...capture, id: otherId}],
      handler: (url) => {
        if (url.includes(`/captures/${id}?`)) return delayed
        if (url.includes(`/captures/${otherId}?`))
          return response({...report, capture: {...capture, id: otherId, durationMillis: 999}})
      }
    })
    await wrapper.find('#thread-capture-selection').setValue(otherId)
    await flushPromises()
    finish(response(report))
    await flushPromises()
    expect(wrapper.text()).toContain('999 ms collection')
    expect(wrapper.find('#thread-capture-selection').element.value).toBe(otherId)
  })

  it('returns to live while a retained capture is loading and ignores its late response', async () => {
    let finish
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const {wrapper} = await setup({
      captures: [capture],
      handler: (url) => {
        if (url.includes(`/captures/${id}?`)) return pending
      }
    })
    await wrapper.find('#thread-capture-selection').setValue('')
    await flushPromises()
    expect(wrapper.findComponent({name: 'PanelSkeleton'}).exists()).toBe(false)
    finish(response(report))
    await flushPromises()
    expect(wrapper.find('#thread-capture-selection').element.value).toBe('')
    expect(wrapper.text()).toContain('Live thread snapshots appear here')
    expect(wrapper.text()).not.toContain('worker-1')
    expect(wrapper.emitted('live-change').at(-1)).toEqual([true])
  })

  it('rejects invalid local observation settings before confirmation or POST', async () => {
    const {wrapper, fetch} = await setup()
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    await wrapper.find('input[type="number"]').setValue(8)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="alert"]').text()).toContain('Choose 2–3 samples')
    await button(wrapper, 'Observe threads').trigger('click')
    expect(confirm).not.toHaveBeenCalled()
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('uses the server effective sample ceiling instead of advertising impossible reservations', async () => {
    const {wrapper, fetch} = await setup()
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    const samples = wrapper.find('#thread-observation-samples')
    expect(samples.attributes('max')).toBe('3')
    expect(wrapper.text()).toContain('2–3 samples')
    await samples.setValue(4)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Observe threads').trigger('click')
    expect(confirm).not.toHaveBeenCalled()
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('permits five samples when the server has sufficient reservation capacity', async () => {
    const {wrapper} = await setup({maxObservationSamples: 5})
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    const samples = wrapper.find('#thread-observation-samples')
    expect(samples.attributes('max')).toBe('5')
    await samples.setValue(5)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('reserves the configured final-capture time in the observation window', async () => {
    const {wrapper} = await setup({captureDeadlineMillis: 5000})
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    const inputs = wrapper.findAll('input[type="number"]')
    await inputs[0].setValue(2)
    await inputs[1].setValue(30)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeDefined()
    await inputs[1].setValue(25)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeUndefined()
    await inputs[1].setValue(25.001)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeDefined()
    await inputs[1].setValue('')
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeDefined()
    await inputs[1].setValue(1.001)
    expect(button(wrapper, 'Observe threads').attributes('disabled')).toBeUndefined()
  })

  it('submits a finite session only after confirmation and stops polling after unmount', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    const observation = {
      id,
      status: 'RUNNING',
      requestedSamples: 3,
      completedSamples: 0,
      clearedSamples: 0,
      captureIds: [],
      threads: [],
      page
    }
    const {wrapper, fetch} = await setup({
      handler: (url) => {
        if (url.includes('/observations')) return response(observation)
      }
    })
    await button(wrapper, 'Observe threads').trigger('click')
    await flushPromises()
    const post = fetch.mock.calls.find(([url, init]) => url.includes('/observations?') && init?.method === 'POST')
    expect(JSON.parse(post[1].body)).toEqual({samples: 3, intervalMillis: 5000})
    expect(wrapper.find('#thread-investigation-tab-compare').attributes('aria-selected')).toBe('true')
    expect(wrapper.text()).toContain('Live refresh, snapshots, and downloads are paused here while observing')
    await vi.advanceTimersByTimeAsync(1000)
    const beforeUnmount = fetch.mock.calls.length
    wrapper.unmount()
    wrappers = []
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetch.mock.calls.length).toBe(beforeUnmount)
  })

  it('converts seconds to integer milliseconds without changing the observation contract', async () => {
    const {wrapper, fetch} = await setup({
      handler: (url) =>
        url.includes('/observations')
          ? response({
              id,
              status: 'COMPLETED',
              requestedSamples: 2,
              completedSamples: 2,
              clearedSamples: 0,
              captureIds: [],
              threads: [],
              page
            })
          : undefined
    })
    await wrapper.find('#thread-investigation-tab-compare').trigger('click')
    await wrapper.find('#thread-observation-samples').setValue(2)
    await wrapper.find('#thread-observation-interval').setValue(1.001)
    await button(wrapper, 'Observe threads').trigger('click')
    await flushPromises()
    const post = fetch.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(post[1].body)).toEqual({samples: 2, intervalMillis: 1001})
    expect(wrapper.text()).toContain('Next observation settings')
    expect(wrapper.find('#thread-capture-selection').exists()).toBe(false)
  })
})
