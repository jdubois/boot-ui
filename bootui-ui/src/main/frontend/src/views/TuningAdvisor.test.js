import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import TuningAdvisor from './TuningAdvisor.vue'

const MB = 1024 * 1024

function memoryReport() {
  return {
    heap: {name: 'Heap', usedBytes: 128 * MB, committedBytes: 256 * MB, maxBytes: 512 * MB, usedPercent: 25},
    nonHeap: {name: 'Non-Heap', usedBytes: 64 * MB, committedBytes: 128 * MB, maxBytes: -1, usedPercent: 50},
    pools: [{name: 'G1 Eden Space', usedBytes: 32 * MB, committedBytes: 64 * MB, maxBytes: 128 * MB, usedPercent: 25}],
    jvmInputArguments: [],
    suggestedJvmOptions: '-Xms512m -Xmx512m -XX:+UseG1GC',
    calculation: {
      totalMemoryBytes: 1024 * MB,
      heapBytes: 512 * MB,
      metaspaceBytes: 64 * MB,
      codeCacheBytes: 240 * MB,
      directMemoryBytes: 10 * MB,
      stackBytesPerThread: MB,
      stackBytesTotal: 250 * MB,
      headRoomBytes: 102 * MB,
      fixedRegionsBytes: 564 * MB,
      threadCount: 250,
      loadedClasses: 5000,
      liveThreadCount: 40,
      liveLoadedClassCount: 5000,
      headRoomPercent: 10,
      jvmOptions: '-Xms512m -Xmx512m -XX:+UseG1GC',
      valid: true,
      error: null
    },
    kubernetes: {
      requestMemoryBytes: 1024 * MB,
      limitMemoryBytes: 1024 * MB,
      burstableRequestMemoryBytes: 512 * MB,
      currentSnapshotBytes: 432 * MB,
      detectedContainerLimitBytes: 1024 * MB,
      requestMemory: '1024Mi',
      limitMemory: '1024Mi',
      burstableRequestMemory: '512Mi',
      currentSnapshotMemory: '432Mi',
      detectedContainerLimitMemory: '1024Mi',
      qosClass: 'Guaranteed',
      confidence: 'High',
      warnings: ['Request equals limit for Kubernetes Guaranteed QoS.'],
      yaml:
        'resources:\n' +
        '  requests:\n' +
        '    memory: "1024Mi"\n' +
        '  limits:\n' +
        '    memory: "1024Mi"\n' +
        'env:\n' +
        '  - name: JAVA_TOOL_OPTIONS\n' +
        '    value: "-Xms512m -Xmx512m -XX:+UseG1GC"'
    }
  }
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('TuningAdvisor', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('renders JVM and Kubernetes tuning recommendations from the memory report', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(memoryReport())))

    wrapper = mount(TuningAdvisor)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/tuning-advisor')
    const renderedText = wrapper.text()
    const panelOrder = [
      'Current JVM Arguments',
      'JVM memory calculator',
      'Recommended JVM Options',
      'Kubernetes calculator'
    ]
    const panelPositions = panelOrder.map((label) => {
      const position = renderedText.indexOf(label)
      expect(position).toBeGreaterThanOrEqual(0)
      return position
    })
    expect(panelPositions).toEqual([...panelPositions].sort((a, b) => a - b))
    expect(renderedText).toContain('1024Mi')
    expect(renderedText).toContain('Guaranteed')
    expect(renderedText).toContain('Burstable alternative')
    expect(renderedText).toContain('512Mi')
    expect(renderedText).toContain('High confidence')
    expect(renderedText).toContain('Request equals limit')
    expect(wrapper.html()).toContain('JAVA_TOOL_OPTIONS')
  })
})
