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
      managerNoOp: manager.noOp,
      managerComposition: manager.composition,
      managerDynamicCaches: manager.dynamicCaches,
      managerDelegateTypes: manager.delegateTypes || []
    }))
  )
})

const expandedTiers = ref(new Set())

function toggleTiers(cache) {
  const key = cacheKey(cache)
  const next = new Set(expandedTiers.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedTiers.value = next
}

function tiersExpanded(cache) {
  return expandedTiers.value.has(cacheKey(cache))
}

/**
 * A disclosure id derived from the row position rather than the cache name: manager and cache names are
 * arbitrary strings, so slugifying them can collide (`a/b` + `c` and `a` + `b/c` slugify alike) and a
 * duplicate id would break both `aria-controls` and any lookup by id.
 */
function tierRowId(index) {
  return `cache-tiers-${index}`
}

const COMPOSITION_LABELS = {
  COMPOSITE: 'Composite',
  DELEGATING: 'Delegating'
}

const DYNAMIC_LABELS = {
  YES: 'Creates caches on demand',
  NO: 'Fixed cache set'
}

function compositionLabel(cache) {
  return COMPOSITION_LABELS[cache.managerComposition] || null
}

function dynamicLabel(cache) {
  return DYNAMIC_LABELS[cache.managerDynamicCaches] || null
}

const LOCALITY_LABELS = {
  LOCAL: 'In this JVM',
  DISTRIBUTED: 'Remote',
  UNKNOWN: 'Locality not reported'
}

function localityLabel(tier) {
  return LOCALITY_LABELS[tier.locality] || LOCALITY_LABELS.UNKNOWN
}

// The only source CacheStatisticsDto ever carries today is NATIVE; the Micrometer overlay travels in the
// separate CacheMetricsDto and is labelled where it is rendered.
const SOURCE_LABELS = {
  NATIVE: 'Provider statistics'
}

function sourceLabel(statistics) {
  if (!statistics) return 'Statistics'
  const label = SOURCE_LABELS[statistics.source] || 'Statistics'
  return statistics.provider ? `${label} · ${statistics.provider}` : label
}

/**
 * The counters a statistics set actually exposes. A counter the provider does not expose is omitted rather
 * than rendered as zero, so an absent counter is never read as "nothing happened".
 */
function counterBadges(statistics) {
  if (!statistics || !statistics.available) return []
  const badges = []
  const push = (label, value, cls) => {
    if (value !== null && value !== undefined) badges.push({label, text: `${label} ${formatNumber(value)}`, cls})
  }
  push('hits', statistics.hits, 'text-bg-success')
  push('misses', statistics.misses, 'text-bg-warning')
  push('requests', statistics.requests, 'text-bg-secondary')
  push('puts', statistics.puts, 'text-bg-secondary')
  push('evictions', statistics.evictions, 'text-bg-secondary')
  push('removals', statistics.removals, 'text-bg-secondary')
  push('load successes', statistics.loadSuccesses, 'text-bg-secondary')
  push('load failures', statistics.loadFailures, 'text-bg-secondary')
  return badges
}

function hasStatistics(statistics) {
  return Boolean(statistics && statistics.available)
}

function hasRatio(statistics) {
  return Boolean(statistics) && statistics.hitRatio !== null && statistics.hitRatio !== undefined
}

/**
 * Micrometer's `cache.gets` meters report a 0.0 hit ratio for a cache that has never been asked for anything,
 * which reads as "0% of requests hit" rather than "there were no requests". The wire value is left alone for
 * contract stability, so the panel is the one that has to refuse to state it.
 */
function hasMicrometerRatio(metrics) {
  if (!metrics) return false
  return (metrics.hits || 0) + (metrics.misses || 0) > 0
}

/** `0` is a real maximum size; only a missing value means the provider did not report one. */
function maximumSizeText(tier) {
  return tier.maximumSize === null || tier.maximumSize === undefined ? 'Not reported' : formatNumber(tier.maximumSize)
}

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

function windowNote(statistics) {
  if (!statistics) return null
  const parts = []
  if (statistics.window === 'APPLICATION_LIFETIME') parts.push('since the cache was created')
  else if (statistics.window === 'UNKNOWN') parts.push('over an unknown period')
  if (statistics.since) parts.push(`recorded from ${statistics.since}`)
  return parts.length ? parts.join(', ') : null
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
      message: report.value.truncated
        ? `Clear every cache across ${report.value.managerCount} cache manager(s)? The table above was truncated, so this clears more caches than it shows. Cached data is recomputed on demand.`
        : `Clear all ${report.value.cacheCount} known caches across ${report.value.managerCount} cache manager(s)? Cached data is recomputed on demand.`,
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
            ? `${report.managerCount} manager${report.managerCount === 1 ? '' : 's'} · ${report.cacheCount} cache${report.cacheCount === 1 ? '' : 's'} · ${report.tierCount} tier${report.tierCount === 1 ? '' : 's'}`
            : `${report.managerCount} manager${report.managerCount === 1 ? '' : 's'} · ${report.cacheCount} cache${report.cacheCount === 1 ? '' : 's'} · ${report.tierCount} tier${report.tierCount === 1 ? '' : 's'} · ${report.operationCount} annotation operation${report.operationCount === 1 ? '' : 's'}`
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
          <h3 class="h5 mb-0">
            Caches <span class="badge bg-secondary">{{ report.cacheCount }}</span>
          </h3>
          <input
            v-model="cacheFilter"
            aria-label="Filter caches"
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
                <th>Tiers</th>
                <th>Hits and misses</th>
                <th class="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="(cache, cacheIndex) in filteredCaches" :key="cacheKey(cache)">
                <tr>
                  <td>
                    <code>{{ cache.managerName }}</code>
                    <span v-if="cache.managerNoOp" class="badge text-bg-secondary ms-1">No-op</span>
                    <div v-if="compositionLabel(cache) || dynamicLabel(cache)" class="small text-muted">
                      <span v-if="compositionLabel(cache)">{{ compositionLabel(cache) }}</span>
                      <span v-if="compositionLabel(cache) && dynamicLabel(cache)"> · </span>
                      <span v-if="dynamicLabel(cache)">{{ dynamicLabel(cache) }}</span>
                    </div>
                  </td>
                  <td>
                    <code class="fw-semibold">{{ cache.name }}</code>
                  </td>
                  <td>
                    <code>{{ shortName(cache.nativeType) }}</code>
                    <div class="small text-muted">{{ cache.nativeType || 'No native cache reported' }}</div>
                  </td>
                  <td>{{ formatNumber(cache.size ?? cache.metrics?.size) }}</td>
                  <td>
                    <button
                      v-if="cache.tiers && cache.tiers.length"
                      type="button"
                      class="btn btn-sm btn-outline-secondary cache-tier-toggle"
                      :aria-expanded="tiersExpanded(cache) ? 'true' : 'false'"
                      :aria-controls="tierRowId(cacheIndex)"
                      @click="toggleTiers(cache)"
                    >
                      <i :class="tiersExpanded(cache) ? 'bi-chevron-down' : 'bi-chevron-right'" aria-hidden="true"></i>
                      {{ cache.tiers.length }} tier{{ cache.tiers.length === 1 ? '' : 's' }}
                      <span class="visually-hidden">for cache {{ cache.name }}</span>
                    </button>
                    <span v-else class="badge text-bg-light border text-dark" :title="cache.opaqueReason || ''">
                      Not described
                    </span>
                  </td>
                  <td>
                    <div v-if="hasStatistics(cache.statistics)" class="cache-metrics-group">
                      <div class="small text-muted">{{ sourceLabel(cache.statistics) }}</div>
                      <div class="cache-metrics">
                        <span
                          v-for="badge in counterBadges(cache.statistics)"
                          :key="badge.label"
                          :class="badge.cls"
                          class="badge"
                          >{{ badge.text }}</span
                        >
                        <span v-if="hasRatio(cache.statistics)" class="badge text-bg-info"
                          >ratio {{ formatRatio(cache.statistics.hitRatio) }}</span
                        >
                        <span
                          v-else
                          class="badge text-bg-light border text-dark"
                          :title="cache.statistics.ratioUnavailableReason || ''"
                          >ratio unknown</span
                        >
                      </div>
                      <div v-if="windowNote(cache.statistics)" class="small text-muted">
                        {{ windowNote(cache.statistics) }}
                      </div>
                    </div>
                    <div v-else-if="cache.statistics" class="small text-muted">
                      {{ cache.statistics.unavailableReason }}
                    </div>

                    <div v-if="cache.metrics && cache.metrics.available" class="cache-metrics-group mt-2">
                      <div class="small text-muted">Micrometer meters</div>
                      <div class="cache-metrics">
                        <span class="badge text-bg-success">hits {{ formatNumber(cache.metrics.hits) }}</span>
                        <span class="badge text-bg-warning">misses {{ formatNumber(cache.metrics.misses) }}</span>
                        <span v-if="hasMicrometerRatio(cache.metrics)" class="badge text-bg-info"
                          >ratio {{ formatRatio(cache.metrics.hitRatio) }}</span
                        >
                        <span
                          v-else
                          class="badge text-bg-light border text-dark"
                          title="No cache requests have been recorded yet. Micrometer reports a 0.0 ratio at zero requests, which BootUI does not repeat as 0%."
                          >ratio unknown</span
                        >
                        <span class="badge text-bg-secondary">puts {{ formatNumber(cache.metrics.puts) }}</span>
                        <span class="badge text-bg-secondary"
                          >evictions {{ formatNumber(cache.metrics.evictions) }}</span
                        >
                        <span class="badge text-bg-secondary">removals {{ formatNumber(cache.metrics.removals) }}</span>
                      </div>
                    </div>
                    <div v-else-if="!cache.statistics" class="text-muted small">No cache metrics registered</div>
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
                <tr
                  v-if="cache.tiers && cache.tiers.length"
                  v-show="tiersExpanded(cache)"
                  :id="tierRowId(cacheIndex)"
                  class="cache-tier-row"
                >
                  <td colspan="7">
                    <div class="table-responsive">
                      <table class="table table-sm mb-0 align-middle">
                        <caption class="visually-hidden">
                          Backing tiers of cache
                          {{
                            cache.name
                          }}
                        </caption>
                        <thead>
                          <tr>
                            <th scope="col">Tier</th>
                            <th scope="col">Implementation</th>
                            <th scope="col">Locality</th>
                            <th scope="col">Maximum size</th>
                            <th scope="col">Expiry</th>
                            <th scope="col">Hits and misses</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="tier in cache.tiers" :key="tier.id">
                            <th scope="row" class="fw-normal">
                              <span class="badge text-bg-secondary">L{{ tier.level }}</span>
                              <span class="ms-1">{{ tier.name }}</span>
                            </th>
                            <td>
                              <code>{{ shortName(tier.implementationType) }}</code>
                              <div class="small text-muted">{{ tier.implementationType || 'Not reported' }}</div>
                            </td>
                            <td>{{ localityLabel(tier) }}</td>
                            <td>
                              {{ maximumSizeText(tier) }}
                              <div v-if="tier.policyNote" class="small text-muted">{{ tier.policyNote }}</div>
                            </td>
                            <td>{{ tier.expiryPolicy || 'No expiry configured' }}</td>
                            <td>
                              <div v-if="hasStatistics(tier.statistics)" class="cache-metrics-group">
                                <div class="small text-muted">{{ sourceLabel(tier.statistics) }}</div>
                                <div class="cache-metrics">
                                  <span
                                    v-for="badge in counterBadges(tier.statistics)"
                                    :key="badge.label"
                                    :class="badge.cls"
                                    class="badge"
                                    >{{ badge.text }}</span
                                  >
                                  <span v-if="hasRatio(tier.statistics)" class="badge text-bg-info"
                                    >ratio {{ formatRatio(tier.statistics.hitRatio) }}</span
                                  >
                                  <span
                                    v-else
                                    class="badge text-bg-light border text-dark"
                                    :title="tier.statistics.ratioUnavailableReason || ''"
                                    >ratio unknown</span
                                  >
                                </div>
                                <div v-if="windowNote(tier.statistics)" class="small text-muted">
                                  {{ windowNote(tier.statistics) }}
                                </div>
                              </div>
                              <div v-else class="small text-muted">{{ tier.statistics?.unavailableReason }}</div>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>

        <div v-else-if="report.cacheAvailable" class="text-muted small">No caches match the current filter.</div>
      </section>

      <section v-if="platform !== 'quarkus'">
        <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
          <h3 class="h5 mb-0">
            Annotation operations <span class="badge bg-secondary">{{ report.operationCount }}</span>
          </h3>
          <input
            v-model="operationFilter"
            aria-label="Filter cache operations"
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
        <h3 class="h5 mb-2">Cached operations</h3>
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

.cache-metrics-group + .cache-metrics-group {
  border-top: 1px solid var(--bs-border-color);
  padding-top: 0.35rem;
}

.cache-tier-toggle {
  white-space: nowrap;
}

.cache-tier-row > td {
  background-color: var(--bs-tertiary-bg);
}
</style>
