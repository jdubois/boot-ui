<script setup>
import {computed, onMounted, ref} from 'vue'

const report = ref(null)
const detail = ref(null)
const loading = ref(true)
const error = ref(null)
const banner = ref(null)
const filter = ref('')
const selectedTraceId = ref(null)
const detailLoading = ref(false)
const busy = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await fetch('api/traces')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function openTrace(traceId) {
  selectedTraceId.value = traceId
  detail.value = null
  detailLoading.value = true
  try {
    const res = await fetch('api/traces/' + traceId)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    detail.value = await res.json()
  } catch (e) {
    banner.value = {type: 'danger', text: 'Could not load trace: ' + e.message}
  } finally {
    detailLoading.value = false
  }
}

function toggleTrace(traceId) {
  if (traceId === selectedTraceId.value) {
    closeDrawer()
    return
  }
  openTrace(traceId)
}

function closeDrawer() {
  selectedTraceId.value = null
  detail.value = null
}

async function clearAll() {
  if (!confirm('Clear all retained traces? This cannot be undone.')) return
  busy.value = true
  try {
    const res = await fetch('api/traces', {method: 'DELETE'})
    if (!res.ok && res.status !== 204) throw new Error('HTTP ' + res.status)
    closeDrawer()
    await load()
    banner.value = {type: 'success', text: 'Cleared retained traces.'}
    setTimeout(() => {
      banner.value = null
    }, 4000)
  } catch (e) {
    banner.value = {type: 'danger', text: 'Could not clear: ' + e.message}
  } finally {
    busy.value = false
  }
}

const filteredTraces = computed(() => {
  if (!report.value) return []
  const v = filter.value.trim().toLowerCase()
  if (!v) return report.value.traces
  return report.value.traces.filter(t =>
    (t.traceId || '').toLowerCase().includes(v)
    || (t.rootSpanName || '').toLowerCase().includes(v)
    || (t.services || []).join(' ').toLowerCase().includes(v))
})

const waterfall = computed(() => {
  if (!detail.value || !detail.value.spans || detail.value.spans.length === 0) return null
  const spans = [...detail.value.spans]
  const minStart = spans.reduce((m, s) => s.startEpochNanos < m ? s.startEpochNanos : m, spans[0].startEpochNanos)
  const maxEnd = spans.reduce((m, s) => s.endEpochNanos > m ? s.endEpochNanos : m, spans[0].endEpochNanos)
  const totalNanos = Math.max(maxEnd - minStart, 1)
  const sorted = spans
    .map(s => ({
      ...s,
      offsetPct: ((s.startEpochNanos - minStart) / totalNanos) * 100,
      widthPct: Math.max((s.durationNanos / totalNanos) * 100, 0.5)
    }))
    .sort((a, b) => a.startEpochNanos - b.startEpochNanos)
  return {spans: sorted, totalNanos}
})

function formatDuration(nanos) {
  if (nanos == null) return '—'
  const ms = nanos / 1_000_000
  if (ms < 1) return (nanos / 1000).toFixed(1) + ' µs'
  if (ms < 1000) return ms.toFixed(1) + ' ms'
  return (ms / 1000).toFixed(2) + ' s'
}

function formatTime(epochNanos) {
  if (!epochNanos) return '—'
  const ms = Math.floor(epochNanos / 1_000_000)
  return new Date(ms).toLocaleTimeString()
}

function spanColor(span) {
  if (span.statusCode === 'ERROR') return 'bg-danger'
  if ((span.kind || '').includes('SERVER')) return 'bg-primary'
  if ((span.kind || '').includes('CLIENT')) return 'bg-info'
  if ((span.kind || '').includes('PRODUCER') || (span.kind || '').includes('CONSUMER')) return 'bg-warning'
  return 'bg-secondary'
}

