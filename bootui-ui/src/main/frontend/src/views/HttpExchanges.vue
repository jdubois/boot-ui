<script setup>
import {computed, nextTick, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {getJson} from '../api.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ServerListFooter from './components/ServerListFooter.vue'
import {formatNumber} from '../utils/format.js'
import {buildCurlCommand} from '../utils/curlCommand.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'

const filter = ref('')
const method = ref('')
const statusClass = ref('')
const routeFilter = ref('')
const expanded = ref(new Set())
const exchangesHeading = ref(null)

const routesReport = ref(null)
const routesError = ref(null)
const rankingMetric = ref('requests')
const highlightedRoute = ref('')

const ROUTE_METRICS = [
  {key: 'requests', label: 'Requests'},
  {key: 'totalDurationMs', label: 'Total time'},
  {key: 'p95DurationMs', label: 'p95 time'},
  {key: 'maxDurationMs', label: 'Slowest request'},
  {key: 'errorCount', label: 'Errors'}
]

const ROUTE_SOURCES = {
  FRAMEWORK_TEMPLATE: {label: 'template', title: 'The route template the framework matched for these requests'},
  DECLARED_MAPPING: {label: 'declared', title: "Matched against the application's declared route mappings"},
  MASKED_PATH: {
    label: 'masked path',
    title: 'No route template matched, so every segment that looks like a value is masked'
  }
}

const {
  data,
  error,
  hasLoaded,
  items: exchanges,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList(
  'api/http-exchanges',
  'exchanges',
  () => ({
    q: filter.value.trim(),
    method: method.value,
    statusClass: statusClass.value,
    route: routeFilter.value
  }),
  {errorContext: 'Could not load HTTP exchanges'}
)

const lastFetched = ref(null)
const recordedCount = computed(() => data.value?.recorded ?? totalCount.value)
const unavailableReason = computed(() => data.value?.unavailableReason ?? null)
const subtitle = computed(
  () => `${formatNumber(totalCount.value)} visible · ${formatNumber(recordedCount.value)} recorded`
)

async function loadRoutes() {
  try {
    routesReport.value = await getJson('api/http-exchanges/routes')
    routesError.value = null
  } catch (e) {
    routesReport.value = null
    routesError.value = e?.message ? `Could not load route rankings: ${e.message}` : 'Could not load route rankings.'
  }
}

async function refreshExchanges() {
  await Promise.all([load(), loadRoutes()])
  if (!error.value) {
    lastFetched.value = Date.now()
  }
}

const routesAvailable = computed(() => Boolean(routesReport.value?.available && routesReport.value?.window))
const routeWindow = computed(() => routesReport.value?.window ?? null)
const routeNotes = computed(() => routesReport.value?.notes ?? [])
const allRoutes = computed(() => routesReport.value?.routes ?? [])

// The server ships the union of each criterion's top routes, so re-sorting here and slicing to
// topPerCriterion yields that criterion's exact top list without another round trip. A route scoring zero
// on the criterion did not earn a place in that ranking, so it is left out unless nothing scores at all.
const rankedRoutes = computed(() => {
  const rows = allRoutes.value
  const metric = rankingMetric.value
  const limit = routesReport.value?.topPerCriterion || rows.length
  const scored = rows.filter((row) => Number(row[metric]) > 0)
  return (scored.length ? scored : [...rows])
    .sort((a, b) => Number(b[metric] ?? 0) - Number(a[metric] ?? 0) || String(a.id).localeCompare(String(b.id)))
    .slice(0, limit)
})

const rankingMetricLabel = computed(
  () => ROUTE_METRICS.find((metric) => metric.key === rankingMetric.value)?.label ?? ''
)

const rankingMetricUnmeasured = computed(
  () => allRoutes.value.length > 0 && !allRoutes.value.some((row) => Number(row[rankingMetric.value]) > 0)
)

const hiddenRouteCount = computed(() =>
  Math.max((routesReport.value?.distinctRoutes ?? allRoutes.value.length) - rankedRoutes.value.length, 0)
)

const highlightedRouteHidden = computed(
  () => Boolean(highlightedRoute.value) && !rankedRoutes.value.some((row) => row.id === highlightedRoute.value)
)

const windowSummary = computed(() => {
  const w = routeWindow.value
  if (!w) return ''
  const parts = [`${formatNumber(w.summarizedExchanges)} retained ${plural(w.summarizedExchanges, 'exchange')}`]
  parts.push(w.bufferSize != null ? `buffer ${formatNumber(w.bufferSize)}` : 'buffer size not reported')
  parts.push(w.evicted != null ? `${formatNumber(w.evicted)} evicted` : 'evictions not reported')
  if (w.oldestTimestamp != null) {
    parts.push(`oldest ${formatTimestamp(w.oldestTimestamp)}`)
  }
  parts.push(`${formatNumber(w.hiddenSelfExchanges)} BootUI ${plural(w.hiddenSelfExchanges, 'exchange')} hidden`)
  return parts.join(' · ')
})

function plural(count, word) {
  return count === 1 ? word : `${word}s`
}

function routeSource(route) {
  return ROUTE_SOURCES[route.routeSource] ?? {label: route.routeSource || 'unknown', title: ''}
}

function formatAverageMs(value) {
  if (value == null) return '—'
  if (value < 1000) return `${Number(value).toFixed(1)} ms`
  return `${(value / 1000).toFixed(2)} s`
}

function formatShare(value) {
  return `${Number(value ?? 0).toFixed(1)}%`
}

async function showRouteExchanges(route) {
  routeFilter.value = route.id
  highlightedRoute.value = route.id
  await nextTick()
  exchangesHeading.value?.focus?.()
}

function clearRouteFilter() {
  routeFilter.value = ''
}

function profileLink(exchange) {
  return {path: '/activity', query: {request: exchange.id}}
}

function showsRouteLine(exchange) {
  return Boolean(exchange.route) && exchange.route !== exchange.path
}

const {autoRefresh, loading: refreshLoading, load: refreshNow} = useAutoRefresh(refreshExchanges)

function formatTimestamp(timestamp) {
  if (!timestamp) return '—'
  return new Date(timestamp).toLocaleString()
}

function formatDurationMs(durationMs) {
  if (durationMs == null) return '—'
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(2)} s`
}

function formatBytes(bytes) {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function statusBadgeClass(exchange) {
  return (
    {
      '1xx': 'text-bg-info',
      '2xx': 'text-bg-success',
      '3xx': 'text-bg-secondary',
      '4xx': 'text-bg-warning',
      '5xx': 'text-bg-danger'
    }[exchange.statusFamily] || 'text-bg-secondary'
  )
}

function methodBadgeClass(methodValue) {
  return (
    {
      GET: 'text-bg-success',
      POST: 'text-bg-primary',
      PUT: 'text-bg-warning',
      PATCH: 'text-bg-info',
      DELETE: 'text-bg-danger'
    }[methodValue] || 'text-bg-secondary'
  )
}

function displayPath(exchange) {
  if (!exchange.path) return '—'
  return exchange.query ? `${exchange.path}?${exchange.query}` : exchange.path
}

function headerValues(header) {
  if (!header.values?.length) {
    return header.masked ? '******' : 'Hidden'
  }
  return header.values.join(', ')
}

function hasMetadata(exchange) {
  return Boolean(exchange.traceId || exchange.remoteAddress || exchange.principal || exchange.sessionId)
}

function detailCount(exchange) {
  return (exchange.requestHeaders?.length || 0) + (exchange.responseHeaders?.length || 0)
}

function toggleDetails(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) {
    next.delete(id)
    if (copyFailureId.value === id) {
      copyFailureId.value = null
    }
  } else {
    next.add(id)
  }
  expanded.value = next
}

function isExpanded(id) {
  return expanded.value.has(id)
}

const {copiedKey, copyToClipboard} = useCopyToClipboard(4000)
const copyStatus = ref('')
const copyFailureId = ref(null)

/**
 * Builds the copyable command from retained metadata only. Called from the template for expanded
 * rows, so nothing is computed for exchanges the user has not opened, and nothing is sent anywhere.
 */
function curlFor(exchange) {
  return buildCurlCommand(exchange)
}

function curlKey(exchange) {
  return `curl-${exchange.id}`
}

async function copyCurl(exchange) {
  const {command, unavailableReason} = curlFor(exchange)
  if (!command) {
    // The control stays focusable, so a click must still say why nothing happened.
    copyStatus.value = unavailableReason || ''
    return
  }
  copyStatus.value = ''
  copyFailureId.value = null
  const copied = await copyToClipboard(command, curlKey(exchange))
  if (copied) {
    // Deliberately without the query string: recorded parameter values must not leak into feedback.
    copyStatus.value = `cURL template copied for ${exchange.method || 'the'} ${exchange.path || 'request'}.`
    return
  }
  copyFailureId.value = exchange.id
}

const route = useRoute()

/** A link from Live Activity, or any other panel, can name a route and a ranking to open on. */
function applyRouteQuery(query) {
  const linkedRoute = query?.route
  if (typeof linkedRoute === 'string' && linkedRoute) {
    routeFilter.value = linkedRoute
    highlightedRoute.value = linkedRoute
  }
  const rank = query?.rank
  if (typeof rank === 'string' && ROUTE_METRICS.some((metric) => metric.key === rank)) {
    rankingMetric.value = rank
  }
}

// Applied before the first load and before the filter watcher exists, so a linked route is the first
// list the panel requests rather than a second one.
applyRouteQuery(route?.query)

watch([filter, method, statusClass, routeFilter], scheduleReload)

watch(
  () => route?.query?.route,
  () => applyRouteQuery(route?.query)
)

onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-arrow-left-right"
      title="HTTP Exchanges"
      :subtitle="subtitle"
      :loading="refreshLoading || loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="refreshNow"
    />

    <p aria-live="polite" class="visually-hidden http-exchanges-copy-status" role="status">{{ copyStatus }}</p>

    <div v-if="unavailableReason" class="alert alert-warning" role="alert">
      <strong>HTTP exchange recording is unavailable.</strong>
      <span class="d-block small">{{ unavailableReason }}</span>
    </div>

    <div v-if="routesError" class="alert alert-warning small py-2">{{ routesError }}</div>

    <section v-if="routesAvailable" aria-labelledby="http-routes-heading" class="mb-4 http-routes">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
        <h3 id="http-routes-heading" class="h5 mb-0">
          Routes <span class="badge bg-secondary">{{ formatNumber(rankedRoutes.length) }}</span>
        </h3>
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small mb-0 text-muted" for="http-routes-metric">Rank by</label>
          <select id="http-routes-metric" v-model="rankingMetric" class="form-select form-select-sm">
            <option v-for="metric in ROUTE_METRICS" :key="metric.key" :value="metric.key">
              {{ metric.label }}
            </option>
          </select>
        </div>
      </div>

      <p class="text-muted small mb-2 http-routes-window">
        Top routes by {{ rankingMetricLabel.toLowerCase() }} over the retained window ({{ windowSummary }}).
      </p>

      <div v-if="rankingMetricUnmeasured" class="text-muted small mb-1">
        <i aria-hidden="true" class="bi bi-info-circle me-1"></i>No retained route records a non-zero
        {{ rankingMetricLabel.toLowerCase() }} in this window, so the routes are listed unranked.
      </div>

      <details v-if="routeNotes.length" class="small text-muted mb-2 http-routes-notes">
        <summary>How these figures are computed</summary>
        <ul class="mb-0 mt-1 ps-3">
          <li v-for="note in routeNotes" :key="note">{{ note }}</li>
        </ul>
      </details>

      <div v-if="!allRoutes.length" class="alert alert-secondary small mt-2 mb-0">
        No application request is retained yet, so there is no route to rank. Send a request to the application and
        refresh this panel.
      </div>

      <div v-else class="table-responsive">
        <table class="table table-sm table-hover align-middle http-routes-table">
          <thead>
            <tr>
              <th scope="col">Route</th>
              <th class="text-end" scope="col">Requests</th>
              <th scope="col">Status</th>
              <th class="text-end text-nowrap" scope="col">Avg</th>
              <th class="text-end text-nowrap" scope="col">p50</th>
              <th class="text-end text-nowrap" scope="col">p95</th>
              <th class="text-end text-nowrap" scope="col">p99</th>
              <th class="text-end text-nowrap" scope="col">Max</th>
              <th class="text-end" scope="col">Share</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="routeRow in rankedRoutes"
              :key="routeRow.id"
              :aria-current="routeRow.id === highlightedRoute ? 'true' : undefined"
              :class="{'table-active http-routes-row-active': routeRow.id === highlightedRoute}"
              :data-route-id="routeRow.id"
            >
              <td>
                <span :class="methodBadgeClass(routeRow.method)" class="badge me-1">{{ routeRow.method }}</span>
                <code class="http-exchanges-path">{{ routeRow.route }}</code>
                <span
                  :title="routeSource(routeRow).title"
                  class="badge text-bg-light border text-body-secondary ms-1 http-routes-source"
                >
                  {{ routeSource(routeRow).label }}
                </span>
              </td>
              <td class="text-end">
                {{ formatNumber(routeRow.requests) }}
                <span v-if="routeRow.timedRequests < routeRow.requests" class="d-block small text-muted text-nowrap">
                  {{ formatNumber(routeRow.timedRequests) }} timed
                </span>
              </td>
              <td class="text-nowrap http-routes-status">
                <span v-if="routeRow.status2xx" class="badge text-bg-success" title="2xx responses">
                  {{ formatNumber(routeRow.status2xx) }}
                </span>
                <span v-if="routeRow.status3xx" class="badge text-bg-secondary" title="3xx responses">
                  {{ formatNumber(routeRow.status3xx) }}
                </span>
                <span v-if="routeRow.status4xx" class="badge text-bg-warning" title="4xx responses">
                  {{ formatNumber(routeRow.status4xx) }}
                </span>
                <span v-if="routeRow.status5xx" class="badge text-bg-danger" title="5xx responses">
                  {{ formatNumber(routeRow.status5xx) }}
                </span>
                <span v-if="routeRow.statusOther" class="badge text-bg-light border" title="Other statuses">
                  {{ formatNumber(routeRow.statusOther) }}
                </span>
                <span class="visually-hidden">
                  {{ routeRow.status2xx }} 2xx, {{ routeRow.status3xx }} 3xx, {{ routeRow.status4xx }} 4xx,
                  {{ routeRow.status5xx }} 5xx, {{ routeRow.statusOther }} other
                </span>
              </td>
              <td class="text-end text-nowrap">{{ formatAverageMs(routeRow.avgDurationMs) }}</td>
              <td class="text-end text-nowrap">{{ formatDurationMs(routeRow.p50DurationMs) }}</td>
              <td class="text-end text-nowrap">{{ formatDurationMs(routeRow.p95DurationMs) }}</td>
              <td class="text-end text-nowrap">{{ formatDurationMs(routeRow.p99DurationMs) }}</td>
              <td class="text-end text-nowrap">{{ formatDurationMs(routeRow.maxDurationMs) }}</td>
              <td class="text-end">{{ formatShare(routeRow.shareOfRetainedTimePercent) }}</td>
              <td class="text-end">
                <button
                  :aria-label="`Show the retained exchanges of ${routeRow.id}`"
                  :aria-pressed="routeFilter === routeRow.id ? 'true' : 'false'"
                  class="btn btn-outline-secondary btn-sm rounded-pill text-nowrap http-routes-exchanges-link"
                  type="button"
                  @click="showRouteExchanges(routeRow)"
                >
                  <i aria-hidden="true" class="bi bi-list-ul me-1"></i>Exchanges
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="hiddenRouteCount > 0" class="text-muted small mb-0 http-routes-truncation">
        Showing {{ formatNumber(rankedRoutes.length) }} of {{ formatNumber(routesReport.distinctRoutes) }} routes;
        {{ formatNumber(hiddenRouteCount) }} more {{ hiddenRouteCount === 1 ? 'route is' : 'routes are' }} retained but
        not among the top {{ formatNumber(routesReport.topPerCriterion) }} by {{ rankingMetricLabel.toLowerCase() }}.
      </p>
      <p v-if="highlightedRouteHidden" class="text-muted small mb-0 http-routes-linked-hidden">
        The linked route <code>{{ highlightedRoute }}</code> is not among the routes shown for this ranking.
      </p>
    </section>

    <h3 ref="exchangesHeading" class="h5 mb-2 http-exchanges-heading" tabindex="-1">Exchanges</h3>

    <div
      v-if="routeFilter"
      class="alert alert-secondary d-flex flex-wrap align-items-center gap-2 py-2 small http-exchanges-route-filter"
    >
      <span>
        Showing only route <code>{{ routeFilter }}</code>
      </span>
      <button class="btn btn-outline-secondary btn-sm rounded-pill ms-auto" type="button" @click="clearRouteFilter">
        <i aria-hidden="true" class="bi bi-x-lg me-1"></i>Show every route
      </button>
    </div>

    <div class="row g-2 mb-3">
      <div class="col-lg-6">
        <label class="form-label" for="http-exchanges-filter">Path or trace filter</label>
        <input
          id="http-exchanges-filter"
          v-model="filter"
          class="form-control"
          placeholder="Filter by path, query, URL, or trace id…"
        />
      </div>
      <div class="col-sm-6 col-lg-3">
        <label class="form-label" for="http-exchanges-method">Method</label>
        <select id="http-exchanges-method" v-model="method" class="form-select">
          <option value="">All methods</option>
          <option>GET</option>
          <option>POST</option>
          <option>PUT</option>
          <option>PATCH</option>
          <option>DELETE</option>
          <option>OPTIONS</option>
          <option>HEAD</option>
        </select>
      </div>
      <div class="col-sm-6 col-lg-3">
        <label class="form-label" for="http-exchanges-status">Status</label>
        <select id="http-exchanges-status" v-model="statusClass" class="form-select">
          <option value="">All statuses</option>
          <option value="2xx">2xx success</option>
          <option value="3xx">3xx redirect</option>
          <option value="4xx">4xx client error</option>
          <option value="5xx">5xx server error</option>
        </select>
      </div>
    </div>

    <PanelSkeleton v-if="loading && !hasLoaded" :rows="8" />

    <div v-else class="table-responsive">
      <table class="table table-sm align-middle http-exchanges-table">
        <thead>
          <tr>
            <th>Time</th>
            <th>Method</th>
            <th>Path</th>
            <th>Status</th>
            <th>Duration</th>
            <th>Size</th>
            <th>Trace</th>
            <th>Profile</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="exchange in exchanges" :key="exchange.id">
            <tr>
              <td class="text-nowrap small">{{ formatTimestamp(exchange.timestamp) }}</td>
              <td>
                <span :class="methodBadgeClass(exchange.method)" class="badge">{{ exchange.method || 'ANY' }}</span>
              </td>
              <td>
                <code class="http-exchanges-path">{{ displayPath(exchange) }}</code>
                <span v-if="showsRouteLine(exchange)" class="d-block small text-muted http-exchanges-route">
                  route <code>{{ exchange.route }}</code>
                </span>
              </td>
              <td>
                <span :class="statusBadgeClass(exchange)" class="badge">{{ exchange.status }}</span>
              </td>
              <td class="text-nowrap">{{ formatDurationMs(exchange.durationMs) }}</td>
              <td class="text-nowrap">{{ formatBytes(exchange.responseSizeBytes) }}</td>
              <td>
                <code v-if="exchange.traceId" class="small">{{ exchange.traceId }}</code>
                <span v-else class="text-muted">—</span>
              </td>
              <td>
                <router-link
                  :aria-label="`Open the request profile of ${exchange.method || 'the'} ${exchange.path || 'request'}`"
                  :to="profileLink(exchange)"
                  class="btn btn-outline-secondary btn-sm rounded-pill text-nowrap http-exchanges-profile-link"
                >
                  <i aria-hidden="true" class="bi bi-diagram-3 me-1"></i>Profile
                </router-link>
              </td>
              <td class="text-end">
                <button
                  :aria-expanded="isExpanded(exchange.id)"
                  class="btn btn-outline-secondary btn-sm rounded-pill http-exchanges-detail-toggle"
                  type="button"
                  @click="toggleDetails(exchange.id)"
                >
                  <i :class="['bi', isExpanded(exchange.id) ? 'bi-chevron-up' : 'bi-card-text', 'me-1']"></i>
                  {{ isExpanded(exchange.id) ? 'Hide details' : 'View details' }}
                  <span class="badge rounded-pill text-bg-light ms-1">{{ detailCount(exchange) }}</span>
                </button>
              </td>
            </tr>
            <tr v-if="isExpanded(exchange.id)" :key="`${exchange.id}-details`" class="http-exchanges-detail-row">
              <td colspan="9">
                <div class="http-exchanges-detail">
                  <div class="http-exchanges-curl mb-3">
                    <div class="d-flex flex-wrap align-items-center gap-2">
                      <button
                        :aria-describedby="
                          curlFor(exchange).unavailableReason ? `curl-unavailable-${exchange.id}` : undefined
                        "
                        :aria-disabled="curlFor(exchange).command ? undefined : 'true'"
                        :class="[
                          'btn btn-outline-secondary btn-sm rounded-pill http-exchanges-curl-copy',
                          {'http-exchanges-curl-copy-inactive': !curlFor(exchange).command}
                        ]"
                        type="button"
                        @click="copyCurl(exchange)"
                      >
                        <i
                          :class="['bi', copiedKey === curlKey(exchange) ? 'bi-check2' : 'bi-terminal', 'me-1']"
                          aria-hidden="true"
                        ></i>
                        {{ copiedKey === curlKey(exchange) ? 'Copied' : 'Copy as cURL' }}
                      </button>
                      <span class="small text-muted">
                        A safe template built from retained metadata. Nothing is sent and no state changes.
                      </span>
                    </div>
                    <pre
                      v-if="curlFor(exchange).command"
                      aria-label="Generated cURL command"
                      class="small mb-0 mt-2 http-exchanges-curl-command"
                      role="group"
                      tabindex="0"
                    ><code>{{ curlFor(exchange).command }}</code></pre>
                    <p
                      v-if="curlFor(exchange).unavailableReason"
                      :id="`curl-unavailable-${exchange.id}`"
                      class="small mb-0 mt-2 http-exchanges-curl-unavailable"
                    >
                      {{ curlFor(exchange).unavailableReason }}
                    </p>
                    <ul v-else class="small text-muted mb-0 mt-2 ps-3 http-exchanges-curl-notes">
                      <li v-for="note in curlFor(exchange).notes" :key="note">{{ note }}</li>
                    </ul>
                    <div
                      v-if="copyFailureId === exchange.id"
                      class="alert alert-danger small py-2 mt-2 mb-0"
                      role="alert"
                    >
                      The browser blocked clipboard access, so nothing was copied. Allow clipboard permission for this
                      page, or select the command shown above and copy it manually.
                    </div>
                  </div>

                  <div v-if="hasMetadata(exchange)" class="mb-3">
                    <h3 class="h6">Metadata</h3>
                    <dl class="row small mb-0">
                      <dt v-if="exchange.remoteAddress" class="col-sm-3">Remote address</dt>
                      <dd v-if="exchange.remoteAddress" class="col-sm-9">{{ exchange.remoteAddress }}</dd>
                      <dt v-if="exchange.principal" class="col-sm-3">Principal</dt>
                      <dd v-if="exchange.principal" class="col-sm-9">{{ exchange.principal }}</dd>
                      <dt v-if="exchange.sessionId" class="col-sm-3">Session</dt>
                      <dd v-if="exchange.sessionId" class="col-sm-9">{{ exchange.sessionId }}</dd>
                      <dt v-if="exchange.traceId" class="col-sm-3">Trace id</dt>
                      <dd v-if="exchange.traceId" class="col-sm-9">
                        <code>{{ exchange.traceId }}</code>
                      </dd>
                    </dl>
                  </div>

                  <div class="row g-3">
                    <div class="col-lg-6">
                      <h3 class="h6">Request headers</h3>
                      <dl class="headers-list small mb-0">
                        <template
                          v-for="header in exchange.requestHeaders"
                          :key="`request-${exchange.id}-${header.name}`"
                        >
                          <dt>{{ header.name }}</dt>
                          <dd>
                            <code :class="{'text-muted': !header.values?.length}">{{ headerValues(header) }}</code>
                          </dd>
                        </template>
                        <p v-if="!exchange.requestHeaders.length" class="text-muted mb-0">
                          No request headers recorded.
                        </p>
                      </dl>
                    </div>
                    <div class="col-lg-6">
                      <h3 class="h6">Response headers</h3>
                      <dl class="headers-list small mb-0">
                        <template
                          v-for="header in exchange.responseHeaders"
                          :key="`response-${exchange.id}-${header.name}`"
                        >
                          <dt>{{ header.name }}</dt>
                          <dd>
                            <code :class="{'text-muted': !header.values?.length}">{{ headerValues(header) }}</code>
                          </dd>
                        </template>
                        <p v-if="!exchange.responseHeaders.length" class="text-muted mb-0">
                          No response headers recorded.
                        </p>
                      </dl>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!loading && !exchanges.length">
            <td class="text-center text-muted py-4" colspan="9">
              No HTTP exchanges match your filters. Send a request to the application and refresh this panel.
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <ServerListFooter
      v-if="!loading && !unavailableReason"
      :loading="loadingMore"
      :matched="matchedCount"
      :page-size="pageSize"
      :shown="shownCount"
      :total="totalCount"
      item-label="exchanges"
      @load-more="loadMore"
    />
  </div>
</template>

<style scoped>
.http-exchanges-table {
  min-width: 980px;
}

.http-exchanges-path {
  word-break: break-all;
}

.http-routes-table {
  min-width: 980px;
}

.http-routes-status .badge + .badge {
  margin-left: 0.25rem;
}

.http-routes-notes summary {
  cursor: pointer;
  width: fit-content;
}

.http-exchanges-heading:focus-visible {
  outline: 2px solid var(--bs-primary);
  outline-offset: 2px;
}

.http-exchanges-detail-toggle {
  white-space: nowrap;
}

.http-exchanges-detail-toggle .badge {
  font-size: 0.68rem;
}

.http-exchanges-detail-row > td {
  padding-top: 0;
}

.http-exchanges-curl {
  border-bottom: 1px solid var(--bs-border-color);
  padding-bottom: 0.75rem;
}

.http-exchanges-curl-unavailable {
  color: var(--bootui-warning-text-strong, #6f5300);
}

.http-exchanges-curl-copy-inactive {
  opacity: 0.65;
}

.http-exchanges-curl-command {
  background: var(--bs-body-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: 0.5rem;
  color: var(--bs-body-color);
  max-height: 12rem;
  overflow: auto;
  padding: 0.5rem 0.75rem;
  white-space: pre;
}

.http-exchanges-curl-notes li + li {
  margin-top: 0.15rem;
}

.http-exchanges-detail {
  background: var(--bs-tertiary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: 0.5rem;
  padding: 1rem;
  box-shadow: var(--bootui-shadow-sm);
}

.headers-list {
  display: grid;
  grid-template-columns: minmax(8rem, 35%) minmax(0, 1fr);
  column-gap: 0.75rem;
  row-gap: 0.35rem;
}

.headers-list dt,
.headers-list dd {
  min-width: 0;
  word-break: break-word;
}
</style>
