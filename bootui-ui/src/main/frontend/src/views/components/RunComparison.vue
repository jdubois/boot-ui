<script setup>
import {computed, ref, watch} from 'vue'
import {getJson} from '../../api.js'
import {describeLoadError} from '../../utils/loadError.js'
import {formatNumber} from '../../utils/format.js'
import {
  changeMarker,
  comparisonSections,
  comparisonStatusIcon,
  comparisonStatusLabel,
  isComparison,
  restartCostText,
  runLabel
} from '../../utils/runComparison.js'
import InsightText from './InsightText.vue'

// Reloaded with the report, so the comparison follows the run as it records more.
const props = defineProps({refreshKey: {type: [Number, String], default: 0}})
// 'loaded' carries the comparison once a fetch settles, null on error or an unexpected shape, so a caller showing a
// summary of this comparison never keeps a stale one from a request that failed after an earlier success.
const emit = defineEmits(['loaded'])

const comparison = ref(null)
const error = ref(null)
const loading = ref(false)
const selectedRun = ref('')

// Guards against an older, slower request overwriting a newer one when the selected run or the refresh key changes
// again before the first fetch settles.
let requestToken = 0

async function load() {
  const token = ++requestToken
  loading.value = true
  error.value = null
  try {
    const query = selectedRun.value ? `?run=${encodeURIComponent(selectedRun.value)}` : ''
    const value = await getJson(`api/runtime-insights/comparison${query}`)
    if (token !== requestToken) return
    comparison.value = isComparison(value) ? value : null
    emit('loaded', comparison.value)
  } catch (e) {
    if (token !== requestToken) return
    error.value = describeLoadError(e, 'Unable to load the run comparison')
    comparison.value = null
    emit('loaded', null)
  } finally {
    if (token === requestToken) loading.value = false
  }
}

watch(() => props.refreshKey, load, {immediate: true})
watch(selectedRun, load)

const sections = computed(() => comparisonSections(comparison.value))
const compared = computed(() => comparison.value?.status === 'COMPARED')
const restart = computed(() => restartCostText(comparison.value?.restartCost))
const extraReasons = computed(() => comparison.value?.notComparableReasons?.slice(1) ?? [])
</script>

