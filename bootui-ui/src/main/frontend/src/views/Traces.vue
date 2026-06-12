<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatDuration, formatTime} from '../utils/format.js'
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
const detail = ref(null)
const error = ref(null)
const {message: banner, flash, show, clear} = useFlashMessage(4000)
const filter = ref('')
const selectedTraceId = ref(null)
const detailLoading = ref(false)
const busy = ref(false)
const lastFetched = ref(null)

async function fetchTraces() {
  error.value = null
  try {
    const res = await apiFetch('api/traces')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load traces')
  }
}

async function openTrace(traceId) {
  selectedTraceId.value = traceId
  detail.value = null
  detailLoading.value = true
  try {
    const res = await apiFetch('api/traces/' + traceId)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    detail.value = await res.json()
  } catch (e) {
    show(formatLoadError(e, 'Could not load trace'), 'danger')
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
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (!confirm('Clear all retained traces? This cannot be undone.')) return
  busy.value = true
  try {
    const res = await apiFetch('api/traces', {method: 'DELETE'})
    if (!res.ok && res.status !== 204) throw new Error('HTTP ' + res.status)
    closeDrawer()
    await load()
    flash('Cleared retained traces.', 'success')
  } catch (e) {
    show(formatLoadError(e, 'Could not clear traces'), 'danger')
  } finally {
    busy.value = false
  }
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

const filteredTraces = computed(() => {
  if (!report.value) return []
  const v = filter.value.trim().toLowerCase()
  if (!v) return report.value.traces
  return report.value.traces.filter(
    (t) =>
      (t.traceId || '').toLowerCase().includes(v) ||
      (t.httpPath || '').toLowerCase().includes(v) ||
      (t.rootSpanName || '').toLowerCase().includes(v) ||
      (t.services || []).join(' ').toLowerCase().includes(v)
  )
})

const waterfall = computed(() => {
  if (!detail.value || !detail.value.spans || detail.value.spans.length === 0) return null
  const spans = [...detail.value.spans]
  const minStart = spans.reduce((m, s) => Math.min(s.startEpochNanos, m), spans[0].startEpochNanos)
  const maxEnd = spans.reduce((m, s) => Math.max(s.endEpochNanos, m), spans[0].endEpochNanos)
  const totalNanos = Math.max(maxEnd - minStart, 1)
  const sorted = spans
    .map((s) => ({
      ...s,
      offsetPct: ((s.startEpochNanos - minStart) / totalNanos) * 100,
      widthPct: Math.max((s.durationNanos / totalNanos) * 100, 0.5)
    }))
    .sort((a, b) => a.startEpochNanos - b.startEpochNanos)
  return {spans: sorted, totalNanos}
})

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

const {autoRefresh, loading, load} = useAutoRefresh(fetchTraces)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-bezier2"
      title="Traces"
      :subtitle="
        report ? `${report.retained} / ${report.capacity} retained trace${report.retained === 1 ? '' : 's'}` : null
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
          :disabled="!report || readOnly || report.retained === 0 || busy"
          class="btn btn-sm btn-outline-danger"
          icon="bi-trash"
          label="Clear"
          @click="clearAll"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="loading && !report" />

    <template v-else-if="report">
      <div v-if="!report.enabled" class="alert alert-info small">
        Telemetry capture is disabled. Set <code>bootui.telemetry.enabled=true</code> to re-enable BootUI's local
        in-memory trace capture.
        <span v-if="report.retained > 0">Showing spans retained before telemetry was disabled.</span>
      </div>

      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Trace clearing is read-only.</ReadOnlyNotice>

      <div v-if="report.enabled && report.retained === 0" class="alert alert-secondary">
        No traces received yet. With the BootUI starter on the classpath, local application spans are captured
        automatically. Exercise your application to populate this panel; cooperating services can still export OTLP to
        <code>/bootui/api/otlp/v1/traces</code>.
      </div>

      <template v-if="report.retained > 0">
        <div class="mb-3">
          <input
            v-model="filter"
            class="form-control form-control-sm"
            placeholder="Filter by trace id, path, root span, or service…"
          />
        </div>

        <div class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th>Started</th>
                <th>Request</th>
                <th>Services</th>
                <th>Spans</th>
                <th>Duration</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="t in filteredTraces" :key="t.traceId">
                <tr :class="{'table-active': t.traceId === selectedTraceId}">
                  <td class="text-muted small">{{ formatTime(t.startEpochNanos) }}</td>
                  <td>
                    <code class="me-2">{{ shortId(t.traceId) }}</code>
                    <span
                      class="fw-semibold"
                      :title="t.httpPath && t.rootSpanName && t.httpPath !== t.rootSpanName ? t.rootSpanName : null"
                      >{{ t.httpPath || t.rootSpanName || '—' }}</span
                    >
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
                      @click="toggleTrace(t.traceId)"
                    >
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
                            {{ waterfall.spans.length }} span{{ waterfall.spans.length === 1 ? '' : 's' }} · total
                            {{ formatDuration(waterfall.totalNanos) }}
                          </div>
                          <div class="waterfall">
                            <div v-for="(s, i) in waterfall.spans" :key="s.spanId + '-' + i" class="waterfall-row">
                              <div :title="s.name" class="waterfall-label">
                                <span class="text-muted small">{{ s.serviceName || '—' }}</span>
                                <div class="text-truncate">{{ s.name }}</div>
                              </div>
                              <div class="waterfall-track">
                                <div
                                  :class="spanColor(s)"
                                  :style="{marginLeft: s.offsetPct + '%', width: s.widthPct + '%'}"
                                  :title="formatDuration(s.durationNanos)"
                                  class="waterfall-bar"
                                ></div>
                              </div>
                              <div class="waterfall-duration small text-muted">
                                {{ formatDuration(s.durationNanos) }}
                              </div>
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
