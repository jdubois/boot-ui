<script setup>
// A kind's external validation (docs/PLAN-v2.md M4-20) as a calm inline mark: muted text and a glyph, never a filled
// badge, so it qualifies a row without competing with it. The reason stays one hover away and in the open row.
defineProps({
  validation: {type: Object, required: true}
})
</script>

<template>
  <span class="insight-validation insight-validation-mark" :title="validation.reason ?? undefined">
    <i class="bi bi-patch-question" aria-hidden="true"></i>{{ validation.marker }}
  </span>
</template>

<style scoped>
.insight-validation-mark {
  align-items: center;
  color: var(--bootui-text-muted);
  display: inline-flex;
  font-size: 0.75rem;
  font-weight: 500;
  gap: 0.3rem;
  line-height: 1.2;
  white-space: nowrap;
}
</style>
