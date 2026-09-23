<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, nextTick, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatClockTime, formatMillis, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, clear: clearBanner} = useFlashMessage()
const filter = ref('')
const categoryFilter = ref('')
const slowOnly = ref(false)
const busy = ref(null)
const lastFetched = ref(null)
const expanded = ref(new Set())
const insights = ref(null)
const insightsError = ref(null)
const rankingMetric = ref('totalDurationMillis')
const expandedRoutes = ref(new Set())
const linkedEntryIds = ref(null)
const linkedEntryLabel = ref('')
const linkedEntryTruncated = ref(false)

const RANKING_METRICS = [
  {key: 'totalDurationMillis', label: 'Total time'},
  {key: 'maxDurationMillis', label: 'Slowest execution'},
  {key: 'executions', label: 'Executions'},
  {key: 'avgDurationMillis', label: 'Average time'},
  {key: 'p95DurationMillis', label: 'p95 time'},
  {key: 'p99DurationMillis', label: 'p99 time'},
  {key: 'errorCount', label: 'Errors'}
]

// Rankings summarize a retained window that only moves as statements are captured, and each refresh
// re-ranks the whole buffer server-side. Recomputing that on every stream tick would spend real work to
// redraw the same table, so the rankings follow the executions at a slower, explicit cadence.
const INSIGHTS_MIN_INTERVAL_MS = 5000
let lastInsightsFetch = 0

async function fetchInsights(force = false) {
  const now = Date.now()
  if (!force && insights.value && now - lastInsightsFetch < INSIGHTS_MIN_INTERVAL_MS) return
  lastInsightsFetch = now
  try {
    insights.value = await getJson('api/sql-trace/insights')
    insightsError.value = null
  } catch (e) {
    insights.value = null
    insightsError.value = describeLoadError(e, 'Unable to load SQL rankings')
  }
}

let forceNextInsights = false

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/sql-trace')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load SQL trace')
    return
  }
  const force = forceNextInsights
  forceNextInsights = false
  await fetchInsights(force)
}

const {autoRefresh, loading, initialLoading, load, retryConnection, connectionState} = useEventStreamRefresh(
  'api/sql-trace/stream',
  fetchReport
)

/** Pressing refresh is an explicit request for current evidence, so it bypasses the ranking cadence. */
function refreshNow() {
  forceNextInsights = true
  return load()
}

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const stats = computed(() => report.value?.stats ?? null)
const entries = computed(() => report.value?.entries ?? [])

const rankingAvailable = computed(() => Boolean(insights.value?.available && insights.value?.window))
const traceWindow = computed(() => insights.value?.window ?? null)
const attribution = computed(() => insights.value?.attribution ?? null)
const insightsNotes = computed(() => insights.value?.notes ?? [])
const attributionNotes = computed(() => attribution.value?.notes ?? [])
const supportedCorrelations = computed(() => /** @type {string[]} */ (attribution.value?.supportedCorrelations ?? []))

// The server ships the union of the top rows for every criterion, so re-sorting here and slicing to
// topPerCriterion yields that criterion's true top N without another round trip.
const rankedStatements = computed(() => {
  const rows = insights.value?.statements ?? []
  const metric = rankingMetric.value
  const limit = insights.value?.topPerCriterion || rows.length
  // A statement that scores zero on the selected criterion did not earn a place in that ranking: showing
  // it would read as "these are the worst offenders for errors" when there were no errors at all. When
  // nothing scores above zero the criterion simply cannot separate the window, so the retained statements
  // are listed unranked with an explicit note instead of an empty table.
  const scored = rows.filter((row) => Number(row[metric]) > 0)
  return (scored.length ? scored : [...rows])
    .sort((a, b) => b[metric] - a[metric] || String(a.id).localeCompare(String(b.id)))
    .slice(0, limit)
})

const hasRankedStatements = computed(() => Boolean(insights.value?.statements?.length))

// True when every retained statement scores zero on the selected criterion, e.g. an in-memory database
// where each execution rounds down to 0 ms.
const rankingMetricUnmeasured = computed(
  () => hasRankedStatements.value && !insights.value.statements.some((row) => Number(row[rankingMetric.value]) > 0)
)

