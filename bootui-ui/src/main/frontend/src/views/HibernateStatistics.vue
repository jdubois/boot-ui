<script setup>
import {computed, ref} from 'vue'
import {ApiError, getJson} from '../api.js'
import {
  diagnosticActionError,
  getDiagnosticAcknowledgement,
  isHibernateStatisticsReport
} from '../utils/diagnosticAcknowledgement.js'
import {formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const enabling = ref(false)
let reportVersion = 0
const {message: banner, flash, clear} = useFlashMessage(8000)

async function fetchStatistics() {
  if (!manifestAvailable.value) return
  const version = reportVersion
  error.value = null
  try {
    const loaded = await getJson('api/hibernate-statistics')
    if (version !== reportVersion) return
    if (!isHibernateStatisticsReport(loaded)) throw new Error('Invalid Hibernate statistics report')
    report.value = loaded
    lastFetched.value = Date.now()
  } catch (e) {
    if (version !== reportVersion) return
    error.value = describeLoadError(e, 'Unable to load Hibernate session statistics')
  }
}

const {autoRefresh, loading, initialLoading, load, loadAfterCurrent} = useAutoRefresh(fetchStatistics, {
  enabled: manifestAvailable,
  initialLoading: false
})

const statistics = computed(() => report.value?.statistics ?? null)
const available = computed(() => manifestAvailable.value && report.value?.available !== false)
const enableAvailable = computed(() => manifestAvailable.value && report.value?.enableAvailable === true)
const summaryMetrics = computed(() => {
  if (!statistics.value) return []
  return [
    {
      icon: 'bi-door-open',
      label: 'Sessions opened',
      value: formatNumber(statistics.value.sessionOpenCount)
    },
    {
      icon: 'bi-check2-circle',
      label: 'Successful transactions',
      value: `${formatNumber(statistics.value.successfulTransactionCount)} / ${formatNumber(statistics.value.transactionCount)}`
    },
    {
      icon: 'bi-terminal',
      label: 'Queries executed',
      value: formatNumber(statistics.value.queryExecutionCount)
    },
    {
      icon: 'bi-stopwatch',
      label: 'Slowest query',
      value: `${formatNumber(statistics.value.queryExecutionMaxTime)} ms`
    }
  ]
})
const statisticSections = [
  {
    title: 'Session lifecycle',
    icon: 'bi-arrow-repeat',
    description: 'SessionFactory usage and transaction outcomes.',
    metrics: [
      {label: 'Sessions opened', key: 'sessionOpenCount'},
      {label: 'Sessions closed', key: 'sessionCloseCount'},
      {label: 'Flushes', key: 'flushCount'},
      {label: 'Connections acquired', key: 'connectCount'},
      {label: 'Transactions', key: 'transactionCount'},
      {label: 'Successful transactions', key: 'successfulTransactionCount'}
    ]
  },
  {
    title: 'Entity activity',
    icon: 'bi-box',
    description: 'Persistence operations recorded across mapped entities.',
    metrics: [
      {label: 'Loaded', key: 'entityLoadCount'},
      {label: 'Fetched', key: 'entityFetchCount'},
      {label: 'Inserted', key: 'entityInsertCount'},
      {label: 'Updated', key: 'entityUpdateCount'},
      {label: 'Deleted', key: 'entityDeleteCount'}
    ]
  },
  {
    title: 'Collection activity',
    icon: 'bi-collection',
    description: 'Lifecycle operations for persistent collections.',
    metrics: [
      {label: 'Loaded', key: 'collectionLoadCount'},
      {label: 'Fetched', key: 'collectionFetchCount'},
      {label: 'Recreated', key: 'collectionRecreateCount'},
      {label: 'Updated', key: 'collectionUpdateCount'},
      {label: 'Removed', key: 'collectionRemoveCount'}
    ]
  }
]
const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'Hibernate session statistics are unavailable.'
})

