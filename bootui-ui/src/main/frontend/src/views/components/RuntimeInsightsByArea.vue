<script setup>
import {computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {formatNumber} from '../../utils/format.js'
import {THEMES, isListed} from '../../utils/runtimeInsights.js'
import {AREAS, areaOf, areaTabs} from '../../utils/runtimeInsightsLayouts.js'
import ChangeImpact from './ChangeImpact.vue'
import InsightCheckLimits from './InsightCheckLimits.vue'
import InsightCoverage from './InsightCoverage.vue'
import InsightDetail from './InsightDetail.vue'
import InsightNotExercised from './InsightNotExercised.vue'
import InsightValidationMark from './InsightValidationMark.vue'
import PanelTabs from './PanelTabs.vue'
import ResourceProfile from './ResourceProfile.vue'
import RunComparison from './RunComparison.vue'

// Proposal B (temporary, ?insightsLayout=b): tabs by area of the application, each counting its findings, over one
// full-width table; a row opens its evidence in a drawer. The area's own tools sit in its tab: the profiler under
// Performance, the comparison and the change impact under Changes, and the run's coverage in a tab of its own.
const ctx = inject('runtimeInsights')

// A theme deep link, such as the Memory panel's, opens the area that holds it; areas replace the theme filter here.
const linkedTheme = THEMES.find((theme) => theme.id === ctx.theme)
const tab = ref(ctx.initialImpact ? 'changes' : linkedTheme ? (areaOf(linkedTheme.kinds[0]) ?? 'all') : 'all')
if (linkedTheme) ctx.theme = ''

const tabs = computed(() => areaTabs(ctx.report, ctx.visibleObservations))
const areaIds = new Set(AREAS.map((area) => area.id))
const isArea = computed(() => areaIds.has(tab.value))
const showsFindings = computed(() => tab.value === 'all' || isArea.value)
const checkTitles = computed(() => new Map((ctx.report.checks ?? []).map((check) => [check.kind, check.title])))
const passed = computed(
  () => new Set((ctx.report.checks ?? []).filter((check) => check.validation === 'PASSED').map((check) => check.kind))
)

// Rows in the report's check order, gathered by area on the All tab and kept to one area on an area tab.
const sections = computed(() => {
  const groups = ctx.groups.filter((group) => tab.value === 'all' || areaOf(group.kind) === tab.value)
  const areas =
    tab.value === 'all'
      ? [...AREAS, {id: null, label: 'Other', icon: 'bi-three-dots', kinds: []}]
      : AREAS.filter((a) => a.id === tab.value)
  return areas
    .map((area) => ({
      ...area,
      rows: groups
        .filter((group) => areaOf(group.kind) === area.id)
        .flatMap((group) => group.observations.map((observation) => ({observation, group})))
    }))
    .filter((section) => section.rows.length > 0)
})
const rowCount = computed(() => sections.value.reduce((sum, section) => sum + section.rows.length, 0))
const areaChecks = computed(() => (ctx.report.checks ?? []).filter((check) => areaOf(check.kind) === tab.value))
const areaRan = computed(
  () => areaChecks.value.filter((check) => check.status === 'EVALUATED' || check.status === 'PARTIAL').length
)
const areaCaveats = computed(() => ctx.unrun.filter((check) => areaOf(check.kind) === tab.value))
const currentLabel = computed(() => tabs.value.find((entry) => entry.id === tab.value)?.label ?? '')

// The drawer: a modal dialog over the table, closed by Escape, its close button, or the backdrop; focus returns to
// the row that opened it.
const drawerEl = ref(null)
let opener = null

function open(id, event) {
  opener = event?.currentTarget ?? null
  ctx.select(id)
}

function close() {
  ctx.select(null)
  const target = opener
  opener = null
  nextTick(() => target?.focus?.())
}

watch(
  () => ctx.selected?.id,
  async (id) => {
    if (!id) return
    await nextTick()
    drawerEl.value?.focus?.()
  },
  {immediate: true}
)

function onKeydown(event) {
  if (!ctx.selected) return
  if (event.key === 'Escape') {
    close()
  } else if (event.key === 'Tab') {
    const focusable = drawerEl.value?.querySelectorAll(
      'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])'
    )
    if (!focusable?.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && (document.activeElement === first || document.activeElement === drawerEl.value)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

const linkedShare = computed(() => ctx.coverage.segments.find((segment) => segment.id === 'request')?.share ?? 0)
</script>

<template>
  <div class="insights-by-area">
    <PanelTabs
      class="mb-3"
      :tabs="tabs"
      :selected="tab"
      id-prefix="insights"
      label="Runtime Insights areas"
      @select="tab = $event"
    >
      <template #tab="{tab: entry}">
        <i :class="['bi', entry.icon]" aria-hidden="true"></i>
        <span>{{ entry.label }}</span>
        <span v-if="entry.count != null" class="bootui-tabs__count">{{ formatNumber(entry.count) }}</span>
      </template>
    </PanelTabs>

    <div :id="`insights-panel-${tab}`" role="tabpanel" :aria-labelledby="`insights-tab-${tab}`">
      <template v-if="showsFindings">
        <div class="d-flex flex-wrap gap-2 align-items-center justify-content-between mb-3">
          <div class="d-flex flex-wrap gap-3 align-items-center">
            <input
              v-model="ctx.query"
              type="search"
              class="form-control form-control-sm insight-search"
              :aria-label="`Search ${currentLabel === 'All' ? 'all' : currentLabel} observations by route, table, or logger`"
              placeholder="Search routes, tables, loggers…"
            />
            <button
              v-if="ctx.anyUnlisted"
              type="button"
              class="btn btn-sm insight-show-all"
              :class="ctx.showAll ? 'btn-primary' : 'btn-outline-secondary'"
              :aria-pressed="ctx.showAll"
              title="Also list short routes and the other rows the default list leaves out"
              @click="ctx.showAll = !ctx.showAll"
            >
              Show all routes
            </button>
          </div>
          <p class="small text-muted mb-0">
            {{ formatNumber(ctx.report.window.requests) }} requests · {{ linkedShare }} % linked to their request
          </p>
        </div>

        <div v-if="ctx.empty === 'no-requests'" class="alert alert-secondary insight-empty">
          <strong>No HTTP requests recorded in this run yet.</strong>
          <span class="d-block small">Exercise your application, then refresh.</span>
        </div>

        <section v-else-if="rowCount === 0" class="insights-area-empty" aria-live="polite">
          <p class="mb-1 fw-semibold">
            <template v-if="isArea">
              Nothing to check in {{ currentLabel }}: {{ areaRan }} of {{ areaChecks.length }} checks ran.
            </template>
            <template v-else>Nothing to check in this run.</template>
          </p>
          <p v-if="!ctx.showAll && ctx.unlisted.total > 0" class="small text-muted mb-0">
            {{ formatNumber(ctx.unlisted.total) }} rows are not listed by default: {{ ctx.unlistedText }}.
          </p>
        </section>

        <div v-else class="table-responsive insights-table-wrap">
          <table class="table align-middle mb-0 insights-table">
            <caption class="visually-hidden">
              {{
                currentLabel
              }}
              findings; open a row for its evidence
            </caption>
            <thead>
              <tr>
                <th scope="col">Finding</th>
                <th scope="col">Route or subject</th>
                <th scope="col" class="text-end">Requests</th>
                <th scope="col">Validation</th>
              </tr>
            </thead>
            <tbody v-for="section in sections" :key="section.id ?? 'other'">
              <tr v-if="tab === 'all'" class="insights-section-row">
                <th scope="rowgroup" colspan="4">
                  <i :class="['bi', section.icon]" aria-hidden="true"></i>
                  {{ section.label }}
                  <span class="text-muted fw-normal">· {{ section.rows.length }}</span>
                </th>
              </tr>
              <tr
                v-for="{observation, group} in section.rows"
                :key="observation.id"
                class="insights-table-row"
                :class="{selected: observation.id === ctx.selectedId}"
              >
                <td class="insights-finding">{{ checkTitles.get(observation.kind) ?? group.title }}</td>
                <td>
                  <button
                    type="button"
                    class="insights-open insight-item"
                    :aria-haspopup="'dialog'"
                    @click="open(observation.id, $event)"
                  >
                    <code class="bootui-break-anywhere">{{ observation.subject }}</code>
                  </button>
                  <span v-if="observation.status !== 'OBSERVED'" class="d-block small text-muted">
                    {{ ctx.statusLabel(observation.status) }}
                  </span>
                  <span v-if="!isListed(observation)" class="d-block small text-muted insight-unlisted-label">
                    Not listed by default
                  </span>
                </td>
                <td class="text-end text-nowrap insights-count">
                  <template v-if="observation.eligible > 0">
                    {{ formatNumber(observation.affected)
                    }}<span class="text-muted"> / {{ formatNumber(observation.eligible) }}</span>
                  </template>
                  <span v-else class="text-muted">—</span>
                </td>
                <td>
                  <InsightValidationMark v-if="group.validation" :validation="group.validation" />
                  <span v-else-if="passed.has(observation.kind)" class="insights-validated">
                    <i class="bi bi-patch-check" aria-hidden="true"></i>Validated
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <p v-if="rowCount > 0 && !ctx.showAll && ctx.unlisted.total > 0" class="small text-muted mt-2 mb-0">
          {{ formatNumber(ctx.unlisted.total) }} more not listed by default. Show all routes lists them.
        </p>

        <details v-if="isArea && areaCaveats.length" class="small mt-3 insights-area-caveats">
          <summary>{{ areaCaveats.length }} {{ currentLabel }} checks with a caveat</summary>
          <div class="mt-2"><InsightCheckLimits :checks="areaCaveats" /></div>
        </details>
      </template>

      <section v-if="tab === 'coverage'" class="row g-3" aria-label="Run coverage">
        <div class="col-xl-6">
          <section class="card h-100 insight-window" aria-labelledby="insight-window-title">
            <div class="card-body">
              <h2 id="insight-window-title" class="h6 mb-2">How this run was linked</h2>
              <InsightCoverage />
              <ul v-if="ctx.report.limitations?.length" class="small text-muted mb-0 mt-3 ps-3">
                <li v-for="limitation in ctx.report.limitations" :key="limitation">{{ limitation }}</li>
              </ul>
            </div>
          </section>
        </div>
        <div class="col-xl-6">
          <section class="card h-100 insight-unrun" aria-labelledby="insight-unrun-title">
            <div class="card-body">
              <h2 id="insight-unrun-title" class="h6 mb-2">Checks and their limits</h2>
              <InsightCheckLimits />
            </div>
          </section>
        </div>
        <div v-if="ctx.report.notExercised?.length" class="col-12">
          <section class="card insight-not-exercised" aria-labelledby="insight-not-exercised-title">
            <div class="card-body">
              <h2 id="insight-not-exercised-title" class="h6 mb-1">Not exercised in this run</h2>
              <InsightNotExercised />
            </div>
          </section>
        </div>
      </section>
    </div>

    <div v-show="tab === 'performance'" class="mt-3">
      <ResourceProfile :read-only="ctx.readOnly" :read-only-reason="ctx.readOnlyReason" />
    </div>
    <div v-show="tab === 'changes'" class="mt-3">
      <RunComparison class="mb-3" :refresh-key="ctx.lastFetched ?? 0" @loaded="ctx.onComparisonLoaded" />
      <ChangeImpact :initial-symbol="ctx.initialImpact" />
    </div>

    <div v-if="ctx.selected" class="insights-drawer-backdrop" @click.self="close">
      <aside
        ref="drawerEl"
        class="insights-drawer insight-detail"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        aria-labelledby="insights-drawer-title"
        aria-describedby="insight-sentence"
      >
        <header class="insights-drawer-header">
          <div>
            <h2 id="insights-drawer-title" class="h6 mb-1">{{ checkTitles.get(ctx.selected.kind) }}</h2>
            <InsightValidationMark v-if="ctx.selectedValidation" :validation="ctx.selectedValidation" />
          </div>
          <button class="btn-close" type="button" aria-label="Close" @click="close"></button>
        </header>
        <div class="insights-drawer-body">
          <InsightDetail />
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.insight-search {
  max-width: 100%;
  width: 22rem;
}

.insights-table-wrap {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
}

.insights-table {
  --bs-table-bg: transparent;
}

.insights-table thead th {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
  font-weight: 600;
  white-space: nowrap;
}

.insights-table > :not(caption) > * > * {
  padding: 0.65rem 0.9rem;
}

.insights-section-row th {
  background: var(--bootui-surface-alt);
  font-weight: 700;
}

.insights-table-row {
  cursor: pointer;
  position: relative;
}

/* The subject's button is the row's one control; stretched over the row, a click anywhere on it opens the drawer. */
.insights-open::after {
  content: '';
  inset: 0;
  position: absolute;
}

.insights-table-row:hover > td,
.insights-table-row.selected > td {
  background: var(--bootui-nav-hover-bg);
}

.insights-finding {
  font-weight: 600;
  min-width: 12rem;
}

.insights-open {
  background: none;
  border: 0;
  border-radius: var(--bootui-radius-xs);
  color: inherit;
  padding: 0;
  text-align: start;
}

.insights-open:hover code,
.insights-open:focus-visible code {
  text-decoration: underline;
}

.insights-count {
  font-variant-numeric: tabular-nums;
}

.insights-validated {
  align-items: center;
  color: var(--bootui-green-dark);
  display: inline-flex;
  font-size: 0.75rem;
  font-weight: 600;
  gap: 0.3rem;
  white-space: nowrap;
}

.insights-area-empty {
  background: var(--bootui-surface-alt);
  border: 1px dashed var(--bootui-border-alt);
  border-radius: var(--bootui-radius-lg);
  padding: 1.25rem;
}

.insights-area-caveats summary {
  color: var(--bootui-text-muted);
  cursor: pointer;
}

.insights-drawer-backdrop {
  background: rgba(15, 23, 42, 0.35);
  display: flex;
  inset: 0;
  justify-content: flex-end;
  position: fixed;
  z-index: 1050;
}

.insights-drawer {
  background: var(--bootui-surface-solid);
  box-shadow: var(--bootui-shadow-md);
  display: flex;
  flex-direction: column;
  height: 100%;
  width: min(760px, 100%);
}

.insights-drawer:focus {
  outline: none;
}

.insights-drawer:focus-visible {
  box-shadow:
    inset 0 0 0 2px var(--bootui-green),
    var(--bootui-shadow-md);
}

.insights-drawer-header {
  align-items: flex-start;
  border-bottom: 1px solid var(--bootui-border);
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  padding: 1rem 1.25rem;
}

.insights-drawer-body {
  flex: 1 1 auto;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 1.25rem;
}

@media (prefers-reduced-motion: no-preference) {
  .insights-drawer {
    animation: insights-drawer-in 180ms cubic-bezier(0.16, 1, 0.3, 1);
  }
}

@keyframes insights-drawer-in {
  from {
    transform: translateX(2rem);
    opacity: 0.6;
  }
}
</style>
