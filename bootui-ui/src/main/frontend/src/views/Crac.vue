<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch} from '../api.js'
import {formatClockTime} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)

const severityClasses = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

const runtime = computed(() => report.value?.runtime || null)

const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

const maxSeverityCount = computed(() => {
  if (!report.value?.severityCounts?.length) return 1
  return Math.max(1, ...report.value.severityCounts.map((count) => count.count))
})

const visibleFindings = computed(() => [...(report.value?.findings || [])])

const emptyFindingsTitle = computed(() => {
  if (!hasScanData.value) return 'Run readiness checks to see checkpoint/restore concerns'
  if (!report.value?.checksRun) return 'No checks were evaluated'
  return 'No checkpoint/restore readiness concerns found'
})

function severityClass(severity) {
  return severityClasses[severity] || 'text-bg-light border text-dark'
}

function severityWidth(count) {
  if (count === 0) return '0%'
  return `${Math.max(3, (count / maxSeverityCount.value) * 100)}%`
}

function pluralize(count, singular, plural = `${singular}s`) {
  return count === 1 ? singular : plural
}

function occurrenceCountLabel(count) {
  return `${count} ${pluralize(count, 'occurrence')} found`
}

function statusBadgeClass(value) {
  return value ? 'text-bg-success' : 'text-bg-secondary'
}

function statusLabel(value) {
  return value ? 'Yes' : 'No'
}

function scanTime() {
  if (!report.value?.scan?.scannedAt) return ''
  return formatClockTime(report.value.scan.scannedAt)
}

async function loadReport() {
  try {
    const res = await apiFetch('api/crac')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load CRaC readiness report')
  }
}