async function enableStatistics() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (enabling.value) return
  enabling.value = true
  try {
    const result = await getDiagnosticAcknowledgement(
      'api/hibernate-statistics/enable',
      {method: 'POST'},
      isHibernateStatisticsReport
    )
    reportVersion++
    report.value = result
    error.value = null
    lastFetched.value = Date.now()
    flash(
      result.available
        ? 'Hibernate statistics enabled for this runtime. Counters start collecting now.'
        : result.unavailableReason || 'Hibernate statistics remain unavailable; activation had no effect.',
      result.available ? 'success' : 'warning'
    )
    if (loading.value) await loadAfterCurrent()
  } catch (e) {
    flash(diagnosticActionError(e, 'Could not enable Hibernate statistics'), 'danger')
    if (!(e instanceof ApiError)) {
      reportVersion++
      await loadAfterCurrent()
    }
  } finally {
    enabling.value = false
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-graph-up"
      title="Hibernate Statistics"
      subtitle="Live counters from Hibernate's Statistics API for the application's SessionFactory."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      :auto-refreshable="manifestAvailable"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <FlashBanner :message="banner" with-icon @dismiss="clear" />

    <ReadOnlyNotice v-if="readOnly && enableAvailable" :reason="readOnlyReason">
      Runtime activation is disabled.
    </ReadOnlyNotice>

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />

    <template v-else-if="!manifestAvailable || report">
      <section v-if="!available" class="card unavailable-state" role="status" aria-live="polite">
        <div class="card-body">
          <div class="unavailable-state__icon" aria-hidden="true">
            <i class="bi bi-graph-up"></i>
          </div>
          <div class="unavailable-state__content">
            <h2 class="h5 mb-2">Session statistics are unavailable</h2>
            <p class="text-muted mb-0">{{ unavailableReason }}</p>
            <div v-if="enableAvailable" class="unavailable-state__action">
              <SpinnerButton
                id="enable-hibernate-statistics"
                :loading="enabling"
                :disabled="readOnly || enabling"
                class="btn btn-primary"
                icon="bi-play-circle"
                label="Enable for this runtime"
                loading-label="Enabling…"
                @click="enableStatistics"
              />
              <span class="small text-muted">
                Collection starts now and lasts until this application stops. Configuration files are not changed.
              </span>
            </div>
            <p class="small text-muted mb-0 mt-3">
              To collect from startup, set <code>hibernate.generate_statistics=true</code> (Spring) or
              <code>quarkus.hibernate-orm.statistics=true</code> (Quarkus).
            </p>
          </div>
        </div>
      </section>

      <template v-else>
        <section class="card runtime-overview mb-4" aria-labelledby="runtime-overview-title">
          <div class="card-body">
            <div class="runtime-overview__heading">
              <div>
                <h2 id="runtime-overview-title" class="h5 mb-1">Runtime overview</h2>
                <p class="text-muted small mb-0">Cumulative counters since statistics collection began.</p>
              </div>
              <span class="badge text-bg-success">
                <i class="bi bi-broadcast-pin me-1" aria-hidden="true"></i>Collecting
              </span>
            </div>
            <dl class="runtime-overview__metrics mb-0">
              <div v-for="metric in summaryMetrics" :key="metric.label" class="runtime-metric">
                <dt>
                  <i :class="['bi', metric.icon]" aria-hidden="true"></i>
                  <span>{{ metric.label }}</span>
                </dt>
                <dd>{{ metric.value }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <div class="row g-3">
          <div v-for="section in statisticSections" :key="section.title" class="col-lg-6">
            <section class="card statistic-section h-100">
              <header class="statistic-section__header">
                <i :class="['bi', section.icon]" aria-hidden="true"></i>
                <div>
                  <h2 class="h5 mb-1">{{ section.title }}</h2>
                  <p class="text-muted small mb-0">{{ section.description }}</p>
                </div>
              </header>
              <dl class="statistic-list mb-0">
                <div v-for="metric in section.metrics" :key="metric.key" class="statistic-row">
                  <dt>{{ metric.label }}</dt>
                  <dd>{{ formatNumber(statistics[metric.key]) }}</dd>
                </div>
              </dl>
            </section>
          </div>

          <div class="col-lg-6">
            <section class="card statistic-section h-100">
              <header class="statistic-section__header">
                <i class="bi bi-terminal" aria-hidden="true"></i>
                <div class="flex-grow-1">
                  <h2 class="h5 mb-1">Query activity</h2>
                  <p class="text-muted small mb-0">Execution volume, peak latency, and query-cache traffic.</p>
                </div>
                <span class="badge" :class="statistics.queryCacheEnabled ? 'text-bg-success' : 'text-bg-secondary'">
                  Query cache {{ statistics.queryCacheEnabled ? 'active' : 'not in use' }}
                </span>
              </header>
              <dl class="statistic-list mb-0">
                <div class="statistic-row">
                  <dt>Executions</dt>
                  <dd>{{ formatNumber(statistics.queryExecutionCount) }}</dd>
                </div>
                <div class="statistic-row">
                  <dt>Slowest execution</dt>
                  <dd>{{ formatNumber(statistics.queryExecutionMaxTime) }} ms</dd>
                </div>
                <template v-if="statistics.queryCacheEnabled">
                  <div class="statistic-row">
                    <dt>Query cache hits</dt>
                    <dd>{{ formatNumber(statistics.queryCacheHitCount) }}</dd>
                  </div>
                  <div class="statistic-row">
                    <dt>Query cache misses</dt>
                    <dd>{{ formatNumber(statistics.queryCacheMissCount) }}</dd>
                  </div>
                  <div class="statistic-row">
                    <dt>Query cache puts</dt>
                    <dd>{{ formatNumber(statistics.queryCachePutCount) }}</dd>
                  </div>
                </template>
              </dl>
              <div v-if="statistics.queryExecutionMaxTimeQueryString" class="slow-query">
                <div class="small fw-semibold mb-2">Slowest query observed</div>
                <code>{{ statistics.queryExecutionMaxTimeQueryString }}</code>
              </div>
              <div v-if="!statistics.queryCacheEnabled" class="statistic-empty">
                <i class="bi bi-info-circle" aria-hidden="true"></i>
                <span>No query has been marked cacheable yet, or the query cache is disabled.</span>
              </div>
            </section>
          </div>

          <div class="col-12">
            <section class="card statistic-section">
              <header class="statistic-section__header">
                <i class="bi bi-layers" aria-hidden="true"></i>
                <div class="flex-grow-1">
                  <h2 class="h5 mb-1">Second-level cache</h2>
                  <p class="text-muted small mb-0">Shared entity and collection cache activity by region.</p>
                </div>
                <span
                  class="badge"
                  :class="statistics.secondLevelCacheEnabled ? 'text-bg-success' : 'text-bg-secondary'"
                >
                  {{ statistics.secondLevelCacheEnabled ? 'Active' : 'No activity' }}
                </span>
              </header>
              <div v-if="!statistics.secondLevelCacheEnabled" class="statistic-empty statistic-empty--roomy">
                <i class="bi bi-layers" aria-hidden="true"></i>
                <span>No cache region has recorded activity. It may not be configured or has not been used yet.</span>
              </div>
              <template v-else>
                <dl class="cache-totals mb-0">
                  <div>
                    <dt>Hits</dt>
                    <dd>{{ formatNumber(statistics.secondLevelCacheHitCount) }}</dd>
                  </div>
                  <div>
                    <dt>Misses</dt>
                    <dd>{{ formatNumber(statistics.secondLevelCacheMissCount) }}</dd>
                  </div>
                  <div>
                    <dt>Puts</dt>
                    <dd>{{ formatNumber(statistics.secondLevelCachePutCount) }}</dd>
                  </div>
                </dl>
                <div v-if="statistics.secondLevelCacheRegions.length" class="table-responsive">
                  <table class="table table-sm table-hover align-middle mb-0 cache-regions">
                    <caption class="visually-hidden">
                      Second-level cache activity by region
                    </caption>
                    <thead>
                      <tr>
                        <th>Region</th>
                        <th class="text-end">Hits</th>
                        <th class="text-end">Misses</th>
                        <th class="text-end">Puts</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="region in statistics.secondLevelCacheRegions" :key="region.regionName">
                        <td class="font-monospace text-break">{{ region.regionName }}</td>
                        <td class="text-end font-monospace">{{ formatNumber(region.hitCount) }}</td>
                        <td class="text-end font-monospace">{{ formatNumber(region.missCount) }}</td>
                        <td class="text-end font-monospace">{{ formatNumber(region.putCount) }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>
            </section>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.unavailable-state .card-body {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 1.5rem;
}

.unavailable-state__icon {
  display: grid;
  flex: 0 0 2.75rem;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  border-radius: var(--bootui-radius-md);
  background: rgba(var(--bs-secondary-rgb), 0.12);
  color: var(--bootui-text-muted);
  font-size: 1.15rem;
}

.unavailable-state__content {
  max-width: 72ch;
}

.unavailable-state__action {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-top: 1.25rem;
}

.runtime-overview .card-body {
  padding: 1.25rem;
}

.runtime-overview__heading,
.statistic-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.runtime-overview__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 1.25rem;
  border-top: 1px solid var(--bootui-border);
}

.runtime-metric {
  min-width: 0;
  padding: 1.1rem 1.25rem 0 0;
}

.runtime-metric + .runtime-metric {
  padding-left: 1.25rem;
  border-left: 1px solid var(--bootui-border);
}

.runtime-metric dt {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  font-weight: 500;
}

.runtime-metric dt i {
  color: var(--bootui-blue);
}

.runtime-metric dd {
  margin: 0.35rem 0 0;
  color: var(--bootui-text);
  font-family: var(--bs-font-monospace);
  font-size: clamp(1.15rem, 2vw, 2.1rem);
  font-weight: 700;
  letter-spacing: -0.03em;
}

.statistic-section {
  overflow: hidden;
}

.statistic-section__header {
  min-height: 5.65rem;
  padding: 1.2rem 1.25rem 1rem;
  border-bottom: 1px solid var(--bootui-border);
}

.statistic-section__header > i {
  flex: 0 0 auto;
  margin-top: 0.1rem;
  color: var(--bootui-blue);
  font-size: 1.15rem;
}

.statistic-list {
  display: block;
}

.statistic-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.72rem 1.25rem;
}

.statistic-row + .statistic-row {
  border-top: 1px solid var(--bootui-border);
}

.statistic-row dt {
  font-weight: 400;
}

.statistic-row dd {
  flex: 0 0 auto;
  margin: 0;
  font-family: var(--bs-font-monospace);
  font-weight: 600;
}

.slow-query {
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--bootui-border);
  background: var(--bs-tertiary-bg);
}

