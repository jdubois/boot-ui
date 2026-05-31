<script setup>
import {computed, onMounted, ref} from 'vue'
import HealthNode from './HealthNode.vue'
import PanelHeader from './components/PanelHeader.vue'

const root = ref(null)
const loading = ref(true)
const error = ref(null)
const lastFetched = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await fetch('api/health')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    root.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function flatten(node) {
  if (!node) return []
  return [node, ...(node.components || []).flatMap(flatten)]
}

const nodes = computed(() => flatten(root.value))
const componentCount = computed(() => Math.max(nodes.value.length - 1, 0))
const problemNodes = computed(() => nodes.value.filter((node) => !['UP', 'UNKNOWN'].includes(node.status)))
const detailsHidden = computed(
  () => root.value && !root.value.details && (!root.value.components || root.value.components.length === 0)
)

const statusMessage = computed(() => {
  if (!root.value) return ''
  if (problemNodes.value.length) {
    return (
      problemNodes.value.length + ' component' + (problemNodes.value.length === 1 ? ' needs' : 's need') + ' attention'
    )
  }
  if (root.value.status === 'UP') return 'All reported components are healthy'
  if (root.value.status === 'UNKNOWN') return 'Health endpoint did not report component details'
  return 'Application health is ' + root.value.status
})

onMounted(load)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-heart-pulse"
      title="Health"
      subtitle="Inspect application and dependency health without reading raw Actuator JSON."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      @refresh="load"
    />

    <div v-if="loading" class="text-muted">Loading…</div>

    <template v-else-if="root">
      <div class="row g-3 mb-3">
        <div class="col-md-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small text-uppercase">Overall status</div>
              <div class="fs-4 fw-semibold">
                <span
                  :class="{
                    'bg-success': root.status === 'UP',
                    'bg-danger': root.status === 'DOWN',
                    'bg-warning text-dark': root.status === 'OUT_OF_SERVICE',
                    'bg-secondary': !['UP', 'DOWN', 'OUT_OF_SERVICE'].includes(root.status)
                  }"
                  class="badge"
                >
                  {{ root.status }}
                </span>
              </div>
              <div class="small text-muted mt-2">{{ statusMessage }}</div>
            </div>
          </div>
        </div>

        <div class="col-md-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small text-uppercase">Contributors</div>
              <div class="fs-4 fw-semibold">{{ componentCount }}</div>
              <div class="small text-muted mt-2">Nested health components reported by Actuator</div>
            </div>
          </div>
        </div>

        <div class="col-md-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small text-uppercase">Attention needed</div>
              <div class="fs-4 fw-semibold">{{ problemNodes.length }}</div>
              <div class="small text-muted mt-2">DOWN or OUT_OF_SERVICE contributors</div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="detailsHidden" class="alert alert-info small">
        Health contributor details are not available. In local development, set
        <code>management.endpoint.health.show-details=always</code> to show component details.
      </div>

      <h5 class="mb-2">Component tree</h5>
      <HealthNode :node="root" />
    </template>
  </div>
</template>
