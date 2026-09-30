<script setup>
import {computed} from 'vue'
import {formatNumber} from '../../utils/format.js'

/**
 * States how a bounded capture buffer retains its records, so a panel never implies its window is complete:
 * how many records are kept of the capacity, how much of it is reserved for failed or slow records, and how many
 * were evicted. An application-managed recorder only reports what BootUI read.
 */
const props = defineProps({
  // The report's `retention` object; nothing renders when it is absent.
  retention: {type: Object, default: null},
  // Plural noun for the buffered records, such as "exchanges".
  noun: {type: String, required: true},
  // What qualifies a record for the reserved share, such as "5xx or slow".
  reservedFor: {type: String, required: true}
})

const owned = computed(() => props.retention && !props.retention.applicationManaged)

const slowLabel = computed(() => {
  const threshold = Number(props.retention?.slowThresholdMillis ?? 0)
  return threshold > 0 ? `slow means ≥ ${formatNumber(threshold)} ms` : 'slow classification is off'
})

const reservationSummary = computed(() => {
  const r = props.retention
  if (!r || !r.reservedCapacity) return 'no reserved share, so the oldest are evicted first'
  return `${formatNumber(r.reserved)} of ${formatNumber(r.reservedCapacity)} reserved for recent ${props.reservedFor} ${props.noun} (${slowLabel.value})`
})
</script>

<template>
  <p v-if="owned" class="capture-retention text-muted small mb-2">
    <i aria-hidden="true" class="bi bi-archive me-1"></i>
    <span class="fw-semibold">Retention:</span>
    keeping {{ formatNumber(retention.retained) }} of {{ formatNumber(retention.capacity) }} {{ noun }} ·
    {{ reservationSummary }} · {{ formatNumber(retention.evicted) }} evicted since startup
  </p>
  <p v-else-if="retention" class="capture-retention text-muted small mb-2">
    <i aria-hidden="true" class="bi bi-archive me-1"></i>
    <span class="fw-semibold">Retention is managed by the application:</span>
    BootUI read {{ formatNumber(retention.retained) }} {{ noun }} from its recorder and cannot reserve failures or count
    evictions there.
  </p>
</template>
