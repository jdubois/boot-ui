<script setup>
import {getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {formatClockTime} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {useDismissedRules} from '../utils/useDismissedRules.js'
import PanelHeader from './components/PanelHeader.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import AdvisorSummary from './components/AdvisorSummary.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const data = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const search = ref('')
const vulnerableOnly = ref(false)

const {dismissLoading, dismiss, restore} = useDismissedRules(loadDependencies)

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
  // Dismissed vulnerabilities sink to the bottom, mirroring the server's VULNERABILITY_ORDER
  // (DependencyReports) so re-sorting client-side never undoes the dismissed-last ordering.
  const dismissed = (left.dismissed ? 1 : 0) - (right.dismissed ? 1 : 0)
  if (dismissed !== 0) return dismissed
  const severity = severityRank(left.severity) - severityRank(right.severity)
  if (severity !== 0) return severity
  return compareText(left.id, right.id)
}

function sortedVulnerabilities(vulnerabilities) {
  return [...(vulnerabilities || [])].sort(compareVulnerabilities)
}

// The composite key persisted in the shared DismissedRulesStore, matching the engine's
// DependencyReports.dismissalKey(vulnerabilityId, packageName) format exactly.
function dismissalKey(vulnerabilityId, packageName) {
  return `${vulnerabilityId}::${packageName}`
}

function toggleDismiss(dependency, vulnerability) {
  const key = dismissalKey(vulnerability.id, dependency.packageName)
  return vulnerability.dismissed ? restore(key) : dismiss(key)
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

const hasScanData = computed(() => hasScanResult(data.value?.scan?.status))

function severityClass(severity) {
  return severityClasses[severity] || 'text-bg-light'
}

function severityWidth(count) {
  if (count === 0) return '0%'
  return `${Math.max(3, (count / maxSeverityCount.value) * 100)}%`
}

function scanTime() {
  if (!data.value?.scan?.scannedAt) return ''
  return formatClockTime(data.value.scan.scannedAt)
}

async function loadDependencies() {
  try {
    data.value = await getJson('api/vulnerabilities')
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load dependencies')
  }
}

async function scanDependencies() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    data.value = await getJson('api/vulnerabilities/scan', {method: 'POST'})
    if (data.value.vulnerable > 0) {
      vulnerableOnly.value = true
    }
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to scan dependencies')
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
    <PanelHeader
      icon="bi-bug"
      title="Vulnerabilities"
      subtitle="Inspect runtime Maven JARs and scan them for known dependency vulnerabilities."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <SpinnerButton
          :loading="loading"
          :disabled="loading || readOnly || data?.scanningEnabled === false"
          class="btn btn-primary"
          type="button"
          label="Scan with OSV.dev"
          @click="scanDependencies"
        />
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>

    <template v-if="data">
      <div class="alert alert-info">
        <strong>On-demand external scan.</strong>
        BootUI only sends dependency package names and versions to OSV.dev when you click Scan.
        <span v-if="readOnly">Scanning is read-only. {{ readOnlyReason }}</span>
        <span v-if="data.scanningEnabled === false">Scanning is disabled by configuration.</span>
      </div>

      <AdvisorSummary
        :score="null"
        :scan-status-label="scanStatusLabel(data.scan.status)"
        :scan-status-class="scanStatusBadgeClass(data.scan.status)"
        :scan-time="scanTime()"
        :metrics="[
          {label: 'Dependencies', value: data.total},
          {label: 'Vulnerable', value: data.vulnerable},
          {label: 'Scanner', value: data.scan.scanner, hint: data.scan.message}
        ]"
      />

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
                  <span v-if="dependency.vulnerabilities.length === 0" class="text-muted">None found</span>
                  <div v-else class="vulnerability-list">
                    <div
                      v-for="vulnerability in sortedVulnerabilities(dependency.vulnerabilities)"
                      :key="vulnerability.id"
                      :class="{'opacity-50': vulnerability.dismissed}"
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
                        <span v-if="vulnerability.dismissed" class="badge text-bg-light border">Dismissed</span>
                        <button
                          :disabled="dismissLoading"
                          :title="vulnerability.dismissed ? 'Restore this vulnerability' : 'Dismiss this vulnerability'"
                          class="btn btn-sm btn-outline-secondary ms-auto"
                          type="button"
                          @click="toggleDismiss(dependency, vulnerability)"
                        >
                          <i :class="vulnerability.dismissed ? 'bi-eye' : 'bi-eye-slash'" class="bi me-1"></i>
                          {{ vulnerability.dismissed ? 'Restore' : 'Dismiss' }}
                        </button>
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
