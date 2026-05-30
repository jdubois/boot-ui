<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch} from '../api.js'
import {panelProps, usePanelState} from '../utils/panelState.js'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const data = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const search = ref('')
const vulnerableOnly = ref(false)

const severityClasses = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-warning',
  MEDIUM: 'text-bg-info',
  LOW: 'text-bg-secondary',
  UNKNOWN: 'text-bg-light',
  NONE: 'text-bg-success'
}

const severityRanks = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
  UNKNOWN: 4,
  NONE: 5
}

function severityRank(severity) {
  return severityRanks[severity] ?? 6
}

function compareText(left, right) {
  const leftText = (left || '').toString()
  const rightText = (right || '').toString()
  const leftNormalized = leftText.toLowerCase()
  const rightNormalized = rightText.toLowerCase()
  if (leftNormalized < rightNormalized) return -1
  if (leftNormalized > rightNormalized) return 1
  if (leftText < rightText) return -1
  if (leftText > rightText) return 1
  return 0
}

function compareDependencies(left, right) {
  const severity = severityRank(left.highestSeverity) - severityRank(right.highestSeverity)
  if (severity !== 0) return severity
  const packageName = compareText(left.packageName, right.packageName)
  if (packageName !== 0) return packageName
  return compareText(left.version, right.version)
}

function compareVulnerabilities(left, right) {
  const severity = severityRank(left.severity) - severityRank(right.severity)
  if (severity !== 0) return severity
  return compareText(left.id, right.id)
}

function sortedVulnerabilities(vulnerabilities) {
  return [...(vulnerabilities || [])].sort(compareVulnerabilities)
}

const filteredDependencies = computed(() => {
  if (!data.value) return []
  const q = search.value.trim().toLowerCase()
  return data.value.dependencies
    .filter((dependency) => {
      const matchesSearch =
        !q || dependency.packageName.toLowerCase().includes(q) || dependency.version.toLowerCase().includes(q)
      const matchesVulnerable = !vulnerableOnly.value || dependency.vulnerabilityCount > 0
      return matchesSearch && matchesVulnerable
    })
    .sort(compareDependencies)
})

const maxSeverityCount = computed(() => {
  if (!data.value?.severityCounts?.length) return 1
  return Math.max(1, ...data.value.severityCounts.map((count) => count.count))
})

const hasScanData = computed(() => data.value?.scan?.status && data.value.scan.status !== 'NOT_SCANNED')

function severityClass(severity) {
  return severityClasses[severity] || 'text-bg-light'
}

function severityWidth(count) {
  if (count === 0) return '0%'
  return `${Math.max(3, (count / maxSeverityCount.value) * 100)}%`
}

