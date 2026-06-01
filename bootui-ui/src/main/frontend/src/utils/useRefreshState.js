import {computed, ref} from 'vue'

/**
 * Tracks refresh state while keeping the initial skeleton limited to the first completed load attempt.
 *
 * @param {Function} callback - refresh function to run
 * @param {{initialLoading?: boolean}} [options] - refresh state options
 * @returns {{ loading, hasLoaded, initialLoading, refresh }}
 */
export function useRefreshState(callback, {initialLoading = true} = {}) {
  const loading = ref(initialLoading)
  const hasLoaded = ref(false)
  let activeRequests = 0

  const isInitialLoading = computed(() => loading.value && !hasLoaded.value)

  async function refresh(...args) {
    activeRequests += 1
    loading.value = true
    try {
      return await callback(...args)
    } finally {
      hasLoaded.value = true
      activeRequests -= 1
      if (activeRequests === 0) {
        loading.value = false
      }
    }
  }

  return {
    loading,
    hasLoaded,
    initialLoading: isInitialLoading,
    refresh
  }
}
