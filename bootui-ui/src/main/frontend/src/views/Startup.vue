<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const report = ref({steps: []})
const filter = ref('')
const selectedDurationBand = ref('all')
const expandedStepIds = ref(new Set())
const error = ref('')
const lastFetched = ref(null)

const durationBands = [
  {id: 'fastest', label: 'Fastest', className: 'startup-duration-label--fastest'},
  {id: 'fast', label: 'Fast', className: 'startup-duration-label--fast'},
  {id: 'medium', label: 'Medium', className: 'startup-duration-label--medium'},
  {id: 'slow', label: 'Slow', className: 'startup-duration-label--slow'},
  {id: 'slowest', label: 'Slowest', className: 'startup-duration-label--slowest'}
]

function normalizedDuration(durationMs) {
  const duration = Number(durationMs)
  return Number.isFinite(duration) ? Math.max(0, duration) : 0
}

const durationScale = computed(() => {
  const durations = report.value.steps.map((step) => normalizedDuration(step.durationMs))
  if (durations.length === 0) {
    return {min: 0, max: 0, minLog: 0, span: 0}
  }

  const min = Math.min(...durations)
  const max = Math.max(...durations)
  const minLog = Math.log1p(min)
  return {
    min,
    max,
    minLog,
    span: Math.log1p(max) - minLog
  }
})

function durationBandFor(durationMs) {
  const scale = durationScale.value
  if (scale.span <= 0) {
    return durationBands[0]
  }

  const ratio = (Math.log1p(normalizedDuration(durationMs)) - scale.minLog) / scale.span
  const index = Math.min(durationBands.length - 1, Math.max(0, Math.floor(ratio * durationBands.length)))
  return durationBands[index]
}

function buildTree(steps) {
  const byId = new Map()
  const roots = []

  steps.forEach((step) => {
    byId.set(step.id, {...step, children: []})
  })

  steps.forEach((step) => {
    const node = byId.get(step.id)
    const parentId = step.parentId
    if (!parentId || !byId.has(parentId)) {
      roots.push(node)
      return
    }
    byId.get(parentId).children.push(node)
  })

  return roots
}

