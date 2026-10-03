<script setup>
import {computed} from 'vue'
import FlashBanner from './components/FlashBanner.vue'
import MemoryOffloadButton from './components/MemoryOffloadButton.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ProgressBar from './components/ProgressBar.vue'
import UnavailableState from './components/UnavailableState.vue'
import {formatBytes, memoryProgressClass, useMemoryReport} from '../utils/memoryReport.js'
import {useDataState} from '../utils/panelState.js'

const {data, error, lastUpdated, autoRefresh, loading, hasLoaded, load} = useMemoryReport()
const panelState = useDataState({
  loading,
  loaded: hasLoaded,
  error,
  hasData: computed(() => data.value !== null)
})
const staleMessage = {
  text: 'Live memory could not be refreshed. Showing the last successful snapshot.',
  type: 'warning'
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-memory"
      title="Live Memory"
      subtitle="Inspect live JVM heap, non-heap, and memory pool usage."
      :loading="loading"
      :error="panelState.retryableError.value ? error : null"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <MemoryOffloadButton @offloaded="load" />
      </template>
    </PanelHeader>

    <PanelSkeleton v-if="panelState.initialLoading.value" label="Loading live memory…" />

    <template v-else-if="data">
      <FlashBanner v-if="panelState.stale.value" :dismissible="false" :message="staleMessage" with-icon />
      <div class="row g-3 mb-4">
        <div class="col-md-6">
          <div class="card h-100">
            <div class="card-header d-flex align-items-center gap-2">
              <i class="bi bi-stack text-success"></i>
              <h3>Heap memory</h3>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between mb-1">
                <span class="text-muted small">Used</span>
                <span class="fw-semibold font-monospace">{{ formatBytes(data.heap.usedBytes) }}</span>
              </div>
              <ProgressBar
                :bar-class="memoryProgressClass(data.heap.usedPercent)"
                label="Heap memory used"
                :value="data.heap.usedPercent"
                :value-text="`${data.heap.usedPercent}% of maximum used`"
                class="live-memory-bar mb-3"
              />
              <div class="row text-center g-2">
                <div class="col-4">
                  <div class="text-muted small">Used</div>
                  <div class="fw-semibold font-monospace">{{ formatBytes(data.heap.usedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Committed</div>
                  <div class="fw-semibold font-monospace">{{ formatBytes(data.heap.committedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Max</div>
                  <div class="fw-semibold font-monospace">{{ formatBytes(data.heap.maxBytes) }}</div>
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
              <h3>Non-heap memory</h3>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between mb-1">
                <span class="text-muted small">Used</span>
                <span class="fw-semibold font-monospace">{{ formatBytes(data.nonHeap.usedBytes) }}</span>
              </div>
              <ProgressBar
                bar-class="bg-info"
                label="Non-heap memory used"
                :value="data.nonHeap.usedPercent"
                :value-text="`${data.nonHeap.usedPercent}% used`"
                class="live-memory-bar mb-3"
              />
              <div class="row text-center g-2">
                <div class="col-4">
                  <div class="text-muted small">Used</div>
                  <div class="fw-semibold font-monospace">{{ formatBytes(data.nonHeap.usedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Committed</div>
                  <div class="fw-semibold font-monospace">{{ formatBytes(data.nonHeap.committedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Max</div>
                  <div class="fw-semibold font-monospace">
                    {{ data.nonHeap.maxBytes < 0 ? 'Unlimited' : formatBytes(data.nonHeap.maxBytes) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="card-footer text-muted small">Metaspace, code cache, and JIT buffers</div>
          </div>
        </div>
      </div>

      <div class="card mb-4">
        <div class="card-header">
          <h3><i class="bi bi-table me-2"></i>Memory pools</h3>
        </div>
        <div class="table-responsive">
          <table class="table table-sm table-hover mb-0">
            <thead class="table-light">
              <tr>
                <th>Pool</th>
                <th class="text-end">Used</th>
                <th class="text-end">Committed</th>
                <th class="text-end">Max</th>
                <th class="live-memory-usage-column">Usage</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!data.pools || data.pools.length === 0">
                <td class="text-muted py-3" colspan="5">The JVM did not report any memory pools for this runtime.</td>
              </tr>
              <tr v-for="pool in data.pools" :key="pool.name">
                <td>
                  <code>{{ pool.name }}</code>
                </td>
                <td class="text-end font-monospace">{{ formatBytes(pool.usedBytes) }}</td>
                <td class="text-end font-monospace">{{ formatBytes(pool.committedBytes) }}</td>
                <td class="text-end font-monospace">{{ pool.maxBytes < 0 ? '∞' : formatBytes(pool.maxBytes) }}</td>
                <td>
                  <div class="d-flex align-items-center gap-2">
                    <ProgressBar
                      :bar-class="memoryProgressClass(pool.usedPercent)"
                      class="flex-grow-1 live-memory-bar live-memory-bar--row"
                      :label="`${pool.name} memory pool used`"
                      :value="pool.usedPercent"
                      :value-text="`${pool.usedPercent}% used`"
                    />
                    <span class="text-muted small live-memory-percent">{{ pool.usedPercent }}%</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <UnavailableState
      v-else-if="panelState.unavailable.value"
      message="Live memory data is unavailable. Retry or refresh this panel."
    />
  </div>
</template>

<style scoped>
.live-memory-usage-column {
  min-width: 8.75rem;
}

.live-memory-percent {
  font-family: var(--bs-font-monospace);
  min-width: 2.75rem;
  text-align: right;
}

.live-memory-bar {
  height: 0.625rem;
}

.live-memory-bar--row {
  height: 0.375rem;
}
</style>
