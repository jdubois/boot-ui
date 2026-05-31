<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch} from '../api.js'
import {formatNumber} from '../utils/format.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const loading = ref(true)
const error = ref(null)
const banner = ref(null)
const cacheFilter = ref('')
const operationFilter = ref('')
const busy = ref(null)
const lastFetched = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await fetch('api/cache')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const caches = computed(() => {
  if (!report.value) return []
  return report.value.managers.flatMap((manager) =>
    manager.caches.map((cache) => ({
      ...cache,
      managerType: manager.type,
      managerNoOp: manager.noOp
    }))
  )
})

const filteredCaches = computed(() => {
  const value = cacheFilter.value.trim().toLowerCase()
  if (!value) return caches.value
  return caches.value.filter(
    (cache) =>
      (cache.managerName || '').toLowerCase().includes(value) ||
      (cache.name || '').toLowerCase().includes(value) ||
      (cache.nativeType || '').toLowerCase().includes(value)
  )
})

const filteredOperations = computed(() => {
  if (!report.value) return []
  const value = operationFilter.value.trim().toLowerCase()
  if (!value) return report.value.operations
  return report.value.operations.filter(
    (operation) =>
      (operation.beanName || '').toLowerCase().includes(value) ||
      (operation.targetType || '').toLowerCase().includes(value) ||
      (operation.method || '').toLowerCase().includes(value) ||
      (operation.operation || '').toLowerCase().includes(value) ||
      (operation.caches || []).join(' ').toLowerCase().includes(value)
  )
})

function shortName(name) {
  if (!name) return '—'
  const i = name.lastIndexOf('.')
  return i < 0 ? name : name.substring(i + 1)
}

function formatRatio(value) {
  if (value === null || value === undefined) return '—'
  return `${Math.round(Number(value) * 100)}%`
}

function cacheKey(cache) {
  return `${cache.managerName}/${cache.name}`
}

function operationClass(operation) {
  return (
    {
      '@Cacheable': 'text-bg-primary',
      '@CachePut': 'text-bg-success',
      '@CacheEvict': 'text-bg-danger'
    }[operation] || 'text-bg-secondary'
  )
}

async function clearOne(cache) {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (
    !confirm(
      `Clear cache "${cache.name}" from manager "${cache.managerName}"? Cached data will be recomputed on demand.`
    )
  )
    return
  await clearCaches(
    {
      managerName: cache.managerName,
      cacheName: cache.name,
      confirm: true
    },
    cacheKey(cache)
  )
}

async function clearAll() {
  if (!report.value) return
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (
    !confirm(`Clear all ${report.value.cacheCount} known caches across ${report.value.managerCount} cache manager(s)?`)
  )
    return
  await clearCaches({all: true, confirm: true}, '__all__')
}

