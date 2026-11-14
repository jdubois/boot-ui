<script setup>
import LowFootprintBanner from './components/LowFootprintBanner.vue'
import {computed, inject, nextTick, onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {getJson} from '../api.js'
import CaptureRetention from './components/CaptureRetention.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ServerListFooter from './components/ServerListFooter.vue'
import {formatNumber} from '../utils/format.js'
import {buildCurlCommand} from '../utils/curlCommand.js'
import {buildExchangeProbeLink, isHttpProbeWritable} from '../utils/httpProbePrefill.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'

const panels = inject('panels', ref(null))
const httpProbeWritable = computed(() => isHttpProbeWritable(panels.value))
const filter = ref('')
const method = ref('')
const statusClass = ref('')
const routeFilter = ref('')
const queryPending = ref(true)
const expanded = ref(new Set())
const exchangesPanel = ref(null)
const drawerClose = ref(null)
let routeTrigger = null
let drawerPointerDownOnBackdrop = false

const routesReport = ref(null)
const routesError = ref(null)
const rankingMetric = ref('requests')
const highlightedRoute = ref('')

// Each metric maps to the server's ranking criterion, whose top routes carry it in topFor.
const ROUTE_METRICS = [
  {key: 'requests', label: 'Requests', criterion: 'REQUESTS'},
  {key: 'totalDurationMs', label: 'Total time', criterion: 'TOTAL_DURATION'},
  {key: 'p95DurationMs', label: 'p95 time', criterion: 'P95_DURATION'},
  {key: 'maxDurationMs', label: 'Slowest request', criterion: 'MAX_DURATION'},
  {key: 'errorCount', label: 'Errors', criterion: 'ERROR_COUNT'}
]

// Every refresh is one more request in the exchange buffer, and Spring records BootUI's own requests there
// before hiding them, so rankings follow the list at a slower cadence unless the user asks for them.
const ROUTES_MIN_INTERVAL_MS = 30_000

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
    method: routeFilter.value ? '' : method.value,
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

let lastRoutesFetch = 0
let forceNextRoutes = false
// Only the newest ranking request may publish, so a slower, older response can never replace a newer one,
// such as the response that pins a just-linked route.
let routesGeneration = 0
const routesFetchedAt = ref(null)

async function loadRoutes(force = false) {
  const now = Date.now()
  if (!force && routesReport.value && now - lastRoutesFetch < ROUTES_MIN_INTERVAL_MS) return
  lastRoutesFetch = now
  // Pinning the linked route makes the server return its row even when it is outside every top list.
  const pinned = highlightedRoute.value
  const url = pinned ? `api/http-exchanges/routes?route=${encodeURIComponent(pinned)}` : 'api/http-exchanges/routes'
  const generation = ++routesGeneration
  try {
    const report = await getJson(url)
    if (generation !== routesGeneration) return
    routesReport.value = report
    routesError.value = null
    routesFetchedAt.value = Date.now()
  } catch (e) {
    if (generation !== routesGeneration) return
    routesReport.value = null
    routesError.value = e?.message ? `Could not load route rankings: ${e.message}` : 'Could not load route rankings.'
  }
}

async function refreshExchanges() {
  const force = forceNextRoutes
  forceNextRoutes = false
  await Promise.all([load(), loadRoutes(force)])
  if (!error.value) {
    lastFetched.value = Date.now()
  }
}

/** Pressing refresh is an explicit request for current evidence, so it bypasses the ranking cadence. */
function refreshAll() {
  forceNextRoutes = true
  return refreshNow()
}

const routesAvailable = computed(() => Boolean(routesReport.value?.available && routesReport.value?.window))
const routeWindow = computed(() => routesReport.value?.window ?? null)
const routeNotes = computed(() => routesReport.value?.notes ?? [])
const allRoutes = computed(() => routesReport.value?.routes ?? [])

const rankingMetricDef = computed(() => ROUTE_METRICS.find((metric) => metric.key === rankingMetric.value))
const rankingMetricLabel = computed(() => rankingMetricDef.value?.label ?? '')

// Plain UTF-16 code-unit order, exactly Java's String.compareTo, so ties order the same way here as in the
// server's ranking, the MCP tool, and the CLI, whatever the browser's locale.
function compareIds(a, b) {
  const left = String(a)
  const right = String(b)
  return left < right ? -1 : left > right ? 1 : 0
}

function byMetric(metric) {
  return (a, b) => Number(b[metric] ?? 0) - Number(a[metric] ?? 0) || compareIds(a.id, b.id)
}

// The server marks each criterion's own top routes in topFor, so the list for a criterion is exactly the
// server's, never a re-slice of the union that could drift from it on ties.
const scoredRoutes = computed(() => {
  const criterion = rankingMetricDef.value?.criterion
  return allRoutes.value.filter((row) => row.topFor?.includes(criterion))
})

const rankingMetricUnmeasured = computed(() => allRoutes.value.length > 0 && scoredRoutes.value.length === 0)

// When nothing scores on the criterion, such as errors in an error-free window, the routes are listed unranked
// rather than as an empty table.
const rankedRoutes = computed(() =>
  [...(rankingMetricUnmeasured.value ? allRoutes.value.filter((row) => row.topFor?.length) : scoredRoutes.value)].sort(
    byMetric(rankingMetric.value)
  )
)

// A route a link names comes back from the server even when it is outside every top list, and is shown
// after the ranking, so the link always lands on its row.
const linkedRoute = computed(() => {
  if (!highlightedRoute.value || rankedRoutes.value.some((row) => row.id === highlightedRoute.value)) return null
  return allRoutes.value.find((row) => row.id === highlightedRoute.value) ?? null
})

const displayedRoutes = computed(() =>
  linkedRoute.value ? [...rankedRoutes.value, linkedRoute.value] : rankedRoutes.value
)

const distinctRoutes = computed(() => routesReport.value?.distinctRoutes ?? allRoutes.value.length)

// Why routes are missing from this ranking: the per-criterion cap, or a zero score on the criterion.
// Why retained routes are not on screen: the per-criterion cap, a zero score on the criterion, or, when nothing
// scores, the bounded union the server returned. Counted against the rows actually displayed, pinned row included.
const rankingGap = computed(() => {
  const missing = distinctRoutes.value - displayedRoutes.value.length
  if (missing <= 0) return null
  if (rankingMetricUnmeasured.value) return {reason: 'unranked', missing}
  // The server lists at most topPerCriterion routes for a criterion, taken from those that score on it. A
  // shorter list therefore already holds every scoring route, and the rest record nothing for this criterion.
  const capped = rankedRoutes.value.length >= (routesReport.value?.topPerCriterion ?? Infinity)
  return {reason: capped ? 'capped' : 'unscored', missing}
})

const highlightedRouteMissing = computed(
  () =>
    Boolean(highlightedRoute.value) &&
    Boolean(routesReport.value) &&
    !allRoutes.value.some((row) => row.id === highlightedRoute.value)
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
  if (routesFetchedAt.value != null) {
    parts.push(`ranked ${new Date(routesFetchedAt.value).toLocaleTimeString()}`)
  }
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

const route = useRoute()
const router = useRouter()

// The drill-down lives in the URL, so it survives a reload, can be shared, and clears when the panel is opened
// without one. Without a router, as in isolated component tests, it is applied locally.
function setRouteQuery(routeId) {
  const query = {...(route?.query ?? {})}
  if (routeId) {
    query.route = routeId
    query.rank = rankingMetric.value
  } else {
    delete query.route
    delete query.rank
  }
  if (router?.replace) {
    return router.replace({query})
  }
  applyRouteQuery(query)
  return Promise.resolve()
}

async function showRouteExchanges(routeRow, event) {
  routeTrigger = event?.currentTarget ?? document.activeElement
  await setRouteQuery(routeRow.id)
  // The list below is fetched now, so bring the row's counts up to the same moment.
  loadRoutes(true)
}

function clearRouteFilter() {
  return setRouteQuery(null)
}

async function syncRouteDrawer(routeId = routeFilter.value) {
  await nextTick()
  const el = exchangesPanel.value
  if (routeFilter.value && el) {
    if (!el.open) {
      if (typeof el.showModal === 'function') el.showModal()
      else el.setAttribute('open', '')
    }
    drawerClose.value?.focus()
  } else {
    const linkedTrigger = [...document.querySelectorAll('.http-routes-table [data-route-id]')]
      .find((row) => row.getAttribute('data-route-id') === routeId)
      ?.querySelector('.http-routes-exchanges-link')
    const target = routeTrigger?.isConnected
      ? routeTrigger
      : (linkedTrigger ?? document.querySelector('.http-routes-exchanges-link'))
    target?.focus()
    routeTrigger = null
  }
}

function onDrawerBackdrop(event) {
  const startedOnBackdrop = drawerPointerDownOnBackdrop
  drawerPointerDownOnBackdrop = false
  if (routeFilter.value && startedOnBackdrop && event.target === exchangesPanel.value) clearRouteFilter()
}

function profileLink(exchange) {
  return {path: '/activity', query: {request: exchange.id}}
}

function exchangeProbeLink(exchange) {
  return buildExchangeProbeLink(exchange)
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
  return Boolean(
    exchange.requestId || exchange.traceId || exchange.remoteAddress || exchange.principal || exchange.sessionId
  )
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
    if (routeFilter.value) next.clear()
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

/** A link from Live Activity, or any other panel, can name a route and a ranking to open on. */
function applyRouteQuery(query) {
  const linked = typeof query?.route === 'string' ? query.route : ''
  routeFilter.value = linked
  highlightedRoute.value = linked
  const rank = query?.rank
  if (typeof rank === 'string' && rank !== rankingMetric.value && ROUTE_METRICS.some((metric) => metric.key === rank)) {
    rankingMetric.value = rank
  }
}

// While a drill-down is in the URL, the chosen ranking travels with it, so a reload or a later URL change
// never snaps the select back to the ranking a link opened on.
watch(rankingMetric, (metric) => {
  const query = route?.query ?? {}
  if (query.route && query.rank !== metric && router?.replace) {
    router.replace({query: {...query, rank: metric}})
  }
})

// Applied before the first load and before the filter watcher exists, so a linked route is the first
// list the panel requests rather than a second one.
applyRouteQuery(route?.query)

watch(
  () => route?.query?.q,
  (prefill) => {
    filter.value = typeof prefill === 'string' ? prefill : ''
  },
  {immediate: true}
)

watch([filter, method, statusClass, routeFilter], () => {
  queryPending.value = true
  scheduleReload()
})

watch(loading, (isLoading) => {
  if (!isLoading && !error.value) queryPending.value = false
})

watch(routeFilter, (nextRoute, previousRoute) => {
  const el = exchangesPanel.value
  // Release native modal inertness before Vue replaces the dialog with the inline section.
  if (el?.open) {
    if (typeof el.close === 'function') el.close()
    else el.removeAttribute('open')
  }
  expanded.value = new Set()
  copyFailureId.value = null
  copyStatus.value = ''
  syncRouteDrawer(nextRoute || previousRoute)
})

watch(
  () => [route?.query?.route, route?.query?.rank],
  ([nextRoute], [previousRoute]) => {
    applyRouteQuery(route?.query)
    // A newly linked route may sit outside every top list, so ask the server to pin its row now.
    if (nextRoute && nextRoute !== previousRoute && !allRoutes.value.some((row) => row.id === nextRoute)) {
      loadRoutes(true)
    }
  }
)

onMounted(() => {
  if (routeFilter.value) syncRouteDrawer()
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
      @refresh="refreshAll"
    />
    <LowFootprintBanner />

    <div v-if="unavailableReason && !routeFilter" class="alert alert-warning" role="alert">
      <strong>HTTP exchange recording is unavailable.</strong>
      <span class="d-block small">{{ unavailableReason }}</span>
    </div>

    <CaptureRetention :retention="data?.retention ?? null" noun="exchanges" reserved-for="5xx or slow" />

    <div v-if="routesError" class="alert alert-warning small py-2">{{ routesError }}</div>

    <section v-if="routesAvailable" aria-labelledby="http-routes-heading" class="mb-4 http-routes">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
        <h3 id="http-routes-heading" class="h5 mb-0">
          Routes <span class="badge bg-secondary">{{ formatNumber(rankedRoutes.length) }}</span>
        </h3>
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small mb-0 text-muted text-nowrap" for="http-routes-metric">Rank by</label>
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
              v-for="routeRow in displayedRoutes"
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
                <span
                  v-if="linkedRoute && routeRow.id === linkedRoute.id"
                  class="d-block small text-muted http-routes-linked-note"
                >
                  Linked route, outside the top {{ formatNumber(routesReport.topPerCriterion) }} by
                  {{ rankingMetricLabel.toLowerCase() }}
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
                  aria-haspopup="dialog"
                  @click="showRouteExchanges(routeRow, $event)"
                >
                  <i aria-hidden="true" class="bi bi-list-ul me-1"></i>Exchanges
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="rankingGap" class="text-muted small mb-0 http-routes-truncation">
        <template v-if="rankingGap.reason === 'capped'">
          Showing the top {{ formatNumber(rankedRoutes.length) }} of {{ formatNumber(distinctRoutes) }} retained routes
          by {{ rankingMetricLabel.toLowerCase() }}; {{ formatNumber(rankingGap.missing) }} more
          {{ rankingGap.missing === 1 ? 'route is' : 'routes are' }} not shown.
        </template>
        <template v-else-if="rankingGap.reason === 'unranked'">
          Showing {{ formatNumber(displayedRoutes.length) }} of {{ formatNumber(distinctRoutes) }} retained routes;
          {{ formatNumber(rankingGap.missing) }} more {{ rankingGap.missing === 1 ? 'route is' : 'routes are' }} not
          shown.
        </template>
        <template v-else>
          {{ formatNumber(rankingGap.missing) }} more retained
          {{ rankingGap.missing === 1 ? 'route records' : 'routes record' }} no
          {{ rankingMetricLabel.toLowerCase() }} in this window, so
          {{ rankingGap.missing === 1 ? 'it is' : 'they are' }} not shown.
        </template>
      </p>
      <p v-if="highlightedRouteMissing" class="text-muted small mb-0 http-routes-linked-hidden">
        The linked route <code>{{ highlightedRoute }}</code> has no retained exchange in this window.
      </p>
    </section>

    <component
      :is="routeFilter ? 'dialog' : 'section'"
      ref="exchangesPanel"
      :class="{'http-route-drawer': routeFilter}"
      :aria-labelledby="routeFilter ? 'http-route-drawer-title' : 'http-exchanges-heading'"
      :aria-describedby="routeFilter ? 'http-route-drawer-route' : undefined"
      @cancel.prevent="clearRouteFilter"
      @keydown.esc.prevent.stop="routeFilter && clearRouteFilter()"
      @pointerdown="drawerPointerDownOnBackdrop = $event.target === exchangesPanel"
      @pointercancel="drawerPointerDownOnBackdrop = false"
      @click="onDrawerBackdrop"
    >
      <p aria-live="polite" class="visually-hidden http-exchanges-copy-status" role="status">{{ copyStatus }}</p>
      <header v-if="routeFilter" class="http-route-drawer-header http-exchanges-route-filter">
        <div class="http-route-drawer-title">
          <h2 id="http-route-drawer-title" class="h5 mb-1">Route exchanges</h2>
          <code id="http-route-drawer-route">{{ routeFilter }}</code>
          <p class="small text-muted mb-0 mt-1">Retained requests only. Open a request for its headers and metadata.</p>
        </div>
        <button
          ref="drawerClose"
          aria-label="Close route exchanges"
          class="btn btn-outline-secondary btn-sm"
          type="button"
          @click="clearRouteFilter"
        >
          <i aria-hidden="true" class="bi bi-x-lg me-1"></i>Close
        </button>
      </header>

      <div :class="{'http-route-drawer-body': routeFilter}">
        <h3 id="http-exchanges-heading" class="h5 mb-2 http-exchanges-heading">
          {{ routeFilter ? 'Retained exchanges' : 'Exchanges' }}
        </h3>
        <div
          v-if="routeFilter && (error || unavailableReason)"
          :class="['alert', error ? 'alert-danger' : 'alert-warning']"
          role="alert"
        >
          <template v-if="error">{{ error }}</template>
          <template v-else>
            <strong>HTTP exchange recording is unavailable.</strong>
            <span class="d-block small">{{ unavailableReason }}</span>
          </template>
          <button
            class="btn btn-sm btn-outline-secondary d-block mt-2"
            :disabled="refreshLoading || loading"
            type="button"
            @click="refreshAll"
          >
            Retry
          </button>
        </div>
        <div class="row g-2 mb-3">
          <div :class="routeFilter ? 'col-lg-8' : 'col-lg-6'">
            <label class="form-label" for="http-exchanges-filter">Path, trace, or request id filter</label>
            <input
              id="http-exchanges-filter"
              v-model="filter"
              aria-describedby="http-exchanges-filter-tip"
              class="form-control"
              placeholder="Filter by path, query, URL, trace id, or X-BootUI-Request-Id…"
            />
            <div id="http-exchanges-filter-tip" class="form-text small http-exchanges-request-id-tip">
              Tip: paste <code>X-BootUI-Request-Id</code> from your browser DevTools Network response headers to find
              that exact request and open its profile.
            </div>
          </div>
          <div v-if="!routeFilter" class="col-sm-6 col-lg-3">
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
          <div class="col-sm-6" :class="routeFilter ? 'col-lg-4' : 'col-lg-3'">
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

        <PanelSkeleton v-if="loading && (!hasLoaded || queryPending)" :rows="8" />

        <div v-else-if="!queryPending && !unavailableReason" class="table-responsive">
          <table class="table table-sm align-middle http-exchanges-table">
            <thead>
              <tr>
                <th>Time</th>
                <th v-if="!routeFilter">Method</th>
                <th>Path</th>
                <th>Status</th>
                <th>Duration</th>
                <th v-if="!routeFilter">Size</th>
                <th v-if="!routeFilter">Trace</th>
                <th v-if="!routeFilter">Profile</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="exchange in exchanges" :key="exchange.id">
                <tr class="http-exchanges-row">
                  <td class="text-nowrap small http-exchanges-time">{{ formatTimestamp(exchange.timestamp) }}</td>
                  <td v-if="!routeFilter">
                    <span :class="methodBadgeClass(exchange.method)" class="badge">{{ exchange.method || 'ANY' }}</span>
                  </td>
                  <td class="http-exchanges-request-path">
                    <code class="http-exchanges-path">{{ displayPath(exchange) }}</code>
                    <span
                      v-if="!routeFilter && showsRouteLine(exchange)"
                      class="d-block small text-muted http-exchanges-route"
                    >
                      route <code>{{ exchange.route }}</code>
                    </span>
                  </td>
                  <td class="http-exchanges-status">
                    <span :class="statusBadgeClass(exchange)" class="badge">{{ exchange.status }}</span>
                  </td>
                  <td class="text-nowrap http-exchanges-duration">{{ formatDurationMs(exchange.durationMs) }}</td>
                  <td v-if="!routeFilter" class="text-nowrap">{{ formatBytes(exchange.responseSizeBytes) }}</td>
                  <td v-if="!routeFilter">
                    <code v-if="exchange.traceId" class="small">{{ exchange.traceId }}</code>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td v-if="!routeFilter">
                    <router-link
                      :aria-label="`Open the request profile of ${exchange.method || 'the'} ${exchange.path || 'request'}`"
                      :to="profileLink(exchange)"
                      class="btn btn-outline-secondary btn-sm rounded-pill text-nowrap http-exchanges-profile-link"
                    >
                      <i aria-hidden="true" class="bi bi-diagram-3 me-1"></i>Profile
                    </router-link>
                  </td>
                  <td class="text-end http-exchanges-actions">
                    <button
                      :aria-expanded="isExpanded(exchange.id)"
                      :aria-controls="`exchange-detail-${exchange.id}`"
                      class="btn btn-outline-secondary btn-sm rounded-pill http-exchanges-detail-toggle"
                      type="button"
                      @click="toggleDetails(exchange.id)"
                    >
                      <i
                        :class="['bi', isExpanded(exchange.id) ? 'bi-chevron-up' : 'bi-card-text', 'me-1']"
                        aria-hidden="true"
                      ></i>
                      {{ isExpanded(exchange.id) ? 'Hide details' : 'View details' }}
                      <span class="badge rounded-pill text-bg-light ms-1">{{ detailCount(exchange) }}</span>
                    </button>
                  </td>
                </tr>
                <tr v-if="isExpanded(exchange.id)" :key="`${exchange.id}-details`" class="http-exchanges-detail-row">
                  <td :colspan="routeFilter ? 5 : 9">
                    <div :id="`exchange-detail-${exchange.id}`" class="http-exchanges-detail">
                      <div v-if="routeFilter" class="d-flex flex-wrap align-items-center gap-2 mb-3">
                        <router-link
                          :to="profileLink(exchange)"
                          class="btn btn-outline-secondary btn-sm http-exchanges-profile-link"
                        >
                          <i aria-hidden="true" class="bi bi-diagram-3 me-1"></i>Open request profile
                        </router-link>
                        <span class="small text-muted"
                          >Response size: {{ formatBytes(exchange.responseSizeBytes) }}</span
                        >
                      </div>
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
                          <router-link
                            v-if="httpProbeWritable && exchangeProbeLink(exchange)"
                            :aria-label="`Open in HTTP Probe: ${exchange.method || 'GET'} ${exchange.path || '/'}`"
                            :to="exchangeProbeLink(exchange)"
                            class="btn btn-outline-secondary btn-sm rounded-pill text-nowrap http-exchanges-probe-link"
                          >
                            <i aria-hidden="true" class="bi bi-send me-1"></i>Open in HTTP Probe
                          </router-link>
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
                          The browser blocked clipboard access, so nothing was copied. Allow clipboard permission for
                          this page, or select the command shown above and copy it manually.
                        </div>
                      </div>

                      <div v-if="hasMetadata(exchange)" class="mb-3">
                        <h3 class="h6">Metadata</h3>
                        <dl class="row small mb-0">
                          <dt v-if="exchange.requestId" class="col-sm-3">Request id</dt>
                          <dd v-if="exchange.requestId" class="col-sm-9">
                            <code>{{ exchange.requestId }}</code>
                          </dd>
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
                <td class="text-center text-muted py-4" :colspan="routeFilter ? 5 : 9">
                  No HTTP exchanges match your filters. Send a request to the application and refresh this panel.
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <ServerListFooter
          v-if="!queryPending && !unavailableReason"
          :loading="loadingMore"
          :matched="matchedCount"
          :page-size="pageSize"
          :shown="shownCount"
          :total="totalCount"
          item-label="exchanges"
          @load-more="loadMore"
        />
      </div>
    </component>
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

.http-route-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0;
  padding: 0;
  width: min(72rem, 100%);
  max-width: 100%;
  height: 100dvh;
  max-height: 100dvh;
  border: 0;
  border-left: 1px solid var(--bootui-border);
  background: var(--bootui-surface-solid);
  color: var(--bootui-text);
  box-shadow: var(--bootui-shadow-md);
}

.http-route-drawer[open] {
  display: flex;
  flex-direction: column;
}

.http-route-drawer::backdrop {
  background: rgba(15, 23, 42, 0.4);
}

.http-route-drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.25rem;
  border-bottom: 1px solid var(--bootui-border);
}

.http-route-drawer-title {
  min-width: 0;
  overflow-wrap: anywhere;
}

.http-route-drawer-header > button {
  flex-shrink: 0;
}

.http-route-drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 1.25rem;
}

.http-route-drawer .http-exchanges-table {
  min-width: 0;
}

.http-route-drawer .http-exchanges-detail {
  box-shadow: none;
}

@media (max-width: 767.98px) {
  .http-route-drawer .http-exchanges-table thead {
    position: absolute;
    clip-path: inset(50%);
    width: 1px;
    height: 1px;
    overflow: hidden;
  }

  .http-route-drawer .http-exchanges-table tbody,
  .http-route-drawer .http-exchanges-detail-row,
  .http-route-drawer .http-exchanges-detail-row > td {
    display: block;
  }

  .http-route-drawer .http-exchanges-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 0.5rem;
    padding: 0.85rem 0;
    border-bottom: 1px solid var(--bs-border-color);
  }

  .http-route-drawer .http-exchanges-row > td {
    padding: 0;
    border: 0;
    min-width: 0;
  }

  .http-route-drawer .http-exchanges-time {
    white-space: normal !important;
  }

  .http-route-drawer .http-exchanges-request-path {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .http-route-drawer .http-exchanges-actions {
    grid-column: 1 / -1;
    justify-self: start;
  }

  .http-route-drawer .http-exchanges-detail {
    padding: 0.85rem;
  }

  .http-route-drawer .headers-list {
    grid-template-columns: minmax(0, 1fr);
    gap: 0.25rem;
  }

  .http-route-drawer .headers-list dd {
    margin-bottom: 0.5rem;
  }
}
</style>
