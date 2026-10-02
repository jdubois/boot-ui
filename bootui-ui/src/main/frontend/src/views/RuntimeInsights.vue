<script setup>
import {computed, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {getJson} from '../api.js'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {
  availableThemes,
  checksWithReasons,
  coverageSources,
  coverageSummary,
  emptyState,
  groupObservations,
  isMachineColumn,
  textParts
} from '../utils/runtimeInsights.js'
import InsightText from './components/InsightText.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ChangeImpact from './components/ChangeImpact.vue'
import RunComparison from './components/RunComparison.vue'
import {comparisonSummary} from '../utils/runComparison.js'

// Runtime Insights (docs/PLAN-v2.md §5.5): the runtime journal's retained events projected into observations. Every
// read is a GET of what the journal already recorded; opening the panel starts no capture, scan, or network call.
const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
// Live Activity links here with ?q=<route> and, from a request's drawer, ?insight=<id> to open one observation;
// ?impact=<symbol> opens the change impact of a symbol.
const route = useRoute()
const query = ref(typeof route?.query?.q === 'string' ? route.query.q : '')
const theme = ref('')
const selectedId = ref(typeof route?.query?.insight === 'string' ? route.query.insight : null)
const initialImpact = typeof route?.query?.impact === 'string' ? route.query.impact : ''
const detail = ref(null)
const detailError = ref(null)
const detailLoading = ref(false)
// The comparison loads on its own; the run summary links to it in a few words once it has.
const comparison = ref(null)
const comparisonText = computed(() => comparisonSummary(comparison.value))

function showComparison() {
  const section = document.getElementById('insight-comparison')
  section?.scrollIntoView?.({block: 'start', behavior: 'smooth'})
  section?.focus({preventScroll: true})
}

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/runtime-insights')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Runtime Insights')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport, {
  enabled: manifestAvailable,
  initialLoading: false
})

const themes = computed(() => availableThemes(report.value))
const groups = computed(() => groupObservations(report.value, {query: query.value, theme: theme.value}))
const visibleObservations = computed(() => groups.value.flatMap((group) => group.observations))
const coverage = computed(() => coverageSummary(report.value))
const sources = computed(() => coverageSources(report.value))
const unrun = computed(() => checksWithReasons(report.value))
const empty = computed(() => emptyState(report.value))
const evaluated = computed(() => (report.value?.checks ?? []).filter((check) => check.status !== 'NOT_APPLICABLE'))
const selected = computed(() => visibleObservations.value.find((observation) => observation.id === selectedId.value))

// Keep a selection while it is still listed, so a refresh never loses the developer's place; otherwise open the first.
watch(
  visibleObservations,
  (observations) => {
    if (!report.value) return
    if (!observations.some((observation) => observation.id === selectedId.value)) {
      selectedId.value = observations[0]?.id ?? null
    }
  },
  {immediate: true}
)

watch(selected, async (observation, previous) => {
  if (!observation) {
    detail.value = null
    return
  }
  if (previous && previous.id === observation.id && detail.value?.observation?.id === observation.id) return
  detailLoading.value = true
  detailError.value = null
  try {
    detail.value = await getJson(`api/runtime-insights/insights/${encodeURIComponent(observation.id)}`)
  } catch (e) {
    detail.value = null
    detailError.value = describeLoadError(e, 'Unable to load this observation’s evidence')
  } finally {
    detailLoading.value = false
  }
})

// The report as the engine projected it, saved by the browser: no request is made.
function exportJson() {
  const blob = new Blob([JSON.stringify(report.value, null, 2)], {type: 'application/json'})
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `runtime-insights-${report.value?.window?.runId ?? 'report'}.json`
  link.click()
  URL.revokeObjectURL(url)
}

function toggleTheme(id) {
  theme.value = theme.value === id ? '' : id
}

function statusLabel(status) {
  return {OBSERVED: 'Observed', PARTIAL: 'Partial', INSUFFICIENT: 'Needs more traffic'}[status] ?? status
}

function statusClass(status) {
  return {OBSERVED: 'insight-status-observed', PARTIAL: 'insight-status-partial'}[status] ?? 'insight-status-pending'
}

function checkStatusLabel(status) {
  return {NOT_APPLICABLE: 'Not applicable', PARTIAL: 'Partial', EVALUATED: 'Ran'}[status] ?? status
}

function tierLabel(tier) {
  return {REQUEST_ID: 'request id', TRACE_ID: 'trace id'}[tier] ?? String(tier ?? '').toLowerCase()
}

