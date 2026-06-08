<script setup>
defineProps({
  // While true, show a spinner in place of the idle icon and (optionally) swap the label.
  loading: {type: Boolean, default: false},
  // Bootstrap icon class shown when not loading (e.g. "bi-play-circle").
  icon: {type: String, default: null},
  // Text rendered when no default slot content is provided.
  label: {type: String, default: null},
  // Optional text shown instead of `label` while loading.
  loadingLabel: {type: String, default: null},
  // Spacing utility for the spinner; matches the surrounding markup ("me-1" or "me-2").
  spinnerClass: {type: String, default: 'me-1'}
})
</script>

<template>
  <button type="button">
    <span v-if="loading" aria-hidden="true" :class="['spinner-border spinner-border-sm', spinnerClass]"></span>
    <i v-else-if="icon" :class="['bi', icon, 'me-1']"></i>
    <slot>{{ loading && loadingLabel !== null ? loadingLabel : label }}</slot>
  </button>
</template>
