<script setup>
import {computed, provide, reactive, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {getJson} from '../api.js'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {
  availableThemes,
  checksWithReasons,
  coverageSources,
  coverageSummary,
  emptyState,
  groupObservations,
  evidenceShares,
  isListed,
  numericColumns,
  unlistedSummary,
  validationOf
} from '../utils/runtimeInsights.js'
import {insightsLayout} from '../utils/runtimeInsightsLayouts.js'
import InsightCheckLimits from './components/InsightCheckLimits.vue'
import InsightCoverage from './components/InsightCoverage.vue'
import InsightDetail from './components/InsightDetail.vue'
import InsightNotExercised from './components/InsightNotExercised.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import RuntimeInsightsByArea from './components/RuntimeInsightsByArea.vue'
import RuntimeInsightsByQuestion from './components/RuntimeInsightsByQuestion.vue'
import RuntimeInsightsSummaryFirst from './components/RuntimeInsightsSummaryFirst.vue'
import {insightMarkdown} from '../utils/markdownExport.js'
import ChangeImpact from './components/ChangeImpact.vue'
import ResourceProfile from './components/ResourceProfile.vue'
import RunComparison from './components/RunComparison.vue'
import {comparisonSummary} from '../utils/runComparison.js'

// Runtime Insights (docs/PLAN-v2.md §5.5): the runtime journal's retained events projected into observations. Every
// read is a GET of what the journal already recorded; opening the panel starts no capture, scan, or network call.
const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason, readOnly, readOnlyReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
// Live Activity links here with ?q=<route> and, from a request's drawer, ?insight=<id> to open one observation;
// ?impact=<symbol> opens the change impact of a symbol.
const route = useRoute()
const query = ref(typeof route?.query?.q === 'string' ? route.query.q : '')
const theme = ref(typeof route?.query?.theme === 'string' ? route.query.theme : '')
const selectedId = ref(typeof route?.query?.insight === 'string' ? route.query.insight : null)
// Show all routes (docs/PLAN-v2.md M4-19): the default list leaves out short routes and other noise; ?all=1, as the
// Memory panel links, or a deep link to a row the default leaves out shows every row.
const showAll = ref(route?.query?.all === '1' || route?.query?.all === 'true')
let deepLinkChecked = false
const initialImpact = typeof route?.query?.impact === 'string' ? route.query.impact : ''
// Layout proposals (temporary): ?insightsLayout=a|b|c renders the same report and state in one of three proposed
// layouts; without it the current layout stays. Proposals B and C open an observation only when it is asked for.
const layout = insightsLayout(route?.query)
const LAYOUTS = {a: RuntimeInsightsByQuestion, b: RuntimeInsightsByArea, c: RuntimeInsightsSummaryFirst}
const opensFirstObservation = layout === '' || layout === 'a'
const detail = ref(null)
// The "Copy for AI" preview of the open observation, built from the detail already loaded; copying sends nothing.
const aiExport = ref(null)
const detailError = ref(null)
const detailLoading = ref(false)
// The comparison loads on its own; the run summary links to it in a few words once it has. comparisonReady stays
// false only until the first fetch settles (success or failure), so the banner says "Comparing…" rather than
// showing nothing or a stale sentence from a previous run's report.
const comparison = ref(null)
const comparisonReady = ref(false)
const comparisonText = computed(() => comparisonSummary(comparison.value))
// Whether there is a previous run to show the changes of, rather than only a reason there is none.
const compared = computed(() => ['COMPARED', 'INSUFFICIENT'].includes(comparison.value?.status))

function onComparisonLoaded(value) {
  comparison.value = value
  comparisonReady.value = true
}

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
    if (!deepLinkChecked) {
      deepLinkChecked = true
      const linked = report.value?.observations?.find((observation) => observation.id === selectedId.value)
      if (linked && !isListed(linked)) showAll.value = true
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Runtime Insights')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport, {
  enabled: manifestAvailable,
  initialLoading: false
})

const themes = computed(() => availableThemes(report.value))
const groups = computed(() =>
  groupObservations(report.value, {
    query: query.value,
    theme: theme.value,
    all: showAll.value,
    selectedId: selectedId.value
  })
)
const unlisted = computed(() =>
  unlistedSummary(report.value, {
    query: query.value,
    theme: theme.value,
    all: showAll.value,
    selectedId: selectedId.value
  })
)
const anyUnlisted = computed(() => (report.value?.observations ?? []).some((observation) => !isListed(observation)))
const unlistedText = computed(() =>
  unlisted.value.groups.map((group) => `${formatNumber(group.count)} ${group.title}`).join(', ')
)
const visibleObservations = computed(() => groups.value.flatMap((group) => group.observations))
const coverage = computed(() => coverageSummary(report.value))
const sources = computed(() => coverageSources(report.value))
const unrun = computed(() => checksWithReasons(report.value))
const empty = computed(() => emptyState(report.value))
// A check ran when it was evaluated, even partially; one not applicable or unavailable did not.
const evaluated = computed(() =>
  (report.value?.checks ?? []).filter((check) => check.status === 'EVALUATED' || check.status === 'PARTIAL')
)
const selected = computed(() => visibleObservations.value.find((observation) => observation.id === selectedId.value))
const selectedValidation = computed(() =>
  validationOf(report.value?.checks?.find((check) => check.kind === selected.value?.kind))
)
watch(selectedId, () => (aiExport.value = null))

