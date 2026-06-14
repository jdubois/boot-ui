<script setup>
import {computed, ref} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import UnavailableState from './components/UnavailableState.vue'
import {formatBytes, formatClockTime, formatNumber} from '../utils/format.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {filterEntries, groupEntries} from '../utils/activityStream.js'

const TYPES = ['REQUEST', 'SQL', 'EXCEPTION', 'SECURITY']
const SEVERITIES = ['OK', 'SLOW', 'WARN', 'ERROR']

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const typeFilter = ref('')
const severityFilter = ref('')

const profile = ref(null)
const profileLoading = ref(false)
const profileError = ref(null)
const profileRequestId = ref(null)

async function loadActivity() {
  try {
    const response = await fetch('api/activity')
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    report.value = await response.json()
    error.value = null
    lastFetched.value = Date.now()
  } catch (err) {
    error.value = err.message || 'Could not load activity'
    throw err
  }
}

const {autoRefresh, loading, load: refreshNow} = useAutoRefresh(loadActivity)

const available = computed(() => report.value?.available ?? false)
const kpis = computed(() => report.value?.kpis ?? null)
const sources = computed(() => report.value?.sources ?? [])
const warnings = computed(() => report.value?.warnings ?? [])

const visibleEntries = computed(() => {
  const filtered = filterEntries(report.value?.entries ?? [], {
    type: typeFilter.value,
    severity: severityFilter.value
  })
  return groupEntries(filtered)
})

const subtitle = computed(() => {
  const counts = report.value?.typeCounts ?? {}
  const total = Object.values(counts).reduce((sum, value) => sum + value, 0)
  return `${formatNumber(total)} recent events · ${sources.value.length} source${sources.value.length === 1 ? '' : 's'}`
})

const paused = computed(() => !autoRefresh.value)

function togglePause() {
  autoRefresh.value = !autoRefresh.value
}

function typeIcon(type) {
  return (
    {
      REQUEST: 'bi-arrow-left-right',
      SQL: 'bi-database',
      EXCEPTION: 'bi-exclamation-octagon',
      SECURITY: 'bi-shield-lock'
    }[type] || 'bi-dot'
  )
}

function severityBadgeClass(severity) {
  return (
    {
      OK: 'text-bg-success',
      SLOW: 'text-bg-warning',
      WARN: 'text-bg-warning',
      ERROR: 'text-bg-danger'
    }[severity] || 'text-bg-secondary'
  )
}

function rowClass(entry) {
  if (entry.severity === 'ERROR') return 'table-danger'
  if (entry.severity === 'SLOW' || entry.severity === 'WARN') return 'table-warning'
  return ''
}

