import {computed, onBeforeUnmount, onMounted, ref, unref, watch} from 'vue'
import {useRefreshState} from './useRefreshState.js'

// Retry/backoff constants for managed reconnection.
const INITIAL_BACKOFF_MS = 1_000
const BACKOFF_MULTIPLIER = 2
const MAX_BACKOFF_MS = 30_000
/** Number of consecutive errors before entering the long-delay unavailable state. */
const MAX_RETRIES = 5
/** Retry interval once the stream is considered unavailable (allows background recovery). */
const LONG_DELAY_MS = 60_000
/** Milliseconds a stream must stay open without an error to be considered stable (resets retries). */
const STABLE_MS = 5_000

/**
 * Composable that loads immediately and then refreshes whenever the server pushes a Server-Sent
 * Events {@code update} tick on the given stream URL, instead of polling on a fixed interval.
 *
 * The push is only a change notification; the supplied callback re-fetches the existing REST
 * endpoint, so all server-side filtering, pagination, and masking continue to apply. It mirrors the
 * {@link useAutoRefresh} return shape so views can swap between the two with no template changes.
 *
 * Connection state lifecycle:
 *   connecting   → stream opened, waiting for first event or open acknowledgement
 *   connected    → stream open and healthy
 *   reconnecting → stream errored, waiting for backoff retry (bounded exponential)
 *   paused       → auto-refresh disabled or tab hidden; no stream open
 *   unavailable  → MAX_RETRIES consecutive errors; retrying at long interval
 *
 * @param {string} streamUrl - relative SSE endpoint to subscribe to (e.g. 'api/exceptions/stream')
 * @param {Function} callback - function to call for initial, manual, push, and visibility refreshes
 * @param {{defaultEnabled?: boolean, enabled?: boolean | import('vue').Ref<boolean>, initialLoading?: boolean}} [options] - options
 * @returns {{ autoRefresh, loading, hasLoaded, initialLoading, load, refresh, startAutoRefresh, stopAutoRefresh, connectionState }}
 */
export function useEventStreamRefresh(
  streamUrl,
  callback,
  {defaultEnabled = true, enabled = true, initialLoading = true} = {}
) {
  const autoRefresh = ref(defaultEnabled)
  const refreshEnabled = computed(() => unref(enabled) !== false)
  const {loading, hasLoaded, initialLoading: isInitialLoading, refresh} = useRefreshState(callback, {initialLoading})

  /** @type {import('vue').Ref<'connecting'|'connected'|'reconnecting'|'paused'|'unavailable'>} */
  const connectionState = ref('connecting')

  let eventSource = null
  let inFlight = false
  let retryCount = 0
  let retryTimer = null
  let stabilityTimer = null

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

  function clearTimers() {
    if (retryTimer !== null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    if (stabilityTimer !== null) {
      clearTimeout(stabilityTimer)
      stabilityTimer = null
    }
  }

  function stopAutoRefresh() {
    clearTimers()
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function scheduleRetry(delayMs) {
    clearTimeout(retryTimer)
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (refreshEnabled.value && autoRefresh.value && document.visibilityState === 'visible') {
        startAutoRefresh()
      }
    }, delayMs)
  }

  function startAutoRefresh() {
    // Always close any existing source first to prevent duplicate instances.
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    clearTimers()

    if (typeof EventSource === 'undefined') {
      connectionState.value = 'unavailable'
      return
    }
    if (!refreshEnabled.value || !autoRefresh.value || document.visibilityState !== 'visible') {
      connectionState.value = 'paused'
      return
    }

    connectionState.value = retryCount > 0 ? 'reconnecting' : 'connecting'
    eventSource = new EventSource(streamUrl)

    eventSource.addEventListener('open', () => {
      connectionState.value = 'connected'
      // Start the stability window: if no error arrives within STABLE_MS, reset the retry counter.
      clearTimeout(stabilityTimer)
      stabilityTimer = setTimeout(() => {
        stabilityTimer = null
        retryCount = 0
      }, STABLE_MS)
    })

    eventSource.addEventListener('error', () => {
      // Close the native source immediately so we fully own the reconnect timing.
      if (eventSource) {
        eventSource.close()
        eventSource = null
      }
      clearTimeout(stabilityTimer)
      stabilityTimer = null

      retryCount++
      if (retryCount >= MAX_RETRIES) {
        connectionState.value = 'unavailable'
        scheduleRetry(LONG_DELAY_MS)
      } else {
        connectionState.value = 'reconnecting'
        const delay = Math.min(INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, retryCount - 1), MAX_BACKOFF_MS)
        scheduleRetry(delay)
      }
    })

    eventSource.addEventListener('update', () => {
      if (refreshEnabled.value && autoRefresh.value && document.visibilityState === 'visible') {
        load()
      }
    })
  }

  function onVisibilityChange() {
    if (!refreshEnabled.value) {
      stopAutoRefresh()
      connectionState.value = 'paused'
      return
    }
    if (document.visibilityState === 'visible') {
      startAutoRefresh()
      if (autoRefresh.value) {
        load()
      }
    } else {
      stopAutoRefresh()
      connectionState.value = 'paused'
    }
  }

  watch([autoRefresh, refreshEnabled], ([autoRefreshEnabled, enabledNow], [, wasEnabled]) => {
    if (!enabledNow || !autoRefreshEnabled) {
      stopAutoRefresh()
      connectionState.value = 'paused'
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
    stopAutoRefresh,
    connectionState
  }
}
