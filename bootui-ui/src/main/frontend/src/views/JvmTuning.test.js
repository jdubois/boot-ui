import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import JvmTuning from './JvmTuning.vue'
import MemoryOffloadButton from './components/MemoryOffloadButton.vue'
import PanelHeader from './components/PanelHeader.vue'

const MB = 1024 * 1024

function memoryReport({
  virtualThreadsEnabled = false,
  virtualThreadsProperty = 'spring.threads.virtual.enabled',
  kubernetesBurstableEnabled = false,
  kubernetesActuatorEnabled = true
} = {}) {
  const requestMemory = kubernetesBurstableEnabled ? '512Mi' : '1024Mi'
  const qosClass = kubernetesBurstableEnabled ? 'Burstable' : 'Depends on CPU'
  const javaToolOptions = '-XX:MaxRAMPercentage=62.5 -XX:MinRAMPercentage=62.5 -XX:InitialRAMPercentage=62.5'
  const probeYaml = kubernetesActuatorEnabled
    ? '\n' +
      '  - name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED\n' +
      '    value: "true"\n' +
      'startupProbe:\n' +
      '  httpGet:\n' +
      '    path: /actuator/health/liveness\n' +
      '    port: http'
    : ''

  return {
    heap: {name: 'Heap', usedBytes: 128 * MB, committedBytes: 256 * MB, maxBytes: 512 * MB, usedPercent: 25},
    nonHeap: {name: 'Non-Heap', usedBytes: 64 * MB, committedBytes: 128 * MB, maxBytes: -1, usedPercent: 50},
    pools: [{name: 'G1 Eden Space', usedBytes: 32 * MB, committedBytes: 64 * MB, maxBytes: 128 * MB, usedPercent: 25}],
    jvmInputArguments: [],
    suggestedJvmOptions: '-Xms512m -Xmx512m -XX:MaxMetaspaceSize=64m',
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
      virtualThreadsEnabled,
      virtualThreadsProperty,
      jvmOptions: '-Xms512m -Xmx512m -XX:MaxMetaspaceSize=64m',
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
      confidence: 'Medium',
      warnings: [
        'Memory request equals memory limit. Kubernetes Guaranteed QoS additionally requires equal, non-zero CPU request and limit for every container in the Pod.',
        'Direct memory is modeled from the current direct-buffer usage and is intentionally not hard-capped.',
        'Exceeding the generated MaxMetaspaceSize hard cap can cause OutOfMemoryError: Metaspace.'
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
      healthProbesEnabled: kubernetesActuatorEnabled
    }
  }
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('JvmTuning', () => {
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

    wrapper = mount(JvmTuning)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/jvm-tuning')
    const renderedText = wrapper.text()
    const panelOrder = [
      'Current JVM Arguments',
      'Virtual threads',
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
    expect(renderedText).toContain('Depends on CPU')
    expect(renderedText).not.toContain('Burstable alternative')
    expect(renderedText).toContain('Medium model confidence')
    expect(renderedText).toContain('CPU request and limit')
    expect(renderedText).toContain('observed committed metadata plus 25%, when available')
    expect(renderedText).toContain('OutOfMemoryError: Metaspace')
    expect(renderedText).not.toContain('metaspace sized for')
    expect(renderedText).not.toContain('Garbage collector:')
    expect(wrapper.find('.alert-info').text()).toContain('Sizing notes')
    expect(renderedText.indexOf('Deployment snippet')).toBeLessThan(renderedText.indexOf('Sizing notes'))
    expect(renderedText).toContain('Burstable resources')
    expect(renderedText).toContain('Kubernetes health probes')
    const virtualThreadsStatus = wrapper.find('.virtual-threads-status')
    expect(virtualThreadsStatus.classes()).toContain('alert-warning')
    expect(virtualThreadsStatus.text()).toContain('Virtual threads not enabled')
    expect(virtualThreadsStatus.text()).toContain('representative load')
    const optionsText = wrapper
      .findAll('.options-box code')
      .map((node) => node.text())
      .join('\n')
    expect(optionsText).toContain('JAVA_TOOL_OPTIONS')
    expect(optionsText).toContain('MaxRAMPercentage=62.5')
    expect(optionsText).toContain('MinRAMPercentage=62.5')
    expect(optionsText).not.toContain('spring.threads.virtual.enabled=true')
    expect(renderedText).toContain('62.5%')
    expect(optionsText).toContain('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED')
    expect(wrapper.html()).not.toContain('value: &quot;-Xms512m')
    expect(wrapper.find('#virtualThreadsEnabled').exists()).toBe(false)
    expect(wrapper.find('#kubernetesBurstableEnabled').element.checked).toBe(false)
    expect(wrapper.find('#kubernetesActuatorEnabled').element.checked).toBe(true)
    const memorySegments = wrapper.findAll('.breakdown-bar .progress-bar')
    expect(
      memorySegments.find((segment) => segment.attributes('title').startsWith('Direct')).attributes('style')
    ).toContain('color: rgb(33, 37, 41)')
    expect(
      memorySegments.find((segment) => segment.attributes('title').startsWith('Thread stacks')).attributes('style')
    ).toContain('color: rgb(33, 37, 41)')
  })

  it('shows an information bubble when virtual threads are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(memoryReport({virtualThreadsEnabled: true}))))

    wrapper = mount(JvmTuning)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/jvm-tuning')
    const virtualThreadsStatus = wrapper.find('.virtual-threads-status')
    expect(virtualThreadsStatus.classes()).toContain('alert-info')
    expect(virtualThreadsStatus.text()).toContain('Virtual threads enabled')
    expect(virtualThreadsStatus.text()).toContain('conservative platform-thread reserve')
    expect(wrapper.find('#virtualThreadsEnabled').exists()).toBe(false)
    expect(wrapper.text()).toContain('1024 KiB per platform thread')
    const optionsText = wrapper
      .findAll('.options-box code')
      .map((node) => node.text())
      .join('\n')
    expect(optionsText).not.toContain('spring.threads.virtual.enabled=true')
  })

  it('preserves sub-percent heap precision', async () => {
    const report = memoryReport()
    report.kubernetes.maxRamPercentage = 0.781
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report)))

    wrapper = mount(JvmTuning)
    await flushPromises()

    expect(wrapper.text()).toContain('0.781%')
  })

  it('hides the virtual-threads advisory when no app-wide switch exists (Quarkus)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(memoryReport({virtualThreadsProperty: null}))))

    wrapper = mount(JvmTuning)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/jvm-tuning')
    expect(wrapper.find('.virtual-threads-status').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Virtual threads not enabled')
    // The rest of the panel still renders (calculator + Kubernetes recommendation).
    expect(wrapper.text()).toContain('Kubernetes health probes')
    expect(wrapper.find('#kubernetesActuatorEnabled').exists()).toBe(true)
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

    wrapper = mount(JvmTuning)
    await flushPromises()

    expect(wrapper.find('#kubernetesBurstableEnabled').element.checked).toBe(true)
    expect(wrapper.find('#kubernetesActuatorEnabled').element.checked).toBe(false)
    expect(wrapper.text()).toContain('Burstable')
  })

  it('keeps equal-request wording when burstable sizing reaches the limit', async () => {
    const report = memoryReport({kubernetesBurstableEnabled: true})
    report.kubernetes.requestMemoryBytes = report.kubernetes.limitMemoryBytes
    report.kubernetes.requestMemory = report.kubernetes.limitMemory
    report.kubernetes.qosClass = 'Depends on CPU'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report)))

    wrapper = mount(JvmTuning)
    await flushPromises()

    expect(wrapper.text()).toContain('Memory request equals limit')
    expect(wrapper.text()).toContain('CPU settings also required')
  })

  it('reloads recommendations when Kubernetes toggles are changed', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(memoryReport()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(JvmTuning)
    await flushPromises()

    await wrapper.find('#kubernetesBurstableEnabled').setValue(true)
    await wrapper.find('#kubernetesActuatorEnabled').setValue(false)
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/jvm-tuning?kubernetesBurstableEnabled=true&kubernetesActuatorEnabled=false'
    )
  })

  it('keeps an invalid untouched default invalid across two refreshes until a real sizing edit', async () => {
    const invalid = memoryReport()
    invalid.calculation = {
      ...invalid.calculation,
      totalMemoryBytes: 2048 * MB,
      threadCount: 1500,
      heapBytes: 0,
      jvmOptions: '',
      valid: false,
      error: 'Insufficient heap budget: the 2048 MiB default cannot request at least 128 MiB.'
    }
    invalid.suggestedJvmOptions = ''
    invalid.kubernetes = {...invalid.kubernetes, yaml: '', javaToolOptions: '', qosClass: 'Unavailable'}
    const fetchMock = vi.fn().mockImplementation((url) => {
      const manual = new URL(url, 'http://localhost/').searchParams.has('totalMemoryMb')
      return Promise.resolve(jsonResponse(manual ? memoryReport() : invalid))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(JvmTuning)
    await flushPromises()
    expect(wrapper.get('#jvm-target-memory').element.value).toBe('2048')
    expect(wrapper.text()).toContain(invalid.calculation.error)

    for (let refresh = 0; refresh < 2; refresh++) {
      vi.advanceTimersByTime(10_000)
      await flushPromises()
      expect(wrapper.text()).toContain(invalid.calculation.error)
      expect(wrapper.text()).not.toContain('-Xms512m')
    }
    expect(fetchMock).toHaveBeenCalledTimes(3)
    for (const [url] of fetchMock.mock.calls) {
      const params = new URL(url, 'http://localhost/').searchParams
      expect(params.has('totalMemoryMb')).toBe(false)
      expect(params.has('threadCount')).toBe(false)
      expect(params.has('headRoomPercent')).toBe(false)
    }

    await wrapper.get('#jvm-target-memory').setValue('2048')
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/jvm-tuning?kubernetesBurstableEnabled=false&kubernetesActuatorEnabled=true&totalMemoryMb=2048'
    )
    expect(wrapper.text()).not.toContain(invalid.calculation.error)
    expect(wrapper.text()).toContain('-Xms512m')
  })

  it('refreshes untouched displayed defaults while preserving only actual sizing overrides', async () => {
    const updated = memoryReport()
    updated.calculation = {...updated.calculation, totalMemoryBytes: 1280 * MB, threadCount: 300, headRoomPercent: 15}
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(memoryReport()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(JvmTuning)
    await flushPromises()
    await wrapper.get('#jvm-target-memory').setValue('704')
    fetchMock.mockResolvedValue(jsonResponse(updated))
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/jvm-tuning?kubernetesBurstableEnabled=false&kubernetesActuatorEnabled=true&totalMemoryMb=704'
    )
    expect(wrapper.get('#jvm-target-memory').element.value).toBe('704')
    expect(wrapper.get('#jvm-thread-budget').element.value).toBe('300')
    expect(wrapper.get('#jvm-headroom').element.value).toBe('15')

    await wrapper.get('#jvm-thread-budget').setValue('100')
    await wrapper.get('#jvm-headroom').setValue('0')
    vi.advanceTimersByTime(300)
    await flushPromises()
    vi.advanceTimersByTime(10_000)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/jvm-tuning?kubernetesBurstableEnabled=false&kubernetesActuatorEnabled=true&totalMemoryMb=704&threadCount=100&headRoomPercent=0'
    )
    expect(wrapper.get('#jvm-target-memory').element.value).toBe('704')
    expect(wrapper.get('#jvm-thread-budget').element.value).toBe('100')
    expect(wrapper.get('#jvm-headroom').element.value).toBe('0')
  })

  it('offers the shared BootUI memory offload in the header and recalculates after it ran', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(memoryReport()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(JvmTuning)
    await flushPromises()
    const calls = fetchMock.mock.calls.length

    wrapper.getComponent(PanelHeader).getComponent(MemoryOffloadButton).vm.$emit('offloaded', {reclaimedBytes: 0})
    await flushPromises()

    expect(fetchMock.mock.calls.length).toBe(calls + 1)
  })
})
