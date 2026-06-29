<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, inject, ref} from 'vue'
import {formatNumber, shortName} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const panels = inject('panels', ref(null))
const platform = computed(() => panels.value?.platform ?? 'spring-boot')
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, clear} = useFlashMessage()
const cacheFilter = ref('')
const operationFilter = ref('')
const busy = ref(null)
const lastFetched = ref(null)

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/cache')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load cache report')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport)

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
    !(await confirm({
      title: 'Clear cache?',
      message: `Clear cache "${cache.name}" from manager "${cache.managerName}"? Cached data is recomputed on demand.`,
      resource: cache.name,
      confirmLabel: 'Clear',
      danger: true
    }))
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
    !(await confirm({
      title: 'Clear all caches?',
      message: `Clear all ${report.value.cacheCount} known caches across ${report.value.managerCount} cache manager(s)? Cached data is recomputed on demand.`,
      confirmLabel: 'Clear all',
      danger: true
    }))
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
  clear()
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
    flash(formatLoadError(e, 'Could not clear cache'), 'danger')
  } finally {
    busy.value = null
  }
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

// useAutoRefresh automatically loads on mount unless configured otherwise
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-hdd-stack"
      title="Cache"
      :subtitle="
        report
          ? platform === 'quarkus'
            ? `${report.managerCount} manager${report.managerCount === 1 ? '' : 's'} · ${report.cacheCount} cache${report.cacheCount === 1 ? '' : 's'}`
            : `${report.managerCount} manager${report.managerCount === 1 ? '' : 's'} · ${report.cacheCount} cache${report.cacheCount === 1 ? '' : 's'} · ${report.operationCount} annotation operation${report.operationCount === 1 ? '' : 's'}`
          : null
      "
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy === '__all__'"
          :disabled="!report || readOnly || !report.clearEnabled || report.cacheCount === 0 || busy"
          class="btn btn-sm btn-outline-danger ms-2"
          icon="bi-trash"
          label="Clear all"
          @click="clearAll"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="initialLoading && !report" />

    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small">
        {{ warning }}
      </div>

      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Cache clearing is read-only.</ReadOnlyNotice>

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
                  <SpinnerButton
                    :loading="busy === cacheKey(cache)"
                    :disabled="readOnly || !report.clearEnabled || busy"
                    class="btn btn-sm btn-outline-danger"
                    label="Clear"
                    @click="clearOne(cache)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else-if="report.cacheAvailable" class="text-muted small">No caches match the current filter.</div>
      </section>

      <section v-if="platform !== 'quarkus'">
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

      <section v-else>
        <h5 class="mb-2">Cached operations</h5>
        <div class="alert alert-secondary small mb-0">
          Quarkus binds caching with build-time annotations (<code>@CacheResult</code>,
          <code>@CacheInvalidate</code>, <code>@CacheInvalidateAll</code>) woven into your methods at compile time, so
          there is no runtime registry of cached operations to list here. The caches above are read live from the
          Quarkus <code>CacheManager</code>; exercise a cached method to populate their metrics.
        </div>
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
