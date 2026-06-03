<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch} from '../api.js'
import {formatLoadError} from '../utils/loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const includeDependencies = ref(true)

const severityClasses = {
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

const severityOrder = ['HIGH', 'MEDIUM', 'LOW', 'INFO']

const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

const maxSeverityCount = computed(() => {
  if (!report.value?.severityCounts?.length) return 1
  return Math.max(1, ...report.value.severityCounts.map((count) => count.count))
})

const visibleFindings = computed(() => [...(report.value?.findings || [])])

const dependenciesWithMetadata = computed(() => {
  const total = report.value?.dependenciesAnalyzed || 0
  return total - (report.value?.dependenciesWithoutMetadata || 0)
})

const canDownloadMetadata = computed(
  () =>
    hasScanData.value &&
    (report.value?.metadata?.reflectionEntries > 0 ||
      report.value?.metadata?.serializationEntries > 0 ||
      report.value?.metadata?.resourceEntries > 0)
)

const emptyFindingsTitle = computed(() => {
  if (!hasScanData.value) return 'Run readiness checks to see native-image concerns'
  if (!report.value?.checksRun) return 'No checks were evaluated'
  return 'No native-image readiness concerns found'
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
    const res = await fetch('api/graalvm')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = formatLoadError(e, 'Unable to load GraalVM readiness report')
  }
}

async function runScan() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const res = await apiFetch(`api/graalvm/scan?includeDependencies=${includeDependencies.value}`, {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = formatLoadError(e, 'Unable to run GraalVM readiness checks')
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
      icon="bi-rocket-takeoff"
      title="GraalVM"
      subtitle="Survey the host application for GraalVM native-image readiness and generate a reachability-metadata.json scaffold."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <div class="form-check form-switch d-inline-flex align-items-center me-2 mb-0">
          <input
            id="graalvm-include-dependencies"
            v-model="includeDependencies"
            :disabled="loading || readOnly"
            class="form-check-input me-2"
            type="checkbox"
          />
          <label class="form-check-label small" for="graalvm-include-dependencies">Include dependencies</label>
        </div>
        <button :disabled="loading || readOnly" class="btn btn-primary" type="button" @click="runScan">
          <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
          {{ loading ? 'Running...' : 'Run readiness checks' }}
        </button>
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <template v-if="report">
      <div class="alert alert-info">
        <strong>Heuristic readiness checks.</strong>
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
              <div class="text-muted small">Checks run</div>
              <div class="display-6">{{ report.checksRun }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Concerns to review</div>
              <div class="display-6">{{ report.findingsFound }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Classes analysed</div>
              <div class="display-6">{{ report.classesAnalyzed }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-lg-5">
          <div class="card h-100">
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
                <div class="col-3">
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
        </div>

        <div class="col-lg-7">
          <div class="card h-100">
            <div class="card-header d-flex justify-content-between align-items-center">
              <span class="fw-semibold">reachability-metadata.json</span>
              <a
                v-if="canDownloadMetadata"
                class="btn btn-outline-primary btn-sm"
                download="reachability-metadata.json"
                href="api/graalvm/metadata"
              >
                <i class="bi bi-download me-1"></i>Download scaffold
              </a>
            </div>
            <div class="card-body">
              <div v-if="!hasScanData" class="text-muted">
                Run readiness checks to derive a metadata scaffold from the application's own classes.
              </div>
              <template v-else>
                <p class="small text-muted mb-2">
                  A heuristic scaffold seeded from the last scan. Review and complete it with the GraalVM tracing agent,
                  then place it under
                  <code>src/main/resources/META-INF/native-image/&lt;groupId&gt;/&lt;artifactId&gt;/</code>.
                </p>
                <ul class="list-unstyled mb-0 small">
                  <li>
                    <strong>{{ report.metadata.reflectionEntries }}</strong> reflection entries
                  </li>
                  <li>
                    <strong>{{ report.metadata.serializationEntries }}</strong> serialization entries
                  </li>
                  <li>
                    <strong>{{ report.metadata.resourceEntries }}</strong> resource globs
                  </li>
                </ul>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div v-if="report.includeDependencies" class="card mb-3">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <span class="fw-semibold">Dependency reachability metadata</span>
          <span class="text-muted small">
            {{ dependenciesWithMetadata }} of {{ report.dependenciesAnalyzed }} dependencies ship metadata
          </span>
        </div>
        <div v-if="!report.dependencies || report.dependencies.length === 0" class="card-body text-muted">
          No third-party dependencies with native-image metadata were detected on the classpath.
        </div>
        <div v-else class="list-group list-group-flush">
          <div
            v-for="dep in report.dependencies"
            :key="dep.name"
            class="list-group-item d-flex align-items-start gap-2"
          >
            <span :class="dep.shipsMetadata ? 'text-bg-success' : 'text-bg-secondary'" class="badge mt-1">
              {{ dep.shipsMetadata ? 'metadata' : 'none' }}
            </span>
            <div>
              <div class="font-monospace small">{{ dep.name }}</div>
              <div v-if="dep.note" class="small text-muted">{{ dep.note }}</div>
            </div>
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
          <i class="bi bi-rocket-takeoff fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ emptyFindingsTitle }}</div>
          <div>The GraalVM tracing agent and an actual native build remain the best way to verify readiness.</div>
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
              {{ occurrenceCountLabel(finding.occurrenceCount) }} for this check.
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
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