function scanTime() {
  if (!data.value?.scan?.scannedAt) return ''
  return new Date(data.value.scan.scannedAt).toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

async function loadDependencies() {
  try {
    const res = await fetch('api/dependencies')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = e.message
  }
}

async function scanDependencies() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/dependencies/scan', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    if (data.value.vulnerable > 0) {
      vulnerableOnly.value = true
    }
    error.value = null
  } catch (e) {
    error.value = e.message
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

onMounted(loadDependencies)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="h4 mb-1"><i class="bi bi-bug me-2"></i>Vulnerabilities</h2>
        <p class="text-muted mb-0">Inspect runtime Maven JARs and scan them for known dependency vulnerabilities.</p>
      </div>
      <button
        :disabled="loading || readOnly || data?.scanningEnabled === false"
        class="btn btn-primary"
        type="button"
        @click="scanDependencies"
      >
        <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
        Scan with OSV.dev
      </button>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <template v-if="data">
      <div class="alert alert-info">
        <strong>On-demand external scan.</strong>
        BootUI only sends dependency package names and versions to OSV.dev when you click Scan.
        <span v-if="readOnly">Scanning is read-only. {{ readOnlyReason }}</span>
        <span v-if="data.scanningEnabled === false">Scanning is disabled by configuration.</span>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Dependencies</div>
              <div class="display-6">{{ data.total }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Vulnerable</div>
              <div class="display-6">{{ data.vulnerable }}</div>
            </div>
          </div>
        </div>
        <div class="col-md-6">
          <div class="card h-100">
            <div class="card-body">
              <div class="d-flex justify-content-between align-items-start gap-2 mb-2">
                <div>
                  <div class="text-muted small">Scan status</div>
                  <div class="fw-semibold">{{ data.scan.status }}</div>
                </div>
                <span class="badge text-bg-light">{{ data.scan.scanner }}</span>
              </div>
              <div class="small text-muted">{{ data.scan.message }}</div>
              <div v-if="scanTime()" class="small text-muted mt-1">Scanned at {{ scanTime() }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="card mb-3">
        <div class="card-header fw-semibold">Severity breakdown</div>
        <div class="card-body">
          <div v-if="!hasScanData" class="text-center text-muted py-4">
            <i class="bi bi-search fs-2 d-block mb-2"></i>
            <div class="fw-semibold text-body">No vulnerability scan data yet</div>
            <div>Run <strong>Scan with OSV.dev</strong> to populate the severity breakdown.</div>
          </div>
          <div v-for="item in data.severityCounts" v-else :key="item.severity" class="row align-items-center g-2 mb-2">
            <div class="col-3 col-md-2">
              <span :class="severityClass(item.severity)" class="badge">{{ item.severity }}</span>
            </div>
            <div class="col">
              <div :aria-label="`${item.severity} vulnerabilities: ${item.count}`" class="progress" role="img">
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
        <div class="card-header">
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
            <div>
              <div class="fw-semibold">Runtime JAR dependencies</div>
              <div class="text-muted small">{{ filteredDependencies.length }} of {{ data.total }} dependencies</div>
            </div>
            <div class="d-flex flex-wrap gap-2">
              <input
                v-model="search"
                class="form-control form-control-sm dependency-search"
                placeholder="Search group, artifact, or version"
              />
              <div class="form-check form-switch">
                <input id="vulnerableOnly" v-model="vulnerableOnly" class="form-check-input" type="checkbox" />
                <label class="form-check-label small" for="vulnerableOnly">Vulnerable only</label>
              </div>
            </div>
          </div>
        </div>
        <div class="table-responsive">
          <table class="table table-sm align-middle mb-0">
            <thead>
              <tr>
                <th>Dependency</th>
                <th>Version</th>
                <th>Risk</th>
                <th>Advisories</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="dependency in filteredDependencies" :key="`${dependency.packageName}:${dependency.version}`">
                <td>
                  <code>{{ dependency.packageName }}</code>
                </td>
                <td>{{ dependency.version }}</td>
                <td>
                  <span :class="severityClass(dependency.highestSeverity)" class="badge">
                    {{ dependency.highestSeverity }}
                  </span>
                </td>
                <td>
                  <span v-if="dependency.vulnerabilityCount === 0" class="text-muted">None found</span>
                  <div v-else class="vulnerability-list">
                    <div
                      v-for="vulnerability in sortedVulnerabilities(dependency.vulnerabilities)"
                      :key="vulnerability.id"
                      class="mb-2"
                    >
                      <div class="d-flex flex-wrap align-items-center gap-2">
                        <span :class="severityClass(vulnerability.severity)" class="badge">{{
                          vulnerability.severity
                        }}</span>
                        <a
                          v-if="vulnerability.references.length"
                          :href="vulnerability.references[0]"
                          rel="noreferrer"
                          target="_blank"
                        >
                          {{ vulnerability.id }}
                        </a>
                        <span v-else class="fw-semibold">{{ vulnerability.id }}</span>
                        <span v-if="vulnerability.fixedVersions.length" class="small text-muted">
                          fixed in {{ vulnerability.fixedVersions.join(', ') }}
                        </span>
                      </div>
                      <div class="small">
                        {{ vulnerability.summary || vulnerability.details || 'No advisory summary available.' }}
                      </div>
                      <div v-if="vulnerability.aliases.length" class="small text-muted">
                        {{ vulnerability.aliases.join(', ') }}
                      </div>
                    </div>
                  </div>
                </td>
              </tr>
              <tr v-if="filteredDependencies.length === 0">
                <td class="text-muted text-center py-4" colspan="4">No dependencies match the current filters.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dependency-search {
  min-width: 18rem;
}

.vulnerability-list {
  max-width: 48rem;
}
</style>
