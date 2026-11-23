<script setup>
import {computed, inject, ref} from 'vue'
import {getJson} from '../../api.js'
import {hasActivityArchiveScope} from '../../utils/activityStream.js'
import {insightsUsable} from '../../utils/insightsPanel.js'
import {formatLoadError} from '../../utils/loadError.js'
import InsightText from './InsightText.vue'

// "Why this route is slow" in a request's drawer (docs/PLAN-v2.md §5.3): the route's time breakdown from Runtime
// Insights. Loaded only when the developer asks, so opening a request makes no extra call.
const props = defineProps({
  route: {type: String, required: true},
  origin: {type: Object, default: null}
})

const panels = inject('panels', ref(null))
const usable = computed(() => insightsUsable(panels.value))
const currentRun = ref(null)
const linksToCurrentRun = computed(() => !props.origin || currentRun.value === props.origin.run)
const linkError = ref(null)
const loading = ref(false)
const error = ref(null)
const loaded = ref(false)
const breakdown = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const params = new URLSearchParams()
    if (props.origin) {
      if (!hasActivityArchiveScope(props.origin)) {
        error.value = 'This persisted request does not identify a recording for Runtime Insights.'
        return
      }
      for (const field of ['namespace', 'application', 'run']) {
        params.set(field, props.origin[field])
      }
    }
    const scope = params.toString()
    const report = await getJson(scope ? `api/runtime-insights?${scope}` : 'api/runtime-insights')
    if (report.available === false) {
      error.value = report.unavailableReason || 'Runtime Insights is unavailable.'
      return
    }
    breakdown.value =
      (report.observations ?? []).find(
        (observation) => observation.kind === 'route-time-breakdown' && observation.subject === props.route
      ) ?? null
    loaded.value = true
    if (scope && breakdown.value) {
      // The shell's overview can outlive a restart. Do not let it turn an archived result into a live link.
      try {
        const overview = await getJson('api/overview', {cache: 'no-store'})
        currentRun.value = overview?.run?.runId ?? null
        if (!currentRun.value) linkError.value = 'The current run could not be verified.'
      } catch (e) {
        linkError.value = formatLoadError(e, 'Unable to verify the current run')
      }
    }
  } catch (e) {
    error.value = formatLoadError(e, 'Unable to load Runtime Insights')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-if="usable" class="route-why-slow mb-2">
    <button
      v-if="!loaded"
      type="button"
      class="btn btn-sm btn-outline-secondary"
      :disabled="loading"
      :aria-busy="loading"
      @click="load"
    >
      <i class="bi bi-lightbulb me-1" aria-hidden="true"></i>Why this route is slow
    </button>
    <div v-if="error" class="small text-muted mt-1" role="status">{{ error }}</div>
    <div v-else-if="loaded" class="small" role="status">
      <template v-if="breakdown">
        <p class="mb-1"><InsightText :text="breakdown.sentence" /></p>
        <p v-if="breakdown.whatToCheck?.length" class="mb-1 text-muted">
          <InsightText :text="breakdown.whatToCheck[0]" />
        </p>
        <router-link
          v-if="linksToCurrentRun"
          :to="{path: '/runtime-insights', query: {q: route, insight: breakdown.id}}"
          class="btn btn-outline-secondary btn-sm"
        >
          Open in Runtime Insights
        </router-link>
        <p v-if="linkError" class="mb-0 text-muted">{{ linkError }}</p>
      </template>
      <p v-else class="mb-0 text-muted">
        Runtime Insights has no time breakdown for this route
        {{ linksToCurrentRun ? 'in this run yet.' : 'in the selected recording.' }}
      </p>
    </div>
  </div>
</template>
