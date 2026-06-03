<script setup>
import {computed, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {formatNumber} from '../utils/format.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'
import PanelHeader from './components/PanelHeader.vue'
import ServerListFooter from './components/ServerListFooter.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)

const filter = ref('')
const state = ref('')
const expanded = ref(new Set())
const downloading = ref(false)
const downloadError = ref(null)

const {
  data,
  error,
  items: threads,
  load: loadThreads,
  loadMore,
  loading: listLoading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList('api/threads', 'threads', () => ({q: filter.value.trim(), state: state.value}), {
  errorContext: 'Could not load threads'
})

const {autoRefresh, loading: refreshLoading} = useAutoRefresh(loadThreads)

const available = computed(() => data.value?.available !== false)
const unavailableReason = computed(() => data.value?.unavailableReason || 'Thread information is unavailable.')
const stateCounts = computed(() => data.value?.stateCounts || [])
const daemonThreads = computed(() => data.value?.daemonThreads ?? 0)
const peakThreads = computed(() => data.value?.peakThreads ?? 0)
const deadlockDetected = computed(() => data.value?.deadlockDetected === true)
const deadlockedThreadIds = computed(() => data.value?.deadlockedThreadIds || [])
const virtualSupported = computed(() => data.value?.virtualThreadsSupported === true)
const hasVirtualThreads = computed(() => threads.value.some((thread) => thread.virtual))
const loading = computed(() => refreshLoading.value || listLoading.value)

const subtitle = computed(() => {
  if (!available.value) return unavailableReason.value
  return `${formatNumber(totalCount.value)} threads · ${formatNumber(daemonThreads.value)} daemon · peak ${formatNumber(
    peakThreads.value
  )}`
})

function stateBadgeClass(threadState) {
  switch (threadState) {
    case 'RUNNABLE':
      return 'text-bg-success'
    case 'BLOCKED':
      return 'text-bg-danger'
    case 'WAITING':
    case 'TIMED_WAITING':
      return 'text-bg-warning'
    case 'TERMINATED':
      return 'text-bg-secondary'
    default:
      return 'text-bg-light border text-dark'
  }
}

function formatCpu(millis) {
  if (millis == null) return '—'
  if (millis < 1000) return `${formatNumber(millis)} ms`
  return `${(millis / 1000).toFixed(2)} s`
}

function toggleStack(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expanded.value = next
}

function isExpanded(id) {
  return expanded.value.has(id)
}

async function downloadDump() {
  if (readOnly.value || downloading.value) return
  downloading.value = true
  downloadError.value = null
  try {
    const res = await apiFetch('api/threads/download', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'thread-dump.txt'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    downloadError.value = 'Could not download the thread dump.'
  } finally {
    downloading.value = false
  }
}

watch([filter, state], scheduleReload)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-list-task"
      title="Threads"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="data?.capturedAt || null"
    >
      <template #actions>
        <AutoRefreshToggle v-model="autoRefresh" />
        <button
          :disabled="readOnly || downloading || !available"
          :title="readOnly ? readOnlyReason : 'Download a raw thread dump snapshot'"
          class="btn btn-outline-primary btn-sm"
          type="button"
          @click="downloadDump"
        >
          <i class="bi bi-download me-1"></i>Download dump
        </button>
      </template>
    </PanelHeader>

    <div v-if="!available" class="alert alert-secondary" role="alert">
      <i class="bi bi-info-circle me-2"></i>{{ unavailableReason }}
    </div>

    <template v-else>
      <div v-if="readOnly" class="text-muted small mb-2">
        <i class="bi bi-lock me-1"></i>Thread-dump download is read-only. {{ readOnlyReason }}
      </div>
      <div v-if="downloadError" class="alert alert-danger py-2" role="alert">{{ downloadError }}</div>

      <div v-if="deadlockDetected" class="alert alert-danger" role="alert">
        <i class="bi bi-exclamation-octagon-fill me-2"></i>
        <strong>Deadlock detected.</strong> Threads involved: {{ deadlockedThreadIds.join(', ') }}
      </div>

      <div class="d-flex flex-wrap gap-2 mb-3">
        <span v-for="count in stateCounts" :key="count.state" :class="['badge', stateBadgeClass(count.state)]">
          {{ count.state }}: {{ formatNumber(count.count) }}
        </span>
        <span v-if="virtualSupported && hasVirtualThreads" class="badge text-bg-info">
          <i class="bi bi-diagram-2 me-1"></i>Virtual threads present
        </span>
      </div>

      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input v-model="filter" class="form-control" placeholder="Filter by name, state, or stack frame…" />
        </div>
        <div class="col-md-4">
          <select v-model="state" class="form-select">
            <option value="">All states</option>
            <option v-for="count in stateCounts" :key="count.state" :value="count.state">
              {{ count.state }} ({{ count.count }})
            </option>
          </select>
        </div>
      </div>

      <div class="table-responsive">
        <table class="table table-sm table-hover threads-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>ID</th>
              <th>State</th>
              <th>Prio</th>
              <th>Daemon</th>
              <th>CPU</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="thread in threads" :key="thread.id">
              <tr :class="{'table-danger': thread.deadlocked}">
                <td>
                  <code :title="thread.name" class="text-truncate d-block">{{ thread.name }}</code>
                  <span v-if="thread.virtual" class="badge text-bg-info badge-virtual">virtual</span>
                </td>
                <td>{{ thread.id }}</td>
                <td>
                  <span :class="['badge', stateBadgeClass(thread.state)]">{{ thread.state }}</span>
                </td>
                <td>{{ thread.priority }}</td>
                <td>
                  <i v-if="thread.daemon" class="bi bi-check2 text-success"></i>
                  <span v-else class="text-muted">—</span>
                </td>
                <td>{{ formatCpu(thread.cpuTimeMillis) }}</td>
                <td class="text-end">
                  <button
                    v-if="thread.stackTrace.length"
                    :aria-expanded="isExpanded(thread.id)"
                    class="btn btn-outline-secondary btn-sm rounded-pill threads-stack-toggle"
                    type="button"
                    @click="toggleStack(thread.id)"
                  >
                    <i :class="['bi', isExpanded(thread.id) ? 'bi-chevron-up' : 'bi-code-slash', 'me-1']"></i>
                    {{ isExpanded(thread.id) ? 'Hide stack' : 'View stack' }}
                    <span class="badge rounded-pill text-bg-light ms-1">{{ thread.stackTrace.length }}</span>
                  </button>
                </td>
              </tr>
              <tr v-if="isExpanded(thread.id)" :key="`${thread.id}-stack`">
                <td colspan="7">
                  <div v-if="thread.lockName" class="small text-muted mb-1">
                    Waiting on <code>{{ thread.lockName }}</code>
                    <span v-if="thread.lockOwnerName">
                      owned by <code>{{ thread.lockOwnerName }}</code> (id {{ thread.lockOwnerId }})
                    </span>
                  </div>
                  <pre class="threads-stack mb-0"><code>{{ thread.stackTrace.join('\n') }}</code></pre>
                </td>
              </tr>
            </template>
            <tr v-if="!loading && matchedCount === 0">
              <td class="text-center text-muted py-4" colspan="7">No threads match your filters.</td>
            </tr>
          </tbody>
        </table>
      </div>

      <ServerListFooter
        v-if="!loading"
        :loading="loadingMore"
        :matched="matchedCount"
        :page-size="pageSize"
        :shown="shownCount"
        :total="totalCount"
        item-label="threads"
        @load-more="loadMore"
      />
    </template>
  </div>
</template>

<style scoped>
.threads-table {
  table-layout: fixed;
}

.threads-table th:nth-child(1) {
  width: 30%;
}

.threads-table th:nth-child(2),
.threads-table th:nth-child(4),
.threads-table th:nth-child(5) {
  width: 8%;
}

.threads-table th:nth-child(3),
.threads-table th:nth-child(6) {
  width: 14%;
}

.badge-virtual {
  font-size: 0.65rem;
}

.threads-stack {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.78rem;
  max-height: 18rem;
  overflow: auto;
}

.threads-stack-toggle {
  white-space: nowrap;
}

.threads-stack-toggle .badge {
  font-size: 0.68rem;
}
</style>
