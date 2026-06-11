<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
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
const installing = ref(false)
const installResult = ref(null)
const installingDockerfile = ref(false)
const dockerInstallResult = ref(null)
const installingBoth = ref(false)
const bothInstallResult = ref(null)
const includeDependencies = ref(false)
const openArtifact = ref('both')
const scanProgress = ref(null)
const cancellingScan = ref(false)
let progressTimer = null

const severityClasses = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

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

const progressPercent = computed(() => {
  const total = scanProgress.value?.dependenciesTotal || 0
  const current = scanProgress.value?.dependenciesScanned || 0
  if (!scanProgress.value?.running || total <= 0) return 0
  return Math.min(100, Math.max(2, Math.round((current / total) * 100)))
})

const canDownloadMetadata = computed(
  () =>
    hasScanData.value &&
    (report.value?.metadata?.reflectionEntries > 0 ||
      report.value?.metadata?.serializationEntries > 0 ||
      report.value?.metadata?.resourceEntries > 0)
)

const canWriteBoth = computed(
  () => hasScanData.value && report.value?.installable && report.value?.dockerfile?.installable
)

function alertClassForStatus(status) {
  switch (status) {
    case 'WRITTEN':
      return 'alert-success'
    case 'EXISTS':
      return 'alert-warning'
    case 'ERROR':
      return 'alert-danger'
    default:
      return 'alert-info'
  }
}

const installResultClass = computed(() => alertClassForStatus(installResult.value?.status))
const dockerInstallResultClass = computed(() => alertClassForStatus(dockerInstallResult.value?.status))
const bothInstallResultClass = computed(() => alertClassForStatus(bothInstallResult.value?.status))

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

function dependencyBadgeLabel(dep) {
  if (dep.shipsMetadata || dep.repositoryMetadata) return 'covered'
  if (dep.repositoryMetadataVersion) return 'partial'
  return 'none'
}

function dependencyBadgeClass(dep) {
  if (dep.shipsMetadata || dep.repositoryMetadata) return 'text-bg-success'
  if (dep.repositoryMetadataVersion) return 'text-bg-warning'
  return 'text-bg-secondary'
}

function scanTime() {
  if (!report.value?.scan?.scannedAt) return ''
  return formatClockTime(report.value.scan.scannedAt)
}

async function loadReport() {
  try {
    const res = await apiFetch('api/graalvm')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load GraalVM readiness report')
  }
}

async function cancelScan() {
  cancellingScan.value = true
  try {
    const res = await apiFetch('api/graalvm/scan/cancel', {method: 'POST'})
    if (res.ok) scanProgress.value = await res.json()
  } catch {
    // The in-flight scan request will still finish and report any outcome.
  }
}

async function pollScanProgress() {
  try {
    const res = await apiFetch('api/graalvm/scan/progress')
    if (res.ok) scanProgress.value = await res.json()
  } catch {
    // Progress is best-effort; the main scan request reports any real failure.
  }
}

function startProgressPolling() {
  stopProgressPolling()
  scanProgress.value = {
    running: true,
    phase: 'starting',
    message: 'Starting GraalVM readiness scan.',
    dependenciesScanned: 0,
    dependenciesTotal: 0
  }
  progressTimer = setInterval(pollScanProgress, 500)
}

function stopProgressPolling() {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
}

async function runScan() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  startProgressPolling()
  try {
    const res = await apiFetch(`api/graalvm/scan?includeDependencies=${includeDependencies.value}`, {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to run GraalVM readiness checks')
  } finally {
    loading.value = false
    cancellingScan.value = false
    stopProgressPolling()
    scanProgress.value = null
  }
}

async function installMetadata() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  installing.value = true
  try {
    const res = await apiFetch('api/graalvm/install', {method: 'POST'})
    installResult.value = await res.json()
  } catch (e) {
    installResult.value = {
      installed: false,
      status: 'ERROR',
      message: describeLoadError(e, 'Unable to install reachability metadata'),
      path: null
    }
  } finally {
    installing.value = false
  }
}

