<script setup>
import {computed, nextTick, provide, reactive, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {getJson} from '../api.js'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {
  checksWithReasons,
  coverageSources,
  coverageSummary,
  emptyState,
  evidenceShares,
  groupObservations,
  numericColumns,
  themeFilters
} from '../utils/runtimeInsights.js'
import {insightMarkdown} from '../utils/markdownExport.js'
import {codeChanges, comparisonSummary} from '../utils/runComparison.js'
import ChangeImpact from './components/ChangeImpact.vue'
import InsightCheckLimits from './components/InsightCheckLimits.vue'
import InsightCoverage from './components/InsightCoverage.vue'
import InsightDetail from './components/InsightDetail.vue'
import InsightNotExercised from './components/InsightNotExercised.vue'
import PanelHeader from './components/PanelHeader.vue'
import LowFootprintBanner from './components/LowFootprintBanner.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import PanelTabs from './components/PanelTabs.vue'
import ResourceProfile from './components/ResourceProfile.vue'
import RunComparison from './components/RunComparison.vue'

// Runtime Insights (docs/PLAN-v2.md §5.5): the runtime journal's retained events projected into observations. Every
// read is a GET of what the journal already recorded; opening the panel starts no capture, scan, or network call.
// The panel leads with a verdict on the run, then compact observation groups that open in place; the run's changes, change
// impact, the resource profiler, and its coverage and limits each have a tab of their own.
const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason, readOnly, readOnlyReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
// Live Activity links here with ?q=<route> and, from a request's drawer, ?insight=<id> to open one observation;
// ?impact=<symbol> opens the change impact of a symbol, and ?tab=<id> one of the tabs.
const route = useRoute()
const query = ref(typeof route?.query?.q === 'string' ? route.query.q : '')
const theme = ref(typeof route?.query?.theme === 'string' ? route.query.theme : '')
const selectedId = ref(typeof route?.query?.insight === 'string' ? route.query.insight : null)
const searchInput = ref(null)
let deepLinkChecked = false
const initialImpact = typeof route?.query?.impact === 'string' ? route.query.impact : ''
// Related panels also expose evidence from the same run.
const ELSEWHERE = [
  {path: '/activity', title: 'Live Activity', detail: 'why a route is slow'},
  {path: '/exceptions', title: 'Exceptions'},
  {path: '/database-connection-pools', title: 'Database Connection Pools'},
  {path: '/ai', title: 'AI Framework'},
  {path: '/memory', title: 'Memory', detail: 'garbage collection and heap'}
]
const TAB_IDS = ['findings', 'changes', 'impact', 'profile', 'coverage']
const linkedTab = typeof route?.query?.tab === 'string' && TAB_IDS.includes(route.query.tab) ? route.query.tab : null
const tab = ref(initialImpact ? 'impact' : (linkedTab ?? 'findings'))
const impactTool = ref(null)
const detail = ref(null)
// The "Copy for AI" preview of the open observation, built from the detail already loaded; copying sends nothing.
const aiExport = ref(null)
const detailError = ref(null)
const detailLoading = ref(false)
// A failed refresh of the open observation keeps its earlier evidence on screen, marked as stale, beside a report that
// has moved on; Copy for AI waits until the evidence is current again.
const detailStale = ref(false)
// The comparison loads on its own; the verdict names it in a few words once it has. comparisonReady stays false only
// until the first fetch settles (success or failure), so the verdict says "Comparing…" rather than showing nothing or
// a stale sentence from a previous run's report.
const comparison = ref(null)
const comparisonReady = ref(false)
const comparisonText = computed(() => comparisonSummary(comparison.value))
// Whether there is a previous run to show the changes of, rather than only a reason there is none.
const compared = computed(() => ['COMPARED', 'PARTIAL', 'INSUFFICIENT'].includes(comparison.value?.status))
// The methods the comparison found changed or added that change impact can check, offered as the first things to check
// there; the total counts every changed or added method, including those the comparison did not list.
const code = computed(() => codeChanges(comparison.value))
const changedMethods = computed(() =>
  (code.value?.rows ?? []).filter((row) => row.checkable).map((row) => ({symbol: row.key, name: row.name}))
)
const changedTotal = computed(() => (code.value?.rows?.length ?? 0) + (code.value?.more ?? 0))

function onComparisonLoaded(value) {
  comparison.value = value
  comparisonReady.value = true
}

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/runtime-insights')
    lastFetched.value = Date.now()
    if (!deepLinkChecked) {
      deepLinkChecked = true
      const linked = report.value?.observations?.find((observation) => observation.id === selectedId.value)
      if (linked) {
        await nextTick()
        document.getElementById(rowId(linked.id))?.scrollIntoView?.({block: 'center'})
      }
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Runtime Insights')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport, {
  enabled: manifestAvailable,
  initialLoading: false
})

