<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
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
const {message: banner, flash, clear: clearFlash} = useFlashMessage()
const textFilter = ref('')
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

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport)

const stats = computed(() => report.value?.stats ?? null)

const categories = computed(() => {
  const seen = new Set()
  for (const query of report.value?.queries ?? []) {
    if (query.category) seen.add(query.category)
  }
  return Array.from(seen).sort()
})

const filteredQueries = computed(() => {
  const queries = report.value?.queries ?? []
  const text = textFilter.value.trim().toLowerCase()
  const category = categoryFilter.value
  return queries.filter((query) => {
    if (category && query.category !== category) return false
    if (slowOnly.value && !query.slow) return false
    if (!text) return true
    const haystack = [
      query.dataSource,
      query.type,
      query.category,
      query.error,
      ...(query.statements || []),
      ...(query.parameters || [])
    ]
      .join(' ')
      .toLowerCase()
    return haystack.includes(text)
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

function rowKey(query) {
  return query.id
}

function toggleRow(query) {
  const next = new Set(expanded.value)
  const key = rowKey(query)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

function isExpanded(query) {
  return expanded.value.has(rowKey(query))
}

async function toggleRecording() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  const next = !report.value?.recording
  busy.value = 'recording'
  clearFlash()
  try {
    const res = await apiFetch('api/sql-trace/recording', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({enabled: next})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    flash(result.message || (next ? 'Recording resumed.' : 'Recording paused.'), 'success')
    await load()
  } catch (e) {
    flash(formatLoadError(e, 'Could not change recording state'), 'danger')
  } finally {
    busy.value = null
  }
}

async function clearBuffer() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (!confirm('Clear all recorded SQL executions?')) return
  busy.value = 'clear'
  clearFlash()
  try {
    const res = await apiFetch('api/sql-trace/clear', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'}
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    expanded.value = new Set()
    flash(result.message || 'Recorded queries cleared.', 'success')
    await load()
  } catch (e) {
    flash(formatLoadError(e, 'Could not clear recorded queries'), 'danger')
  } finally {
    busy.value = null
  }
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

const subtitle = computed(() => {
  if (!report.value) return null
  if (!report.value.available) return 'SQL tracing unavailable'
  const s = stats.value
  const parts = [`${formatNumber(s.recorded)} recorded`]
  if (s.captured > s.recorded) parts.push(`${formatNumber(s.captured)} captured`)
  if (s.slowQueries) parts.push(`${formatNumber(s.slowQueries)} slow`)
  if (s.failedQueries) parts.push(`${formatNumber(s.failedQueries)} failed`)
  parts.push(report.value.recording ? 'recording' : 'paused')
  return parts.join(' · ')
})

// useAutoRefresh automatically loads on mount unless configured otherwise
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-clipboard-data"
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
          :class="report && report.recording ? 'btn btn-sm btn-outline-warning' : 'btn btn-sm btn-outline-success'"
          :icon="report && report.recording ? 'bi-pause-fill' : 'bi-record-fill'"
          :label="report && report.recording ? 'Pause' : 'Resume'"
          @click="toggleRecording"
        />
        <SpinnerButton
          :loading="busy === 'clear'"
          :disabled="!report || readOnly || !stats || stats.recorded === 0 || busy"
          class="btn btn-sm btn-outline-danger ms-2"
          icon="bi-trash"
          label="Clear"
          @click="clearBuffer"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clearFlash" />

    <PanelSkeleton v-if="initialLoading && !report" />

    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small">
        {{ warning }}
      </div>

      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Recording controls are read-only.</ReadOnlyNotice>

      <div v-if="!report.available" class="alert alert-secondary">
        {{ report.unavailableReason || 'SQL tracing is not active.' }}
      </div>

      <template v-else>
        <section class="row g-2 mb-4">
          <div class="col-6 col-md-3 col-xl-2">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Recorded</div>
                <div class="fs-5 fw-semibold">{{ formatNumber(stats.recorded) }}</div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3 col-xl-2">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Avg time</div>
                <div class="fs-5 fw-semibold">{{ stats.avgElapsedMillis.toFixed(1) }} ms</div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3 col-xl-2">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Slowest</div>
                <div class="fs-5 fw-semibold">{{ formatNumber(stats.maxElapsedMillis) }} ms</div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3 col-xl-2">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Slow (≥ {{ formatNumber(report.slowQueryThresholdMillis) }} ms)</div>
                <div class="fs-5 fw-semibold" :class="{'text-warning': stats.slowQueries}">
                  {{ formatNumber(stats.slowQueries) }}
                </div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3 col-xl-2">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Failed</div>
                <div class="fs-5 fw-semibold" :class="{'text-danger': stats.failedQueries}">
                  {{ formatNumber(stats.failedQueries) }}
                </div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3 col-xl-2">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">By type</div>
                <div class="sql-type-counts">
                  <span class="badge text-bg-primary">S {{ formatNumber(stats.selectCount) }}</span>
                  <span class="badge text-bg-success">I {{ formatNumber(stats.insertCount) }}</span>
                  <span class="badge text-bg-warning">U {{ formatNumber(stats.updateCount) }}</span>
                  <span class="badge text-bg-danger">D {{ formatNumber(stats.deleteCount) }}</span>
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
                    <span v-if="group.potentialNPlusOne" class="badge text-bg-danger ms-1" title="Repeated many times">
                      possible N+1
                    </span>
                  </td>
                  <td class="text-end">{{ formatNumber(group.executions) }}</td>
                  <td class="text-end">{{ formatNumber(group.totalElapsedMillis) }} ms</td>
                  <td class="text-end">{{ formatNumber(group.maxElapsedMillis) }} ms</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section>
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h5 class="mb-0">
              Executions <span class="badge bg-secondary">{{ filteredQueries.length }}</span>
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
                v-model="textFilter"
                class="form-control form-control-sm sql-filter"
                placeholder="Filter by SQL, data source, parameter…"
              />
            </div>
          </div>

          <div v-if="(report.queries?.length ?? 0) === 0" class="alert alert-secondary small">
            No SQL has been recorded yet. Exercise the application to capture queries.
          </div>

          <div v-else-if="filteredQueries.length" class="table-responsive">
            <table class="table table-sm table-hover align-middle sql-table">
              <thead>
                <tr>
                  <th style="width: 2rem"></th>
                  <th>Time</th>
                  <th>Category</th>
                  <th>Statement</th>
                  <th class="text-end">Elapsed</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="query in filteredQueries" :key="rowKey(query)">
                  <tr class="sql-row" @click="toggleRow(query)">
                    <td class="text-muted">
                      <i class="bi" :class="isExpanded(query) ? 'bi-chevron-down' : 'bi-chevron-right'"></i>
                    </td>
                    <td class="text-nowrap">
                      <span class="font-monospace small">{{ formatClockTime(query.timestamp) }}</span>
                    </td>
                    <td>
                      <span :class="categoryClass(query.category)" class="badge">{{ query.category }}</span>
                      <span v-if="query.batch" class="badge text-bg-secondary ms-1">batch ×{{ query.batchSize }}</span>
                    </td>
                    <td>
                      <code class="sql-text">{{ query.statements[0] }}</code>
                      <span v-if="query.statements.length > 1" class="text-muted small ms-1">
                        +{{ query.statements.length - 1 }} more
                      </span>
                    </td>
                    <td class="text-end text-nowrap">
                      <span :class="{'text-warning fw-semibold': query.slow}"
                        >{{ formatNumber(query.elapsedMillis) }} ms</span
                      >
                    </td>
                    <td>
                      <span v-if="query.success" class="badge text-bg-success">ok</span>
                      <span v-else class="badge text-bg-danger" :title="query.error">failed</span>
                      <span v-if="query.slow" class="badge text-bg-warning ms-1">slow</span>
                    </td>
                  </tr>
                  <tr v-if="isExpanded(query)" class="sql-detail-row">
                    <td></td>
                    <td colspan="5">
                      <dl class="row mb-0 small">
                        <dt class="col-sm-2">Statement{{ query.statements.length > 1 ? 's' : '' }}</dt>
                        <dd class="col-sm-10">
                          <pre class="sql-detail mb-1" v-for="(sql, i) in query.statements" :key="i">{{ sql }}</pre>
                        </dd>
                        <template v-if="query.parameters && query.parameters.length">
                          <dt class="col-sm-2">Parameters</dt>
                          <dd class="col-sm-10">
                            <span
                              v-for="(param, i) in query.parameters"
                              :key="i"
                              class="badge text-bg-light border text-dark me-1 mb-1"
                            >
                              {{ param }}
                            </span>
                          </dd>
                        </template>
                        <dt class="col-sm-2">Data source</dt>
                        <dd class="col-sm-10">
                          <code>{{ query.dataSource }}</code>
                        </dd>
                        <dt class="col-sm-2">Connection</dt>
                        <dd class="col-sm-10">
                          <code>{{ query.connectionId || '—' }}</code> · {{ query.type }}
                        </dd>
                        <dt class="col-sm-2">Thread</dt>
                        <dd class="col-sm-10">
                          <code>{{ query.thread }}</code>
                        </dd>
                        <template v-if="query.error">
                          <dt class="col-sm-2 text-danger">Error</dt>
                          <dd class="col-sm-10 text-danger">{{ query.error }}</dd>
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
.sql-filter {
  max-width: 20rem;
}

.sql-filter-select {
  max-width: 12rem;
}

.sql-text {
  display: inline-block;
  max-width: 48rem;
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