const rankingMetricLabel = computed(
  () => RANKING_METRICS.find((metric) => metric.key === rankingMetric.value)?.label ?? ''
)

const windowSummary = computed(() => {
  const w = traceWindow.value
  if (!w) return null
  const parts = [`${formatNumber(w.retainedStatements)} retained executions`]
  if (w.oldestTimestamp && w.newestTimestamp) {
    parts.push(`${formatClockTime(w.oldestTimestamp)}–${formatClockTime(w.newestTimestamp)}`)
  }
  parts.push(`${formatMillis(w.totalDurationMillis)} ms of database time`)
  if (w.evicted) parts.push(`${formatNumber(w.evicted)} older executions already evicted`)
  return parts.join(' · ')
})

function correlationLabel(value) {
  return {TRACE_ID: 'trace id', SERVING_THREAD: 'serving thread', TIME_WINDOW: 'time window'}[value] || value
}

function toggleRoute(routeGroup) {
  const next = new Set(expandedRoutes.value)
  if (next.has(routeGroup.id)) next.delete(routeGroup.id)
  else next.add(routeGroup.id)
  expandedRoutes.value = next
}

function isRouteExpanded(routeGroup) {
  return expandedRoutes.value.has(routeGroup.id)
}

/** Deep-links a ranking row to the executions table, filtering it to exactly the linked executions. */
function showExecutions(entryIds, label, truncated = false) {
  linkedEntryIds.value = new Set(entryIds || [])
  linkedEntryTruncated.value = Boolean(truncated)
  linkedEntryLabel.value = label
  nextTick(() => {
    document.getElementById('sql-executions')?.scrollIntoView({behavior: 'smooth', block: 'start'})
  })
}

function clearLinkedEntries() {
  linkedEntryIds.value = null
  linkedEntryTruncated.value = false
  linkedEntryLabel.value = ''
}

const categories = computed(() => {
  const seen = new Set()
  for (const entry of entries.value) {
    if (entry.category) seen.add(entry.category)
  }
  return Array.from(seen).sort()
})

const filteredEntries = computed(() => {
  const value = filter.value.trim().toLowerCase()
  const category = categoryFilter.value
  return entries.value.filter((entry) => {
    if (linkedEntryIds.value && !linkedEntryIds.value.has(entry.id)) return false
    if (category && entry.category !== category) return false
    if (slowOnly.value && !entry.slow) return false
    if (!value) return true
    return [
      entry.sql,
      entry.category,
      entry.statementType,
      entry.connectionId,
      entry.thread,
      entry.errorMessage,
      ...(entry.parameters || [])
    ]
      .join(' ')
      .toLowerCase()
      .includes(value)
  })
})

function categoryClass(category) {
  return (
    {
      SELECT: 'text-bg-primary',
      INSERT: 'text-bg-success',
      UPDATE: 'text-bg-warning',
      DELETE: 'text-bg-danger',
      DDL: 'text-bg-info'
    }[category] || 'text-bg-secondary'
  )
}

function toggleRow(entry) {
  const next = new Set(expanded.value)
  if (next.has(entry.id)) next.delete(entry.id)
  else next.add(entry.id)
  expanded.value = next
}

function isExpanded(entry) {
  return expanded.value.has(entry.id)
}

const subtitle = computed(() => {
  if (!report.value || !report.value.available) return null
  const s = stats.value
  const parts = [
    `${formatNumber(s.totalQueries)} retained`,
    `${formatNumber(report.value.totalCaptured)} captured since startup`
  ]
  if (s.slowQueries) parts.push(`${formatNumber(s.slowQueries)} slow`)
  if (s.failedQueries) parts.push(`${formatNumber(s.failedQueries)} failed`)
  parts.push(report.value.capturing ? 'recording' : 'paused')
  return parts.join(' · ')
})

async function applyAction(action, options) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (options.confirm && !(await confirm(options.confirm))) return
  busy.value = action
  clearBanner()
  try {
    const res = await apiFetch(options.url, options.init)
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    report.value = result
    lastFetched.value = Date.now()
    if (options.onSuccess) options.onSuccess(result)
    // A user-triggered capture change (start, stop, clear) must be reflected at once.
    await fetchInsights(true)
    flash(options.success(result), 'success')
  } catch (e) {
    flash(formatLoadError(e, options.failure), 'danger')
  } finally {
    busy.value = null
  }
}

