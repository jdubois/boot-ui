<script setup>
import {inject} from 'vue'

// The checks that did not fully run, and the ones that ran with a reason an empty or short list must never hide.
const ctx = inject('runtimeInsights')
</script>

<template>
  <ul class="list-unstyled small mb-0 insight-check-limits">
    <li v-for="check in ctx.unrun" :key="check.kind" class="mb-1">
      <span class="fw-semibold">{{ check.title }}</span>
      <span class="text-muted"> · {{ ctx.checkStatusLabel(check.status) }}</span>
      <span v-if="check.reason" class="d-block text-muted">{{ check.reason }}</span>
    </li>
  </ul>
</template>
