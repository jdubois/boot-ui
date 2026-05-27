<script setup>
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'

const data = ref(null)
const error = ref(null)
const lastUpdated = ref(null)
const copied = ref(false)
let timer = null
let debounceHandle = null

const totalMemoryMb = ref(null)
const threadCount = ref(null)
const headRoomPercent = ref(null)
const inputsInitialized = ref(false)

const MB = 1024 * 1024

function bytesToMb(bytes) {
  return Math.round(bytes / MB)
}

function buildQuery() {
  if (!inputsInitialized.value) return ''
  const parts = []
  if (totalMemoryMb.value != null) parts.push('totalMemoryMb=' + totalMemoryMb.value)
  if (threadCount.value != null) parts.push('threadCount=' + threadCount.value)
  if (headRoomPercent.value != null) parts.push('headRoomPercent=' + headRoomPercent.value)
  return parts.length ? '?' + parts.join('&') : ''
}

async function load() {
  try {
    const res = await fetch('api/memory' + buildQuery())
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const payload = await res.json()
    data.value = payload
    lastUpdated.value = new Date()
    error.value = null
    if (!inputsInitialized.value && payload.calculation) {
      totalMemoryMb.value = bytesToMb(payload.calculation.totalMemoryBytes)
      threadCount.value = payload.calculation.threadCount
      headRoomPercent.value = payload.calculation.headRoomPercent
      inputsInitialized.value = true
    }
  } catch (e) {
    error.value = e.message
  }
}

function scheduleReload() {
  if (debounceHandle) clearTimeout(debounceHandle)
  debounceHandle = setTimeout(() => {
    load()
  }, 300)
}

