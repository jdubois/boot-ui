import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import TuningAdvisor from './TuningAdvisor.vue'

const MB = 1024 * 1024

function memoryReport({
  virtualThreadsEnabled = false,
  kubernetesBurstableEnabled = false,
  kubernetesActuatorEnabled = true
} = {}) {
  const requestMemory = kubernetesBurstableEnabled ? '512Mi' : '1024Mi'
  const qosClass = kubernetesBurstableEnabled ? 'Burstable' : 'Guaranteed'
  const javaToolOptions =
    '-XX:+UseContainerSupport -XX:MaxRAMPercentage=62.5 -XX:InitialRAMPercentage=62.5 -XX:+UseG1GC' +
    (virtualThreadsEnabled ? ' -Dspring.threads.virtual.enabled=true' : '')
  const probeYaml = kubernetesActuatorEnabled
    ? '\n' +
      '  - name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED\n' +
      '    value: "true"\n' +
      'startupProbe:\n' +
      '  httpGet:\n' +
      '    path: /actuator/health/liveness\n' +
      '    port: 8080'
    : ''

  return {
    heap: {name: 'Heap', usedBytes: 128 * MB, committedBytes: 256 * MB, maxBytes: 512 * MB, usedPercent: 25},
    nonHeap: {name: 'Non-Heap', usedBytes: 64 * MB, committedBytes: 128 * MB, maxBytes: -1, usedPercent: 50},
    pools: [{name: 'G1 Eden Space', usedBytes: 32 * MB, committedBytes: 64 * MB, maxBytes: 128 * MB, usedPercent: 25}],
    jvmInputArguments: [],
    suggestedJvmOptions: virtualThreadsEnabled
      ? '-Xms512m -Xmx512m -XX:+UseG1GC -Dspring.threads.virtual.enabled=true'
      : '-Xms512m -Xmx512m -XX:+UseG1GC',
    calculation: {
      totalMemoryBytes: 1024 * MB,
      heapBytes: 512 * MB,
      metaspaceBytes: 64 * MB,
      codeCacheBytes: 240 * MB,
      directMemoryBytes: 10 * MB,
      stackBytesPerThread: virtualThreadsEnabled ? MB / 2 : MB,
      stackBytesTotal: virtualThreadsEnabled ? 125 * MB : 250 * MB,
      headRoomBytes: 102 * MB,
      fixedRegionsBytes: 564 * MB,
      threadCount: 250,
      loadedClasses: 5000,
      liveThreadCount: 40,
      liveLoadedClassCount: 5000,
      headRoomPercent: 10,
      virtualThreadsEnabled,
      jvmOptions: virtualThreadsEnabled
        ? '-Xms512m -Xmx512m -XX:+UseG1GC -Dspring.threads.virtual.enabled=true'
        : '-Xms512m -Xmx512m -XX:+UseG1GC',
      valid: true,
      error: null
    },
    kubernetes: {
      requestMemoryBytes: kubernetesBurstableEnabled ? 512 * MB : 1024 * MB,
      limitMemoryBytes: 1024 * MB,
      burstableRequestMemoryBytes: 512 * MB,
      currentSnapshotBytes: 432 * MB,
      detectedContainerLimitBytes: 1024 * MB,
      requestMemory,
      limitMemory: '1024Mi',
      burstableRequestMemory: '512Mi',
      currentSnapshotMemory: '432Mi',
      detectedContainerLimitMemory: '1024Mi',
      qosClass,
      confidence: 'High',
      warnings: [
        'Garbage collector: G1GC is selected for calculated heaps below 4 GiB; the advisor switches to ZGC for larger heaps.',
        'Request equals limit for Kubernetes Guaranteed QoS.'
      ],
      yaml:
        'resources:\n' +
        '  requests:\n' +
        `    memory: "${requestMemory}"\n` +
        '  limits:\n' +
        '    memory: "1024Mi"\n' +
        'env:\n' +
        '  - name: JAVA_TOOL_OPTIONS\n' +
        '    value: >-\n' +
        `      ${javaToolOptions}` +
        probeYaml,
      maxRamPercentage: 62.5,
      initialRamPercentage: 62.5,
      javaToolOptions,
      burstableEnabled: kubernetesBurstableEnabled,
      actuatorProbesEnabled: kubernetesActuatorEnabled
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
      'Spring virtual threads',
      'Bare metal JVM calculator',
      'Bare metal JVM options',
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
    expect(renderedText).not.toContain('Burstable alternative')
    expect(renderedText).toContain('High confidence')
    expect(renderedText).toContain('Request equals limit')
    expect(renderedText).toContain('Garbage collector: G1GC')
    expect(wrapper.find('.alert-info').text()).toContain('Sizing notes')
    expect(renderedText.indexOf('Deployment snippet')).toBeLessThan(renderedText.indexOf('Sizing notes'))
    expect(renderedText.indexOf('Garbage collector: G1GC')).toBeLessThan(renderedText.indexOf('Request equals limit'))
    expect(renderedText).toContain('Burstable resources')
    expect(renderedText).toContain('Spring Boot Actuator probes')
    expect(wrapper.html()).toContain('JAVA_TOOL_OPTIONS')
    expect(wrapper.html()).toContain('MaxRAMPercentage=62.5')
    expect(wrapper.html()).toContain('spring.threads.virtual.enabled=true')
    expect(wrapper.html()).toContain('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED')
    expect(wrapper.html()).not.toContain('value: &quot;-Xms512m')
    expect(wrapper.find('#virtualThreadsEnabled').element.checked).toBe(false)
    expect(wrapper.find('#kubernetesBurstableEnabled').element.checked).toBe(false)
    expect(wrapper.find('#kubernetesActuatorEnabled').element.checked).toBe(true)
  })

  it('initializes the virtual-thread toggle from the memory report', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(memoryReport({virtualThreadsEnabled: true}))))

    wrapper = mount(TuningAdvisor)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/tuning-advisor')
    expect(wrapper.find('#virtualThreadsEnabled').element.checked).toBe(true)
    expect(wrapper.text()).toContain('Enabled')
  })

  it('initializes Kubernetes toggles from the memory report', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(memoryReport({kubernetesBurstableEnabled: true, kubernetesActuatorEnabled: false}))
        )
    )

    wrapper = mount(TuningAdvisor)
    await flushPromises()

    expect(wrapper.find('#kubernetesBurstableEnabled').element.checked).toBe(true)
    expect(wrapper.find('#kubernetesActuatorEnabled').element.checked).toBe(false)
    expect(wrapper.text()).toContain('Burstable')
  })

  it('reloads recommendations when virtual threads are toggled', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(memoryReport()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(TuningAdvisor)
    await flushPromises()

    await wrapper.find('#virtualThreadsEnabled').setValue(true)
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/tuning-advisor?virtualThreadsEnabled=true&kubernetesBurstableEnabled=false&kubernetesActuatorEnabled=true&totalMemoryMb=1024&threadCount=250&headRoomPercent=10'
    )
  })

  it('reloads recommendations when Kubernetes toggles are changed', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(memoryReport()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(TuningAdvisor)
    await flushPromises()

    await wrapper.find('#kubernetesBurstableEnabled').setValue(true)
    await wrapper.find('#kubernetesActuatorEnabled').setValue(false)
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/tuning-advisor?virtualThreadsEnabled=false&kubernetesBurstableEnabled=true&kubernetesActuatorEnabled=false&totalMemoryMb=1024&threadCount=250&headRoomPercent=10'
    )
  })
})
