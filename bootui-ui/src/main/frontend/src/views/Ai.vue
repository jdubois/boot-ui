<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {formatDuration, formatNumber, formatRelative, formatTime} from '../utils/format.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard'
import AiSetupChecklist from './components/AiSetupChecklist.vue'

const overview = ref(null)
const series = ref(null)
const detail = ref(null)
const loading = ref(true)
const error = ref(null)
const selectedSpanId = ref(null)
const detailLoading = ref(false)
const lastUpdated = ref(null)
const autoRefresh = ref(true)
let refreshInterval = null

function startAutoRefresh() {
  stopAutoRefresh()
  if (autoRefresh.value) {
    refreshInterval = setInterval(() => {
      if (document.visibilityState === 'visible') load()
    }, 10000)
  }
}

function stopAutoRefresh() {
  if (refreshInterval) {
    clearInterval(refreshInterval)
    refreshInterval = null
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible' && autoRefresh.value) {
    load()
  }
}

function onAutoRefreshChange() {
  if (autoRefresh.value) {
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
}

const isStale = computed(() => {
  if (autoRefresh.value || !lastUpdated.value) return false
  return Date.now() - lastUpdated.value > 30_000
})

const lastUpdatedText = computed(() => {
  if (!lastUpdated.value) return null
  return formatRelative(lastUpdated.value)
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const [ovRes, tsRes] = await Promise.all([
      fetch('api/ai/overview'),
      fetch('api/ai/tokens?minutes=' + windowMinutes.value)
    ])
    if (!ovRes.ok) throw new Error('HTTP ' + ovRes.status)
    overview.value = await ovRes.json()
    if (tsRes.ok) {
      series.value = await tsRes.json()
    }
    lastUpdated.value = Date.now()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function exportCsv() {
  const rows = filteredChats.value
  const headers = [
    'started',
    'provider',
    'model',
    'inputTokens',
    'outputTokens',
    'totalTokens',
    'durationMs',
    'status',
    'finishReason',
    'traceId',
    'spanId'
  ]
  const lines = [
    headers.join(','),
    ...rows.map((c) =>
      [
        c.startEpochNanos ? new Date(Math.floor(c.startEpochNanos / 1_000_000)).toISOString() : '',
        c.provider || '',
        c.requestModel || '',
        c.inputTokens ?? '',
        c.outputTokens ?? '',
        c.totalTokens ?? '',
        c.durationNanos != null ? (c.durationNanos / 1_000_000).toFixed(2) : '',
        c.statusCode || '',
        c.finishReason || '',
        c.traceId || '',
        c.spanId || ''
      ]
        .map((v) => JSON.stringify(String(v)))
        .join(',')
    )
  ]
  const blob = new Blob([lines.join('\n')], {type: 'text/csv'})
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'ai-chats.csv'
  a.click()
  URL.revokeObjectURL(url)
}

async function openChat(spanId) {
  selectedSpanId.value = spanId
  detail.value = null
  detailLoading.value = true
  try {
    const res = await fetch('api/ai/chats/' + spanId)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    detail.value = await res.json()
  } catch (e) {
    detail.value = {error: e.message}
  } finally {
    detailLoading.value = false
  }
}

function toggleChat(spanId) {
  if (spanId === selectedSpanId.value) {
    closeDrawer()
    return
  }
  openChat(spanId)
}

function closeDrawer() {
  selectedSpanId.value = null
  detail.value = null
}

const tableSearch = ref('')
const providerFilter = ref('')
const modelFilter = ref('')
const statusFilter = ref('')
const tableSort = ref('startEpochNanos')
const tableSortDir = ref('desc')
const pageSize = ref(25)

function sortTable(col) {
  if (tableSort.value === col) {
    tableSortDir.value = tableSortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    tableSort.value = col
    tableSortDir.value = 'desc'
  }
}

const distinctProviders = computed(() => {
  if (!overview.value || !overview.value.recent) return []
  return [...new Set(overview.value.recent.map((c) => c.provider).filter(Boolean))].sort()
})

const distinctModels = computed(() => {
  if (!overview.value || !overview.value.recent) return []
  return [...new Set(overview.value.recent.map((c) => c.requestModel).filter(Boolean))].sort()
})

const filteredChats = computed(() => {
  if (!overview.value || !overview.value.recent) return []
  const q = tableSearch.value.trim().toLowerCase()
  let rows = overview.value.recent.filter((c) => {
    if (providerFilter.value && c.provider !== providerFilter.value) return false
    if (modelFilter.value && c.requestModel !== modelFilter.value) return false
    if (statusFilter.value && c.statusCode !== statusFilter.value) return false
    if (q) {
      const hay = ((c.requestModel || '') + ' ' + (c.provider || '') + ' ' + (c.spanId || '')).toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
  const col = tableSort.value
  const dir = tableSortDir.value === 'asc' ? 1 : -1
  rows = [...rows].sort((a, b) => {
    let av = a[col] ?? 0
    let bv = b[col] ?? 0
    if (col === 'totalTokens') {
      av = (a.inputTokens || 0) + (a.outputTokens || 0)
      bv = (b.inputTokens || 0) + (b.outputTokens || 0)
    }
    return typeof av === 'string' ? av.localeCompare(bv) * dir : (av - bv) * dir
  })
  return rows
})

const pagedChats = computed(() => filteredChats.value.slice(0, pageSize.value))

const {copiedKey, copyToClipboard} = useCopyToClipboard()

function durationClass(nanos) {
  if (nanos == null) return ''
  const ms = nanos / 1_000_000
  if (ms > 10000) return 'text-danger'
  if (ms > 2000) return 'text-warning'
  return ''
}

const groupedAttributes = computed(() => {
  if (!detail.value || !detail.value.attributes || !detail.value.attributes.length) return []
  const groups = {}
  for (const a of detail.value.attributes) {
    const dot = a.key.indexOf('.')
    const ns = dot > 0 ? a.key.slice(0, dot) : '(other)'
    if (!groups[ns]) groups[ns] = []
    groups[ns].push(a)
  }
  return Object.entries(groups).sort((a, b) => a[0].localeCompare(b[0]))
})

const miniTimeline = computed(() => {
  if (!detail.value || !detail.value.summary) return null
  const s = detail.value.summary
  if (!s.startEpochNanos || !s.durationNanos) return null
  const totalDuration = s.durationNanos
  const width = 400
  const height = 30
  const baseY = 10
  const barH = 8

  function toX(relNanos) {
    return (relNanos / totalDuration) * width
  }

  const bars = []
  // base chat span bar
  bars.push({x: 0, w: width, y: baseY, h: barH, color: '#dee2e6', title: 'Chat span'})

  const children = []
  if (detail.value.toolCalls) {
    for (const tc of detail.value.toolCalls) {
      if (tc.startEpochNanos && tc.durationNanos) {
        const rel = tc.startEpochNanos - s.startEpochNanos
        children.push({
          x: Math.max(0, toX(rel)),
          w: Math.max(2, toX(tc.durationNanos)),
          y: baseY,
          h: barH,
          color: '#0d6efd',
          title: (tc.name || 'tool') + ' ' + (tc.durationNanos / 1_000_000).toFixed(1) + 'ms'
        })
      }
    }
  }
  if (detail.value.vectorOperations) {
    for (const vo of detail.value.vectorOperations) {
      if (vo.startEpochNanos && vo.durationNanos) {
        const rel = vo.startEpochNanos - s.startEpochNanos
        children.push({
          x: Math.max(0, toX(rel)),
          w: Math.max(2, toX(vo.durationNanos)),
          y: baseY,
          h: barH,
          color: '#fd7e14',
          title: (vo.operation || 'vector') + ' ' + (vo.durationNanos / 1_000_000).toFixed(1) + 'ms'
        })
      }
    }
  }

  return {width, height, bars: [...bars, ...children]}
})

const byModelSort = ref('totalTokens')
const byModelSortDir = ref('desc')

function sortByModel(col) {
  if (byModelSort.value === col) {
    byModelSortDir.value = byModelSortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    byModelSort.value = col
    byModelSortDir.value = 'desc'
  }
}

const byModel = computed(() => {
  if (!overview.value) return []
  const tokens = overview.value.tokensByModel || {}
  const calls = overview.value.callsByModel || {}
  const allModels = new Set([...Object.keys(tokens), ...Object.keys(calls)])
  const rows = Array.from(allModels).map((model) => {
    const totalTokens = tokens[model] || 0
    const c = calls[model] || 0
    return {model, calls: c, totalTokens, avgTokens: c > 0 ? Math.round(totalTokens / c) : 0}
  })
  const maxTokens = rows.reduce((m, r) => Math.max(m, r.totalTokens), 1)
  const col = byModelSort.value
  const dir = byModelSortDir.value === 'asc' ? 1 : -1
  rows.sort((a, b) => {
    const av = a[col] ?? 0
    const bv = b[col] ?? 0
    return typeof av === 'string' ? av.localeCompare(bv) * dir : (av - bv) * dir
  })
  return rows.map((r) => ({...r, pct: Math.round((r.totalTokens / maxTokens) * 100)}))
})

const windowMinutes = ref(60)
const tooltipData = ref(null)
const chartContainerRef = ref(null)

async function loadTokenSeries() {
  const res = await fetch('api/ai/tokens?minutes=' + windowMinutes.value)
  if (res.ok) series.value = await res.json()
}

async function onWindowChange() {
  await loadTokenSeries()
}

const chart = computed(() => {
  if (!series.value || !series.value.buckets || series.value.buckets.length === 0) return null
  const buckets = series.value.buckets
  const n = buckets.length
  const width = 600
  const height = 80
  const step = width / Math.max(n - 1, 1)

  const maxTokens = buckets.reduce((m, b) => {
    const t = (b.inputTokens || 0) + (b.outputTokens || 0)
    return t > m ? t : m
  }, 1)
  const maxCalls = buckets.reduce((m, b) => Math.max(m, b.callCount || 0), 1)
  const totalCalls = buckets.reduce((s, b) => s + (b.callCount || 0), 0)

  const xs = buckets.map((_, i) => i * step)
  const inputTops = buckets.map((b) => height - ((b.inputTokens || 0) / maxTokens) * height)
  const stackedTops = buckets.map((b) => height - (((b.inputTokens || 0) + (b.outputTokens || 0)) / maxTokens) * height)
  const callYs = buckets.map((b) => height - ((b.callCount || 0) / maxCalls) * height)

  // Build input area polygon (bottom area)
  const inputAreaPts = xs.map((x, i) => `${x},${inputTops[i]}`).join(' ') + ` ${xs[n - 1]},${height} ${xs[0]},${height}`

  // Build output area polygon (stacked on top of input)
  const outputAreaPts =
    xs.map((x, i) => `${x},${stackedTops[i]}`).join(' ') +
    ' ' +
    xs.map((x, i) => `${xs[n - 1 - i]},${inputTops[n - 1 - i]}`).join(' ')

  const inputLinePts = xs.map((x, i) => `${x},${inputTops[i]}`).join(' ')
  const outputLinePts = xs.map((x, i) => `${x},${stackedTops[i]}`).join(' ')
  const callLinePts = xs.map((x, i) => `${x},${callYs[i]}`).join(' ')

  const halfY = height / 2
  const firstTime = new Date(buckets[0].epochMinute * 60000).toLocaleTimeString()
  const midTime = new Date(buckets[Math.floor((n - 1) / 2)].epochMinute * 60000).toLocaleTimeString()
  const lastTime = new Date(buckets[n - 1].epochMinute * 60000).toLocaleTimeString()

  return {
    width,
    height,
    maxTokens,
    totalCalls,
    inputAreaPts,
    outputAreaPts,
    inputLinePts,
    outputLinePts,
    callLinePts,
    halfY,
    firstTime,
    midTime,
    lastTime,
    buckets,
    xs
  }
})

function onChartMousemove(event) {
  if (!chart.value) return
  const rect = event.currentTarget.getBoundingClientRect()
  const relX = ((event.clientX - rect.left) / rect.width) * chart.value.width
  const idx = chart.value.xs.reduce(
    (best, x, i) => (Math.abs(x - relX) < Math.abs(chart.value.xs[best] - relX) ? i : best),
    0
  )
  const b = chart.value.buckets[idx]
  tooltipData.value = {
    idx,
    x: (chart.value.xs[idx] / chart.value.width) * 100,
    time: new Date(b.epochMinute * 60000).toLocaleTimeString(),
    input: b.inputTokens || 0,
    output: b.outputTokens || 0,
    calls: b.callCount || 0
  }
}

function onChartMouseleave() {
  tooltipData.value = null
}

const avgLatency = computed(() => {
  const recent = overview.value && overview.value.recent
  if (!recent || recent.length === 0) return null
  const sum = recent.reduce((s, c) => s + (c.durationNanos || 0), 0)
  return sum / recent.length
})

const errorRate = computed(() => {
  const recent = overview.value && overview.value.recent
  if (!recent || recent.length === 0) return null
  const errors = recent.filter((c) => c.statusCode === 'ERROR').length
  return (errors / recent.length) * 100
})

const hasAnyData = computed(() => overview.value && overview.value.totalChats > 0)

onMounted(() => {
  load()
  startAutoRefresh()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stopAutoRefresh()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-cpu me-2"></i>AI Usage</h2>
        <div v-if="overview" class="text-muted small">
          <span v-if="overview.springAiDetected" class="badge text-bg-success me-1">Spring AI detected</span>
          <span v-else class="badge text-bg-secondary me-1">Spring AI not on classpath</span>
          <span v-if="!overview.enabled" class="badge text-bg-warning me-1">Telemetry disabled</span>
        </div>
      </div>
      <div class="d-flex align-items-center gap-2 flex-wrap">
        <span v-if="lastUpdatedText" class="text-muted small">Updated {{ lastUpdatedText }}</span>
        <span v-if="isStale" class="badge text-bg-warning">Data may be stale</span>
        <div class="form-check form-switch mb-0">
          <input
            id="autoRefreshToggle"
            v-model="autoRefresh"
            class="form-check-input"
            type="checkbox"
            @change="onAutoRefreshChange"
          />
          <label class="form-check-label small" for="autoRefreshToggle">Auto-refresh</label>
        </div>
        <button v-if="overview && hasAnyData" class="btn btn-sm btn-outline-secondary" @click="exportCsv">
          <i class="bi bi-download"></i> Export CSV
        </button>
        <button :disabled="loading" class="btn btn-sm btn-outline-secondary" @click="load">
          <i class="bi bi-arrow-clockwise"></i> Refresh
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-muted">Loading AI usage…</div>
    <div v-else-if="error" class="alert alert-warning">
      <div class="fw-semibold">Could not load AI usage data</div>
      <details class="mt-1">
        <summary class="small">Details</summary>
        <code class="small">{{ error }}</code>
      </details>
      <button class="btn btn-sm btn-outline-secondary mt-2" @click="load">Retry</button>
    </div>

    <template v-else-if="overview">
      <div v-if="overview.contentBanner && hasAnyData" class="alert alert-info small">
        <i class="bi bi-info-circle me-1"></i>{{ overview.contentBanner }}
      </div>

      <AiSetupChecklist
        v-if="!overview.enabled || !overview.springAiDetected"
        :enabled="overview.enabled"
        :has-data="hasAnyData"
        :spring-ai-detected="overview.springAiDetected"
      />

      <div v-else-if="!hasAnyData" class="card mb-3 border-info-subtle">
        <div class="card-body d-flex align-items-start gap-3">
          <span class="text-info fs-3"><i class="bi bi-broadcast-pin"></i></span>
          <div>
            <div class="d-flex align-items-center gap-2 flex-wrap mb-1">
              <h5 class="mb-0">No AI chat completions recorded yet</h5>
              <span class="badge text-bg-info">Telemetry ready</span>
            </div>
            <p class="text-muted mb-0">
              BootUI's telemetry capture is active and Spring AI is detected. Exercise a Spring AI chat flow; this panel
              will refresh automatically when the first completion span arrives.
            </p>
          </div>
        </div>
      </div>

      <template v-else>
        <div class="row row-cols-2 row-cols-md-3 row-cols-xl-6 g-3 mb-3">
          <div class="col">
            <div class="card h-100">
              <div class="card-body kpi-card-body">
                <div class="text-muted small"><i class="bi bi-chat-dots me-1"></i>Chats</div>
                <div class="fs-3 fw-semibold">{{ formatNumber(overview.totalChats) }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body kpi-card-body">
                <div class="text-muted small"><i class="bi bi-coin me-1"></i>Total tokens</div>
                <div class="fs-3 fw-semibold">
                  {{ formatNumber((overview.totalInputTokens || 0) + (overview.totalOutputTokens || 0)) }}
                </div>
                <small class="text-muted"
                  >{{ formatNumber(overview.totalInputTokens) }} in ·
                  {{ formatNumber(overview.totalOutputTokens) }} out</small
                >
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body kpi-card-body">
                <div class="text-muted small"><i class="bi bi-stopwatch me-1"></i>Avg latency</div>
                <div class="fs-3 fw-semibold">{{ avgLatency != null ? formatDuration(avgLatency) : '—' }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div :class="['card-body', 'kpi-card-body', errorRate > 0 ? 'text-danger' : '']">
                <div class="text-muted small"><i class="bi bi-exclamation-triangle me-1"></i>Error rate</div>
                <div class="fs-3 fw-semibold">{{ errorRate != null ? errorRate.toFixed(1) + '%' : '—' }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body kpi-card-body">
                <div class="text-muted small"><i class="bi bi-tools me-1"></i>Tool calls</div>
                <div class="fs-3 fw-semibold">{{ formatNumber(overview.toolCallCount) }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body kpi-card-body">
                <div class="text-muted small"><i class="bi bi-database me-1"></i>Vector ops</div>
                <div class="fs-3 fw-semibold">{{ formatNumber(overview.vectorOperationCount) }}</div>
                <small class="text-muted">+ {{ formatNumber(overview.embeddingCount) }} embeddings</small>
              </div>
            </div>
          </div>
        </div>

        <div v-if="chart" class="card mb-3">
          <div class="card-body">
            <div class="d-flex justify-content-between align-items-center mb-2">
              <h6 class="mb-0">Token usage (last {{ series.minutes }} min)</h6>
              <select v-model="windowMinutes" class="form-select form-select-sm w-auto" @change="onWindowChange">
                <option :value="15">15 min</option>
                <option :value="60">60 min</option>
                <option :value="240">240 min</option>
              </select>
            </div>
            <div
              ref="chartContainerRef"
              class="position-relative"
              @mouseleave="onChartMouseleave"
              @mousemove="onChartMousemove"
            >
              <svg
                :aria-label="'Token usage over the last ' + windowMinutes + ' minutes'"
                :viewBox="'0 0 ' + chart.width + ' ' + chart.height"
                class="w-100"
                role="img"
                style="max-height: 100px"
              >
                <line :x1="0" :x2="chart.width" :y1="chart.halfY" :y2="chart.halfY" stroke="#dee2e6" stroke-width="1" />
                <text :x="2" :y="8" fill="#6c757d" font-size="9">{{ formatNumber(chart.maxTokens) }}</text>
                <text :x="2" :y="chart.halfY - 2" fill="#6c757d" font-size="9">
                  {{ formatNumber(Math.round(chart.maxTokens / 2)) }}
                </text>
                <text :x="2" :y="chart.height - 1" fill="#6c757d" font-size="9">0</text>
                <polygon :points="chart.inputAreaPts" fill="#0d6efd" fill-opacity="0.6" />
                <polygon :points="chart.outputAreaPts" fill="#6610f2" fill-opacity="0.6" />
                <polyline :points="chart.outputLinePts" fill="none" stroke="#6610f2" stroke-width="1.5" />
                <polyline
                  :points="chart.callLinePts"
                  fill="none"
                  stroke="#198754"
                  stroke-dasharray="4 2"
                  stroke-width="1.5"
                />
                <line
                  v-if="tooltipData"
                  :x1="(tooltipData.x / 100) * chart.width"
                  :x2="(tooltipData.x / 100) * chart.width"
                  :y1="0"
                  :y2="chart.height"
                  aria-hidden="true"
                  stroke="#adb5bd"
                  stroke-width="1"
                />
              </svg>
              <div
                v-if="tooltipData"
                :style="{left: tooltipData.x + '%'}"
                class="position-absolute bg-white border rounded p-1 small shadow-sm"
                style="top: 0; transform: translateX(-50%); pointer-events: none; white-space: nowrap; z-index: 10"
              >
                <div class="fw-semibold">{{ tooltipData.time }}</div>
                <div style="color: #0d6efd">In: {{ tooltipData.input }}</div>
                <div style="color: #6610f2">Out: {{ tooltipData.output }}</div>
                <div style="color: #198754">Calls: {{ tooltipData.calls }}</div>
              </div>
            </div>
            <div class="d-flex justify-content-between text-muted small mt-1 px-1">
              <span>{{ chart.firstTime }}</span>
              <span>{{ chart.midTime }}</span>
              <span>{{ chart.lastTime }}</span>
            </div>
            <div class="text-muted small mt-1">
              Peak {{ formatNumber(chart.maxTokens) }} tokens/min · {{ formatNumber(chart.totalCalls) }} calls
            </div>
          </div>
        </div>

        <div class="card mb-3">
          <div class="card-body">
            <h6>Usage by model</h6>
            <div class="table-responsive">
              <table class="table table-sm mb-0">
                <caption class="visually-hidden">
                  Usage by model
                </caption>
                <thead>
                  <tr>
                    <th scope="col" class="cursor-pointer user-select-none" @click="sortByModel('model')">
                      Model
                      <i
                        v-if="byModelSort === 'model'"
                        :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                        class="bi"
                      ></i>
                    </th>
                    <th scope="col" class="text-end cursor-pointer user-select-none" @click="sortByModel('calls')">
                      Calls
                      <i
                        v-if="byModelSort === 'calls'"
                        :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                        class="bi"
                      ></i>
                    </th>
                    <th
                      scope="col"
                      class="text-end cursor-pointer user-select-none"
                      @click="sortByModel('totalTokens')"
                    >
                      Total tokens
                      <i
                        v-if="byModelSort === 'totalTokens'"
                        :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                        class="bi"
                      ></i>
                    </th>
                    <th scope="col" class="text-end cursor-pointer user-select-none" @click="sortByModel('avgTokens')">
                      Avg tokens/call
                      <i
                        v-if="byModelSort === 'avgTokens'"
                        :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                        class="bi"
                      ></i>
                    </th>
                    <th scope="col"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in byModel" :key="row.model">
                    <td>
                      <code
                        :title="row.model"
                        class="text-truncate d-inline-block align-middle"
                        style="max-width: 20ch"
                        >{{ row.model }}</code
                      >
                    </td>
                    <td class="text-end">{{ formatNumber(row.calls) }}</td>
                    <td class="text-end">{{ formatNumber(row.totalTokens) }}</td>
                    <td class="text-end">{{ formatNumber(row.avgTokens) }}</td>
                    <td style="width: 30%">
                      <div class="progress" style="height: 6px">
                        <div :style="{width: row.pct + '%'}" class="progress-bar" role="progressbar"></div>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <h5>Recent chats</h5>

        <div class="row g-2 mb-2">
          <div class="col-md-4">
            <input
              v-model="tableSearch"
              class="form-control form-control-sm"
              placeholder="Search model, provider, span…"
              type="search"
            />
          </div>
          <div class="col-md-2">
            <select v-model="providerFilter" class="form-select form-select-sm">
              <option value="">All providers</option>
              <option v-for="p in distinctProviders" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="col-md-3">
            <select v-model="modelFilter" class="form-select form-select-sm">
              <option value="">All models</option>
              <option v-for="m in distinctModels" :key="m" :value="m">{{ m }}</option>
            </select>
          </div>
          <div class="col-md-2">
            <select v-model="statusFilter" class="form-select form-select-sm">
              <option value="">All statuses</option>
              <option value="OK">OK</option>
              <option value="ERROR">ERROR</option>
            </select>
          </div>
        </div>

        <div class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <caption class="visually-hidden">
              Recent AI chats
            </caption>
            <thead>
              <tr>
                <th scope="col" class="cursor-pointer user-select-none" @click="sortTable('startEpochNanos')">
                  Started
                  <i
                    v-if="tableSort === 'startEpochNanos'"
                    :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                    class="bi"
                  ></i>
                </th>
                <th scope="col" class="cursor-pointer user-select-none" @click="sortTable('provider')">
                  Provider
                  <i
                    v-if="tableSort === 'provider'"
                    :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                    class="bi"
                  ></i>
                </th>
                <th scope="col" class="cursor-pointer user-select-none" @click="sortTable('requestModel')">
                  Model
                  <i
                    v-if="tableSort === 'requestModel'"
                    :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                    class="bi"
                  ></i>
                </th>
                <th scope="col" class="cursor-pointer user-select-none" @click="sortTable('totalTokens')">
                  Tokens
                  <i
                    v-if="tableSort === 'totalTokens'"
                    :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                    class="bi"
                  ></i>
                </th>
                <th scope="col" class="cursor-pointer user-select-none" @click="sortTable('durationNanos')">
                  Duration
                  <i
                    v-if="tableSort === 'durationNanos'"
                    :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                    class="bi"
                  ></i>
                </th>
                <th scope="col">Status</th>
                <th scope="col">Finish reason</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="chat in pagedChats" :key="chat.spanId">
                <tr :class="{'table-active': chat.spanId === selectedSpanId}">
                  <td class="small">
                    <div>{{ formatTime(chat.startEpochNanos) }}</div>
                    <small class="text-muted">{{
                      formatRelative(chat.startEpochNanos ? Math.floor(chat.startEpochNanos / 1_000_000) : null)
                    }}</small>
                  </td>
                  <td>{{ chat.provider || '—' }}</td>
                  <td>
                    <code
                      :title="chat.requestModel"
                      class="text-truncate d-inline-block align-middle"
                      style="max-width: 16ch"
                      >{{ chat.requestModel || '—' }}</code
                    >
                  </td>
                  <td>{{ formatNumber((chat.inputTokens || 0) + (chat.outputTokens || 0)) }}</td>
                  <td :class="durationClass(chat.durationNanos)">{{ formatDuration(chat.durationNanos) }}</td>
                  <td>
                    <span v-if="chat.statusCode === 'ERROR'" class="badge text-bg-danger">error</span>
                    <span v-else class="badge text-bg-success">ok</span>
                  </td>
                  <td>
                    <span v-if="chat.finishReason" class="badge text-bg-light">{{ chat.finishReason }}</span>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td class="text-end text-nowrap">
                    <button
                      :class="copiedKey === chat.spanId ? 'btn-success' : 'btn-outline-secondary'"
                      :title="copiedKey === chat.spanId ? 'Copied!' : 'Copy span id'"
                      class="btn btn-sm me-1"
                      @click="copyToClipboard(chat.spanId, chat.spanId)"
                    >
                      <i :class="copiedKey === chat.spanId ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
                    </button>
                    <a :href="'#/traces'" class="btn btn-sm btn-outline-secondary me-1" title="View trace">
                      <i class="bi bi-bezier2"></i>
                    </a>
                    <button
                      :aria-expanded="chat.spanId === selectedSpanId"
                      aria-label="Toggle chat details"
                      class="btn btn-sm btn-outline-primary"
                      @click="toggleChat(chat.spanId)"
                    >
                      <i :class="chat.spanId === selectedSpanId ? 'bi-chevron-up' : 'bi-chevron-down'" class="bi"></i>
                    </button>
                  </td>
                </tr>
                <tr v-if="chat.spanId === selectedSpanId" class="chat-detail-row">
                  <td class="p-0" colspan="8">
                    <div class="card m-2">
                      <div class="card-header d-flex justify-content-between align-items-center">
                        <div>
                          <i class="bi bi-stars me-2"></i>Chat
                          <code>{{ selectedSpanId }}</code>
                          <button
                            :title="copiedKey === selectedSpanId ? 'Copied!' : 'Copy span id'"
                            class="btn btn-sm btn-link p-0 ms-2"
                            @click="copyToClipboard(selectedSpanId, selectedSpanId)"
                          >
                            <i :class="copiedKey === selectedSpanId ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
                          </button>
                        </div>
                        <button class="btn btn-sm btn-outline-secondary" @click="closeDrawer">
                          <i class="bi bi-x"></i> Close
                        </button>
                      </div>
                      <div class="card-body">
                        <div v-if="detailLoading" class="text-muted">Loading…</div>
                        <template v-else-if="detail && detail.summary">
                          <div v-if="detail.contentBanner && !detail.contentCaptured" class="alert alert-info small">
                            <i class="bi bi-info-circle me-1"></i>{{ detail.contentBanner }}
                          </div>
                          <dl class="row mb-3">
                            <dt class="col-sm-3">Provider</dt>
                            <dd class="col-sm-9">{{ detail.summary.provider || '—' }}</dd>
                            <dt class="col-sm-3">Request model</dt>
                            <dd class="col-sm-9">
                              <code>{{ detail.summary.requestModel || '—' }}</code>
                            </dd>
                            <dt class="col-sm-3">Response model</dt>
                            <dd class="col-sm-9">
                              <code>{{ detail.summary.responseModel || '—' }}</code>
                            </dd>
                            <dt class="col-sm-3">Tokens</dt>
                            <dd class="col-sm-9">
                              in {{ formatNumber(detail.summary.inputTokens) }} · out
                              {{ formatNumber(detail.summary.outputTokens) }} · total
                              {{ formatNumber(detail.summary.totalTokens) }}
                            </dd>
                            <dt class="col-sm-3">Duration</dt>
                            <dd class="col-sm-9">{{ formatDuration(detail.summary.durationNanos) }}</dd>
                            <dt class="col-sm-3">Finish reason</dt>
                            <dd class="col-sm-9">{{ detail.summary.finishReason || '—' }}</dd>
                          </dl>

                          <div v-if="miniTimeline" class="mb-3">
                            <h6>Span timeline</h6>
                            <svg
                              :viewBox="'0 0 ' + miniTimeline.width + ' ' + miniTimeline.height"
                              class="w-100"
                              style="max-height: 30px"
                            >
                              <rect
                                v-for="(bar, bi) in miniTimeline.bars"
                                :key="bi"
                                :fill="bar.color"
                                :height="bar.h"
                                :width="bar.w"
                                :x="bar.x"
                                :y="bar.y"
                              >
                                <title>{{ bar.title }}</title>
                              </rect>
                            </svg>
                          </div>

                          <div v-if="detail.toolCalls && detail.toolCalls.length" class="mb-3">
                            <h6>Tool calls</h6>
                            <ul class="list-group">
                              <li
                                v-for="tc in detail.toolCalls"
                                :key="tc.spanId"
                                class="list-group-item d-flex justify-content-between"
                              >
                                <span
                                  ><i class="bi bi-tools me-1"></i><code>{{ tc.name || '(unnamed)' }}</code></span
                                >
                                <span class="text-muted small">{{ formatDuration(tc.durationNanos) }}</span>
                              </li>
                            </ul>
                          </div>

                          <div v-if="detail.vectorOperations && detail.vectorOperations.length" class="mb-3">
                            <h6>Vector operations</h6>
                            <ul class="list-group">
                              <li
                                v-for="vo in detail.vectorOperations"
                                :key="vo.spanId"
                                class="list-group-item d-flex justify-content-between"
                              >
                                <span
                                  ><i class="bi bi-database me-1"></i><code>{{ vo.collectionName || '?' }}</code> ·
                                  {{ vo.operation || '—' }}</span
                                >
                                <span class="text-muted small">{{ formatDuration(vo.durationNanos) }}</span>
                              </li>
                            </ul>
                          </div>

                          <div v-if="detail.events && detail.events.length" class="mb-3">
                            <h6>Events</h6>
                            <ul class="list-group">
                              <li
                                v-for="(ev, ei) in detail.events"
                                :key="ei"
                                class="list-group-item d-flex justify-content-between"
                              >
                                <span>{{ ev.name }}</span>
                                <span class="text-muted small">{{ formatTime(ev.epochNanos) }}</span>
                              </li>
                            </ul>
                          </div>

                          <div v-if="groupedAttributes.length">
                            <h6>Span attributes ({{ detail.attributes.length }})</h6>
                            <details v-for="[ns, attrs] in groupedAttributes" :key="ns" class="mb-1">
                              <summary class="text-muted small">{{ ns }} ({{ attrs.length }})</summary>
                              <table class="table table-sm mt-1">
                                <tbody>
                                  <tr v-for="a in attrs" :key="a.key">
                                    <td>
                                      <code>{{ a.key }}</code>
                                    </td>
                                    <td>
                                      <code>{{ a.value }}</code>
                                    </td>
                                  </tr>
                                </tbody>
                              </table>
                            </details>
                          </div>
                        </template>
                        <div v-else-if="detail && detail.error" class="alert alert-danger small">
                          {{ detail.error }}
                        </div>
                        <div v-else class="text-muted small">No detail available.</div>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div v-if="pagedChats.length < filteredChats.length" class="text-center mb-3">
          <button class="btn btn-sm btn-outline-secondary" @click="pageSize += 25">
            Load more (+{{ Math.min(25, filteredChats.length - pagedChats.length) }})
          </button>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
code {
  overflow-wrap: anywhere;
}
.cursor-pointer {
  cursor: pointer;
}
.kpi-card-body {
  min-height: 90px;
}
</style>
