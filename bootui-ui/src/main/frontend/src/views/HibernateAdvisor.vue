<script setup>
import {apiFetch} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)

const severityClasses = {
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

const statusClasses = {
  PASS: 'text-bg-success',
  VIOLATION: 'text-bg-danger',
  SKIPPED: 'text-bg-secondary',
  ERROR: 'text-bg-warning'
}

const severityOrder = ['HIGH', 'MEDIUM', 'LOW', 'INFO']

const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

const maxSeverityCount = computed(() => {
  if (!report.value?.severityCounts?.length) return 1
  return Math.max(1, ...report.value.severityCounts.map((count) => count.count))
})

const visibleResults = computed(() =>
  [...(report.value?.results || [])].filter((result) => result.status === 'VIOLATION').sort(compareImportance)
)

const emptyRuleResultsTitle = computed(() => {
  if (!hasScanData.value) return 'Run Hibernate checks to see advisor findings'
  if (!report.value?.rulesEvaluated) return 'No rules were evaluated'
  return 'No Hibernate Advisor findings'
})

function severityClass(severity) {
  return severityClasses[severity] || 'text-bg-light border text-dark'
}

function statusClass(status) {
  return statusClasses[status] || 'text-bg-light border text-dark'
}

function severityWidth(count) {
  if (count === 0) return '0%'
  return `${Math.max(3, (count / maxSeverityCount.value) * 100)}%`
}

function compareImportance(left, right) {
  const severityDiff = severityRank(left.severity) - severityRank(right.severity)
  if (severityDiff !== 0) return severityDiff
  const countDiff = right.violationCount - left.violationCount
  if (countDiff !== 0) return countDiff
  return left.id.localeCompare(right.id)
}

function severityRank(severity) {
  const index = severityOrder.indexOf(severity)
  return index === -1 ? severityOrder.length : index
}

function pluralize(count, singular, plural = `${singular}s`) {
  return count === 1 ? singular : plural
}

function violationCountLabel(count) {
  return `${count} ${pluralize(count, 'finding')} found`
}

function scanTime() {
  if (!report.value?.scan?.scannedAt) return ''
  return new Date(report.value.scan.scannedAt).toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

async function loadReport() {
  try {
    const res = await apiFetch('api/hibernate-advisor')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Hibernate Advisor report')
  }
}

async function runScan() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/hibernate-advisor/scan', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to run Hibernate checks')
  } finally {
    loading.value = false
  }
}

function showReadOnlyMessage() {
  actionMessage.value = readOnlyReason.value
  setTimeout(() => {
    actionMessage.value = null
  }, 6000)
}

onMounted(loadReport)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-database-gear"
      title="Hibernate Advisor"
      subtitle="Review mapped JPA entities with bounded Hibernate performance checks."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <button :disabled="loading || readOnly" class="btn btn-primary" type="button" @click="runScan">
          <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
          {{ loading ? 'Running...' : 'Run Hibernate checks' }}
        </button>
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <template v-if="report">
      <div class="alert alert-info">
        <strong>Heuristic Hibernate rules.</strong>
        {{ report.disclaimer }}
        <span v-if="readOnly">Scanning is read-only. {{ readOnlyReason }}</span>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Scan status</div>
              <div class="mt-2">
                <span :class="scanStatusBadgeClass(report.scan.status)" class="badge fs-6">
                  {{ scanStatusLabel(report.scan.status) }}
                </span>
              </div>
              <div v-if="scanTime()" class="small text-muted">Scanned at {{ scanTime() }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Rules evaluated</div>
              <div class="display-6">{{ report.rulesEvaluated }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Advisor findings</div>
              <div class="display-6">{{ report.violationsFound }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Entities analysed</div>
              <div class="display-6">{{ report.entitiesAnalyzed }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-header fw-semibold">Findings by severity</div>
            <div class="card-body">
              <div v-if="!hasScanData" class="text-center text-muted py-4">
                <i class="bi bi-search fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No Hibernate Advisor data yet</div>
                <div>Run Hibernate checks to populate advisor findings.</div>
              </div>
              <div
                v-for="item in report.severityCounts"
                v-else
                :key="item.severity"
                class="row align-items-center g-2 mb-2"
              >
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
        </div>

        <div class="col-lg-7">
          <div class="card h-100">
            <div class="card-header fw-semibold">Entity packages</div>
            <div class="card-body">
              <div v-if="!report.entityPackages || report.entityPackages.length === 0" class="text-muted">
                No mapped entity package was detected.
              </div>
              <ul v-else class="list-unstyled mb-0">
                <li v-for="pkg in report.entityPackages" :key="pkg" class="font-monospace small">
                  <i class="bi bi-box me-1"></i>{{ pkg }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <div class="fw-semibold">Rule results</div>
            <div class="text-muted small">
              <template v-if="hasScanData && report.violationsFound > 0">
                {{ report.violationsFound }} {{ pluralize(report.violationsFound, 'violating rule') }}, sorted by
                importance
              </template>
              <template v-else>{{ report.violationsFound }} advisor finding(s)</template>
            </div>
          </div>
          <span v-if="hasScanData && report.violationsFound === 0" class="badge text-bg-success">No findings</span>
        </div>
        <div v-if="visibleResults.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-database-gear fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ emptyRuleResultsTitle }}</div>
          <div>Project-specific performance tests and query reviews remain the source of truth.</div>
        </div>
        <div v-else class="list-group list-group-flush">
          <div v-for="result in visibleResults" :key="result.id" class="list-group-item">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <span :class="statusClass(result.status)" class="badge">{{ result.status }}</span>
              <span :class="severityClass(result.severity)" class="badge">{{ result.severity }}</span>
              <span class="badge text-bg-light border">{{ result.category }}</span>
              <span class="text-muted small">{{ result.id }}</span>
            </div>
            <h3 class="h6 mb-1">{{ result.name }}</h3>
            <div class="small text-muted mb-2">{{ result.description }}</div>
            <div class="small mb-2">
              <strong>What happened:</strong>
              {{ violationCountLabel(result.violationCount) }} for this rule.
            </div>
            <div v-if="result.sampleViolations && result.sampleViolations.length" class="mb-2">
              <div class="small fw-semibold">
                Sample details (showing {{ result.sampleViolations.length }} of {{ result.violationCount }})
              </div>
              <ul class="small mb-0">
                <li v-for="(sample, index) in result.sampleViolations" :key="index" class="font-monospace">
                  {{ sample }}
                </li>
              </ul>
            </div>
            <div class="small">
              <strong>Recommendation:</strong>
              {{ result.recommendation }}
              <a
                v-if="result.learnMoreUrl"
                :href="result.learnMoreUrl"
                class="ms-1"
                rel="noopener noreferrer"
                target="_blank"
              >
                Learn more
              </a>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