function shortId(id) {
  if (!id) return '—'
  return id.length > 8 ? id.substring(0, 8) : id
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-bezier2 me-2"></i>Traces</h2>
        <div v-if="report" class="text-muted small">
          {{ report.retained }} / {{ report.capacity }} retained trace{{ report.retained === 1 ? '' : 's' }}
          <span v-if="!report.enabled" class="badge text-bg-secondary ms-1">Disabled</span>
        </div>
      </div>
      <div class="d-flex gap-2">
        <button :disabled="!report || report.retained === 0 || busy" class="btn btn-sm btn-outline-danger"
                @click="clearAll">
          <span v-if="busy" class="spinner-border spinner-border-sm me-1"></span>
          <i v-else class="bi bi-trash me-1"></i>Clear
        </button>
        <button :disabled="loading" class="btn btn-sm btn-outline-secondary" @click="load">
          <i class="bi bi-arrow-clockwise"></i> Refresh
        </button>
      </div>
    </div>

    <div v-if="banner" :class="'alert-' + banner.type" class="alert d-flex justify-content-between align-items-center">
      <div>{{ banner.text }}</div>
      <button class="btn-close" @click="banner = null"></button>
    </div>

    <div v-if="loading" class="text-muted">Loading traces…</div>
    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-else-if="report">
      <div v-if="!report.enabled" class="alert alert-info small">
        Telemetry receiver is disabled. Set <code>bootui.telemetry.enabled=true</code> and configure your application
        to export OTLP to <code>http://localhost:8080/bootui/api/otlp/v1/traces</code>.
        <span v-if="report.retained > 0">Showing spans retained before telemetry was disabled.</span>
      </div>

      <div v-if="report.enabled && report.retained === 0" class="alert alert-secondary">
        No traces received yet. Add an OTLP exporter pointed at
        <code>/bootui/api/otlp/v1/traces</code> and exercise your application to populate this panel.
      </div>

      <template v-if="report.retained > 0">
        <div class="mb-3">
          <input v-model="filter" class="form-control form-control-sm"
                 placeholder="Filter by trace id, root span, or service…"/>
        </div>

        <div class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
            <tr>
              <th>Started</th>
              <th>Root span</th>
              <th>Services</th>
              <th>Spans</th>
              <th>Duration</th>
              <th>Status</th>
              <th></th>
            </tr>
            </thead>
            <tbody>
            <template v-for="t in filteredTraces" :key="t.traceId">
              <tr :class="{ 'table-active': t.traceId === selectedTraceId }">
                <td class="text-muted small">{{ formatTime(t.startEpochNanos) }}</td>
                <td>
                  <code class="me-2">{{ shortId(t.traceId) }}</code>
                  <span class="fw-semibold">{{ t.rootSpanName || '—' }}</span>
                  <span v-if="t.hasAi" class="badge text-bg-info ms-1"><i class="bi bi-stars"></i> AI</span>
                </td>
                <td>
                  <span v-for="s in t.services" :key="s" class="badge text-bg-secondary me-1">{{ s }}</span>
                </td>
                <td>{{ t.spanCount }}</td>
                <td>{{ formatDuration(t.durationNanos) }}</td>
                <td>
                  <span v-if="t.hasError" class="badge text-bg-danger">error</span>
                  <span v-else class="badge text-bg-success">ok</span>
                </td>
                <td class="text-end">
                  <button
                    :aria-expanded="t.traceId === selectedTraceId"
                    class="btn btn-sm btn-outline-primary"
                    @click="toggleTrace(t.traceId)">
                    {{ t.traceId === selectedTraceId ? 'Close' : 'Open' }}
                  </button>
                </td>
              </tr>
              <tr v-if="t.traceId === selectedTraceId" class="trace-detail-row">
                <td class="p-0" colspan="7">
                  <div class="trace-drawer card m-2">
                    <div class="card-header d-flex justify-content-between align-items-center">
                      <div>
                        <i class="bi bi-bezier2 me-2"></i>
                        <code>{{ selectedTraceId }}</code>
                      </div>
                      <button class="btn btn-sm btn-outline-secondary" @click="closeDrawer">Close</button>
                    </div>
                    <div class="card-body">
                      <div v-if="detailLoading" class="text-muted small">Loading spans…</div>
                      <template v-else-if="waterfall">
                        <div class="text-muted small mb-2">
                          {{ waterfall.spans.length }} span{{ waterfall.spans.length === 1 ? '' : 's' }} ·
                          total {{ formatDuration(waterfall.totalNanos) }}
                        </div>
                        <div class="waterfall">
                          <div v-for="(s, i) in waterfall.spans" :key="s.spanId + '-' + i" class="waterfall-row">
                            <div :title="s.name" class="waterfall-label">
                              <span class="text-muted small">{{ s.serviceName || '—' }}</span>
                              <div class="text-truncate">{{ s.name }}</div>
                            </div>
                            <div class="waterfall-track">
                              <div :class="spanColor(s)" :style="{ marginLeft: s.offsetPct + '%', width: s.widthPct + '%' }"
                                   :title="formatDuration(s.durationNanos)"
                                   class="waterfall-bar">
                              </div>
                            </div>
                            <div class="waterfall-duration small text-muted">{{ formatDuration(s.durationNanos) }}</div>
                          </div>
                        </div>
                      </template>
                      <div v-else class="text-muted">No spans in this trace.</div>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.waterfall {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-x: auto;
}

.waterfall-row {
  display: grid;
  grid-template-columns: 220px minmax(220px, 1fr) 80px;
  align-items: center;
  gap: 8px;
  min-width: 540px;
}

.waterfall-label {
  overflow: hidden;
}

.waterfall-track {
  position: relative;
  height: 16px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
}

.waterfall-bar {
  height: 100%;
  border-radius: 4px;
}

.waterfall-duration {
  text-align: right;
}

.trace-drawer {
  border: 1px solid rgba(0, 0, 0, 0.08);
}

code {
  overflow-wrap: anywhere;
}
</style>
