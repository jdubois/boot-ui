import {computed, onBeforeUnmount, ref, watch} from 'vue'
import {ApiError, getJson} from '../api.js'
import {formatLoadError} from './loadError.js'

export function useAdvisorRuleViolations(props) {
  const page = ref(null)
  const loading = ref(false)
  const error = ref(null)
  const stale = ref(false)
  const announcement = ref('')
  const requestOffset = ref(0)
  const scanId = computed(() => props.details?.scanId)
  const available = computed(() => typeof scanId.value === 'string' && scanId.value.trim().length > 0)
  let generation = 0
  let controller

  function invalidate() {
    generation++
    controller?.abort()
    loading.value = false
  }

  watch(
    [() => props.apiPath, () => props.rule.id, () => scanId.value],
    () => {
      invalidate()
      page.value = null
      error.value = null
      stale.value = false
      announcement.value = ''
    },
    {flush: 'sync'}
  )
  onBeforeUnmount(invalidate)

  async function load(offset = 0) {
    if (!available.value || loading.value) return false
    const requestGeneration = ++generation
    const requestedScanId = scanId.value
    const ruleId = props.rule.id
    controller = new AbortController()
    requestOffset.value = offset
    loading.value = true
    stale.value = false
    announcement.value = `Loading violations for ${ruleId}.`
    try {
      const query = new URLSearchParams({scanId: requestedScanId, offset: String(offset), limit: '100'})
      const result = await getJson(`${props.apiPath}/rules/${encodeURIComponent(ruleId)}/violations?${query}`, {
        signal: controller.signal
      })
      if (requestGeneration !== generation) return false
      if (result.scanId !== requestedScanId || result.ruleId !== ruleId) {
        throw new ApiError(409)
      }
      page.value = result
      error.value = null
      announcement.value = `${ruleId}: ${rangeLabel(result)}.`
      return true
    } catch (cause) {
      if (requestGeneration !== generation) return false
      stale.value = cause instanceof ApiError && cause.status === 409
      error.value = stale.value
        ? 'This scan is no longer available. Refresh the cached report, then view its violations. No scan will run.'
        : formatLoadError(cause, 'Unable to load violations')
      announcement.value = `${ruleId}: ${error.value}`
      return false
    } finally {
      if (requestGeneration === generation) loading.value = false
    }
  }

  /** Rereads the page on display, dropping any request still in flight: its dismissed flags are out of date. */
  async function refresh() {
    if (!page.value) return false
    invalidate()
    return load(page.value.page.offset)
  }

  function backToSamples() {
    invalidate()
    page.value = null
    error.value = null
    stale.value = false
    announcement.value = `Showing samples for ${props.rule.id}.`
  }

  return {
    page,
    loading,
    error,
    stale,
    announcement,
    available,
    load,
    retry: () => load(requestOffset.value),
    refresh,
    backToSamples
  }
}

export function rangeLabel(result) {
  const {offset, returned} = result.page
  const range = returned > 0 ? `${offset + 1}–${offset + returned}` : '0'
  const dismissed = result.suppressedCount > 0 ? `, ${result.suppressedCount} dismissed` : ''
  return `Showing ${range} of ${result.retainedCount} retained violations (${result.violationCount} found${dismissed})`
}
