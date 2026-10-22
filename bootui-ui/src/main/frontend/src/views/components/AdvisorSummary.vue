<script setup>
import {computed, ref, watch} from 'vue'

const props = defineProps({
  score: {type: Number, default: null},
  scoreLabel: {type: String, default: ''},
  scoreReason: {type: String, default: ''},
  incomplete: {type: Boolean, default: false},
  dismissedCount: {type: Number, default: 0},
  scanStatusLabel: {type: String, default: ''},
  scanStatusClass: {type: String, default: 'text-bg-secondary'},
  scanTime: {type: String, default: null},
  metrics: {type: Array, default: () => []},
  // The comparison with the previous scan, for the advisors that key their findings; null on the first scan.
  changes: {type: Object, default: null},
  changesEnabled: {type: Boolean, default: false},
  scanned: {type: Boolean, default: false}
})

const hasScore = computed(() => Number.isFinite(props.score))
const metricList = computed(
  () => /** @type {Array<{label: string, value: string|number, hint?: string}>} */ (props.metrics || [])
)
const coverageLabel = computed(() => props.scoreLabel || 'Coverage unknown')
const coverageReason = computed(
  () => props.scoreReason || 'Coverage evidence is unavailable. A score does not establish application safety.'
)
const showChanges = computed(() => props.changesEnabled && props.scanned)
const resolved = computed(() => (Array.isArray(props.changes?.resolved) ? props.changes.resolved : []))
const changeNotes = computed(() => (Array.isArray(props.changes?.notes) ? props.changes.notes : []))
const comparison = computed(() => {
  const changes = props.changes
  if (!changes) return {kind: 'first', newCount: 0, resolvedCount: 0, unchangedCount: 0, unverifiedCount: 0}
  const counts = {
    newCount: changes.newCount || 0,
    resolvedCount: changes.resolvedCount || 0,
    unchangedCount: changes.unchangedCount || 0,
    unverifiedCount: changes.unverifiedCount || 0
  }
  const compared = counts.newCount + counts.resolvedCount + counts.unchangedCount + counts.unverifiedCount
  if (!changes.complete && compared === 0) return {kind: 'notCompared', ...counts}
  if (!changes.complete) return {kind: 'incomplete', ...counts}
  if (counts.newCount === 0 && counts.resolvedCount === 0) return {kind: 'unchanged', ...counts}
  return {kind: 'changed', ...counts}
})
const changesText = computed(() => {
  const state = comparison.value
  switch (state.kind) {
    case 'first':
      return 'First scan: nothing to compare yet. Run the checks again after a change to see what moved.'
    case 'notCompared':
      return 'Not compared with the previous scan.'
    case 'unchanged':
      return state.unchangedCount === 0
        ? 'No changes since the previous scan. No findings in either scan.'
        : `No changes since the previous scan. ${state.unchangedCount} unchanged.`
    default: {
      const text = `${state.newCount} new, ${state.resolvedCount} resolved, ${state.unchangedCount} unchanged since the previous scan`
      return state.kind === 'incomplete'
        ? `${text}. Comparison incomplete${
            state.unverifiedCount ? `: ${state.unverifiedCount} could not be compared` : ''
          }.`
        : `${text}.`
    }
  }
})
// A visually hidden status region, always present, so a screen reader hears the result of a scan run now but not
// the cached report read on arrival.
const announcement = ref('')
watch(
  () => `${props.changes?.previousScanId ?? ''}|${changesText.value}`,
  () => {
    if (showChanges.value) announcement.value = changesText.value
  }
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

      <div v-if="showChanges" class="advisor-summary__changes mt-3" data-testid="advisor-changes">
        <h3 class="fs-6 fw-semibold mb-1">Since previous scan</h3>
        <p v-if="comparison.kind === 'first'" class="mb-0 small text-muted">
          <i class="bi bi-clock-history me-1" aria-hidden="true"></i>{{ changesText }}
        </p>
        <p v-else-if="comparison.kind === 'notCompared'" class="mb-0 small text-muted">
          <i class="bi bi-exclamation-triangle me-1" aria-hidden="true"></i>{{ changesText }}
        </p>
        <p v-else-if="comparison.kind === 'unchanged'" class="mb-0 small">
          <i class="bi bi-check-circle me-1" aria-hidden="true"></i>{{ changesText }}
        </p>
        <p v-else class="advisor-summary__change-counts mb-0 small">
          <span class="advisor-summary__change">
            <i class="bi bi-plus-circle me-1" aria-hidden="true"></i>{{ comparison.newCount }} new
          </span>
          <span class="advisor-summary__change-separator" aria-hidden="true">·</span>
          <span class="advisor-summary__change">
            <i class="bi bi-check-circle me-1" aria-hidden="true"></i>{{ comparison.resolvedCount }} resolved
          </span>
          <span class="advisor-summary__change-separator" aria-hidden="true">·</span>
          <span class="advisor-summary__change">
            <i class="bi bi-dash-circle me-1" aria-hidden="true"></i>{{ comparison.unchangedCount }} unchanged
          </span>
          <span v-if="comparison.kind === 'incomplete'" class="advisor-summary__change-incomplete text-muted">
            <i class="bi bi-exclamation-triangle me-1" aria-hidden="true"></i>Comparison incomplete<template
              v-if="comparison.unverifiedCount"
              >: {{ comparison.unverifiedCount }} could not be compared</template
            >.
          </span>
        </p>
        <details v-if="changeNotes.length && comparison.kind !== 'first'" class="small text-muted mt-1">
          <summary>Why is this comparison incomplete?</summary>
          <ul class="mb-0 mt-1 ps-3">
            <li v-for="note in changeNotes" :key="note">{{ note }}</li>
          </ul>
        </details>
        <details v-if="resolved.length" class="advisor-summary__resolved small mt-1">
          <summary>Resolved findings ({{ changes.resolvedCount }})</summary>
          <ul class="mb-0 mt-1 ps-3">
            <li v-for="(finding, index) in resolved" :key="index">
              <span class="font-monospace text-muted me-2">{{ finding.ruleId }}</span>
              <span class="font-monospace">{{ finding.text }}</span>
            </li>
          </ul>
          <p v-if="changes.resolvedCount > resolved.length" class="mb-0 mt-1 text-muted">
            Showing {{ resolved.length }} of {{ changes.resolvedCount }} resolved findings.
          </p>
        </details>
      </div>
      <div v-if="changesEnabled" class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
        {{ announcement }}
      </div>

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
.advisor-summary__notes,
.advisor-summary__changes {
  overflow-wrap: anywhere;
}

.advisor-summary__change-counts {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 0.5rem;
}

.advisor-summary__change {
  white-space: nowrap;
}

.advisor-summary__resolved summary,
.advisor-summary__changes details summary {
  cursor: pointer;
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
