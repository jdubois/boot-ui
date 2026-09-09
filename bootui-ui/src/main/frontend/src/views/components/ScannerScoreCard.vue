<script setup>
import {computed} from 'vue'
import {scoreBandTone} from '../../utils/scannerScore.js'
import SpinnerButton from './SpinnerButton.vue'

const props = defineProps({
  title: {type: String, required: true},
  icon: {type: String, default: 'bi-shield-check'},
  tone: {type: String, default: 'primary'},
  to: {type: String, default: null},
  openLabel: {type: String, default: 'Open panel'},
  // idle | running | done | error
  state: {type: String, default: 'idle'},
  score: {type: Number, default: null},
  hasReport: {type: Boolean, default: false},
  scoreLabel: {type: String, default: ''},
  incomplete: {type: Boolean, default: false},
  severityCounts: {type: Array, default: () => []},
  statusLabel: {type: String, default: null},
  statusTone: {type: String, default: 'secondary'},
  errorMessage: {type: String, default: null},
  warningMessage: {type: String, default: null},
  runLabel: {type: String, default: 'Run scan'},
  rerunLabel: {type: String, default: 'Re-run scan'},
  runDisabled: {type: Boolean, default: false},
  idleHint: {type: String, default: 'Run this scanner to inspect findings and assessment coverage.'}
})

const emit = defineEmits(['run'])

const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO', 'UNKNOWN', 'NONE']
const SEVERITY_TONES = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-secondary',
  INFO: 'text-bg-light border'
}

const topSeverities = computed(() =>
  /** @type {Array<{severity: string, count: number}>} */ ([...(props.severityCounts || [])])
    .filter((entry) => Number(entry?.count) > 0)
    .sort((a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity))
)

const hasScore = computed(() => Number.isFinite(props.score))
const coverageLabel = computed(() => props.scoreLabel || 'Not scored')

function severityTone(severity) {
  return SEVERITY_TONES[String(severity).toUpperCase()] || 'text-bg-light border'
}

function onRun() {
  emit('run')
}
</script>

<template>
  <div class="scanner-card card h-100">
    <div class="card-body d-flex flex-column">
      <div class="d-flex align-items-center gap-2 mb-3">
        <span :class="['scanner-icon', `scanner-icon--${tone}`]"><i :class="['bi', icon]"></i></span>
        <div class="flex-grow-1 min-w-0">
          <div class="fw-bold text-truncate">{{ title }}</div>
          <span v-if="statusLabel" :class="['badge', statusTone, 'scanner-status']">{{ statusLabel }}</span>
        </div>
      </div>

      <div class="scanner-body flex-grow-1">
        <slot name="score">
          <template v-if="state === 'running'">
            <div class="d-flex align-items-center gap-2 text-muted">
              <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
              <span>Scanning…</span>
            </div>
          </template>
          <template v-else-if="state === 'error'">
            <div class="text-danger small">
              <i class="bi bi-exclamation-triangle-fill me-1"></i>{{ errorMessage || 'Scan failed' }}
            </div>
          </template>
          <div v-if="hasReport && (state === 'running' || state === 'error')" class="text-muted small my-2">
            Showing the last report.
          </div>
          <div
            v-if="hasScore"
            class="scanner-score-summary mb-2"
            role="img"
            :aria-label="`${title} known-findings score: ${score} out of 100${incomplete ? ' — Scan notes available' : ''}`"
          >
            <div class="d-flex align-items-baseline gap-2">
              <span :class="['scanner-score', `text-${scoreBandTone(score)}-emphasis`]">{{ score }}</span>
              <span class="text-muted small">/ 100</span>
            </div>
            <div class="small text-muted mt-1">Known-findings score</div>
          </div>
          <div v-else-if="hasReport" class="scanner-assessment fw-semibold mb-2">{{ coverageLabel }}</div>
          <template v-if="hasReport || hasScore">
            <div v-if="topSeverities.length" class="d-flex flex-wrap gap-1" aria-label="Retained severity counts">
              <span
                v-for="entry in topSeverities"
                :key="entry.severity"
                :class="['badge', severityTone(entry.severity)]"
              >
                {{ entry.count }} {{ entry.severity.toLowerCase() }}
              </span>
            </div>
            <div v-else-if="hasScore" class="text-muted small">No retained findings in the assessed evidence</div>
          </template>
          <div v-else-if="state === 'idle'" class="text-muted small">{{ idleHint }}</div>
        </slot>
        <div v-if="warningMessage" class="text-warning-emphasis small mt-2" role="status" aria-live="polite">
          <i class="bi bi-exclamation-circle me-1"></i>{{ warningMessage }}
        </div>
      </div>

      <div class="d-flex gap-2 mt-3">
        <slot name="actions">
          <SpinnerButton
            :loading="state === 'running'"
            class="btn btn-sm btn-primary"
            type="button"
            :disabled="runDisabled || state === 'running'"
            @click="onRun"
          >
            {{ state === 'idle' ? runLabel : rerunLabel }}
          </SpinnerButton>
          <router-link
            v-if="to"
            :to="to"
            :aria-label="`${openLabel}: ${title}`"
            class="btn btn-sm btn-outline-secondary ms-auto"
          >
            {{ openLabel }}<i class="bi bi-arrow-right-short"></i>
          </router-link>
        </slot>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scanner-assessment {
  overflow-wrap: anywhere;
}

.scanner-card {
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  transition:
    transform 160ms ease,
    box-shadow 160ms ease;
}

.scanner-card:hover {
  box-shadow: var(--bootui-shadow-md);
  transform: translateY(-2px);
}

.scanner-icon {
  align-items: center;
  border-radius: var(--bootui-radius-md);
  display: inline-flex;
  flex-shrink: 0;
  font-size: 1.1rem;
  height: 2.4rem;
  justify-content: center;
  width: 2.4rem;
}

.scanner-icon--primary {
  background: rgba(13, 110, 253, 0.12);
  color: var(--bootui-blue);
}

.scanner-icon--danger {
  background: rgba(220, 53, 69, 0.12);
  color: var(--bootui-danger);
}

.scanner-icon--warning {
  background: rgba(255, 193, 7, 0.18);
  color: var(--bootui-warning-text);
}

.scanner-icon--info {
  background: rgba(13, 202, 240, 0.16);
  color: var(--bootui-info-text);
}

.scanner-icon--success {
  background: rgba(25, 135, 84, 0.12);
  color: var(--bootui-green);
}

.scanner-status {
  font-size: 0.72rem;
  font-weight: 700;
}

.scanner-score {
  font-family: var(--bs-font-monospace);
  font-size: 2.1rem;
  font-weight: 850;
  line-height: 1;
  color: var(--bootui-text);
}

.min-w-0 {
  min-width: 0;
}

@media (prefers-reduced-motion: reduce) {
  .scanner-card {
    transition: none;
  }

  .scanner-card:hover,
  .scanner-card:focus-visible,
  .scanner-card:focus-within {
    transform: none;
  }
}
</style>
