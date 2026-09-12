<script setup>
import {actionBusyMessage, getJson, isActionBusyError} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {formatBytes, formatClockTime, formatNumber} from '../utils/format.js'
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

const SEVERITY_CLASSES = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']

const DATABASE_STATUS_CLASSES = {
  SCANNED: 'text-bg-success',
  PARTIAL: 'text-bg-warning',
  ERROR: 'text-bg-danger',
  DISABLED: 'text-bg-secondary'
}

const DATABASE_STATUS_LABELS = {
  SCANNED: 'Read',
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

// The read has actually produced a database read once the status leaves NOT_READ, so an
// empty prompt is only shown before the first read rather than hiding a real (possibly
// disabled or errored) result.
const hasRead = computed(() => Boolean(report.value) && report.value.status !== 'NOT_READ')

const disabled = computed(() => report.value?.status === 'DISABLED')

const databases = computed(() => report.value?.databases || [])
const diagnostics = computed(() => report.value?.diagnostics || [])
const severityCounts = computed(() => report.value?.severityCounts || [])

const findings = computed(() =>
  [...(report.value?.findings || [])].sort((left, right) => {
    const severityDiff = severityRank(left.severity) - severityRank(right.severity)
    if (severityDiff !== 0) return severityDiff
    return (left.id || '').localeCompare(right.id || '')
  })
)

const maxSeverityCount = computed(() => Math.max(1, ...severityCounts.value.map((item) => item.count)))

const limitations = computed(() => report.value?.evidence?.limitations || [])

const readFailed = computed(() => hasRead.value && report.value?.status === 'ERROR')

const nothingAssessed = computed(() => hasRead.value && report.value?.evidence?.usable === false)

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
    // a clean result.
    if (report.value?.status === 'ERROR' || report.value?.status === 'PARTIAL') {
      return report.value.message || 'The read did not complete; see the diagnostics below.'
    }
    return null
  }
  return `${reasons.join('; ')}. These are reported below as diagnostics and are not counted as findings.`
})

function severityRank(severity) {
  const index = SEVERITY_ORDER.indexOf(severity)
  return index === -1 ? SEVERITY_ORDER.length : index
}

function severityClass(severity) {
  return SEVERITY_CLASSES[severity] || 'text-bg-light border text-dark'
}

function severityWidth(count) {
  if (count === 0) return '0%'
  return `${Math.max(3, (count / maxSeverityCount.value) * 100)}%`
}

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

function readTime() {
  if (!report.value?.readAt) return ''
  return formatClockTime(report.value.readAt)
}

function databaseFindingCount(database) {
  return findings.value.filter((finding) => finding.dataSource === database.name).length
}

// The engine marks a partially read section AVAILABLE with a reason, because the rows it did
// read are real. That reason is what stops the section from claiming more than it checked, so
// it is shown as PARTIAL rather than green. A truncated section is partial for the same reason
// even when it carries no reason of its own: the rows past the bound were never examined.
function sectionPartial(section) {
  return section.status === 'AVAILABLE' && (!!section.reason || !!section.truncated)
}

function sectionPartialReason(section) {
  return section.reason || 'a row bound was reached, so rows past it were not examined'
}

// A section only reads as "clean" when it was AVAILABLE, had zero findings, and read everything
// it set out to read. PARTIAL, SKIPPED and FAILED always carry their reason, so a view BootUI
// could not fully read never looks healthy.
function sectionBadge(section) {
  return sectionPartial(section) ? 'PARTIAL' : section.status
}

