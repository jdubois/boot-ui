<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import HealthNode from './HealthNode.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import {describeLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'

const root = ref(null)
const error = ref(null)
const lastFetched = ref(null)

async function fetchHealth() {
  error.value = null
  try {
    const res = await apiFetch('api/health')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    root.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load health')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchHealth)

function flatten(node) {
  if (!node) return []
  return [node, ...(node.components || []).flatMap(flatten)]
}

const nodes = computed(() => flatten(root.value))
const componentCount = computed(() => Math.max(nodes.value.length - 1, 0))
const healthUnavailable = computed(() => root.value?.available === false)
const setupSteps = computed(() => root.value?.setup || [])
const hasHealthComponents = computed(() => componentCount.value > 0)
const defaultOnlyHealth = computed(
  () => root.value?.guidanceReason === 'Only Spring Boot default health indicators are available'
)
const healthGuidance = computed(
  () => healthUnavailable.value || Boolean(root.value?.guidanceReason) || setupSteps.value.length > 0
)
const defaultContributorNames = computed(() =>
  (root.value?.components || []).map((component) => component.name).join(', ')
)
const setupTitle = computed(() =>
  defaultOnlyHealth.value ? 'Add application health contributors' : 'Set up Spring Boot Actuator health'
)
const setupIntro = computed(() => {
  if (defaultOnlyHealth.value) {
    const contributors = defaultContributorNames.value ? `: ${defaultContributorNames.value}` : ''
    return `Actuator health is available, but the reported tree contains only Spring Boot default health indicators${contributors}. These framework checks are useful, but they do not prove that your application dependencies are healthy. The SSL indicator only appears when Spring has SSL bundles to validate.`
  }
  return (
    'The Health panel is disabled until a Spring Boot Actuator HealthEndpoint is available. Once it is configured, ' +
    'BootUI will render the application status, liveness/readiness probes, SSL certificate checks, and any dependency ' +
    'contributors reported by Actuator.'
  )
})
const problemNodes = computed(() =>
  nodes.value.filter((node) => node.available !== false && !['UP', 'UNKNOWN', 'DISABLED'].includes(node.status))
)
const detailsHidden = computed(
  () =>
    root.value &&
    !healthUnavailable.value &&
    !root.value.details &&
    (!root.value.components || root.value.components.length === 0)
)

const statusMessage = computed(() => {
  if (!root.value) return ''
  if (healthUnavailable.value) return root.value.unavailableReason || 'Actuator health data is unavailable'
  if (root.value.guidanceReason) return root.value.guidanceReason
  if (problemNodes.value.length) {
    return (
      problemNodes.value.length + ' component' + (problemNodes.value.length === 1 ? ' needs' : 's need') + ' attention'
    )
  }
  if (root.value.status === 'UP') return 'All reported components are healthy'
  if (root.value.status === 'UNKNOWN') return 'Health endpoint did not report component details'
  return 'Application health is ' + root.value.status
})
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
    >
      <template #actions>
        <AutoRefreshToggle v-model="autoRefresh" />
      </template>
    </PanelHeader>

    <PanelSkeleton v-if="initialLoading" />

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

      <div v-if="healthGuidance" class="card border-info mb-3">
        <div class="card-body">
          <h5 class="card-title d-flex align-items-center gap-2">
            <i class="bi bi-info-circle text-info"></i>
            {{ setupTitle }}
          </h5>
          <p class="text-muted mb-3">
            {{ setupIntro }}
          </p>
          <ol v-if="setupSteps.length" class="mb-0 ps-3">
            <li v-for="step in setupSteps" :key="step.title" class="mb-3">
              <div class="fw-semibold">{{ step.title }}</div>
              <div class="text-muted small mb-2">{{ step.description }}</div>
              <div v-if="step.snippets?.length" class="d-flex flex-column gap-1">
                <code v-for="snippet in step.snippets" :key="snippet" class="small">{{ snippet }}</code>
              </div>
            </li>
          </ol>
        </div>
      </div>

      <template v-if="!healthUnavailable || hasHealthComponents">
        <h5 class="mb-2">Component tree</h5>
        <HealthNode :node="root" />
      </template>
    </template>
  </div>
</template>
