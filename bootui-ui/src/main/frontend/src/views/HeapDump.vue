<script setup>
import {apiFetch} from '../api.js'
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const live = ref(true)
const filter = ref('')
const smartFilter = ref('')
let filterTimer = null
let reportRequestId = 0

const statusClasses = {
  CAPTURED: 'text-bg-success',
  ANALYZED: 'text-bg-info',
  NOT_CAPTURED: 'text-bg-secondary',
  ERROR: 'text-bg-danger'
}

const hasVisibleClasses = computed(() => (report.value?.topClasses?.length ?? 0) > 0)
const hasHistogram = computed(() => {
  const current = report.value
  return (
    hasVisibleClasses.value || (current?.histogramTotalInstances ?? 0) > 0 || (current?.histogramTotalBytes ?? 0) > 0
  )
})

function barValue(entry) {
  if (smartFilter.value === 'big-objects' && entry.instances > 0) {
    return entry.bytes / entry.instances
  }
  return entry.bytes
}

const maxBarValue = computed(() => {
  if (!hasVisibleClasses.value) return 1
  return Math.max(1, ...report.value.topClasses.map(barValue))
})

const histogramTitle = computed(() => {
  if (smartFilter.value === 'big-objects') return 'Top classes by bytes per object'
  if (smartFilter.value === 'collection-bloat') return 'Collection classes by retained size'
  return 'Top classes by retained size'
})

function statusClass(status) {
  return statusClasses[status] || 'text-bg-light border text-dark'
}

function classWidth(entry) {
  const v = barValue(entry)
  if (!v) return '0%'
  return `${Math.max(3, (v / maxBarValue.value) * 100)}%`
}

function barLabel(entry) {
  if (smartFilter.value === 'big-objects' && entry.instances > 0) {
    return formatBytes(Math.round(entry.bytes / entry.instances)) + '/obj'
  }
  return formatBytes(entry.bytes)
}

