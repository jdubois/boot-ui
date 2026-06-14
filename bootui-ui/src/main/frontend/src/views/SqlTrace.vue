<script setup>
import {apiFetch} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const {message: banner, flash, clear: clearBanner} = useFlashMessage()
const filter = ref('')
const categoryFilter = ref('')
const slowOnly = ref(false)
const busy = ref(null)
const lastFetched = ref(null)
const expanded = ref(new Set())

async function fetchReport() {
  error.value = null
  try {
    const res = await apiFetch('api/sql-trace')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load SQL trace')
  }
}

const {autoRefresh, loading, initialLoading, load} = useEventStreamRefresh('api/sql-trace/stream', fetchReport)

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const stats = computed(() => report.value?.stats ?? null)
const entries = computed(() => report.value?.entries ?? [])

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
  if (options.confirm && !confirm(options.confirm)) return
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
    confirm: 'Clear all captured SQL executions from the in-memory trace buffer?',
    onSuccess: () => (expanded.value = new Set()),
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
      @refresh="load"
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
                  <div class="fs-5 fw-semibold">{{ stats.avgDurationMillis.toFixed(1) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Slowest</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(stats.maxDurationMillis) }} ms</div>
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

        <section v-if="report.topStatements.length" class="mb-4">
          <h5 class="mb-2">
            Most frequent statements <span class="badge bg-secondary">{{ report.topStatements.length }}</span>
          </h5>
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
                  </td>
                  <td class="text-end">{{ formatNumber(group.executions) }}</td>
                  <td class="text-end">{{ formatNumber(group.totalDurationMillis) }} ms</td>
                  <td class="text-end">{{ formatNumber(group.maxDurationMillis) }} ms</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section>
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h5 class="mb-0">
              Recent executions <span class="badge bg-secondary">{{ filteredEntries.length }}</span>
            </h5>
            <div class="d-flex flex-wrap gap-2">
              <select v-model="categoryFilter" class="form-select form-select-sm sql-filter-select">
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
                class="form-control form-control-sm trace-filter"
                placeholder="Filter by SQL, category, connection, thread, or parameter…"
              />
            </div>
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
                  <tr class="sql-row" @click="toggleRow(entry)">
                    <td class="text-muted">
                      <i class="bi" :class="isExpanded(entry) ? 'bi-chevron-down' : 'bi-chevron-right'"></i>
                    </td>
                    <td class="text-nowrap font-monospace small">{{ formatClockTime(entry.timestamp) }}</td>
                    <td>
                      <span :class="categoryClass(entry.category)" class="badge">{{ entry.category }}</span>
                      <span v-if="entry.batchSize > 0" class="badge text-bg-secondary ms-1"
                        >batch ×{{ entry.batchSize }}</span
                      >
                    </td>
                    <td class="text-end text-nowrap" :class="{'text-warning fw-semibold': entry.slow}">
                      {{ formatNumber(entry.durationMillis) }} ms
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
                  <tr v-if="isExpanded(entry)" class="sql-detail-row">
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

.sql-type-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
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
