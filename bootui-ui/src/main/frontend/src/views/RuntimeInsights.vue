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
  isListed,
  numericColumns,
  themeFilters,
  unlistedSummary
} from '../utils/runtimeInsights.js'
import {insightMarkdown} from '../utils/markdownExport.js'
import {codeChanges, comparisonSummary} from '../utils/runComparison.js'
import ChangeImpact from './components/ChangeImpact.vue'
import InsightCheckLimits from './components/InsightCheckLimits.vue'
import InsightCoverage from './components/InsightCoverage.vue'
import InsightDetail from './components/InsightDetail.vue'
import InsightNotExercised from './components/InsightNotExercised.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import PanelTabs from './components/PanelTabs.vue'
import ResourceProfile from './components/ResourceProfile.vue'
import RunComparison from './components/RunComparison.vue'

// Runtime Insights (docs/PLAN-v2.md §5.5): the runtime journal's retained events projected into observations. Every
// read is a GET of what the journal already recorded; opening the panel starts no capture, scan, or network call.
// The panel leads with a verdict on the run, then one list of findings that open in place; the run's changes, change
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
// Show all routes (docs/PLAN-v2.md M4-19): the default list leaves out short routes and other noise; ?all=1, as the
// Memory panel links, or a deep link to a row the default leaves out shows every row.
const showAll = ref(route?.query?.all === '1' || route?.query?.all === 'true')
let deepLinkChecked = false
const initialImpact = typeof route?.query?.impact === 'string' ? route.query.impact : ''
// The panels that show some checks' findings in place of this list, as the engine's default listing says per check.
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
        if (!isListed(linked)) showAll.value = true
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
  theme: theme.value,
  all: showAll.value,
  selectedId: selectedId.value
}))
const groups = computed(() => groupObservations(report.value, filters.value))
// One list in the report's check order, each row carrying its check's title.
const rows = computed(() =>
  groups.value.flatMap((group) => group.observations.map((observation) => ({observation, title: group.title})))
)
const visibleObservations = computed(() => rows.value.map((row) => row.observation))
const themes = computed(() => themeFilters(report.value, filters.value))
// The verdict speaks for the run, whatever the list is filtered to.
const listed = computed(() =>
  groupObservations(report.value, {all: showAll.value, selectedId: selectedId.value}).flatMap(
    (group) => group.observations
  )
)
const counts = computed(() => ({total: listed.value.length}))
const unlisted = computed(() => unlistedSummary(report.value, filters.value))
const anyUnlisted = computed(() => (report.value?.observations ?? []).some((observation) => !isListed(observation)))
// The rows the default list leaves out, counted from the same source as the verdict: an open row it leaves out is
// already shown, so it is counted once, as listed.
const hiddenByDefault = computed(() =>
  showAll.value
    ? 0
    : (report.value?.observations ?? []).filter(
        (observation) => !isListed(observation) && observation.id !== selectedId.value
      ).length
)
const unlistedText = computed(() =>
  unlisted.value.groups.map((group) => `${formatNumber(group.count)} ${group.title}`).join(', ')
)
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
const selected = computed(() => visibleObservations.value.find((observation) => observation.id === selectedId.value))
watch(selectedId, () => (aiExport.value = null))

