import {computed, ref, unref, watch} from 'vue'

const DEFAULT_CHUNK_SIZE = 200

export function useVisibleItems(source, options = {}) {
  const chunkSize = options.chunkSize || DEFAULT_CHUNK_SIZE
  const initialLimit = options.initialLimit || chunkSize
  const limit = ref(initialLimit)
  const items = computed(() => unref(source) || [])
  const totalCount = computed(() => items.value.length)
  const visibleItems = computed(() => items.value.slice(0, limit.value))
  const shownCount = computed(() => visibleItems.value.length)
  const hiddenCount = computed(() => Math.max(totalCount.value - shownCount.value, 0))
  const hasHiddenItems = computed(() => hiddenCount.value > 0)

  function resetLimit() {
    limit.value = initialLimit
  }

  function showMore() {
    limit.value = Math.min(totalCount.value, limit.value + chunkSize)
  }

  function showAll() {
    limit.value = totalCount.value
  }

  watch(items, resetLimit)

  return {
    chunkSize,
    visibleItems,
    totalCount,
    shownCount,
    hiddenCount,
    hasHiddenItems,
    resetLimit,
    showMore,
    showAll
  }
}