.slow-query code {
  display: block;
  color: var(--bootui-text);
  white-space: normal;
  overflow-wrap: anywhere;
}

.statistic-empty {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--bootui-border);
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
}

.statistic-empty i {
  color: var(--bootui-info-text);
}

.statistic-empty--roomy {
  padding-block: 1.5rem;
}

.cache-totals {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-bottom: 1px solid var(--bootui-border);
}

.cache-totals > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 1.25rem;
}

.cache-totals > div + div {
  border-left: 1px solid var(--bootui-border);
}

.cache-totals dt {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
  font-weight: 500;
}

.cache-totals dd {
  margin: 0;
  font-family: var(--bs-font-monospace);
  font-size: 1.15rem;
  font-weight: 700;
}

.cache-regions th {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  font-weight: 600;
}

@media (max-width: 767.98px) {
  .runtime-overview__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .runtime-metric:nth-child(3) {
    padding-left: 0;
    border-left: 0;
  }

  .runtime-metric:nth-child(n + 3) {
    border-top: 1px solid var(--bootui-border);
  }

  .unavailable-state__action {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 575.98px) {
  .unavailable-state .card-body {
    padding: 1.25rem;
  }

  .runtime-overview__heading,
  .statistic-section__header {
    flex-wrap: wrap;
  }

  .runtime-overview__metrics,
  .cache-totals {
    grid-template-columns: 1fr;
  }

  .runtime-metric,
  .runtime-metric + .runtime-metric,
  .runtime-metric:nth-child(3) {
    padding: 0.85rem 0;
    border-top: 1px solid var(--bootui-border);
    border-left: 0;
  }

  .runtime-metric:first-child {
    border-top: 0;
  }

  .cache-totals > div + div {
    border-top: 1px solid var(--bootui-border);
    border-left: 0;
  }
}
</style>
