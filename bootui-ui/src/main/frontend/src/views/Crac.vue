<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch, getJson} from '../api.js'
import {formatClockTime} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import PanelHeader from './components/PanelHeader.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const installingDockerfile = ref(false)
const dockerInstallResult = ref(null)
const installingEntrypoint = ref(false)
const entrypointInstallResult = ref(null)
const installingBoth = ref(false)
const bothInstallResult = ref(null)
const openArtifact = ref('both')

const severityClasses = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

const runtime = computed(() => report.value?.runtime || null)

const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

const generatedFiles = computed(() => report.value?.generatedFiles || [])

const dockerfile = computed(() => generatedFiles.value.find((file) => file.name === 'Dockerfile-crac') || null)

const entrypoint = computed(() => generatedFiles.value.find((file) => file.name === 'checkpoint-and-run.sh') || null)

const hasGeneratedFiles = computed(() => generatedFiles.value.length > 0)

const canWriteBoth = computed(() => Boolean(dockerfile.value?.installable && entrypoint.value?.installable))

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

const dockerInstallResultClass = computed(() => alertClassForStatus(dockerInstallResult.value?.status))
const entrypointInstallResultClass = computed(() => alertClassForStatus(entrypointInstallResult.value?.status))
const bothInstallResultClass = computed(() => alertClassForStatus(bothInstallResult.value?.status))

const maxSeverityCount = computed(() => {
  if (!report.value?.severityCounts?.length) return 1
  return Math.max(1, ...report.value.severityCounts.map((count) => count.count))
})

const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const severityFilter = ref([])
const categoryFilter = ref('')
const searchText = ref('')

const allFindings = computed(() => report.value?.findings || [])

const availableSeverities = computed(() => {
  const present = new Set(allFindings.value.map((finding) => finding.severity))
  const known = severityOrder.filter((severity) => present.has(severity))
  const extra = [...present].filter((severity) => !severityOrder.includes(severity)).sort()
  return [...known, ...extra]
})

const availableCategories = computed(() =>
  [...new Set(allFindings.value.map((finding) => finding.category).filter(Boolean))].sort()
)

const visibleFindings = computed(() => {
  const term = searchText.value.trim().toLowerCase()
  return allFindings.value.filter((finding) => {
    if (severityFilter.value.length && !severityFilter.value.includes(finding.severity)) return false
    if (categoryFilter.value && finding.category !== categoryFilter.value) return false
    if (term) {
      const haystack = [
        finding.name,
        finding.description,
        finding.id,
        finding.category,
        ...(finding.sampleOccurrences || [])
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      if (!haystack.includes(term)) return false
    }
    return true
  })
})

const filtersActive = computed(
  () => severityFilter.value.length > 0 || categoryFilter.value !== '' || searchText.value.trim() !== ''
)

function toggleSeverity(severity) {
  if (severityFilter.value.includes(severity)) {
    severityFilter.value = severityFilter.value.filter((value) => value !== severity)
  } else {
    severityFilter.value = [...severityFilter.value, severity]
  }
}

function severityChipCount(severity) {
  return allFindings.value.filter((finding) => finding.severity === severity).length
}

function clearFilters() {
  severityFilter.value = []
  categoryFilter.value = ''
  searchText.value = ''
}

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
    report.value = await getJson('api/crac')
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
    report.value = await getJson('api/crac/scan', {method: 'POST'})
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

async function installDockerfile() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  const ok = await confirm({
    title: 'Write Dockerfile-crac?',
    message:
      'Write a CRaC-enabled Dockerfile into your project. An existing Dockerfile-crac at the target path is overwritten.',
    confirmLabel: 'Write file',
    danger: true
  })
  if (!ok) return
  installingDockerfile.value = true
  try {
    const res = await apiFetch('api/crac/dockerfile/install', {method: 'POST'})
    dockerInstallResult.value = await res.json()
  } catch (e) {
    dockerInstallResult.value = {
      installed: false,
      status: 'ERROR',
      message: describeLoadError(e, 'Unable to write Dockerfile-crac'),
      path: null
    }
  } finally {
    installingDockerfile.value = false
  }
}

async function installEntrypoint() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  const ok = await confirm({
    title: 'Write entrypoint script?',
    message:
      'Write the checkpoint-and-run.sh entrypoint script into your project. An existing script at the target path is overwritten.',
    confirmLabel: 'Write file',
    danger: true
  })
  if (!ok) return
  installingEntrypoint.value = true
  try {
    const res = await apiFetch('api/crac/entrypoint/install', {method: 'POST'})
    entrypointInstallResult.value = await res.json()
  } catch (e) {
    entrypointInstallResult.value = {
      installed: false,
      status: 'ERROR',
      message: describeLoadError(e, 'Unable to write checkpoint-and-run.sh'),
      path: null
    }
  } finally {
    installingEntrypoint.value = false
  }
}

