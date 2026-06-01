<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {formatNumber} from '../utils/format.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'

const report = ref(null)
const loading = ref(true)
const error = ref(null)
const selectedName = ref('')
const history = ref([])
const lastUpdated = ref(null)
let timer = null

const SERIES = [
  {key: 'active', label: 'Active', color: '#dc3545'},
  {key: 'idle', label: 'Idle', color: '#198754'},
  {key: 'total', label: 'Total', color: '#0d6efd'},
  {key: 'pending', label: 'Pending', color: '#fd7e14'}
]

const pools = computed(() => report.value?.pools ?? [])

const selectedPool = computed(() => pools.value.find((pool) => pool.poolName === selectedName.value) || null)

const chartMax = computed(() => {
  let max = 1
  for (const point of history.value) {
    for (const series of SERIES) {
      max = Math.max(max, point[series.key] ?? 0)
    }
  }
  return max
})

function chartPath(key) {
  const points = history.value
  if (points.length < 2) return ''
  const max = chartMax.value
  return points
    .map((point, index) => {
      const x = (index / (points.length - 1)) * 100
      const y = 44 - ((point[key] ?? 0) / max) * 40
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`
    })
    .join(' ')
}

function poolKey(pool) {
  return pool.poolName || pool.beanName
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await fetch('api/hikari/pools')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastUpdated.value = new Date()
    if (!selectedPool.value && pools.value.length) {
      selectPool(poolKey(pools.value[0]))
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function selectPool(name) {
  if (selectedName.value === name) return
  selectedName.value = name
  history.value = []
  pollSnapshot()
}

async function pollSnapshot() {
  const pool = selectedPool.value
  if (!pool || !pool.available || !pool.poolName) return
  try {
    const res = await fetch(`api/hikari/pools/${encodeURIComponent(pool.poolName)}/snapshot`)
    if (!res.ok) return
    const snapshot = await res.json()
    history.value = [
      ...history.value,
      {
        active: snapshot.active,
        idle: snapshot.idle,
        total: snapshot.total,
        pending: snapshot.pending
      }
    ].slice(-60)
    lastUpdated.value = new Date()
  } catch {
    // Transient polling failures are ignored; the next tick retries.
  }
}

function scheduleNextPoll() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    if (document.visibilityState !== 'hidden') {
      await pollSnapshot()
    }
    scheduleNextPoll()
  }, 2000)
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

function formatMillis(value) {
  if (value === null || value === undefined || value < 0) return '—'
  if (value === 0) return 'disabled'
  if (value >= 1000) return `${(value / 1000).toLocaleString(undefined, {maximumFractionDigits: 1})} s`
  return `${value} ms`
}

function shortName(name) {
  if (!name) return '—'
  const i = name.lastIndexOf('.')
  return i < 0 ? name : name.substring(i + 1)
}

const currentSnapshot = computed(() => history.value[history.value.length - 1] || null)

onMounted(async () => {
  await load()
  scheduleNextPoll()
})

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})

const {interval, intervalOptions} = useAutoRefresh(load, [0, 10, 30, 60], 0)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-hdd-network"
      title="Connection Pools"
      :subtitle="report ? `${pools.length} HikariCP pool${pools.length === 1 ? '' : 's'} · read-only` : null"
      :loading="loading"
      :error="error"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      @refresh="load"
    >
      <template #actions>
        <select v-model.number="interval" class="form-select form-select-sm auto-refresh-select" title="Auto-refresh">
          <option v-for="s in intervalOptions" :key="s" :value="s">
            {{ s === 0 ? 'Manual' : `${s}s` }}
          </option>
        </select>
      </template>
    </PanelHeader>

    <PanelSkeleton v-if="loading && !report" />

    <template v-else-if="report">
      <div v-if="!pools.length" class="alert alert-secondary">
        No <code>HikariDataSource</code> beans were detected. Configure a HikariCP-backed datasource to inspect pool
        saturation here.
      </div>

      <div v-else class="row g-3">
        <div class="col-lg-4">
          <div class="card h-100">
            <div class="card-header fw-semibold">Pools</div>
            <div class="list-group list-group-flush pool-list">
              <button
                v-for="pool in pools"
                :key="poolKey(pool)"
                :class="{active: poolKey(pool) === selectedName}"
                class="list-group-item list-group-item-action"
                type="button"
                @click="selectPool(poolKey(pool))"
              >
                <div class="d-flex justify-content-between align-items-start gap-2">
                  <span class="fw-semibold">{{ pool.poolName || pool.beanName }}</span>
                  <span v-if="pool.available" class="badge text-bg-success">live</span>
                  <span v-else class="badge text-bg-secondary">unavailable</span>
                </div>
                <div class="small text-muted">{{ shortName(pool.driverClassName) }}</div>
              </button>
            </div>
          </div>
        </div>

        <div class="col-lg-8">
          <div v-if="selectedPool" class="card mb-3">
            <div class="card-header d-flex flex-wrap justify-content-between align-items-start gap-2">
              <div>
                <code class="fs-6">{{ selectedPool.poolName || selectedPool.beanName }}</code>
                <div class="text-muted small">
                  bean: <code>{{ selectedPool.beanName }}</code>
                </div>
              </div>
              <span v-if="selectedPool.readOnly" class="badge text-bg-info">read-only datasource</span>
            </div>
            <div class="card-body">
              <div v-if="!selectedPool.available" class="alert alert-warning small mb-3">
                <i class="bi bi-exclamation-triangle me-1"></i>
                This pool is not reporting live metrics: {{ selectedPool.unavailableReason }}
              </div>

              <div v-else class="row g-3 align-items-stretch mb-3">
                <div class="col-md-4">
                  <div class="d-flex flex-wrap gap-2">
                    <span
                      v-for="series in SERIES"
                      :key="series.key"
                      class="badge"
                      :style="{backgroundColor: series.color}"
                    >
                      {{ series.label }}: {{ formatNumber(currentSnapshot?.[series.key]) }}
                    </span>
                  </div>
                  <div class="small text-muted mt-2">Sampled every 2 seconds (last 60 points).</div>
                </div>
                <div class="col-md-8">
                  <div class="chart-box">
                    <svg
                      aria-label="Live connection pool graph"
                      preserveAspectRatio="none"
                      role="img"
                      viewBox="0 0 100 48"
                    >
                      <line class="chart-axis" x1="0" x2="100" y1="44" y2="44" />
                      <path
                        v-for="series in SERIES"
                        :key="series.key"
                        :d="chartPath(series.key)"
                        :stroke="series.color"
                        class="chart-line"
                        fill="none"
                      />
                    </svg>
                    <div v-if="history.length < 2" class="chart-empty text-muted small">
                      Waiting for another sample…
                    </div>
                  </div>
                </div>
              </div>

              <div class="table-responsive">
                <table class="table table-sm align-middle mb-0">
                  <tbody>
                    <tr>
                      <th class="text-muted" style="width: 14rem">JDBC URL</th>
                      <td>
                        <code>{{ selectedPool.jdbcUrl || '—' }}</code>
                      </td>
                    </tr>
                    <tr>
                      <th class="text-muted">Username</th>
                      <td>
                        <code>{{ selectedPool.username || '—' }}</code>
                      </td>
                    </tr>
                    <tr>
                      <th class="text-muted">Driver</th>
                      <td>
                        <code>{{ selectedPool.driverClassName || '—' }}</code>
                      </td>
                    </tr>
                    <tr>
                      <th class="text-muted">Sizing</th>
                      <td>min idle {{ selectedPool.minimumIdle }} · max pool {{ selectedPool.maximumPoolSize }}</td>
                    </tr>
                    <tr>
                      <th class="text-muted">Timeouts</th>
                      <td>
                        connection {{ formatMillis(selectedPool.connectionTimeoutMs) }} · validation
                        {{ formatMillis(selectedPool.validationTimeoutMs) }}
                      </td>
                    </tr>
                    <tr>
                      <th class="text-muted">Lifetime</th>
                      <td>
                        idle {{ formatMillis(selectedPool.idleTimeoutMs) }} · max lifetime
                        {{ formatMillis(selectedPool.maxLifetimeMs) }} · keepalive
                        {{ formatMillis(selectedPool.keepaliveTimeMs) }}
                      </td>
                    </tr>
                    <tr>
                      <th class="text-muted">Flags</th>
                      <td>
                        read-only {{ selectedPool.readOnly ? 'yes' : 'no' }} · auto-commit
                        {{ selectedPool.autoCommit ? 'yes' : 'no' }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div v-else class="text-muted">Select a pool to inspect live connection usage.</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pool-list {
  max-height: 44rem;
  overflow: auto;
}

.chart-box {
  background: linear-gradient(180deg, rgba(13, 110, 253, 0.08), rgba(25, 135, 84, 0.06));
  border: 1px solid rgba(13, 110, 253, 0.12);
  border-radius: 1rem;
  min-height: 12rem;
  padding: 1rem;
  position: relative;
}

.chart-box svg {
  height: 10rem;
  width: 100%;
}

.chart-axis {
  stroke: rgba(100, 116, 139, 0.35);
  stroke-width: 0.5;
}

.chart-line {
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.5;
}

.chart-empty {
  left: 1rem;
  position: absolute;
  top: 1rem;
}
</style>
