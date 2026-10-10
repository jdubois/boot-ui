import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RuntimeResources from './RuntimeResources.vue'

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function point(overrides = {}) {
  return {
    epochMillis: 1700000000000,
    sequence: 10,
    intervalNanos: 1_000_000_000,
    processCpuNanos: 400_000_000,
    requestCpuNanos: 100_000_000,
    internalCpuNanos: 200_000_000,
    familyCpuNanos: [80_000_000, 20_000_000],
    unreadThreads: 0,
    heapUsedBytes: 64 * 1024 * 1024,
    heapCommittedBytes: 128 * 1024 * 1024,
    heapAfterGcBytes: 32 * 1024 * 1024,
    allocatedBytes: 1024,
    liveThreads: 40,
    daemonThreads: 30,
    ...overrides
  }
}

describe('RuntimeResources', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('splits the run CPU time into requests, thread families, BootUI, and JVM internals', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(
        jsonResponse({
          available: true,
          unavailableReason: null,
          families: ['pool-N-thread-N', 'BootUI'],
          points: [point(), point({epochMillis: 1700000001000, heapUsedBytes: 80 * 1024 * 1024})],
          totals: {
            sweeps: 2,
            processCpuNanos: 1_000_000_000,
            requestCpuNanos: 250_000_000,
            internalCpuNanos: 500_000_000,
            familyCpuNanos: {'pool-N-thread-N': 200_000_000, BootUI: 50_000_000}
          }
        })
      )
    )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(RuntimeResources)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/activity/resources', expect.anything())
    expect(wrapper.text()).toContain("75% of this run's 1.00 s of CPU time was not attributed to requests")
    expect(wrapper.text()).toContain('it is not proof that this work ran outside requests')
    const rows = wrapper.findAll('tbody tr').map((row) => row.text())
    expect(rows[0]).toContain('Unclassified (JVM and unobserved threads)')
    expect(rows[0]).toContain('50%')
    expect(rows.some((row) => row.includes('Requests') && row.includes('25%'))).toBe(true)
    expect(rows.some((row) => row.includes('pool-N-thread-N') && row.includes('20%'))).toBe(true)
    expect(rows.some((row) => row.includes('BootUI itself') && row.includes('5.0%'))).toBe(true)
    expect(wrapper.text()).toContain('80.0 MB of 128.0 MB committed')
    expect(wrapper.text()).toContain('peak 40% of one core')
    expect(wrapper.findAll('polyline')).toHaveLength(2)
  })

  it('says why the sampler does not run', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            available: false,
            unavailableReason: 'The runtime journal does not record the resources source.',
            families: [],
            points: [],
            totals: {sweeps: 0, processCpuNanos: 0, requestCpuNanos: 0, internalCpuNanos: 0, familyCpuNanos: {}}
          })
        )
      )
    )

    const wrapper = mount(RuntimeResources)
    await flushPromises()

    expect(wrapper.text()).toContain('does not record the resources source')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it.each([
    {
      reason: 'thread counters exceed the independently measured process counter',
      processCpuNanos: 20_000_000,
      requestCpuNanos: 10_000_000,
      familyCpuNanos: {'pool-N-thread-N': 30_000_000}
    },
    {
      reason: 'only some process intervals were measured',
      processCpuNanos: 20_000_000,
      requestCpuNanos: 30_000_000,
      familyCpuNanos: {'pool-N-thread-N': 20_000_000}
    },
    {
      reason: 'no process interval was measured but thread CPU is known',
      processCpuNanos: 0,
      requestCpuNanos: 10_000_000,
      familyCpuNanos: {'pool-N-thread-N': 30_000_000}
    }
  ])('qualifies incomplete attribution when $reason', async ({processCpuNanos, requestCpuNanos, familyCpuNanos}) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            available: true,
            unavailableReason: null,
            families: ['pool-N-thread-N'],
            points: [point(), point({epochMillis: 1700000001000, internalCpuNanos: -1})],
            totals: {sweeps: 2, internalCpuNanos: -1, processCpuNanos, requestCpuNanos, familyCpuNanos}
          })
        )
      )
    )

    const wrapper = mount(RuntimeResources)
    await flushPromises()

    expect(wrapper.text()).toContain('CPU attribution is incomplete')
    expect(wrapper.text()).toContain('Measured times are shown without percentage shares')
    expect(wrapper.text()).not.toContain('went to work outside requests')
    expect(wrapper.text()).not.toContain('No CPU time measured yet')
    expect(wrapper.find('.runtime-resources__stack').exists()).toBe(false)
    const table = wrapper.find('table')
    expect(table.exists()).toBe(true)
    expect(table.text()).toContain('Requests')
    expect(table.text()).toContain('pool-N-thread-N')
    expect(table.text()).not.toContain('Share')
    expect(table.text()).not.toContain('%')
    expect(table.text()).toContain('Unknown')
    expect(wrapper.findAll('polyline')).toHaveLength(2)
  })
})
