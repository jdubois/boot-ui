<script setup>
import {inject} from 'vue'
import {formatNumber} from '../../utils/format.js'

// The declared routes no request of this run reached. The panel owns the report and provides it.
const ctx = inject('runtimeInsights')
</script>

<template>
  <p class="small text-muted mb-2">Declared routes no request of this run reached, so no finding speaks for them.</p>
  <ul class="list-unstyled small mb-0 insight-not-exercised-list">
    <li v-for="declared in ctx.report.notExercised" :key="declared">
      <code class="bootui-break-anywhere">{{ declared }}</code>
    </li>
  </ul>
  <p v-if="ctx.report.notExercisedOmitted > 0" class="small text-muted mb-0 mt-2">
    {{ formatNumber(ctx.report.notExercisedOmitted) }} more routes not listed.
  </p>
</template>

<style scoped>
.insight-not-exercised-list {
  columns: 2 22rem;
  column-gap: 1.25rem;
}
</style>