async function runScan() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/crac/scan', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to run CRaC readiness checks')
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
      icon="bi-camera"
      title="CRaC"
      subtitle="Review the host application's Coordinated Restore at Checkpoint readiness: live runtime status plus heuristic checkpoint/restore checks."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <SpinnerButton
          :loading="loading"
          :disabled="loading || readOnly"
          class="btn btn-primary"
          type="button"
          label="Run readiness checks"
          loading-label="Running..."
          @click="runScan"
        />
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <template v-if="report">
      <div v-if="runtime" class="card mb-3">
        <div class="card-header d-flex justify-content-between align-items-center">
          <span class="fw-semibold">Runtime status</span>
          <span class="text-muted small font-monospace">{{ runtime.jvmName }}</span>
        </div>
        <div class="card-body">
          <div class="alert alert-info mb-3">{{ runtime.summary }}</div>
          <div class="row g-3">
            <div class="col-md-3 col-6">
              <div class="text-muted small">org.crac API</div>
              <span :class="statusBadgeClass(runtime.cracApiPresent)" class="badge fs-6 mt-1">
                {{ statusLabel(runtime.cracApiPresent) }}
              </span>
            </div>
            <div class="col-md-3 col-6">
              <div class="text-muted small">CRaC-capable JVM</div>
              <span :class="statusBadgeClass(runtime.cracCapableJvm)" class="badge fs-6 mt-1">
                {{ statusLabel(runtime.cracCapableJvm) }}
              </span>
            </div>
            <div class="col-md-3 col-6">
              <div class="text-muted small">Checkpoint on refresh</div>
              <span :class="statusBadgeClass(runtime.checkpointOnRefresh)" class="badge fs-6 mt-1">
                {{ statusLabel(runtime.checkpointOnRefresh) }}
              </span>
            </div>
            <div class="col-md-3 col-6">
              <div class="text-muted small">Checkpoint directory</div>
              <div class="font-monospace small mt-1">{{ runtime.checkpointTo || '—' }}</div>
            </div>
          </div>
          <div v-if="runtime.restoreFrom" class="small text-muted mt-2">
            Restoring from <code>{{ runtime.restoreFrom }}</code>
          </div>
          <div v-if="runtime.cracJvmArgs && runtime.cracJvmArgs.length" class="mt-3">
            <div class="small fw-semibold">CRaC JVM arguments</div>
            <ul class="small mb-0 font-monospace">
              <li v-for="(arg, index) in runtime.cracJvmArgs" :key="index">{{ arg }}</li>
            </ul>
          </div>
          <div class="small text-muted mt-3">
            Learn more in the
            <a
              href="https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html"
              rel="noopener noreferrer"
              target="_blank"
              >Spring CRaC reference</a
            >
            and the
            <a href="https://crac.org/" rel="noopener noreferrer" target="_blank">Project CRaC</a> docs.
          </div>
        </div>
      </div>

      <div class="alert alert-info">
        <strong>Heuristic readiness checks.</strong>
        {{ report.disclaimer }}
        <span v-if="readOnly">Scanning is read-only. {{ readOnlyReason }}</span>
      </div>
      <div v-if="report.warnings && report.warnings.length" class="alert alert-warning">
        <strong>Scan warnings.</strong>
        <ul class="mb-0">
          <li v-for="warning in report.warnings" :key="warning">{{ warning }}</li>
        </ul>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-md-4">
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
        <div class="col-md-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Checks run</div>
              <div class="display-6">{{ report.checksRun }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Concerns to review</div>
              <div class="display-6">{{ report.findingsFound }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="card mb-3">
        <div class="card-header fw-semibold">Concerns by severity</div>
        <div class="card-body">
          <div v-if="!hasScanData" class="text-center text-muted py-4">
            <i class="bi bi-search fs-2 d-block mb-2"></i>
            <div class="fw-semibold text-body">No readiness data yet</div>
            <div>Run readiness checks to populate the results.</div>
          </div>
          <div
            v-for="item in report.severityCounts"
            v-else
            :key="item.severity"
            class="row align-items-center g-2 mb-2"
          >
            <div class="col-3 col-md-2">
              <span :class="severityClass(item.severity)" class="badge">{{ item.severity }}</span>
            </div>
            <div class="col">
              <div :aria-label="`${item.severity} concerns: ${item.count}`" class="progress" role="img">
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

      <div class="card">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <div class="fw-semibold">Readiness concerns</div>
            <div class="text-muted small">
              <template v-if="hasScanData && report.findingsFound > 0">
                {{ report.findingsFound }} {{ pluralize(report.findingsFound, 'concern') }}, sorted by importance
              </template>
              <template v-else>{{ report.findingsFound }} concern(s) to review</template>
            </div>
          </div>
          <span v-if="hasScanData && report.findingsFound === 0" class="badge text-bg-success">No concerns</span>
        </div>
        <div v-if="visibleFindings.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-camera fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ emptyFindingsTitle }}</div>
          <div>An actual checkpoint/restore run on a CRaC-enabled JDK remains the best way to verify readiness.</div>
        </div>
        <div v-else class="list-group list-group-flush">
          <div v-for="finding in visibleFindings" :key="finding.id" class="list-group-item">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <span class="badge text-bg-warning">{{ finding.status }}</span>
              <span :class="severityClass(finding.severity)" class="badge">{{ finding.severity }}</span>
              <span class="badge text-bg-light border">{{ finding.category }}</span>
              <span class="text-muted small">{{ finding.id }}</span>
            </div>
            <h3 class="h6 mb-1">{{ finding.name }}</h3>
            <div class="small text-muted mb-2">{{ finding.description }}</div>
            <div class="small mb-2">
              <strong>What happened:</strong>
              <template v-if="finding.status === 'ERROR'">
                {{ finding.sampleOccurrences?.[0] || 'Check could not be evaluated.' }}
              </template>
              <template v-else>{{ occurrenceCountLabel(finding.occurrenceCount) }} for this check.</template>
            </div>
            <div v-if="finding.sampleOccurrences && finding.sampleOccurrences.length" class="mb-2">
              <div class="small fw-semibold">
                Sample details (showing {{ finding.sampleOccurrences.length }} of {{ finding.occurrenceCount }})
              </div>
              <ul class="small mb-0">
                <li v-for="(sample, index) in finding.sampleOccurrences" :key="index" class="font-monospace">
                  {{ sample }}
                </li>
              </ul>
            </div>
            <div class="small">
              <strong>Recommendation:</strong>
              {{ finding.recommendation }}
              <a
                v-if="finding.learnMoreUrl"
                :href="finding.learnMoreUrl"
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
