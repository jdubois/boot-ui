<script setup>
import {getJson} from '../api.js'
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {describeLoadError, isAbortError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import PanelTabs from './components/PanelTabs.vue'
import {SERVER_PAGE_SIZE} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

const data = ref(null)
const tab = ref('positive')
const filter = ref('')
const loading = ref(false)
const loadingMore = ref(false)
const hasLoaded = ref(false)
const error = ref(null)

let baseAc = null
let appendAc = null
let timer = null
let disposed = false

const entriesKey = computed(() => (tab.value === 'positive' ? 'positiveMatches' : 'negativeMatches'))
const entries = computed(() => data.value?.[entriesKey.value] || [])
const counts = computed(() => data.value?.counts || {})
const matchedCount = computed(() =>
  tab.value === 'positive' ? counts.value.positiveMatched || 0 : counts.value.negativeMatched || 0
)
const conditionTabs = computed(() => [
  {id: 'positive', label: `Positive (${counts.value.positiveMatched || 0})`},
  {id: 'negative', label: `Negative (${counts.value.negativeMatched || 0})`}
])
const totalCount = computed(() =>
  tab.value === 'positive' ? counts.value.positiveTotal || 0 : counts.value.negativeTotal || 0
)
const shownCount = computed(() => entries.value.length)
const hiddenCount = computed(() => Math.max(matchedCount.value - shownCount.value, 0))

function buildUrl(offset) {
  const params = new URLSearchParams()
  params.set('outcome', tab.value)
  params.set('offset', String(offset))
  params.set('limit', String(SERVER_PAGE_SIZE))
  if (filter.value.trim()) params.set('q', filter.value.trim())
  return `api/conditions?${params.toString()}`
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

    const key = entriesKey.value
    const currentEntries = [...entries.value]
    const requestUrl = buildUrl(currentEntries.length)
    loadingMore.value = true
    error.value = null
    try {
      const next = await getJson(requestUrl, {signal: ac.signal})
      if (appendAc !== ac || requestUrl !== buildUrl(currentEntries.length)) return
      data.value = data.value
        ? {
            ...next,
            [key]: [...currentEntries, ...(next[key] || [])]
          }
        : next
    } catch (e) {
      if (isAbortError(e)) return
      if (appendAc === ac) error.value = describeLoadError(e, 'Unable to load conditions')
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
      const next = await getJson(requestUrl, {signal: ac.signal})
      if (baseAc !== ac || requestUrl !== buildUrl(0)) return
      data.value = next
    } catch (e) {
      if (isAbortError(e)) return
      if (baseAc === ac) error.value = describeLoadError(e, 'Unable to load conditions')
    } finally {
      if (baseAc === ac) {
        baseAc = null
        loading.value = false
        hasLoaded.value = true
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
  }, 250)
}

function loadMore() {
  if (hiddenCount.value > 0 && !loading.value && !loadingMore.value) {
    return load({append: true})
  }
  return Promise.resolve()
}

onMounted(load)
watch([tab, filter], scheduleReload)
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
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-check2-circle"
      title="Auto-configuration conditions"
      :error="error"
      :loading="loading"
      @refresh="load"
    />
    <PanelTabs
      class="mb-3"
      :tabs="conditionTabs"
      :selected="tab"
      id-prefix="conditions"
      label="Condition outcomes"
      @select="tab = $event"
    />
    <div :id="`conditions-panel-${tab}`" :aria-labelledby="`conditions-tab-${tab}`" role="tabpanel">
      <input v-model="filter" aria-label="Filter conditions" class="form-control mb-3" placeholder="Filter…" />
      <PanelSkeleton v-if="loading && !hasLoaded" :rows="6" />
      <template v-else>
        <p class="small text-muted">{{ matchedCount }} of {{ totalCount }} {{ tab }} entries matched</p>
        <div v-for="e in entries" :key="e.autoConfigurationClass + e.condition + e.message" class="mb-2">
          <div class="d-flex gap-2">
            <span
              :class="tab === 'positive' ? 'bg-success' : 'bg-secondary'"
              class="badge align-self-start flex-shrink-0"
              >{{ e.outcome }}</span
            >
            <div class="min-w-0">
              <code class="fw-semibold bootui-break-anywhere">{{ e.autoConfigurationClass }}</code>
              <div class="small text-muted font-monospace bootui-break-anywhere">{{ e.condition }}</div>
              <div class="small bootui-break-anywhere">{{ e.message }}</div>
            </div>
          </div>
        </div>
        <div v-if="matchedCount === 0 && totalCount === 0" class="text-muted py-3">
          No {{ tab }} condition entries were reported.
        </div>
        <div v-else-if="!loading && matchedCount === 0" class="text-muted py-3">
          No {{ tab }} entries match your filter.
        </div>
      </template>
      <ServerListFooter
        v-if="!loading"
        :loading="loadingMore"
        :matched="matchedCount"
        :page-size="SERVER_PAGE_SIZE"
        :shown="shownCount"
        :total="totalCount"
        item-label="condition entries"
        @load-more="loadMore"
      />
    </div>
  </div>
</template>

<style scoped>
.min-w-0 {
  min-width: 0;
}
</style>
