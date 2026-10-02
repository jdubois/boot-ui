<script setup>
import {computed, ref, watch} from 'vue'
import {getJson} from '../../api.js'
import {describeLoadError} from '../../utils/loadError.js'
import {
  comparisonSections,
  comparisonStatusLabel,
  isComparison,
  restartCostText,
  runLabel
} from '../../utils/runComparison.js'
import InsightText from './InsightText.vue'

// Reloaded with the report, so the comparison follows the run as it records more.
const props = defineProps({refreshKey: {type: [Number, String], default: 0}})

const comparison = ref(null)
const error = ref(null)
const loading = ref(false)
const selectedRun = ref('')

async function load() {
  loading.value = true
  error.value = null
  try {
    const query = selectedRun.value ? `?run=${encodeURIComponent(selectedRun.value)}` : ''
    const value = await getJson(`api/runtime-insights/comparison${query}`)
    comparison.value = isComparison(value) ? value : null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load the run comparison')
  } finally {
    loading.value = false
  }
}

watch(() => props.refreshKey, load, {immediate: true})
watch(selectedRun, load)

const sections = computed(() => comparisonSections(comparison.value))
const compared = computed(() => comparison.value?.status === 'COMPARED')
const restart = computed(() => restartCostText(comparison.value?.restartCost))
</script>

<template>
  <section class="card insight-comparison mt-3" aria-labelledby="insight-comparison-title" :aria-busy="loading">
    <div class="card-body">
      <div class="d-flex flex-wrap align-items-baseline justify-content-between gap-2 mb-1">
        <h2 id="insight-comparison-title" class="h6 mb-0">Compared with the previous run</h2>
        <select
          v-if="comparison?.runs?.length > 1"
          v-model="selectedRun"
          class="form-select form-select-sm w-auto"
          aria-label="Run to compare with"
        >
          <option value="">Newest kept run</option>
          <option v-for="run in comparison.runs" :key="run.runId" :value="run.runId">{{ runLabel(run) }}</option>
        </select>
      </div>
      <div v-if="error" class="alert alert-warning small mb-0" role="alert">{{ error }}</div>
      <template v-else-if="comparison">
        <p class="small text-muted mb-2">
          <span class="fw-semibold">{{ comparisonStatusLabel(comparison.status) }}</span>
          <template v-if="comparison.previous"> · {{ runLabel(comparison.previous) }}</template>
        </p>
        <p v-if="comparison.reason" class="small mb-2 insight-comparison-reason">{{ comparison.reason }}</p>
        <ul v-if="comparison.notComparableReasons.length > 1" class="small mb-2">
          <li v-for="reason in comparison.notComparableReasons.slice(1)" :key="reason">{{ reason }}</li>
        </ul>
        <p v-if="compared && sections.length === 0" class="small mb-2">
          No route changed what it ran, called, or raised.
        </p>
        <div v-for="section in sections" :key="section.id" class="mb-2" :data-section="section.id">
          <h3 class="h6 small fw-semibold mb-1">
            {{ section.title }}<span v-if="section.note" class="text-muted fw-normal"> · {{ section.note }}</span>
          </h3>
          <ul class="list-unstyled small mb-0 insight-comparison-rows">
            <li v-for="(row, index) in section.rows" :key="index" class="mb-1">
              <InsightText :text="row.sentence" />
            </li>
          </ul>
        </div>
        <div v-if="restart" class="mb-2" data-section="restart">
          <h3 class="h6 small fw-semibold mb-1">Restart cost</h3>
          <p class="small mb-1">{{ restart }}</p>
          <ul v-if="comparison.restartCost.beans.length" class="list-unstyled small mb-0">
            <li v-for="bean in comparison.restartCost.beans" :key="bean.subject">
              <InsightText :text="bean.sentence" />
            </li>
          </ul>
        </div>
        <details v-if="comparison.limitations.length" class="small text-muted">
          <summary>Limits</summary>
          <ul class="mb-0 mt-1">
            <li v-for="limitation in comparison.limitations" :key="limitation">{{ limitation }}</li>
          </ul>
        </details>
      </template>
    </div>
  </section>
</template>

<style scoped>
.insight-comparison-reason,
.insight-comparison-rows {
  max-width: 75ch;
}
</style>
