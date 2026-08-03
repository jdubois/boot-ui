<script setup>
import {computed, watch, ref} from 'vue'

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

const props = defineProps({
  /** @type {'connecting'|'connected'|'reconnecting'|'paused'|'unavailable'} */
  connectionState: {type: String, default: 'connected'},
  /** Called when the user clicks "Retry now" in the unavailable state. */
  onRetry: {type: Function, default: null}
})

const emit = defineEmits(['retry'])

// Announce only meaningful non-healthy transitions; avoid re-announcing the same state.
const announcement = ref('')
let previousState = props.connectionState

watch(
  () => props.connectionState,
  (next, prev) => {
    previousState = prev
    if (next === 'reconnecting' && prev !== 'reconnecting') {
      announcement.value = 'Stream reconnecting\u2026'
    } else if (next === 'unavailable' && prev !== 'unavailable') {
      announcement.value = 'Stream unavailable. Retrying later.'
    } else if (next === 'connected' && (prev === 'reconnecting' || prev === 'unavailable')) {
      announcement.value = 'Stream connected.'
    } else {
      announcement.value = ''
    }
  }
)

const isVisible = computed(() => props.connectionState === 'reconnecting' || props.connectionState === 'unavailable')

function handleRetry() {
  emit('retry')
  if (typeof props.onRetry === 'function') props.onRetry()
}
</script>

<template>
  <!-- aria-live region is always present in the DOM so screen readers register it on mount. -->
  <span aria-live="polite" aria-atomic="true" class="visually-hidden">{{ announcement }}</span>

  <div v-if="isVisible" class="stream-status-indicator" role="status" aria-label="Stream connection status">
    <template v-if="connectionState === 'reconnecting'">
      <span class="stream-status-dot stream-status-dot--reconnecting" aria-hidden="true"></span>
      <span class="stream-status-label">Reconnecting&hellip;</span>
    </template>

    <template v-else-if="connectionState === 'unavailable'">
      <i class="bi bi-wifi-off stream-status-icon stream-status-icon--unavailable" aria-hidden="true"></i>
      <span class="stream-status-label stream-status-label--unavailable">Stream unavailable</span>
      <button class="stream-status-retry" type="button" @click="handleRetry">Retry now</button>
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
  font-size: 0.75rem;
  gap: 0.4rem;
  margin-bottom: 0.75rem;
  padding: 0.2rem 0.65rem;
}

/* Animated dot for reconnecting state */
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
  color: var(--bootui-danger-text, #b02a37);
}

.stream-status-icon {
  flex-shrink: 0;
  font-size: 0.75rem;
}

.stream-status-icon--unavailable {
  color: var(--bootui-danger-text, #b02a37);
}

.stream-status-retry {
  background: none;
  border: none;
  color: var(--bootui-blue, #0d6efd);
  cursor: pointer;
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0;
  text-decoration: underline;
  white-space: nowrap;
}

.stream-status-retry:hover {
  color: var(--bootui-accessible-deep-blue, #0a53be);
}

.stream-status-retry:focus-visible {
  border-radius: 2px;
  outline: 2px solid var(--bootui-blue, #0d6efd);
  outline-offset: 2px;
}
</style>
