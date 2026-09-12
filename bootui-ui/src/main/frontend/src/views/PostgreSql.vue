<script setup>
import {actionBusyMessage, getJson, isActionBusyError} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {formatBytes, formatClockTime, formatNumber, formatRelative} from '../utils/format.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const initialLoading = ref(true)
const showDiagnostics = ref(false)

const DATABASE_STATUS_CLASSES = {
  READ: 'text-bg-success',
  PARTIAL: 'text-bg-warning',
  ERROR: 'text-bg-danger',
  DISABLED: 'text-bg-secondary'
}

const DATABASE_STATUS_LABELS = {
  READ: 'Read',
  PARTIAL: 'Partly read',
  ERROR: 'Unreadable',
  DISABLED: 'Disabled'
}

const SECTION_STATUS_CLASSES = {
  AVAILABLE: 'text-bg-success',
  PARTIAL: 'text-bg-warning',
  SKIPPED: 'text-bg-secondary',
  FAILED: 'text-bg-danger'
}

const DIAGNOSTIC_CLASSES = {
  ERROR: 'text-bg-danger',
  WARNING: 'text-bg-warning',
  INFO: 'text-bg-secondary'
}

const CHANGE_DIRECTION_ICONS = {
  UP: 'bi-arrow-up-right',
  DOWN: 'bi-arrow-down-right',
  SAME: 'bi-dash'
}

// A session is worth the reader's attention when it is doing something now: running a statement,
// waiting on a lock, or holding a transaction open while idle. Everything else is an idle pool
// connection, which is normal and noisy.
const BUSY_STATES = ['active', 'idle in transaction', 'idle in transaction (aborted)']

// The read has actually produced a database read once the status leaves NOT_READ, so an
// empty prompt is only shown before the first read rather than hiding a real (possibly
// disabled or errored) result.
const hasRead = computed(() => Boolean(report.value) && report.value.status !== 'NOT_READ')

const disabled = computed(() => report.value?.status === 'DISABLED')

const databases = computed(() => report.value?.databases || [])
const diagnostics = computed(() => report.value?.diagnostics || [])
const limitations = computed(() => report.value?.limitations || [])

const readFailed = computed(() => hasRead.value && report.value?.status === 'ERROR')

const incompleteReadMessage = computed(() => {
  if (!hasRead.value) return null
  const reasons = []
  const unreadable = databases.value.filter((database) => database.status === 'ERROR').length
  if (unreadable > 0) {
    reasons.push(`${unreadable} ${pluralize(unreadable, 'datasource')} could not be read`)
  }
  const partial = databases.value.filter((database) => database.status === 'PARTIAL').length
  if (partial > 0) {
    reasons.push(`${partial} ${pluralize(partial, 'datasource')} were read only partially`)
  }
  if (report.value?.truncated) {
    reasons.push('a read bound was reached, so some rows may be missing')
  }
  if (reasons.length === 0) {
    // A report can be incomplete without carrying a single database row — an exhausted read budget, or a
    // discovery failure, leaves nothing to count. Reporting only per-database reasons would render that as
    // a complete result.
    if (report.value?.status === 'ERROR' || report.value?.status === 'PARTIAL') {
      return report.value.message || 'The read did not complete; see the diagnostics below.'
    }
    return null
  }
  return `${reasons.join('; ')}. The tables below therefore do not cover everything.`
})

function databaseStatusClass(status) {
  return DATABASE_STATUS_CLASSES[status] || 'text-bg-secondary'
}

function databaseStatusLabel(status) {
  return DATABASE_STATUS_LABELS[status] || status
}

function sectionStatusClass(status) {
  return SECTION_STATUS_CLASSES[status] || 'text-bg-secondary'
}

function diagnosticClass(level) {
  return DIAGNOSTIC_CLASSES[level] || 'text-bg-light border text-dark'
}

function changeDirectionIcon(direction) {
  return CHANGE_DIRECTION_ICONS[direction] || 'bi-dash'
}

function pluralize(count, singular, plural = `${singular}s`) {
  return count === 1 ? singular : plural
}

function percent(ratio) {
  if (ratio == null) return '—'
  return `${(ratio * 100).toFixed(1)}%`
}

function seconds(value) {
  if (value == null) return '—'
  return `${Number(value).toFixed(1)} s`
}

function millis(value) {
  if (value == null) return '—'
  return `${Number(value).toFixed(value < 10 ? 2 : 0)} ms`
}

