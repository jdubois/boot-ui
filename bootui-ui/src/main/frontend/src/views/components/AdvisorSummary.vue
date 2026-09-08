<script setup>
import {computed} from 'vue'

const props = defineProps({
  score: {type: Number, default: null},
  scoreLabel: {type: String, default: ''},
  scoreReason: {type: String, default: ''},
  incomplete: {type: Boolean, default: false},
  dismissedCount: {type: Number, default: 0},
  scanStatusLabel: {type: String, default: ''},
  scanStatusClass: {type: String, default: 'text-bg-secondary'},
  scanTime: {type: String, default: null},
  metrics: {type: Array, default: () => []}
})

const hasScore = computed(() => Number.isFinite(props.score))
const metricList = computed(
  () => /** @type {Array<{label: string, value: string|number, hint?: string}>} */ (props.metrics || [])
)
const coverageLabel = computed(() => props.scoreLabel || 'Coverage unknown')
const coverageReason = computed(
  () => props.scoreReason || 'Coverage evidence is unavailable. A score does not establish application safety.'
)
const scoreAccessibleLabel = computed(
  () => `Known-findings score: ${props.score} out of 100${props.incomplete ? ' — Scan notes available' : ''}`
)
</script>

<template>
  <div class="card advisor-score-card mb-3">
    <div class="card-body">
      <dl class="advisor-summary__metrics">
        <div class="advisor-summary__metric advisor-summary__metric--status">
          <dt>Scan status</dt>
          <dd>
            <span :class="['badge', hasScore ? 'text-bg-secondary' : scanStatusClass, 'fs-6']">
              {{ hasScore ? 'Results available' : scanStatusLabel }}
            </span>
          </dd>
          <small v-if="scanTime" class="advisor-summary__hint">Scanned at {{ scanTime }}</small>
        </div>
        <div v-for="metric in metricList" :key="metric.label" class="advisor-summary__metric">
          <dt>{{ metric.label }}</dt>
          <dd>{{ metric.value }}</dd>
          <small v-if="metric.hint" class="advisor-summary__hint">{{ metric.hint }}</small>
        </div>
      </dl>

      <div v-if="!hasScore" class="advisor-summary__assessment mt-3">
        <div class="fw-semibold">{{ coverageLabel }}</div>
        <div class="small text-muted">{{ coverageReason }}</div>
      </div>

      <div v-if="hasScore" class="advisor-summary__score mt-3" role="img" :aria-label="scoreAccessibleLabel">
        <span class="advisor-summary__score-label">Known-findings score</span>
        <span class="advisor-summary__value">{{ score }}</span>
        <span class="text-muted">/ 100</span>
      </div>
      <p v-if="hasScore" class="small text-muted mb-0 mt-1">
        Weighted penalties from retained findings, not a measure of application safety.
      </p>
      <details v-if="hasScore && incomplete" class="advisor-summary__notes small text-muted mt-3">
        <summary>Scan notes</summary>
        <p class="mb-0 mt-2">{{ coverageReason }}</p>
      </details>
      <p v-if="dismissedCount > 0" class="advisor-summary__dismissed">
        <i class="bi bi-eye-slash me-1" aria-hidden="true"></i>{{ dismissedCount }} dismissed rule(s) excluded from
        active findings. Dismissal changes score penalties, not application safety.
      </p>
    </div>
  </div>
</template>

<style scoped>
.advisor-summary__score {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.5rem;
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
}

.advisor-summary__assessment,
.advisor-summary__notes {
  overflow-wrap: anywhere;
}

.advisor-summary__notes summary {
  cursor: pointer;
}

.advisor-summary__value {
  font-family: var(--bs-font-monospace);
  font-weight: 700;
}

.advisor-summary__score-label {
  margin-right: 0.5rem;
}

.advisor-summary__metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0.85rem 2rem;
  margin: 0;
}

.advisor-summary__metric {
  min-width: 6rem;
}

.advisor-summary__metric dt {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  font-weight: 500;
  margin-bottom: 0.3rem;
}

.advisor-summary__metric dd {
  font-family: var(--bs-font-monospace);
  font-size: clamp(1.15rem, 2vw, 2.1rem);
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.1;
  color: var(--bootui-text);
  margin: 0;
}

.advisor-summary__metric--status dd {
  font-family: inherit;
  font-size: 1rem;
  letter-spacing: normal;
}

.advisor-summary__hint {
  display: block;
  margin-top: 0.2rem;
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
}

.advisor-summary__dismissed {
  margin: 0.9rem 0 0;
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
}
</style>