function sectionSummary(section) {
  if (section.status === 'AVAILABLE') {
    const findings =
      section.findingCount > 0
        ? `${section.findingCount} ${pluralize(section.findingCount, 'finding')}`
        : 'Checked and clean'
    return sectionPartial(section) ? `Partially read — ${sectionPartialReason(section)}` : findings
  }
  if (section.status === 'SKIPPED') {
    return section.reason ? `Skipped — ${section.reason}` : 'Skipped'
  }
  if (section.status === 'FAILED') {
    return section.reason ? `Failed — ${section.reason}` : 'Failed'
  }
  return section.reason || section.status
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
      subtitle="Bounded, read-only vital signs of the application's own PostgreSQL database, read on demand from its pg_stat_* and pg_catalog views."
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
        <strong>Read-only PostgreSQL vital signs.</strong>
        {{ report.disclaimer }}
        <span v-if="readOnly">Reading is read-only. {{ readOnlyReason }}</span>
      </div>

      <div v-if="!hasRead" class="card">
        <div class="card-body text-center text-muted py-5">
          <i class="bi bi-database-fill-check fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">No PostgreSQL data yet</div>
          <div>Run the PostgreSQL read to inspect the database's own vital signs.</div>
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
          <span
            >{{ report.databasesRead }} {{ pluralize(report.databasesRead, 'database') }} read ·
            {{ report.findingsFound }} {{ pluralize(report.findingsFound, 'finding') }}</span
          >
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
          <summary class="fw-semibold">Read limitations ({{ limitations.length }})</summary>
          <ul class="mb-0 mt-2 small">
            <li v-for="(limitation, index) in limitations" :key="index">{{ limitation }}</li>
          </ul>
        </details>

        <div class="card mb-3">
          <div class="card-header"><h3 class="fs-6 fw-semibold mb-0">Findings by severity</h3></div>
          <div class="card-body">
            <div v-if="findings.length === 0 && nothingAssessed" class="text-center text-muted py-3">
              <i class="bi bi-slash-circle fs-2 d-block mb-2"></i>
              <div class="fw-semibold text-body">Nothing was assessed</div>
              <div>No PostgreSQL statistics were read, so the absence of findings means nothing.</div>
            </div>
            <div v-else-if="findings.length === 0" class="text-center text-muted py-3">
              <i class="bi bi-check2-circle fs-2 d-block mb-2"></i>
              <div class="fw-semibold text-body">No findings in the assessed evidence</div>
              <div>Sections that could not be read are listed below as skipped, not as passing.</div>
            </div>
            <div v-for="item in severityCounts" v-else :key="item.severity" class="row align-items-center g-2 mb-2">
              <div class="col-3">
                <span :class="severityClass(item.severity)" class="badge">{{ item.severity }}</span>
              </div>
              <div class="col">
                <div :aria-label="`${item.severity} findings: ${item.count}`" class="progress" role="img">
                  <div
                    :class="severityClass(item.severity)"
                    :style="{width: severityWidth(item.count)}"
                    class="progress-bar"
                  ></div>
                </div>
              </div>
              <div class="col-auto small text-muted">{{ item.count }}</div>
            </div>
          </div>
        </div>

        <div v-if="findings.length > 0" class="card mb-3">
          <div class="card-header">
            <h3 class="fs-6 fw-semibold mb-0">Findings</h3>
            <div class="text-muted small">
              {{ findings.length }} {{ pluralize(findings.length, 'finding') }}, sorted by severity
            </div>
          </div>
          <div class="list-group list-group-flush">
            <div v-for="finding in findings" :key="finding.id" class="list-group-item">
              <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
                <span :class="severityClass(finding.severity)" class="badge">{{ finding.severity }}</span>
                <span v-if="finding.category" class="badge text-bg-light border font-monospace">{{
                  finding.category
                }}</span>
                <span class="text-muted small font-monospace">{{ finding.id }}</span>
                <span v-if="finding.dataSource" class="text-muted small font-monospace ms-auto"
                  ><i class="bi bi-hdd-stack me-1"></i>{{ finding.dataSource }}</span
                >
              </div>
              <h4 class="h6 mb-1">{{ finding.title }}</h4>
              <div v-if="finding.description" class="small text-muted mb-2">{{ finding.description }}</div>
              <div v-if="finding.evidence" class="small mb-2">
                <strong>Evidence:</strong> <span class="font-monospace">{{ finding.evidence }}</span>
              </div>
              <div v-if="finding.samples && finding.samples.length" class="mb-2">
                <div class="small fw-semibold">Samples</div>
                <ul class="small mb-0">
                  <li v-for="(sample, index) in finding.samples" :key="index" class="font-monospace">{{ sample }}</li>
                </ul>
              </div>
              <div v-if="finding.recommendation" class="small mb-1">
                <strong>Recommendation:</strong>
                {{ finding.recommendation }}
                <a
                  v-if="finding.learnMoreUrl"
                  :href="finding.learnMoreUrl"
                  class="ms-1"
                  rel="noopener noreferrer"
                  target="_blank"
                  >Learn more</a
                >
              </div>
              <div v-if="finding.caveat" class="small text-muted">
                <i class="bi bi-info-circle me-1"></i>{{ finding.caveat }}
              </div>
            </div>
          </div>
        </div>

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
              :title="database.monitoringRole ? 'Connected with pg_monitor' : 'Without pg_monitor'"
            >
              <i class="bi bi-person-badge me-1"></i>{{ database.role
              }}<span v-if="database.monitoringRole"> · pg_monitor</span>
            </span>
          </div>
          <div v-if="database.message" class="card-body border-bottom small text-muted font-monospace">
            {{ database.message }}
          </div>

          <div v-if="database.vitalSigns" class="card-body border-bottom">
            <h4 class="fs-6 fw-semibold mb-2">Vital signs</h4>
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

          <div v-if="database.sections && database.sections.length" class="card-body border-bottom">
            <h4 class="fs-6 fw-semibold mb-2">Sections</h4>
            <ul class="list-unstyled mb-0">
              <li v-for="section in database.sections" :key="section.id" class="mb-2">
                <div class="d-flex flex-wrap align-items-center gap-2">
                  <span :class="sectionStatusClass(sectionBadge(section))" class="badge">{{
                    sectionBadge(section)
                  }}</span>
                  <span class="fw-semibold">{{ section.title }}</span>
                  <span class="text-muted small">{{ sectionSummary(section) }}</span>
                  <span v-if="section.truncated" class="badge text-bg-warning">Truncated</span>
                </div>
                <div v-if="section.hint" class="small text-muted font-monospace ms-1">
                  <i class="bi bi-lightbulb me-1"></i>{{ section.hint }}
                </div>
              </li>
            </ul>
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
                {{ diagnostics.length }} {{ pluralize(diagnostics.length, 'note') }} — not counted as findings
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
