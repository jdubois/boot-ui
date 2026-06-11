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
const {message: banner, flash, clear: clearBanner} = useFlashMessage()
const filter = ref('')
const busy = ref(false)
const lastFetched = ref(null)

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

const entries = computed(() => report.value?.entries ?? [])

const filteredEntries = computed(() => {
  const value = filter.value.trim().toLowerCase()
  if (!value) return entries.value
  return entries.value.filter(
    (entry) =>
      (entry.sql || '').toLowerCase().includes(value) ||
      (entry.operation || '').toLowerCase().includes(value) ||
      (entry.statementType || '').toLowerCase().includes(value) ||
      (entry.connectionId || '').toLowerCase().includes(value) ||
      (entry.parameters || []).join(' ').toLowerCase().includes(value)
  )
})

function operationClass(operation) {
  return (
    {
      QUERY: 'text-bg-primary',
      UPDATE: 'text-bg-success',
      BATCH: 'text-bg-info'
    }[operation] || 'text-bg-secondary'
  )
}

async function clearTrace() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (!confirm('Clear all captured SQL executions from the in-memory trace buffer?')) return
  busy.value = true
  clearBanner()
  try {
    const res = await apiFetch('api/sql-trace/clear', {method: 'POST'})
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    report.value = result
    lastFetched.value = Date.now()
    flash('SQL trace cleared.', 'success')
  } catch (e) {
    flash(formatLoadError(e, 'Could not clear SQL trace'), 'danger')
  } finally {
    busy.value = false
  }
}

// useAutoRefresh automatically loads on mount unless configured otherwise
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-stopwatch"
      title="SQL Trace"
      :subtitle="
        report && report.available
          ? `${formatNumber(report.stats.totalQueries)} retained · ${formatNumber(report.totalCaptured)} captured since startup`
          : null
      "
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy"
          :disabled="!report || !report.available || readOnly || busy || report.stats.totalQueries === 0"
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
      <div v-if="!report.available" class="alert alert-secondary">
        {{ report.unavailableReason || 'SQL tracing is not available.' }}
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Clearing the trace is read-only.</ReadOnlyNotice>

        <div v-if="!report.capturing" class="alert alert-info small">
          SQL tracing is currently disabled. Set <code>bootui.sql-trace.enabled=true</code> in a trusted local profile
          to capture executions.
        </div>

        <div v-else-if="!report.captureParameters" class="alert alert-secondary small">
          Parameter capture is disabled. Set <code>bootui.sql-trace.capture-parameters=true</code> (local profiles only)
          to record bound parameter values.
        </div>

        <section class="mb-4">
          <div class="row g-2 stat-cards">
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Retained</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(report.stats.totalQueries) }}</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Total time</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(report.stats.totalDurationMillis) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Avg time</div>
                  <div class="fs-5 fw-semibold">{{ report.stats.avgDurationMillis.toFixed(1) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Max time</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(report.stats.maxDurationMillis) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Slow (&gt;{{ report.slowQueryThresholdMillis }} ms)</div>
                  <div class="fs-5 fw-semibold" :class="{'text-warning': report.stats.slowQueries > 0}">
                    {{ formatNumber(report.stats.slowQueries) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Failed</div>
                  <div class="fs-5 fw-semibold" :class="{'text-danger': report.stats.failedQueries > 0}">
                    {{ formatNumber(report.stats.failedQueries) }}
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="mt-2 d-flex flex-wrap gap-1">
            <span class="badge text-bg-primary">queries {{ formatNumber(report.stats.selectCount) }}</span>
            <span class="badge text-bg-success">updates {{ formatNumber(report.stats.updateCount) }}</span>
            <span class="badge text-bg-info">batches {{ formatNumber(report.stats.batchCount) }}</span>
            <span class="badge text-bg-secondary">other {{ formatNumber(report.stats.otherCount) }}</span>
          </div>
        </section>

        <section>
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h5 class="mb-0">
              Recent executions <span class="badge bg-secondary">{{ entries.length }}</span>
            </h5>
            <input
              v-model="filter"
              class="form-control form-control-sm trace-filter"
              placeholder="Filter by SQL, operation, connection, or parameter…"
            />
          </div>

          <div v-if="entries.length === 0" class="alert alert-secondary small">
            No SQL has been captured yet. Exercise the application's database access and refresh to see executions.
          </div>

          <div v-else-if="filteredEntries.length" class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Operation</th>
                  <th class="text-end">Duration</th>
                  <th>SQL</th>
                  <th class="text-end">Rows</th>
                  <th>Connection</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="entry in filteredEntries" :key="entry.id" :class="{'table-danger': !entry.success}">
                  <td class="text-nowrap">{{ formatClockTime(entry.timestamp) }}</td>
                  <td>
                    <span :class="operationClass(entry.operation)" class="badge">{{ entry.operation }}</span>
                    <div class="small text-muted">{{ entry.statementType }}</div>
                    <span v-if="entry.batchSize > 0" class="badge text-bg-light border text-dark"
                      >batch {{ entry.batchSize }}</span
                    >
                  </td>
                  <td class="text-end text-nowrap" :class="{'text-warning fw-semibold': entry.slow}">
                    {{ formatNumber(entry.durationMillis) }} ms
                  </td>
                  <td>
                    <code class="sql-text">{{ entry.sql }}</code>
                    <div v-if="entry.parameters && entry.parameters.length" class="small text-muted">
                      params:
                      <span v-for="(param, idx) in entry.parameters" :key="idx" class="me-1">
                        <code>{{ param }}</code>
                      </span>
                    </div>
                    <div v-if="!entry.success" class="small text-danger">{{ entry.errorMessage }}</div>
                  </td>
                  <td class="text-end">{{ entry.affectedRows === null ? '—' : formatNumber(entry.affectedRows) }}</td>
                  <td>
                    <code>{{ entry.connectionId }}</code>
                  </td>
                </tr>
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
  max-width: 26rem;
}

.sql-text {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
