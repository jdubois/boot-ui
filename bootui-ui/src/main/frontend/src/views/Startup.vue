<script setup>
import { computed, onMounted, ref } from 'vue'

const report = ref({ steps: [] })
const filter = ref('')
const expandedStepIds = ref(new Set())
const loading = ref(true)
const error = ref('')

function buildTree(steps) {
  const byId = new Map()
  const roots = []

  steps.forEach(step => {
    byId.set(step.id, { ...step, children: [] })
  })

  steps.forEach(step => {
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

async function load() {
  loading.value = true
  error.value = ''

  try {
    const res = await fetch('api/startup')
    if (!res.ok) {
      throw new Error(`Request failed with status ${res.status}`)
    }
    const data = await res.json()
    const steps = Array.isArray(data?.steps) ? data.steps : []
    report.value = {
      steps
    }
    expandedStepIds.value = new Set(buildTree(steps).map(step => step.id))
  } catch (err) {
    report.value = { steps: [] }
    expandedStepIds.value = new Set()
    error.value = err instanceof Error ? err.message : 'Unable to load startup data'
  } finally {
    loading.value = false
  }
}

const sortedSteps = computed(() =>
  [...report.value.steps].sort((a, b) => a.id - b.id)
)

const tree = computed(() => buildTree(sortedSteps.value))

function filterTree(nodes, query) {
  if (!query) {
    return nodes
  }

  return nodes.flatMap(node => {
    const children = filterTree(node.children, query)
    const matches = (node.name || '').toLowerCase().includes(query)
    if (!matches && children.length === 0) {
      return []
    }
    return [{ ...node, children }]
  })
}

function flattenVisible(nodes, query, depth = 0) {
  return nodes.flatMap(node => {
    const expanded = Boolean(query) || expandedStepIds.value.has(node.id)
    return [
      { ...node, depth, expanded },
      ...(expanded ? flattenVisible(node.children, query, depth + 1) : [])
    ]
  })
}

const visibleSteps = computed(() => {
  const query = filter.value.trim().toLowerCase()
  return flattenVisible(filterTree(tree.value, query), query)
})

const branchStepCount = computed(() =>
  report.value.steps.filter(step => step.parentId != null).length
)

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
  expandedStepIds.value = new Set(report.value.steps
    .filter(step => treeStepHasChildren(step.id))
    .map(step => step.id))
}

function collapseAll() {
  expandedStepIds.value = new Set()
}

function treeStepHasChildren(stepId) {
  return report.value.steps.some(step => step.parentId === stepId)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-clock-history me-2"></i>Startup timeline</h2>
        <p class="text-muted mb-0">
          {{ report.steps.length }} steps · {{ branchStepCount }} nested<span v-if="filter"> · {{ visibleSteps.length }} shown</span>
        </p>
      </div>
      <div class="col-12 col-md-7 col-lg-6 px-0">
        <div class="input-group">
          <span class="input-group-text"><i class="bi bi-search"></i></span>
          <input
            v-model="filter"
            class="form-control"
            placeholder="Filter by step name…"
            type="search"
          />
          <button
            class="btn btn-outline-secondary"
            type="button"
            :disabled="loading || report.steps.length === 0"
            @click="expandAll"
          >
            Expand all
          </button>
          <button
            class="btn btn-outline-secondary"
            type="button"
            :disabled="loading || report.steps.length === 0"
            @click="collapseAll"
          >
            Collapse all
          </button>
        </div>
      </div>
    </div>

    <div v-if="loading" class="text-muted">
      <i class="bi bi-hourglass-split me-2"></i>Loading startup data…
    </div>
    <div v-else-if="error" class="alert alert-danger" role="alert">
      <i class="bi bi-exclamation-triangle me-2"></i>{{ error }}
    </div>
    <div v-else-if="report.steps.length === 0" class="alert alert-light border" role="status">
      <i class="bi bi-info-circle me-2"></i>No startup data available
    </div>
    <div v-else-if="visibleSteps.length === 0" class="alert alert-light border" role="status">
      <i class="bi bi-search me-2"></i>No startup steps match the current filter
    </div>
    <div v-else class="list-group">
      <div
        v-for="step in visibleSteps"
        :key="step.id"
        class="list-group-item py-3"
        :style="{ paddingLeft: `${1 + step.depth * 1.25}rem` }"
      >
        <div class="d-flex align-items-start justify-content-between gap-3">
          <div class="flex-grow-1 min-w-0">
            <div class="d-flex align-items-center gap-2 flex-wrap mb-1">
              <button
                class="btn btn-sm btn-link text-decoration-none p-0 startup-tree-toggle"
                type="button"
                :class="{ 'invisible': !step.children?.length }"
                :aria-expanded="step.expanded"
                :aria-label="`${step.expanded ? 'Collapse' : 'Expand'} ${step.name}`"
                @click="toggleStep(step)"
              >
                <i class="bi" :class="step.expanded ? 'bi-chevron-down' : 'bi-chevron-right'"></i>
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
          <span class="badge text-bg-primary ms-auto">{{ step.durationMs }} ms</span>
        </div>
      </div>
    </div>
  </div>
</template>
