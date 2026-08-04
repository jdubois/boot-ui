<script setup>
import {computed, ref, watch} from 'vue'

/**
 * Calm, accessible status chip for SSE-backed panels.
 *
 * Renders nothing when the stream is connected and healthy, so the panel
 * stays noise-free during normal operation. An accessible aria-live region
 * announces meaningful state transitions without spamming screen readers.
 *
 * States rendered:
 *   reconnecting → amber pill with animated dot
 *   unavailable  → warning pill with retry action
 *   paused       → rendered by the auto-refresh toggle; this component stays silent
 *   connected    → briefly announces recovery, then disappears
 */

/** @typedef {'connecting'|'connected'|'reconnecting'|'paused'|'unavailable'} ConnectionState */

const props = defineProps({
  connectionState: {
    type: /** @type {import('vue').PropType<ConnectionState>} */ (String),
    default: 'connected'
  }
})

const emit = defineEmits(['retry'])

const announcement = ref('')

watch(
  () => props.connectionState,
  (next, prev) => {
    if (next === 'reconnecting' && prev !== 'reconnecting') {
      announcement.value = 'Stream reconnecting\u2026'
    } else if (next === 'unavailable' && prev !== 'unavailable') {
      announcement.value = 'Stream unavailable. Retrying later.'
    } else if (next === 'connected' && (prev === 'reconnecting' || prev === 'unavailable')) {
      announcement.value = 'Stream connected.'
    } else {
      announcement.value = ''
    }
  },
  {immediate: true}
)

const isVisible = computed(() => props.connectionState === 'reconnecting' || props.connectionState === 'unavailable')

function handleRetry() {
  emit('retry')
}
</script>

<template>
  <span role="status" aria-live="polite" aria-atomic="true" class="visually-hidden">{{ announcement }}</span>

  <div v-if="isVisible" class="stream-status-indicator">
    <template v-if="connectionState === 'reconnecting'">
      <span class="stream-status-dot stream-status-dot--reconnecting" aria-hidden="true"></span>
      <span class="stream-status-label">Reconnecting&hellip;</span>
    </template>

    <template v-else-if="connectionState === 'unavailable'">
      <i class="bi bi-wifi-off stream-status-icon stream-status-icon--unavailable" aria-hidden="true"></i>
      <span class="stream-status-label stream-status-label--unavailable">Stream unavailable</span>
      <button class="stream-status-retry" type="button" aria-label="Retry stream connection now" @click="handleRetry">
        Retry now
      </button>
    </template>
  </div>
</template>

<style scoped>
.stream-status-indicator {
  align-items: center;
  background: var(--bootui-surface, rgba(255, 255, 255, 0.82));
  border: 1px solid var(--bootui-border, rgba(15, 23, 42, 0.08));
  border-radius: var(--bootui-radius-pill, 999px);
  box-shadow: var(--bootui-shadow-sm, 0 0.25rem 0.75rem rgba(15, 23, 42, 0.05));
  display: inline-flex;
  flex-wrap: wrap;
  font-size: 1rem;
  gap: 0.4rem;
  margin-bottom: 0.75rem;
  max-width: 100%;
  padding: 0.35rem 0.75rem;
}

.stream-status-dot {
  border-radius: 50%;
  flex-shrink: 0;
  height: 0.55rem;
  width: 0.55rem;
}

.stream-status-dot--reconnecting {
  animation: stream-status-pulse 1.4s ease-in-out infinite;
  background-color: var(--bootui-warning, #ffc107);
}

@media (prefers-reduced-motion: reduce) {
  .stream-status-dot--reconnecting {
    animation: none;
    opacity: 0.8;
  }
}

@keyframes stream-status-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.stream-status-label {
  color: var(--bootui-warning-text-strong, #6f5300);
  font-weight: 600;
  white-space: nowrap;
}

.stream-status-label--unavailable {
  color: var(--bootui-text, #152033);
}

.stream-status-icon {
  flex-shrink: 0;
  font-size: inherit;
}

.stream-status-icon--unavailable {
  color: var(--bootui-danger, #dc3545);
}

.stream-status-retry {
  background: none;
  border: none;
  color: var(--bootui-blue, #0d6efd);
  cursor: pointer;
  font-size: inherit;
  font-weight: 600;
  line-height: 1.35;
  padding: 0.1rem;
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 0.12em;
  white-space: nowrap;
}

.stream-status-retry:hover {
  color: var(--bootui-blue, #0d6efd);
  text-decoration-thickness: 2px;
}

.stream-status-retry:focus-visible {
  border-radius: var(--bootui-radius-xs, 0.35rem);
  outline: 2px solid var(--bootui-blue, #0d6efd);
  outline-offset: 2px;
}
</style>
