<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch} from '../api.js'
import {panelProps, usePanelState} from '../utils/panelState.js'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const live = ref(true)

const statusClasses = {
  CAPTURED: 'text-bg-success',
  ANALYZED: 'text-bg-info',
  NOT_CAPTURED: 'text-bg-secondary',
  ERROR: 'text-bg-danger'
}

const hasHistogram = computed(() => (report.value?.topClasses?.length ?? 0) > 0)
const maxClassBytes = computed(() => {
  if (!hasHistogram.value) return 1
  return Math.max(1, ...report.value.topClasses.map((entry) => entry.bytes))
})

function statusClass(status) {
  return statusClasses[status] || 'text-bg-light border text-dark'
}

function classWidth(bytes) {
  if (!bytes) return '0%'
  return `${Math.max(3, (bytes / maxClassBytes.value) * 100)}%`
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

async function loadReport() {
  try {
    const res = await fetch('api/heap-dump')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = e.message
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
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = e.message
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

onMounted(loadReport)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="h4 mb-1"><i class="bi bi-file-earmark-binary me-2"></i>Heap Dump</h2>
        <p class="text-muted mb-0">
          Capture and analyze a local JVM heap snapshot to find which classes fill the heap.
        </p>
      </div>
      <div class="d-flex flex-wrap align-items-center gap-2">
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
      </div>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>
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
              <span class="fw-semibold">Top classes by retained size</span>
              <span v-if="hasHistogram" class="small text-muted">
                {{ formatNumber(report.histogramTotalInstances) }} objects ·
                {{ formatBytes(report.histogramTotalBytes) }}
              </span>
            </div>
            <div class="card-body">
              <div v-if="!hasHistogram" class="text-center text-muted py-4">
                <i class="bi bi-bar-chart fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No heap analysis yet</div>
                <div>Use "Analyze live heap" or "Capture heap dump" to compute a class histogram.</div>
              </div>
              <table v-else class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col" class="text-end">#</th>
                    <th scope="col">Class</th>
                    <th scope="col" class="text-end">Instances</th>
                    <th scope="col" class="w-25">Retained</th>
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
                          :aria-label="`${entry.className}: ${formatBytes(entry.bytes)}`"
                        >
                          <div class="progress-bar text-bg-primary" :style="{width: classWidth(entry.bytes)}"></div>
                        </div>
                        <span class="small text-muted text-nowrap">{{ formatBytes(entry.bytes) }}</span>
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
