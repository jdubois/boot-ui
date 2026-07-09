<script setup>
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {getJson} from '../api.js'
import {describeLoadError} from '../utils/loadError.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import PanelHeader from './components/PanelHeader.vue'
import ServerListFooter from './components/ServerListFooter.vue'

const GRAPH_LIMIT = 12
const NODE_WIDTH = 220
const NODE_HEIGHT = 62
const COLUMN_X = [20, 320, 620]

const view = ref('list')
const filter = ref('')
const classification = ref('')
const graphSearch = ref('')
const graph = ref(null)
const graphError = ref(null)
const graphLoading = ref(false)
const suggestions = ref([])
const conditions = ref([])
const conditionsLoading = ref(false)

let suggestionTimer = null
let suggestionRequest = 0
let graphRequest = 0

const {
  error,
  items: visibleBeans,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList('api/beans', 'beans', () => ({q: filter.value.trim(), classification: classification.value}), {
  errorContext: 'Could not load beans'
})

const graphHeight = computed(() => {
  const rows = Math.max(graph.value?.dependencies?.length || 0, graph.value?.dependents?.length || 0, 2)
  return Math.max(240, rows * 86 + 36)
})

const focusY = computed(() => (graphHeight.value - NODE_HEIGHT) / 2)
const dependencyNodes = computed(() => layoutColumn(graph.value?.dependencies || [], 0))
const dependentNodes = computed(() => layoutColumn(graph.value?.dependents || [], 2))
const focusNode = computed(() => (graph.value?.focus ? {...graph.value.focus, x: COLUMN_X[1], y: focusY.value} : null))

function layoutColumn(items, column) {
  if (!items.length) return []
  const contentHeight = items.length * NODE_HEIGHT + (items.length - 1) * 24
  const start = Math.max(18, (graphHeight.value - contentHeight) / 2)
  return items.map((item, index) => ({
    ...item,
    x: COLUMN_X[column],
    y: start + index * (NODE_HEIGHT + 24)
  }))
}

function shortName(value, max = 28) {
  if (!value || value.length <= max) return value || '—'
  return `${value.slice(0, max - 1)}…`
}

function showGraph(bean) {
  view.value = 'graph'
  graphSearch.value = bean.name
  suggestions.value = []
  return loadGraph(bean.name)
}

async function loadGraph(name) {
  const focus = (name || graphSearch.value).trim()
  if (!focus) return
  const id = ++graphRequest
  graphLoading.value = true
  graphError.value = null
  conditions.value = []
  try {
    const report = await getJson(`api/beans/graph?focus=${encodeURIComponent(focus)}&limit=${GRAPH_LIMIT}`)
    if (id !== graphRequest) return
    graph.value = report
    if (!report.available) {
      graphError.value = 'Bean inventory is unavailable.'
    } else if (!report.focus) {
      graphError.value = `Bean “${focus}” was not found.`
    } else {
      await loadConditions(report.focus, id)
    }
  } catch (e) {
    if (id === graphRequest) graphError.value = describeLoadError(e, 'Could not load bean graph')
  } finally {
    if (id === graphRequest) graphLoading.value = false
  }
}

function conditionClass(resource) {
  if (!resource) return null
  const match = resource.match(/(?:^|\[)([A-Za-z0-9_$/]+)\.class\]?$/)
  return match ? match[1].replaceAll('/', '.').replaceAll('$', '.') : null
}

async function loadConditions(bean, id) {
  const className = conditionClass(bean.resource)
  if (!className) return
  conditionsLoading.value = true
  try {
    const report = await getJson(`api/conditions?outcome=positive&q=${encodeURIComponent(className)}&limit=100`)
    if (id !== graphRequest) return
    conditions.value = (report.positiveMatches || []).filter((entry) => entry.autoConfigurationClass === className)
  } catch {
    // Conditions are optional enrichment; the graph remains useful without them.
  } finally {
    if (id === graphRequest) conditionsLoading.value = false
  }
}

function scheduleSuggestions() {
  suggestionRequest += 1
  const id = suggestionRequest
  if (suggestionTimer) clearTimeout(suggestionTimer)
  const query = graphSearch.value.trim()
  if (!query || query === graph.value?.focus?.name) {
    suggestions.value = []
    return
  }
  suggestionTimer = setTimeout(async () => {
    try {
      const result = await getJson(`api/beans?q=${encodeURIComponent(query)}&offset=0&limit=8`)
      if (id === suggestionRequest) suggestions.value = result.beans || []
    } catch {
      if (id === suggestionRequest) suggestions.value = []
    }
  }, 200)
}

function activateNode(event, bean) {
  if (event.type === 'click' || event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    showGraph(bean)
  }
}

function returnToList() {
  view.value = 'list'
  graphError.value = null
}

onMounted(load)
watch([filter, classification], scheduleReload)
watch(graphSearch, scheduleSuggestions)
onBeforeUnmount(() => {
  if (suggestionTimer) clearTimeout(suggestionTimer)
  graphRequest += 1
  suggestionRequest += 1
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-diagram-3"
      title="Beans"
      :subtitle="`${totalCount} beans · ${matchedCount} matched`"
      :error="view === 'list' ? error : graphError"
    />

    <div class="d-flex justify-content-between align-items-center gap-3 mb-3">
      <p class="text-muted mb-0">Inspect the runtime inventory or focus on one bean's immediate wiring.</p>
      <div class="view-toggle" role="group" aria-label="Beans view">
        <button
          type="button"
          class="view-toggle__button"
          :class="{active: view === 'list'}"
          :aria-pressed="view === 'list'"
          @click="returnToList"
        >
          <i class="bi bi-list-ul" aria-hidden="true"></i> List
        </button>
        <button
          type="button"
          class="view-toggle__button"
          :class="{active: view === 'graph'}"
          :aria-pressed="view === 'graph'"
          @click="view = 'graph'"
        >
          <i class="bi bi-share" aria-hidden="true"></i> Graph
        </button>
      </div>
    </div>

    <template v-if="view === 'list'">
      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input
            v-model="filter"
            class="form-control"
            placeholder="Filter by name or type…"
            aria-label="Filter beans"
          />
        </div>
        <div class="col-md-4">
          <select v-model="classification" class="form-select" aria-label="Filter by classification">
            <option value="">All classifications</option>
            <option value="APPLICATION">Application</option>
            <option value="FRAMEWORK">Framework</option>
            <option value="BOOTUI">BootUI</option>
            <option value="PLATFORM">Platform</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
      </div>

      <div class="table-responsive">
        <table class="table table-sm table-hover beans-table">
          <colgroup>
            <col class="beans-table-name" />
            <col class="beans-table-type" />
            <col class="beans-table-scope" />
            <col class="beans-table-classification" />
            <col class="beans-table-dependencies" />
          </colgroup>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Scope</th>
              <th>Class.</th>
              <th>Dependencies</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in visibleBeans" :key="b.name">
              <td>
                <button
                  type="button"
                  class="bean-link"
                  :title="`Graph ${b.name}`"
                  :aria-label="`Graph ${b.name}`"
                  @click="showGraph(b)"
                >
                  {{ b.name }}
                </button>
              </td>
              <td>
                <small :title="b.type" class="text-truncate d-block">{{ b.type }}</small>
              </td>
              <td>{{ b.scope }}</td>
              <td>
                <span class="badge bg-light text-dark">{{ b.classification }}</span>
              </td>
              <td>
                <small :title="b.dependencies.join(', ')" class="text-muted text-truncate d-block">{{
                  b.dependencies.join(', ')
                }}</small>
              </td>
            </tr>
            <tr v-if="!loading && matchedCount === 0">
              <td class="text-center text-muted py-4" colspan="5">No beans match your filters.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <ServerListFooter
        v-if="!loading"
        :loading="loadingMore"
        :matched="matchedCount"
        :page-size="pageSize"
        :shown="shownCount"
        :total="totalCount"
        item-label="beans"
        @load-more="loadMore"
      />
    </template>

    <template v-else>
      <form class="graph-search mb-3" role="search" @submit.prevent="loadGraph()">
        <label for="bean-graph-focus" class="form-label">Focus bean</label>
        <div class="input-group">
          <input
            id="bean-graph-focus"
            v-model="graphSearch"
            class="form-control"
            autocomplete="off"
            placeholder="Search by bean name…"
          />
          <button class="btn btn-primary" type="submit" :disabled="graphLoading || !graphSearch.trim()">Focus</button>
        </div>
        <div v-if="suggestions.length" class="graph-suggestions" aria-label="Bean search results">
          <button
            v-for="bean in suggestions"
            :key="bean.name"
            type="button"
            class="graph-suggestion"
            @click="showGraph(bean)"
          >
            <code>{{ bean.name }}</code
            ><span>{{ bean.type }}</span>
          </button>
        </div>
      </form>

      <div v-if="graphLoading" class="text-muted py-4" role="status">Loading dependency graph…</div>
      <div v-else-if="!graph?.focus && !graphError" class="graph-empty">
        Search for a bean or select one from the List view to inspect its immediate wiring.
      </div>
      <template v-else-if="graph?.focus">
        <div class="graph-summary mb-3">
          <div>
            <strong>{{ graph.focus.name }}</strong>
            <code>{{ graph.focus.type }}</code>
          </div>
          <span class="badge bg-light text-dark">{{ graph.focus.classification }}</span>
          <span>{{ graph.focus.scope || 'Unknown scope' }}</span>
          <span v-if="graph.focus.resource" class="text-muted">{{ graph.focus.resource }}</span>
        </div>

        <div class="graph-labels" aria-hidden="true">
          <span>Dependencies</span><span>Focused bean</span><span>Dependents</span>
        </div>
        <div class="graph-canvas">
          <svg :viewBox="`0 0 860 ${graphHeight}`" role="img" :aria-label="`Dependency graph for ${graph.focus.name}`">
            <line
              v-for="node in dependencyNodes"
              :key="`dependency-edge-${node.name}`"
              :x1="node.x + NODE_WIDTH"
              :y1="node.y + NODE_HEIGHT / 2"
              :x2="focusNode.x"
              :y2="focusNode.y + NODE_HEIGHT / 2"
              class="graph-edge"
            />
            <line
              v-for="node in dependentNodes"
              :key="`dependent-edge-${node.name}`"
              :x1="focusNode.x + NODE_WIDTH"
              :y1="focusNode.y + NODE_HEIGHT / 2"
              :x2="node.x"
              :y2="node.y + NODE_HEIGHT / 2"
              class="graph-edge"
            />
            <g
              v-for="node in [...dependencyNodes, ...dependentNodes]"
              :key="node.name"
              class="graph-node"
              role="button"
              tabindex="0"
              :aria-label="`Focus ${node.name}, ${node.classification} bean`"
              @click="activateNode($event, node)"
              @keydown="activateNode($event, node)"
            >
              <rect :x="node.x" :y="node.y" :width="NODE_WIDTH" :height="NODE_HEIGHT" rx="10" />
              <text :x="node.x + 12" :y="node.y + 25" class="graph-node__name">{{ shortName(node.name) }}</text>
              <text :x="node.x + 12" :y="node.y + 46" class="graph-node__type">{{ shortName(node.type, 31) }}</text>
            </g>
            <g class="graph-node graph-node--focus" aria-hidden="true">
              <rect :x="focusNode.x" :y="focusNode.y" :width="NODE_WIDTH" :height="NODE_HEIGHT" rx="10" />
              <text :x="focusNode.x + 12" :y="focusNode.y + 25" class="graph-node__name">
                {{ shortName(focusNode.name) }}
              </text>
              <text :x="focusNode.x + 12" :y="focusNode.y + 46" class="graph-node__type">
                {{ shortName(focusNode.type, 31) }}
              </text>
            </g>
          </svg>
        </div>

        <p
          v-if="graph.hiddenDependencies || graph.hiddenDependents || graph.hiddenUnresolvedDependencies"
          class="small text-muted mt-2"
        >
          Bounded view: {{ graph.hiddenDependencies }} more dependencies, {{ graph.hiddenDependents }} more dependents,
          and {{ graph.hiddenUnresolvedDependencies }} unresolved references are hidden.
        </p>
        <div v-if="graph.unresolvedDependencies.length" class="unresolved mt-3">
          <strong>Unresolved dependency references</strong>
          <div class="d-flex flex-wrap gap-2 mt-2">
            <code v-for="name in graph.unresolvedDependencies" :key="name">{{ name }}</code>
          </div>
        </div>

        <section v-if="conditionsLoading || conditions.length" class="conditions-detail mt-3" aria-live="polite">
          <h3>Why this bean exists</h3>
          <p v-if="conditionsLoading" class="text-muted">Loading matching auto-configuration conditions…</p>
          <template v-else>
            <div v-for="entry in conditions" :key="entry.condition + entry.message" class="condition-entry">
              <strong>{{ entry.condition }}</strong>
              <span>{{ entry.message }}</span>
            </div>
            <router-link
              :to="{path: '/conditions', query: {q: conditions[0].autoConfigurationClass}}"
              class="btn btn-sm btn-outline-secondary mt-2"
            >
              View matching conditions
            </router-link>
          </template>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.view-toggle {
  display: inline-flex;
  padding: 0.2rem;
  border: 1px solid var(--bs-border-color);
  border-radius: 0.75rem;
  background: var(--bs-body-bg);
}

.view-toggle__button {
  border: 0;
  border-radius: 0.55rem;
  padding: 0.4rem 0.75rem;
  color: var(--bs-body-color);
  background: transparent;
}

.view-toggle__button.active {
  color: #fff;
  background: linear-gradient(135deg, #198754, #0d6efd);
}

.view-toggle__button:focus-visible,
.bean-link:focus-visible,
.graph-suggestion:focus-visible,
.graph-node:focus-visible {
  outline: 3px solid var(--bootui-focus-ring, rgba(13, 110, 253, 0.55));
  outline-offset: 2px;
}

.beans-table {
  table-layout: fixed;
}

.beans-table-name {
  width: 22%;
}

.beans-table-type {
  width: 34%;
}

.beans-table-scope {
  width: 10%;
}

.beans-table-classification {
  width: 14%;
}

.beans-table-dependencies {
  width: 20%;
}

.bean-link {
  display: block;
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  color: var(--bootui-link-color, #0a53be);
  font-family: var(--bs-font-monospace);
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 0;
  background: transparent;
}

.graph-search {
  position: relative;
  max-width: 42rem;
}

.graph-suggestions {
  position: absolute;
  z-index: 10;
  right: 0;
  left: 0;
  overflow: hidden;
  border: 1px solid var(--bs-border-color);
  border-radius: 0 0 0.75rem 0.75rem;
  background: var(--bs-body-bg);
  box-shadow: 0 1.2rem 3rem rgba(15, 23, 42, 0.11);
}

.graph-suggestion {
  display: flex;
  width: 100%;
  gap: 1rem;
  justify-content: space-between;
  padding: 0.65rem 0.8rem;
  text-align: left;
  border: 0;
  border-bottom: 1px solid var(--bs-border-color);
  background: transparent;
}

.graph-suggestion:hover {
  background: var(--bs-tertiary-bg);
}

.graph-suggestion span {
  overflow: hidden;
  color: var(--bs-secondary-color);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.graph-summary,
.unresolved,
.conditions-detail,
.graph-empty {
  padding: 1rem;
  border: 1px solid var(--bs-border-color);
  border-radius: 1.1rem;
  background: color-mix(in srgb, var(--bs-body-bg) 88%, transparent);
}

.graph-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1rem;
  align-items: center;
}

.graph-summary div {
  display: grid;
}

.graph-labels {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin-bottom: 0.25rem;
  color: var(--bs-secondary-color);
  font-weight: 700;
  text-align: center;
}

.graph-canvas {
  overflow-x: auto;
  border: 1px solid var(--bs-border-color);
  border-radius: 1.1rem;
  background: color-mix(in srgb, var(--bs-body-bg) 92%, var(--bs-primary) 8%);
}

.graph-canvas svg {
  display: block;
  min-width: 760px;
}

.graph-edge {
  stroke: var(--bs-secondary-color);
  stroke-width: 1.5;
  opacity: 0.55;
}

.graph-node {
  cursor: pointer;
}

.graph-node rect {
  fill: var(--bs-body-bg);
  stroke: var(--bs-border-color);
  stroke-width: 1.5;
}

.graph-node:hover rect,
.graph-node:focus-visible rect {
  stroke: #0a53be;
  stroke-width: 2.5;
}

.graph-node--focus {
  cursor: default;
}

.graph-node--focus rect {
  fill: #0a53be;
  stroke: #0a53be;
}

.graph-node__name,
.graph-node__type {
  font-family: var(--bs-font-monospace);
  pointer-events: none;
}

.graph-node__name {
  fill: var(--bs-body-color);
  font-size: 13px;
  font-weight: 700;
}

.graph-node__type {
  fill: var(--bs-secondary-color);
  font-size: 10px;
}

.graph-node--focus .graph-node__name,
.graph-node--focus .graph-node__type {
  fill: #fff;
}

.conditions-detail h3 {
  margin-bottom: 0.75rem;
  font-size: 1.05rem;
}

.condition-entry {
  display: grid;
  margin-bottom: 0.6rem;
}

@media (prefers-reduced-motion: reduce) {
  .view-toggle__button,
  .graph-node {
    transition: none;
  }
}
</style>
