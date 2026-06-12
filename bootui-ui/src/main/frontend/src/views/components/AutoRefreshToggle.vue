<script setup>
import {useId} from 'vue'

defineProps({
  modelValue: {type: Boolean, default: true},
  title: {type: String, default: 'Refresh every 10 seconds while this tab is visible'}
})

const emit = defineEmits(['update:modelValue'])
const inputId = useId()

function updateValue(event) {
  emit('update:modelValue', event.target.checked)
}
</script>

<template>
  <div class="form-check form-switch mb-0 auto-refresh-toggle" :title="title">
    <input
      :id="inputId"
      :checked="modelValue"
      class="form-check-input"
      type="checkbox"
      aria-label="Toggle auto-refresh"
      @change="updateValue"
    />
    <label class="form-check-label small auto-refresh-label" :for="inputId">
      <span class="auto-refresh-dot" :class="{'auto-refresh-dot--live': modelValue}" aria-hidden="true"></span>
      Auto-refresh
    </label>
  </div>
</template>

<style scoped>
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

@media (prefers-reduced-motion: reduce) {
  .auto-refresh-dot--live {
    animation: none;
  }
}
</style>
