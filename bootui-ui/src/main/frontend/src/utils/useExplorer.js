import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {buildActivityQueryParams, filterEntries} from './activityStream.js'
import {formatLoadError} from './loadError.js'
import {useEventStreamRefresh} from './useEventStreamRefresh.js'
import {appendOlderExplorerEvents, createFreshEvidenceTracker, mergeExplorerEvents} from './explorerModel.js'

export function useExplorer(enabled) {
  const report = ref(null),
    error = ref(null),
    lastFetched = ref(null)
  const type = ref(''),
    severity = ref(''),
    text = ref('')
  const older = ref([]),
    olderInfo = ref(null),
    loadingOlder = ref(false)
  const selectedId = ref(null),
    selectedEvent = ref(null),
    detail = ref(null),
    detailError = ref(null),
    detailLoading = ref(false)
  const freshIds = ref([]),
    follow = ref(false),
    replaying = ref(false)
  const hidden = ref(document.visibilityState === 'hidden')
  const tracker = createFreshEvidenceTracker()
  let disposed = false,
    generation = 0,
    detailGeneration = 0
  let rootController, detailController, olderController, reconciliationTimer, filterTimer
  const persistent = computed(() => report.value?.activity?.pageInfo?.persistent === true)
  const entries = computed(() => mergeExplorerEvents(report.value?.activity?.entries, older.value))
  const visibleEntries = computed(() =>
    filterEntries(entries.value, {type: type.value, severity: severity.value, text: text.value})
  )
  const pageInfo = computed(() => olderInfo.value || report.value?.activity?.pageInfo)
  const canLoadOlder = computed(() => persistent.value && !!pageInfo.value?.hasMore && !!pageInfo.value?.nextCursor)
  const streamEnabled = computed(() => enabled.value && report.value?.available !== false)

  function url(extra = {}) {
    const params = new URLSearchParams(
      buildActivityQueryParams({type: type.value, severity: severity.value, text: text.value})
    )
    for (const [key, value] of Object.entries(extra)) params.set(key, value)
    return `api/explorer${params.size ? `?${params}` : ''}`
  }
  async function read(path, signal) {
    const response = await apiFetch(path, {signal})
    if (!response.ok) throw new Error(`Request failed with status ${response.status}`)
    return response.json()
  }
  async function loadRoot() {
    const current = generation
    rootController?.abort()
    rootController = new AbortController()
    try {
      const next = await read(url(), rootController.signal)
      if (disposed || current !== generation || !enabled.value) return
      const fresh = tracker.accept(
        next.activity?.entries || [],
        follow.value && autoRefresh.value && !hidden.value && !replaying.value
      )
      report.value = next
      freshIds.value = fresh
      error.value = null
      lastFetched.value = Date.now()
      if (selectedId.value && !replaying.value && !detailLoading.value) void loadDetail(false)
    } catch (err) {
      if (err.name === 'AbortError' || disposed || current !== generation) return
      error.value = formatLoadError(err, 'Could not load Explorer activity')
    }
  }
  const {
    autoRefresh,
    loading,
    initialLoading,
    load: refresh,
    connectionState,
    retryConnection
  } = useEventStreamRefresh('api/activity/stream', loadRoot, {enabled: streamEnabled})

  function cancelDetail() {
    detailGeneration++
    detailController?.abort()
    clearTimeout(reconciliationTimer)
    detailLoading.value = false
  }
  async function loadDetail(reconcile = false) {
    const id = selectedId.value
    if (!id || !enabled.value || disposed) return
    cancelDetail()
    const current = detailGeneration
    detailController = new AbortController()
    detailLoading.value = true
    try {
      const timestamp = selectedEvent.value?.timestamp
      const version = Number.isFinite(timestamp) ? `?timestamp=${timestamp}` : ''
      const next = await read(`api/explorer/events/${encodeURIComponent(id)}${version}`, detailController.signal)
      if (disposed || id !== selectedId.value || current !== detailGeneration || !enabled.value) return
      detail.value = next
      detailError.value = null
      if (next.event) selectedEvent.value = next.event
      if (reconcile && autoRefresh.value && !hidden.value && (next.partial || !next.found)) {
        // A single reconciliation catches advice finishing after the activity notification. No poller.
        reconciliationTimer = setTimeout(() => {
          if (!replaying.value) void loadDetail(false)
        }, 400)
      }
    } catch (err) {
      if (err.name !== 'AbortError' && !disposed && current === detailGeneration)
        detailError.value = formatLoadError(err, 'Could not load captured detail')
    } finally {
      if (current === detailGeneration) detailLoading.value = false
    }
  }
  function select(event) {
    cancelDetail()
    selectedId.value = event?.id || null
    selectedEvent.value = event || null
    detail.value = null
    detailError.value = null
    freshIds.value = []
    if (event) void loadDetail(true)
  }
  async function loadOlder() {
    if (!canLoadOlder.value || loadingOlder.value || !enabled.value) return
    const current = generation
    loadingOlder.value = true
    olderController = new AbortController()
    try {
      const next = await read(url({cursor: pageInfo.value.nextCursor}), olderController.signal)
      if (disposed || current !== generation) return
      older.value = appendOlderExplorerEvents(report.value?.activity?.entries, older.value, next.activity?.entries)
      olderInfo.value = next.activity?.pageInfo
      freshIds.value = []
      error.value = null
    } catch (err) {
      if (err.name !== 'AbortError' && !disposed && current === generation)
        error.value = formatLoadError(err, 'Could not load older activity')
    } finally {
      loadingOlder.value = false
    }
  }
  function boundary() {
    generation++
    rootController?.abort()
    olderController?.abort()
    cancelDetail()
    tracker.reset()
    freshIds.value = []
  }
  function visibility() {
    hidden.value = document.visibilityState === 'hidden'
  }
  watch(
    [autoRefresh, hidden, enabled],
    () => {
      boundary()
      if (!enabled.value) {
        report.value = null
        older.value = []
        select(null)
      }
    },
    {flush: 'sync'}
  )
  watch(
    follow,
    () => {
      tracker.reset()
      freshIds.value = []
    },
    {flush: 'sync'}
  )
  watch([type, severity, text], () => {
    generation++
    tracker.reset()
    freshIds.value = []
    older.value = []
    olderInfo.value = null
    olderController?.abort()
    clearTimeout(filterTimer)
    filterTimer = setTimeout(() => void refresh(), 250)
  })
  onMounted(() => document.addEventListener('visibilitychange', visibility, true))
  onBeforeUnmount(() => {
    disposed = true
    boundary()
    clearTimeout(filterTimer)
    document.removeEventListener('visibilitychange', visibility, true)
  })
  return {
    report,
    error,
    lastFetched,
    type,
    severity,
    text,
    entries,
    visibleEntries,
    selectedId,
    selectedEvent,
    detail,
    detailError,
    detailLoading,
    select,
    loadDetail,
    freshIds,
    follow,
    replaying,
    hidden,
    loadingOlder,
    canLoadOlder,
    loadOlder,
    autoRefresh,
    loading,
    initialLoading,
    refresh,
    connectionState,
    retryConnection
  }
}
