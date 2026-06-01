import {onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRefreshState} from './useRefreshState.js'

/**
 * Composable that loads immediately and refreshes while auto-refresh is enabled and the tab is visible.
 *
 * @param {Function} callback - function to call for initial, manual, interval, and visibility refreshes
 * @param {{intervalMs?: number, defaultEnabled?: boolean}} [options] - auto-refresh options
 * @returns {{ autoRefresh, intervalMs, loading, hasLoaded, initialLoading, load, refresh, startAutoRefresh, stopAutoRefresh }}
 */
export function useAutoRefresh(callback, {intervalMs = 10_000, defaultEnabled = true} = {}) {
  const autoRefresh = ref(defaultEnabled)
  const {loading, hasLoaded, initialLoading, refresh} = useRefreshState(callback)
  let timer = null
  let inFlight = false

  function stopAutoRefresh() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  async function load(...args) {
    if (inFlight) return
    inFlight = true
    try {
      return await refresh(...args)
    } finally {
      inFlight = false
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh()
    if (autoRefresh.value && document.visibilityState === 'visible') {
      timer = setInterval(() => {
        if (autoRefresh.value && document.visibilityState === 'visible') {
          load()
        }
      }, intervalMs)
    }
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'visible') {
      startAutoRefresh()
      if (autoRefresh.value) {
        load()
      }
    } else {
      stopAutoRefresh()
    }
  }

  watch(autoRefresh, () => {
    if (autoRefresh.value) {
      startAutoRefresh()
    } else {
      stopAutoRefresh()
    }
  })

  onMounted(() => {
    load()
    startAutoRefresh()
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onBeforeUnmount(() => {
    stopAutoRefresh()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    autoRefresh,
    intervalMs,
    loading,
    hasLoaded,
    initialLoading,
    load,
    refresh: load,
    startAutoRefresh,
    stopAutoRefresh
  }
}
