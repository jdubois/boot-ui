<script setup>
import {apiFetch} from '../api.js'
import {computed, inject, onMounted, reactive, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {scanStatusBadgeClass, scanStatusLabel} from '../utils/scanStatus.js'
import {overallScore, scoreBandLabel, scoreBandTone, scoreFromSeverityCounts} from '../utils/scannerScore.js'
import ScannerScoreCard from './components/ScannerScoreCard.vue'
import OverviewHealthCard from './components/OverviewHealthCard.vue'
import OverviewMemoryCard from './components/OverviewMemoryCard.vue'

const injectedPanels = inject('panels', null)
const githubProjectUrl = 'https://github.com/jdubois/boot-ui'

// Locally fetched panel availability when the shell has not provided it yet.
const localPanels = ref(null)
const panelLookup = computed(() => {
  const source = injectedPanels?.value?.panels ?? localPanels.value?.panels ?? []
  return new Map(source.map((panel) => [panel.id, panel]))
})

function panelAvailable(id) {
  const panel = panelLookup.value.get(id)
  // Treat unknown panels as available so the dashboard degrades gracefully.
  return !panel || panel.available !== false
}

// Severity-based scanners share the same {severityCounts, scan.status} contract.
const scannerDefs = [
  {
    id: 'architecture',
    title: 'Architecture',
    icon: 'bi-diagram-2',
    tone: 'primary',
    to: '/architecture',
    endpoint: 'api/architecture/scan'
  },
  {
    id: 'rest-advisor',
    title: 'REST API Advisor',
    icon: 'bi-signpost-split',
    tone: 'primary',
    to: '/rest-advisor',
    endpoint: 'api/rest-advisor/scan'
  },
  {
    id: 'hibernate-advisor',
    title: 'Hibernate Advisor',
    icon: 'bi-database-gear',
    tone: 'info',
    to: '/hibernate-advisor',
    endpoint: 'api/hibernate-advisor/scan'
  },
  {
    id: 'security-advisor',
    title: 'Security Advisor',
    icon: 'bi-shield-check',
    tone: 'success',
    to: '/security-advisor',
    endpoint: 'api/security-advisor/scan'
  },
  {
    id: 'vulnerabilities',
    title: 'Vulnerabilities',
    icon: 'bi-bug',
    tone: 'danger',
    to: '/vulnerabilities',
    endpoint: 'api/dependencies/scan'
  },
  {
    id: 'pentest',
    title: 'Pentesting',
    icon: 'bi-shield-exclamation',
    tone: 'warning',
    to: '/pentest',
    endpoint: 'api/pentest/scan'
  }
]

function newScannerState() {
  return {state: 'idle', score: null, severityCounts: [], statusLabel: null, statusTone: 'secondary', error: null}
}

const scanners = reactive(Object.fromEntries(scannerDefs.map((def) => [def.id, newScannerState()])))

const visibleScanners = computed(() => scannerDefs.filter((def) => panelAvailable(def.id)))

async function runScanner(def) {
  const state = scanners[def.id]
  state.state = 'running'
  state.error = null
  try {
    const res = await apiFetch(def.endpoint, {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const report = await res.json()
    state.severityCounts = report.severityCounts ?? []
    state.score = scoreFromSeverityCounts(state.severityCounts)
    const status = report.scan?.status
    state.statusLabel = scanStatusLabel(status)
    state.statusTone = scanStatusBadgeClass(status)
    state.state = 'done'
  } catch (e) {
    state.state = 'error'
    state.error = describeLoadError(e, `Unable to run ${def.title}`).message
  }
}

// GitHub is not a severity scanner; it is included in the overall score only
// when the repository is available and the credential is authenticated.
const github = reactive({
  state: 'idle',
  connected: false,
  authenticated: false,
  available: true,
  score: null,
  severityCounts: [],
  statusLabel: null,
  statusTone: 'secondary',
  error: null
})

const githubVisible = computed(() => panelAvailable('github'))

function githubSeverityCounts(report) {
  const alerts = (report.securitySignals ?? [])
    .filter((signal) => signal.status === 'AVAILABLE')
    .reduce((total, signal) => total + (Number(signal.count) || 0), 0)
  return alerts > 0 ? [{severity: 'HIGH', count: alerts}] : []
}

async function connectGithub() {
  github.state = 'running'
  github.error = null
  try {
    const res = await apiFetch('api/github/refresh', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const report = await res.json()
    github.available = report.available !== false
    github.connected = report.connected === true
    github.authenticated = report.credential?.authenticated === true
    github.statusLabel = report.status
    github.statusTone = 'text-bg-secondary'
    if (github.connected && github.authenticated) {
      github.severityCounts = githubSeverityCounts(report)
      github.score = scoreFromSeverityCounts(github.severityCounts)
    } else {
      github.severityCounts = []
      github.score = null
    }
    github.state = 'done'
  } catch (e) {
    github.state = 'error'
    github.error = describeLoadError(e, 'Unable to connect to GitHub').message
  }
}

const githubScored = computed(
  () => github.state === 'done' && github.connected && github.authenticated && Number.isFinite(github.score)
)

const overall = computed(() => {
  const scores = visibleScanners.value
    .map((def) => scanners[def.id])
    .filter((state) => state.state === 'done' && Number.isFinite(state.score))
    .map((state) => state.score)
  if (githubVisible.value && githubScored.value) scores.push(github.score)
  return overallScore(scores)
})

const scoredCount = computed(() => {
  let count = visibleScanners.value.filter((def) => scanners[def.id].state === 'done').length
  if (githubVisible.value && githubScored.value) count += 1
  return count
})

const totalCount = computed(() => visibleScanners.value.length + (githubVisible.value ? 1 : 0))

const anyRunning = computed(
  () => github.state === 'running' || visibleScanners.value.some((def) => scanners[def.id].state === 'running')
)

const overallBandLabel = computed(() => (Number.isFinite(overall.value) ? scoreBandLabel(overall.value) : 'Not scored'))
const overallBandTone = computed(() => (Number.isFinite(overall.value) ? scoreBandTone(overall.value) : 'secondary'))

const overallContributions = computed(() => {
  const items = visibleScanners.value
    .map((def) => ({title: def.title, score: scanners[def.id].score, state: scanners[def.id].state}))
    .filter((item) => item.state === 'done' && Number.isFinite(item.score))
  if (githubVisible.value && githubScored.value) items.push({title: 'GitHub', score: github.score, state: 'done'})
  return items
    .map((item) => ({title: item.title, deduction: item.score - 100}))
    .sort((a, b) => a.deduction - b.deduction)
})

async function runAll() {
  const tasks = visibleScanners.value
    .filter((def) => scanners[def.id].state !== 'running')
    .map((def) => runScanner(def))
  if (githubVisible.value && github.state === 'idle') tasks.push(connectGithub())
  await Promise.allSettled(tasks)
}

async function ensurePanels() {
  if (injectedPanels?.value?.panels || localPanels.value) return
  try {
    const res = await apiFetch('api/panels')
    if (res.ok) localPanels.value = await res.json()
  } catch {
    // Availability is best-effort; missing data simply shows every scanner card.
  }
}

onMounted(ensurePanels)
</script>

<template>
  <div>
    <div class="overview-hero mb-4">
      <div class="hero-copy">
        <h2>Overview</h2>
        <p class="hero-lead">Understand your Spring Boot app in minutes.</p>
        <p>
          BootUI turns the local runtime into a guided map of profiles, ports, safety status, Actuator-backed
          diagnostics, and the panels that explain how the app is wired.
        </p>
      </div>
      <div class="hero-actions">
        <a class="btn btn-outline-light" href="/">
          <i class="bi bi-house-door me-1"></i>
          Application homepage
        </a>
        <a :href="githubProjectUrl" class="btn btn-outline-light" rel="noopener noreferrer" target="_blank">
          <i class="bi bi-github me-1"></i>
          BootUI GitHub project
        </a>
      </div>
    </div>

    <div class="row gx-3 gy-4 mb-4">
      <div class="col-lg-4">
        <OverviewHealthCard />
      </div>
      <div class="col-lg-8">
        <OverviewMemoryCard />
      </div>
    </div>

    <div class="row gx-3 gy-4 mb-4">
      <div class="col-12">
        <div class="card overall-card">
          <div class="card-body d-flex flex-column flex-lg-row align-items-lg-center gap-4">
            <div class="flex-shrink-0 min-w-0">
              <div class="text-uppercase text-muted fw-bold small">Overall score</div>
              <div class="d-flex align-items-center gap-3 mt-2">
                <div :class="['overall-gauge', `overall-gauge--${overallBandTone}`]">
                  <span class="overall-gauge__value">{{ Number.isFinite(overall) ? overall : '—' }}</span>
                  <span class="overall-gauge__max">/ 100</span>
                </div>
                <div class="min-w-0">
                  <span
                    :class="[
                      'badge',
                      `text-bg-${overallBandTone}`,
                      'fs-6',
                      'text-truncate',
                      'd-inline-block',
                      'mw-100'
                    ]"
                    >{{ overallBandLabel }}</span
                  >
                  <div class="text-muted small mt-2 text-truncate">
                    {{ scoredCount }} of {{ totalCount }} scanners scored
                  </div>
                </div>
              </div>
            </div>

            <div class="flex-grow-1 min-w-0">
              <div v-if="overallContributions.length" class="row g-2">
                <div
                  v-for="item in overallContributions"
                  :key="item.title"
                  class="col-sm-6 col-lg-4 d-flex justify-content-between align-items-center small min-w-0"
                >
                  <span class="text-muted text-truncate me-2">{{ item.title }}</span>
                  <span
                    :class="[
                      'flex-shrink-0',
                      item.deduction < 0 ? 'text-danger fw-semibold' : 'text-success fw-semibold'
                    ]"
                  >
                    {{ item.deduction < 0 ? item.deduction : '0' }}
                  </span>
                </div>
              </div>
              <p v-else class="text-muted small mb-0">
                Run the scanners to compute an overall security & health score.
              </p>
            </div>

            <div class="flex-shrink-0 mt-3 mt-lg-0 min-w-0">
              <button class="btn btn-primary" type="button" :disabled="anyRunning || totalCount === 0" @click="runAll">
                <span v-if="anyRunning" class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                {{ anyRunning ? 'Running scanners…' : 'Run all scanners' }}
              </button>
            </div>
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
          :score="github.score"
          :severity-counts="github.severityCounts"
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
            <template v-else-if="githubScored">
              <div class="d-flex align-items-baseline gap-2">
                <span :class="['scanner-score', `scanner-score--${scoreBandTone(github.score)}`]">
                  {{ github.score }}
                </span>
                <span class="text-muted small">/ 100</span>
                <span :class="['badge', `text-bg-${scoreBandTone(github.score)}`, 'ms-auto']">
                  {{ scoreBandLabel(github.score) }}
                </span>
              </div>
              <div v-if="github.severityCounts.length" class="d-flex flex-wrap gap-1 mt-2">
                <span v-for="entry in github.severityCounts" :key="entry.severity" class="badge text-bg-danger">
                  {{ entry.count }} security alert(s)
                </span>
              </div>
              <div v-else class="text-success small mt-2">
                <i class="bi bi-check-circle me-1"></i>No open security alerts
              </div>
            </template>
            <template v-else-if="github.state === 'done'">
              <div class="text-muted small">
                <i class="bi bi-cloud-arrow-down me-1"></i>
                Connect to GitHub to load live security metrics.
              </div>
            </template>
            <template v-else>
              <div class="text-muted small">Connect to GitHub to score repository security signals.</div>
            </template>
          </template>

          <template #actions>
            <button
              class="btn btn-sm btn-primary"
              type="button"
              :disabled="github.state === 'running'"
              @click="connectGithub"
            >
              <span
                v-if="github.state === 'running'"
                class="spinner-border spinner-border-sm me-1"
                aria-hidden="true"
              ></span>
              <i v-else class="bi bi-github me-1"></i>
              {{ githubScored ? 'Refresh' : 'Connect to GitHub' }}
            </button>
            <router-link to="/github" class="btn btn-sm btn-outline-secondary ms-auto">
              Open GitHub<i class="bi bi-arrow-right-short"></i>
            </router-link>
          </template>
        </ScannerScoreCard>
      </div>

      <div v-for="def in visibleScanners" :key="def.id" class="col-md-6 col-lg-4">
        <ScannerScoreCard
          :title="def.title"
          :icon="def.icon"
          :tone="def.tone"
          :to="def.to"
          :state="scanners[def.id].state"
          :score="scanners[def.id].score"
          :severity-counts="scanners[def.id].severityCounts"
          :status-label="scanners[def.id].statusLabel"
          :status-tone="scanners[def.id].statusTone"
          :error-message="scanners[def.id].error"
          @run="runScanner(def)"
        />
      </div>

      <div v-if="totalCount === 0" class="col-12">
        <div class="alert alert-secondary mb-0">No technology scanners were detected for this application.</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overview-hero {
  align-items: flex-start;
  background:
    radial-gradient(circle at top right, rgba(255, 255, 255, 0.38), transparent 18rem),
    linear-gradient(135deg, #16794c, #0d6efd);
  border-radius: 1.4rem;
  box-shadow: 0 1.5rem 3.5rem rgba(13, 110, 253, 0.22);
  color: #fff;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  overflow: hidden;
  padding: clamp(1.4rem, 3vw, 2.4rem);
  position: relative;
}

.overview-hero::after {
  animation: sweep 5s ease-in-out infinite;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.18), transparent);
  content: '';
  height: 150%;
  position: absolute;
  right: 12%;
  top: -25%;
  transform: rotate(18deg);
  width: 7rem;
}

.hero-copy {
  max-width: 48rem;
  position: relative;
  z-index: 1;
}

.hero-kicker {
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 999px;
  display: inline-flex;
  font-size: 0.78rem;
  font-weight: 800;
  letter-spacing: 0.07em;
  margin-bottom: 1rem;
  padding: 0.4rem 0.7rem;
  text-transform: uppercase;
}

.overview-hero h2 {
  font-size: clamp(2rem, 4vw, 3.5rem);
  font-weight: 900;
  letter-spacing: -0.04em;
  line-height: 0.95;
  margin-bottom: 1rem;
}

.overview-hero p {
  color: rgba(255, 255, 255, 0.82);
  font-size: 1.05rem;
  margin-bottom: 0;
}

.overview-hero .hero-lead {
  font-size: 1.35rem;
  font-weight: 700;
  margin-bottom: 0.75rem;
}

.hero-actions {
  display: flex;
  flex-shrink: 0;
  gap: 0.75rem;
  position: relative;
  z-index: 1;
}

.hero-actions .btn {
  font-weight: 700;
}

.overall-card {
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0.78)),
    radial-gradient(circle at top right, rgba(13, 110, 253, 0.12), transparent 16rem);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.2rem;
  box-shadow: 0 1rem 2.6rem rgba(15, 23, 42, 0.08);
}

