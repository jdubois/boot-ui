import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import ResourceProfile from './ResourceProfile.vue'

const idle = {
  state: 'IDLE',
  reason: null,
  sampler: null,
  startedAt: null,
  endsAt: null,
  finishedAt: null,
  maxDurationSeconds: 30,
  cpuSamples: 0,
  outsideSamples: 0,
  requests: 0,
  routes: [],
  routesOmitted: 0,
  limitations: []
}

const completed = {
  ...idle,
  state: 'COMPLETED',
  sampler: 'jdk.ExecutionSample',
  startedAt: 1_000,
  endsAt: 31_000,
  finishedAt: 20_000,
  cpuSamples: 400,
  outsideSamples: 100,
  requests: 12,
  routes: [
    {
      route: 'GET /api/report/{id}',
      requests: 10,
      cpuSamples: 270,
      allocatedBytes: 5 * 1024 * 1024,
      virtualThreads: true,
      hotFrames: [
        {frame: 'com.example.ReportService.render:42', samples: 200},
        {frame: 'com.example.ReportService.load:17', samples: 50}
      ]
    },
    {route: 'GET /api/health', requests: 2, cpuSamples: 30, allocatedBytes: 2048, virtualThreads: false, hotFrames: []}
  ],
  limitations: [
    'CPU is counted in samples of running threads every 10 ms (jdk.ExecutionSample), not as a measured time.'
  ]
}

const running = {...idle, state: 'RUNNING', startedAt: 1_000, endsAt: 31_000}
const routesHidden =
  "The http-exchanges panel is disabled, so the samples are not listed by route; the session's totals still count every sampled request."

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return {promise, resolve, reject}
}

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({'Content-Type': 'application/json'}),
    json: () => Promise.resolve(body)
  }
}