async function installBoth() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  const ok = await confirm({
    title: 'Write CRaC artifacts?',
    message:
      'Write both the CRaC Dockerfile and the checkpoint-and-run.sh entrypoint into your project. Existing files at the target paths are overwritten.',
    confirmLabel: 'Write files',
    danger: true
  })
  if (!ok) return
  installingBoth.value = true
  try {
    const res = await apiFetch('api/crac/install/all', {method: 'POST'})
    bothInstallResult.value = await res.json()
  } catch (e) {
    bothInstallResult.value = {
      installed: false,
      status: 'ERROR',
      message: describeLoadError(e, 'Unable to write the CRaC container assets'),
      dockerfile: null,
      entrypoint: null
    }
  } finally {
    installingBoth.value = false
  }
}

function toggleArtifact(name) {
  openArtifact.value = openArtifact.value === name ? null : name
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
          <span class="text-muted small font-monospace" :title="`JVM: ${runtime.jvmName}`" aria-label="Running JVM">{{
            runtime.jvmName
          }}</span>
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
          <div v-if="runtime.restoreCaveats && runtime.restoreCaveats.length" class="mt-3">
            <div class="small fw-semibold">Checkpoint &amp; restore caveats</div>
            <ul class="small mb-0">
              <li v-for="(caveat, index) in runtime.restoreCaveats" :key="index">{{ caveat }}</li>
            </ul>
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

      <div v-if="hasGeneratedFiles" class="card mb-3">
        <div class="card-header fw-semibold"><i class="bi bi-box-seam me-1"></i>Container assets</div>
        <div class="card-body">
          <p class="small text-muted mb-3">
            Generate a CRaC-enabled container for this application: a multi-stage
            <code>Dockerfile-crac</code> that builds with a plain JDK and runs on a CRaC-enabled BellSoft Liberica JDK,
            plus the <code>checkpoint-and-run.sh</code> entrypoint it relies on. Download them, or write them directly
            into the project root when running from an exploded build. Each write is fail-closed and never overwrites a
            file BootUI did not generate.
          </p>
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
                  <p class="small text-muted mb-3">
                    Writes both <code>Dockerfile-crac</code> and <code>checkpoint-and-run.sh</code> into the project
                    root in a single step.
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
                    <ul v-if="bothInstallResult.dockerfile || bothInstallResult.entrypoint" class="mb-0 mt-1 ps-3">
                      <li v-if="bothInstallResult.dockerfile">
                        <code>Dockerfile-crac</code> — {{ bothInstallResult.dockerfile.message }}
                      </li>
                      <li v-if="bothInstallResult.entrypoint">
                        <code>checkpoint-and-run.sh</code> — {{ bothInstallResult.entrypoint.message }}
                      </li>
                    </ul>
                  </div>
                  <div v-else-if="!canWriteBoth" class="small text-muted mb-0">
                    Direct write unavailable: writing both files requires the application to run from an exploded build
                    (for example <code>mvn spring-boot:run</code> or an IDE) rather than a packaged jar.
                  </div>
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
                  <span class="fw-semibold">Dockerfile-crac</span>
                </button>
              </h2>
              <div :class="['accordion-collapse collapse', {show: openArtifact === 'dockerfile'}]">
                <div class="accordion-body">
                  <div class="d-flex gap-2 mb-3">
                    <a class="btn btn-outline-primary btn-sm" download="Dockerfile-crac" href="api/crac/dockerfile">
                      <i class="bi bi-download me-1"></i>Download
                    </a>
                    <SpinnerButton
                      v-if="dockerfile && dockerfile.installable"
                      :loading="installingDockerfile"
                      :disabled="installingDockerfile || readOnly"
                      :title="dockerfile.installPath"
                      class="btn btn-primary btn-sm"
                      type="button"
                      label="Write into project"
                      loading-label="Writing..."
                      @click="installDockerfile"
                    />
                  </div>
                  <p class="small text-muted mb-2">
                    A multi-stage CRaC build: it packages the app with a plain JDK and runs it on a CRaC-enabled JDK,
                    taking a checkpoint on the first start and restoring it afterwards.
                  </p>
                  <div v-if="dockerInstallResult" :class="dockerInstallResultClass" class="alert py-2 small mb-2">
                    {{ dockerInstallResult.message }}
                  </div>
                  <div
                    v-else-if="dockerfile && dockerfile.installable && dockerfile.installPath"
                    class="small text-muted mb-2"
                  >
                    Detected source tree: write saves to <code>{{ dockerfile.installPath }}</code
                    >.
                  </div>
                  <div
                    v-else-if="dockerfile && !dockerfile.installable && dockerfile.installPath"
                    class="small text-muted mb-2"
                  >
                    Direct write unavailable: {{ dockerfile.installPath }}
                  </div>
                  <pre
                    v-if="dockerfile && dockerfile.content"
                    class="bg-body-tertiary border rounded p-2 mb-0 small"
                    style="max-height: 16rem; overflow: auto"
                  ><code>{{ dockerfile.content }}</code></pre>
                </div>
              </div>
            </div>

            <div class="accordion-item">
              <h2 class="accordion-header">
                <button
                  :class="['accordion-button', {collapsed: openArtifact !== 'entrypoint'}]"
                  :aria-expanded="openArtifact === 'entrypoint'"
                  type="button"
                  @click="toggleArtifact('entrypoint')"
                >
                  <span class="fw-semibold">checkpoint-and-run.sh</span>
                </button>
              </h2>
              <div :class="['accordion-collapse collapse', {show: openArtifact === 'entrypoint'}]">
                <div class="accordion-body">
                  <div class="d-flex gap-2 mb-3">
                    <a
                      class="btn btn-outline-primary btn-sm"
                      download="checkpoint-and-run.sh"
                      href="api/crac/entrypoint"
                    >
                      <i class="bi bi-download me-1"></i>Download
                    </a>
                    <SpinnerButton
                      v-if="entrypoint && entrypoint.installable"
                      :loading="installingEntrypoint"
                      :disabled="installingEntrypoint || readOnly"
                      :title="entrypoint.installPath"
                      class="btn btn-primary btn-sm"
                      type="button"
                      label="Write into project"
                      loading-label="Writing..."
                      @click="installEntrypoint"
                    />
                  </div>
                  <p class="small text-muted mb-2">
                    The container entrypoint referenced by <code>Dockerfile-crac</code>. It takes a CRaC checkpoint via
                    <code>spring.context.checkpoint=onRefresh</code> on the first start and restores it on later starts.
                  </p>
                  <div
                    v-if="entrypointInstallResult"
                    :class="entrypointInstallResultClass"
                    class="alert py-2 small mb-2"
                  >
                    {{ entrypointInstallResult.message }}
                  </div>
                  <div
                    v-else-if="entrypoint && entrypoint.installable && entrypoint.installPath"
                    class="small text-muted mb-2"
                  >
                    Detected source tree: write saves to <code>{{ entrypoint.installPath }}</code
                    >.
                  </div>
                  <div
                    v-else-if="entrypoint && !entrypoint.installable && entrypoint.installPath"
                    class="small text-muted mb-2"
                  >
                    Direct write unavailable: {{ entrypoint.installPath }}
                  </div>
                  <pre
                    v-if="entrypoint && entrypoint.content"
                    class="bg-body-tertiary border rounded p-2 mb-0 small"
                    style="max-height: 16rem; overflow: auto"
                  ><code>{{ entrypoint.content }}</code></pre>
                </div>
              </div>
            </div>
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
                <template v-if="filtersActive"
                  >Showing {{ visibleFindings.length }} of {{ report.findingsFound }}
                  {{ pluralize(report.findingsFound, 'concern') }}</template
                >
                <template v-else
                  >{{ report.findingsFound }} {{ pluralize(report.findingsFound, 'concern') }}, sorted by
                  importance</template
                >
              </template>
              <template v-else>{{ report.findingsFound }} concern(s) to review</template>
            </div>
          </div>
          <span v-if="hasScanData && report.findingsFound === 0" class="badge text-bg-success">No concerns</span>
        </div>
        <div
          v-if="hasScanData && report.findingsFound > 0"
          class="card-header d-flex flex-wrap align-items-center gap-2 border-top"
        >
          <span class="small fw-semibold text-muted me-1">Filter</span>
          <div class="btn-group btn-group-sm" role="group" aria-label="Filter concerns by severity">
            <button
              v-for="sev in availableSeverities"
              :key="sev"
              type="button"
              class="btn"
              :class="severityFilter.includes(sev) ? severityClass(sev) : 'btn-outline-secondary'"
              :aria-pressed="severityFilter.includes(sev)"
              @click="toggleSeverity(sev)"
            >
              {{ sev }} <span class="opacity-75">{{ severityChipCount(sev) }}</span>
            </button>
          </div>
          <select v-model="categoryFilter" class="form-select form-select-sm w-auto" aria-label="Filter by category">
            <option value="">All categories</option>
            <option v-for="cat in availableCategories" :key="cat" :value="cat">{{ cat }}</option>
          </select>
          <input
            v-model="searchText"
            type="search"
            class="form-control form-control-sm w-auto"
            placeholder="Search concerns"
            aria-label="Search concerns"
          />
          <button
            v-if="filtersActive"
            type="button"
            class="btn btn-sm btn-link text-decoration-none ms-auto"
            @click="clearFilters"
          >
            Clear filters
          </button>
        </div>
        <div v-if="visibleFindings.length === 0" class="card-body text-center text-muted py-5">
          <template v-if="filtersActive">
            <i class="bi bi-funnel fs-2 d-block mb-2"></i>
            <div class="fw-semibold text-body">No concerns match the active filters</div>
            <button type="button" class="btn btn-sm btn-outline-secondary mt-2" @click="clearFilters">
              Clear filters
            </button>
          </template>
          <template v-else>
            <i class="bi bi-camera fs-2 d-block mb-2"></i>
            <div class="fw-semibold text-body">{{ emptyFindingsTitle }}</div>
            <div>An actual checkpoint/restore run on a CRaC-enabled JDK remains the best way to verify readiness.</div>
          </template>
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