const windowText = computed(() => {
  const window = report.value?.window
  if (!window) return ''
  const parts = [
    `${formatNumber(window.requests)} ${window.requests === 1 ? 'request' : 'requests'}`,
    `${formatNumber(window.retainedEvents)} events`
  ]
  if (window.firstEventAt && window.lastEventAt) {
    parts.push(`${formatClockTime(window.firstEventAt)} – ${formatClockTime(window.lastEventAt)}`)
  }
  parts.push(`${formatNumber(window.droppedEvents)} dropped`)
  if (window.evictedEvents > 0) parts.push(`${formatNumber(window.evictedEvents)} evicted`)
  return parts.join(' · ')
})
</script>

<template>
  <div class="runtime-insights">
    <PanelHeader
      icon="bi-lightbulb"
      title="Runtime Insights"
      subtitle="What this run did that no single panel shows."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <button
          v-if="report?.available"
          type="button"
          class="btn btn-sm btn-outline-secondary insight-export"
          title="Download this report as JSON"
          @click="exportJson"
        >
          <i class="bi bi-download me-1" aria-hidden="true"></i>Export JSON
        </button>
      </template>
    </PanelHeader>

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />

    <div v-else-if="!manifestAvailable" class="alert alert-warning">
      <strong>Runtime Insights is unavailable.</strong>
      <span class="d-block small">{{ manifestUnavailableReason }}</span>
    </div>

    <template v-else-if="report">
      <div v-if="empty === 'disabled'" class="alert alert-warning">
        <strong>Runtime Insights is unavailable.</strong>
        <span class="d-block small">{{ report.unavailableReason }}</span>
      </div>

      <template v-else>
        <section class="card insight-window mb-3" aria-labelledby="insight-window-title">
          <div class="card-body">
            <h2 id="insight-window-title" class="visually-hidden">Window and correlation coverage</h2>
            <p class="mb-2 small insight-window-text">
              <span class="fw-semibold">This run</span>
              <span class="text-muted"> · {{ windowText }}</span>
              <template v-if="comparisonText">
                <span class="text-muted"> · </span>
                <button
                  type="button"
                  class="btn btn-link btn-sm p-0 align-baseline insight-comparison-link"
                  @click="showComparison"
                >
                  {{ comparisonText }}
                </button>
              </template>
            </p>
            <div v-if="coverage.events > 0">
              <div
                class="insight-coverage-bar"
                role="img"
                :aria-label="
                  'Events linked by ' +
                  coverage.segments.map((segment) => `${segment.label} ${segment.share} %`).join(', ')
                "
              >
                <span
                  v-for="segment in coverage.segments.filter((segment) => segment.count > 0)"
                  :key="segment.id"
                  :class="`insight-coverage-${segment.id}`"
                  :style="{flexGrow: segment.count}"
                ></span>
              </div>
              <ul class="list-inline small mb-0 mt-2 insight-coverage-legend">
                <li class="list-inline-item text-muted">Linked by</li>
                <li v-for="segment in coverage.segments" :key="segment.id" class="list-inline-item">
                  <span :class="`insight-coverage-swatch insight-coverage-${segment.id}`" aria-hidden="true"></span>
                  {{ segment.label }} <span class="fw-semibold">{{ segment.share }} %</span>
                </li>
              </ul>
              <details class="small mt-2 insight-coverage-sources">
                <summary>By source</summary>
                <div class="table-responsive mt-2">
                  <table class="table table-sm align-middle mb-0">
                    <thead>
                      <tr>
                        <th scope="col">Source</th>
                        <th scope="col" class="text-end">Events</th>
                        <th scope="col" class="text-end">Request id</th>
                        <th scope="col" class="text-end">Trace id</th>
                        <th scope="col" class="text-end">Run or message</th>
                        <th scope="col" class="text-end">Outside requests</th>
                        <th scope="col" class="text-end">Dropped</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="source in sources" :key="source.source">
                        <td>
                          <code>{{ source.source }}</code>
                        </td>
                        <td class="text-end">{{ formatNumber(source.events) }}</td>
                        <td class="text-end">{{ formatNumber(source.byRequestId) }}</td>
                        <td class="text-end">{{ formatNumber(source.byTraceId) }}</td>
                        <td class="text-end">{{ formatNumber(source.byExecutionId) }}</td>
                        <td class="text-end">{{ formatNumber(source.unlinked) }}</td>
                        <td class="text-end">{{ formatNumber(source.dropped) }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </details>
            </div>
          </div>
        </section>

        <div v-for="limitation in report.limitations" :key="limitation" class="alert alert-secondary small py-2">
          {{ limitation }}
        </div>

        <div v-if="empty === 'no-requests'" class="alert alert-secondary insight-empty">
          <strong>No requests recorded in this run yet.</strong>
          <span class="d-block small">
            Exercise your application, then refresh. Observations are projected from the requests the runtime journal
            retains.
          </span>
        </div>

        <div v-else-if="empty === 'nothing-observed'" class="alert alert-secondary insight-empty">
          <strong>Nothing to report across {{ formatNumber(report.window.requests) }} requests.</strong>
          <span class="d-block small">
            {{ evaluated.length }} of {{ report.checks.length }} checks ran and found nothing; the others are listed
            below with why they did not run.
          </span>
        </div>

        <template v-else>
          <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
            <input
              v-model="query"
              type="search"
              class="form-control form-control-sm insight-search"
              aria-label="Search observations by route, table, or logger"
              placeholder="Search routes, tables, loggers…"
            />
            <div class="d-flex flex-wrap gap-1" role="group" aria-label="Filter observations by theme">
              <button
                v-for="chip in themes"
                :key="chip.id"
                type="button"
                class="btn btn-sm insight-theme"
                :class="theme === chip.id ? 'btn-primary' : 'btn-outline-secondary'"
                :aria-pressed="theme === chip.id"
                @click="toggleTheme(chip.id)"
              >
                {{ chip.label }}
              </button>
            </div>
          </div>

          <div v-if="groups.length === 0" class="text-muted small mb-3">No observation matches this search.</div>

          <div v-else class="row g-3 mb-3">
            <div class="col-lg-5">
              <nav aria-label="Observations">
                <div v-for="group in groups" :key="group.kind" class="mb-3">
                  <h2 class="h6 mb-2 insight-group-title">{{ group.title }}</h2>
                  <div class="list-group">
                    <button
                      v-for="observation in group.observations"
                      :key="observation.id"
                      type="button"
                      class="list-group-item list-group-item-action insight-item"
                      :class="{active: observation.id === selectedId}"
                      :aria-current="observation.id === selectedId ? 'true' : undefined"
                      @click="selectedId = observation.id"
                    >
                      <span class="d-flex justify-content-between align-items-start gap-2">
                        <code class="bootui-break-anywhere">{{ observation.subject }}</code>
                        <span class="badge insight-status" :class="statusClass(observation.status)">
                          {{ statusLabel(observation.status) }}
                        </span>
                      </span>
                      <span v-if="observation.eligible > 0" class="d-block small text-muted mt-1">
                        {{ formatNumber(observation.affected) }} of {{ formatNumber(observation.eligible) }} requests
                      </span>
                    </button>
                  </div>
                </div>
              </nav>
            </div>

            <div class="col-lg-7">
              <section
                v-if="selected"
                class="card insight-detail"
                aria-live="polite"
                aria-labelledby="insight-sentence"
              >
                <div class="card-body">
                  <p id="insight-sentence" class="insight-sentence mb-2"><InsightText :text="selected.sentence" /></p>
                  <p class="small text-muted mb-3">
                    {{ formatNumber(selected.affected) }} of {{ formatNumber(selected.eligible) }} requests · linked by
                    {{ tierLabel(selected.minimumTier) }} · {{ statusLabel(selected.status) }}
                  </p>

                  <h3 class="h6">What to check</h3>
                  <ol class="small mb-3 insight-checks">
                    <li v-for="check in selected.whatToCheck" :key="check"><InsightText :text="check" /></li>
                  </ol>

                  <template v-if="selected.exemplarRequestIds.length">
                    <h3 class="h6">Open a request</h3>
                    <ul class="list-inline small mb-3">
                      <li v-for="requestId in selected.exemplarRequestIds" :key="requestId" class="list-inline-item">
                        <router-link :to="{path: '/activity', query: {request: requestId}}">
                          <code>{{ requestId }}</code>
                        </router-link>
                      </li>
                    </ul>
                  </template>

                  <h3 class="h6">Evidence</h3>
                  <div v-if="detailLoading" class="small text-muted mb-3" role="status">Loading evidence…</div>
                  <div v-else-if="detailError" class="alert alert-warning small py-2">{{ detailError }}</div>
                  <div v-else-if="detail && !detail.available" class="small text-muted mb-3">
                    {{ detail.unavailableReason }}
                  </div>
                  <template v-else-if="detail">
                    <div class="table-responsive mb-2">
                      <table class="table table-sm align-middle insight-evidence mb-0">
                        <thead>
                          <tr>
                            <th v-for="column in detail.columns" :key="column" scope="col">{{ column }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="(row, index) in detail.rows" :key="index">
                            <td v-for="(cell, column) in row.cells" :key="column">
                              <code v-if="isMachineColumn(detail.columns[column])" class="bootui-break-anywhere">{{
                                cell
                              }}</code>
                              <InsightText v-else :text="cell" />
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <p v-if="detail.truncated > 0" class="small text-muted mb-3">
                      {{ formatNumber(detail.truncated) }} more rows not shown.
                    </p>
                  </template>

                  <template v-if="selected.limitations.length">
                    <h3 class="h6">Limits</h3>
                    <ul class="small text-muted mb-0">
                      <li v-for="limitation in selected.limitations" :key="limitation">
                        <InsightText :text="limitation" />
                      </li>
                    </ul>
                  </template>
                </div>
              </section>
            </div>
          </div>
        </template>

        <RunComparison class="mb-3" :refresh-key="lastFetched ?? 0" @loaded="comparison = $event" />

        <ChangeImpact class="mb-3" :initial-symbol="initialImpact" />

        <div v-if="unrun.length || report.notExercised?.length" class="row g-3 insight-caveats">
          <div v-if="report.notExercised?.length" :class="unrun.length ? 'col-xl-7' : 'col-12'">
            <section
              v-if="report.notExercised?.length"
              class="card h-100 insight-not-exercised"
              aria-labelledby="insight-not-exercised-title"
            >
              <div class="card-body">
                <h2 id="insight-not-exercised-title" class="h6 mb-1">Not exercised in this run</h2>
                <p class="small text-muted mb-2">
                  Declared routes no request of this run reached, so nothing above speaks for them.
                </p>
                <ul class="list-unstyled small mb-0 insight-not-exercised-list">
                  <li v-for="declared in report.notExercised" :key="declared">
                    <code class="bootui-break-anywhere">{{ declared }}</code>
                  </li>
                </ul>
                <p v-if="report.notExercisedOmitted > 0" class="small text-muted mb-0 mt-2">
                  {{ formatNumber(report.notExercisedOmitted) }} more routes not listed.
                </p>
              </div>
            </section>
          </div>
          <div v-if="unrun.length" :class="report.notExercised?.length ? 'col-xl-5' : 'col-12'">
            <section v-if="unrun.length" class="card h-100 insight-unrun" aria-labelledby="insight-unrun-title">
              <div class="card-body">
                <h2 id="insight-unrun-title" class="h6 mb-2">Checks that could not fully run</h2>
                <ul class="list-unstyled small mb-0">
                  <li v-for="check in unrun" :key="check.kind" class="mb-1">
                    <span class="fw-semibold">{{ check.title }}</span>
                    <span class="text-muted"> · {{ checkStatusLabel(check.status) }}</span>
                    <span v-if="check.reason" class="d-block text-muted">{{ check.reason }}</span>
                  </li>
                </ul>
              </div>
            </section>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.insight-search {
  max-width: 22rem;
}

.insight-not-exercised-list {
  columns: 2 22rem;
  column-gap: 1.25rem;
}

.insight-coverage-bar {
  display: flex;
  height: 0.5rem;
  border-radius: var(--bootui-radius-pill);
  overflow: hidden;
  background: var(--bs-secondary-bg);
}

.insight-coverage-bar > span {
  flex-basis: 0;
  min-width: 2px;
}

.insight-coverage-swatch {
  display: inline-block;
  width: 0.65rem;
  height: 0.65rem;
  border-radius: 50%;
  margin-right: 0.25rem;
  vertical-align: baseline;
}

.insight-coverage-request {
  background: var(--bootui-green);
}

.insight-coverage-trace {
  background: var(--bootui-blue);
}

.insight-coverage-execution {
  background: var(--bs-secondary-color);
}

.insight-coverage-none {
  background: var(--bs-border-color);
}

.insight-group-title {
  font-weight: 700;
}

.insight-sentence {
  font-size: 1.15rem;
  font-weight: 700;
  max-width: 75ch;
}

.insight-checks {
  max-width: 75ch;
  padding-left: 1.25rem;
}

.insight-status {
  font-weight: 600;
  white-space: nowrap;
}

.insight-status-observed {
  background: var(--bs-secondary-bg);
  color: var(--bs-body-color);
  border: 1px solid var(--bs-border-color);
}

.insight-status-partial,
.insight-status-pending {
  background: transparent;
  color: var(--bs-secondary-color);
  border: 1px dashed var(--bs-border-color);
}

.list-group-item-action.active .insight-status {
  background: transparent;
  color: inherit;
  border-color: currentColor;
}
</style>
