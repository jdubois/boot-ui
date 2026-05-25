<script setup>
import { computed, onMounted, ref } from 'vue'

const report = ref({ steps: [] })
const filter = ref('')
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''

  try {
    const res = await fetch('api/startup')
    if (!res.ok) {
      throw new Error(`Request failed with status ${res.status}`)
    }
    const data = await res.json()
    report.value = {
      steps: Array.isArray(data?.steps) ? data.steps : []
    }
  } catch (err) {
    report.value = { steps: [] }
    error.value = err instanceof Error ? err.message : 'Unable to load startup data'
  } finally {
    loading.value = false
  }
}

const sortedSteps = computed(() =>
  [...report.value.steps].sort((a, b) => a.id - b.id)
)

const tree = computed(() => {
  const byId = new Map()
  const roots = []

  sortedSteps.value.forEach(step => {
    byId.set(step.id, { ...step, children: [] })
  })

  sortedSteps.value.forEach(step => {
    const node = byId.get(step.id)
    const parentId = step.parentId
    if (!parentId || !byId.has(parentId)) {
      roots.push(node)
      return
    }
    byId.get(parentId).children.push(node)
  })

  return roots
})

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

function flatten(nodes, depth = 0) {
  return nodes.flatMap(node => [
    { ...node, depth },
    ...flatten(node.children, depth + 1)
  ])
}

const visibleSteps = computed(() => {
  const query = filter.value.trim().toLowerCase()
  return flatten(filterTree(tree.value, query))
})

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-clock-history me-2"></i>Startup timeline</h2>
        <p class="text-muted mb-0">
          {{ report.steps.length }} steps<span v-if="filter"> · {{ visibleSteps.length }} shown</span>
        </p>
      </div>
      <div class="col-12 col-md-5 col-lg-4 px-0">
        <div class="input-group">
          <span class="input-group-text"><i class="bi bi-search"></i></span>
          <input
            v-model="filter"
            class="form-control"
            placeholder="Filter by step name…"
            type="search"
          />
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
              <i class="bi bi-arrow-return-right text-muted" :class="{ 'opacity-0': step.depth === 0 }"></i>
              <span class="fw-semibold text-break">{{ step.name }}</span>
              <span class="badge text-bg-light">#{{ step.id }}</span>
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