async function clearCaches(payload, busyKey) {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  busy.value = busyKey
  banner.value = null
  try {
    const res = await apiFetch('api/cache/clear', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    flash(result.message || 'Cache cleared.', 'success')
    await load()
  } catch (e) {
    flash('Could not clear cache: ' + e.message, 'danger')
  } finally {
    busy.value = null
  }
}

function flash(text, type) {
  banner.value = {text, type}
  setTimeout(() => {
    banner.value = null
  }, 6000)
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

onMounted(load)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-hdd-stack"
      title="Spring Cache"
      :subtitle="report ? `${report.managerCount} manager${report.managerCount === 1 ? '' : 's'} · ${report.cacheCount} cache${report.cacheCount === 1 ? '' : 's'} · ${report.operationCount} annotation operation${report.operationCount === 1 ? '' : 's'}` : null"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      @refresh="load"
    >
      <template #actions>
        <button
          :disabled="!report || readOnly || !report.clearEnabled || report.cacheCount === 0 || busy"
          class="btn btn-sm btn-outline-danger"
          @click="clearAll"
        >
          <span v-if="busy === '__all__'" class="spinner-border spinner-border-sm me-1"></span>
          <i v-else class="bi bi-trash me-1"></i>
          Clear all
        </button>
      </template>
    </PanelHeader>

    <div v-if="banner" :class="'alert-' + banner.type" class="alert d-flex justify-content-between align-items-center">
      <div>{{ banner.text }}</div>
      <button class="btn-close" @click="banner = null"></button>
    </div>

    <div v-if="loading" class="text-muted">Loading Spring Cache report…</div>

    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small">
        {{ warning }}
      </div>

      <div v-if="readOnly" class="alert alert-warning small">
        <i class="bi bi-lock me-1"></i>
        Cache clearing is read-only. {{ readOnlyReason }}
      </div>

      <div v-if="!report.clearEnabled" class="alert alert-info small">
        Cache clearing has been disabled by configuration. Set <code>bootui.cache.clear-enabled=true</code>
        in a trusted local profile to enable clear actions.
      </div>

      <div v-if="!report.cacheAvailable" class="alert alert-secondary">
        No <code>CacheManager</code> beans were detected. Enable Spring's cache abstraction to inspect caches here.
      </div>

      <section class="mb-4">
        <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
          <h5 class="mb-0">
            Caches <span class="badge bg-secondary">{{ report.cacheCount }}</span>
          </h5>
          <input
            v-model="cacheFilter"
            class="form-control form-control-sm cache-filter"
            placeholder="Filter by manager, cache, or implementation…"
          />
        </div>

        <div v-if="report.cacheAvailable && report.cacheCount === 0" class="alert alert-secondary small">
          Cache managers are present, but they do not currently report named caches. Some dynamic cache managers only
          expose caches after the application has used them.
        </div>

        <div v-else-if="filteredCaches.length" class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th>Manager</th>
                <th>Cache</th>
                <th>Implementation</th>
                <th>Size</th>
                <th>Metrics</th>
                <th class="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="cache in filteredCaches" :key="cacheKey(cache)">
                <td>
                  <code>{{ cache.managerName }}</code>
                  <span v-if="cache.managerNoOp" class="badge text-bg-secondary ms-1">No-op</span>
                </td>
                <td class="fw-semibold">{{ cache.name }}</td>
                <td>
                  <code>{{ shortName(cache.nativeType) }}</code>
                  <div class="small text-muted">{{ cache.nativeType || 'No native cache reported' }}</div>
                </td>
                <td>{{ formatNumber(cache.size ?? cache.metrics?.size) }}</td>
                <td>
                  <div v-if="cache.metrics && cache.metrics.available" class="cache-metrics">
                    <span class="badge text-bg-success">hits {{ formatNumber(cache.metrics.hits) }}</span>
                    <span class="badge text-bg-warning">misses {{ formatNumber(cache.metrics.misses) }}</span>
                    <span class="badge text-bg-info">ratio {{ formatRatio(cache.metrics.hitRatio) }}</span>
                    <span class="badge text-bg-secondary">puts {{ formatNumber(cache.metrics.puts) }}</span>
                    <span class="badge text-bg-secondary">evictions {{ formatNumber(cache.metrics.evictions) }}</span>
                    <span class="badge text-bg-secondary">removals {{ formatNumber(cache.metrics.removals) }}</span>
                  </div>
                  <span v-else class="text-muted small">No cache metrics registered</span>
                </td>
                <td class="text-end">
                  <button
                    :disabled="readOnly || !report.clearEnabled || busy"
                    class="btn btn-sm btn-outline-danger"
                    @click="clearOne(cache)"
                  >
                    <span v-if="busy === cacheKey(cache)" class="spinner-border spinner-border-sm me-1"></span>
                    Clear
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else-if="report.cacheAvailable" class="text-muted small">No caches match the current filter.</div>
      </section>

      <section>
        <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
          <h5 class="mb-0">
            Annotation operations <span class="badge bg-secondary">{{ report.operationCount }}</span>
          </h5>
          <input
            v-model="operationFilter"
            class="form-control form-control-sm cache-filter"
            placeholder="Filter by bean, method, operation, or cache…"
          />
        </div>

        <div v-if="report.operationCount === 0" class="alert alert-secondary small">
          No <code>@Cacheable</code>, <code>@CachePut</code>, or <code>@CacheEvict</code> operations were discovered.
        </div>

        <div v-else-if="filteredOperations.length" class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th>Operation</th>
                <th>Bean / method</th>
                <th>Caches</th>
                <th>Expressions</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="operation in filteredOperations"
                :key="operation.beanName + operation.method + operation.operation"
              >
                <td>
                  <span :class="operationClass(operation.operation)" class="badge">{{ operation.operation }}</span>
                </td>
                <td>
                  <div>
                    <code>{{ operation.beanName }}</code>
                  </div>
                  <div class="small">
                    <code>{{ operation.method }}</code>
                    <span class="text-muted"> · {{ shortName(operation.targetType) }}</span>
                  </div>
                </td>
                <td>
                  <span
                    v-for="cache in operation.caches"
                    :key="cache"
                    class="badge text-bg-light border text-dark me-1"
                  >
                    {{ cache }}
                  </span>
                </td>
                <td class="small">
                  <div v-if="operation.key">
                    key: <code>{{ operation.key }}</code>
                  </div>
                  <div v-if="operation.condition">
                    condition: <code>{{ operation.condition }}</code>
                  </div>
                  <div v-if="operation.unless">
                    unless: <code>{{ operation.unless }}</code>
                  </div>
                  <div v-if="operation.allEntries" class="text-danger">all entries</div>
                  <div v-if="operation.beforeInvocation" class="text-muted">before invocation</div>
                  <span
                    v-if="
                      !operation.key &&
                      !operation.condition &&
                      !operation.unless &&
                      !operation.allEntries &&
                      !operation.beforeInvocation
                    "
                    class="text-muted"
                    >—</span
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else class="text-muted small">No annotation operations match the current filter.</div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.cache-filter {
  max-width: 22rem;
}

.cache-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}
</style>
