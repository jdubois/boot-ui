<script setup>
import {computed, inject, ref} from 'vue'
import {formatNumber} from '../../utils/format.js'
import {isListed, validationOf} from '../../utils/runtimeInsights.js'
import {themeTabs, validationCounts} from '../../utils/runtimeInsightsLayouts.js'
import ChangeImpact from './ChangeImpact.vue'
import InsightCheckLimits from './InsightCheckLimits.vue'
import InsightCoverage from './InsightCoverage.vue'
import InsightDetail from './InsightDetail.vue'
import InsightNotExercised from './InsightNotExercised.vue'
import InsightValidationMark from './InsightValidationMark.vue'
import PanelTabs from './PanelTabs.vue'
import ResourceProfile from './ResourceProfile.vue'
import RunComparison from './RunComparison.vue'

// Proposal C (temporary, ?insightsLayout=c): summary first. A verdict sentence says how many things to check and how
// far to trust them, one list follows with theme tabs as its filter, and a row opens in place. Everything about the
// run rather than its findings sits behind a single disclosure, itself split into tabs.
const ctx = inject('runtimeInsights')

const checks = computed(() => new Map((ctx.report.checks ?? []).map((check) => [check.kind, check])))
const rows = computed(() =>
  ctx.groups.flatMap((group) => group.observations.map((observation) => ({observation, title: group.title})))
)
// Theme counts ignore the theme itself, so each tab says what it would show.
const unfiltered = computed(() => {
  if (!ctx.theme) return ctx.visibleObservations
  const needle = ctx.query.trim().toLowerCase()
  return (ctx.report.observations ?? []).filter(
    (observation) =>
      (ctx.showAll || needle !== '' || isListed(observation) || observation.id === ctx.selectedId) &&
      (!needle || `${observation.subject} ${observation.sentence}`.toLowerCase().includes(needle))
  )
})
// A theme with nothing to show leaves the strip, unless it is the one selected, so no tab leads to an empty list.
const filterTabs = computed(() =>
  themeTabs(ctx.report, unfiltered.value).filter(
    (entry) => entry.id === 'all' || entry.count > 0 || entry.id === ctx.theme
  )
)
const counts = computed(() => validationCounts(ctx.report, ctx.visibleObservations))
const total = computed(() => (ctx.report.observations ?? []).length)
const linkedShare = computed(() => ctx.coverage.segments.find((segment) => segment.id === 'request')?.share ?? 0)

const runOpen = ref(Boolean(ctx.initialImpact))
const runTab = ref('changes')
const runTabs = computed(() => [
  {id: 'changes', label: 'Changes', count: null},
  {id: 'profile', label: 'Profile', count: null},
  {id: 'coverage', label: 'Coverage', count: null},
  {
    id: 'not-exercised',
    label: 'Not exercised',
    count: (ctx.report.notExercised?.length ?? 0) + (ctx.report.notExercisedOmitted ?? 0)
  },
  {id: 'limits', label: 'Check limits', count: ctx.unrun.length}
])
const runSection = ref(null)

function openRun(tab) {
  runTab.value = tab
  runOpen.value = true
  requestAnimationFrame(() => runSection.value?.scrollIntoView?.({block: 'start', behavior: 'smooth'}))
}

function onRunToggle(event) {
  runOpen.value = /** @type {HTMLDetailsElement} */ (event.target).open
}

function toggle(id) {
  ctx.select(ctx.selectedId === id ? null : id)
}

function selectTheme(id) {
  ctx.theme = id === 'all' ? '' : id
}
</script>