const tabs = computed(() => [
  {id: 'findings', label: 'Findings', icon: 'bi-search', count: listed.value.length},
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

// A row stays open while it is still listed, so a refresh never loses the developer's place; one the filters leave out
// closes. Nothing opens on its own: the verdict and the list come first.
watch(visibleObservations, (observations) => {
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

function toggle(id) {
  selectedId.value = selectedId.value === id ? null : id
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
              <template v-else-if="counts.total === 0">Nothing listed by default {{ scope }}</template>
              <template v-else>
                {{ formatNumber(counts.total) }} {{ counts.total === 1 ? 'thing' : 'things' }} to check {{ scope }}
              </template>
            </h2>
            <ul class="insight-verdict-facts">
              <li v-if="hiddenByDefault > 0">
                <i class="bi bi-eye-slash" aria-hidden="true"></i>
                {{ formatNumber(hiddenByDefault) }} more not listed by default
              </li>
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
            Some checks show their findings in other panels:
            <template v-for="(panel, index) in ELSEWHERE" :key="panel.path"
              >{{ index === 0 ? '' : index === ELSEWHERE.length - 1 ? ', and ' : ', '
              }}<router-link :to="panel.path" class="btn btn-outline-secondary btn-sm">{{ panel.title }}</router-link
              ><template v-if="panel.detail"> ({{ panel.detail }})</template></template
            >.
          </p>

          <template v-else>
            <div class="d-flex flex-wrap gap-2 align-items-center mb-3 insight-toolbar">
              <input
                v-model="query"
                type="search"
                class="form-control form-control-sm insight-search"
                aria-label="Search observations by check, route, table, or logger"
                placeholder="Search checks, routes, tables, loggers…"
              />
              <div class="d-flex flex-wrap gap-1" role="group" aria-label="Filter observations by theme">
                <button
                  v-for="chip in themes"
                  :key="chip.id || 'all'"
                  type="button"
                  class="btn btn-sm insight-theme"
                  :class="theme === chip.id ? 'btn-primary' : 'btn-outline-secondary'"
                  :aria-pressed="theme === chip.id"
                  @click="theme = chip.id"
                >
                  {{ chip.label }} <span class="insight-theme-count">{{ formatNumber(chip.count) }}</span>
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
                Show all routes<template v-if="hiddenByDefault > 0">
                  · {{ formatNumber(hiddenByDefault) }} more</template
                >
              </button>
            </div>

            <p v-if="rows.length === 0" class="text-muted small mb-3 insight-none-listed">
              <template v-if="unlisted.total > 0">
                Nothing is listed by default here. {{ formatNumber(unlisted.total) }} not listed: {{ unlistedText }}.
                <button type="button" class="btn btn-outline-secondary btn-sm align-baseline" @click="showAll = true">
                  Show all routes
                </button>
              </template>
              <template v-else>No observation matches this search.</template>
            </p>

            <ul v-else class="list-unstyled mb-0 insight-list" aria-label="Observations">
              <li
                v-for="{observation, title} in rows"
                :id="rowId(observation.id)"
                :key="observation.id"
                class="insight-row"
                :class="{open: observation.id === selectedId}"
              >
                <button
                  type="button"
                  class="insight-row-toggle insight-item bootui-keyboard-target"
                  :aria-expanded="observation.id === selectedId ? 'true' : 'false'"
                  :aria-controls="`${rowId(observation.id)}-detail`"
                  @click="toggle(observation.id)"
                >
                  <span class="insight-row-main">
                    <span class="insight-row-kind">{{ title }}</span>
                    <code class="bootui-break-anywhere">{{ observation.subject }}</code>
                  </span>
                  <span class="insight-row-meta">
                    <span v-if="observation.eligible > 0" class="insight-row-count">
                      {{ formatNumber(observation.affected) }} of {{ formatNumber(observation.eligible) }} requests
                    </span>
                    <span v-if="observation.status !== 'OBSERVED'" class="insight-row-status">
                      {{ statusLabel(observation.status) }}
                    </span>
                    <span v-if="!isListed(observation)" class="insight-unlisted-label">Not listed by default</span>
                  </span>
                  <i class="bi bi-chevron-down insight-row-chevron" aria-hidden="true"></i>
                </button>
                <section
                  v-if="observation.id === selectedId && selected"
                  :id="`${rowId(observation.id)}-detail`"
                  class="insight-detail"
                  aria-live="polite"
                  aria-labelledby="insight-sentence"
                >
                  <InsightDetail />
                </section>
              </li>
            </ul>

            <p v-if="rows.length > 0 && unlisted.total > 0" class="small text-muted mt-2 mb-0 insight-unlisted">
              {{ formatNumber(unlisted.total) }} more not listed by default: {{ unlistedText }}.
              <button type="button" class="btn btn-outline-secondary btn-sm align-baseline" @click="showAll = true">
                Show all routes
              </button>
            </p>
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

.insight-search {
  max-width: 100%;
  width: 18rem;
}

.insight-theme-count {
  font-variant-numeric: tabular-nums;
}

.insight-list {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  overflow: hidden;
}

.insight-row + .insight-row {
  border-top: 1px solid var(--bootui-border);
}

.insight-row-toggle {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--bootui-text);
  display: grid;
  gap: 0.35rem 1rem;
  grid-template-columns: minmax(0, 1fr) auto auto;
  padding: 0.75rem 1rem;
  text-align: start;
  transition: background-color 150ms ease;
  width: 100%;
}

.insight-row-toggle:focus-visible {
  outline-offset: -2px;
}

.insight-row-toggle:hover,
.insight-row.open .insight-row-toggle {
  background: var(--bootui-nav-hover-bg);
}

.insight-row-main {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}

.insight-row-kind {
  font-weight: 600;
}

.insight-row-meta {
  align-items: center;
  color: var(--bootui-text-muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 0.875rem;
  gap: 0.25rem 0.9rem;
  justify-content: flex-end;
}

.insight-row-count {
  font-variant-numeric: tabular-nums;
}

.insight-unlisted-label {
  font-style: italic;
}

.insight-row-chevron {
  color: var(--bootui-text-muted);
  transition: transform 150ms ease;
}

.insight-row.open .insight-row-chevron {
  transform: rotate(180deg);
}

.insight-detail {
  border-top: 1px solid var(--bootui-border);
  padding: 1rem 1.25rem 1.25rem;
}

@media (max-width: 575.98px) {
  .insight-row-toggle {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .insight-row-meta {
    grid-column: 1 / -1;
    grid-row: 2;
    justify-content: flex-start;
  }

  .insight-detail {
    padding: 0.85rem 1rem 1rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .insight-row-toggle,
  .insight-row-chevron {
    transition: none;
  }
}
</style>
