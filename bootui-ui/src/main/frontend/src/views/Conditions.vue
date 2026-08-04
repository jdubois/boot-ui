<script setup>
import {getJson} from '../api.js'
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {describeLoadError, isAbortError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import {SERVER_PAGE_SIZE} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

const data = ref(null)
const tab = ref('positive')
const filter = ref('')
const loading = ref(false)
const loadingMore = ref(false)
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
    <PanelHeader icon="bi-check2-circle" title="Auto-configuration conditions" :error="error" />
    <ul class="nav nav-tabs mb-3">
      <li class="nav-item">
        <a :class="{active: tab === 'positive'}" class="nav-link" href="#" @click.prevent="tab = 'positive'">
          Positive ({{ counts.positiveMatched || 0 }})
        </a>
      </li>
      <li class="nav-item">
        <a :class="{active: tab === 'negative'}" class="nav-link" href="#" @click.prevent="tab = 'negative'">
          Negative ({{ counts.negativeMatched || 0 }})
        </a>
      </li>
    </ul>
    <input v-model="filter" class="form-control mb-3" placeholder="Filter…" />
    <p class="small text-muted">{{ matchedCount }} of {{ totalCount }} {{ tab }} entries matched</p>
    <div v-for="e in entries" :key="e.autoConfigurationClass + e.condition + e.message" class="mb-2">
      <div class="d-flex">
        <span :class="tab === 'positive' ? 'bg-success' : 'bg-secondary'" class="badge me-2">{{ e.outcome }}</span>
        <div>
          <strong>{{ e.autoConfigurationClass }}</strong>
          <div class="small text-muted">{{ e.condition }}</div>
          <div class="small">{{ e.message }}</div>
        </div>
      </div>
    </div>
    <div v-if="!loading && matchedCount === 0" class="text-muted py-3">No {{ tab }} entries match your filter.</div>
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
</template>
