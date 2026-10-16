<script setup>
import {computed, inject, onMounted, ref} from 'vue'
import {useLowFootprint} from '../../utils/useLowFootprint.js'

// Shown on panels fed by data that low-footprint mode stops recording, so an empty or frozen panel
// is never mistaken for a quiet application.
const {status, refresh, setEnabled} = useLowFootprint()
const panels = inject('panels', ref(null))

const busy = ref(false)
const failure = ref(null)
const owner = computed(() => (panels.value?.panels ?? []).find((panel) => panel.id === 'live-memory') ?? null)
const readOnly = computed(() => owner.value?.readOnly === true)

async function turnOff() {
  if (busy.value || readOnly.value) return
  busy.value = true
  failure.value = null
  try {
    await setEnabled(false)
  } catch (e) {
    failure.value = e?.body?.reason || 'Unable to turn off low-footprint mode'
  } finally {
    busy.value = false
  }
}

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
    BootUI has stopped recording this data to save resources, so this panel shows nothing new until you resume.
    <button
      :disabled="busy || readOnly"
      :title="readOnly ? 'BootUI actions are read-only.' : undefined"
      class="btn btn-sm btn-outline-primary ms-2"
      data-testid="low-footprint-banner-off"
      type="button"
      @click="turnOff"
    >
      Turn off low-footprint mode
    </button>
    <span v-if="failure" class="ms-2 text-danger" role="alert">{{ failure }}</span>
  </div>
</template>