<template>
  <section
    id="insight-comparison"
    class="card insight-comparison"
    aria-labelledby="insight-comparison-title"
    :aria-busy="loading"
    tabindex="-1"
  >
    <div class="card-body">
      <header class="insight-comparison-header">
        <div class="insight-comparison-heading">
          <h2 id="insight-comparison-title" class="h6 mb-0">Compared with the previous run</h2>
          <span
            v-if="comparison"
            class="insight-comparison-status"
            :class="{'insight-comparison-status-settled': compared}"
            data-testid="comparison-status"
          >
            <i class="bi" :class="comparisonStatusIcon(comparison.status)" aria-hidden="true"></i>
            {{ comparisonStatusLabel(comparison.status) }}
          </span>
        </div>
        <select
          v-if="comparison?.runs?.length > 1"
          v-model="selectedRun"
          class="form-select form-select-sm insight-comparison-picker"
          aria-label="Run to compare with"
        >
          <option value="">Newest kept run</option>
          <option v-for="run in comparison.runs" :key="run.runId" :value="run.runId">
            {{ runLabel(run, comparison) }}
          </option>
        </select>
      </header>

      <div v-if="error" class="alert alert-warning small mb-0 mt-3" role="alert">{{ error }}</div>
      <p v-else-if="!comparison && loading" class="small text-muted mb-0 mt-2" role="status">
        Comparing with the previous run…
      </p>
      <template v-else-if="comparison">
        <p v-if="comparison.previous" class="small text-muted mb-0 mt-1 insight-comparison-against">
          Against {{ runLabel(comparison.previous, comparison) }}
        </p>
        <p v-if="comparison.reason" class="mb-0 mt-2 insight-comparison-reason">{{ comparison.reason }}</p>
        <ul v-if="extraReasons.length" class="small mb-0 mt-1 insight-comparison-reason">
          <li v-for="reason in extraReasons" :key="reason">{{ reason }}</li>
        </ul>
        <p v-if="compared && sections.length === 0" class="mb-0 mt-2">
          No eligible route or execution changed what it ran, called, or raised.
        </p>

        <div
          v-for="section in sections"
          :key="section.id"
          class="insight-comparison-section"
          :data-section="section.id"
        >
          <h3 class="h6 mb-1">
            {{ section.title }}
            <span class="text-muted fw-normal small">
              · {{ section.rows.length }}<template v-if="section.note"> · {{ section.note }}</template>
            </span>
          </h3>
          <ul class="list-unstyled mb-0 insight-comparison-rows">
            <li v-for="(row, index) in section.rows" :key="index" class="insight-comparison-row">
              <span class="insight-comparison-marker" :title="changeMarker(row.change).label">
                <i class="bi" :class="changeMarker(row.change).icon" aria-hidden="true"></i>
                <span class="visually-hidden">{{ changeMarker(row.change).label }}:</span>
              </span>
              <span class="insight-comparison-sentence"><InsightText :text="row.sentence" /></span>
            </li>
          </ul>
        </div>

        <div v-if="restart" class="insight-comparison-section" data-section="restart">
          <h3 class="h6 mb-1">Restart cost</h3>
          <p class="small mb-1">
            Ready in
            <strong class="insight-comparison-figure"
              >{{ formatNumber(Math.round(comparison.restartCost.readyMsAfter)) }} ms</strong
            >
            after this restart,
            <span class="insight-comparison-figure"
              >{{ formatNumber(Math.round(comparison.restartCost.readyMsBefore)) }} ms</span
            >
            after run {{ comparison.previous.ordinal }} (the immediately preceding restart).
          </p>
          <ul v-if="comparison.restartCost.beans.length" class="list-unstyled mb-0 insight-comparison-rows">
            <li v-for="bean in comparison.restartCost.beans" :key="bean.subject" class="insight-comparison-row">
              <span class="insight-comparison-marker" :title="changeMarker(bean.change).label">
                <i class="bi" :class="changeMarker(bean.change).icon" aria-hidden="true"></i>
                <span class="visually-hidden">{{ changeMarker(bean.change).label }}:</span>
              </span>
              <span class="insight-comparison-sentence"><InsightText :text="bean.sentence" /></span>
            </li>
          </ul>
        </div>

        <p
          v-else-if="comparison.restartCost?.reason"
          class="small text-muted mt-3 mb-0"
          data-testid="restart-unavailable"
        >
          Restart cost unavailable: {{ comparison.restartCost.reason }}
        </p>

        <details v-if="comparison.limitations.length" class="small text-muted mt-3 insight-comparison-limits">
          <summary>Limits · {{ comparison.limitations.length }}</summary>
          <ul class="mb-0 mt-1">
            <li v-for="limitation in comparison.limitations" :key="limitation">{{ limitation }}</li>
          </ul>
        </details>
      </template>
    </div>
  </section>
</template>

<style scoped>
.insight-comparison:focus-visible {
  outline: 2px solid var(--bootui-green);
  outline-offset: 2px;
}

.insight-comparison-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem 1rem;
}

.insight-comparison-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.insight-comparison-status {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.1rem 0.6rem;
  border: 1px dashed var(--bs-border-color);
  border-radius: var(--bootui-radius-pill);
  color: var(--bs-secondary-color);
  font-size: 0.8rem;
  font-weight: 600;
  white-space: nowrap;
}

.insight-comparison-status-settled {
  border-style: solid;
  background: var(--bs-secondary-bg);
  color: var(--bs-body-color);
}

.insight-comparison-picker {
  width: auto;
  max-width: min(100%, 18rem);
}

.insight-comparison-figure {
  font-variant-numeric: tabular-nums;
}

.insight-comparison-reason,
.insight-comparison-against,
.insight-comparison-section {
  max-width: 80ch;
}

.insight-comparison-section {
  margin-top: 1.25rem;
}

.insight-comparison-rows {
  border-top: 1px solid var(--bootui-border);
}

.insight-comparison-row {
  display: grid;
  grid-template-columns: 1.5rem minmax(0, 1fr);
  align-items: baseline;
  gap: 0.35rem;
  padding: 0.4rem 0;
  border-bottom: 1px solid var(--bootui-border);
  font-size: 0.875rem;
}

.insight-comparison-marker {
  display: inline-flex;
  justify-content: center;
  color: var(--bs-secondary-color);
}

.insight-comparison-sentence {
  overflow-wrap: anywhere;
}

.insight-comparison-limits summary {
  cursor: pointer;
}
</style>
