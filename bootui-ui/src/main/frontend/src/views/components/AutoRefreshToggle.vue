<script setup>
import {useId} from 'vue'

defineProps({
  modelValue: {type: Boolean, default: true}
})

const emit = defineEmits(['update:modelValue'])
const inputId = useId()

function updateValue(event) {
  emit('update:modelValue', event.target.checked)
}
</script>

<template>
  <div class="form-check form-switch mb-0" title="Refresh every 10 seconds while this tab is visible">
    <input :id="inputId" :checked="modelValue" class="form-check-input" type="checkbox" @change="updateValue" />
    <label class="form-check-label small" :for="inputId">Auto-refresh</label>
  </div>
</template>
