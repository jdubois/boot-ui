<script setup>
import {ref} from 'vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import {formatLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'

const data = ref(null)
const error = ref(null)
const lastUpdated = ref(null)

async function fetchMemory() {
  try {
    const res = await fetch('api/memory')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    lastUpdated.value = new Date()
    error.value = null
  } catch (e) {
    error.value = formatLoadError(e, 'Unable to load memory data')
  }
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

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchMemory)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-memory"
      title="Memory"
      subtitle="Inspect current live JVM memory metrics."
      :loading="loading"
      :error="error"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      @refresh="load"
    >
      <template #actions>
        <AutoRefreshToggle v-model="autoRefresh" />
      </template>
    </PanelHeader>

    <PanelSkeleton v-if="initialLoading" />

    <template v-else-if="data">
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
              <div class="progress mb-3" style="height: 10px">
                <div
                  :aria-valuenow="data.heap.usedPercent"
                  :class="progressClass(data.heap.usedPercent)"
                  :style="{width: data.heap.usedPercent + '%'}"
                  aria-valuemax="100"
                  aria-valuemin="0"
                  class="progress-bar"
                  role="progressbar"
                ></div>
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
            <div class="card-footer text-muted small">{{ data.heap.usedPercent }}% of max used</div>
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
              <div class="progress mb-3" style="height: 10px">
                <div
                  :aria-valuenow="data.nonHeap.usedPercent"
                  :style="{width: Math.min(data.nonHeap.usedPercent, 100) + '%'}"
                  aria-valuemax="100"
                  aria-valuemin="0"
                  class="progress-bar bg-info"
                  role="progressbar"
                ></div>
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
            <div class="card-footer text-muted small">Metaspace, code cache, and JIT buffers</div>
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
                <th style="width: 140px">Usage</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pool in data.pools" :key="pool.name">
                <td>
                  <code>{{ pool.name }}</code>
                </td>
                <td class="text-end">{{ formatBytes(pool.usedBytes) }}</td>
                <td class="text-end">{{ formatBytes(pool.committedBytes) }}</td>
                <td class="text-end">{{ pool.maxBytes < 0 ? '∞' : formatBytes(pool.maxBytes) }}</td>
                <td>
                  <div class="d-flex align-items-center gap-2">
                    <div class="progress flex-grow-1" style="height: 6px">
                      <div
                        :class="progressClass(pool.usedPercent)"
                        :style="{width: Math.min(pool.usedPercent, 100) + '%'}"
                        class="progress-bar"
                        role="progressbar"
                      ></div>
                    </div>
                    <span class="text-muted small" style="width: 32px; text-align: right">{{ pool.usedPercent }}%</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

    </template>

    <div v-else-if="!error" class="text-muted">Loading…</div>
  </div>
</template>
