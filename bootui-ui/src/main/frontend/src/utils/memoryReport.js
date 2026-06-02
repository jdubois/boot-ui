import {onBeforeUnmount, ref, watch} from 'vue'
import {formatLoadError} from './loadError.js'
import {useAutoRefresh} from './useAutoRefresh.js'

export const MB = 1024 * 1024

export function bytesToMb(bytes) {
  return Math.round(bytes / MB)
}

export function formatBytes(bytes) {
  if (bytes == null || bytes < 0) return 'N/A'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

export function memoryProgressClass(pct) {
  if (pct >= 90) return 'bg-danger'
  if (pct >= 75) return 'bg-warning'
  return 'bg-success'
}

export function confidenceBadgeClass(confidence) {
  if (confidence === 'High') return 'text-bg-success'
  if (confidence === 'Medium') return 'text-bg-warning'
  if (confidence === 'Low') return 'text-bg-secondary'
  return 'text-bg-secondary'
}

export function useMemoryReport({endpoint = 'api/memory', tuningInputs = false} = {}) {
  const data = ref(null)
  const error = ref(null)
  const lastUpdated = ref(null)
  const totalMemoryMb = ref(null)
  const threadCount = ref(null)
  const headRoomPercent = ref(null)
  const virtualThreadsEnabled = ref(false)
  const kubernetesBurstableEnabled = ref(false)
  const kubernetesActuatorEnabled = ref(true)
  const inputsInitialized = ref(false)
  let debounceHandle = null
  let initializingInputs = false

  function buildQuery() {
    if (!tuningInputs) return ''
    if (!inputsInitialized.value) return ''
    const params = new URLSearchParams()
    params.set('virtualThreadsEnabled', String(virtualThreadsEnabled.value))
    params.set('kubernetesBurstableEnabled', String(kubernetesBurstableEnabled.value))
    params.set('kubernetesActuatorEnabled', String(kubernetesActuatorEnabled.value))
    if (totalMemoryMb.value != null) params.set('totalMemoryMb', String(totalMemoryMb.value))
    if (threadCount.value != null) params.set('threadCount', String(threadCount.value))
    if (headRoomPercent.value != null) params.set('headRoomPercent', String(headRoomPercent.value))
    const query = params.toString()
    return query ? '?' + query : ''
  }

  async function fetchMemory() {
    try {
      const res = await fetch(endpoint + buildQuery())
      if (!res.ok) throw new Error('HTTP ' + res.status)
      const payload = await res.json()
      data.value = payload
      lastUpdated.value = new Date()
      error.value = null
      if (tuningInputs && !inputsInitialized.value && payload.calculation) {
        initializingInputs = true
        totalMemoryMb.value = bytesToMb(payload.calculation.totalMemoryBytes)
        threadCount.value = payload.calculation.threadCount
        headRoomPercent.value = payload.calculation.headRoomPercent
        virtualThreadsEnabled.value = payload.calculation.virtualThreadsEnabled ?? false
        kubernetesBurstableEnabled.value = payload.kubernetes?.burstableEnabled ?? false
        kubernetesActuatorEnabled.value = payload.kubernetes?.actuatorProbesEnabled ?? true
        inputsInitialized.value = true
        queueMicrotask(() => {
          initializingInputs = false
        })
      }
    } catch (e) {
      error.value = formatLoadError(e, 'Unable to load memory data')
    }
  }

  function scheduleReload() {
    if (debounceHandle) clearTimeout(debounceHandle)
    debounceHandle = setTimeout(() => {
      load()
    }, 300)
  }

  if (tuningInputs) {
    watch(
      [
        totalMemoryMb,
        threadCount,
        headRoomPercent,
        virtualThreadsEnabled,
        kubernetesBurstableEnabled,
        kubernetesActuatorEnabled
      ],
      () => {
        if (inputsInitialized.value && !initializingInputs) scheduleReload()
      }
    )
  }

  const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchMemory)

  onBeforeUnmount(() => {
    if (debounceHandle) clearTimeout(debounceHandle)
  })

  return {
    data,
    error,
    lastUpdated,
    totalMemoryMb,
    threadCount,
    headRoomPercent,
    virtualThreadsEnabled,
    kubernetesBurstableEnabled,
    kubernetesActuatorEnabled,
    autoRefresh,
    loading,
    initialLoading,
    load
  }
}