function respond(...bodies) {
  const queue = [...bodies]
  const fetchMock = vi.fn(() => {
    const body = queue.length > 1 ? queue.shift() : queue[0]
    return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(body)})
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('ResourceProfile', () => {
  let wrapper

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=resource-profile-test; path=/'
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  it('reads the state on mount without starting a session', async () => {
    const fetchMock = respond(idle)
    wrapper = mount(ResourceProfile)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1]?.method ?? 'GET').toBe('GET')
    expect(wrapper.text()).toContain('for 30 seconds')
    expect(wrapper.get('.insight-profile-start').attributes('disabled')).toBeUndefined()
  })

  it('shows a failed read as its message, never as an object', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Request failed with status 500')))
    wrapper = mount(ResourceProfile)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toBe('Unable to read the resource profile: Request failed with status 500')
    expect(alert.text()).not.toContain('{')
  })

  it('starts a session only when asked, counts it down, and offers to stop it', async () => {
    vi.useFakeTimers({now: 11_000})
    const fetchMock = respond(idle, {...idle, state: 'RUNNING', startedAt: 1_000, endsAt: 31_000})
    wrapper = mount(ResourceProfile)
    await flushPromises()

    await wrapper.get('.insight-profile-start').trigger('click')
    await flushPromises()

    const [url, init] = fetchMock.mock.calls.find(([, options]) => options?.method === 'POST')
    expect(String(url)).toContain('api/runtime-insights/resource-profile')
    expect(init.method).toBe('POST')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('33')
    expect(wrapper.text()).toContain('20 s left')
    expect(wrapper.find('.insight-profile-stop').exists()).toBe(true)
  })

  it('lists the routes by CPU samples with their bars, allocation, virtual threads, and hottest frames', async () => {
    respond(completed)
    wrapper = mount(ResourceProfile)
    await flushPromises()

    expect(wrapper.get('.insight-profile-summary').text()).toMatch(
      /^400 CPU samples, 75 % of them while a request ran, joined to 12 requests by the execution sampler · ended .+\.$/
    )
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].classes()).toContain('insight-profile-top')
    expect(rows[0].text()).toContain('virtual threads')
    expect(rows[0].text()).toContain('5.0 MB')
    expect(rows[0].text()).toContain('270 · 90 %')
    expect(rows[0].get('.insight-share-bar').attributes('style')).toContain('width: 90%')
    expect(rows[0].text()).toContain('ReportService.render:42')
    expect(rows[0].get('.insight-profile-frame code').attributes('title')).toBe('com.example.ReportService.render:42')
    expect(rows[0].get('details summary').text()).toBe('1 more')
    expect(rows[1].text()).toContain('No frame sampled')
    expect(wrapper.text()).toContain('not as a measured time')
    expect(wrapper.get('.insight-profile-start').text()).toBe('Profile again')
  })

  it('cannot start a session when the panel is read-only or the runtime has no JFR', async () => {
    respond({...idle, state: 'UNAVAILABLE', reason: 'JDK Flight Recorder is not available in this JVM.'})
    wrapper = mount(ResourceProfile)
    await flushPromises()
    expect(wrapper.get('.insight-profile-start').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('JDK Flight Recorder is not available in this JVM.')
    wrapper.unmount()

    respond(idle)
    wrapper = mount(ResourceProfile, {props: {readOnly: true, readOnlyReason: 'Read-only by configuration.'}})
    await flushPromises()
    expect(wrapper.get('.insight-profile-start').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Read-only by configuration.')
  })

  it.each(['response', 'error'])('does not let a pending poll %s replace an accepted stop result', async (outcome) => {
    vi.useFakeTimers({now: 11_000})
    const poll = deferred()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(running))
      .mockReturnValueOnce(poll.promise)
      .mockResolvedValueOnce(jsonResponse(completed))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)

    await wrapper.get('.insight-profile-stop').trigger('click')
    await flushPromises()
    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    if (outcome === 'response') poll.resolve(jsonResponse(running))
    else poll.reject(new Error('Stale poll failure'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(6000)

    expect(wrapper.find('.insight-profile-stop').exists()).toBe(false)
    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores an older read in the same action generation', async () => {
    const older = deferred()
    const newer = deferred()
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise))
    wrapper = mount(ResourceProfile)
    const nextRead = wrapper.vm.load()
    newer.resolve(jsonResponse(completed))
    await nextRead
    await flushPromises()
    older.resolve(jsonResponse(idle))
    await flushPromises()

    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
  })

  it.each(['initial', 'poll', 'action'])(
    'does not restart timers after unmount with a pending %s response',
    async (phase) => {
      vi.useFakeTimers({now: 11_000})
      const pending = deferred()
      const fetchMock = vi.fn()
      if (phase !== 'initial') fetchMock.mockResolvedValueOnce(jsonResponse(running))
      fetchMock.mockReturnValueOnce(pending.promise)
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(ResourceProfile)
      await flushPromises()
      if (phase === 'poll') await vi.advanceTimersByTimeAsync(2000)
      if (phase === 'action') {
        await wrapper.get('.insight-profile-stop').trigger('click')
        await flushPromises()
      }
      const calls = fetchMock.mock.calls.length
      wrapper.unmount()
      wrapper = null
      pending.resolve(jsonResponse(running))
      await flushPromises()
      await vi.advanceTimersByTimeAsync(6000)

      expect(fetchMock).toHaveBeenCalledTimes(calls)
      expect(vi.getTimerCount()).toBe(0)
    }
  )

  it('does not reconcile or create timers after an action fails following unmount', async () => {
    vi.useFakeTimers({now: 11_000})
    const pending = deferred()
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(running)).mockReturnValueOnce(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await wrapper.get('.insight-profile-stop').trigger('click')
    await flushPromises()
    wrapper.unmount()
    wrapper = null
    pending.reject(new TypeError('Failed to fetch'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(6000)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('admits one mutation while an action is pending', async () => {
    const pending = deferred()
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(idle)).mockReturnValueOnce(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    const first = wrapper.vm.start()
    const second = wrapper.vm.start()
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    pending.resolve(jsonResponse(running))
    await Promise.all([first, second])
  })

  it.each([
    ['lost response', () => Promise.reject(new TypeError('Failed to fetch'))],
    [
      'malformed JSON',
      () => Promise.resolve({ok: true, status: 200, json: () => Promise.reject(new SyntaxError('Invalid JSON'))})
    ],
    ['error body', () => Promise.resolve(jsonResponse({error: 'Recorder reply unavailable'}))],
    ['unrecognized state', () => Promise.resolve(jsonResponse({...idle, state: 'SURPRISE'}))],
    ['incomplete route', () => Promise.resolve(jsonResponse({...completed, routes: [{route: 'GET /incomplete'}]}))]
  ])('keeps accepted results and reconciles once after a %s', async (_, actionResponse) => {
    const fresh = deferred()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(completed))
      .mockImplementationOnce(actionResponse)
      .mockReturnValueOnce(fresh.promise)
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await wrapper.get('.insight-profile-start').trigger('click')
    await flushPromises()

    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    expect(wrapper.get('[role="alert"]').text()).toMatch(/outcome is unknown/i)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    fresh.resolve(jsonResponse(idle))
    await flushPromises()
    expect(wrapper.get('.insight-profile-start').text()).toBe('Profile resources')
    expect(wrapper.get('[role="alert"]').text()).toMatch(/outcome is unknown/i)
  })

  it('keeps accepted data if unknown-outcome reconciliation fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(completed))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockRejectedValueOnce(new Error('Reconciliation unavailable'))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await wrapper.get('.insight-profile-start').trigger('click')
    await flushPromises()

    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    expect(wrapper.get('[role="alert"]').text()).toMatch(/outcome is unknown/i)
    expect(wrapper.get('[role="alert"]').text()).toContain('Reconciliation unavailable')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('reconciles an uncertain server failure without retrying the mutation', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(completed))
      .mockResolvedValueOnce(jsonResponse({error: 'Recorder response failed'}, 500))
      .mockResolvedValueOnce(jsonResponse(idle))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await wrapper.get('.insight-profile-start').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('Recorder response failed')
    expect(wrapper.get('[role="alert"]').text()).toMatch(/outcome is unknown/i)
    expect(wrapper.get('.insight-profile-start').text()).toBe('Profile resources')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('preserves accepted results when a read returns a malformed report', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(completed))
      .mockResolvedValueOnce(jsonResponse({error: 'Not a profile report'}))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await wrapper.vm.load()
    await flushPromises()

    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    expect(wrapper.get('[role="alert"]').text()).toContain('Unable to read the resource profile')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('shows the canonical policy refusal without reconciling or discarding accepted data', async () => {
    const reason = "Panel 'runtime-insights' is read-only (bootui.panels.runtime-insights.read-only=true)"
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(completed))
      .mockResolvedValueOnce(
        jsonResponse({error: 'BootUI panel access denied', panel: 'runtime-insights', reason}, 403)
      )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(ResourceProfile)
    await flushPromises()
    await wrapper.get('.insight-profile-start').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(reason)
    expect(wrapper.get('[role="alert"]').text()).not.toMatch(/unknown/i)
    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each(['IDLE', 'RUNNING', 'COMPLETED', 'UNAVAILABLE', 'FAILED'])('accepts a native %s report', async (state) => {
    respond({...idle, state, reason: state === 'UNAVAILABLE' || state === 'FAILED' ? 'JFR is unavailable.' : null})
    wrapper = mount(ResourceProfile)
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(state === 'FAILED')
    if (state === 'RUNNING') expect(wrapper.find('.insight-profile-stop').exists()).toBe(true)
  })

  it('keeps positive request totals when route samples are withheld by policy', async () => {
    respond({...completed, routes: [], limitations: [routesHidden]})
    wrapper = mount(ResourceProfile)
    await flushPromises()

    expect(wrapper.get('.insight-profile-summary').text()).toContain('12 requests')
    expect(wrapper.text()).toContain(routesHidden)
    expect(wrapper.text()).not.toContain('No request ran')
    expect(wrapper.text()).not.toContain('No request samples were attributed')
  })

  it('describes an empty attribution as missing samples rather than no requests', async () => {
    respond({...completed, requests: 0, routes: []})
    wrapper = mount(ResourceProfile)
    await flushPromises()

    expect(wrapper.text()).toContain('No request samples were attributed')
    expect(wrapper.text()).not.toContain('No request ran')
  })

  it('does not infer a policy refusal from an empty route list and keeps omitted-route evidence', async () => {
    respond({...completed, routes: [], routesOmitted: 2})
    wrapper = mount(ResourceProfile)
    await flushPromises()

    expect(wrapper.text()).toContain('No request samples were attributed')
    expect(wrapper.text()).toContain('2 more routes not listed')
    expect(wrapper.text()).not.toContain('http-exchanges panel is disabled')
    expect(wrapper.text()).not.toContain('No request ran')
  })
})
