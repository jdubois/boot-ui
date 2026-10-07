<script setup>
import {computed, inject, nextTick, ref} from 'vue'
import {formatNumber} from '../../utils/format.js'
import {isListed} from '../../utils/runtimeInsights.js'
import ChangeImpact from './ChangeImpact.vue'
import InsightCheckLimits from './InsightCheckLimits.vue'
import InsightCoverage from './InsightCoverage.vue'
import InsightDetail from './InsightDetail.vue'
import InsightNotExercised from './InsightNotExercised.vue'
import InsightValidationMark from './InsightValidationMark.vue'
import PanelTabs from './PanelTabs.vue'
import ResourceProfile from './ResourceProfile.vue'
import RunComparison from './RunComparison.vue'

// Proposal A (temporary, ?insightsLayout=a): tabs by the question the developer asks. Findings is a master-detail
// list; what changed, what was not exercised, the profiler, and how far the run can be trusted each get a tab.
const ctx = inject('runtimeInsights')

const tab = ref(ctx.initialImpact ? 'changes' : 'findings')
const detailEl = ref(null)

const tabs = computed(() => [
  {id: 'findings', label: 'Findings', icon: 'bi-search', count: ctx.visibleObservations.length},
  {id: 'changes', label: 'Changes', icon: 'bi-arrow-left-right', count: null},
  {id: 'not-exercised', label: 'Not exercised', icon: 'bi-signpost-split', count: notExercisedCount.value},
  {id: 'profile', label: 'Profile', icon: 'bi-cpu', count: null},
  {id: 'coverage', label: 'Coverage & limits', icon: 'bi-bullseye', count: null}
])

const notExercisedCount = computed(() => (ctx.report.notExercised?.length ?? 0) + (ctx.report.notExercisedOmitted ?? 0))
const linkedShare = computed(() => ctx.coverage.segments.find((segment) => segment.id === 'request')?.share ?? 0)

async function open(id) {
  ctx.select(id)
  // Stacked below the list on a narrow screen, the detail is brought into view rather than left below the fold.
  if (window.matchMedia?.('(max-width: 991.98px)').matches) {
    await nextTick()
    detailEl.value?.scrollIntoView?.({block: 'start', behavior: 'smooth'})
  }
}
</script>

