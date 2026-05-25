<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'

const data = ref(null)
const error = ref(null)
const lastUpdated = ref(null)
const copied = ref(false)
let timer = null

async function load() {
  try {
    const res = await fetch('api/memory')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    lastUpdated.value = new Date()
    error.value = null
  } catch (e) {
    error.value = e.message
  }
}

function formatBytes(bytes) {
  if (bytes < 0) return 'N/A'
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
  return lastUpdated.value.toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function copyOptions() {
  if (!data.value) return
  try {
    await navigator.clipboard.writeText(data.value.suggestedJvmOptions)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // fallback for older browsers
    const ta = document.createElement('textarea')
    ta.value = data.value.suggestedJvmOptions
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h2 class="mb-0"><i class="bi bi-memory me-2"></i>Memory</h2>
      <span class="text-muted small" v-if="lastUpdated">
        <i class="bi bi-arrow-repeat me-1"></i>Updated {{ lastUpdatedText() }} · auto-refreshes every 5s
      </span>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-if="data">
      <!-- JVM Options Panel -->
      <div class="card mb-4 border-primary">
        <div class="card-header bg-primary text-white d-flex justify-content-between align-items-center">
          <span><i class="bi bi-rocket-takeoff me-2"></i>Recommended JVM Options</span>
          <button class="btn btn-sm btn-light" @click="copyOptions" :class="{ 'btn-success': copied }">
            <i :class="['bi', copied ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
            {{ copied ? 'Copied!' : 'Copy' }}
          </button>
        </div>
        <div class="card-body">
          <p class="text-muted small mb-2">
            Calculated from current memory usage. Includes Kubernetes container-aware settings and best practices.
          </p>
          <pre class="bg-dark text-light rounded p-3 mb-0 options-box"><code>{{ data.suggestedJvmOptions }}</code></pre>
          <div class="mt-2">
            <span class="badge text-bg-secondary me-1"><i class="bi bi-box me-1"></i>Kubernetes-ready</span>
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
                <div class="progress-bar" :class="progressClass(data.heap.usedPercent)"
                     :style="{ width: data.heap.usedPercent + '%' }" role="progressbar"
                     :aria-valuenow="data.heap.usedPercent" aria-valuemin="0" aria-valuemax="100"></div>
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
                <div class="progress-bar bg-info"
                     :style="{ width: Math.min(data.nonHeap.usedPercent, 100) + '%' }" role="progressbar"
                     :aria-valuenow="data.nonHeap.usedPercent" aria-valuemin="0" aria-valuemax="100"></div>
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
                  <div class="fw-semibold">{{ data.nonHeap.maxBytes < 0 ? 'Unlimited' : formatBytes(data.nonHeap.maxBytes) }}</div>
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
                      <div class="progress-bar" :class="progressClass(pool.usedPercent)"
                           :style="{ width: Math.min(pool.usedPercent, 100) + '%' }" role="progressbar"></div>
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
      <div class="card" v-if="data.jvmInputArguments && data.jvmInputArguments.length">
        <div class="card-header"><i class="bi bi-terminal me-2"></i>Current JVM Arguments</div>
        <div class="card-body">
          <div v-if="data.jvmInputArguments.length === 0" class="text-muted small">No JVM arguments passed at startup.</div>
          <ul class="list-unstyled mb-0" v-else>
            <li v-for="arg in data.jvmInputArguments" :key="arg" class="mb-1">
              <code class="text-secondary">{{ arg }}</code>
            </li>
          </ul>
        </div>
      </div>
      <div class="card" v-else>
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
</style>
