<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const data = ref(null)
const error = ref(null)
const lastUpdated = ref(null)
const filter = ref('')
const dataSourceFilter = ref('')
const showErrorsOnly = ref(false)
const autoRefresh = ref(true)
let timer = null

async function load() {
  try {
    const res = await fetch('api/database')
    if (res.status === 404) {
      error.value = 'No DataSource is present in this application.'
      return
    }
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    lastUpdated.value = new Date()
    error.value = null
  } catch (e) {
    error.value = e.message
  }
}

function startAutoRefresh() {
  if (timer) return
  timer = setInterval(load, 3000)
}

function stopAutoRefresh() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) startAutoRefresh()
  else stopAutoRefresh()
}

const dataSources = computed(() => {
  if (!data.value) return []
  return data.value.dataSources || []
})

const dataSourceOptions = computed(() => {
  const set = new Set(dataSources.value.map(d => d.beanName))
  return Array.from(set).sort()
})

const filteredSql = computed(() => {
  if (!data.value) return []
  const f = filter.value.trim().toLowerCase()
  return (data.value.recentSql || []).filter(r => {
    if (showErrorsOnly.value && r.success) return false
    if (dataSourceFilter.value && r.dataSource !== dataSourceFilter.value) return false
    if (!f) return true
    return (r.sql || '').toLowerCase().includes(f)
  })
})

function shortClass(name) {
  if (!name) return ''
  const i = name.lastIndexOf('.')
  return i < 0 ? name : name.substring(i + 1)
}

function fmtTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleTimeString([], { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0')
}

function fmtMicros(micros) {
  if (micros == null) return ''
  if (micros < 1000) return micros + ' µs'
  if (micros < 1000000) return (micros / 1000).toFixed(1) + ' ms'
  return (micros / 1000000).toFixed(2) + ' s'
}

function durationClass(micros) {
  if (micros >= 500000) return 'text-danger fw-semibold'
  if (micros >= 50000) return 'text-warning fw-semibold'
  return 'text-muted'
}

function poolUsagePercent(pool) {
  const active = pool.activeConnections || 0
  const max = pool.maximumPoolSize || 0
  if (!max) return 0
  return Math.min(100, Math.round((active * 100) / max))
}

function progressClass(pct) {
  if (pct >= 90) return 'bg-danger'
  if (pct >= 70) return 'bg-warning'
  return 'bg-success'
}

function statementBadge(type) {
  switch (type) {
    case 'PREPARED': return 'bg-primary'
    case 'CALLABLE': return 'bg-info text-dark'
    case 'STATEMENT': return 'bg-secondary'
    default: return 'bg-secondary'
  }
}

onMounted(() => {
  load()
  if (autoRefresh.value) startAutoRefresh()
})

onBeforeUnmount(stopAutoRefresh)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h2 class="mb-0"><i class="bi bi-hdd-stack me-2"></i>Database</h2>
      <div class="d-flex align-items-center gap-2">
        <span class="text-muted small" v-if="lastUpdated">
          <i class="bi bi-arrow-repeat me-1"></i>Updated {{ lastUpdated.toLocaleTimeString([], { hour12: false }) }}
        </span>
        <button class="btn btn-sm btn-outline-secondary" @click="toggleAutoRefresh">
          <i :class="['bi', autoRefresh ? 'bi-pause-circle' : 'bi-play-circle', 'me-1']"></i>
          {{ autoRefresh ? 'Pause' : 'Resume' }}
        </button>
        <button class="btn btn-sm btn-outline-secondary" @click="load">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
      </div>
    </div>

    <div v-if="error" class="alert alert-warning">{{ error }}</div>

    <template v-if="data && data.dataSourcePresent">
      <!-- DataSources -->
      <div class="card mb-3">
        <div class="card-header"><i class="bi bi-server me-2"></i>Data sources <span class="badge bg-secondary ms-2">{{ dataSources.length }}</span></div>
        <div class="table-responsive">
          <table class="table table-sm table-hover mb-0">
            <thead class="table-light">
              <tr>
                <th>Bean</th>
                <th>Type</th>
                <th>JDBC URL</th>
                <th>Driver</th>
                <th>User</th>
                <th>Catalog / Schema</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="ds in dataSources" :key="ds.beanName">
                <td><code>{{ ds.beanName }}</code></td>
                <td><span class="badge text-bg-light border">{{ shortClass(ds.dataSourceClass) }}</span></td>
                <td><code class="small">{{ ds.jdbcUrl || '—' }}</code></td>
                <td class="small">{{ shortClass(ds.driverClassName) || '—' }}</td>
                <td class="small">{{ ds.username || '—' }}</td>
                <td class="small">
                  <span v-if="ds.catalog || ds.schema">
                    <span v-if="ds.catalog">{{ ds.catalog }}</span>
                    <span v-if="ds.catalog && ds.schema"> / </span>
                    <span v-if="ds.schema">{{ ds.schema }}</span>
                  </span>
                  <span v-else class="text-muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Connection pools -->
      <div class="row g-3 mb-3" v-if="data.pools && data.pools.length">
        <div class="col-md-6" v-for="pool in data.pools" :key="pool.beanName">
          <div class="card h-100">
            <div class="card-header d-flex justify-content-between align-items-center">
              <span><i class="bi bi-diagram-2 me-2"></i><strong>{{ pool.poolName || pool.beanName }}</strong></span>
              <span class="badge text-bg-primary">{{ pool.poolType }}</span>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between small mb-1">
                <span class="text-muted">Active / max</span>
                <span class="fw-semibold">{{ pool.activeConnections ?? '—' }} / {{ pool.maximumPoolSize ?? '—' }}</span>
              </div>
              <div class="progress mb-3" style="height: 10px;">
                <div class="progress-bar" :class="progressClass(poolUsagePercent(pool))"
                     :style="{ width: poolUsagePercent(pool) + '%' }"
                     role="progressbar"
                     :aria-valuenow="poolUsagePercent(pool)"
                     aria-valuemin="0" aria-valuemax="100"></div>
              </div>
              <div class="row text-center g-2">
                <div class="col-3">
                  <div class="text-muted small">Active</div>
                  <div class="fw-semibold">{{ pool.activeConnections ?? '—' }}</div>
                </div>
                <div class="col-3">
                  <div class="text-muted small">Idle</div>
                  <div class="fw-semibold">{{ pool.idleConnections ?? '—' }}</div>
                </div>
                <div class="col-3">
                  <div class="text-muted small">Total</div>
                  <div class="fw-semibold">{{ pool.totalConnections ?? '—' }}</div>
                </div>
                <div class="col-3">
                  <div class="text-muted small">Waiting</div>
                  <div class="fw-semibold" :class="{ 'text-danger': (pool.threadsAwaitingConnection || 0) > 0 }">
                    {{ pool.threadsAwaitingConnection ?? '—' }}
                  </div>
                </div>
              </div>
              <dl class="row mt-3 mb-0 small">
                <dt class="col-6 text-muted">Min idle</dt>
                <dd class="col-6 mb-0">{{ pool.minimumIdle ?? '—' }}</dd>
                <dt class="col-6 text-muted">Connection timeout</dt>
                <dd class="col-6 mb-0">{{ pool.connectionTimeoutMs != null ? pool.connectionTimeoutMs + ' ms' : '—' }}</dd>
                <dt class="col-6 text-muted">Idle timeout</dt>
                <dd class="col-6 mb-0">{{ pool.idleTimeoutMs != null ? pool.idleTimeoutMs + ' ms' : '—' }}</dd>
                <dt class="col-6 text-muted">Max lifetime</dt>
                <dd class="col-6 mb-0">{{ pool.maxLifetimeMs != null ? pool.maxLifetimeMs + ' ms' : '—' }}</dd>
              </dl>
            </div>
          </div>
        </div>
      </div>
      <div class="alert alert-info small" v-else>
        No connection-pool metrics are available for the registered data sources.
        Connection-pool stats are currently surfaced for HikariCP (the Spring Boot default),
        Tomcat JDBC and DBCP2.
      </div>

      <!-- SQL requests -->
      <div class="card">
        <div class="card-header d-flex flex-wrap gap-2 align-items-center">
          <span><i class="bi bi-list-columns-reverse me-2"></i>Recent SQL requests</span>
          <span class="badge bg-secondary">{{ filteredSql.length }} / {{ data.recordedSqlRequests }} shown</span>
          <span class="text-muted small">buffer: {{ data.maxSqlRequests }}</span>
          <div class="ms-auto d-flex flex-wrap gap-2 align-items-center">
            <input class="form-control form-control-sm" style="width: 260px;"
                   v-model="filter" placeholder="Filter by SQL fragment…" />
            <select class="form-select form-select-sm" style="width: auto;" v-model="dataSourceFilter">
              <option value="">All data sources</option>
              <option v-for="o in dataSourceOptions" :key="o" :value="o">{{ o }}</option>
            </select>
            <div class="form-check form-switch m-0">
              <input class="form-check-input" type="checkbox" id="errorsOnly" v-model="showErrorsOnly" />
              <label class="form-check-label small" for="errorsOnly">Errors only</label>
            </div>
          </div>
        </div>
        <div class="table-responsive">
          <table class="table table-sm table-hover mb-0">
            <thead class="table-light">
              <tr>
                <th style="width: 110px;">Time</th>
                <th style="width: 110px;">Data source</th>
                <th style="width: 100px;">Statement</th>
                <th>SQL</th>
                <th class="text-end" style="width: 110px;">Duration</th>
                <th class="text-end" style="width: 80px;">Rows</th>
                <th style="width: 80px;">Status</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="filteredSql.length === 0">
                <td colspan="7" class="text-center text-muted py-4">
                  <i class="bi bi-inboxes me-1"></i>No SQL requests captured yet.
                </td>
              </tr>
              <tr v-for="(r, idx) in filteredSql" :key="idx" :class="{ 'table-danger': !r.success }">
                <td class="small text-muted">{{ fmtTime(r.timestamp) }}</td>
                <td class="small"><code>{{ r.dataSource }}</code></td>
                <td><span class="badge" :class="statementBadge(r.statementType)">{{ r.statementType }}</span></td>
                <td>
                  <code class="small sql-cell">{{ r.sql || '(unknown)' }}</code>
                  <div v-if="r.error" class="small text-danger mt-1">{{ r.error }}</div>
                </td>
                <td class="text-end small" :class="durationClass(r.durationMicros)">{{ fmtMicros(r.durationMicros) }}</td>
                <td class="text-end small">{{ r.affectedRows ?? '' }}</td>
                <td>
                  <span v-if="r.success" class="badge bg-success">OK</span>
                  <span v-else class="badge bg-danger">FAIL</span>
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

<style scoped>
.sql-cell {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