function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 0) return 'unknown'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`
}

function formatNumber(value) {
  return (value ?? 0).toLocaleString()
}

function formatTime(epochMs) {
  if (!epochMs) return ''
  return new Date(epochMs).toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

async function loadReport(options = {}) {
  const requestId = options.requestId || ++reportRequestId
  try {
    const params = new URLSearchParams()
    if (filter.value.trim()) params.set('filter', filter.value.trim())
    if (smartFilter.value) params.set('smartFilter', smartFilter.value)
    const qs = params.toString()
    const url = qs ? 'api/heap-dump?' + qs : 'api/heap-dump'
    const res = await apiFetch(url)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const next = await res.json()
    if (requestId !== reportRequestId) return
    report.value = next
    error.value = null
  } catch (e) {
    if (requestId === reportRequestId) error.value = describeLoadError(e, 'Unable to load heap dump report')
  }
}

async function runAction(path, body) {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const init = {method: 'POST'}
    if (body) {
      init.headers = {'Content-Type': 'application/x-www-form-urlencoded'}
      init.body = body
    }
    const res = await apiFetch(path, init)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    await loadReport()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to run heap dump action')
  } finally {
    loading.value = false
  }
}

function captureDump() {
  runAction('api/heap-dump/capture?live=' + (live.value ? 'true' : 'false'))
}

function analyzeHeap() {
  runAction('api/heap-dump/analyze')
}

function deleteDump(name) {
  runAction('api/heap-dump/delete', 'name=' + encodeURIComponent(name))
}

function downloadUrl(name) {
  return 'api/heap-dump/download?name=' + encodeURIComponent(name)
}

function showReadOnlyMessage() {
  actionMessage.value = readOnlyReason.value
  setTimeout(() => {
    actionMessage.value = null
  }, 6000)
}

function scheduleFilterReload() {
  reportRequestId += 1
  const requestId = reportRequestId
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => loadReport({requestId}), 250)
}

function toggleSmartFilter(name) {
  smartFilter.value = smartFilter.value === name ? '' : name
  loadReport()
}

onMounted(loadReport)

onBeforeUnmount(() => {
  clearTimeout(filterTimer)
  reportRequestId += 1
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-file-earmark-binary"
      title="Heap Dump"
      subtitle="Capture and analyze a local JVM heap snapshot to find which classes fill the heap."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <button :disabled="loading || readOnly" class="btn btn-outline-primary" type="button" @click="analyzeHeap">
          <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
          Analyze live heap
        </button>
        <button
          :disabled="loading || readOnly || report?.captureEnabled === false || report?.hotspotAvailable === false"
          class="btn btn-primary"
          type="button"
          @click="captureDump"
        >
          <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
          {{ loading ? 'Working...' : 'Capture heap dump' }}
        </button>
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <div class="alert alert-warning">
      <strong>Local-only and sensitive.</strong>
      A heap dump contains plaintext secrets (passwords, tokens, PII) pulled from live memory and bypasses BootUI's
      value masking. Dumps are written to a local directory and are never exposed over HTTP unless raw download is
      explicitly enabled. The class histogram below exposes only class names and aggregate sizes, never object values.
      <span v-if="readOnly">Capture and delete are read-only. {{ readOnlyReason }}</span>
    </div>

    <template v-if="report">
      <div v-if="!report.hotspotAvailable" class="alert alert-secondary">
        Heap dumps are not supported on this JVM (the HotSpot diagnostic MXBean is unavailable).
      </div>

      <div class="row g-3 mb-3">
        <div class="col-md-3 col-6">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Last action</div>
              <div>
                <span :class="statusClass(report.capture?.status)" class="badge">{{ report.capture?.status }}</span>
              </div>
              <div v-if="formatTime(report.capture?.capturedAtEpochMs)" class="small text-muted mt-1">
                at {{ formatTime(report.capture.capturedAtEpochMs) }}
              </div>
            </div>
          </div>
        </div>
        <div class="col-md-3 col-6">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Live heap used</div>
              <div class="fs-4 fw-semibold">{{ formatBytes(report.liveHeapUsedBytes) }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3 col-6">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Dumps on disk</div>
              <div class="fs-4 fw-semibold">{{ report.dumpCount }} / {{ report.maxDumps }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3 col-6">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Free disk</div>
              <div class="fs-4 fw-semibold">{{ formatBytes(report.freeDiskBytes) }}</div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="report.capture?.status === 'ERROR'" class="alert alert-danger">
        {{ report.capture.message }}
      </div>

      <div class="row g-3 mb-3">
        <div class="col-lg-8">
          <div class="card h-100">
            <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
              <span class="fw-semibold">{{ histogramTitle }}</span>
              <div class="d-flex flex-wrap align-items-center gap-2">
                <span v-if="hasHistogram" class="small text-muted">
                  {{ formatNumber(report.histogramTotalInstances) }} objects ·
                  {{ formatBytes(report.histogramTotalBytes) }}
                </span>
                <button
                  :disabled="!hasHistogram"
                  class="btn btn-sm"
                  :class="smartFilter === 'big-objects' ? 'btn-warning' : 'btn-outline-warning'"
                  type="button"
                  title="Sort by bytes per object — surfaces classes with unusually large individual instances"
                  @click="toggleSmartFilter('big-objects')"
                >
                  Big objects
                </button>
                <button
                  :disabled="!hasHistogram"
                  class="btn btn-sm"
                  :class="smartFilter === 'collection-bloat' ? 'btn-info' : 'btn-outline-info'"
                  type="button"
                  title="Show only Java collection classes — detects unbounded caches or excessive data loads"
                  @click="toggleSmartFilter('collection-bloat')"
                >
                  Collection bloat
                </button>
                <input
                  v-model="filter"
                  :disabled="!hasHistogram"
                  class="form-control form-control-sm"
                  style="width: 220px"
                  type="text"
                  placeholder="Filter by class prefix…"
                  @input="scheduleFilterReload"
                />
              </div>
            </div>
            <div class="card-body">
              <div v-if="!hasHistogram" class="text-center text-muted py-4">
                <i class="bi bi-bar-chart fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No heap analysis yet</div>
                <div>Use "Analyze live heap" or "Capture heap dump" to compute a class histogram.</div>
              </div>
              <div v-else-if="!hasVisibleClasses" class="text-center text-muted py-4">
                <i class="bi bi-search fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No classes match the current filter</div>
                <div>Clear the class prefix filter or turn off the smart filter to show matching classes.</div>
              </div>
              <table v-else class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col" class="text-end">#</th>
                    <th scope="col">Class</th>
                    <th scope="col" class="text-end">Instances</th>
                    <th scope="col" class="w-25">{{ smartFilter === 'big-objects' ? 'Per object' : 'Retained' }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="entry in report.topClasses" :key="entry.rank">
                    <td class="text-end text-muted">{{ entry.rank }}</td>
                    <td class="font-monospace text-break">{{ entry.className }}</td>
                    <td class="text-end">{{ formatNumber(entry.instances) }}</td>
                    <td>
                      <div class="d-flex align-items-center gap-2">
                        <div
                          class="progress flex-grow-1"
                          role="img"
                          :aria-label="`${entry.className}: ${barLabel(entry)}`"
                        >
                          <div class="progress-bar text-bg-primary" :style="{width: classWidth(entry)}"></div>
                        </div>
                        <span class="small text-muted text-nowrap">{{ barLabel(entry) }}</span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="col-lg-4">
          <div class="card h-100">
            <div class="card-header fw-semibold">Capture options</div>
            <div class="card-body">
              <div class="form-check form-switch mb-3">
                <input id="heapDumpLive" v-model="live" :disabled="readOnly" class="form-check-input" type="checkbox" />
                <label class="form-check-label" for="heapDumpLive">Live objects only (run GC first)</label>
              </div>
              <dl class="row small mb-0">
                <dt class="col-5 text-muted">Output dir</dt>
                <dd class="col-7 text-break font-monospace">{{ report.outputDirectory }}</dd>
                <dt class="col-5 text-muted">Capture</dt>
                <dd class="col-7">{{ report.captureEnabled ? 'Enabled' : 'Disabled' }}</dd>
                <dt class="col-5 text-muted">Raw download</dt>
                <dd class="col-7">{{ report.rawDownloadEnabled ? 'Enabled' : 'Disabled' }}</dd>
              </dl>
              <p class="small text-muted mt-3 mb-0">
                For deep analysis, capture a dump and open the downloaded <code>.hprof</code> in Eclipse MAT or
                VisualVM.
              </p>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header fw-semibold">Captured dumps</div>
        <div v-if="report.dumps.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-folder2-open fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">No heap dumps on disk</div>
          <div>Capture a heap dump to write a snapshot to {{ report.outputDirectory }}.</div>
        </div>
        <div v-else class="table-responsive">
          <table class="table table-sm align-middle mb-0">
            <thead>
              <tr>
                <th scope="col">File</th>
                <th scope="col" class="text-end">Size</th>
                <th scope="col">Scope</th>
                <th scope="col">Captured</th>
                <th scope="col" class="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="dump in report.dumps" :key="dump.name">
                <td class="font-monospace text-break">{{ dump.name }}</td>
                <td class="text-end">{{ formatBytes(dump.sizeBytes) }}</td>
                <td>
                  <span class="badge" :class="dump.live ? 'text-bg-info' : 'text-bg-secondary'">
                    {{ dump.live ? 'live' : 'all' }}
                  </span>
                </td>
                <td class="small text-muted">{{ formatTime(dump.createdAtEpochMs) }}</td>
                <td class="text-end text-nowrap">
                  <a
                    v-if="report.rawDownloadEnabled"
                    :href="downloadUrl(dump.name)"
                    class="btn btn-sm btn-outline-secondary me-1"
                  >
                    Download
                  </a>
                  <button
                    :disabled="loading || readOnly"
                    class="btn btn-sm btn-outline-danger"
                    type="button"
                    @click="deleteDump(dump.name)"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>