.overall-gauge {
  align-items: center;
  border: 0.4rem solid;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  height: 6.5rem;
  justify-content: center;
  width: 6.5rem;
}

.overall-gauge__value {
  font-size: 2rem;
  font-weight: 850;
  line-height: 1;
}

.overall-gauge__max {
  color: #64748b;
  font-size: 0.72rem;
}

.overall-gauge--success {
  border-color: #198754;
  color: #198754;
}

.overall-gauge--warning {
  border-color: #ffc107;
  color: #997404;
}

.overall-gauge--danger {
  border-color: #dc3545;
  color: #dc3545;
}

.overall-gauge--secondary {
  border-color: #cbd5e1;
  color: #64748b;
}

.overall-contributions {
  display: grid;
  gap: 0.35rem;
  margin-bottom: 1rem;
}

.scanner-score {
  font-size: 2.1rem;
  font-weight: 850;
  line-height: 1;
}

.scanner-score--success {
  color: #198754;
}

.scanner-score--warning {
  color: #997404;
}

.scanner-score--danger {
  color: #dc3545;
}

.scanner-score--secondary {
  color: #64748b;
}

@keyframes sweep {
  0% {
    opacity: 0;
    transform: translateX(-6rem) rotate(18deg);
  }
  45%,
  55% {
    opacity: 1;
  }
  100% {
    transform: translateX(16rem) rotate(18deg);
  }
}

@media (max-width: 991.98px) {
  .overview-hero {
    flex-direction: column;
  }

  .hero-actions {
    flex-wrap: wrap;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
