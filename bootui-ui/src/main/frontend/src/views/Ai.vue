<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, inject, ref} from 'vue'
import {formatDuration, formatNumber, formatRelative, formatTime} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useDataState} from '../utils/panelState.js'
import AiSetupChecklist from './components/AiSetupChecklist.vue'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import FoldedInsightsLink from './components/FoldedInsightsLink.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ProgressBar from './components/ProgressBar.vue'

const overview = ref(null)
const series = ref(null)
const detail = ref(null)
const error = ref(null)
const selectedSpanId = ref(null)
const detailLoading = ref(false)
const lastUpdated = ref(null)
const partialWarning = ref(null)
let tokenSeriesRequest = 0
let detailRequest = 0

const panels = inject('panels', ref(null))
const platform = computed(() => panels.value?.platform ?? 'spring-boot')

const isStale = computed(() => {
  if (autoRefresh.value || !lastUpdated.value) return false
  return Date.now() - lastUpdated.value > 30_000
})

async function fetchAiUsage() {
  error.value = null
  try {
    const requestedWindow = windowMinutes.value
    const requestId = ++tokenSeriesRequest
    const [overviewResult, tokenResult] = await Promise.allSettled([
      apiFetch('api/ai/overview'),
      apiFetch(`api/ai/tokens?minutes=${requestedWindow}`)
    ])
    if (overviewResult.status === 'rejected') throw overviewResult.reason
    const ovRes = overviewResult.value
    if (!ovRes.ok) throw new Error(`HTTP ${ovRes.status}`)
    overview.value = await ovRes.json()

    if (requestId === tokenSeriesRequest) {
      if (tokenResult.status === 'rejected') {
        setTokenPartialWarning()
      } else if (!tokenResult.value.ok) {
        setTokenPartialWarning(`HTTP ${tokenResult.value.status}`)
      } else {
        try {
          const nextSeries = await tokenResult.value.json()
          if (requestId === tokenSeriesRequest && requestedWindow === windowMinutes.value) {
            series.value = nextSeries
            partialWarning.value = null
          }
        } catch {
          if (requestId === tokenSeriesRequest) setTokenPartialWarning('invalid response')
        }
      }
    }
    lastUpdated.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load AI usage data')
  }
}

