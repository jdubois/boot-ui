import {computed, onBeforeUnmount, onMounted, ref, unref, watch} from 'vue'
import {useRefreshState} from './useRefreshState.js'

/**
 * Composable that loads immediately and refreshes while auto-refresh is enabled and the tab is visible.
 *
 * @param {Function} callback - function to call for initial, manual, interval, and visibility refreshes
 * @param {{intervalMs?: number, defaultEnabled?: boolean, enabled?: boolean | import('vue').Ref<boolean>, initialLoading?: boolean}} [options] - auto-refresh options
 * @returns {{ autoRefresh, intervalMs, loading, hasLoaded, initialLoading, load, loadAfterCurrent, refresh, startAutoRefresh, stopAutoRefresh }}
 */
export function useAutoRefresh(
  callback,
  {intervalMs = 10_000, defaultEnabled = true, enabled = true, initialLoading = true} = {}
) {
  const autoRefresh = ref(defaultEnabled)
  const refreshEnabled = computed(() => unref(enabled) !== false)
  const {loading, hasLoaded, initialLoading: isInitialLoading, refresh} = useRefreshState(callback, {initialLoading})
  let timer = null
  let inFlight = false
  let pendingRefresh = false
  let disposed = false

  function stopAutoRefresh() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  async function load(...args) {
    if (disposed || !refreshEnabled.value) return
    if (inFlight) return
    inFlight = true
    try {
      return await refresh(...args)
    } finally {
      inFlight = false
      if (pendingRefresh) {
        pendingRefresh = false
        void load()
      }
    }
  }

  // Post-action reads must not be dropped by an outstanding refresh. Ordinary refreshes keep their existing policy.
  function loadAfterCurrent() {
    if (disposed || !refreshEnabled.value) return
    if (inFlight) {
      pendingRefresh = true
      return
    }
    return load()
  }

  function startAutoRefresh() {
    stopAutoRefresh()
    if (refreshEnabled.value && autoRefresh.value && document.visibilityState === 'visible') {
      timer = setInterval(() => {
        if (refreshEnabled.value && autoRefresh.value && document.visibilityState === 'visible') {
          load()
        }
      }, intervalMs)
    }
  }

  function onVisibilityChange() {
    if (!refreshEnabled.value) {
      stopAutoRefresh()
      return
    }
    if (document.visibilityState === 'visible') {
      startAutoRefresh()
      if (autoRefresh.value) {
        load()
      }
    } else {
      stopAutoRefresh()
    }
  }

  watch([autoRefresh, refreshEnabled], ([autoRefreshEnabled, enabledNow], [, wasEnabled]) => {
    if (!enabledNow || !autoRefreshEnabled) {
      stopAutoRefresh()
      return
    }
    startAutoRefresh()
    if (wasEnabled === false && document.visibilityState === 'visible') {
      load()
    }
  })

  onMounted(() => {
    if (refreshEnabled.value) {
      load()
    }
    startAutoRefresh()
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onBeforeUnmount(() => {
    disposed = true
    pendingRefresh = false
    stopAutoRefresh()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    autoRefresh,
    intervalMs,
    loading,
    hasLoaded,
    initialLoading: isInitialLoading,
    load,
    loadAfterCurrent,
    refresh: load,
    startAutoRefresh,
    stopAutoRefresh
  }
}
