<script setup>
import {getJson} from '../api.js'
import {computed, ref} from 'vue'
import HealthNode from './components/HealthNode.vue'
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
    root.value = await getJson('api/health')
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
const defaultOnlyHealth = computed(() => !healthUnavailable.value && Boolean(root.value?.guidanceReason))
const healthGuidance = computed(
  () => healthUnavailable.value || Boolean(root.value?.guidanceReason) || setupSteps.value.length > 0
)
const defaultContributorNames = computed(() =>
  (root.value?.components || []).map((component) => component.name).join(', ')
)
const setupTitle = computed(() =>
  defaultOnlyHealth.value ? 'Add application health contributors' : 'Set up health monitoring'
)
const setupIntro = computed(() => {
  if (defaultOnlyHealth.value) {
    const contributors = defaultContributorNames.value ? `: ${defaultContributorNames.value}` : ''
    return `Health is reporting, but the tree contains only the framework's built-in health indicators${contributors}. These framework checks are useful, but they do not prove that your application dependencies are healthy.`
  }
  return (
    'The Health panel renders the application status and dependency contributors once a health backend is ' +
    'available. Use the steps below to light up real dependency health in local development.'
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
  if (healthUnavailable.value) return root.value.unavailableReason || 'Health data is unavailable'
  if (root.value.guidanceReason) return root.value.guidanceReason
  if (problemNodes.value.length) {
    return `${problemNodes.value.length} component${problemNodes.value.length === 1 ? ' needs' : 's need'} attention`
  }
  if (root.value.status === 'UP') return 'All reported components are healthy'
  if (root.value.status === 'UNKNOWN') return 'Health endpoint did not report component details'
  return `Application health is ${root.value.status}`
})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-heart-pulse"
      title="Health"
      subtitle="Inspect application and dependency health at a glance."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading" />

    <template v-else-if="root">
      <div class="card health-summary mb-3">
        <div class="card-body">
          <div class="health-summary__row">
            <div class="health-summary__status">
              <div class="health-summary__eyebrow">Overall status</div>
              <span
                :class="{
                  'bg-success': root.status === 'UP',
                  'bg-danger': root.status === 'DOWN',
                  'bg-warning text-dark': root.status === 'OUT_OF_SERVICE',
                  'bg-secondary': !['UP', 'DOWN', 'OUT_OF_SERVICE'].includes(root.status)
                }"
                class="badge health-summary__badge"
              >
                {{ root.status }}
              </span>
              <p class="health-summary__message">{{ statusMessage }}</p>
            </div>

            <div class="health-summary__divider" aria-hidden="true"></div>

            <dl class="health-summary__metrics">
              <div class="health-summary__metric">
                <dt>Contributors</dt>
                <dd>{{ componentCount }}</dd>
                <small class="health-summary__hint">Nested health components reported by the health backend</small>
              </div>
              <div class="health-summary__metric">
                <dt>Attention needed</dt>
                <dd :class="{'text-danger': problemNodes.length > 0}">{{ problemNodes.length }}</dd>
                <small class="health-summary__hint">DOWN or OUT_OF_SERVICE contributors</small>
              </div>
            </dl>
          </div>
        </div>
      </div>

      <div v-if="detailsHidden" class="alert alert-info small">
        Health contributor details are not available. Configure your health backend to report component details so
        BootUI can show them in local development.
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

<style scoped>
.health-summary__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem 2rem;
}

.health-summary__status {
  flex-shrink: 0;
}

.health-summary__eyebrow {
  font-size: 0.72rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--bootui-text-muted);
  margin-bottom: 0.4rem;
}

.health-summary__badge {
  font-size: 1.15rem;
  padding: 0.45em 0.7em;
}

.health-summary__message {
  margin: 0.55rem 0 0;
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
  max-width: 34ch;
}

.health-summary__divider {
  align-self: stretch;
  width: 1px;
  background: var(--bootui-border);
}

.health-summary__metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0.85rem 2rem;
  flex: 1 1 16rem;
  margin: 0;
}

.health-summary__metric {
  min-width: 7rem;
}

.health-summary__metric dt {
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--bootui-text-muted);
  margin-bottom: 0.3rem;
}

.health-summary__metric dd {
  font-size: 1.6rem;
  font-weight: 700;
  line-height: 1.1;
  color: var(--bootui-text);
  margin: 0 0 0.2rem;
}

.health-summary__hint {
  display: block;
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
  max-width: 24ch;
}

@media (max-width: 575.98px) {
  .health-summary__divider {
    display: none;
  }
}
</style>