function text(value) {
  return value == null || value === '' ? '—' : value
}

function since(epochMillis) {
  if (epochMillis == null) return 'never'
  return formatRelative(epochMillis)
}

function readTime() {
  if (!report.value?.readAt) return ''
  return formatClockTime(report.value.readAt)
}

function section(database, id) {
  return (database.sections || []).find((candidate) => candidate.id === id) || null
}

// The engine marks a partially read section AVAILABLE with a reason, because the rows it did
// read are real. That reason is what stops the table from claiming more than it read, so it is
// shown as PARTIAL rather than green. A truncated section is partial for the same reason even
// when it carries no reason of its own: the rows past the bound were never read.
function sectionPartial(candidate) {
  return candidate.status === 'AVAILABLE' && (!!candidate.reason || !!candidate.truncated)
}

function sectionBadge(candidate) {
  return sectionPartial(candidate) ? 'PARTIAL' : candidate.status
}

function sectionNote(candidate) {
  if (!candidate) return null
  if (candidate.reason) return candidate.reason
  if (candidate.truncated) return 'A row bound was reached, so rows past it were not read.'
  return null
}

function sectionReadable(candidate) {
  return Boolean(candidate) && candidate.status === 'AVAILABLE'
}

function sessionBusy(session) {
  return BUSY_STATES.includes(session.state) || Boolean(session.blockedBy)
}

function sessionRowClass(session) {
  if (session.blockedBy) return 'table-warning'
  if (session.state === 'active') return 'table-primary'
  return ''
}

async function loadReport() {
  if (!manifestAvailable.value) return
  try {
    report.value = await getJson('api/postgresql')
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load PostgreSQL report')
  }
}

async function runRead() {
  if (readOnly.value) {
    showActionMessage(readOnlyReason.value)
    return
  }
  if (!manifestAvailable.value) {
    showActionMessage(manifestUnavailableReason.value)
    return
  }
  loading.value = true
  actionMessage.value = null
  try {
    report.value = await getJson('api/postgresql/read', {method: 'POST'})
    error.value = null
  } catch (e) {
    if (isActionBusyError(e)) {
      showActionMessage(actionBusyMessage(e))
      error.value = null
    } else {
      error.value = describeLoadError(e, 'Unable to run the PostgreSQL read')
    }
  } finally {
    loading.value = false
  }
}

function showActionMessage(message) {
  actionMessage.value = message
  setTimeout(() => {
    actionMessage.value = null
  }, 6000)
}

