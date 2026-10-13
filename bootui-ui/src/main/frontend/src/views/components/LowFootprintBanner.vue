<script setup>
import {inject, onMounted, ref} from 'vue'
import {useLowFootprint} from '../../utils/useLowFootprint.js'

// Shown on panels fed by data that low-footprint mode stops recording, so an empty or frozen panel
// is never mistaken for a quiet application.
const {status, refresh} = useLowFootprint()
const panels = inject('panels', ref(null))

// The status endpoint belongs to the Live Memory panel, so only ask when the manifest lists it as usable.
onMounted(() => {
  const owner = (panels.value?.panels ?? []).find((panel) => panel.id === 'live-memory')
  if (status.value === null && owner && owner.enabled !== false && owner.available !== false) refresh()
})
</script>

<template>
  <div v-if="status?.active" class="alert alert-info" data-testid="low-footprint-banner" role="status">
    <i aria-hidden="true" class="bi bi-pause-circle-fill me-2"></i>
    <strong>Paused by low-footprint mode.</strong>
    BootUI has stopped recording this data to save resources. Turn off low-footprint mode from a memory panel to resume.
  </div>
</template>
