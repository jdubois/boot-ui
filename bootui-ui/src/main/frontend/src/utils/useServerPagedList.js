import {computed, onBeforeUnmount, ref} from 'vue'
import {apiFetch} from '../api.js'
import {describeLoadError, isAbortError} from './loadError.js'

export const SERVER_PAGE_SIZE = 200

export function useServerPagedList(endpoint, itemsKey, queryParams, options = {}) {
  const pageSize = options.pageSize || SERVER_PAGE_SIZE
  const debounceMs = options.debounceMs || 250
  const errorContext = options.errorContext || 'Unable to load data'
  const data = ref(null)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref(null)

  let baseAc = null
  let appendAc = null
  let timer = null
  let disposed = false

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

  function cancelAppend() {
    if (appendAc) {
      appendAc.abort()
      appendAc = null
      loadingMore.value = false
    }
  }

  async function load(loadOpts = {}) {
    if (disposed) return
    const append = loadOpts.append === true

    if (append) {
      if (loading.value || baseAc || timer) return
      cancelAppend()
      const ac = new AbortController()
      appendAc = ac

      const currentItems = [...items.value]
      const requestUrl = buildUrl(currentItems.length)
      loadingMore.value = true
      error.value = null
      try {
        const res = await apiFetch(requestUrl, {signal: ac.signal})
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const next = await res.json()
        if (appendAc !== ac || requestUrl !== buildUrl(currentItems.length)) return
        data.value = data.value
          ? {
              ...next,
              [itemsKey]: [...currentItems, ...(next[itemsKey] || [])]
            }
          : next
      } catch (e) {
        if (isAbortError(e)) return
        if (appendAc === ac) error.value = describeLoadError(e, errorContext)
      } finally {
        if (appendAc === ac) {
          appendAc = null
          loadingMore.value = false
        }
      }
    } else {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      if (baseAc) {
        baseAc.abort()
        baseAc = null
      }
      cancelAppend()
      const ac = new AbortController()
      baseAc = ac

      const requestUrl = buildUrl(0)
      loading.value = true
      error.value = null
      try {
        const res = await apiFetch(requestUrl, {signal: ac.signal})
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const next = await res.json()
        if (baseAc !== ac || requestUrl !== buildUrl(0)) return
        data.value = next
      } catch (e) {
        if (isAbortError(e)) return
        if (baseAc === ac) error.value = describeLoadError(e, errorContext)
      } finally {
        if (baseAc === ac) {
          baseAc = null
          loading.value = false
        }
      }
    }
  }

  function scheduleReload() {
    if (disposed) return
    if (baseAc) {
      baseAc.abort()
      baseAc = null
    }
    cancelAppend()
    if (timer) clearTimeout(timer)
    loading.value = true
    timer = setTimeout(() => {
      timer = null
      void load()
    }, debounceMs)
  }

  function loadMore() {
    if (hiddenCount.value > 0 && !loading.value && !loadingMore.value) {
      return load({append: true})
    }
    return Promise.resolve()
  }

  onBeforeUnmount(() => {
    disposed = true
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (baseAc) {
      baseAc.abort()
      baseAc = null
    }
    if (appendAc) {
      appendAc.abort()
      appendAc = null
    }
    loading.value = false
    loadingMore.value = false
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
