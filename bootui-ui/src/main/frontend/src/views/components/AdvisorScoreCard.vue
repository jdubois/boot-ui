<script setup>
import {computed} from 'vue'
import {scoreBandLabel, scoreBandTone} from '../../utils/scannerScore.js'

const props = defineProps({
  score: {type: Number, default: null},
  dismissedCount: {type: Number, default: 0}
})

const hasScore = computed(() => Number.isFinite(props.score))
const bandLabel = computed(() => (hasScore.value ? scoreBandLabel(props.score) : null))
const bandTone = computed(() => (hasScore.value ? scoreBandTone(props.score) : 'secondary'))
</script>

<template>
  <div v-if="hasScore" class="card advisor-score-card mb-3">
    <div class="card-body d-flex align-items-center gap-3">
      <div :class="['advisor-score-gauge', `advisor-score-gauge--${bandTone}`]">
        <span class="advisor-score-gauge__value">{{ score }}</span>
        <span class="advisor-score-gauge__max">/ 100</span>
      </div>
      <div class="min-w-0">
        <div class="text-uppercase text-muted fw-bold small">Advisor score</div>
        <span :class="['badge', `text-bg-${bandTone}`, 'fs-6', 'mt-1']">{{ bandLabel }}</span>
        <div v-if="dismissedCount > 0" class="text-muted small mt-2">
          <i class="bi bi-eye-slash me-1"></i>{{ dismissedCount }} dismissed rule(s) excluded from this score
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.advisor-score-card {
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.1rem;
  box-shadow: 0 0.5rem 1.6rem rgba(15, 23, 42, 0.06);
}

.advisor-score-gauge {
  align-items: center;
  border: 0.35rem solid;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  height: 5.5rem;
  justify-content: center;
  width: 5.5rem;
}

.advisor-score-gauge__value {
  font-size: 1.75rem;
  font-weight: 850;
  line-height: 1;
}

.advisor-score-gauge__max {
  color: #64748b;
  font-size: 0.68rem;
}

.advisor-score-gauge--success {
  border-color: #198754;
  color: #198754;
}

.advisor-score-gauge--warning {
  border-color: #ffc107;
  color: #997404;
}

.advisor-score-gauge--danger {
  border-color: #dc3545;
  color: #dc3545;
}

.advisor-score-gauge--secondary {
  border-color: #cbd5e1;
  color: #64748b;
}

.min-w-0 {
  min-width: 0;
}
</style>
