<script setup>
import {computed} from 'vue'
import {scoreBandLabel, scoreBandTone} from '../../utils/scannerScore.js'

const props = defineProps({
  score: {type: Number, default: null},
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
const bandLabel = computed(() => (hasScore.value ? scoreBandLabel(props.score) : null))
const bandTone = computed(() => (hasScore.value ? scoreBandTone(props.score) : 'secondary'))
const gaugeLabel = computed(() =>
  hasScore.value ? `Advisor score: ${props.score} out of 100 — ${bandLabel.value}` : null
)
</script>

<template>
  <div class="card advisor-score-card mb-3">
    <div class="card-body">
      <div class="advisor-summary">
        <div v-if="hasScore" class="advisor-summary__score">
          <div :class="['advisor-summary__gauge', `text-${bandTone}`]" role="img" :aria-label="gaugeLabel">
            <span class="advisor-summary__value">{{ score }}</span>
            <span class="advisor-summary__max">/ 100</span>
          </div>
          <div class="advisor-summary__band">
            <div class="advisor-summary__eyebrow">Advisor score</div>
            <span :class="['badge', `text-bg-${bandTone}`, 'fs-6']">{{ bandLabel }}</span>
          </div>
        </div>

        <div v-if="hasScore" class="advisor-summary__divider" aria-hidden="true"></div>

        <dl class="advisor-summary__metrics">
          <div class="advisor-summary__metric advisor-summary__metric--status">
            <dt>Scan status</dt>
            <dd>
              <span :class="['badge', scanStatusClass, 'fs-6']">{{ scanStatusLabel }}</span>
            </dd>
            <small v-if="scanTime" class="advisor-summary__hint">Scanned at {{ scanTime }}</small>
          </div>
          <div v-for="metric in metricList" :key="metric.label" class="advisor-summary__metric">
            <dt>{{ metric.label }}</dt>
            <dd>{{ metric.value }}</dd>
            <small v-if="metric.hint" class="advisor-summary__hint">{{ metric.hint }}</small>
          </div>
        </dl>
      </div>

      <p v-if="dismissedCount > 0" class="advisor-summary__dismissed">
        <i class="bi bi-eye-slash me-1"></i>{{ dismissedCount }} dismissed rule(s) excluded from this score
      </p>
    </div>
  </div>
</template>

<style scoped>
.advisor-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem 2rem;
}

.advisor-summary__score {
  display: flex;
  align-items: center;
  gap: 0.9rem;
  flex-shrink: 0;
}

/* Tone (success/warning/danger) is carried by the global, theme-tuned .text-* utility
   applied in the template; the ring and value inherit it through currentColor so the
   gauge stays readable in both themes without re-hardcoding the palette. */
.advisor-summary__gauge {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 4.75rem;
  height: 4.75rem;
  border: 0.3rem solid currentColor;
  border-radius: 50%;
}

.advisor-summary__value {
  font-size: 1.9rem;
  font-weight: 800;
  line-height: 1;
}

.advisor-summary__max {
  font-size: 0.65rem;
  font-weight: 600;
  opacity: 0.7;
}

.advisor-summary__eyebrow {
  font-size: 0.72rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--bootui-text-muted);
  margin-bottom: 0.35rem;
}

.advisor-summary__divider {
  align-self: stretch;
  width: 1px;
  background: var(--bootui-border);
}

.advisor-summary__metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0.85rem 2rem;
  flex: 1 1 16rem;
  margin: 0;
}

.advisor-summary__metric {
  min-width: 6rem;
}

.advisor-summary__metric dt {
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--bootui-text-muted);
  margin-bottom: 0.3rem;
}

.advisor-summary__metric dd {
  font-size: 1.4rem;
  font-weight: 700;
  line-height: 1.1;
  color: var(--bootui-text);
  margin: 0;
}

.advisor-summary__metric--status dd {
  font-size: 1rem;
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

@media (max-width: 575.98px) {
  .advisor-summary__divider {
    display: none;
  }
}
</style>