function formatBytes(bytes) {
  if (bytes == null || bytes < 0) return 'N/A'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function progressClass(pct) {
  if (pct >= 90) return 'bg-danger'
  if (pct >= 75) return 'bg-warning'
  return 'bg-success'
}

function lastUpdatedText() {
  if (!lastUpdated.value) return ''
  return lastUpdated.value.toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

async function copyOptions() {
  if (!data.value) return
  try {
    await navigator.clipboard.writeText(data.value.suggestedJvmOptions)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = data.value.suggestedJvmOptions
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  }
}

const breakdown = computed(() => {
  const c = data.value?.calculation
  if (!c) return []
  const segments = [
    {key: 'heap', label: 'Heap', bytes: c.heapBytes, color: '#198754'},
    {key: 'metaspace', label: 'Metaspace', bytes: c.metaspaceBytes, color: '#0d6efd'},
    {key: 'codeCache', label: 'Code cache', bytes: c.codeCacheBytes, color: '#6610f2'},
    {key: 'directMemory', label: 'Direct', bytes: c.directMemoryBytes, color: '#fd7e14'},
    {key: 'stacks', label: 'Thread stacks', bytes: c.stackBytesTotal, color: '#ffc107'},
    {key: 'headroom', label: 'Headroom', bytes: c.headRoomBytes, color: '#6c757d'}
  ]
  const total = segments.reduce((sum, s) => sum + Math.max(0, s.bytes || 0), 0)
  return segments.map(s => ({
    ...s,
    bytes: Math.max(0, s.bytes || 0),
    percent: total > 0 ? (Math.max(0, s.bytes || 0) / total) * 100 : 0
  }))
})

function stepTotal(delta) {
  const next = Math.max(128, Math.min(8192, (totalMemoryMb.value || 0) + delta))
  totalMemoryMb.value = next
}

watch([totalMemoryMb, threadCount, headRoomPercent], () => {
  if (inputsInitialized.value) scheduleReload()
})

onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (debounceHandle) clearTimeout(debounceHandle)
})
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h2 class="mb-0"><i class="bi bi-memory me-2"></i>Memory</h2>
      <span v-if="lastUpdated" class="text-muted small">
        <i class="bi bi-arrow-repeat me-1"></i>Updated {{ lastUpdatedText() }} · auto-refreshes every 5s
      </span>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-if="data">
      <!-- Memory Calculator Panel -->
      <div v-if="data.calculation" class="card mb-4 border-primary">
        <div class="card-header bg-primary text-white d-flex justify-content-between align-items-center">
          <span><i class="bi bi-calculator me-2"></i>JVM memory calculator</span>
          <small class="text-white-50">Plan a container memory limit</small>
        </div>
        <div class="card-body">
          <p class="text-muted small mb-3">
            Inspired by the Paketo <code>libjvm</code> memory calculator.
            Heap is whatever is left after subtracting metaspace (sized from
            currently loaded classes × 1.25 safety factor), code cache, direct
            memory, thread stacks, and headroom from your target memory.
          </p>

          <div class="row g-3 mb-3">
            <div class="col-md-5">
              <label class="form-label small fw-semibold">Total container memory (MB)</label>
              <div class="input-group input-group-sm">
                <button aria-label="Decrease" class="btn btn-outline-secondary" type="button" @click="stepTotal(-64)">
                  −
                </button>
                <input
                  v-model.number="totalMemoryMb"
                  class="form-control text-center"
                  max="8192"
                  min="128"
                  step="64"
                  type="number"
                />
                <button aria-label="Increase" class="btn btn-outline-secondary" type="button" @click="stepTotal(64)">+
                </button>
                <span class="input-group-text">MB</span>
              </div>
            </div>
            <div class="col-md-4">
              <label class="form-label small fw-semibold">
                Thread count
                <span class="text-muted fw-normal">
                  (currently {{ data.calculation.liveThreadCount }})
                </span>
              </label>
              <input
                v-model.number="threadCount"
                class="form-control form-control-sm"
                max="10000"
                min="1"
                step="10"
                type="number"
              />
            </div>
            <div class="col-md-3">
              <label class="form-label small fw-semibold">Headroom (%)</label>
              <input
                v-model.number="headRoomPercent"
                class="form-control form-control-sm"
                max="30"
                min="0"
                step="1"
                type="number"
              />
            </div>
          </div>

          <div v-if="!data.calculation.valid" class="alert alert-warning small mb-3">
            <i class="bi bi-exclamation-triangle me-1"></i>{{ data.calculation.error }}
          </div>

          <template v-else>
            <div aria-label="Memory breakdown" class="progress breakdown-bar mb-2" role="img" style="height: 24px;">
              <div
                v-for="seg in breakdown"
                :key="seg.key"
                :style="{ width: seg.percent + '%', backgroundColor: seg.color }"
                :title="seg.label + ': ' + formatBytes(seg.bytes)"
                class="progress-bar"
              >
                <span v-if="seg.percent >= 8" class="small">{{ seg.label }}</span>
              </div>
            </div>
            <div class="d-flex flex-wrap gap-3 small mb-2">
              <div v-for="seg in breakdown" :key="'leg-' + seg.key" class="d-flex align-items-center">
                <span :style="{ backgroundColor: seg.color }" class="legend-swatch me-1"></span>
                <span class="text-muted me-1">{{ seg.label }}:</span>
                <span class="fw-semibold">{{ formatBytes(seg.bytes) }}</span>
              </div>
            </div>
            <div class="small text-muted">
              Currently {{ data.calculation.liveLoadedClassCount.toLocaleString() }} classes loaded ·
              metaspace sized for {{ data.calculation.loadedClasses.toLocaleString() }} classes × 1.25 safety factor
            </div>
          </template>
        </div>
      </div>

      <!-- Recommended JVM Options -->
      <div class="card mb-4 border-primary">
        <div class="card-header bg-primary text-white d-flex justify-content-between align-items-center">
          <span><i class="bi bi-rocket-takeoff me-2"></i>Recommended JVM Options</span>
          <button
            :class="{ 'btn-success': copied }"
            :disabled="!data.calculation || !data.calculation.valid"
            class="btn btn-sm btn-light"
            @click="copyOptions"
          >
            <i :class="['bi', copied ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
            {{ copied ? 'Copied!' : 'Copy' }}
          </button>
        </div>
        <div class="card-body">
          <p class="text-muted small mb-2">
            Generated from your calculator inputs. <code>-Xms == -Xmx</code> for predictable container startup;
            GC picked automatically (G1 below 4 GB, ZGC above).
          </p>
          <pre
            :class="{ 'opacity-50': data.calculation && !data.calculation.valid }"
            class="bg-dark text-light rounded p-3 mb-0 options-box"
          ><code>{{ data.suggestedJvmOptions || '—' }}</code></pre>
          <div class="mt-2">
            <span class="badge text-bg-secondary me-1"><i class="bi bi-shield-check me-1"></i>OOM protection</span>
            <span class="badge text-bg-secondary me-1"><i class="bi bi-gear me-1"></i>GC tuned</span>
          </div>
        </div>
      </div>

      <!-- Heap & Non-Heap summary cards -->
      <div class="row g-3 mb-4">
        <div class="col-md-6">
          <div class="card h-100">
            <div class="card-header d-flex align-items-center gap-2">
              <i class="bi bi-stack text-success"></i>
              <strong>Heap Memory</strong>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between mb-1">
                <span class="text-muted small">Used</span>
                <span class="fw-semibold">{{ formatBytes(data.heap.usedBytes) }}</span>
              </div>
              <div class="progress mb-3" style="height: 10px;">
                <div :aria-valuenow="data.heap.usedPercent" :class="progressClass(data.heap.usedPercent)"
                     :style="{ width: data.heap.usedPercent + '%' }" aria-valuemax="100"
                     aria-valuemin="0" class="progress-bar" role="progressbar"></div>
              </div>
              <div class="row text-center g-2">
                <div class="col-4">
                  <div class="text-muted small">Used</div>
                  <div class="fw-semibold">{{ formatBytes(data.heap.usedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Committed</div>
                  <div class="fw-semibold">{{ formatBytes(data.heap.committedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Max</div>
                  <div class="fw-semibold">{{ formatBytes(data.heap.maxBytes) }}</div>
                </div>
              </div>
            </div>
            <div class="card-footer text-muted small">
              {{ data.heap.usedPercent }}% of max used
            </div>
          </div>
        </div>

        <div class="col-md-6">
          <div class="card h-100">
            <div class="card-header d-flex align-items-center gap-2">
              <i class="bi bi-cpu text-info"></i>
              <strong>Non-Heap Memory</strong>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between mb-1">
                <span class="text-muted small">Used</span>
                <span class="fw-semibold">{{ formatBytes(data.nonHeap.usedBytes) }}</span>
              </div>
              <div class="progress mb-3" style="height: 10px;">
                <div :aria-valuenow="data.nonHeap.usedPercent"
                     :style="{ width: Math.min(data.nonHeap.usedPercent, 100) + '%' }" aria-valuemax="100"
                     aria-valuemin="0" class="progress-bar bg-info" role="progressbar"></div>
              </div>
              <div class="row text-center g-2">
                <div class="col-4">
                  <div class="text-muted small">Used</div>
                  <div class="fw-semibold">{{ formatBytes(data.nonHeap.usedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Committed</div>
                  <div class="fw-semibold">{{ formatBytes(data.nonHeap.committedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Max</div>
                  <div class="fw-semibold">
                    {{ data.nonHeap.maxBytes < 0 ? 'Unlimited' : formatBytes(data.nonHeap.maxBytes) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="card-footer text-muted small">
              Metaspace, code cache, and JIT buffers
            </div>
          </div>
        </div>
      </div>

      <!-- Memory Pools table -->
      <div class="card mb-4">
        <div class="card-header"><i class="bi bi-table me-2"></i>Memory Pools</div>
        <div class="table-responsive">
          <table class="table table-sm table-hover mb-0">
            <thead class="table-light">
            <tr>
              <th>Pool</th>
              <th class="text-end">Used</th>
              <th class="text-end">Committed</th>
              <th class="text-end">Max</th>
              <th style="width:140px">Usage</th>
            </tr>
            </thead>
            <tbody>
            <tr v-for="pool in data.pools" :key="pool.name">
              <td><code>{{ pool.name }}</code></td>
              <td class="text-end">{{ formatBytes(pool.usedBytes) }}</td>
              <td class="text-end">{{ formatBytes(pool.committedBytes) }}</td>
              <td class="text-end">{{ pool.maxBytes < 0 ? '∞' : formatBytes(pool.maxBytes) }}</td>
              <td>
                <div class="d-flex align-items-center gap-2">
                  <div class="progress flex-grow-1" style="height: 6px;">
                    <div :class="progressClass(pool.usedPercent)" :style="{ width: Math.min(pool.usedPercent, 100) + '%' }"
                         class="progress-bar" role="progressbar"></div>
                  </div>
                  <span class="text-muted small" style="width: 32px; text-align: right;">{{ pool.usedPercent }}%</span>
                </div>
              </td>
            </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Current JVM Arguments -->
      <div v-if="data.jvmInputArguments && data.jvmInputArguments.length" class="card">
        <div class="card-header"><i class="bi bi-terminal me-2"></i>Current JVM Arguments</div>
        <div class="card-body">
          <div v-if="data.jvmInputArguments.length === 0" class="text-muted small">No JVM arguments passed at startup.
          </div>
          <ul v-else class="list-unstyled mb-0">
            <li v-for="arg in data.jvmInputArguments" :key="arg" class="mb-1">
              <code class="text-secondary">{{ arg }}</code>
            </li>
          </ul>
        </div>
      </div>
      <div v-else class="card">
        <div class="card-header"><i class="bi bi-terminal me-2"></i>Current JVM Arguments</div>
        <div class="card-body text-muted small">No explicit JVM arguments were passed at startup.</div>
      </div>
    </template>

    <div v-else-if="!error" class="text-muted">Loading…</div>
  </div>
</template>

<style scoped>
.options-box {
  font-size: 0.85rem;
  white-space: pre-wrap;
  word-break: break-all;
}

.breakdown-bar .progress-bar {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #fff;
  font-weight: 500;
}

.legend-swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 2px;
}
</style>

