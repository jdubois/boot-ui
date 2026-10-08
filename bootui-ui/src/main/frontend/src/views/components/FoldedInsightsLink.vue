<script setup>
import {computed, inject, ref} from 'vue'
import {insightsUsable} from '../../utils/insightsPanel.js'

// A Runtime Insights kind that did not pass its external validation is folded into the panel showing the same evidence
// (docs/PLAN-v2.md M4-20): Runtime Insights no longer lists its rows by default, and this panel links to them. The
// validation itself stays in the plan and is never shown. It makes no request; the link opens Runtime Insights on the
// kind's theme with every row shown.
defineProps({
  // The theme chip of the folded kind, such as `errors`.
  theme: {type: String, required: true},
  // What the folded rows are, as the sentence's subject, such as `Exception groups per route`.
  what: {type: String, required: true}
})

const panels = inject('panels', ref(null))
const usable = computed(() => insightsUsable(panels.value))
</script>

<template>
  <p v-if="usable" class="small text-muted folded-insights">
    <i class="bi bi-lightbulb me-1" aria-hidden="true"></i>{{ what }}, from the runtime journal, are in
    <RouterLink :to="{path: '/runtime-insights', query: {theme, all: '1'}}">Runtime Insights</RouterLink>, which does
    not list them by default.
  </p>
</template>
