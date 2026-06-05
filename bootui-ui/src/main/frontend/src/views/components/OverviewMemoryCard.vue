<script setup>
import {computed, onMounted} from 'vue'
import {useMemoryReport, formatBytes} from '../../utils/memoryReport.js'
import {useAutoRefresh} from '../../utils/useAutoRefresh.js'

const {data, error, loading, initialLoading, load} = useMemoryReport({
  endpoint: 'api/tuning-advisor',
  tuningInputs: true
})

const {autoRefresh} = useAutoRefresh(load)

const breakdown = computed(() => {
  const c = data.value?.calculation
  if (!c) return []
  const segments = [
    {key: 'heap', label: 'Heap', bytes: c.heapBytes, color: '#198754'},
    {key: 'metaspace', label: 'Metaspace', bytes: c.metaspaceBytes, color: '#0d6efd'},
    {key: 'codeCache', label: 'Code cache', bytes: c.codeCacheBytes, color: '#6610f2'},
    {key: 'directMemory', label: 'Direct', bytes: c.directMemoryBytes, color: '#fd7e14'},
    {key: 'stacks', label: 'Thread stacks', bytes: c.stackBytesTotal, color: '#ffc107'}
  ]
  const total = segments.reduce((sum, s) => sum + Math.max(0, s.bytes || 0), 0)
  return segments.map((s) => ({
    ...s,
    bytes: Math.max(0, s.bytes || 0),
    percent: total > 0 ? (Math.max(0, s.bytes || 0) / total) * 100 : 0
  }))
})

const totalBytes = computed(() => {
  return breakdown.value.reduce((sum, s) => sum + s.bytes, 0)
})
</script>

<template>
  <div class="scanner-card card h-100">
    <div class="card-body d-flex flex-column">
      <div class="d-flex align-items-center gap-2 mb-3">
        <span class="scanner-icon scanner-icon--primary"><i class="bi bi-cpu"></i></span>
        <div class="flex-grow-1 min-w-0">
          <div class="fw-bold text-truncate">Memory</div>
        </div>
        <div v-if="loading && !initialLoading" class="spinner-border spinner-border-sm text-secondary" role="status">
          <span class="visually-hidden">Loading...</span>
        </div>
      </div>

      <div class="scanner-body flex-grow-1">
        <template v-if="initialLoading">
          <div class="d-flex align-items-center gap-2 text-muted">
            <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
            <span>Loading…</span>
          </div>
        </template>
        <template v-else-if="error">
          <div class="text-danger small"><i class="bi bi-exclamation-triangle-fill me-1"></i>{{ error }}</div>
        </template>
        <template v-else-if="data?.calculation?.valid">
          <div class="d-flex align-items-baseline gap-2 mb-2">
            <span class="fs-4 fw-semibold">{{ formatBytes(totalBytes) }}</span>
            <span class="text-muted small">JVM total</span>
          </div>
          <div aria-label="Memory breakdown" class="progress breakdown-bar mb-2" role="img" style="height: 12px">
            <div
              v-for="seg in breakdown"
              :key="seg.key"
              :style="{width: seg.percent + '%', backgroundColor: seg.color}"
              :title="seg.label + ': ' + formatBytes(seg.bytes)"
              class="progress-bar"
            ></div>
          </div>
          <div class="d-flex flex-wrap gap-2 small text-muted">
            <div v-for="seg in breakdown" :key="'leg-' + seg.key" class="d-flex align-items-center">
              <span
                :style="{backgroundColor: seg.color}"
                class="legend-swatch me-1"
                style="width: 8px; height: 8px; display: inline-block; border-radius: 2px"
              ></span>
              <span>{{ seg.label }}</span>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="alert alert-warning small mb-0 py-1 px-2">
            <i class="bi bi-exclamation-triangle me-1"></i>{{ data?.calculation?.error || 'Unavailable' }}
          </div>
        </template>
      </div>

      <div class="d-flex gap-2 mt-3">
        <router-link to="/memory" class="btn btn-sm btn-outline-secondary ms-auto">
          Open panel<i class="bi bi-arrow-right-short"></i>
        </router-link>
      </div>
    </div>
  </div>
</template>
