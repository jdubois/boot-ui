<script setup>
import {computed, inject, ref} from 'vue'
import {getJson} from '../../api.js'
import {insightsUsable} from '../../utils/insightsPanel.js'
import {formatLoadError} from '../../utils/loadError.js'
import InsightText from './InsightText.vue'

// "Why this route is slow" in a request's drawer (docs/PLAN-v2.md §5.3): the route's time breakdown from Runtime
// Insights. Loaded only when the developer asks, so opening a request makes no extra call.
const props = defineProps({route: {type: String, required: true}})

const panels = inject('panels', ref(null))
const usable = computed(() => insightsUsable(panels.value))
const loading = ref(false)
const error = ref(null)
const loaded = ref(false)
const breakdown = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const report = await getJson('api/runtime-insights')
    breakdown.value =
      (report.observations ?? []).find(
        (observation) => observation.kind === 'route-time-breakdown' && observation.subject === props.route
      ) ?? null
    loaded.value = true
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
          :to="{path: '/runtime-insights', query: {q: route, insight: breakdown.id}}"
          class="btn btn-outline-secondary btn-sm"
        >
          Open in Runtime Insights
        </router-link>
      </template>
      <p v-else class="mb-0 text-muted">Runtime Insights has no time breakdown for this route in this run yet.</p>
    </div>
  </div>
</template>