function formatDurationMs(durationMs) {
  if (durationMs == null) return ''
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(2)} s`
}

async function openProfile(entry) {
  if (!entry.profileable) return
  profileRequestId.value = entry.id
  profileLoading.value = true
  profileError.value = null
  profile.value = null
  try {
    const response = await fetch(`api/activity/request/${encodeURIComponent(entry.id)}`)
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    profile.value = await response.json()
  } catch (err) {
    profileError.value = err.message || 'Could not load request profile'
  } finally {
    profileLoading.value = false
  }
}

function closeProfile() {
  profileRequestId.value = null
  profile.value = null
  profileError.value = null
}

function clearFilters() {
  typeFilter.value = ''
  severityFilter.value = ''
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-broadcast"
      title="Live Activity"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      auto-refresh-title="Stream new activity every 10 seconds while this tab is visible"
      @refresh="refreshNow"
    />

    <UnavailableState
      v-if="report && !available"
      icon="bi-broadcast"
      message="No live activity sources are available yet. Enable HTTP exchange recording, SQL tracing, exception capture, or security logs to populate this stream."
    />

    <template v-else-if="report">
      <div v-if="kpis" class="row g-2 mb-3 activity-kpis">
        <div class="col-6 col-lg-2">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Requests/min</div>
              <div class="fs-5">{{ formatNumber(kpis.requestsPerMinute) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-2">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Error rate</div>
              <div class="fs-5">{{ kpis.errorRatePercent }}%</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-2">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Latency p50 / p95</div>
              <div class="fs-5">{{ kpis.p50LatencyMs ?? '—' }} / {{ kpis.p95LatencyMs ?? '—' }} ms</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-2">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">SQL/min</div>
              <div class="fs-5">{{ formatNumber(kpis.sqlPerMinute) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-2">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Active exceptions</div>
              <div class="fs-5">{{ formatNumber(kpis.activeExceptionCount) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-2">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Heap used</div>
              <div class="fs-5">{{ formatBytes(kpis.heapUsedBytes) }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="d-flex flex-wrap align-items-end gap-2 mb-3">
        <div>
          <label class="form-label small mb-1">Type</label>
          <select v-model="typeFilter" class="form-select form-select-sm">
            <option value="">All types</option>
            <option v-for="type in TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
        </div>
        <div>
          <label class="form-label small mb-1">Severity</label>
          <select v-model="severityFilter" class="form-select form-select-sm">
            <option value="">All severities</option>
            <option v-for="severity in SEVERITIES" :key="severity" :value="severity">{{ severity }}</option>
          </select>
        </div>
        <button
          v-if="typeFilter || severityFilter"
          class="btn btn-sm btn-outline-secondary"
          type="button"
          @click="clearFilters"
        >
          Clear
        </button>
        <div class="ms-auto">
          <button
            class="btn btn-sm"
            :class="paused ? 'btn-success' : 'btn-outline-secondary'"
            type="button"
            @click="togglePause"
          >
            <i :class="['bi', paused ? 'bi-play-fill' : 'bi-pause-fill', 'me-1']"></i>
            {{ paused ? 'Resume' : 'Pause' }}
          </button>
        </div>
      </div>

      <div v-for="warning in warnings" :key="warning" class="alert alert-warning py-2 small" role="alert">
        {{ warning }}
      </div>

      <div class="table-responsive">
        <table class="table table-sm align-middle activity-table">
          <thead>
            <tr>
              <th class="text-nowrap">Time</th>
              <th>Type</th>
              <th>Severity</th>
              <th>Activity</th>
              <th class="text-end text-nowrap">Duration</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in visibleEntries" :key="entry.id" :class="rowClass(entry)">
              <td class="text-nowrap small">{{ formatClockTime(entry.timestamp) }}</td>
              <td class="text-nowrap"><i :class="['bi', typeIcon(entry.type), 'me-1']"></i>{{ entry.type }}</td>
              <td>
                <span :class="['badge', severityBadgeClass(entry.severity)]">{{ entry.severity }}</span>
              </td>
              <td>
                <span>{{ entry.summary }}</span>
                <span v-if="entry.count > 1" class="badge rounded-pill text-bg-light ms-2">×{{ entry.count }}</span>
                <span v-if="entry.detail" class="d-block text-muted small">{{ entry.detail }}</span>
              </td>
              <td class="text-end text-nowrap small">{{ formatDurationMs(entry.durationMs) }}</td>
              <td class="text-end">
                <button
                  v-if="entry.profileable"
                  class="btn btn-outline-primary btn-sm rounded-pill"
                  type="button"
                  @click="openProfile(entry)"
                >
                  <i class="bi bi-search me-1"></i>Profile
                </button>
              </td>
            </tr>
            <tr v-if="!visibleEntries.length">
              <td colspan="6" class="text-center text-muted py-4">No activity matches the current filters.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Per-request profiler drawer -->
    <div v-if="profileRequestId" class="activity-drawer-backdrop" @click.self="closeProfile">
      <aside class="activity-drawer card shadow">
        <div class="card-header d-flex align-items-center justify-content-between">
          <h2 class="h6 mb-0">Request profile</h2>
          <button class="btn-close" type="button" aria-label="Close" @click="closeProfile"></button>
        </div>
        <div class="card-body activity-drawer-body">
          <div v-if="profileLoading" class="text-muted">Loading…</div>
          <div v-else-if="profileError" class="alert alert-danger">{{ profileError }}</div>
          <div v-else-if="profile && !profile.available" class="alert alert-warning">
            {{ profile.unavailableReason }}
          </div>
          <div v-else-if="profile">
            <section class="mb-3">
              <h3 class="h6">Request</h3>
              <dl class="row small mb-0">
                <dt class="col-4">Method · Path</dt>
                <dd class="col-8">
                  <code>{{ profile.request.method }} {{ profile.request.path }}</code>
                </dd>
                <dt class="col-4">Status</dt>
                <dd class="col-8">{{ profile.request.status }}</dd>
                <dt class="col-4">Duration</dt>
                <dd class="col-8">{{ formatDurationMs(profile.request.durationMs) }}</dd>
                <template v-if="profile.request.principal">
                  <dt class="col-4">Principal</dt>
                  <dd class="col-8">{{ profile.request.principal }}</dd>
                </template>
                <template v-if="profile.request.traceId">
                  <dt class="col-4">Trace id</dt>
                  <dd class="col-8">
                    <code>{{ profile.request.traceId }}</code>
                  </dd>
                </template>
              </dl>
            </section>

            <section v-if="profile.timing" class="mb-3">
              <h3 class="h6">Timing</h3>
              <p class="small mb-1">
                {{ profile.timing.sqlCount }} SQL statement(s), {{ profile.timing.sqlMs }} ms in SQL
                <span v-if="profile.timing.sqlPercent != null">({{ profile.timing.sqlPercent }}% of request)</span>
              </p>
            </section>

            <section class="mb-3">
              <h3 class="h6">
                SQL
                <span
                  v-if="profile.sqlCorrelationApproximate"
                  class="badge text-bg-secondary ms-1"
                  title="SQL has no trace id; correlated by time window"
                >
                  approximate
                </span>
              </h3>
              <div v-for="group in profile.sqlGroups" :key="group.sql" class="small mb-1">
                <span v-if="group.potentialNPlusOne" class="badge text-bg-danger me-1">
                  N+1 · {{ group.executions }} identical
                </span>
                <span v-else class="badge text-bg-light me-1">×{{ group.executions }}</span>
                <code>{{ group.sql }}</code>
              </div>
              <p v-if="!profile.sql.length" class="text-muted small mb-0">No SQL correlated to this request.</p>
            </section>

            <section v-if="profile.exceptions.length" class="mb-3">
              <h3 class="h6">Exceptions</h3>
              <div v-for="(ex, index) in profile.exceptions" :key="index" class="small mb-1">
                <code>{{ ex.exceptionClassName }}</code>
                <span v-if="ex.message" class="text-muted">: {{ ex.message }}</span>
                <span v-if="ex.location" class="d-block text-muted">{{ ex.location }}</span>
              </div>
            </section>

            <section v-if="profile.trace && profile.trace.spans.length" class="mb-3">
              <h3 class="h6">Trace waterfall</h3>
              <ul class="list-unstyled small mb-0">
                <li v-for="(span, index) in profile.trace.spans" :key="index">
                  <code>{{ span.name }}</code>
                </li>
              </ul>
            </section>

            <section v-if="profile.notes.length">
              <h3 class="h6">Notes</h3>
              <ul class="small text-muted mb-0">
                <li v-for="(note, index) in profile.notes" :key="index">{{ note }}</li>
              </ul>
            </section>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.activity-drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  justify-content: flex-end;
  z-index: 1050;
}

.activity-drawer {
  width: min(560px, 100%);
  height: 100%;
  border-radius: 0;
  overflow: hidden;
}

.activity-drawer-body {
  overflow-y: auto;
}
</style>