onMounted(async () => {
  if (!manifestAvailable.value) {
    initialLoading.value = false
    return
  }
  try {
    await loadReport()
  } finally {
    initialLoading.value = false
  }
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-database-fill-check"
      title="PostgreSQL"
      subtitle="Bounded, read-only runtime view of the application's own PostgreSQL database, read on demand from its pg_stat_* and pg_catalog views."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <SpinnerButton
          :loading="loading"
          :disabled="loading || readOnly"
          class="btn btn-primary"
          type="button"
          icon="bi-play-circle"
          label="Run PostgreSQL read"
          loading-label="Reading..."
          @click="runRead"
        />
      </template>
    </PanelHeader>

    <div v-if="actionMessage" class="alert alert-warning" role="status" aria-live="polite">
      {{ actionMessage }}
    </div>

    <PanelSkeleton v-if="initialLoading" />

    <UnavailableState v-else-if="!manifestAvailable" variant="info">
      {{ manifestUnavailableReason }}
    </UnavailableState>

    <UnavailableState v-else-if="disabled" variant="info" icon="bi-database-slash">
      {{ report.message || 'No PostgreSQL datasource was detected, so there is nothing to read.' }}
    </UnavailableState>

    <template v-else-if="report">
      <div class="alert alert-info">
        <strong>Read-only runtime view.</strong>
        {{ report.disclaimer }}
        <span v-if="readOnly">Reading is read-only. {{ readOnlyReason }}</span>
      </div>

      <div v-if="!hasRead" class="card">
        <div class="card-body text-center text-muted py-5">
          <i class="bi bi-database-fill-check fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">No PostgreSQL data yet</div>
          <div>Run the PostgreSQL read to see what the database currently reports about itself.</div>
        </div>
      </div>

      <template v-else>
        <div class="d-flex flex-wrap align-items-center gap-2 mb-3 small text-muted">
          <span
            v-if="report.message"
            class="badge"
            :class="
              report.status === 'READ'
                ? 'text-bg-success'
                : report.status === 'ERROR'
                  ? 'text-bg-danger'
                  : 'text-bg-warning'
            "
            >{{ report.message }}</span
          >
          <span v-if="readTime()">Read at {{ readTime() }}</span>
          <span>{{ report.databasesRead }} {{ pluralize(report.databasesRead, 'database') }} read</span>
        </div>

        <div
          v-if="incompleteReadMessage"
          :class="readFailed ? 'alert alert-danger' : 'alert alert-warning'"
          role="status"
        >
          <i class="bi bi-exclamation-triangle me-1"></i>
          <strong>{{ readFailed ? 'Read failed.' : 'Incomplete read.' }}</strong>
          {{ incompleteReadMessage }}
        </div>

        <details v-if="limitations.length > 0" class="alert alert-warning">
          <summary class="fw-semibold">What this read does not cover ({{ limitations.length }})</summary>
          <ul class="mb-0 mt-2 small">
            <li v-for="(limitation, index) in limitations" :key="index">{{ limitation }}</li>
          </ul>
        </details>

        <div v-for="database in databases" :key="database.name" class="card mb-3">
          <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
            <div class="d-flex flex-wrap align-items-center gap-2">
              <span :class="databaseStatusClass(database.status)" class="badge">{{
                databaseStatusLabel(database.status)
              }}</span>
              <span class="font-monospace"><i class="bi bi-hdd-stack me-1"></i>{{ database.name }}</span>
              <span v-if="database.serverVersion" class="text-muted small">{{ database.serverVersion }}</span>
              <span v-if="database.databaseName" class="text-muted small font-monospace">{{
                database.databaseName
              }}</span>
              <span v-if="database.truncated" class="badge text-bg-warning">Truncated</span>
            </div>
            <span
              v-if="database.role"
              class="text-muted small font-monospace"
              :title="
                database.monitoringRole
                  ? 'Can read the statistics of every backend'
                  : 'Cannot read the statistics of other backends'
              "
            >
              <i class="bi bi-person-badge me-1"></i>{{ database.role
              }}<span v-if="database.monitoringRole"> · pg_read_all_stats</span>
            </span>
          </div>
          <div v-if="database.message" class="card-body border-bottom small text-muted font-monospace">
            {{ database.message }}
          </div>

          <div v-if="database.vitalSigns" class="card-body border-bottom">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <h4 class="fs-6 fw-semibold mb-0">Vital signs</h4>
              <span
                v-if="section(database, 'vital-signs')"
                :class="sectionStatusClass(sectionBadge(section(database, 'vital-signs')))"
                class="badge"
                >{{ sectionBadge(section(database, 'vital-signs')) }}</span
              >
            </div>
            <div v-if="sectionNote(section(database, 'vital-signs'))" class="small text-muted mb-2">
              <i class="bi bi-info-circle me-1"></i>{{ sectionNote(section(database, 'vital-signs')) }}
            </div>
            <div class="row g-3">
              <div class="col-6 col-md-3">
                <div class="text-muted small">Cache hit ratio</div>
                <div class="fw-semibold font-monospace">{{ percent(database.vitalSigns.cacheHitRatio) }}</div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Rollback ratio</div>
                <div class="fw-semibold font-monospace">{{ percent(database.vitalSigns.rollbackRatio) }}</div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Connections</div>
                <div class="fw-semibold font-monospace">
                  {{ formatNumber(database.vitalSigns.connections) }} /
                  {{ formatNumber(database.vitalSigns.maxConnections) }}
                </div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Connection usage</div>
                <div class="fw-semibold font-monospace">{{ percent(database.vitalSigns.connectionUsageRatio) }}</div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Idle in transaction</div>
                <div class="fw-semibold font-monospace">
                  {{ formatNumber(database.vitalSigns.idleInTransactionSessions) }}
                </div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Longest transaction</div>
                <div class="fw-semibold font-monospace">
                  {{ seconds(database.vitalSigns.longestTransactionSeconds) }}
                </div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Blocked sessions</div>
                <div class="fw-semibold font-monospace">{{ formatNumber(database.vitalSigns.blockedSessions) }}</div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Wraparound usage</div>
                <div class="fw-semibold font-monospace">{{ percent(database.vitalSigns.wraparoundUsageRatio) }}</div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Database size</div>
                <div class="fw-semibold font-monospace">{{ formatBytes(database.vitalSigns.databaseSizeBytes) }}</div>
              </div>
              <div class="col-6 col-md-3">
                <div class="text-muted small">Deadlocks</div>
                <div class="fw-semibold font-monospace">{{ formatNumber(database.vitalSigns.deadlocks) }}</div>
              </div>
            </div>
          </div>

          <div
            v-for="part in (database.sections || []).filter((candidate) => candidate.id !== 'vital-signs')"
            :key="part.id"
            class="card-body border-bottom"
          >
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <h4 class="fs-6 fw-semibold mb-0">{{ part.title }}</h4>
              <span :class="sectionStatusClass(sectionBadge(part))" class="badge">{{ sectionBadge(part) }}</span>
              <span v-if="part.status === 'AVAILABLE'" class="text-muted small"
                >{{ formatNumber(part.rowCount) }} {{ pluralize(part.rowCount, 'row') }}</span
              >
              <span v-if="part.truncated" class="badge text-bg-warning">Truncated</span>
            </div>
            <div v-if="sectionNote(part)" class="small text-muted mb-2">
              <i class="bi bi-info-circle me-1"></i>{{ sectionNote(part) }}
            </div>
            <div v-if="part.hint" class="small text-muted font-monospace mb-2">
              <i class="bi bi-lightbulb me-1"></i>{{ part.hint }}
            </div>

            <div v-if="part.id === 'sessions' && sectionReadable(part)" class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">PID</th>
                    <th scope="col">Role</th>
                    <th scope="col">Application</th>
                    <th scope="col">State</th>
                    <th scope="col">Waiting on</th>
                    <th scope="col">Blocked by</th>
                    <th scope="col" class="text-end">Transaction</th>
                    <th scope="col" class="text-end">In state</th>
                    <th scope="col">Statement</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="session in database.sessions || []" :key="session.pid" :class="sessionRowClass(session)">
                    <td class="font-monospace">{{ session.pid }}</td>
                    <td class="font-monospace">{{ text(session.user) }}</td>
                    <td class="font-monospace">{{ text(session.applicationName) }}</td>
                    <td>
                      <span class="font-monospace">{{ text(session.state) }}</span>
                      <i v-if="sessionBusy(session)" class="bi bi-activity ms-1" aria-hidden="true"></i>
                    </td>
                    <td class="font-monospace">
                      <template v-if="session.waitEventType"
                        >{{ session.waitEventType }}<span v-if="session.waitEvent">/{{ session.waitEvent }}</span>
                      </template>
                      <template v-else>—</template>
                    </td>
                    <td class="font-monospace">{{ text(session.blockedBy) }}</td>
                    <td class="font-monospace text-end">{{ seconds(session.transactionSeconds) }}</td>
                    <td class="font-monospace text-end">{{ seconds(session.stateSeconds) }}</td>
                    <td class="font-monospace small text-break">{{ text(session.query) }}</td>
                  </tr>
                  <tr v-if="(database.sessions || []).length === 0">
                    <td class="text-muted" colspan="9">No client backend was connected at the time of the read.</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else-if="part.id === 'statements' && sectionReadable(part)" class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Normalized statement</th>
                    <th scope="col" class="text-end">Calls</th>
                    <th scope="col" class="text-end">Total</th>
                    <th scope="col" class="text-end">Mean</th>
                    <th scope="col" class="text-end">Max</th>
                    <th scope="col" class="text-end">Rows</th>
                    <th scope="col" class="text-end">Cache hit</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="statement in database.statements || []" :key="statement.queryId">
                    <td class="font-monospace small text-break">{{ text(statement.query) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(statement.calls) }}</td>
                    <td class="font-monospace text-end">{{ millis(statement.totalTimeMs) }}</td>
                    <td class="font-monospace text-end">{{ millis(statement.meanTimeMs) }}</td>
                    <td class="font-monospace text-end">{{ millis(statement.maxTimeMs) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(statement.rows) }}</td>
                    <td class="font-monospace text-end">{{ percent(statement.cacheHitRatio) }}</td>
                  </tr>
                  <tr v-if="(database.statements || []).length === 0">
                    <td class="text-muted" colspan="7">pg_stat_statements reported no statement for this database.</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else-if="part.id === 'indexes' && sectionReadable(part)" class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Index</th>
                    <th scope="col">Table</th>
                    <th scope="col" class="text-end">Scans</th>
                    <th scope="col" class="text-end">Tuples read</th>
                    <th scope="col" class="text-end">Size</th>
                    <th scope="col">Kind</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="index in database.indexes || []" :key="`${index.schema}.${index.index}`">
                    <td class="font-monospace">{{ index.index }}</td>
                    <td class="font-monospace">{{ index.schema }}.{{ index.table }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(index.scans) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(index.tuplesRead) }}</td>
                    <td class="font-monospace text-end">{{ formatBytes(index.sizeBytes) }}</td>
                    <td class="small">
                      <span v-if="index.primaryKey" class="badge text-bg-light border text-dark me-1">primary key</span>
                      <span v-else-if="index.unique" class="badge text-bg-light border text-dark me-1">unique</span>
                      <span
                        v-if="index.constraintBacked && !index.primaryKey"
                        class="badge text-bg-light border text-dark"
                        >constraint</span
                      >
                    </td>
                  </tr>
                  <tr v-if="(database.indexes || []).length === 0">
                    <td class="text-muted" colspan="6">No user index was reported for this database.</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else-if="part.id === 'tables' && sectionReadable(part)" class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Table</th>
                    <th scope="col" class="text-end">Total size</th>
                    <th scope="col" class="text-end">Indexes</th>
                    <th scope="col" class="text-end">Live rows</th>
                    <th scope="col" class="text-end">Dead rows</th>
                    <th scope="col" class="text-end">Seq scans</th>
                    <th scope="col" class="text-end">Index scans</th>
                    <th scope="col" class="text-end">Seq share</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="table in database.tables || []" :key="`${table.schema}.${table.table}`">
                    <td class="font-monospace">{{ table.schema }}.{{ table.table }}</td>
                    <td class="font-monospace text-end">{{ formatBytes(table.totalSizeBytes) }}</td>
                    <td class="font-monospace text-end">{{ formatBytes(table.indexSizeBytes) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(table.liveTuples) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(table.deadTuples) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(table.sequentialScans) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(table.indexScans) }}</td>
                    <td class="font-monospace text-end">{{ percent(table.sequentialScanRatio) }}</td>
                  </tr>
                  <tr v-if="(database.tables || []).length === 0">
                    <td class="text-muted" colspan="8">No user relation was reported for this database.</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else-if="part.id === 'vacuum' && sectionReadable(part)" class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Table</th>
                    <th scope="col" class="text-end">Dead rows</th>
                    <th scope="col" class="text-end">Dead share</th>
                    <th scope="col" class="text-end">Autovacuum at</th>
                    <th scope="col">Last vacuum</th>
                    <th scope="col">Last analyze</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="relation in database.vacuum || []"
                    :key="`${relation.schema}.${relation.table}`"
                    :class="relation.vacuumDue ? 'table-warning' : ''"
                  >
                    <td class="font-monospace">
                      {{ relation.schema }}.{{ relation.table }}
                      <span v-if="!relation.autovacuumEnabled" class="badge text-bg-secondary ms-1"
                        >autovacuum off</span
                      >
                    </td>
                    <td class="font-monospace text-end">{{ formatNumber(relation.deadTuples) }}</td>
                    <td class="font-monospace text-end">{{ percent(relation.deadTupleRatio) }}</td>
                    <td class="font-monospace text-end">{{ formatNumber(relation.vacuumThreshold) }}</td>
                    <td class="font-monospace small">{{ since(relation.lastAutoVacuum || relation.lastVacuum) }}</td>
                    <td class="font-monospace small">{{ since(relation.lastAutoAnalyze || relation.lastAnalyze) }}</td>
                  </tr>
                  <tr v-if="(database.vacuum || []).length === 0">
                    <td class="text-muted" colspan="6">No user relation was reported for this database.</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else-if="part.id === 'replication' && sectionReadable(part) && database.replication">
              <div class="row g-3 mb-2">
                <div class="col-6 col-md-3">
                  <div class="text-muted small">Role</div>
                  <div class="fw-semibold font-monospace">
                    {{ database.replication.inRecovery ? 'standby' : 'primary' }}
                  </div>
                </div>
                <div class="col-6 col-md-3">
                  <div class="text-muted small">WAL level</div>
                  <div class="fw-semibold font-monospace">{{ text(database.replication.walLevel) }}</div>
                </div>
                <div class="col-6 col-md-3">
                  <div class="text-muted small">Checkpoints (timed / requested)</div>
                  <div class="fw-semibold font-monospace">
                    {{ formatNumber(database.replication.checkpointsTimed) }} /
                    {{ formatNumber(database.replication.checkpointsRequested) }}
                  </div>
                </div>
                <div class="col-6 col-md-3">
                  <div class="text-muted small">Slots (inactive)</div>
                  <div class="fw-semibold font-monospace">
                    {{ formatNumber(database.replication.replicationSlots) }} ({{
                      formatNumber(database.replication.inactiveReplicationSlots)
                    }})
                  </div>
                </div>
              </div>
              <div v-if="(database.replication.replicas || []).length > 0" class="table-responsive">
                <table class="table table-sm align-middle mb-0">
                  <thead>
                    <tr>
                      <th scope="col">Application</th>
                      <th scope="col">Client</th>
                      <th scope="col">State</th>
                      <th scope="col">Sync</th>
                      <th scope="col" class="text-end">Sent lag</th>
                      <th scope="col" class="text-end">Flush lag</th>
                      <th scope="col" class="text-end">Replay lag</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(replica, index) in database.replication.replicas" :key="index">
                      <td class="font-monospace">{{ text(replica.applicationName) }}</td>
                      <td class="font-monospace">{{ text(replica.clientAddress) }}</td>
                      <td class="font-monospace">{{ text(replica.state) }}</td>
                      <td class="font-monospace">{{ text(replica.syncState) }}</td>
                      <td class="font-monospace text-end">{{ formatBytes(replica.sentLagBytes) }}</td>
                      <td class="font-monospace text-end">{{ formatBytes(replica.flushLagBytes) }}</td>
                      <td class="font-monospace text-end">{{ formatBytes(replica.replayLagBytes) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-else class="small text-muted">No streaming replica is connected to this server.</div>
            </div>

            <div v-else-if="part.id === 'settings' && sectionReadable(part)" class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Setting</th>
                    <th scope="col">Value</th>
                    <th scope="col">Source</th>
                    <th scope="col">Why it is shown</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="setting in database.settings || []" :key="setting.name">
                    <td class="font-monospace">{{ setting.name }}</td>
                    <td class="font-monospace">
                      {{ text(setting.value) }}<span v-if="setting.unit"> {{ setting.unit }}</span>
                    </td>
                    <td class="font-monospace small">{{ text(setting.source) }}</td>
                    <td class="small text-muted">{{ text(setting.note) }}</td>
                  </tr>
                  <tr v-if="(database.settings || []).length === 0">
                    <td class="text-muted" colspan="4">No notable setting was reported by this server.</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-if="database.changes && database.changes.length" class="card-body">
            <h4 class="fs-6 fw-semibold mb-2">What changed since the previous read</h4>
            <ul class="list-unstyled mb-0">
              <li v-for="(change, index) in database.changes" :key="index" class="small font-monospace mb-1">
                <i :class="['bi', changeDirectionIcon(change.direction), 'me-1']" aria-hidden="true"></i>
                <span class="fw-semibold">{{ change.metric }}</span>
                : {{ change.previous }} → {{ change.current }}
              </li>
            </ul>
          </div>
        </div>

        <div v-if="diagnostics.length > 0" class="card">
          <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
            <div>
              <div class="fw-semibold">Read diagnostics</div>
              <div class="text-muted small">
                {{ diagnostics.length }} {{ pluralize(diagnostics.length, 'note') }} about the read itself
              </div>
            </div>
            <button
              class="btn btn-sm btn-outline-secondary"
              type="button"
              :aria-expanded="showDiagnostics"
              @click="showDiagnostics = !showDiagnostics"
            >
              {{ showDiagnostics ? 'Hide' : 'Show' }} diagnostics
            </button>
          </div>
          <ul v-if="showDiagnostics" class="list-group list-group-flush">
            <li v-for="(diagnostic, index) in diagnostics" :key="index" class="list-group-item small">
              <span :class="diagnosticClass(diagnostic.level)" class="badge me-2">{{ diagnostic.level }}</span>
              <span class="font-monospace">{{ diagnostic.source }}</span>
              <span class="ms-2">{{ diagnostic.message }}</span>
            </li>
          </ul>
        </div>
      </template>
    </template>
  </div>
</template>
