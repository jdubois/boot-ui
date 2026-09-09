<script setup>
import {actionBusyMessage, getJson, isActionBusyError} from '../api.js'
import {getBootUiApplicationPath} from '../utils/bootUiPath.js'
import {computed, inject, onActivated, onDeactivated, onMounted, reactive, ref, watch} from 'vue'
import {createPanelLookup} from '../utils/panelNavigation.js'
import {describeLoadError} from '../utils/loadError.js'
import {scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {
  advisorAssessment,
  githubSecurityScore,
  isValidSeveritySummary,
  overallScore,
  scoreBandTone
} from '../utils/scannerScore.js'
import PanelHeader from './components/PanelHeader.vue'
import ScannerScoreCard from './components/ScannerScoreCard.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const injectedPanels = inject('panels', null)
const applicationPath = getBootUiApplicationPath()

// Standalone mounts fetch availability; the application shell owns its injected manifest.
const localPanels = ref(null)
const panelsError = ref(null)
const panelLookup = computed(() => createPanelLookup(injectedPanels?.value ?? localPanels.value))

function panelAvailable(id) {
  const panel = panelLookup.value.get(id)
  // Treat unknown panels as available so the dashboard degrades gracefully.
  return !panel || (panel.available !== false && panel.enabled !== false)
}

const platform = computed(() => injectedPanels?.value?.platform ?? localPanels.value?.platform ?? 'spring-boot')

// Resolves a scanner's display title for the active framework: the shared `spring` advisor renders as
// "Quarkus" on Quarkus, mirroring App.vue's platform-aware navigation/header titles.
function displayTitle(def) {
  return def.titleByPlatform?.[platform.value] || def.title
}

// Severity-based scanners share the same {severityCounts, scan.status} contract.
// GitHub renders as the first grid card, so the scanner order below lays out the
// remaining cards into a 3-column grid: Architecture, Memory, REST API / Spring,
// Database, Hibernate, Security / Pentesting, Vulnerabilities.
const scannerDefs = [
  {
    id: 'architecture',
    title: 'Architecture',
    icon: 'bi-diagram-2',
    tone: 'primary',
    to: '/architecture',
    endpoint: 'api/architecture/scan',
    reportEndpoint: 'api/architecture'
  },
  {
    id: 'memory',
    title: 'Memory',
    icon: 'bi-clipboard2-pulse',
    tone: 'warning',
    to: '/memory',
    endpoint: 'api/memory/scan',
    reportEndpoint: 'api/memory'
  },
  {
    id: 'rest-api',
    title: 'REST API',
    icon: 'bi-signpost-split',
    tone: 'primary',
    to: '/rest-api',
    endpoint: 'api/rest-api/scan',
    reportEndpoint: 'api/rest-api'
  },
  {
    id: 'spring',
    title: 'Spring',
    titleByPlatform: {quarkus: 'Quarkus'},
    icon: 'bi-boxes',
    tone: 'info',
    to: '/spring',
    endpoint: 'api/spring/scan',
    reportEndpoint: 'api/spring'
  },
  {
    id: 'database-advisor',
    title: 'Database',
    icon: 'bi-hdd-rack',
    tone: 'info',
    to: '/database-advisor',
    endpoint: 'api/database-advisor/scan',
    reportEndpoint: 'api/database-advisor'
  },
  {
    id: 'hibernate',
    title: 'Hibernate',
    icon: 'bi-database-gear',
    tone: 'info',
    to: '/hibernate',
    endpoint: 'api/hibernate/scan',
    reportEndpoint: 'api/hibernate'
  },
  {
    id: 'security',
    title: 'Security',
    icon: 'bi-shield-check',
    tone: 'success',
    to: '/security',
    endpoint: 'api/security/scan',
    reportEndpoint: 'api/security'
  },
  {
    id: 'pentesting',
    title: 'Pentesting',
    icon: 'bi-shield-exclamation',
    tone: 'warning',
    to: '/pentesting',
    endpoint: 'api/pentesting/scan',
    reportEndpoint: 'api/pentesting'
  },
  {
    id: 'vulnerabilities',
    title: 'Vulnerabilities',
    icon: 'bi-bug',
    tone: 'danger',
    to: '/vulnerabilities',
    endpoint: 'api/vulnerabilities/scan',
    reportEndpoint: 'api/vulnerabilities'
  }
]

function newScannerState() {
  return {
    state: 'idle',
    score: null,
    assessed: false,
    incomplete: false,
    hasReport: false,
    scoreLabel: '',
    severityCounts: [],
    statusLabel: null,
    statusTone: 'secondary',
    error: null,
    warning: null
  }
}

const scanners = reactive(Object.fromEntries(scannerDefs.map((def) => [def.id, newScannerState()])))

// Monotonic request token per scanner so a slower in-flight request (e.g. a POST scan
// started before navigating away) can never overwrite a newer response (e.g. the GET
// refresh that runs when the kept-alive dashboard is re-activated). Plain object, not
// reactive: it is bookkeeping, not rendered state.
const requestTokens = {}
const pendingRefreshes = new Set()

function nextToken(id) {
  requestTokens[id] = (requestTokens[id] || 0) + 1
  return requestTokens[id]
}

function applyReport(def, state, report) {
  const assessment = advisorAssessment(report, {vulnerabilities: def.id === 'vulnerabilities'})
  if (assessment.invalid) throw new Error(assessment.reason)
  const validSummary = isValidSeveritySummary(report?.severityCounts, {vulnerabilities: def.id === 'vulnerabilities'})
  state.hasReport = true
  state.severityCounts = validSummary ? report.severityCounts : []
  state.score = assessment.score
  state.scoreLabel = assessment.score !== null ? 'Scan complete' : assessment.label
  const status = report?.scan?.status
  state.assessed = ['SCANNED', 'PARTIAL'].includes(status) && report.evidence != null
  state.incomplete = assessment.incomplete
  state.statusLabel = assessment.score !== null ? state.scoreLabel : scanStatusLabel(status)
  state.statusTone = assessment.score !== null ? 'text-bg-secondary' : scanStatusBadgeClass(status)
  state.state = status === 'ERROR' ? 'error' : status === 'NOT_SCANNED' ? 'idle' : 'done'
  state.error = status === 'ERROR' ? report.scan.message || 'Scan failed' : null
  state.warning = validSummary ? null : 'The report has an invalid severity summary. Its counts cannot be displayed.'
}

const visibleScanners = computed(() => scannerDefs.filter((def) => panelAvailable(def.id)))

async function runScanner(def) {
  const state = scanners[def.id]
  const token = nextToken(def.id)
  const previousState = state.state
  state.state = 'running'
  state.error = null
  state.warning = null
  try {
    const report = await getJson(def.endpoint, {method: 'POST'})
    if (token !== requestTokens[def.id]) return
    applyReport(def, state, report)
  } catch (e) {
    if (token !== requestTokens[def.id]) return
    if (isActionBusyError(e)) {
      state.state = previousState
      state.warning = actionBusyMessage(e)
    } else {
      state.state = 'error'
      state.error = describeLoadError(e, `Unable to run ${displayTitle(def)}`).message
    }
  } finally {
    if (token === requestTokens[def.id] && pendingRefreshes.delete(def.id)) await refreshScanner(def)
  }
}

// The dashboard is kept alive (App.vue wraps it in <keep-alive include="Overview">), so its
// scores survive navigation. Dismissing/restoring an advisor rule in a panel changes that
// advisor's server-side score, which would otherwise leave the dashboard showing a stale value.
// Discover panel/agent-originated reports too, even when unscoreable.
// Only read endpoints confirmed by the panel manifest.
async function refreshScanner(def) {
  const state = scanners[def.id]
  if (!def.reportEndpoint || !panelLookup.value.has(def.id) || !panelAvailable(def.id)) return
  // A cached GET during a scan still contains the previous report, not a newer assessment.
  if (state.state === 'running') {
    pendingRefreshes.add(def.id)
    return
  }
  const token = nextToken(def.id)
  try {
    const report = await getJson(def.reportEndpoint)
    if (token !== requestTokens[def.id]) return
    applyReport(def, state, report)
  } catch (e) {
    if (token !== requestTokens[def.id]) return
    state.state = 'error'
    state.error = describeLoadError(e, `Unable to refresh ${displayTitle(def)}`).message
  }
}

function refreshScores() {
  for (const def of visibleScanners.value) {
    refreshScanner(def)
  }
}

// GitHub's alert-count heuristic is separate from advisor severity scoring. Refresh remains user-triggered.
const github = reactive({
  state: 'idle',
  score: null,
  hasReport: false,
  connected: false,
  authenticated: false,
  available: true,
  securitySignals: [],
  statusLabel: null,
  statusTone: 'secondary',
  error: null
})

const githubVisible = computed(() => panelAvailable('github'))
const overallContributions = computed(() => {
  const items = visibleScanners.value.map((def) => ({
    id: def.id,
    title: displayTitle(def),
    score: scanners[def.id].score
  }))
  if (githubVisible.value) items.push({id: 'github', title: 'GitHub', score: github.score})
  return items
    .filter(({score}) => Number.isFinite(score) && score >= 0 && score <= 100)
    .sort((a, b) => a.score - b.score)
})
const contributingScores = computed(() => overallContributions.value.map(({score}) => score))
const overall = computed(() => overallScore(contributingScores.value))
const overallBand = computed(() => {
  const tone = scoreBandTone(overall.value)
  return {tone, label: {secondary: 'Not scored', success: 'Good', warning: 'Needs attention', danger: 'At risk'}[tone]}
})

async function connectGithub() {
  github.state = 'running'
  github.error = null
  try {
    const report = await getJson('api/github/refresh', {method: 'POST'})
    const score = githubSecurityScore(report)
    github.available = report.available !== false
    github.connected = report.connected === true
    github.authenticated = report.credential?.authenticated === true
    github.statusLabel = report.status === 'CONNECTED' ? 'Connected' : report.status
    github.statusTone = 'text-bg-secondary'
    github.securitySignals = Array.isArray(report.securitySignals)
      ? report.securitySignals.filter((signal) => signal && typeof signal.label === 'string')
      : []
    github.score = score
    github.hasReport = true
    github.state = 'done'
  } catch (e) {
    github.state = 'error'
    github.error = describeLoadError(e, 'Unable to connect to GitHub').message
  }
}

const assessedCount = computed(() => visibleScanners.value.filter((def) => scanners[def.id].assessed).length)
const failedCount = computed(() => visibleScanners.value.filter((def) => scanners[def.id].state === 'error').length)
const unscannedCount = computed(() => visibleScanners.value.filter((def) => scanners[def.id].state === 'idle').length)
const incompleteCount = computed(() => visibleScanners.value.filter((def) => scanners[def.id].incomplete).length)
const hasActions = computed(() => visibleScanners.value.length > 0 || githubVisible.value)

const anyRunning = computed(
  () => github.state === 'running' || visibleScanners.value.some((def) => scanners[def.id].state === 'running')
)

const retainedSeverities = computed(() => {
  const counts = new Map()
  for (const def of visibleScanners.value) {
    for (const entry of scanners[def.id].severityCounts) {
      const severity = entry.severity.toUpperCase()
      counts.set(severity, (counts.get(severity) || 0) + entry.count)
    }
  }
  return ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO', 'UNKNOWN', 'NONE']
    .filter((severity) => counts.get(severity) > 0)
    .map((severity) => ({severity, count: counts.get(severity)}))
})

// A run-all-scanners nudge toward the MCP Server panel: with the MCP server enabled,
// an AI agent can read these same scan results and act on them. Only surfaced when the
// panel is available, and dismissible so it stays a temporary tip.
const mcpServerVisible = computed(() => panelAvailable('mcp-server'))
const showMcpTip = ref(false)

async function runAll() {
  if (mcpServerVisible.value) showMcpTip.value = true
  const tasks = visibleScanners.value
    .filter((def) => scanners[def.id].state !== 'running')
    .map((def) => runScanner(def))
  if (githubVisible.value && github.state !== 'running') tasks.push(connectGithub())
  await Promise.allSettled(tasks)
}

async function ensurePanels() {
  if (injectedPanels || localPanels.value) return
  panelsError.value = null
  try {
    localPanels.value = await getJson('api/panels')
  } catch (e) {
    panelsError.value = describeLoadError(e, 'Unable to load panel availability').message
  }
}

const active = ref(false)
onMounted(() => {
  active.value = true
  ensurePanels()
})
onActivated(() => (active.value = true))
onDeactivated(() => (active.value = false))
// Mount and initial KeepAlive activation share one refresh, after availability is known.
watch(
  [active, panelLookup],
  ([isActive]) => {
    if (isActive) refreshScores()
  },
  {flush: 'post'}
)
</script>

<template>
  <div class="overview-scores">
    <PanelHeader
      icon="bi-speedometer2"
      title="Overview"
      subtitle="Inspect retained findings and assessment coverage, then open an advisor for evidence and next steps."
      :refreshable="false"
    >
      <template #actions>
        <a class="btn btn-outline-secondary btn-sm" :href="applicationPath">
          <i class="bi bi-house-door me-1"></i>
          Application homepage
        </a>
      </template>
    </PanelHeader>

    <div v-if="panelsError" class="alert alert-danger" role="alert">
      {{ panelsError }}
      <button type="button" class="btn btn-sm btn-outline-danger ms-2" @click="ensurePanels">Retry</button>
    </div>

    <div class="row gx-3 gy-4 mb-4">
      <div class="col-12">
        <div v-if="showMcpTip" class="alert alert-info d-flex align-items-start gap-2 mb-3 mcp-tip" role="alert">
          <i class="bi bi-lightbulb-fill flex-shrink-0 mt-1" aria-hidden="true"></i>
          <div class="flex-grow-1">
            <strong>Tip:</strong>
            Enable the
            <router-link to="/mcp-server" class="alert-link">BootUI MCP Server</router-link>
            to give your AI agent direct access to these scan results — so it can investigate and fix the issues for you
            automatically.
          </div>
          <button
            type="button"
            class="btn-close flex-shrink-0"
            aria-label="Dismiss tip"
            @click="showMcpTip = false"
          ></button>
        </div>
        <div class="card overall-card">
          <div class="card-body">
            <div class="d-flex flex-column flex-xl-row align-items-xl-center gap-4">
              <div class="flex-shrink-0 min-w-0">
                <h2 class="fs-6 text-muted fw-semibold mb-0">Overall score</h2>
                <div v-if="overall !== null" class="d-flex align-items-center gap-3 mt-2">
                  <div
                    :class="['overall-score', 'overall-gauge', `overall-gauge--${overallBand.tone}`]"
                    role="img"
                    :aria-label="`Overall score: ${overall} out of 100 — Average of ${contributingScores.length} ${contributingScores.length === 1 ? 'score' : 'scores'}`"
                  >
                    <span class="overall-gauge__value overview-score-value">{{ overall }}</span>
                    <span class="overall-gauge__max">/ 100</span>
                  </div>
                  <div>
                    <span
                      :class="['overall-band', 'badge', `text-bg-${overallBand.tone}`, 'fs-6']"
                      title="Known-findings score band, not a safety or coverage assessment."
                      >{{ overallBand.label }}</span
                    >
                    <p class="small text-muted mt-2 mb-0">
                      Average of {{ contributingScores.length }}
                      {{ contributingScores.length === 1 ? 'score' : 'scores' }}
                    </p>
                  </div>
                </div>
                <p v-else class="mt-2 mb-0">
                  <strong>Not scored</strong>
                  <span class="text-muted small"> · Run an available scanner to calculate a score.</span>
                </p>
              </div>
              <div v-if="overallContributions.length" class="overall-contributions flex-grow-1 min-w-0">
                <div class="small text-muted mb-2">Points deducted per score</div>
                <ul class="row g-2 list-unstyled mb-0">
                  <li
                    v-for="item in overallContributions"
                    :key="item.id"
                    class="col-sm-6 col-xl-4 d-flex justify-content-between align-items-center small"
                  >
                    <span class="text-muted me-2">{{ item.title }}</span>
                    <span :class="['fw-semibold', 'flex-shrink-0', item.score < 100 ? 'text-danger' : 'text-success']">
                      {{ item.score - 100 }}
                    </span>
                  </li>
                </ul>
              </div>
              <SpinnerButton
                :loading="anyRunning"
                :disabled="anyRunning || !hasActions"
                class="btn btn-primary flex-shrink-0 align-self-start align-self-xl-center ms-xl-auto"
                type="button"
                :label="assessedCount > 0 ? 'Re-run all scanners' : 'Run all scanners'"
                loading-label="Running scanners…"
                @click="runAll"
              />
            </div>
            <div class="overall-assessment d-flex flex-wrap align-items-center gap-2 mt-3 small">
              <span>
                <strong>{{ assessedCount }} of {{ visibleScanners.length }} advisors assessed</strong>
                <span v-if="unscannedCount"> · {{ unscannedCount }} not scanned</span>
                <span v-if="failedCount"> · {{ failedCount }} failed</span>
              </span>
              <div
                v-if="retainedSeverities.length"
                class="d-flex flex-wrap align-items-center gap-2"
                aria-label="Retained advisor severity counts"
              >
                <span class="text-muted">Retained advisor severity counts</span>
                <span
                  v-for="entry in retainedSeverities"
                  :key="entry.severity"
                  :class="[
                    'badge',
                    ['CRITICAL', 'HIGH'].includes(entry.severity)
                      ? 'text-bg-danger'
                      : entry.severity === 'MEDIUM'
                        ? 'text-bg-warning'
                        : 'text-bg-secondary'
                  ]"
                  >{{ entry.count }} {{ entry.severity.toLowerCase() }}</span
                >
              </div>
              <span v-else class="text-muted">
                No retained advisor findings. An unscanned advisor is not a clean result.
              </span>
            </div>
            <p v-if="incompleteCount > 0" class="assessment-summary small text-muted mb-0 mt-2">
              {{ incompleteCount }} {{ incompleteCount === 1 ? 'advisor has' : 'advisors have' }} scan notes. Open a
              panel for details.
            </p>
          </div>
        </div>
      </div>
    </div>

    <div class="row gx-3 gy-4">
      <div v-if="githubVisible" class="col-md-6 col-lg-4">
        <ScannerScoreCard
          title="GitHub"
          icon="bi-github"
          tone="primary"
          to="/github"
          open-label="Open GitHub"
          :state="github.state"
          :status-label="github.statusLabel"
          :status-tone="github.statusTone"
          :error-message="github.error"
        >
          <template #score>
            <template v-if="github.state === 'running'">
              <div class="d-flex align-items-center gap-2 text-muted">
                <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
                <span>Connecting…</span>
              </div>
            </template>
            <template v-else-if="github.state === 'error'">
              <div class="text-danger small">
                <i class="bi bi-exclamation-triangle-fill me-1"></i>{{ github.error }}
              </div>
            </template>
            <div v-if="github.hasReport && ['running', 'error'].includes(github.state)" class="text-muted small my-2">
              Showing the last report.
            </div>
            <div
              v-if="github.score !== null"
              class="mb-2"
              role="img"
              :aria-label="`GitHub security-alert score: ${github.score} out of 100`"
            >
              <span
                :class="['overview-score-value', 'scanner-score', `text-${scoreBandTone(github.score)}-emphasis`]"
                >{{ github.score }}</span
              >
              <span class="text-muted small ms-2">/ 100</span>
              <div class="small text-muted mt-1">Security-alert score · 10 points per alert</div>
            </div>
            <template v-if="github.hasReport">
              <p v-if="github.score === null" class="small mb-2">
                <strong>Not scored</strong> · Requires an authenticated connection and all three security signals
                available.
              </p>
              <div class="small fw-semibold">
                {{
                  !github.available
                    ? 'Unavailable'
                    : !github.connected
                      ? 'Not connected'
                      : github.authenticated
                        ? 'Connected · Authenticated'
                        : 'Connected · Not authenticated'
                }}
              </div>
              <ul v-if="github.securitySignals.length" class="list-unstyled small mt-2 mb-0">
                <li v-for="signal in github.securitySignals" :key="signal.label">
                  {{ signal.label }}:
                  {{
                    signal.status === 'AVAILABLE' && Number.isSafeInteger(signal.count) && signal.count >= 0
                      ? `${signal.count} open`
                      : 'Unavailable'
                  }}
                </li>
              </ul>
              <p v-else class="text-muted small mt-2 mb-0">Security signals unavailable.</p>
            </template>
            <template v-else-if="github.state === 'idle'">
              <div class="text-muted small">Connect to GitHub to load repository security signals.</div>
            </template>
          </template>

          <template #actions>
            <SpinnerButton
              :loading="github.state === 'running'"
              class="btn btn-sm btn-primary"
              type="button"
              icon="bi-github"
              :disabled="github.state === 'running'"
              @click="connectGithub"
            >
              {{ github.connected && github.authenticated ? 'Refresh' : 'Connect to GitHub' }}
            </SpinnerButton>
            <router-link to="/github" class="btn btn-sm btn-outline-secondary ms-auto">
              Open GitHub<i class="bi bi-arrow-right-short"></i>
            </router-link>
          </template>
        </ScannerScoreCard>
      </div>

      <div v-for="def in visibleScanners" :key="def.id" class="col-md-6 col-lg-4">
        <ScannerScoreCard
          :title="displayTitle(def)"
          :icon="def.icon"
          :tone="def.tone"
          :to="def.to"
          :state="scanners[def.id].state"
          :score="scanners[def.id].score"
          :has-report="scanners[def.id].hasReport"
          :score-label="scanners[def.id].scoreLabel"
          :incomplete="scanners[def.id].incomplete"
          :severity-counts="scanners[def.id].severityCounts"
          :status-label="scanners[def.id].statusLabel"
          :status-tone="scanners[def.id].statusTone"
          :error-message="scanners[def.id].error"
          :warning-message="scanners[def.id].warning"
          @run="runScanner(def)"
        />
      </div>

      <div v-if="!hasActions" class="col-12">
        <div class="alert alert-secondary mb-0">No technology scanners were detected for this application.</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
:global(:root:not([data-bootui-theme='dark'])) .overview-scores {
  --bs-success-text-emphasis: var(--bootui-green);
  --bs-warning-text-emphasis: var(--bootui-warning-text);
  --bs-danger-text-emphasis: var(--bootui-danger);
}

.overall-gauge {
  align-items: center;
  border: 0.5rem solid;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  height: 6.5rem;
  justify-content: center;
  width: 6.5rem;
}

.overall-gauge__max {
  color: var(--bootui-text-muted);
  font-size: 0.72rem;
}

.overall-gauge--success {
  border-color: var(--bootui-green);
  color: var(--bs-success-text-emphasis);
}

.overall-gauge--warning {
  border-color: var(--bootui-warning-text);
  color: var(--bs-warning-text-emphasis);
}

.overall-gauge--danger {
  border-color: var(--bootui-danger);
  color: var(--bs-danger-text-emphasis);
}

.overall-contributions {
  min-width: 0;
}

.overview-score-value {
  font-family: var(--bs-font-monospace);
  font-size: 2.1rem;
  font-weight: 850;
  line-height: 1;
  color: var(--bootui-text);
}

.overall-gauge__value {
  color: inherit;
}
</style>
