<script setup>
import {shortTraceId} from '../../utils/correlation.js'

defineProps({
  traceId: {type: String, default: null},
  targets: {type: Array, default: () => []}
})

defineEmits(['pivot', 'clear'])
</script>

<template>
  <div v-if="traceId" class="alert alert-info d-flex flex-wrap align-items-center gap-2 correlation-banner">
    <span class="me-1"><i class="bi bi-link-45deg me-1"></i>Correlated with trace</span>
    <code class="correlation-trace">{{ shortTraceId(traceId) }}</code>
    <span v-if="targets.length" class="text-muted small ms-1">View related:</span>
    <button
      v-for="target in targets"
      :key="target.name"
      type="button"
      class="btn btn-sm btn-outline-primary correlation-pivot"
      :title="`View this trace in ${target.label}`"
      @click="$emit('pivot', target.name)"
    >
      <i :class="['bi', target.icon, 'me-1']"></i>{{ target.label }}
    </button>
    <button type="button" class="btn btn-sm btn-outline-secondary ms-auto" @click="$emit('clear')">
      <i class="bi bi-x-lg me-1"></i>Clear filter
    </button>
  </div>
</template>

<style scoped>
.correlation-trace {
  word-break: break-all;
}
</style>
