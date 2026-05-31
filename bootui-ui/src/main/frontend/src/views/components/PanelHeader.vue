<script setup>
import {computed, useAttrs} from 'vue'
import {formatRelative} from '../../utils/format.js'

const props = defineProps({
  icon: {type: String, default: null},
  title: {type: String, required: true},
  subtitle: {type: String, default: null},
  loading: {type: Boolean, default: false},
  error: {type: String, default: null},
  lastFetched: {type: Number, default: null}
})

const emit = defineEmits(['refresh'])

const attrs = useAttrs()
const hasRefresh = computed(() => typeof attrs.onRefresh === 'function')

const lastFetchedText = computed(() => {
  if (!props.lastFetched) return null
  return formatRelative(props.lastFetched)
})
</script>

<template>
  <div class="panel-header mb-3">
    <div class="panel-header__info">
      <h2 class="mb-0"><i v-if="icon" :class="['bi', icon, 'me-2']"></i>{{ title }}</h2>
      <p v-if="subtitle" class="text-muted small mb-0 mt-1">{{ subtitle }}</p>
    </div>
    <div class="panel-header__actions">
      <span v-if="lastFetchedText" class="last-fetched-text">{{ lastFetchedText }}</span>
      <slot name="actions"></slot>
      <button
        v-if="hasRefresh"
        :disabled="loading"
        class="btn btn-outline-secondary btn-sm"
        title="Refresh"
        @click="emit('refresh')"
      >
        <i :class="['bi bi-arrow-clockwise', {spin: loading}]"></i>
      </button>
    </div>
  </div>
  <div v-if="error" class="alert alert-danger d-flex align-items-center gap-2 mb-3" role="alert">
    <i class="bi bi-exclamation-triangle-fill flex-shrink-0"></i>
    <span class="flex-grow-1">{{ error }}</span>
    <button v-if="hasRefresh" class="btn btn-sm btn-outline-danger" @click="emit('refresh')">
      <i class="bi bi-arrow-clockwise me-1"></i>Retry
    </button>
  </div>
</template>

<style scoped>
.panel-header {
  align-items: flex-start;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  flex-wrap: wrap;
}

.panel-header__info {
  flex: 1;
  min-width: 0;
}

.panel-header__actions {
  align-items: center;
  display: flex;
  flex-shrink: 0;
  gap: 0.5rem;
}

.last-fetched-text {
  color: var(--bootui-text-subtle, #94a3b8);
  font-size: 0.78rem;
  white-space: nowrap;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.spin {
  animation: spin 900ms linear infinite;
  display: inline-block;
}
</style>
