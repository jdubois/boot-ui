import {computed, onBeforeUnmount, ref} from 'vue'

export const SERVER_PAGE_SIZE = 200

export function useServerPagedList(endpoint, itemsKey, queryParams, options = {}) {
  const pageSize = options.pageSize || SERVER_PAGE_SIZE
  const debounceMs = options.debounceMs || 250
  const data = ref(null)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref(null)

  let requestId = 0
  let timer = null

  const items = computed(() => data.value?.[itemsKey] || [])
  const page = computed(() => data.value?.page || null)
  const shownCount = computed(() => items.value.length)
  const matchedCount = computed(() => page.value?.matched ?? shownCount.value)
  const totalCount = computed(() => page.value?.total ?? matchedCount.value)
  const hiddenCount = computed(() => Math.max(matchedCount.value - shownCount.value, 0))

  function buildUrl(offset) {
    const params = new URLSearchParams()
    const values = queryParams ? queryParams() : {}
    for (const [key, value] of Object.entries(values || {})) {
      if (value !== null && value !== undefined && value !== '') {
        params.set(key, String(value))
      }
    }
    params.set('offset', String(offset))
    params.set('limit', String(pageSize))
    return `${endpoint}?${params.toString()}`
  }

  async function load(options = {}) {
    const append = options.append === true
    const id = options.requestId || ++requestId
    const targetLoading = append ? loadingMore : loading
    const currentItems = append ? items.value : []
    targetLoading.value = true
    error.value = null
    try {
      const res = await fetch(buildUrl(currentItems.length))
      if (!res.ok) throw new Error('HTTP ' + res.status)
      const next = await res.json()
      if (id !== requestId) return
      data.value =
        append && data.value
          ? {
              ...next,
              [itemsKey]: [...currentItems, ...(next[itemsKey] || [])]
            }
          : next
    } catch (e) {
      if (id === requestId) error.value = e.message
    } finally {
      if (id === requestId) targetLoading.value = false
    }
  }

  function scheduleReload() {
    requestId += 1
    const id = requestId
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => load({requestId: id}), debounceMs)
  }

  function loadMore() {
    if (hiddenCount.value > 0 && !loadingMore.value) {
      return load({append: true})
    }
    return Promise.resolve()
  }

  onBeforeUnmount(() => {
    if (timer) clearTimeout(timer)
    requestId += 1
  })

  return {
    data,
    error,
    hiddenCount,
    items,
    load,
    loadMore,
    loading,
    loadingMore,
    matchedCount,
    page,
    pageSize,
    scheduleReload,
    shownCount,
    totalCount
  }
}
