<script setup>
defineProps({
  // The reason this panel is read-only, appended after the slot text.
  reason: {type: String, default: ''}
})
</script>

<template>
  <div class="alert alert-warning small">
    <i class="bi bi-lock me-1"></i>
    <slot></slot>
    {{ reason }}
  </div>
</template>