const filters = computed(() => ({
  query: query.value,
  theme: theme.value
}))
const groups = computed(() => groupObservations(report.value, filters.value))
const filteredObservations = computed(() => groups.value.flatMap((group) => group.observations))
const themes = computed(() => themeFilters(report.value, {theme: theme.value}))
const filtersActive = computed(() => query.value.trim() !== '' || theme.value !== '')
// The verdict speaks for the run, whatever the list is filtered to.
const counts = computed(() => ({total: report.value?.observations?.length ?? 0}))

async function clearFilters() {
  query.value = ''
  theme.value = ''
  await nextTick()
  searchInput.value?.focus()
}
const coverage = computed(() => coverageSummary(report.value))
const linkedShare = computed(() => coverage.value.segments.find((segment) => segment.id === 'request')?.share ?? 0)
const sources = computed(() => coverageSources(report.value))
const unrun = computed(() => checksWithReasons(report.value))
const empty = computed(() => emptyState(report.value))
// A check ran when it was evaluated, even partially; one not applicable or unavailable did not.
const evaluated = computed(() =>
  (report.value?.checks ?? []).filter((check) => check.status === 'EVALUATED' || check.status === 'PARTIAL')
)
const notExercisedCount = computed(
  () => (report.value?.notExercised?.length ?? 0) + (report.value?.notExercisedOmitted ?? 0)
)
const selected = computed(() => filteredObservations.value.find((observation) => observation.id === selectedId.value))
watch(selectedId, () => (aiExport.value = null))
function groupExpanded(kind) {
  return selected.value?.kind === kind
}

function groupId(kind) {
  return `insight-group-${kind}`
}

function toggleGroup(group) {
  if (groupExpanded(group.kind)) {
    document.getElementById(groupId(group.kind))?.focus({preventScroll: true})
    selectedId.value = null
  } else {
    selectedId.value = group.observations[0].id
  }
}

function observationIndex(group) {
  return group.observations.findIndex((observation) => observation.id === selectedId.value)
}

async function stepObservation(group, direction) {
  const observation = group.observations[observationIndex(group) + direction]
  if (!observation) return
  selectedId.value = observation.id
  await nextTick()
  document.getElementById(`${groupId(group.kind)}-observation`)?.focus()
}

function observationLabel(observation) {
  const limit =
    observation.status === 'OBSERVED'
      ? ''
      : ` - Evidence limit: ${observation.status === 'PARTIAL' ? 'Some source events were dropped' : statusLabel(observation.status)}`
  return (observation.subject ?? 'No subject recorded') + limit
}

const tabs = computed(() => [
  {id: 'findings', label: 'Observations', icon: 'bi-search', count: counts.value.total},
  {id: 'changes', label: 'Changes', icon: 'bi-arrow-left-right', count: null},
  {id: 'impact', label: 'Change impact', icon: 'bi-diagram-3', count: null},
  {id: 'profile', label: 'JFR profile', icon: 'bi-cpu', count: null},
  {id: 'coverage', label: 'Coverage & limits', icon: 'bi-bullseye', count: null}
])