<template>
  <div class="insights-by-question">
    <p class="small mb-3 insights-run-line">
      <span class="fw-semibold">This run</span>
      <span class="text-muted">
        · {{ ctx.windowText }} · {{ linkedShare }} % linked to their request ·
        {{
          ctx.comparisonReady ? ctx.comparisonText || 'No previous run to compare' : 'Comparing with the previous run…'
        }}
      </span>
      <button
        v-if="ctx.compared"
        type="button"
        class="btn btn-link btn-sm p-0 ms-1 align-baseline"
        @click="tab = 'changes'"
      >
        See what changed
      </button>
    </p>

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
      <div v-if="ctx.empty === 'no-requests'" class="alert alert-secondary insight-empty">
        <strong>No HTTP requests recorded in this run yet.</strong>
        <span class="d-block small">
          Exercise your application, then refresh. Scheduled jobs and consumed messages are also checked when their
          sources are enabled; Coverage &amp; limits says which checks ran.
        </span>
      </div>
      <div v-else-if="ctx.empty === 'nothing-observed'" class="alert alert-secondary insight-empty">
        <strong>Nothing to report across {{ formatNumber(ctx.report.window.requests) }} requests.</strong>
        <span class="d-block small">
          {{ ctx.evaluated.length }} of {{ ctx.report.checks.length }} checks ran and found nothing; Coverage &amp;
          limits says why the others did not.
        </span>
      </div>
      <template v-else>
        <div class="d-flex flex-wrap gap-2 align-items-center mb-3 insights-toolbar">
          <input
            v-model="ctx.query"
            type="search"
            class="form-control form-control-sm insight-search"
            aria-label="Search observations by route, table, or logger"
            placeholder="Search routes, tables, loggers…"
          />
          <select v-model="ctx.theme" class="form-select form-select-sm insights-theme" aria-label="Theme">
            <option value="">All themes</option>
            <option v-for="chip in ctx.themes" :key="chip.id" :value="chip.id">{{ chip.label }}</option>
          </select>
          <button
            v-if="ctx.anyUnlisted"
            type="button"
            class="btn btn-sm insight-show-all"
            :class="ctx.showAll ? 'btn-primary' : 'btn-outline-secondary'"
            :aria-pressed="ctx.showAll"
            title="Also list short routes and the other rows the default list leaves out"
            @click="ctx.showAll = !ctx.showAll"
          >
            Show all routes<template v-if="!ctx.showAll && ctx.unlisted.total > 0">
              ({{ formatNumber(ctx.unlisted.total) }} more)</template
            >
          </button>
        </div>

        <p v-if="ctx.groups.length === 0" class="text-muted small mb-3 insight-none-listed">
          <template v-if="ctx.unlisted.total > 0">
            Nothing is listed by default here. {{ formatNumber(ctx.unlisted.total) }} not listed:
            {{ ctx.unlistedText }}.
          </template>
          <template v-else>No observation matches this search.</template>
        </p>

        <div v-else class="row g-3">
          <div class="col-lg-5 col-xl-4">
            <nav aria-label="Observations" class="insights-master">
              <section v-for="group in ctx.groups" :key="group.kind" class="insights-group">
                <h2 class="insights-group-title">
                  <span>{{ group.title }}</span>
                  <InsightValidationMark v-if="group.validation" :validation="group.validation" />
                </h2>
                <ul class="list-unstyled mb-0">
                  <li v-for="observation in group.observations" :key="observation.id">
                    <button
                      type="button"
                      class="insights-row insight-item"
                      :class="{selected: observation.id === ctx.selectedId}"
                      :aria-current="observation.id === ctx.selectedId ? 'true' : undefined"
                      @click="open(observation.id)"
                    >
                      <code class="bootui-break-anywhere">{{ observation.subject }}</code>
                      <span class="insights-row-meta">
                        <span v-if="observation.eligible > 0">
                          {{ formatNumber(observation.affected) }} of {{ formatNumber(observation.eligible) }} requests
                        </span>
                        <span v-if="observation.status !== 'OBSERVED'"
                          >· {{ ctx.statusLabel(observation.status) }}</span
                        >
                        <span v-if="!isListed(observation)" class="insight-unlisted-label"
                          >· Not listed by default</span
                        >
                      </span>
                    </button>
                  </li>
                </ul>
              </section>
            </nav>
          </div>
          <div class="col-lg-7 col-xl-8">
            <section
              v-if="ctx.selected"
              ref="detailEl"
              class="card insight-detail insights-detail"
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
    </div>

    <div v-show="tab === 'changes'" id="insights-panel-changes" role="tabpanel" aria-labelledby="insights-tab-changes">
      <RunComparison class="mb-3" :refresh-key="ctx.lastFetched ?? 0" @loaded="ctx.onComparisonLoaded" />
      <ChangeImpact :initial-symbol="ctx.initialImpact" />
    </div>

    <div
      v-show="tab === 'not-exercised'"
      id="insights-panel-not-exercised"
      role="tabpanel"
      aria-labelledby="insights-tab-not-exercised"
    >
      <section class="card insight-not-exercised" aria-labelledby="insight-not-exercised-title">
        <div class="card-body">
          <h2 id="insight-not-exercised-title" class="h6 mb-1">Routes no request reached</h2>
          <template v-if="notExercisedCount > 0">
            <p class="small text-muted mb-3">
              Nothing in Findings speaks for these declared routes: exercise them, then refresh.
            </p>
            <InsightNotExercised hide-intro />
          </template>
          <p v-else class="small text-muted mb-0">Every declared route was reached in this run.</p>
        </div>
      </section>
    </div>

    <div v-show="tab === 'profile'" id="insights-panel-profile" role="tabpanel" aria-labelledby="insights-tab-profile">
      <ResourceProfile :read-only="ctx.readOnly" :read-only-reason="ctx.readOnlyReason" />
    </div>

    <div
      v-show="tab === 'coverage'"
      id="insights-panel-coverage"
      role="tabpanel"
      aria-labelledby="insights-tab-coverage"
    >
      <div class="row g-3">
        <div class="col-xl-6">
          <section class="card h-100 insight-window" aria-labelledby="insight-window-title">
            <div class="card-body">
              <h2 id="insight-window-title" class="h6 mb-2">How this run was linked</h2>
              <InsightCoverage />
              <ul v-if="ctx.report.limitations?.length" class="small text-muted mb-0 mt-3 insights-limitations">
                <li v-for="limitation in ctx.report.limitations" :key="limitation">{{ limitation }}</li>
              </ul>
            </div>
          </section>
        </div>
        <div class="col-xl-6">
          <section class="card h-100 insight-unrun" aria-labelledby="insight-unrun-title">
            <div class="card-body">
              <h2 id="insight-unrun-title" class="h6 mb-2">Checks and their limits</h2>
              <InsightCheckLimits v-if="ctx.unrun.length" />
              <p v-else class="small text-muted mb-0">Every check ran without a caveat.</p>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.insights-run-line {
  max-width: 120ch;
}

.insight-search {
  max-width: 22rem;
}

.insights-theme {
  width: auto;
}

.insights-group + .insights-group {
  margin-top: 1rem;
}

.insights-group-title {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  font-size: 1rem;
  font-weight: 700;
  gap: 0.25rem 0.6rem;
  margin-bottom: 0.4rem;
}

.insights-row {
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--bootui-radius-sm);
  color: var(--bootui-text);
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  padding: 0.45rem 0.65rem;
  text-align: start;
  transition:
    background-color 150ms ease,
    border-color 150ms ease;
  width: 100%;
}

.insights-row:hover {
  background: var(--bootui-nav-hover-bg);
}

.insights-row.selected {
  background: var(--bootui-nav-hover-bg);
  border-color: var(--bootui-border-alt);
}

.insights-row.selected code {
  color: var(--bootui-nav-hover-color);
  font-weight: 600;
}

.insights-row-meta {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
}

.insight-unlisted-label {
  font-style: italic;
}

@media (min-width: 992px) {
  .insights-detail {
    max-height: calc(100vh - 2rem);
    overflow-y: auto;
    position: sticky;
    top: 1rem;
  }
}

.insights-limitations {
  padding-left: 1.1rem;
}

@media (prefers-reduced-motion: reduce) {
  .insights-row {
    transition: none;
  }
}
</style>
