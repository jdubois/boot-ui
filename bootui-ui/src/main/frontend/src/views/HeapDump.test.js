import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import HeapDump from './HeapDump.vue'
import MemoryOffloadButton from './components/MemoryOffloadButton.vue'
import PanelHeader from './components/PanelHeader.vue'

function heapReport(overrides = {}) {
  return {
    hotspotAvailable: true,
    captureEnabled: true,
    rawDownloadEnabled: false,
    outputDirectory: '/tmp/bootui',
    maxDumps: 5,
    dumpCount: 0,
    liveHeapUsedBytes: 1024,
    freeDiskBytes: 1024 * 1024,
    capture: {
      status: 'ANALYZED',
      message: 'Live heap analyzed without writing a dump',
      capturedAtEpochMs: 1780200000000
    },
    dumps: [],
    histogramTotalInstances: 10,
    histogramTotalBytes: 100,
    topClasses: [
      {
        rank: 1,
        className: 'java.lang.String',
        instances: 10,
        bytes: 100
      }
    ],
    ...overrides
  }
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('HeapDump', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('keeps the class prefix filter editable when it matches no histogram rows', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(heapReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(HeapDump)
    await flushPromises()

    fetchMock.mockResolvedValueOnce(jsonResponse(heapReport({topClasses: []})))
    await wrapper.get('input[type="text"]').setValue('com.missing')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith('api/heap-dump?filter=com.missing', expect.anything())
    expect(wrapper.text()).toContain('No classes match the current filter')
    expect(wrapper.get('input[type="text"]').attributes('disabled')).toBeUndefined()

    fetchMock.mockResolvedValueOnce(jsonResponse(heapReport()))
    await wrapper.get('input[type="text"]').setValue('')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith('api/heap-dump', expect.anything())
    expect(wrapper.text()).toContain('java.lang.String')
  })

  it('ignores stale filtered responses after the filter changes', async () => {
    let resolveFiltered
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(heapReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(HeapDump)
    await flushPromises()

    fetchMock.mockImplementationOnce(() => new Promise((resolve) => (resolveFiltered = resolve)))
    await wrapper.get('input[type="text"]').setValue('com.missing')
    await vi.advanceTimersByTimeAsync(250)

    fetchMock.mockResolvedValueOnce(jsonResponse(heapReport()))
    await wrapper.get('input[type="text"]').setValue('')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.text()).toContain('java.lang.String')

    resolveFiltered(jsonResponse(heapReport({topClasses: []})))
    await flushPromises()

    expect(wrapper.text()).toContain('java.lang.String')
    expect(wrapper.text()).not.toContain('No classes match the current filter')
  })

  it('retains the histogram and shows a warning when another heap action is active', async () => {
    const busy = {
      error: 'BootUI action already in progress',
      operation: 'heap-dump.analyze',
      activeOperation: 'heap-dump.capture',
      message: "Operation 'heap-dump.analyze' cannot start while 'heap-dump.capture' is in progress."
    }
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(heapReport()))
      .mockResolvedValueOnce(
        new Response(JSON.stringify(busy), {status: 409, headers: {'Content-Type': 'application/json'}})
      )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(HeapDump)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Analyze live heap'))
      .trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('java.lang.String')
    expect(wrapper.text()).toContain(busy.message)
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('Unable to run heap dump action')
  })

  it('offers the shared BootUI memory offload in the header and reloads the report after it ran', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(heapReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(HeapDump)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const offload = wrapper.getComponent(PanelHeader).getComponent(MemoryOffloadButton)
    expect(offload.props('followUp')).toContain('Analyze the live heap')
    offload.vm.$emit('offloaded', {reclaimedBytes: 0})
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[1][0])).toContain('api/heap-dump')
  })
})