async function fetchStartup() {
  error.value = ''

  try {
    const res = await apiFetch('api/startup')
    if (!res.ok) {
      throw new Error(`Request failed with status ${res.status}`)
    }
    const data = await res.json()
    const steps = Array.isArray(data?.steps) ? data.steps : []
    report.value = {
      steps
    }
    expandedStepIds.value = new Set(buildTree(steps).map((step) => step.id))
    lastFetched.value = Date.now()
  } catch (err) {
    report.value = {steps: []}
    expandedStepIds.value = new Set()
    error.value = describeLoadError(err, 'Unable to load startup data')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchStartup)

const sortedSteps = computed(() => [...report.value.steps].sort((a, b) => a.id - b.id))

const tree = computed(() => buildTree(sortedSteps.value))

const durationBandCounts = computed(() =>
  durationBands.map((band) => ({
    ...band,
    count: report.value.steps.filter((step) => durationBandFor(step.durationMs).id === band.id).length
  }))
)

function stepMatchesFilters(node, query, durationBandId) {
  const matchesQuery = !query || (node.name || '').toLowerCase().includes(query)
  const matchesDurationBand = durationBandId === 'all' || durationBandFor(node.durationMs).id === durationBandId
  return matchesQuery && matchesDurationBand
}

function filterTree(nodes, query, durationBandId) {
  if (!query && durationBandId === 'all') {
    return nodes
  }

  return nodes.flatMap((node) => {
    const children = filterTree(node.children, query, durationBandId)
    const matches = stepMatchesFilters(node, query, durationBandId)
    if (!matches && children.length === 0) {
      return []
    }
    return [{...node, children}]
  })
}

function flattenVisible(nodes, hasActiveFilter, depth = 0) {
  return nodes.flatMap((node) => {
    const expanded = hasActiveFilter || expandedStepIds.value.has(node.id)
    return [{...node, depth, expanded}, ...(expanded ? flattenVisible(node.children, hasActiveFilter, depth + 1) : [])]
  })
}

const visibleSteps = computed(() => {
  const query = filter.value.trim().toLowerCase()
  const durationBandId = selectedDurationBand.value
  const hasActiveFilter = Boolean(query) || durationBandId !== 'all'
  return flattenVisible(filterTree(tree.value, query, durationBandId), hasActiveFilter)
})

const branchStepCount = computed(() => report.value.steps.filter((step) => step.parentId != null).length)

function toggleStep(step) {
  if (!step.children?.length) {
    return
  }

  const next = new Set(expandedStepIds.value)
  if (next.has(step.id)) {
    next.delete(step.id)
  } else {
    next.add(step.id)
  }
  expandedStepIds.value = next
}

function expandAll() {
  expandedStepIds.value = new Set(
    report.value.steps.filter((step) => treeStepHasChildren(step.id)).map((step) => step.id)
  )
}

function collapseAll() {
  expandedStepIds.value = new Set()
}

function treeStepHasChildren(stepId) {
  return report.value.steps.some((step) => step.parentId === stepId)
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-clock-history"
      title="Startup timeline"
      :subtitle="report.steps.length ? `${report.steps.length} steps · ${branchStepCount} nested` : null"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <div v-if="!loading && !error" class="d-flex flex-wrap justify-content-end gap-2 mb-3">
      <div class="col-12 col-md-7 col-lg-6 px-0">
        <div class="input-group mb-2">
          <span class="input-group-text"><i class="bi bi-search"></i></span>
          <input v-model="filter" class="form-control" placeholder="Filter by step name…" type="search" />
          <button
            :disabled="loading || report.steps.length === 0"
            class="btn btn-outline-secondary"
            type="button"
            @click="expandAll"
          >
            Expand all
          </button>
          <button
            :disabled="loading || report.steps.length === 0"
            class="btn btn-outline-secondary"
            type="button"
            @click="collapseAll"
          >
            Collapse all
          </button>
        </div>
        <div class="d-flex flex-wrap align-items-center gap-2">
          <span class="text-muted small">Duration color:</span>
          <div aria-label="Filter startup steps by duration color" class="btn-group flex-wrap" role="group">
            <button
              :aria-pressed="selectedDurationBand === 'all'"
              :class="{active: selectedDurationBand === 'all'}"
              class="btn btn-sm btn-outline-secondary"
              type="button"
              @click="selectedDurationBand = 'all'"
            >
              All
            </button>
            <button
              v-for="band in durationBandCounts"
              :key="band.id"
              :aria-pressed="selectedDurationBand === band.id"
              :class="{active: selectedDurationBand === band.id}"
              :title="`Show ${band.label.toLowerCase()} startup steps`"
              class="btn btn-sm btn-outline-secondary startup-duration-filter"
              type="button"
              @click="selectedDurationBand = band.id"
            >
              <span :class="band.className" class="startup-duration-filter__swatch"></span>
              {{ band.label }}
              <span class="badge text-bg-light ms-1">{{ band.count }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <PanelSkeleton v-if="initialLoading" />
    <div v-else-if="report.steps.length === 0 && !error" class="alert alert-light border" role="status">
      <i class="bi bi-info-circle me-2"></i>No startup data available
    </div>
    <div v-else-if="visibleSteps.length === 0" class="alert alert-light border" role="status">
      <i class="bi bi-search me-2"></i>No startup steps match the current filters
    </div>
    <div v-else class="list-group">
      <div
        v-for="step in visibleSteps"
        :key="step.id"
        :style="{paddingLeft: `${1 + step.depth * 1.25}rem`}"
        class="list-group-item py-3"
      >
        <div class="d-flex align-items-start justify-content-between gap-3">
          <div class="flex-grow-1 min-w-0">
            <div class="d-flex align-items-center gap-2 flex-wrap mb-1">
              <button
                :aria-expanded="step.expanded"
                :aria-label="`${step.expanded ? 'Collapse' : 'Expand'} ${step.name}`"
                :class="{invisible: !step.children?.length}"
                class="btn btn-sm btn-link text-decoration-none p-0 startup-tree-toggle"
                type="button"
                @click="toggleStep(step)"
              >
                <i :class="step.expanded ? 'bi-chevron-down' : 'bi-chevron-right'" class="bi"></i>
              </button>
              <span class="fw-semibold text-break">{{ step.name }}</span>
              <span class="badge text-bg-light">#{{ step.id }}</span>
              <span v-if="step.children?.length" class="badge text-bg-light">
                {{ step.children.length }} child{{ step.children.length === 1 ? '' : 'ren' }}
              </span>
            </div>
            <div v-if="step.tags?.length" class="d-flex flex-wrap gap-1 mt-2">
              <span
                v-for="tag in step.tags"
                :key="`${step.id}-${tag.key}-${tag.value}`"
                class="badge rounded-pill text-bg-secondary"
              >
                {{ tag.key }}={{ tag.value }}
              </span>
            </div>
          </div>
          <span
            :class="durationBandFor(step.durationMs).className"
            :title="`${durationBandFor(step.durationMs).label} startup step`"
            class="badge ms-auto startup-duration-label"
          >
            {{ step.durationMs }} ms
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.startup-duration-filter {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

.startup-duration-filter__swatch {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 50%;
  display: inline-block;
}

.startup-duration-label {
  font-weight: 700;
}

.startup-duration-label--fastest {
  background-color: #198754;
  color: #fff;
}

.startup-duration-label--fast {
  background-color: #8bc34a;
  color: #212529;
}

.startup-duration-label--medium {
  background-color: #ffc107;
  color: #212529;
}

.startup-duration-label--slow {
  background-color: #ff7a00;
  color: #212529;
}

.startup-duration-label--slowest {
  background-color: #ff0000;
  color: #fff;
}
</style>