<template>
  <div class="insights-summary-first">
    <section class="card mb-3 insights-verdict" aria-labelledby="insights-verdict-title">
      <div class="card-body">
        <h2 id="insights-verdict-title" class="insights-verdict-title">
          <template v-if="ctx.empty === 'no-requests'">No HTTP requests recorded in this run yet</template>
          <template v-else-if="ctx.empty === 'nothing-observed'">
            Nothing to check across {{ formatNumber(ctx.report.window.requests) }} requests
          </template>
          <template v-else>
            {{ formatNumber(counts.total) }} {{ counts.total === 1 ? 'thing' : 'things' }} to check across
            {{ formatNumber(ctx.report.window.requests) }} requests
          </template>
        </h2>
        <ul class="insights-verdict-facts">
          <li v-if="counts.total > 0">
            <i class="bi bi-patch-check" aria-hidden="true"></i>
            {{ formatNumber(counts.validated) }} from
            {{ counts.validated === 1 ? 'a validated check' : 'validated checks' }}
          </li>
          <li v-if="counts.unvalidated > 0">
            <i class="bi bi-patch-question" aria-hidden="true"></i>
            {{ formatNumber(counts.unvalidated) }} from checks not externally validated
          </li>
          <li v-if="!ctx.showAll && ctx.unlisted.total > 0">
            <i class="bi bi-eye-slash" aria-hidden="true"></i>
            {{ formatNumber(ctx.unlisted.total) }} more not listed by default
          </li>
          <li>
            <i class="bi bi-link-45deg" aria-hidden="true"></i>
            {{ linkedShare }} % of events linked to their request
          </li>
          <li>
            <i class="bi bi-arrow-left-right" aria-hidden="true"></i>
            <button
              v-if="ctx.compared"
              type="button"
              class="btn btn-link btn-sm p-0 align-baseline"
              @click="openRun('changes')"
            >
              {{ ctx.comparisonText }}
            </button>
            <template v-else>{{
              ctx.comparisonReady ? ctx.comparisonText : 'Comparing with the previous run…'
            }}</template>
          </li>
        </ul>
        <p v-if="ctx.report.limitations?.length" class="small text-muted mb-0 mt-2">
          {{ ctx.report.limitations.length }} {{ ctx.report.limitations.length === 1 ? 'limit' : 'limits' }} on what
          this run can show:
          <button type="button" class="btn btn-link btn-sm p-0 align-baseline" @click="openRun('coverage')">
            see coverage
          </button>
        </p>
      </div>
    </section>

    <template v-if="!ctx.empty">
      <div class="d-flex flex-wrap gap-2 align-items-center justify-content-between mb-3">
        <PanelTabs
          :tabs="filterTabs"
          :selected="ctx.theme || 'all'"
          id-prefix="insights-filter"
          label="Filter findings by theme"
          @select="selectTheme"
        >
          <template #tab="{tab: entry}">
            <span>{{ entry.label }}</span>
            <span class="bootui-tabs__count">{{ formatNumber(entry.count) }}</span>
          </template>
        </PanelTabs>
        <div class="d-flex flex-wrap gap-2 align-items-center">
          <input
            v-model="ctx.query"
            type="search"
            class="form-control form-control-sm insight-search"
            aria-label="Search observations by route, table, or logger"
            placeholder="Search routes, tables, loggers…"
          />
          <div v-if="ctx.anyUnlisted" class="btn-group btn-group-sm" role="group" aria-label="Rows to list">
            <button
              type="button"
              class="btn"
              :class="!ctx.showAll ? 'btn-secondary' : 'btn-outline-secondary'"
              :aria-pressed="!ctx.showAll"
              @click="ctx.showAll = false"
            >
              Listed
            </button>
            <button
              type="button"
              class="btn insight-show-all"
              :class="ctx.showAll ? 'btn-secondary' : 'btn-outline-secondary'"
              :aria-pressed="ctx.showAll"
              @click="ctx.showAll = true"
            >
              Everything ({{ formatNumber(total) }})
            </button>
          </div>
        </div>
      </div>

      <div
        :id="`insights-filter-panel-${ctx.theme || 'all'}`"
        role="tabpanel"
        :aria-labelledby="`insights-filter-tab-${ctx.theme || 'all'}`"
      >
        <p v-if="rows.length === 0" class="text-muted small insight-none-listed">
          <template v-if="ctx.unlisted.total > 0">
            Nothing is listed by default here. {{ formatNumber(ctx.unlisted.total) }} not listed:
            {{ ctx.unlistedText }}.
          </template>
          <template v-else>No observation matches this search.</template>
        </p>
        <ul v-else class="list-unstyled mb-0 insights-findings">
          <li
            v-for="{observation, title} in rows"
            :key="observation.id"
            class="insights-finding"
            :class="{open: observation.id === ctx.selectedId}"
          >
            <button
              type="button"
              class="insights-finding-toggle insight-item"
              :aria-expanded="observation.id === ctx.selectedId ? 'true' : 'false'"
              :aria-controls="`insights-finding-${observation.id}`"
              @click="toggle(observation.id)"
            >
              <span class="insights-finding-main">
                <span class="insights-finding-kind">{{ title }}</span>
                <code class="bootui-break-anywhere">{{ observation.subject }}</code>
              </span>
              <span class="insights-finding-meta">
                <span v-if="observation.eligible > 0" class="insights-finding-count">
                  {{ formatNumber(observation.affected) }} of {{ formatNumber(observation.eligible) }} requests
                </span>
                <span v-if="observation.status !== 'OBSERVED'">{{ ctx.statusLabel(observation.status) }}</span>
                <span v-if="!isListed(observation)" class="insight-unlisted-label">Not listed by default</span>
                <InsightValidationMark
                  v-if="validationOf(checks.get(observation.kind))"
                  :validation="validationOf(checks.get(observation.kind))"
                />
              </span>
              <i class="bi bi-chevron-down insights-finding-chevron" aria-hidden="true"></i>
            </button>
            <section
              v-if="observation.id === ctx.selectedId && ctx.selected"
              :id="`insights-finding-${observation.id}`"
              class="insights-finding-body insight-detail"
              aria-live="polite"
              aria-labelledby="insight-sentence"
            >
              <InsightDetail />
            </section>
          </li>
        </ul>
      </div>
    </template>

    <details ref="runSection" class="mt-4 insights-run" :open="runOpen" @toggle="onRunToggle">
      <summary class="insights-run-summary">
        <i class="bi bi-chevron-right insights-run-chevron" aria-hidden="true"></i>
        <span class="fw-semibold">About this run</span>
        <span class="text-muted small">
          {{ ctx.windowText }} · changes since the last run, profiling, coverage, routes not exercised, and check limits
        </span>
      </summary>
      <div class="pt-3">
        <PanelTabs
          class="mb-3"
          :tabs="runTabs"
          :selected="runTab"
          id-prefix="insights-run"
          label="About this run"
          @select="runTab = $event"
        >
          <template #tab="{tab: entry}">
            <span>{{ entry.label }}</span>
            <span v-if="entry.count" class="bootui-tabs__count">{{ formatNumber(entry.count) }}</span>
          </template>
        </PanelTabs>
        <div
          v-show="runTab === 'changes'"
          id="insights-run-panel-changes"
          role="tabpanel"
          aria-labelledby="insights-run-tab-changes"
        >
          <RunComparison class="mb-3" :refresh-key="ctx.lastFetched ?? 0" @loaded="ctx.onComparisonLoaded" />
          <ChangeImpact :initial-symbol="ctx.initialImpact" />
        </div>
        <div
          v-show="runTab === 'profile'"
          id="insights-run-panel-profile"
          role="tabpanel"
          aria-labelledby="insights-run-tab-profile"
        >
          <ResourceProfile :read-only="ctx.readOnly" :read-only-reason="ctx.readOnlyReason" />
        </div>
        <div
          v-show="runTab === 'coverage'"
          id="insights-run-panel-coverage"
          role="tabpanel"
          aria-labelledby="insights-run-tab-coverage"
          class="insights-run-pane"
        >
          <InsightCoverage />
          <ul v-if="ctx.report.limitations?.length" class="small text-muted mb-0 mt-3 ps-3">
            <li v-for="limitation in ctx.report.limitations" :key="limitation">{{ limitation }}</li>
          </ul>
        </div>
        <div
          v-show="runTab === 'not-exercised'"
          id="insights-run-panel-not-exercised"
          role="tabpanel"
          aria-labelledby="insights-run-tab-not-exercised"
          class="insights-run-pane"
        >
          <InsightNotExercised v-if="ctx.report.notExercised?.length" />
          <p v-else class="small text-muted mb-0">Every declared route was reached in this run.</p>
        </div>
        <div
          v-show="runTab === 'limits'"
          id="insights-run-panel-limits"
          role="tabpanel"
          aria-labelledby="insights-run-tab-limits"
          class="insights-run-pane"
        >
          <InsightCheckLimits v-if="ctx.unrun.length" />
          <p v-else class="small text-muted mb-0">Every check ran without a caveat.</p>
        </div>
      </div>
    </details>
  </div>