function toggleRecording() {
  const next = !report.value?.capturing
  applyAction('recording', {
    url: 'api/sql-trace/recording',
    init: {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({enabled: next})},
    success: () => (next ? 'Recording resumed.' : 'Recording paused; existing executions are kept.'),
    failure: 'Could not change recording state'
  })
}

function clearTrace() {
  applyAction('clear', {
    url: 'api/sql-trace/clear',
    init: {method: 'POST'},
    confirm: {
      title: 'Clear SQL trace?',
      message: 'Clear all captured SQL executions from the in-memory trace buffer.',
      confirmLabel: 'Clear',
      danger: true
    },
    onSuccess: () => {
      expanded.value = new Set()
      expandedRoutes.value = new Set()
      clearLinkedEntries()
    },
    success: () => 'SQL trace cleared.',
    failure: 'Could not clear SQL trace'
  })
}

// useEventStreamRefresh automatically loads on mount unless configured otherwise
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-stopwatch"
      title="SQL Trace"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      :auto-refresh-state="connectionState"
      @refresh="refreshNow"
      @retry-auto-refresh="retryConnection"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy === 'recording'"
          :disabled="!report || !report.available || readOnly || busy"
          class="ms-2"
          :class="report && report.capturing ? 'btn btn-sm btn-outline-warning' : 'btn btn-sm btn-outline-success'"
          :icon="report && report.capturing ? 'bi-pause-fill' : 'bi-record-fill'"
          :label="report && report.capturing ? 'Pause' : 'Resume'"
          @click="toggleRecording"
        />
        <SpinnerButton
          :loading="busy === 'clear'"
          :disabled="!report || !report.available || readOnly || busy || !stats || stats.totalQueries === 0"
          class="btn btn-sm btn-outline-danger ms-2"
          icon="bi-trash"
          label="Clear"
          @click="clearTrace"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clearBanner" />

    <PanelSkeleton v-if="initialLoading && !report" />

    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small py-2">
        {{ warning }}
      </div>

      <div v-if="!report.available" class="alert alert-secondary">
        {{ report.unavailableReason || 'SQL tracing is not available.' }}
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Recording controls are read-only.</ReadOnlyNotice>

        <div v-if="!report.captureParameters" class="alert alert-secondary small py-2">
          Parameter capture is disabled. Set <code>bootui.sql-trace.capture-parameters=true</code> (local profiles only)
          to record bound parameter values.
        </div>

        <section class="mb-4">
          <div class="row g-2 stat-cards">
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Retained</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(stats.totalQueries) }}</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Avg time</div>
                  <div class="fs-5 fw-semibold">{{ formatMillis(stats.avgDurationMillis) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Slowest</div>
                  <div class="fs-5 fw-semibold">{{ formatMillis(stats.maxDurationMillis) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Slow (&ge;{{ formatNumber(report.slowQueryThresholdMillis) }} ms)</div>
                  <div class="fs-5 fw-semibold" :class="{'text-warning': stats.slowQueries > 0}">
                    {{ formatNumber(stats.slowQueries) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Failed</div>
                  <div class="fs-5 fw-semibold" :class="{'text-danger': stats.failedQueries > 0}">
                    {{ formatNumber(stats.failedQueries) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">By category</div>
                  <div class="sql-type-counts">
                    <span class="badge text-bg-primary">S {{ formatNumber(stats.selectCount) }}</span>
                    <span class="badge text-bg-success">I {{ formatNumber(stats.insertCount) }}</span>
                    <span class="badge text-bg-warning">U {{ formatNumber(stats.updateCount) }}</span>
                    <span class="badge text-bg-danger">D {{ formatNumber(stats.deleteCount) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <div v-if="insightsError" class="alert alert-warning small py-2">
          {{ insightsError }}
        </div>

        <section v-if="rankingAvailable" class="mb-4">
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h3 class="h5 mb-0">
              Statement rankings <span class="badge bg-secondary">{{ rankedStatements.length }}</span>
            </h3>
            <div class="d-flex align-items-center gap-2">
              <label class="form-label small mb-0 text-muted" for="sql-ranking-metric">Rank by</label>
              <select
                id="sql-ranking-metric"
                v-model="rankingMetric"
                aria-label="Rank statements by"
                class="form-select form-select-sm sql-filter-select"
              >
                <option v-for="metric in RANKING_METRICS" :key="metric.key" :value="metric.key">
                  {{ metric.label }}
                </option>
              </select>
            </div>
          </div>

          <p v-if="windowSummary" class="text-muted small mb-2">
            Top {{ rankedStatements.length }} by {{ rankingMetricLabel.toLowerCase() }} over the retained trace window
            ({{ windowSummary }}). These are diagnostic evidence for this window, not lifetime metrics.
          </p>

          <div v-if="rankingMetricUnmeasured" class="text-muted small mb-1">
            <i aria-hidden="true" class="bi bi-info-circle me-1"></i>No retained statement records a non-zero
            {{ rankingMetricLabel.toLowerCase() }} in this window, so this criterion cannot rank them; the retained
            statements are listed as captured.
          </div>

          <div v-for="note in insightsNotes" :key="note" class="text-muted small mb-1">
            <i aria-hidden="true" class="bi bi-info-circle me-1"></i>{{ note }}
          </div>

          <div v-if="!rankedStatements.length" class="alert alert-secondary small mt-2">
            No SQL has been captured yet, so there is nothing to rank.
          </div>

          <div v-else class="table-responsive mt-2">
            <table class="table table-sm table-hover align-middle sql-ranking-table">
              <thead>
                <tr>
                  <th>Category</th>
                  <th>Normalized statement</th>
                  <th class="text-end">Executions</th>
                  <th class="text-end text-nowrap">Total (ms)</th>
                  <th class="text-end text-nowrap">Max (ms)</th>
                  <th class="text-end text-nowrap">Avg (ms)</th>
                  <th class="text-end text-nowrap">p95 (ms)</th>
                  <th class="text-end text-nowrap">p99 (ms)</th>
                  <th class="text-end">Errors</th>
                  <th class="text-end">Share</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="group in rankedStatements" :key="group.id">
                  <td>
                    <span :class="categoryClass(group.category)" class="badge">{{ group.category }}</span>
                  </td>
                  <td>
                    <code class="sql-text">{{ group.sql }}</code>
                    <span
                      v-if="group.potentialNPlusOne"
                      class="badge text-bg-danger ms-1"
                      title="This SELECT repeats many times — it may be an N+1 query"
                    >
                      possible N+1
                    </span>
                    <div v-if="group.callSites && group.callSites.length" class="call-sites small text-muted">
                      <div v-for="site in group.callSites" :key="site" class="font-monospace">at {{ site }}</div>
                    </div>
                  </td>
                  <td class="text-end">{{ formatNumber(group.executions) }}</td>
                  <td class="text-end">{{ formatMillis(group.totalDurationMillis) }}</td>
                  <td class="text-end">{{ formatMillis(group.maxDurationMillis) }}</td>
                  <td class="text-end">{{ formatMillis(group.avgDurationMillis) }}</td>
                  <td class="text-end">{{ formatMillis(group.p95DurationMillis) }}</td>
                  <td class="text-end">{{ formatMillis(group.p99DurationMillis) }}</td>
                  <td class="text-end" :class="{'text-danger fw-semibold': group.errorCount > 0}">
                    {{ formatNumber(group.errorCount) }}
                  </td>
                  <td class="text-end">{{ group.shareOfRetainedTimePercent.toFixed(1) }}%</td>
                  <td class="text-end">
                    <button
                      class="btn btn-sm sql-executions-link bootui-keyboard-target text-nowrap"
                      type="button"
                      :aria-label="`Show retained executions of ${group.sql}`"
                      @click="showExecutions(group.entryIds, group.sql, group.entryIdsTruncated)"
                    >
                      <i aria-hidden="true" class="bi bi-list-ul"></i>
                      Executions
                    </button>
                    <div v-if="group.entryIdsTruncated" class="text-muted small text-nowrap">
                      first {{ formatNumber(group.entryIds.length) }}
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <p v-if="insights.statementsTruncated" class="text-muted small mb-0">
            {{ formatNumber(insights.distinctStatements) }} distinct statements were retained; only the highest ranked
            for each criterion are shown.
          </p>
        </section>

        <section v-if="rankingAvailable" class="mb-4">
          <h3 class="h5 mb-2">Database time by request route</h3>

          <div v-if="!attribution.available" class="alert alert-secondary small py-2">
            {{ attribution.unavailableReason || 'Request attribution is not available on this runtime.' }}
          </div>

          <template v-else>
            <p class="text-muted small mb-1">
              Attributed from {{ formatNumber(attribution.requestsConsidered) }} captured
              {{ attribution.requestsConsidered === 1 ? 'request' : 'requests' }} using
              <span v-for="(correlation, index) in supportedCorrelations" :key="correlation">
                <span v-if="index > 0">, </span>
                <span class="badge text-bg-light border text-body-secondary">{{ correlationLabel(correlation) }}</span>
              </span>
              correlation. BootUI never invents a request relationship.
            </p>

            <div v-for="note in attributionNotes" :key="note" class="text-muted small mb-1">
              <i aria-hidden="true" class="bi bi-info-circle me-1"></i>{{ note }}
            </div>

            <div class="row g-2 stat-cards my-2">
              <div class="col-6 col-md-4">
                <div class="card h-100">
                  <div class="card-body py-2">
                    <div class="text-muted small">Attributed to a route</div>
                    <div class="fs-5 fw-semibold">{{ formatNumber(attribution.attributedExecutions) }}</div>
                  </div>
                </div>
              </div>
              <div class="col-6 col-md-4">
                <div class="card h-100" :title="attribution.unattributed.reason">
                  <div class="card-body py-2">
                    <div class="text-muted small">Unattributed</div>
                    <div class="fs-5 fw-semibold">{{ formatNumber(attribution.unattributed.executions) }}</div>
                    <div class="text-muted small">
                      {{ formatMillis(attribution.unattributed.totalDurationMillis) }} ms ·
                      {{ attribution.unattributed.shareOfRetainedTimePercent.toFixed(1) }}%
                    </div>
                  </div>
                </div>
              </div>
              <div class="col-6 col-md-4">
                <div class="card h-100" :title="attribution.ambiguous.reason">
                  <div class="card-body py-2">
                    <div class="text-muted small">Ambiguous</div>
                    <div class="fs-5 fw-semibold">{{ formatNumber(attribution.ambiguous.executions) }}</div>
                    <div class="text-muted small">
                      {{ formatMillis(attribution.ambiguous.totalDurationMillis) }} ms ·
                      {{ attribution.ambiguous.shareOfRetainedTimePercent.toFixed(1) }}%
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <dl class="row text-muted small mb-3">
              <dt class="col-sm-2 fw-semibold">Unattributed</dt>
              <dd class="col-sm-10 mb-1">{{ attribution.unattributed.reason }}</dd>
              <dt class="col-sm-2 fw-semibold">Ambiguous</dt>
              <dd class="col-sm-10 mb-0">{{ attribution.ambiguous.reason }}</dd>
            </dl>

            <div v-if="!(attribution.routes || []).length" class="alert alert-secondary small">
              No retained statement could be attributed to a captured request.
            </div>

            <div v-else class="table-responsive">
              <table class="table table-sm table-hover align-middle sql-route-table">
                <thead>
                  <tr>
                    <th style="width: 2rem"></th>
                    <th>Route</th>
                    <th class="text-end">Requests</th>
                    <th class="text-end">Statements</th>
                    <th class="text-end">Total</th>
                    <th class="text-end">Max</th>
                    <th class="text-end">Avg</th>
                    <th class="text-end">Errors</th>
                    <th class="text-end">Share</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="routeGroup in attribution.routes || []" :key="routeGroup.id">
                    <tr>
                      <td>
                        <button
                          class="btn btn-sm btn-link p-0"
                          type="button"
                          :aria-controls="`sql-route-${routeGroup.id}`"
                          :aria-expanded="isRouteExpanded(routeGroup)"
                          :aria-label="`${isRouteExpanded(routeGroup) ? 'Collapse' : 'Expand'} statements for ${routeGroup.method} ${routeGroup.route}`"
                          @click="toggleRoute(routeGroup)"
                        >
                          <i
                            aria-hidden="true"
                            class="bi"
                            :class="isRouteExpanded(routeGroup) ? 'bi-chevron-down' : 'bi-chevron-right'"
                          ></i>
                        </button>
                      </td>
                      <td>
                        <span class="badge text-bg-secondary me-1">{{ routeGroup.method }}</span>
                        <code>{{ routeGroup.route }}</code>
                        <span
                          v-if="routeGroup.routeSource === 'MASKED_PATH'"
                          class="badge text-bg-light border text-body-secondary ms-1"
                          title="No route template was available, so the request path was masked. Identifier-looking segments show as {value}."
                        >
                          masked path
                        </span>
                        <div class="small text-muted">
                          <span v-if="routeGroup.traceCorrelated"
                            >{{ formatNumber(routeGroup.traceCorrelated) }} by trace id</span
                          >
                          <span v-if="routeGroup.threadCorrelated" class="ms-2"
                            >{{ formatNumber(routeGroup.threadCorrelated) }} by serving thread</span
                          >
                          <span v-if="routeGroup.timeWindowCorrelated" class="ms-2"
                            >{{ formatNumber(routeGroup.timeWindowCorrelated) }} by time window</span
                          >
                        </div>
                      </td>
                      <td class="text-end">{{ formatNumber(routeGroup.requests) }}</td>
                      <td class="text-end">
                        {{ formatNumber(routeGroup.executions) }}
                        <span class="text-muted">/ {{ formatNumber(routeGroup.distinctStatements) }}</span>
                      </td>
                      <td class="text-end">{{ formatMillis(routeGroup.totalDurationMillis) }} ms</td>
                      <td class="text-end">{{ formatMillis(routeGroup.maxDurationMillis) }} ms</td>
                      <td class="text-end">{{ formatMillis(routeGroup.avgDurationMillis) }} ms</td>
                      <td class="text-end" :class="{'text-danger fw-semibold': routeGroup.errorCount > 0}">
                        {{ formatNumber(routeGroup.errorCount) }}
                      </td>
                      <td class="text-end">{{ routeGroup.shareOfRetainedTimePercent.toFixed(1) }}%</td>
                      <td class="text-end">
                        <button
                          class="btn btn-sm sql-executions-link bootui-keyboard-target text-nowrap"
                          type="button"
                          :aria-label="`Show retained executions for ${routeGroup.method} ${routeGroup.route}`"
                          @click="showExecutions(routeGroup.entryIds, `${routeGroup.method} ${routeGroup.route}`)"
                        >
                          <i aria-hidden="true" class="bi bi-list-ul"></i>
                          Executions
                        </button>
                      </td>
                    </tr>
                    <tr v-if="isRouteExpanded(routeGroup)" :id="`sql-route-${routeGroup.id}`" class="sql-detail-row">
                      <td></td>
                      <td colspan="9">
                        <table class="table table-sm mb-0 align-middle">
                          <thead>
                            <tr>
                              <th>Normalized statement</th>
                              <th class="text-end">Executions</th>
                              <th class="text-end">Total</th>
                              <th class="text-end">Max</th>
                              <th class="text-end">Errors</th>
                            </tr>
                          </thead>
                          <tbody>
                            <tr v-for="statement in routeGroup.topStatements || []" :key="statement.statementId">
                              <td>
                                <span :class="categoryClass(statement.category)" class="badge me-1">{{
                                  statement.category
                                }}</span>
                                <code class="sql-text">{{ statement.sql }}</code>
                              </td>
                              <td class="text-end">{{ formatNumber(statement.executions) }}</td>
                              <td class="text-end">{{ formatMillis(statement.totalDurationMillis) }} ms</td>
                              <td class="text-end">{{ formatMillis(statement.maxDurationMillis) }} ms</td>
                              <td class="text-end">{{ formatNumber(statement.errorCount) }}</td>
                            </tr>
                          </tbody>
                        </table>
                        <p v-if="routeGroup.topStatementsTruncated" class="text-muted small mb-0 mt-1">
                          Only the heaviest statements are listed for this route.
                        </p>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </div>

            <p v-if="attribution.routesTruncated" class="text-muted small mb-0">
              {{ formatNumber(attribution.distinctRoutes) }} routes contributed database time; only the heaviest are
              shown.
            </p>
          </template>
        </section>

        <section v-if="!rankingAvailable && report.topStatements.length" class="mb-4">
          <h3 class="h5 mb-2">
            Most frequent statements <span class="badge bg-secondary">{{ report.topStatements.length }}</span>
          </h3>
          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Category</th>
                  <th>Statement</th>
                  <th class="text-end">Count</th>
                  <th class="text-end">Total</th>
                  <th class="text-end">Max</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(group, index) in report.topStatements" :key="index">
                  <td>
                    <span :class="categoryClass(group.category)" class="badge">{{ group.category }}</span>
                  </td>
                  <td>
                    <code class="sql-text">{{ group.sql }}</code>
                    <span
                      v-if="group.potentialNPlusOne"
                      class="badge text-bg-danger ms-1"
                      title="This SELECT repeats many times — it may be an N+1 query"
                    >
                      possible N+1
                    </span>
                    <div v-if="group.callSites && group.callSites.length" class="call-sites small text-muted">
                      <div v-for="site in group.callSites" :key="site" class="font-monospace">at {{ site }}</div>
                    </div>
                  </td>
                  <td class="text-end">{{ formatNumber(group.executions) }}</td>
                  <td class="text-end">{{ formatMillis(group.totalDurationMillis) }} ms</td>
                  <td class="text-end">{{ formatMillis(group.maxDurationMillis) }} ms</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section id="sql-executions">
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h3 class="h5 mb-0">
              Recent executions <span class="badge bg-secondary">{{ filteredEntries.length }}</span>
            </h3>
            <div class="d-flex flex-wrap gap-2">
              <select
                v-model="categoryFilter"
                aria-label="Filter SQL executions by category"
                class="form-select form-select-sm sql-filter-select"
              >
                <option value="">All categories</option>
                <option v-for="category in categories" :key="category" :value="category">{{ category }}</option>
              </select>
              <div class="form-check form-switch d-flex align-items-center">
                <input
                  id="sql-slow-only"
                  v-model="slowOnly"
                  class="form-check-input me-1"
                  type="checkbox"
                  role="switch"
                />
                <label class="form-check-label small" for="sql-slow-only">Slow only</label>
              </div>
              <input
                v-model="filter"
                aria-label="Filter SQL executions"
                class="form-control form-control-sm trace-filter"
                placeholder="Filter by SQL, category, connection, thread, or parameter…"
              />
            </div>
          </div>

          <div v-if="linkedEntryIds" class="alert alert-info small py-2 d-flex align-items-center gap-2">
            <span class="flex-grow-1">
              Showing only the retained executions linked from <code>{{ linkedEntryLabel }}</code
              >.
              <template v-if="linkedEntryTruncated">
                This group ran more times than BootUI keeps deep links for, so these are the first linked executions
                rather than all of them.
              </template>
            </span>
            <button class="btn btn-sm btn-outline-secondary" type="button" @click="clearLinkedEntries">
              Show all executions
            </button>
          </div>

          <div v-if="entries.length === 0" class="alert alert-secondary small">
            No SQL has been captured yet. Exercise the application's database access and refresh to see executions.
          </div>

          <div v-else-if="filteredEntries.length" class="table-responsive">
            <table class="table table-sm table-hover align-middle sql-table">
              <thead>
                <tr>
                  <th style="width: 2rem"></th>
                  <th>Time</th>
                  <th>Category</th>
                  <th class="text-end">Duration</th>
                  <th>SQL</th>
                  <th class="text-end">Rows</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="entry in filteredEntries" :key="entry.id">
                  <tr class="sql-row" data-keyboard-delegate="toggleRow(entry)" @click="toggleRow(entry)">
                    <td class="text-muted">
                      <button
                        class="btn btn-sm btn-link p-0 bootui-keyboard-target sql-row-toggle"
                        type="button"
                        :aria-controls="`sql-details-${entry.id}`"
                        :aria-expanded="isExpanded(entry)"
                        :aria-label="`${isExpanded(entry) ? 'Collapse' : 'Expand'} details for ${entry.category} query at ${formatClockTime(entry.timestamp)}`"
                        @click.stop="toggleRow(entry)"
                      >
                        <i
                          aria-hidden="true"
                          class="bi"
                          :class="isExpanded(entry) ? 'bi-chevron-down' : 'bi-chevron-right'"
                        ></i>
                      </button>
                    </td>
                    <td class="text-nowrap font-monospace small">{{ formatClockTime(entry.timestamp) }}</td>
                    <td>
                      <span :class="categoryClass(entry.category)" class="badge">{{ entry.category }}</span>
                      <span v-if="entry.batchSize > 0" class="badge text-bg-secondary ms-1"
                        >batch ×{{ entry.batchSize }}</span
                      >
                    </td>
                    <td class="text-end text-nowrap" :class="{'text-warning fw-semibold': entry.slow}">
                      {{ formatMillis(entry.durationMicros / 1000) }} ms
                    </td>
                    <td>
                      <code class="sql-text">{{ entry.sql }}</code>
                    </td>
                    <td class="text-end">{{ entry.affectedRows === null ? '—' : formatNumber(entry.affectedRows) }}</td>
                    <td>
                      <span v-if="entry.success" class="badge text-bg-success">ok</span>
                      <span v-else class="badge text-bg-danger" :title="entry.errorMessage">failed</span>
                      <span v-if="entry.slow" class="badge text-bg-warning ms-1">slow</span>
                    </td>
                  </tr>
                  <tr v-if="isExpanded(entry)" :id="`sql-details-${entry.id}`" class="sql-detail-row">
                    <td></td>
                    <td colspan="6">
                      <dl class="row mb-0 small">
                        <dt class="col-sm-2">Statement</dt>
                        <dd class="col-sm-10">
                          <pre class="sql-detail mb-1">{{ entry.sql }}</pre>
                        </dd>
                        <template v-if="entry.parameters && entry.parameters.length">
                          <dt class="col-sm-2">Parameters</dt>
                          <dd class="col-sm-10">
                            <span
                              v-for="(param, i) in entry.parameters"
                              :key="i"
                              class="badge text-bg-light border text-dark me-1 mb-1"
                            >
                              {{ param }}
                            </span>
                          </dd>
                        </template>
                        <dt class="col-sm-2">Type</dt>
                        <dd class="col-sm-10">{{ entry.statementType }}</dd>
                        <dt class="col-sm-2">Connection</dt>
                        <dd class="col-sm-10">
                          <code>{{ entry.connectionId || '—' }}</code>
                        </dd>
                        <dt class="col-sm-2">Thread</dt>
                        <dd class="col-sm-10">
                          <code>{{ entry.thread || '—' }}</code>
                        </dd>
                        <dt class="col-sm-2">Call site</dt>
                        <dd class="col-sm-10">
                          <code>{{ entry.callSite || '—' }}</code>
                        </dd>
                        <template v-if="!entry.success">
                          <dt class="col-sm-2 text-danger">Error</dt>
                          <dd class="col-sm-10 text-danger">{{ entry.errorMessage }}</dd>
                        </template>
                      </dl>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>

          <div v-else class="text-muted small">No executions match the current filter.</div>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.trace-filter {
  max-width: 24rem;
}

.sql-filter-select {
  max-width: 12rem;
}

.sql-text {
  display: inline-block;
  max-width: 44rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.sql-ranking-table .sql-text,
.sql-ranking-table .call-sites {
  max-width: 26rem;
}

.sql-ranking-table .call-sites div {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sql-type-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
}

.sql-executions-link {
  align-items: center;
  background: color-mix(in srgb, var(--bootui-blue) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--bootui-blue) 24%, transparent);
  border-radius: var(--bootui-radius-pill);
  color: var(--bootui-blue);
  display: inline-flex;
  font-weight: 600;
  gap: 0.3rem;
  line-height: 1;
  min-height: 2rem;
  padding: 0.35rem 0.65rem;
  text-decoration: none;
}

.sql-executions-link:hover {
  background: color-mix(in srgb, var(--bootui-blue) 14%, transparent);
  border-color: color-mix(in srgb, var(--bootui-blue) 40%, transparent);
  color: var(--bootui-blue);
}

.sql-row {
  cursor: pointer;
}

.sql-detail-row > td {
  background-color: var(--bs-tertiary-bg);
}

.sql-detail {
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 0;
}
</style>
