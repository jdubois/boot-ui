import {computed, onBeforeUnmount, onMounted, ref, unref, watch} from 'vue'
import {useRefreshState} from './useRefreshState.js'

/**
 * Composable that loads immediately and then refreshes whenever the server pushes a Server-Sent
 * Events {@code update} tick on the given stream URL, instead of polling on a fixed interval.
 *
 * The push is only a change notification; the supplied callback re-fetches the existing REST
 * endpoint, so all server-side filtering, pagination, and masking continue to apply. It mirrors the
 * {@link useAutoRefresh} return shape so views can swap between the two with no template changes.
 *
 * @param {string} streamUrl - relative SSE endpoint to subscribe to (e.g. 'api/exceptions/stream')
 * @param {Function} callback - function to call for initial, manual, push, and visibility refreshes
 * @param {{defaultEnabled?: boolean, enabled?: boolean | import('vue').Ref<boolean>, initialLoading?: boolean}} [options] - options
 * @returns {{ autoRefresh, loading, hasLoaded, initialLoading, load, refresh, startAutoRefresh, stopAutoRefresh }}
 */
export function useEventStreamRefresh(
  streamUrl,
  callback,
  {defaultEnabled = true, enabled = true, initialLoading = true} = {}
) {
  const autoRefresh = ref(defaultEnabled)
  const refreshEnabled = computed(() => unref(enabled) !== false)
  const {loading, hasLoaded, initialLoading: isInitialLoading, refresh} = useRefreshState(callback, {initialLoading})
  let eventSource = null
  let inFlight = false

  async function load(...args) {
    if (!refreshEnabled.value) return
    if (inFlight) return
    inFlight = true
    try {
      return await refresh(...args)
    } finally {
      inFlight = false
    }
  }

  function stopAutoRefresh() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh()
    if (typeof EventSource === 'undefined') return
    if (!refreshEnabled.value || !autoRefresh.value || document.visibilityState !== 'visible') {
      return
    }
    eventSource = new EventSource(streamUrl)
    eventSource.addEventListener('update', () => {
      if (refreshEnabled.value && autoRefresh.value && document.visibilityState === 'visible') {
        load()
      }
    })
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
    stopAutoRefresh()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    autoRefresh,
    loading,
    hasLoaded,
    initialLoading: isInitialLoading,
    load,
    refresh: load,
    startAutoRefresh,
    stopAutoRefresh
  }
}