</template>

<style scoped>
.insights-verdict-title {
  font-size: 1.15rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.insights-verdict-facts {
  color: var(--bootui-text-muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 0.875rem;
  gap: 0.35rem 1.25rem;
  list-style: none;
  margin: 0;
  padding: 0;
}

.insights-verdict-facts .bi {
  margin-right: 0.25rem;
}

.insight-search {
  max-width: 100%;
  width: 18rem;
}

.insights-run-chevron {
  color: var(--bootui-text-muted);
  transition: transform 150ms ease;
}

.insights-run[open] .insights-run-chevron {
  transform: rotate(90deg);
}

.insights-run-summary::-webkit-details-marker {
  display: none;
}

.insights-findings {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  overflow: hidden;
}

.insights-finding + .insights-finding {
  border-top: 1px solid var(--bootui-border);
}

.insights-finding-toggle {
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

.insights-finding-toggle:hover,
.insights-finding.open .insights-finding-toggle {
  background: var(--bootui-nav-hover-bg);
}

.insights-finding-main {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}

.insights-finding-kind {
  font-weight: 600;
}

.insights-finding-meta {
  align-items: center;
  color: var(--bootui-text-muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 0.875rem;
  gap: 0.25rem 0.9rem;
  justify-content: flex-end;
}

.insights-finding-count {
  font-variant-numeric: tabular-nums;
}

.insights-finding-chevron {
  color: var(--bootui-text-muted);
  transition: transform 150ms ease;
}

.insights-finding.open .insights-finding-chevron {
  transform: rotate(180deg);
}

.insights-finding-body {
  border-top: 1px solid var(--bootui-border);
  padding: 1rem 1.25rem 1.25rem;
}

.insight-unlisted-label {
  font-style: italic;
}

.insights-run {
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  padding: 0.75rem 1rem;
}

.insights-run-summary {
  cursor: pointer;
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.75rem;
  align-items: baseline;
}

.insights-run-pane {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  padding: 1.25rem;
}

@media (max-width: 575.98px) {
  .insights-finding-toggle {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .insights-finding-meta {
    grid-column: 1 / -1;
    grid-row: 2;
    justify-content: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .insights-finding-toggle,
  .insights-finding-chevron,
  .insights-run-chevron {
    transition: none;
  }
}
</style>
