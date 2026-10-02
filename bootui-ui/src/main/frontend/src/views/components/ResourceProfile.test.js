import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

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

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
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
})
