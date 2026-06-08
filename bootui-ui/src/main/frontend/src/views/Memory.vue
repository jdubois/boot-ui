<script setup>
import {apiFetch} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useDismissedRules} from '../utils/useDismissedRules.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)

const {dismissLoading, loadDismissed, isDismissed, dismiss, restore} = useDismissedRules()

const severityClasses = {
  CRITICAL: 'text-bg-danger',
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

const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']

const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

const summary = computed(() => report.value?.summary || null)

const allViolations = computed(() =>
  [...(report.value?.results || [])].filter((result) => result.status === 'VIOLATION').sort(compareImportance)
)

const visibleResults = computed(() => allViolations.value.filter((result) => !isDismissed(result.id)))

const dismissedResults = computed(() => allViolations.value.filter((result) => isDismissed(result.id)))

const activeSeverityCounts = computed(() => {
  const counts = {}
  for (const sev of severityOrder) counts[sev] = 0
  for (const result of visibleResults.value) {
    if (counts[result.severity] !== undefined) counts[result.severity]++
  }
  return severityOrder.map((sev) => ({severity: sev, count: counts[sev]}))
})

const maxSeverityCount = computed(() => {
  if (!activeSeverityCounts.value.length) return 1
  return Math.max(1, ...activeSeverityCounts.value.map((item) => item.count))
})

const emptyRuleResultsTitle = computed(() => {
  if (!hasScanData.value) return 'Run memory checks to see advisor findings'
  if (!report.value?.rulesEvaluated) return 'No rules were evaluated'
  return 'No Memory Advisor findings'
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
  return `${count} ${pluralize(count, 'observation')} found`
}

function formatBytes(value) {
  if (value === null || value === undefined || value < 0) return 'n/a'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
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
    const res = await apiFetch('api/memory')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Memory Advisor report')
  }
}

async function runScan() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/memory/scan', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to run memory checks')
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

onMounted(async () => {
  await Promise.all([loadReport(), loadDismissed()])
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-clipboard2-pulse"
      title="Memory"
      subtitle="Rule-based JVM memory, GC, and thread health findings from the live management beans."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <button :disabled="loading || readOnly" class="btn btn-primary" type="button" @click="runScan">
          <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
          {{ loading ? 'Running...' : 'Run memory checks' }}
        </button>
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <template v-if="report">
      <div class="alert alert-info">
        <strong>Heuristic JVM memory and thread health rules.</strong>
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
              <div class="display-6">{{ visibleResults.length }}</div>
              <div v-if="dismissedResults.length > 0" class="small text-muted">
                {{ dismissedResults.length }} dismissed
              </div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Heap used</div>
              <div class="display-6">{{ summary ? summary.heapUsedPercent + '%' : '—' }}</div>
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
                <div class="fw-semibold text-body">No Memory Advisor data yet</div>
                <div>Run memory checks to populate advisor findings.</div>
              </div>
              <div
                v-for="item in activeSeverityCounts"
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
            <div class="card-header fw-semibold">Runtime snapshot</div>
            <div class="card-body">
              <div v-if="!summary" class="text-muted">Run memory checks to capture a runtime snapshot.</div>
              <dl v-else class="row mb-0 small">
                <dt class="col-6">Heap used</dt>
                <dd class="col-6 text-end">
                  {{ formatBytes(summary.heapUsedBytes) }} / {{ formatBytes(summary.heapMaxBytes) }} ({{
                    summary.heapUsedPercent
                  }}%)
                </dd>
                <dt class="col-6">Live threads</dt>
                <dd class="col-6 text-end">{{ summary.liveThreads }}</dd>
                <dt class="col-6">Peak threads</dt>
                <dd class="col-6 text-end">{{ summary.peakThreads }}</dd>
                <dt class="col-6">Loaded classes</dt>
                <dd class="col-6 text-end">{{ summary.loadedClasses }}</dd>
                <dt class="col-6">Deadlock detected</dt>
                <dd class="col-6 text-end">
                  <span :class="summary.deadlockDetected ? 'text-bg-danger' : 'text-bg-success'" class="badge">
                    {{ summary.deadlockDetected ? 'Yes' : 'No' }}
                  </span>
                </dd>
                <dt class="col-6">Heap histogram</dt>
                <dd class="col-6 text-end">
                  <span :class="summary.histogramAvailable ? 'text-bg-success' : 'text-bg-secondary'" class="badge">
                    {{ summary.histogramAvailable ? 'Collected' : 'Not collected' }}
                  </span>
                </dd>
              </dl>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <div class="fw-semibold">Rule results</div>
            <div class="text-muted small">
              <template v-if="hasScanData && visibleResults.length > 0">
                {{ visibleResults.length }} {{ pluralize(visibleResults.length, 'finding') }}, sorted by importance
              </template>
              <template v-else>{{ visibleResults.length }} advisor finding(s)</template>
            </div>
          </div>
          <span v-if="hasScanData && visibleResults.length === 0 && dismissedResults.length === 0" class="badge text-bg-success">No findings</span>
        </div>
        <div v-if="visibleResults.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-clipboard2-pulse fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ emptyRuleResultsTitle }}</div>
          <div>Validate findings against the application's workload and a profiler before acting.</div>
        </div>
        <div v-else class="list-group list-group-flush">
          <div v-for="result in visibleResults" :key="result.id" class="list-group-item">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <span :class="statusClass(result.status)" class="badge">{{ result.status }}</span>
              <span :class="severityClass(result.severity)" class="badge">{{ result.severity }}</span>
              <span class="badge text-bg-light border">{{ result.category }}</span>
              <span class="text-muted small">{{ result.id }}</span>
              <button
                class="btn btn-sm btn-outline-secondary ms-auto"
                type="button"
                :disabled="dismissLoading"
                @click="dismiss(result.id)"
                title="Dismiss this rule"
              >
                <i class="bi bi-eye-slash me-1"></i>Dismiss
              </button>
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
        <template v-if="dismissedResults.length > 0">
          <div class="card-header text-muted small">
            <i class="bi bi-eye-slash me-1"></i>Dismissed rules ({{ dismissedResults.length }}) — not counted in score
          </div>
          <div class="list-group list-group-flush">
            <div
              v-for="result in dismissedResults"
              :key="result.id"
              class="list-group-item opacity-50"
            >
              <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                <span :class="statusClass(result.status)" class="badge">{{ result.status }}</span>
                <span :class="severityClass(result.severity)" class="badge">{{ result.severity }}</span>
                <span class="badge text-bg-light border">{{ result.category }}</span>
                <span class="text-muted small">{{ result.id }}</span>
                <button
                  class="btn btn-sm btn-outline-secondary ms-auto"
                  type="button"
                  :disabled="dismissLoading"
                  @click="restore(result.id)"
                  title="Restore this rule"
                >
                  <i class="bi bi-eye me-1"></i>Restore
                </button>
              </div>
              <div class="small fw-semibold">{{ result.name }}</div>
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