async function installDockerfile() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  installingDockerfile.value = true
  try {
    const res = await apiFetch('api/graalvm/dockerfile/install', {method: 'POST'})
    dockerInstallResult.value = await res.json()
  } catch (e) {
    dockerInstallResult.value = {
      installed: false,
      status: 'ERROR',
      message: describeLoadError(e, 'Unable to write Dockerfile-native'),
      path: null
    }
  } finally {
    installingDockerfile.value = false
  }
}

async function installBoth() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  installingBoth.value = true
  try {
    const res = await apiFetch('api/graalvm/install/all', {method: 'POST'})
    bothInstallResult.value = await res.json()
  } catch (e) {
    bothInstallResult.value = {
      installed: false,
      status: 'ERROR',
      message: describeLoadError(e, 'Unable to write the GraalVM artifacts'),
      metadata: null,
      dockerfile: null
    }
  } finally {
    installingBoth.value = false
  }
}

function showReadOnlyMessage() {
  actionMessage.value = readOnlyReason.value
  setTimeout(() => {
    actionMessage.value = null
  }, 6000)
}

function toggleArtifact(name) {
  openArtifact.value = openArtifact.value === name ? null : name
}

onMounted(loadReport)
onBeforeUnmount(stopProgressPolling)
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
    <div v-if="loading && scanProgress" class="alert alert-primary">
      <div class="d-flex justify-content-between align-items-center gap-3">
        <div>
          <strong>{{ scanProgress.message || 'Running GraalVM readiness scan.' }}</strong>
          <div v-if="scanProgress.dependenciesTotal > 0" class="small">
            {{ scanProgress.dependenciesScanned }} of {{ scanProgress.dependenciesTotal }} dependency lookups checked
          </div>
        </div>
        <div class="d-flex align-items-center gap-2">
          <button
            v-if="scanProgress.phase && scanProgress.phase.startsWith('dependencies')"
            :disabled="cancellingScan"
            class="btn btn-sm btn-outline-primary"
            type="button"
            @click="cancelScan"
          >
            {{ cancellingScan ? 'Cancelling…' : 'Abort dependency check' }}
          </button>
          <span class="badge text-bg-light text-dark">{{ scanProgress.phase }}</span>
        </div>
      </div>
      <div v-if="scanProgress.dependenciesTotal > 0" class="progress mt-2" style="height: 0.5rem">
        <div
          class="progress-bar progress-bar-striped progress-bar-animated"
          role="progressbar"
          :style="{width: progressPercent + '%'}"
          :aria-valuenow="progressPercent"
          aria-valuemin="0"
          aria-valuemax="100"
        ></div>
      </div>
    </div>

    <template v-if="report">
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
          <div class="accordion">
            <div class="accordion-item">
              <h2 class="accordion-header">
                <button
                  :class="['accordion-button', {collapsed: openArtifact !== 'both'}]"
                  :aria-expanded="openArtifact === 'both'"
                  type="button"
                  @click="toggleArtifact('both')"
                >
                  <span class="fw-semibold">All files</span>
                </button>
              </h2>
              <div :class="['accordion-collapse collapse', {show: openArtifact === 'both'}]">
                <div class="accordion-body">
                  <div v-if="!hasScanData" class="text-muted">
                    Run readiness checks to generate and write both GraalVM artifacts in one step.
                  </div>
                  <template v-else>
                    <p class="small text-muted mb-3">
                      Generates the <code>reachability-metadata.json</code> scaffold and a tailored
                      <code>Dockerfile-native</code>, then writes both directly into the project's source tree in a
                      single step — the metadata under <code>{{ report.metadataDirectory }}</code> and the Dockerfile at
                      the project root. Each write is fail-closed and never overwrites a file BootUI did not generate.
                    </p>
                    <div class="d-flex gap-2 mb-3">
                      <SpinnerButton
                        v-if="canWriteBoth"
                        :loading="installingBoth"
                        :disabled="installingBoth || readOnly"
                        class="btn btn-primary btn-sm"
                        type="button"
                        label="Write into project"
                        loading-label="Writing..."
                        @click="installBoth"
                      />
                    </div>
                    <div v-if="bothInstallResult" :class="bothInstallResultClass" class="alert py-2 small mb-0">
                      <div>{{ bothInstallResult.message }}</div>
                      <ul v-if="bothInstallResult.metadata || bothInstallResult.dockerfile" class="mb-0 mt-1 ps-3">
                        <li v-if="bothInstallResult.metadata">
                          <code>reachability-metadata.json</code> — {{ bothInstallResult.metadata.message }}
                        </li>
                        <li v-if="bothInstallResult.dockerfile">
                          <code>Dockerfile-native</code> — {{ bothInstallResult.dockerfile.message }}
                        </li>
                      </ul>
                    </div>
                    <div v-else-if="!canWriteBoth" class="small text-muted mb-0">
                      Direct write unavailable: writing both files requires the application to run from an exploded
                      build (for example <code>mvn spring-boot:run</code> or an IDE) rather than a packaged jar.
                    </div>
                  </template>
                </div>
              </div>
            </div>

            <div class="accordion-item">
              <h2 class="accordion-header">
                <button
                  :class="['accordion-button', {collapsed: openArtifact !== 'metadata'}]"
                  :aria-expanded="openArtifact === 'metadata'"
                  type="button"
                  @click="toggleArtifact('metadata')"
                >
                  <span class="fw-semibold">reachability-metadata.json</span>
                </button>
              </h2>
              <div :class="['accordion-collapse collapse', {show: openArtifact === 'metadata'}]">
                <div class="accordion-body">
                  <div v-if="!hasScanData" class="text-muted">
                    Run readiness checks to derive a metadata scaffold from the application's own classes.
                  </div>
                  <template v-else>
                    <div class="d-flex gap-2 mb-3">
                      <a
                        v-if="canDownloadMetadata"
                        class="btn btn-outline-primary btn-sm"
                        download="reachability-metadata.json"
                        href="api/graalvm/metadata"
                      >
                        <i class="bi bi-download me-1"></i>Download
                      </a>
                      <SpinnerButton
                        v-if="canDownloadMetadata && report.installable"
                        :loading="installing"
                        :disabled="installing || readOnly"
                        :title="report.installPath"
                        class="btn btn-primary btn-sm"
                        type="button"
                        label="Write into project"
                        loading-label="Writing..."
                        @click="installMetadata"
                      />
                    </div>
                    <p class="small text-muted mb-2">
                      A heuristic scaffold seeded from the last scan. Review and complete it with the GraalVM tracing
                      agent, then place it under <code>{{ report.metadataDirectory }}</code
                      >.
                    </p>
                    <div v-if="installResult" :class="installResultClass" class="alert py-2 small mb-2">
                      {{ installResult.message }}
                    </div>
                    <div v-else-if="report.installable && report.installPath" class="small text-muted mb-2">
                      Detected source tree: install writes to <code>{{ report.installPath }}</code
                      >.
                    </div>
                    <div v-else-if="!report.installable && report.installPath" class="small text-muted mb-2">
                      Direct install unavailable: {{ report.installPath }}
                    </div>
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

            <div class="accordion-item">
              <h2 class="accordion-header">
                <button
                  :class="['accordion-button', {collapsed: openArtifact !== 'dockerfile'}]"
                  :aria-expanded="openArtifact === 'dockerfile'"
                  type="button"
                  @click="toggleArtifact('dockerfile')"
                >
                  <span class="fw-semibold">Dockerfile-native</span>
                </button>
              </h2>
              <div :class="['accordion-collapse collapse', {show: openArtifact === 'dockerfile'}]">
                <div class="accordion-body">
                  <div v-if="!hasScanData" class="text-muted">
                    Run readiness checks to generate a native-image Dockerfile tailored to this application.
                  </div>
                  <template v-else>
                    <div class="d-flex gap-2 mb-3">
                      <a
                        v-if="hasScanData"
                        class="btn btn-outline-primary btn-sm"
                        download="Dockerfile-native"
                        href="api/graalvm/dockerfile"
                      >
                        <i class="bi bi-download me-1"></i>Download
                      </a>
                      <SpinnerButton
                        v-if="hasScanData && report.dockerfile && report.dockerfile.installable"
                        :loading="installingDockerfile"
                        :disabled="installingDockerfile || readOnly"
                        :title="report.dockerfile.installPath"
                        class="btn btn-primary btn-sm"
                        type="button"
                        label="Write into project"
                        loading-label="Writing..."
                        @click="installDockerfile"
                      />
                    </div>
                    <p class="small text-muted mb-2">
                      A multi-stage native-image build that compiles the application with GraalVM and packages the
                      resulting executable into a minimal runtime image.
                    </p>
                    <div v-if="dockerInstallResult" :class="dockerInstallResultClass" class="alert py-2 small mb-2">
                      {{ dockerInstallResult.message }}
                    </div>
                    <div
                      v-else-if="report.dockerfile && report.dockerfile.installable && report.dockerfile.installPath"
                      class="small text-muted mb-2"
                    >
                      Detected source tree: write saves to <code>{{ report.dockerfile.installPath }}</code
                      >.
                    </div>
                    <div
                      v-else-if="report.dockerfile && !report.dockerfile.installable && report.dockerfile.installPath"
                      class="small text-muted mb-2"
                    >
                      Direct write unavailable: {{ report.dockerfile.installPath }}
                    </div>
                    <pre
                      v-if="report.dockerfile && report.dockerfile.content"
                      class="bg-body-tertiary border rounded p-2 mb-0 small"
                      style="max-height: 16rem; overflow: auto"
                    ><code>{{ report.dockerfile.content }}</code></pre>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="report.includeDependencies" class="card mb-3">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <span class="fw-semibold">Dependency reachability metadata</span>
          <span class="text-muted small">
            {{ dependenciesWithMetadata }} of {{ report.dependenciesAnalyzed }} dependencies ship bundled metadata
          </span>
        </div>
        <div v-if="!report.dependencies || report.dependencies.length === 0" class="card-body text-muted">
          No third-party dependency JARs were detected on the classpath.
        </div>
        <div v-else class="list-group list-group-flush">
          <div
            v-for="dep in report.dependencies"
            :key="dep.name"
            class="list-group-item d-flex align-items-start gap-2"
          >
            <span :class="dependencyBadgeClass(dep)" class="badge mt-1">
              {{ dependencyBadgeLabel(dep) }}
            </span>
            <div>
              <div class="font-monospace small">{{ dep.name }}</div>
              <div v-if="dep.coordinates" class="small text-muted">{{ dep.coordinates }}</div>
              <div v-if="dep.note" class="small text-muted">{{ dep.note }}</div>
              <div
                v-if="!dep.repositoryMetadata && dep.repositoryMetadataVersion"
                class="small text-warning-emphasis mt-1"
              >
                Repository metadata exists for a different version
                <span v-if="dep.repositoryTestedVersions">({{ dep.repositoryTestedVersions }})</span>.
              </div>
              <div v-if="dep.repositoryUrl || dep.repositoryMetadataUrl" class="small mt-1 d-flex flex-wrap gap-2">
                <a v-if="dep.repositoryUrl" :href="dep.repositoryUrl" target="_blank" rel="noopener noreferrer"
                  >Repository entry</a
                >
                <a
                  v-if="dep.repositoryMetadataUrl"
                  :href="dep.repositoryMetadataUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  >Metadata file</a
                >
              </div>
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
