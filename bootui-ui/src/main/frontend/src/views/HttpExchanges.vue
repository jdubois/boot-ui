<script setup>
import {computed, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import ServerListFooter from './components/ServerListFooter.vue'
import {formatNumber} from '../utils/format.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import {useTraceCorrelation} from '../utils/correlation.js'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'
import CorrelationBanner from './components/CorrelationBanner.vue'
import TraceIdTag from './components/TraceIdTag.vue'

const filter = ref('')
const method = ref('')
const statusClass = ref('')
const expanded = ref(new Set())

const {traceFilter, pivotTargets, focusTrace, clearTrace, pivotTo} = useTraceCorrelation('http-exchanges')

const {
  data,
  error,
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
    q: traceFilter.value || filter.value.trim(),
    method: method.value,
    statusClass: statusClass.value
  }),
  {errorContext: 'Could not load HTTP exchanges'}
)

const lastFetched = ref(null)
const recordedCount = computed(() => data.value?.recorded ?? totalCount.value)
const unavailableReason = computed(() => data.value?.unavailableReason ?? null)
const subtitle = computed(
  () => `${formatNumber(totalCount.value)} visible · ${formatNumber(recordedCount.value)} recorded`
)

async function refreshExchanges() {
  await load()
  if (!error.value) {
    lastFetched.value = Date.now()
  }
}

const {autoRefresh, loading: refreshLoading} = useAutoRefresh(refreshExchanges)

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
  } else {
    next.add(id)
  }
  expanded.value = next
}

function isExpanded(id) {
  return expanded.value.has(id)
}

watch([filter, method, statusClass, traceFilter], scheduleReload)
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
    >
      <template #actions>
        <AutoRefreshToggle v-model="autoRefresh" />
      </template>
    </PanelHeader>

    <div v-if="unavailableReason" class="alert alert-warning" role="alert">
      <strong>HTTP exchange recording is unavailable.</strong>
      <span class="d-block small">{{ unavailableReason }}</span>
    </div>

    <CorrelationBanner :trace-id="traceFilter" :targets="pivotTargets" @pivot="pivotTo" @clear="clearTrace" />

    <div class="row g-2 mb-3">
      <div class="col-lg-6">
        <label class="form-label">Path or trace filter</label>
        <input v-model="filter" class="form-control" placeholder="Filter by path, query, URL, or trace id…" />
      </div>
      <div class="col-sm-6 col-lg-3">
        <label class="form-label">Method</label>
        <select v-model="method" class="form-select">
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
        <label class="form-label">Status</label>
        <select v-model="statusClass" class="form-select">
          <option value="">All statuses</option>
          <option value="2xx">2xx success</option>
          <option value="3xx">3xx redirect</option>
          <option value="4xx">4xx client error</option>
          <option value="5xx">5xx server error</option>
        </select>
      </div>
    </div>

    <div class="table-responsive">
      <table class="table table-sm align-middle http-exchanges-table">
        <thead>
          <tr>
            <th>Time</th>
            <th>Method</th>
            <th>Path</th>
            <th>Status</th>
            <th>Duration</th>
            <th>Size</th>
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
                <div>
                  <code class="http-exchanges-path">{{ displayPath(exchange) }}</code>
                </div>
                <TraceIdTag v-if="exchange.traceId" :trace-id="exchange.traceId" class="mt-1" @correlate="focusTrace" />
              </td>
              <td>
                <span :class="statusBadgeClass(exchange)" class="badge">{{ exchange.status }}</span>
              </td>
              <td class="text-nowrap">{{ formatDurationMs(exchange.durationMs) }}</td>
              <td class="text-nowrap">{{ formatBytes(exchange.responseSizeBytes) }}</td>
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
              <td colspan="7">
                <div class="http-exchanges-detail">
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
                        <TraceIdTag :trace-id="exchange.traceId" :clickable="false" :short="false" />
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
            <td class="text-center text-muted py-4" colspan="7">
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

.http-exchanges-detail-toggle {
  white-space: nowrap;
}

.http-exchanges-detail-toggle .badge {
  font-size: 0.68rem;
}

.http-exchanges-detail-row > td {
  padding-top: 0;
}

.http-exchanges-detail {
  background: var(--bs-tertiary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: 0.5rem;
  padding: 1rem;
  box-shadow: 0 0.5rem 1rem rgba(0, 0, 0, 0.04);
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
