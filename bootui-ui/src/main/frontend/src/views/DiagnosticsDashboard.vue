<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

defineProps(panelProps)

const report = ref(null)
const error = ref(null)
const query = ref('')
const selectedId = ref(null)
const lastFetched = ref(null)

async function fetchDashboard() {
  error.value = null
  try {
    const q = query.value.trim()
    const res = await apiFetch('api/diagnostics-dashboard' + (q ? '?q=' + encodeURIComponent(q) : ''))
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
    if (selectedId.value && !requests.value.some((r) => r.id === selectedId.value)) {
      selectedId.value = null
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load the diagnostics dashboard')
  }
}

const requests = computed(() => (report.value && report.value.requests) || [])
const unattributed = computed(() => report.value && report.value.unattributed)
const sources = computed(() => (report.value && report.value.sources) || {})

const selected = computed(() => requests.value.find((r) => r.id === selectedId.value) || null)

const subtitle = computed(() => {
  if (!report.value || !report.value.available) return null
  const total = report.value.totalRequests || 0
  return `${formatNumber(total)} correlated request${total === 1 ? '' : 's'}`
})

const activeSources = computed(() => {
  const s = sources.value
  const labels = []
  if (s.httpExchanges) labels.push('HTTP Exchanges')
  if (s.sqlTrace) labels.push('SQL Trace')
  if (s.exceptions) labels.push('Exceptions')
  if (s.securityLogs) labels.push('Security Logs')
  if (s.traces) labels.push('Traces')
  if (s.logTail) labels.push('Log Tail')
  return labels
})

function select(id) {
  selectedId.value = selectedId.value === id ? null : id
}

function applySearch() {
  load()
}

function correlationBadge(correlation) {
  switch (correlation) {
    case 'TRACE':
      return {label: 'Trace-linked', cls: 'text-bg-success', title: 'Joined by distributed trace id (strong)'}
    case 'REQUEST':
      return {label: 'Request', cls: 'text-bg-primary', title: 'Anchored on an HTTP exchange, joined by path and time'}
    case 'THREAD':
      return {label: 'Heuristic', cls: 'text-bg-warning', title: 'Grouped by thread and time window (best-effort)'}
    default:
      return {label: 'Single', cls: 'text-bg-secondary', title: 'A single signal with no correlation'}
  }
}

function kindMeta(kind) {
  switch (kind) {
    case 'HTTP':
      return {icon: 'bi-arrow-left-right', cls: 'text-primary'}
    case 'SQL':
      return {icon: 'bi-database', cls: 'text-info'}
    case 'EXCEPTION':
      return {icon: 'bi-exclamation-octagon', cls: 'text-danger'}
    case 'SECURITY':
      return {icon: 'bi-shield-lock', cls: 'text-warning'}
    default:
      return {icon: 'bi-dot', cls: 'text-secondary'}
  }
}

function severityClass(severity) {
  switch (severity) {
    case 'ERROR':
      return 'text-danger'
    case 'WARN':
      return 'text-warning'
    default:
      return 'text-body-secondary'
  }
}

function requestTitle(req) {
  if (req.method && req.path) return `${req.method} ${req.path}`
  return req.label || req.id
}

const {autoRefresh, loading, load} = useAutoRefresh(fetchDashboard)
</script>

<template>
  <div>
    <PanelHeader
      v-model:auto-refresh="autoRefresh"
      icon="bi-binoculars-fill"
      title="Diagnostics dashboard"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      @refresh="load"
    />

    <PanelSkeleton v-if="loading && !report" />

    <template v-else-if="report">
      <div v-if="!report.available" class="alert alert-info small">
        The diagnostics dashboard has no signal sources to correlate.
        <span v-if="report.unavailableReason">{{ report.unavailableReason }}.</span>
        Enable the HTTP Exchanges, SQL Trace, Exceptions, Security Logs or Traces panels to populate it.
      </div>

      <template v-else>
        <div v-if="!report.tracingActive" class="alert alert-warning small d-flex align-items-start gap-2">
          <i class="bi bi-info-circle mt-1" aria-hidden="true"></i>
          <div>
            No distributed trace id was observed on the captured signals. Correlation falls back to thread and
            time-window heuristics, which can be approximate. Enable Micrometer tracing for precise request linking.
          </div>
        </div>

        <p class="small text-body-secondary mb-3">
          Correlating:
          <span v-for="(label, idx) in activeSources" :key="label">
            <span class="badge text-bg-light border">{{ label }}</span
            ><span v-if="idx < activeSources.length - 1"> </span>
          </span>
        </p>

        <div class="row g-2 mb-3">
          <div class="col">
            <input
              v-model="query"
              class="form-control form-control-sm"
              placeholder="Search by path, SQL, exception, principal, trace id…"
              @keyup.enter="applySearch"
            />
          </div>
          <div class="col-auto">
            <button class="btn btn-sm btn-outline-primary" type="button" @click="applySearch">Search</button>
          </div>
        </div>

        <div v-if="requests.length === 0" class="alert alert-secondary">
          No correlated requests yet. Exercise your application (and any matching the search) to populate the dashboard.
        </div>

        <div v-else class="row g-3">
          <div class="col-lg-5">
            <div class="list-group">
              <button
                v-for="req in requests"
                :key="req.id"
                type="button"
                class="list-group-item list-group-item-action"
                :class="{active: req.id === selectedId}"
                @click="select(req.id)"
              >
                <div class="d-flex justify-content-between align-items-start gap-2">
                  <span class="fw-semibold text-truncate">{{ requestTitle(req) }}</span>
                  <span
                    class="badge"
                    :class="correlationBadge(req.correlation).cls"
                    :title="correlationBadge(req.correlation).title"
                    >{{ correlationBadge(req.correlation).label }}</span
                  >
                </div>
                <div
                  class="small d-flex flex-wrap gap-2 mt-1"
                  :class="req.id === selectedId ? '' : 'text-body-secondary'"
                >
                  <span v-if="req.status">{{ req.status }}</span>
                  <span v-if="req.durationMs != null">{{ formatNumber(req.durationMs) }} ms</span>
                  <span v-if="req.principal"><i class="bi bi-person" aria-hidden="true"></i> {{ req.principal }}</span>
                  <span><i class="bi bi-clock" aria-hidden="true"></i> {{ formatClockTime(req.startTimestamp) }}</span>
                </div>
                <div
                  class="small d-flex flex-wrap gap-2 mt-1"
                  :class="req.id === selectedId ? '' : 'text-body-secondary'"
                >
                  <span v-if="req.sqlCount"><i class="bi bi-database" aria-hidden="true"></i> {{ req.sqlCount }}</span>
                  <span v-if="req.exceptionCount" class="text-danger"
                    ><i class="bi bi-exclamation-octagon" aria-hidden="true"></i> {{ req.exceptionCount }}</span
                  >
                  <span v-if="req.securityCount"
                    ><i class="bi bi-shield-lock" aria-hidden="true"></i> {{ req.securityCount }}</span
                  >
                  <span v-if="req.hasError" class="badge text-bg-danger">error</span>
                </div>
              </button>
            </div>
          </div>

          <div class="col-lg-7">
            <div v-if="!selected" class="alert alert-secondary">Select a request to see its unified timeline.</div>
            <div v-else class="card">
              <div class="card-header">
                <div class="d-flex justify-content-between align-items-start gap-2">
                  <span class="fw-semibold">{{ requestTitle(selected) }}</span>
                  <span class="badge" :class="correlationBadge(selected.correlation).cls">{{
                    correlationBadge(selected.correlation).label
                  }}</span>
                </div>
                <div class="small text-body-secondary mt-1">
                  <span v-if="selected.traceId" class="font-monospace me-2">trace {{ selected.traceId }}</span>
                  <router-link
                    v-if="selected.traceId"
                    class="me-2"
                    :to="{path: '/traces', query: {trace: selected.traceId}}"
                    >View in Traces</router-link
                  >
                  <router-link class="me-2" to="/http-exchanges">HTTP Exchanges</router-link>
                </div>
              </div>
              <ul class="list-group list-group-flush">
                <li v-for="(entry, idx) in selected.timeline" :key="idx" class="list-group-item">
                  <div class="d-flex gap-2">
                    <i
                      class="bi mt-1"
                      :class="[kindMeta(entry.kind).icon, kindMeta(entry.kind).cls]"
                      aria-hidden="true"
                    ></i>
                    <div class="flex-grow-1 min-w-0">
                      <div class="d-flex justify-content-between gap-2">
                        <span class="fw-semibold" :class="severityClass(entry.severity)">{{ entry.title }}</span>
                        <span class="small text-body-secondary text-nowrap">
                          <span v-if="entry.durationMs != null" :class="{'text-warning fw-semibold': entry.slow}"
                            >{{ formatNumber(entry.durationMs) }} ms</span
                          >
                          <span class="ms-2">{{ formatClockTime(entry.timestamp) }}</span>
                        </span>
                      </div>
                      <div v-if="entry.detail" class="small text-body-secondary text-break font-monospace">
                        {{ entry.detail }}
                      </div>
                      <div v-if="entry.thread" class="small text-body-secondary">
                        <i class="bi bi-cpu-fill" aria-hidden="true"></i> {{ entry.thread }}
                      </div>
                    </div>
                  </div>
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div
          v-if="unattributed && (unattributed.sqlCount || unattributed.exceptionCount || unattributed.securityCount)"
          class="card mt-3"
        >
          <div class="card-header d-flex align-items-center gap-2">
            <i class="bi bi-question-circle" aria-hidden="true"></i>
            <span class="fw-semibold">Unattributed signals</span>
            <span class="small text-body-secondary"
              >could not be tied to a request — {{ unattributed.sqlCount }} SQL,
              {{ unattributed.exceptionCount }} exception, {{ unattributed.securityCount }} security</span
            >
          </div>
          <ul class="list-group list-group-flush">
            <li v-for="(entry, idx) in unattributed.entries" :key="idx" class="list-group-item">
              <div class="d-flex gap-2">
                <i
                  class="bi mt-1"
                  :class="[kindMeta(entry.kind).icon, kindMeta(entry.kind).cls]"
                  aria-hidden="true"
                ></i>
                <div class="flex-grow-1 min-w-0">
                  <div class="d-flex justify-content-between gap-2">
                    <span class="fw-semibold" :class="severityClass(entry.severity)">{{ entry.title }}</span>
                    <span class="small text-body-secondary text-nowrap">{{ formatClockTime(entry.timestamp) }}</span>
                  </div>
                  <div v-if="entry.detail" class="small text-body-secondary text-break font-monospace">
                    {{ entry.detail }}
                  </div>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </template>
    </template>
  </div>
</template>
