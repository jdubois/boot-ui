import {computed, onBeforeUnmount, onMounted, ref, unref, watch} from 'vue'
import {useRefreshState} from './useRefreshState.js'

const INITIAL_BACKOFF_MS = 1_000
const BACKOFF_MULTIPLIER = 2
const MAX_BACKOFF_MS = 30_000
const MAX_RETRIES = 5
const LONG_DELAY_MS = 60_000
const STABLE_MS = 5_000

/** @typedef {'connecting'|'connected'|'reconnecting'|'paused'|'unavailable'} EventStreamConnectionState */

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
 * @returns {{ autoRefresh, loading, hasLoaded, initialLoading, load, refresh, startAutoRefresh, stopAutoRefresh, retryConnection, connectionState }}
 */
export function useEventStreamRefresh(
  streamUrl,
  callback,
  {defaultEnabled = true, enabled = true, initialLoading = true} = {}
) {
  const autoRefresh = ref(defaultEnabled)
  const refreshEnabled = computed(() => unref(enabled) !== false)
  const {loading, hasLoaded, initialLoading: isInitialLoading, refresh} = useRefreshState(callback, {initialLoading})

  /** @type {import('vue').Ref<EventStreamConnectionState>} */
  const connectionState = ref('connecting')

  /** @type {EventSource | null} */
  let eventSource = null
  let inFlight = false
  let retryCount = 0
  /** @type {ReturnType<typeof setTimeout> | null} */
  let retryTimer = null
  /** @type {ReturnType<typeof setTimeout> | null} */
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

  function clearRetryTimer() {
    if (retryTimer !== null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  function clearStabilityTimer() {
    if (stabilityTimer !== null) {
      clearTimeout(stabilityTimer)
      stabilityTimer = null
    }
  }

  function closeEventSource() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function teardownConnection() {
    clearRetryTimer()
    clearStabilityTimer()
    closeEventSource()
  }

  function canConnect() {
    return refreshEnabled.value && autoRefresh.value && document.visibilityState === 'visible'
  }

  function stopAutoRefresh() {
    teardownConnection()
    connectionState.value = 'paused'
  }

  function scheduleRetry(delayMs) {
    clearRetryTimer()
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (canConnect()) {
        startAutoRefresh()
      } else {
        connectionState.value = 'paused'
      }
    }, delayMs)
  }

  /**
   * @param {EventSource | null} source
   */
  function handleConnectionError(source) {
    if (source !== null && eventSource !== source) return

    if (source !== null) {
      source.close()
      eventSource = null
    }
    clearStabilityTimer()

    if (!canConnect()) {
      clearRetryTimer()
      connectionState.value = 'paused'
      return
    }

    retryCount++
    if (retryCount >= MAX_RETRIES) {
      connectionState.value = 'unavailable'
      scheduleRetry(LONG_DELAY_MS)
      return
    }

    connectionState.value = 'reconnecting'
    const delay = Math.min(INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, retryCount - 1), MAX_BACKOFF_MS)
    scheduleRetry(delay)
  }

  function startAutoRefresh(reconnecting = retryCount > 0) {
    teardownConnection()

    if (!canConnect()) {
      connectionState.value = 'paused'
      return
    }
    if (typeof EventSource !== 'function') {
      connectionState.value = 'unavailable'
      return
    }

    connectionState.value = reconnecting ? 'reconnecting' : 'connecting'
    /** @type {EventSource} */
    let source
    try {
      source = new EventSource(streamUrl)
    } catch {
      handleConnectionError(null)
      return
    }
    eventSource = source

    source.addEventListener('open', () => {
      if (eventSource !== source) return

      connectionState.value = 'connected'
      clearStabilityTimer()
      stabilityTimer = setTimeout(() => {
        stabilityTimer = null
        if (eventSource === source) {
          retryCount = 0
        }
      }, STABLE_MS)
    })

    source.addEventListener('error', () => {
      handleConnectionError(source)
    })

    source.addEventListener('update', () => {
      if (eventSource === source && canConnect()) {
        load()
      }
    })
  }

  function retryConnection() {
    retryCount = 0
    startAutoRefresh(true)
    return load()
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'visible') {
      startAutoRefresh()
      if (refreshEnabled.value && autoRefresh.value) {
        load()
      }
    } else {
      stopAutoRefresh()
    }
  }

  watch([autoRefresh, refreshEnabled], ([autoRefreshEnabled, enabledNow], [wasAutoRefreshEnabled, wasEnabled]) => {
    const becameEnabled = enabledNow && wasEnabled === false
    const resumedAutoRefresh = autoRefreshEnabled && wasAutoRefreshEnabled === false

    if (!enabledNow || !autoRefreshEnabled) {
      stopAutoRefresh()
      if (becameEnabled && document.visibilityState === 'visible') {
        load()
      }
      return
    }
    startAutoRefresh()
    if ((becameEnabled || resumedAutoRefresh) && document.visibilityState === 'visible') {
      load()
    }
  })

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange)
    if (refreshEnabled.value) {
      load()
    }
    startAutoRefresh()
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    teardownConnection()
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
    retryConnection,
    connectionState
  }
}
