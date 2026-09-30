<script setup>
import {computed, useId} from 'vue'
import {OPEN_IN_PRESETS, useOpenInPreference} from '../../utils/sourceLocation.js'

const props = defineProps({
  notes: {type: Array, default: () => []}
})

const {openIn, setOpenIn} = useOpenInPreference()
const selected = computed({get: () => openIn.value, set: (value) => setOpenIn(value)})
const selectId = `source-open-in-${useId()}`
const visibleNotes = computed(() => (Array.isArray(props.notes) ? props.notes.filter((note) => note) : []))
</script>

<template>
  <div class="source-location-preference">
    <div class="d-flex flex-wrap align-items-center gap-2">
      <label :for="selectId" class="small text-muted mb-0">Open locations in</label>
      <select :id="selectId" v-model="selected" class="form-select form-select-sm w-auto">
        <option v-for="preset in OPEN_IN_PRESETS" :key="preset.id" :value="preset.id">{{ preset.label }}</option>
      </select>
    </div>
    <ul v-if="visibleNotes.length" class="small text-muted list-unstyled mb-0 mt-1">
      <li v-for="(note, index) in visibleNotes" :key="index">
        <i class="bi bi-info-circle me-1" aria-hidden="true"></i>{{ note }}
      </li>
    </ul>
  </div>
</template>
