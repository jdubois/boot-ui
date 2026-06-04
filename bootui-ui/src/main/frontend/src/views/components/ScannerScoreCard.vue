<script setup>
import {computed} from 'vue'
import {scoreBandLabel, scoreBandTone} from '../../utils/scannerScore.js'

const props = defineProps({
  title: {type: String, required: true},
  icon: {type: String, default: 'bi-shield-check'},
  tone: {type: String, default: 'primary'},
  to: {type: String, default: null},
  openLabel: {type: String, default: 'Open panel'},
  // idle | running | done | error
  state: {type: String, default: 'idle'},
  score: {type: Number, default: null},
  severityCounts: {type: Array, default: () => []},
  statusLabel: {type: String, default: null},
  statusTone: {type: String, default: 'secondary'},
  errorMessage: {type: String, default: null},
  runLabel: {type: String, default: 'Run scan'},
  rerunLabel: {type: String, default: 'Re-run scan'},
  runDisabled: {type: Boolean, default: false},
  idleHint: {type: String, default: 'Run this scanner to compute a score.'}
})

const emit = defineEmits(['run'])

const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const SEVERITY_TONES = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-secondary',
  INFO: 'text-bg-light border'
}

const topSeverities = computed(() =>
  [...(props.severityCounts || [])]
    .filter((entry) => Number(entry?.count) > 0)
    .sort((a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity))
)

const hasScore = computed(() => props.state === 'done' && Number.isFinite(props.score))
const bandLabel = computed(() => (hasScore.value ? scoreBandLabel(props.score) : null))
const bandTone = computed(() => (hasScore.value ? scoreBandTone(props.score) : 'secondary'))

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
          <template v-else-if="hasScore">
            <div class="d-flex align-items-baseline gap-2">
              <span :class="['scanner-score', `scanner-score--${bandTone}`]">{{ score }}</span>
              <span class="text-muted small">/ 100</span>
              <span :class="['badge', `text-bg-${bandTone}`, 'ms-auto']">{{ bandLabel }}</span>
            </div>
            <div v-if="topSeverities.length" class="d-flex flex-wrap gap-1 mt-2">
              <span
                v-for="entry in topSeverities"
                :key="entry.severity"
                :class="['badge', severityTone(entry.severity)]"
              >
                {{ entry.count }} {{ entry.severity.toLowerCase() }}
              </span>
            </div>
            <div v-else class="text-success small mt-2"><i class="bi bi-check-circle me-1"></i>No findings</div>
          </template>
          <template v-else>
            <div class="text-muted small">{{ idleHint }}</div>
          </template>
        </slot>
      </div>

      <div class="d-flex gap-2 mt-3">
        <slot name="actions">
          <button
            class="btn btn-sm btn-primary"
            type="button"
            :disabled="runDisabled || state === 'running'"
            @click="onRun"
          >
            <span v-if="state === 'running'" class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {{ state === 'idle' ? runLabel : rerunLabel }}
          </button>
          <router-link v-if="to" :to="to" class="btn btn-sm btn-outline-secondary ms-auto">
            {{ openLabel }}<i class="bi bi-arrow-right-short"></i>
          </router-link>
        </slot>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scanner-card {
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.1rem;
  box-shadow: 0 0.75rem 2rem rgba(15, 23, 42, 0.06);
  transition:
    transform 160ms ease,
    box-shadow 160ms ease;
}

.scanner-card:hover {
  box-shadow: 0 1rem 2.4rem rgba(15, 23, 42, 0.12);
  transform: translateY(-2px);
}

.scanner-icon {
  align-items: center;
  border-radius: 0.85rem;
  display: inline-flex;
  flex-shrink: 0;
  font-size: 1.1rem;
  height: 2.4rem;
  justify-content: center;
  width: 2.4rem;
}

.scanner-icon--primary {
  background: rgba(13, 110, 253, 0.12);
  color: #0d6efd;
}

.scanner-icon--danger {
  background: rgba(220, 53, 69, 0.12);
  color: #dc3545;
}

.scanner-icon--warning {
  background: rgba(255, 193, 7, 0.18);
  color: #997404;
}

.scanner-icon--info {
  background: rgba(13, 202, 240, 0.16);
  color: #087990;
}

.scanner-icon--success {
  background: rgba(25, 135, 84, 0.12);
  color: #198754;
}

.scanner-status {
  font-size: 0.66rem;
  font-weight: 700;
}

.scanner-score {
  font-size: 2.1rem;
  font-weight: 850;
  line-height: 1;
}

.scanner-score--success {
  color: #198754;
}

.scanner-score--warning {
  color: #997404;
}

.scanner-score--danger {
  color: #dc3545;
}

.scanner-score--secondary {
  color: #64748b;
}

.min-w-0 {
  min-width: 0;
}
</style>