function openAiExport() {
  const check = report.value?.checks?.find((candidate) => candidate.kind === selected.value?.kind)
  aiExport.value = insightMarkdown(detail.value, {
    title: check?.title,
    checkReason: check?.status !== 'EVALUATED' ? check?.reason : null
  })
}
// A breakdown's share column becomes bars, so the phase that took the time stands out before any number is read.
const shares = computed(() => evidenceShares(detail.value))
const numeric = computed(() => numericColumns(detail.value))

// A row stays open while it is still retained, so a refresh never loses the developer's place; one the filters leave out
// closes. Nothing opens on its own: the verdict and the list come first.
watch(filteredObservations, (observations) => {
  if (!report.value || selectedId.value === null) return
  if (!observations.some((observation) => observation.id === selectedId.value)) selectedId.value = null
})

// A newer request supersedes an older one, so a slow answer never overwrites the evidence of a later refresh.
let detailRequest = 0

// Every refresh replaces the report, so the evidence is reloaded with it: the rows must match the sentence above them.
// When the same observation stays open the old rows remain visible until the new ones arrive; when that refresh fails,
// they stay visible labelled as stale, with the failure.
watch(selected, async (observation, previous) => {
  const token = ++detailRequest
  if (!observation) {
    detail.value = null
    detailStale.value = false
    return
  }
  const refresh = previous?.id === observation.id && detail.value?.observation?.id === observation.id
  // A refresh leaves a stale warning in place, unchanged, until it succeeds, so a failing auto-refresh neither blanks
  // it nor has it announced again.
  if (!refresh) {
    detailLoading.value = true
    detailStale.value = false
    detailError.value = null
  }
  try {
    const loaded = await getJson(`api/runtime-insights/insights/${encodeURIComponent(observation.id)}`)
    if (token === detailRequest) {
      detail.value = loaded
      detailStale.value = false
      detailError.value = null
    }
  } catch (e) {
    if (token !== detailRequest) return
    if (refresh) {
      // An open Copy for AI preview holds the earlier evidence too: close it rather than let it be copied as current.
      aiExport.value = null
      detailStale.value = true
      detailError.value = formatLoadError(e, 'Unable to refresh this observation’s evidence')
    } else {
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

// A verdict link opens its tab and moves focus there, so a keyboard or screen reader user lands on what it named.
async function showTab(id, targetId) {
  tab.value = id
  await nextTick()
  const target = document.getElementById(targetId ?? `insights-panel-${id}`)
  target?.focus({preventScroll: true})
  target?.scrollIntoView?.({block: 'start', behavior: 'smooth'})
}

// "See its impact" on a changed method: Change impact opens on that method, checked at once.
async function showImpact({symbol, name}) {
  await showTab('impact')
  impactTool.value?.check(symbol, name)
}

function rowId(id) {
  return `insight-row-${id}`
}

function statusLabel(status) {
  return {OBSERVED: 'Observed', PARTIAL: 'Partial', INSUFFICIENT: 'Not enough evidence'}[status] ?? status
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

const scope = computed(() => {
  const requests = report.value?.window?.requests ?? 0
  return requests > 0
    ? `across ${formatNumber(requests)} ${requests === 1 ? 'request' : 'requests'}`
    : `across ${formatNumber(report.value?.window?.retainedEvents ?? 0)} retained events`
})

// The open row's body, the run's coverage, and the check limits are components of their own; they read this state.
provide(
  'runtimeInsights',
  reactive({
    report,
    detail,
    detailLoading,
    detailError,
    detailStale,
    aiExport,
    coverage,
    sources,
    unrun,
    selected,
    shares,
    numeric,
    windowText,
    showTab,
    openAiExport,
    statusLabel,
    checkStatusLabel,
    tierLabel
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
    <LowFootprintBanner />

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
        <section class="card mb-3 insight-verdict" aria-labelledby="insight-verdict-title">
          <div class="card-body">
            <h2 id="insight-verdict-title" class="insight-verdict-title">
              <template v-if="empty === 'no-requests'">No HTTP requests recorded in this run yet</template>
              <template v-else-if="empty === 'nothing-observed'">Nothing to report {{ scope }}</template>
              <template v-else>
                {{ formatNumber(counts.total) }} {{ counts.total === 1 ? 'observation' : 'observations' }} recorded
                {{ scope }}
              </template>
            </h2>
            <ul class="insight-verdict-facts">
              <li v-if="empty === 'nothing-observed'">
                <i class="bi bi-check2-all" aria-hidden="true"></i>
                {{ evaluated.length }} of {{ report.checks.length }} checks ran
              </li>
              <li v-if="coverage.events > 0">
                <i class="bi bi-link-45deg" aria-hidden="true"></i>
                {{ linkedShare }} % of events linked to their request
              </li>
              <li class="insight-comparison-banner">
                <i class="bi bi-arrow-left-right" aria-hidden="true"></i>
                <button
                  v-if="compared"
                  type="button"
                  class="btn btn-outline-secondary btn-sm align-baseline insight-comparison-link"
                  @click="showTab('changes', 'insight-comparison')"
                >
                  {{ comparisonText }}
                </button>
                <template v-else>{{ comparisonReady ? comparisonText : 'Comparing with the previous run…' }}</template>
              </li>
            </ul>
            <p v-if="report.limitations?.length" class="small text-muted mb-0 mt-2 insight-verdict-limits">
              {{ formatNumber(report.limitations.length) }}
              {{ report.limitations.length === 1 ? 'limit' : 'limits' }} on what this run can show:
              <button
                type="button"
                class="btn btn-outline-secondary btn-sm align-baseline"
                @click="showTab('coverage')"
              >
                see Coverage &amp; limits
              </button>
            </p>
          </div>
        </section>

        <PanelTabs
          class="mb-3"
          :tabs="tabs"
          :selected="tab"
          id-prefix="insights"
          label="Runtime Insights views"
          @select="tab = $event"
        >
          <template #tab="{tab: entry}">
            <i :class="['bi', entry.icon]" aria-hidden="true"></i>
            <span>{{ entry.label }}</span>
            <span v-if="entry.count != null" class="bootui-tabs__count">{{ formatNumber(entry.count) }}</span>
          </template>
        </PanelTabs>

        <div
          v-show="tab === 'findings'"
          id="insights-panel-findings"
          role="tabpanel"
          aria-labelledby="insights-tab-findings"
        >
          <div v-if="empty === 'no-requests'" class="alert alert-secondary insight-empty">
            <strong>Exercise your application, then refresh.</strong>
            <span class="d-block small">
              Scheduled jobs and consumed messages are also checked when their sources are enabled; Coverage &amp;
              limits says which checks ran.
            </span>
          </div>

          <div v-else-if="empty === 'nothing-observed'" class="alert alert-secondary insight-empty">
            <strong>Every check that ran found nothing.</strong>
            <span class="d-block small">
              {{ evaluated.length }} of {{ report.checks.length }} checks ran; Coverage &amp; limits says why the others
              did not.
            </span>
          </div>

          <p v-if="empty === 'no-requests' || empty === 'nothing-observed'" class="small text-muted insight-elsewhere">
            Related evidence is also available in other panels:
            <template v-for="(panel, index) in ELSEWHERE" :key="panel.path"
              >{{ index === 0 ? '' : index === ELSEWHERE.length - 1 ? ', and ' : ', '
              }}<router-link :to="panel.path" class="btn btn-outline-secondary btn-sm">{{ panel.title }}</router-link
              ><template v-if="panel.detail"> ({{ panel.detail }})</template></template
            >.
          </p>

          <template v-else>
            <div class="d-flex flex-wrap gap-3 align-items-end mb-2 insight-toolbar">
              <div class="insight-search-field">
                <label for="insight-search" class="form-label small mb-1">Search observations</label>
                <input
                  id="insight-search"
                  ref="searchInput"
                  v-model="query"
                  type="search"
                  class="form-control form-control-sm insight-search"
                  placeholder="Search checks, routes, tables, loggers…"
                  aria-describedby="insight-result-count"
                />
              </div>
              <div class="insight-topic-field">
                <label for="insight-topic" class="form-label small mb-1">Topic</label>
                <select
                  id="insight-topic"
                  v-model="theme"
                  class="form-select form-select-sm insight-topic"
                  aria-describedby="insight-result-count"
                >
                  <option v-for="topic in themes" :key="topic.id" :value="topic.id">{{ topic.label }}</option>
                </select>
              </div>
              <button
                v-if="filtersActive"
                type="button"
                class="btn btn-outline-secondary btn-sm insight-clear-filters"
                @click="clearFilters"
              >
                Clear filters
              </button>
            </div>
            <p id="insight-result-count" class="text-muted small mb-1" role="status">
              <template v-if="filtersActive">
                {{ formatNumber(filteredObservations.length) }} of {{ formatNumber(counts.total) }} observations match
                your filters, in {{ formatNumber(groups.length) }} {{ groups.length === 1 ? 'group' : 'groups' }}.
              </template>
              <template v-else>
                All {{ formatNumber(counts.total) }} {{ counts.total === 1 ? 'observation' : 'observations' }}, in
                {{ formatNumber(groups.length) }} {{ groups.length === 1 ? 'group' : 'groups' }}. Nothing excluded.
              </template>
            </p>
            <p class="text-muted small mb-3 insight-review-order">
              Specific review candidates first, not a severity score. Observations describe recorded behavior, not
              confirmed defects.
            </p>

            <p v-if="groups.length === 0" class="text-muted small mb-3 insight-no-matches">
              No observations match your filters. Change the search or topic, or clear filters.
            </p>

            <ul v-else class="list-unstyled mb-0 insight-list" aria-label="Observation groups">
              <li
                v-for="group in groups"
                :key="group.kind"
                class="insight-group"
                :class="{open: groupExpanded(group.kind)}"
              >
                <h3 class="m-0">
                  <button
                    :id="groupId(group.kind)"
                    type="button"
                    class="insight-group-toggle bootui-keyboard-target"
                    :aria-expanded="groupExpanded(group.kind) ? 'true' : 'false'"
                    :aria-controls="`${groupId(group.kind)}-detail`"
                    :aria-labelledby="`${groupId(group.kind)}-title ${groupId(group.kind)}-count`"
                    :aria-describedby="group.summary ? `${groupId(group.kind)}-summary` : undefined"
                    @click="toggleGroup(group)"
                  >
                    <span class="insight-group-main">
                      <span :id="`${groupId(group.kind)}-title`" class="insight-group-title">{{ group.title }}</span>
                      <span v-if="group.summary" :id="`${groupId(group.kind)}-summary`" class="insight-group-summary">
                        {{ group.summary }}
                      </span>
                    </span>
                    <span :id="`${groupId(group.kind)}-count`" class="insight-group-count">
                      {{ formatNumber(group.observations.length) }}
                      {{ group.observations.length === 1 ? 'observation' : 'observations' }}
                    </span>
                    <i class="bi bi-chevron-right insight-group-chevron" aria-hidden="true"></i>
                  </button>
                </h3>
                <div
                  v-show="groupExpanded(group.kind)"
                  :id="`${groupId(group.kind)}-detail`"
                  class="insight-group-content"
                >
                  <section
                    v-if="groupExpanded(group.kind) && selected"
                    :id="rowId(selected.id)"
                    class="insight-row open insight-detail"
                    aria-live="polite"
                    aria-labelledby="insight-sentence"
                  >
                    <div v-if="group.observations.length > 1" class="insight-observation-navigation mb-3">
                      <div class="insight-observation-field">
                        <div class="d-flex justify-content-between gap-2 mb-1">
                          <label :for="`${groupId(group.kind)}-observation`" class="small">Observation</label>
                          <span :id="`${groupId(group.kind)}-position`" class="small text-muted insight-position">
                            {{ formatNumber(observationIndex(group) + 1) }} of
                            {{ formatNumber(group.observations.length) }}
                          </span>
                        </div>
                        <select
                          :id="`${groupId(group.kind)}-observation`"
                          v-model="selectedId"
                          class="form-select form-select-sm insight-observation-select"
                          :aria-describedby="`${groupId(group.kind)}-position`"
                        >
                          <option
                            v-for="observation in group.observations"
                            :key="observation.id"
                            :value="observation.id"
                          >
                            {{ observationLabel(observation) }}
                          </option>
                        </select>
                      </div>
                      <div class="d-flex gap-2 insight-observation-paging">
                        <button
                          type="button"
                          class="btn btn-sm btn-outline-secondary"
                          aria-label="Previous observation"
                          :disabled="observationIndex(group) <= 0"
                          @click="stepObservation(group, -1)"
                        >
                          Previous
                        </button>
                        <button
                          type="button"
                          class="btn btn-sm btn-outline-secondary"
                          aria-label="Next observation"
                          :disabled="observationIndex(group) >= group.observations.length - 1"
                          @click="stepObservation(group, 1)"
                        >
                          Next
                        </button>
                      </div>
                    </div>
                    <p v-else class="insight-observation-subject mb-3">
                      <code class="bootui-break-anywhere">{{ selected.subject }}</code>
                    </p>
                    <p v-if="selected.status !== 'OBSERVED'" class="insight-row-limit mb-3">
                      Evidence limit:
                      {{
                        selected.status === 'PARTIAL' ? 'Some source events were dropped' : statusLabel(selected.status)
                      }}
                    </p>
                    <InsightDetail />
                  </section>
                </div>
              </li>
            </ul>
          </template>
        </div>

        <div
          v-show="tab === 'changes'"
          id="insights-panel-changes"
          role="tabpanel"
          aria-labelledby="insights-tab-changes"
        >
          <RunComparison
            class="mb-3"
            :refresh-key="lastFetched ?? 0"
            @loaded="onComparisonLoaded"
            @impact="showImpact"
          />
        </div>

        <div
          v-show="tab === 'impact'"
          id="insights-panel-impact"
          tabindex="-1"
          role="tabpanel"
          aria-labelledby="insights-tab-impact"
        >
          <ChangeImpact
            ref="impactTool"
            :initial-symbol="initialImpact"
            :changed="changedMethods"
            :changed-total="changedTotal"
            @show-coverage="showTab('coverage', 'insight-not-exercised')"
          />
        </div>

        <div
          v-show="tab === 'profile'"
          id="insights-panel-profile"
          tabindex="-1"
          role="tabpanel"
          aria-labelledby="insights-tab-profile"
        >
          <ResourceProfile :read-only="readOnly" :read-only-reason="readOnlyReason" />
        </div>

        <div
          v-show="tab === 'coverage'"
          id="insights-panel-coverage"
          tabindex="-1"
          role="tabpanel"
          aria-labelledby="insights-tab-coverage"
        >
          <div class="row g-3">
            <div class="col-xl-6">
              <section class="card h-100 insight-window" aria-labelledby="insight-window-title">
                <div class="card-body">
                  <h2 id="insight-window-title" class="h6 mb-2">How this run was linked</h2>
                  <InsightCoverage />
                  <ul v-if="report.limitations?.length" class="small text-muted mb-0 mt-3 ps-3 insight-limitations">
                    <li v-for="limitation in report.limitations" :key="limitation">{{ limitation }}</li>
                  </ul>
                </div>
              </section>
            </div>
            <div class="col-xl-6">
              <section class="card h-100 insight-unrun" aria-labelledby="insight-unrun-title">
                <div class="card-body">
                  <h2 id="insight-unrun-title" class="h6 mb-2">Checks and their limits</h2>
                  <InsightCheckLimits v-if="unrun.length" />
                  <p v-else class="small text-muted mb-0">Every check ran without a caveat.</p>
                </div>
              </section>
            </div>
            <div class="col-12">
              <section
                id="insight-not-exercised"
                tabindex="-1"
                class="card insight-not-exercised"
                aria-labelledby="insight-not-exercised-title"
              >
                <div class="card-body">
                  <h2 id="insight-not-exercised-title" class="h6 mb-1">
                    Not exercised in this run
                    <span v-if="notExercisedCount > 0" class="text-muted fw-normal">
                      · {{ formatNumber(notExercisedCount) }}
                    </span>
                  </h2>
                  <InsightNotExercised v-if="report.notExercised?.length" />
                  <p v-else class="small text-muted mb-0">Every declared route was reached in this run.</p>
                </div>
              </section>
            </div>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.insight-verdict-title {
  font-size: 1.15rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.insight-verdict-facts {
  color: var(--bootui-text-muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 0.875rem;
  gap: 0.35rem 1.25rem;
  list-style: none;
  margin: 0;
  padding: 0;
}

.insight-verdict-facts .bi {
  margin-right: 0.25rem;
}

.insight-search-field {
  flex: 1 1 18rem;
  min-width: 0;
  max-width: 32rem;
}

.insight-topic-field {
  flex: 0 1 12rem;
  min-width: 0;
}

.insight-row-limit {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
}

.insight-list {
  display: flow-root;
}

.insight-group {
  background: var(--bootui-surface-solid);
  border-inline: 1px solid var(--bootui-border);
  overflow: hidden;
}

.insight-group:first-child {
  border-top: 1px solid var(--bootui-border);
  border-top-left-radius: var(--bootui-radius-lg);
  border-top-right-radius: var(--bootui-radius-lg);
}

.insight-group:last-child,
.insight-group:has(+ .insight-group.open) {
  border-bottom: 1px solid var(--bootui-border);
  border-bottom-left-radius: var(--bootui-radius-lg);
  border-bottom-right-radius: var(--bootui-radius-lg);
}

.insight-group.open + .insight-group {
  border-top-left-radius: var(--bootui-radius-lg);
  border-top-right-radius: var(--bootui-radius-lg);
}

.insight-group + .insight-group,
.insight-group-content {
  border-top: 1px solid var(--bootui-border);
}

.insight-group.open {
  margin-block: 1rem;
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
}

.insight-group-toggle {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--bootui-text);
  display: grid;
  font-size: 1rem;
  font-weight: 400;
  gap: 0.35rem 1rem;
  grid-template-columns: minmax(0, 1fr) auto auto;
  padding: 0.85rem 1rem;
  text-align: start;
  transition: background-color 150ms ease;
  width: 100%;
}

.insight-group-toggle:focus-visible {
  outline-offset: -2px;
}

.insight-group-toggle:hover,
.insight-group.open > h3 .insight-group-toggle {
  background: var(--bootui-nav-hover-bg);
}

.insight-group-main {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}

.insight-group-title {
  font-weight: 600;
}

.insight-group-summary,
.insight-group-count {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
}

.insight-group-count {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.insight-group-chevron {
  color: var(--bootui-text-muted);
  transition: transform 150ms ease;
}

.insight-group.open > h3 .insight-group-chevron {
  transform: rotate(90deg);
}

.insight-observation-navigation {
  align-items: center;
  display: grid;
  gap: 0.75rem 1rem;
  grid-template-columns: minmax(0, 1fr) auto;
}

.insight-observation-field {
  min-width: 0;
}

.insight-observation-select {
  font-family: var(--bs-font-monospace);
}

.insight-observation-paging {
  align-self: end;
}

.insight-position {
  font-variant-numeric: tabular-nums;
}

.insight-detail {
  padding: 1rem 1.25rem 1.25rem;
}

@media (max-width: 575.98px) {
  .insight-group-toggle {
    column-gap: 0.5rem;
    padding-inline: 0.75rem;
  }

  .insight-group-main {
    display: contents;
  }

  .insight-group-title {
    grid-column: 1;
    grid-row: 1;
  }

  .insight-group-summary {
    grid-column: 1 / 3;
    grid-row: 2;
  }

  .insight-group-count {
    grid-column: 2;
    grid-row: 1;
  }

  .insight-group-chevron {
    grid-column: 3;
    grid-row: 1 / 3;
  }

  .insight-observation-navigation {
    grid-template-columns: minmax(0, 1fr);
  }

  .insight-observation-paging {
    justify-content: flex-end;
  }

  .insight-detail {
    padding: 0.85rem 1rem 1rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .insight-group-toggle,
  .insight-group-chevron {
    transition: none;
  }
}
</style>