const {autoRefresh, loading, hasLoaded, initialLoading, load} = useAutoRefresh(fetchAiUsage)
const panelState = useDataState({
  loading,
  loaded: hasLoaded,
  error,
  hasData: computed(() => overview.value !== null),
  partial: computed(() => partialWarning.value !== null)
})
const dataStatusMessage = computed(() => {
  if (panelState.stale.value) {
    return {text: 'AI usage could not be refreshed. Showing the last successful snapshot.', type: 'warning'}
  }
  if (panelState.partialSuccess.value) {
    return {text: `Partial AI usage data. ${partialWarning.value}`, type: 'warning'}
  }
  if (isStale.value) {
    return {text: 'Auto-refresh is off. This AI usage snapshot may be stale.', type: 'warning'}
  }
  return null
})

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
  const requestId = ++detailRequest
  selectedSpanId.value = spanId
  detail.value = null
  detailLoading.value = true
  try {
    const nextDetail = await getJson(`api/ai/chats/${spanId}`)
    if (requestId === detailRequest && selectedSpanId.value === spanId) {
      detail.value = nextDetail
    }
  } catch (e) {
    if (requestId === detailRequest && selectedSpanId.value === spanId) {
      detail.value = {error: formatLoadError(e, 'Unable to load AI chat details')}
    }
  } finally {
    if (requestId === detailRequest) detailLoading.value = false
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
  detailRequest += 1
  selectedSpanId.value = null
  detail.value = null
  detailLoading.value = false
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

function ariaSort(activeColumn, column, direction) {
  if (activeColumn !== column) return 'none'
  return direction === 'asc' ? 'ascending' : 'descending'
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
      const hay = `${c.requestModel || ''} ${c.provider || ''} ${c.spanId || ''}`.toLowerCase()
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
  bars.push({x: 0, w: width, y: baseY, h: barH, color: 'var(--bootui-chart-span)', title: 'Chat span'})

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
          color: 'var(--bootui-chart-tool)',
          title: `${tc.name || 'tool'} ${(tc.durationNanos / 1_000_000).toFixed(1)}ms`
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
          color: 'var(--bootui-chart-vector)',
          title: `${vo.operation || 'vector'} ${(vo.durationNanos / 1_000_000).toFixed(1)}ms`
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

function setTokenPartialWarning(reason = 'request failed') {
  partialWarning.value =
    `Token history could not be refreshed (${reason}). ` +
    'Overview data remains available; any token chart shown is from the previous successful refresh.'
}

async function loadTokenSeries() {
  const requestedWindow = windowMinutes.value
  const requestId = ++tokenSeriesRequest
  try {
    const res = await apiFetch(`api/ai/tokens?minutes=${requestedWindow}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const nextSeries = await res.json()
    if (requestId !== tokenSeriesRequest || requestedWindow !== windowMinutes.value) return
    series.value = nextSeries
    partialWarning.value = null
  } catch (e) {
    if (requestId !== tokenSeriesRequest) return
    windowMinutes.value = series.value?.minutes ?? requestedWindow
    setTokenPartialWarning(e instanceof Error ? e.message : 'request failed')
  }
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
  if (!overview.value || !overview.value.totalChats) return null
  return overview.value.averageDurationNanos ?? null
})

const errorRate = computed(() => {
  if (!overview.value || !overview.value.totalChats) return null
  return ((overview.value.errorCount || 0) / overview.value.totalChats) * 100
})

const hasAnyData = computed(() => overview.value && overview.value.totalChats > 0)

const frameworkDetected = computed(
  () => !!overview.value && (overview.value.springAiDetected || overview.value.langChain4jDetected)
)

const detectedFrameworks = computed(() => {
  if (!overview.value) return []
  const frameworks = []
  if (overview.value.springAiDetected) frameworks.push('Spring AI')
  if (overview.value.langChain4jDetected) frameworks.push('LangChain4j')
  return frameworks
})

const detectedFrameworkLabel = computed(() => {
  if (!overview.value) return null
  const frameworks = detectedFrameworks.value
  if (frameworks.length === 0) {
    return platform.value === 'quarkus' ? 'LangChain4j not on classpath' : 'Spring AI or LangChain4j not on classpath'
  }
  return `${frameworks.join(' & ')} detected`
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-cpu"
      title="AI Framework"
      :subtitle="overview ? detectedFrameworkLabel : null"
      :loading="loading"
      :error="panelState.retryableError.value ? error : null"
      :last-fetched="lastUpdated"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <span
          v-for="fw in detectedFrameworks"
          :key="fw"
          class="badge text-bg-primary d-inline-flex align-items-center"
          title="AI framework detected on the application classpath"
        >
          <i class="bi bi-robot me-1"></i>{{ fw }}
        </span>
        <button v-if="overview && hasAnyData" class="btn btn-sm btn-outline-secondary" @click="exportCsv">
          <i class="bi bi-download"></i> Export CSV
        </button>
      </template>
    </PanelHeader>

    <PanelSkeleton v-if="initialLoading" />
    <template v-else-if="overview">
      <FlashBanner v-if="dataStatusMessage" :dismissible="false" :message="dataStatusMessage" with-icon />
      <div v-if="overview.contentBanner && hasAnyData" class="alert alert-info small">
        <i class="bi bi-info-circle me-1"></i>{{ overview.contentBanner }}
      </div>

      <AiSetupChecklist
        v-if="!overview.enabled || !frameworkDetected"
        :enabled="overview.enabled"
        :has-data="hasAnyData"
        :platform="platform"
        :spring-ai-detected="overview.springAiDetected"
        :lang-chain4j-detected="overview.langChain4jDetected"
      />

      <div v-else-if="!hasAnyData" class="card mb-3 border-info-subtle">
        <div class="card-body d-flex align-items-start gap-3">
          <span class="text-info fs-3"><i class="bi bi-broadcast-pin"></i></span>
          <div>
            <div class="d-flex align-items-center gap-2 flex-wrap mb-1">
              <h3 class="h5 mb-0">No AI chat completions recorded yet</h3>
              <span class="badge text-bg-info">Telemetry ready</span>
            </div>
            <p class="text-muted mb-0">
              BootUI's telemetry capture is active and an AI framework is detected. Exercise an AI chat flow; this panel
              will refresh automatically when the first completion span arrives.
            </p>
          </div>
        </div>
      </div>

      <template v-else>
        <FoldedInsightsLink class="mb-3" theme="ai" what="AI calls and tokens per route" />

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
              <h3 class="fs-6 mb-0">Token usage (last {{ series.minutes }} min)</h3>
              <select
                v-model="windowMinutes"
                aria-label="Token usage time window"
                class="form-select form-select-sm w-auto"
                @change="onWindowChange"
              >
                <option :value="15">15 min</option>
                <option :value="60">60 min</option>
                <option :value="240">240 min</option>
              </select>
            </div>
            <div
              ref="chartContainerRef"
              class="ai-chart-container position-relative"
              @mouseleave="onChartMouseleave"
              @mousemove="onChartMousemove"
            >
              <svg
                :aria-label="'Token usage over the last ' + series.minutes + ' minutes'"
                :viewBox="'0 0 ' + chart.width + ' ' + chart.height"
                class="w-100"
                role="img"
                style="max-height: 100px"
              >
                <line
                  :x1="0"
                  :x2="chart.width"
                  :y1="chart.halfY"
                  :y2="chart.halfY"
                  aria-hidden="true"
                  class="ai-chart-grid"
                  stroke="var(--bootui-chart-grid)"
                  stroke-width="1"
                />
                <text :x="2" :y="8" class="ai-chart-axis-label" fill="var(--bootui-chart-axis)" font-size="9">
                  {{ formatNumber(chart.maxTokens) }}
                </text>
                <text
                  :x="2"
                  :y="chart.halfY - 2"
                  class="ai-chart-axis-label"
                  fill="var(--bootui-chart-axis)"
                  font-size="9"
                >
                  {{ formatNumber(Math.round(chart.maxTokens / 2)) }}
                </text>
                <text
                  :x="2"
                  :y="chart.height - 1"
                  class="ai-chart-axis-label"
                  fill="var(--bootui-chart-axis)"
                  font-size="9"
                >
                  0
                </text>
                <polygon
                  :points="chart.inputAreaPts"
                  class="ai-chart-input-area"
                  fill="var(--bootui-chart-input)"
                  fill-opacity="0.6"
                />
                <polygon
                  :points="chart.outputAreaPts"
                  class="ai-chart-output-area"
                  fill="var(--bootui-chart-output)"
                  fill-opacity="0.6"
                />
                <polyline
                  :points="chart.outputLinePts"
                  class="ai-chart-output-line"
                  fill="none"
                  stroke="var(--bootui-chart-output)"
                  stroke-width="1.5"
                />
                <polyline
                  :points="chart.callLinePts"
                  class="ai-chart-calls-line"
                  fill="none"
                  stroke="var(--bootui-chart-calls)"
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
                  class="ai-chart-selection"
                  stroke="var(--bootui-chart-selection)"
                  stroke-width="1"
                />
              </svg>
              <div
                v-if="tooltipData"
                :style="{left: tooltipData.x + '%'}"
                class="ai-chart-tooltip position-absolute rounded p-1 small shadow-sm"
                style="top: 0; transform: translateX(-50%); pointer-events: none; white-space: nowrap; z-index: 10"
              >
                <div class="fw-semibold">{{ tooltipData.time }}</div>
                <div class="ai-chart-tooltip-input">In: {{ tooltipData.input }}</div>
                <div class="ai-chart-tooltip-output">Out: {{ tooltipData.output }}</div>
                <div class="ai-chart-tooltip-calls">Calls: {{ tooltipData.calls }}</div>
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
            <h3 class="fs-6">Usage by model</h3>
            <div class="table-responsive">
              <table class="table table-sm mb-0">
                <caption class="visually-hidden">
                  Usage by model
                </caption>
                <thead>
                  <tr>
                    <th scope="col" :aria-sort="ariaSort(byModelSort, 'model', byModelSortDir)">
                      <button class="sort-button bootui-keyboard-target" type="button" @click="sortByModel('model')">
                        Model
                        <i
                          v-if="byModelSort === 'model'"
                          aria-hidden="true"
                          :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                          class="bi"
                        ></i>
                      </button>
                    </th>
                    <th scope="col" :aria-sort="ariaSort(byModelSort, 'calls', byModelSortDir)">
                      <button
                        class="sort-button bootui-keyboard-target text-end"
                        type="button"
                        @click="sortByModel('calls')"
                      >
                        Calls
                        <i
                          v-if="byModelSort === 'calls'"
                          aria-hidden="true"
                          :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                          class="bi"
                        ></i>
                      </button>
                    </th>
                    <th scope="col" :aria-sort="ariaSort(byModelSort, 'totalTokens', byModelSortDir)">
                      <button
                        class="sort-button bootui-keyboard-target text-end"
                        type="button"
                        @click="sortByModel('totalTokens')"
                      >
                        Total tokens
                        <i
                          v-if="byModelSort === 'totalTokens'"
                          aria-hidden="true"
                          :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                          class="bi"
                        ></i>
                      </button>
                    </th>
                    <th scope="col" :aria-sort="ariaSort(byModelSort, 'avgTokens', byModelSortDir)">
                      <button
                        class="sort-button bootui-keyboard-target text-end"
                        type="button"
                        @click="sortByModel('avgTokens')"
                      >
                        Avg tokens/call
                        <i
                          v-if="byModelSort === 'avgTokens'"
                          aria-hidden="true"
                          :class="byModelSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                          class="bi"
                        ></i>
                      </button>
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
                      <ProgressBar
                        :label="`${row.model} token share`"
                        :value="row.pct"
                        :value-text="`${row.pct}% of tokens`"
                        style="height: 6px"
                      />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <h3 class="h5">Recent chats</h3>

        <div class="row g-2 mb-2">
          <div class="col-md-4">
            <input
              v-model="tableSearch"
              aria-label="Search recent chats"
              class="form-control form-control-sm"
              placeholder="Search model, provider, span…"
              type="search"
            />
          </div>
          <div class="col-md-2">
            <select
              v-model="providerFilter"
              aria-label="Filter recent chats by provider"
              class="form-select form-select-sm"
            >
              <option value="">All providers</option>
              <option v-for="p in distinctProviders" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="col-md-3">
            <select v-model="modelFilter" aria-label="Filter recent chats by model" class="form-select form-select-sm">
              <option value="">All models</option>
              <option v-for="m in distinctModels" :key="m" :value="m">{{ m }}</option>
            </select>
          </div>
          <div class="col-md-2">
            <select
              v-model="statusFilter"
              aria-label="Filter recent chats by status"
              class="form-select form-select-sm"
            >
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
                <th scope="col" :aria-sort="ariaSort(tableSort, 'startEpochNanos', tableSortDir)">
                  <button
                    class="sort-button bootui-keyboard-target"
                    type="button"
                    @click="sortTable('startEpochNanos')"
                  >
                    Started
                    <i
                      v-if="tableSort === 'startEpochNanos'"
                      aria-hidden="true"
                      :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                      class="bi"
                    ></i>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(tableSort, 'provider', tableSortDir)">
                  <button class="sort-button bootui-keyboard-target" type="button" @click="sortTable('provider')">
                    Provider
                    <i
                      v-if="tableSort === 'provider'"
                      aria-hidden="true"
                      :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                      class="bi"
                    ></i>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(tableSort, 'requestModel', tableSortDir)">
                  <button class="sort-button bootui-keyboard-target" type="button" @click="sortTable('requestModel')">
                    Model
                    <i
                      v-if="tableSort === 'requestModel'"
                      aria-hidden="true"
                      :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                      class="bi"
                    ></i>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(tableSort, 'totalTokens', tableSortDir)">
                  <button class="sort-button bootui-keyboard-target" type="button" @click="sortTable('totalTokens')">
                    Tokens
                    <i
                      v-if="tableSort === 'totalTokens'"
                      aria-hidden="true"
                      :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                      class="bi"
                    ></i>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(tableSort, 'durationNanos', tableSortDir)">
                  <button class="sort-button bootui-keyboard-target" type="button" @click="sortTable('durationNanos')">
                    Duration
                    <i
                      v-if="tableSort === 'durationNanos'"
                      aria-hidden="true"
                      :class="tableSortDir === 'asc' ? 'bi-caret-up-fill' : 'bi-caret-down-fill'"
                      class="bi"
                    ></i>
                  </button>
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
                            <h4 class="fs-6">Span timeline</h4>
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
                            <h4 class="fs-6">Tool calls</h4>
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
                            <h4 class="fs-6">Vector operations</h4>
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
                            <h4 class="fs-6">Events</h4>
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
                            <h4 class="fs-6">Span attributes ({{ detail.attributes.length }})</h4>
                            <details v-for="[ns, attrs] in groupedAttributes" :key="ns" class="mb-1">
                              <summary class="text-muted small">{{ ns }} ({{ attrs.length }})</summary>
                              <table class="table table-sm mt-1">
                                <thead class="visually-hidden">
                                  <tr>
                                    <th scope="col">Attribute</th>
                                    <th scope="col">Value</th>
                                  </tr>
                                </thead>
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
.sort-button {
  align-items: center;
  background: transparent;
  border: 0;
  color: inherit;
  cursor: pointer;
  display: inline-flex;
  gap: 0.25rem;
  padding: 0.25rem;
  width: 100%;
}

.sort-button.text-end {
  justify-content: flex-end;
}
.kpi-card-body {
  min-height: 90px;
}

.ai-chart-tooltip {
  background: var(--bootui-chart-tooltip-bg);
  border: 1px solid var(--bootui-chart-tooltip-border);
  color: var(--bootui-chart-tooltip-text);
}

.ai-chart-tooltip-input {
  color: var(--bootui-chart-input);
}

.ai-chart-tooltip-output {
  color: var(--bootui-chart-output);
}

.ai-chart-tooltip-calls {
  color: var(--bootui-chart-calls);
}
</style>