function openAiExport() {
  const check = report.value?.checks?.find((candidate) => candidate.kind === selected.value?.kind)
  aiExport.value = insightMarkdown(detail.value, {
    title: check?.title,
    checkReason: check?.status !== 'EVALUATED' ? check?.reason : null,
    validation: validationOf(check)?.reason
  })
}
// A breakdown's share column becomes bars, so the phase that took the time stands out before any number is read.
const shares = computed(() => evidenceShares(detail.value))
const numeric = computed(() => numericColumns(detail.value))

// Keep a selection while it is still listed, so a refresh never loses the developer's place; otherwise open the first.
watch(
  visibleObservations,
  (observations) => {
    if (!report.value) return
    if (!observations.some((observation) => observation.id === selectedId.value)) {
      selectedId.value = opensFirstObservation ? (observations[0]?.id ?? null) : null
    }
  },
  {immediate: true}
)

// A newer request supersedes an older one, so a slow answer never overwrites the evidence of a later refresh.
let detailRequest = 0

// Every refresh replaces the report, so the evidence is reloaded with it: the rows must match the sentence above them.
// When the same observation stays selected the old rows remain visible until the new ones arrive.
watch(selected, async (observation, previous) => {
  const token = ++detailRequest
  if (!observation) {
    detail.value = null
    return
  }
  const refresh = previous?.id === observation.id && detail.value?.observation?.id === observation.id
  if (!refresh) detailLoading.value = true
  detailError.value = null
  try {
    const loaded = await getJson(`api/runtime-insights/insights/${encodeURIComponent(observation.id)}`)
    if (token === detailRequest) detail.value = loaded
  } catch (e) {
    if (token === detailRequest && !refresh) {
      detail.value = null
      detailError.value = formatLoadError(e, 'Unable to load this observation’s evidence')
    }
  } finally {
    if (token === detailRequest) detailLoading.value = false
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
  return {OBSERVED: 'Observed', PARTIAL: 'Partial', INSUFFICIENT: 'Not enough evidence'}[status] ?? status
}

function statusClass(status) {
  return {OBSERVED: 'insight-status-observed', PARTIAL: 'insight-status-partial'}[status] ?? 'insight-status-pending'
}

function checkStatusLabel(status) {
  return (
    {
      NOT_APPLICABLE: 'Not applicable',
      UNAVAILABLE: 'Unavailable',
      INSUFFICIENT: 'Not enough evidence',
      PARTIAL: 'Partial',
      EVALUATED: 'Ran'
    }[status] ?? status
  )
}

function tierLabel(tier) {
  return (
    {REQUEST_ID: 'request id', PROPAGATED: 'propagated by the agent', TRACE_ID: 'trace id'}[tier] ??
    String(tier ?? '').toLowerCase()
  )
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

function select(id) {
  selectedId.value = id
}

// The shared state every layout and the extracted pieces of the panel read; refs are unwrapped and stay writable.
provide(
  'runtimeInsights',
  reactive({
    report,
    lastFetched,
    readOnly,
    readOnlyReason,
    query,
    theme,
    showAll,
    selectedId,
    detail,
    detailLoading,
    detailError,
    aiExport,
    themes,
    groups,
    unlisted,
    unlistedText,
    anyUnlisted,
    visibleObservations,
    coverage,
    sources,
    unrun,
    empty,
    evaluated,
    selected,
    selectedValidation,
    shares,
    numeric,
    comparison,
    comparisonReady,
    comparisonText,
    compared,
    windowText,
    initialImpact,
    select,
    openAiExport,
    toggleTheme,
    statusLabel,
    checkStatusLabel,
    tierLabel,
    onComparisonLoaded
  })
)
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

      <component :is="LAYOUTS[layout]" v-else-if="layout" />

      <template v-else>
        <section class="card insight-window mb-3" aria-labelledby="insight-window-title">
          <div class="card-body">
            <h2 id="insight-window-title" class="visually-hidden">Window and correlation coverage</h2>
            <InsightCoverage />
          </div>
        </section>

        <div
          v-if="comparisonReady ? comparisonText : true"
          class="alert alert-secondary insight-comparison-banner d-flex flex-wrap align-items-center justify-content-between gap-2 py-2"
          role="status"
        >
          <span class="small">
            <i class="bi bi-arrow-left-right me-1" aria-hidden="true"></i>
            {{ comparisonReady ? comparisonText : 'Comparing with the previous run…' }}
          </span>
          <button
            v-if="comparisonReady && comparisonText"
            type="button"
            class="btn btn-sm btn-outline-secondary insight-comparison-link"
            @click="showComparison"
          >
            See what changed
          </button>
        </div>

        <ChangeImpact class="mb-3" :initial-symbol="initialImpact" />

        <div v-for="limitation in report.limitations" :key="limitation" class="alert alert-secondary small py-2">
          {{ limitation }}
        </div>

        <div v-if="empty === 'no-requests'" class="alert alert-secondary insight-empty">
          <strong>No HTTP requests recorded in this run yet.</strong>
          <span class="d-block small">
            Exercise your application, then refresh. Scheduled jobs and consumed messages are also checked when their
            sources are enabled; see which checks ran below.
          </span>
        </div>

        <div v-else-if="empty === 'nothing-observed'" class="alert alert-secondary insight-empty">
          <strong v-if="report.window.requests > 0">
            Nothing to report across {{ formatNumber(report.window.requests) }} requests.
          </strong>
          <strong v-else>
            Nothing to report across {{ formatNumber(report.window.retainedEvents) }} retained events.
          </strong>
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
            <button
              v-if="anyUnlisted"
              type="button"
              class="btn btn-sm insight-show-all"
              :class="showAll ? 'btn-primary' : 'btn-outline-secondary'"
              :aria-pressed="showAll"
              title="Also list short routes and the other rows the default list leaves out"
              @click="showAll = !showAll"
            >
              Show all routes
            </button>
          </div>

          <div v-if="groups.length === 0" class="text-muted small mb-3 insight-none-listed">
            <template v-if="unlisted.total > 0">
              Nothing is listed by default here. {{ formatNumber(unlisted.total) }} not listed: {{ unlistedText }}.
              <button type="button" class="btn btn-link btn-sm p-0 align-baseline" @click="showAll = true">
                Show all routes
              </button>
            </template>
            <template v-else>No observation matches this search.</template>
          </div>

          <div v-else class="row g-3 mb-3">
            <div class="col-lg-5">
              <nav aria-label="Observations">
                <div v-for="group in groups" :key="group.kind" class="mb-3">
                  <div class="d-flex flex-wrap align-items-baseline gap-2 mb-2">
                    <h2 class="h6 mb-0 insight-group-title">{{ group.title }}</h2>
                    <span
                      v-if="group.validation"
                      class="badge rounded-pill text-bg-secondary insight-validation"
                      :title="group.validation.reason ?? undefined"
                      >{{ group.validation.marker }}</span
                    >
                  </div>
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
                      <span v-if="!isListed(observation)" class="d-block small mt-1 insight-unlisted-label">
                        Not listed by default
                      </span>
                    </button>
                  </div>
                </div>
                <p v-if="unlisted.total > 0" class="small text-muted mb-0 insight-unlisted">
                  {{ formatNumber(unlisted.total) }} more not listed by default: {{ unlistedText }}.
                  <button type="button" class="btn btn-link btn-sm p-0 align-baseline" @click="showAll = true">
                    Show all routes
                  </button>
                </p>
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
                  <InsightDetail />
                </div>
              </section>
            </div>
          </div>
        </template>

        <RunComparison class="mb-3" :refresh-key="lastFetched ?? 0" @loaded="onComparisonLoaded" />

        <ResourceProfile class="mb-3" :read-only="readOnly" :read-only-reason="readOnlyReason" />

        <div v-if="unrun.length || report.notExercised?.length" class="row g-3 insight-caveats">
          <div v-if="report.notExercised?.length" :class="unrun.length ? 'col-xl-7' : 'col-12'">
            <section
              v-if="report.notExercised?.length"
              class="card h-100 insight-not-exercised"
              aria-labelledby="insight-not-exercised-title"
            >
              <div class="card-body">
                <h2 id="insight-not-exercised-title" class="h6 mb-1">Not exercised in this run</h2>
                <InsightNotExercised />
              </div>
            </section>
          </div>
          <div v-if="unrun.length" :class="report.notExercised?.length ? 'col-xl-5' : 'col-12'">
            <section v-if="unrun.length" class="card h-100 insight-unrun" aria-labelledby="insight-unrun-title">
              <div class="card-body">
                <h2 id="insight-unrun-title" class="h6 mb-2">Checks and their limits</h2>
                <InsightCheckLimits />
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

.insight-group-title {
  font-weight: 700;
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

.insight-unlisted-label {
  color: var(--bs-secondary-color);
  font-style: italic;
}

.list-group-item-action.active .insight-unlisted-label {
  color: inherit;
}

.insight-unlisted {
  margin-top: -0.25rem;
}
</style>
