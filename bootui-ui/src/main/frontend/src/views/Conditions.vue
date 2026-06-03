<script setup>
import {apiFetch} from '../api.js'
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import {SERVER_PAGE_SIZE} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

const data = ref(null)
const tab = ref('positive')
const filter = ref('')
const loading = ref(false)
const loadingMore = ref(false)
const error = ref(null)

let requestId = 0
let timer = null

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

async function load(options = {}) {
  const append = options.append === true
  const id = options.requestId || ++requestId
  const targetLoading = append ? loadingMore : loading
  targetLoading.value = true
  error.value = null
  try {
    const res = await apiFetch(buildUrl(append ? entries.value.length : 0))
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const next = await res.json()
    if (id !== requestId) return
    data.value =
      append && data.value
        ? {
            ...next,
            [entriesKey.value]: [...entries.value, ...(next[entriesKey.value] || [])]
          }
        : next
  } catch (e) {
    if (id === requestId) error.value = describeLoadError(e, 'Unable to load conditions')
  } finally {
    if (id === requestId) targetLoading.value = false
  }
}

function scheduleReload() {
  requestId += 1
  const id = requestId
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => load({requestId: id}), 250)
}

function loadMore() {
  if (hiddenCount.value > 0 && !loadingMore.value) {
    return load({append: true})
  }
  return Promise.resolve()
}

onMounted(load)
watch([tab, filter], scheduleReload)
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  requestId += 1
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-check2-circle"
      title="Auto-configuration conditions"
      :error="error"
    />
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
