<script setup>
import {computed, ref, useId, watch} from 'vue'

const props = defineProps({
  modelValue: {type: Boolean, default: true},
  title: {type: String, default: 'Refresh every 10 seconds while this tab is visible'},
  connectionState: {
    type: /** @type {import('vue').PropType<'connecting'|'connected'|'reconnecting'|'paused'|'unavailable'|null>} */ (
      String
    ),
    default: null
  }
})

const emit = defineEmits(['update:modelValue', 'retry'])
const inputId = useId()
const announcement = ref('')

const statusText = computed(() => {
  if (props.connectionState === 'reconnecting') return 'Reconnecting\u2026'
  if (props.connectionState === 'unavailable') return 'Stream unavailable'
  return null
})

watch(
  () => props.connectionState,
  (next, prev) => {
    if (next === 'reconnecting' && prev !== 'reconnecting') {
      announcement.value = 'Auto-refresh stream reconnecting\u2026'
    } else if (next === 'unavailable' && prev !== 'unavailable') {
      announcement.value = 'Auto-refresh stream unavailable. Retrying later.'
    } else if (next === 'connected' && (prev === 'reconnecting' || prev === 'unavailable')) {
      announcement.value = 'Auto-refresh stream connected.'
    } else {
      announcement.value = ''
    }
  },
  {immediate: true}
)

function updateValue(event) {
  emit('update:modelValue', event.target.checked)
}
</script>

<template>
  <div class="auto-refresh-control" :title="title">
    <div class="form-check form-switch mb-0 auto-refresh-toggle">
      <input
        :id="inputId"
        :checked="modelValue"
        class="form-check-input"
        type="checkbox"
        aria-label="Toggle auto-refresh"
        @change="updateValue"
      />
      <label class="form-check-label small auto-refresh-label" :for="inputId">
        <span
          class="auto-refresh-dot"
          :class="{
            'auto-refresh-dot--live': modelValue && (!connectionState || connectionState === 'connected'),
            'auto-refresh-dot--reconnecting': modelValue && connectionState === 'reconnecting',
            'auto-refresh-dot--unavailable': modelValue && connectionState === 'unavailable'
          }"
          aria-hidden="true"
        ></span>
        Auto-refresh
      </label>
    </div>
    <span v-if="statusText" class="auto-refresh-status small" :class="`auto-refresh-status--${connectionState}`">
      {{ statusText }}
    </span>
    <button
      v-if="connectionState === 'unavailable'"
      class="auto-refresh-retry small"
      type="button"
      aria-label="Retry auto-refresh stream connection now"
      @click="emit('retry')"
    >
      Retry now
    </button>
    <span role="status" aria-live="polite" aria-atomic="true" class="visually-hidden">{{ announcement }}</span>
  </div>
</template>

<style scoped>
.auto-refresh-control {
  align-items: center;
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.auto-refresh-label {
  align-items: center;
  display: inline-flex;
  gap: 0.35rem;
}

.auto-refresh-dot {
  background: var(--bootui-text-subtle, #94a3b8);
  border-radius: 999px;
  flex-shrink: 0;
  height: 0.5rem;
  width: 0.5rem;
}

.auto-refresh-dot--live {
  background: var(--bootui-green, #198754);
  box-shadow: 0 0 0 0 rgba(25, 135, 84, 0.5);
  animation: auto-refresh-pulse 1.8s ease-out infinite;
}

.auto-refresh-dot--reconnecting {
  animation: auto-refresh-reconnect-pulse 1.4s ease-in-out infinite;
  background: var(--bootui-warning, #ffc107);
}

.auto-refresh-dot--unavailable {
  background: var(--bootui-danger, #dc3545);
}

.auto-refresh-status {
  font-weight: 600;
  white-space: nowrap;
}

.auto-refresh-status--reconnecting {
  color: var(--bootui-warning-text-strong, #6f5300);
}

.auto-refresh-status--unavailable {
  color: var(--bootui-text, #152033);
}

.auto-refresh-retry {
  background: none;
  border: none;
  border-radius: var(--bootui-radius-xs, 0.35rem);
  color: var(--bootui-blue, #0d6efd);
  cursor: pointer;
  font-weight: 600;
  padding: 0.1rem;
  text-decoration: underline;
  text-underline-offset: 0.12em;
  white-space: nowrap;
}

.auto-refresh-retry:hover {
  text-decoration-thickness: 2px;
}

.auto-refresh-retry:focus-visible {
  outline: 2px solid var(--bootui-blue, #0d6efd);
  outline-offset: 2px;
}

@keyframes auto-refresh-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(25, 135, 84, 0.5);
  }
  70% {
    box-shadow: 0 0 0 0.4rem rgba(25, 135, 84, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(25, 135, 84, 0);
  }
}

@keyframes auto-refresh-reconnect-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

@media (prefers-reduced-motion: reduce) {
  .auto-refresh-dot--live,
  .auto-refresh-dot--reconnecting {
    animation: none;
  }
}
</style>
