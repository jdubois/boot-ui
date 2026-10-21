<script setup>
import {computed, inject, nextTick, ref, watch} from 'vue'
import {describeLoadError} from '../../utils/loadError.js'
import {useLowFootprint} from '../../utils/useLowFootprint.js'

// Shown on panels fed by data that low-footprint mode stops recording, so an empty or frozen panel
// is never mistaken for a quiet application.
const {status, refresh, setEnabled} = useLowFootprint()
const panels = inject('panels', ref(null))

const busy = ref(false)
const failure = ref(null)
const success = ref(null)
const failureNotice = ref(null)
const successNotice = ref(null)
const owner = computed(() => (panels.value?.panels ?? []).find((panel) => panel.id === 'live-memory') ?? null)
const readOnly = computed(() => owner.value?.readOnly === true)
const readOnlyReason = computed(() => owner.value?.readOnlyReason || 'BootUI actions are read-only.')

async function turnOff() {
  if (busy.value || readOnly.value) return
  busy.value = true
  failure.value = null
  success.value = null
  try {
    const next = await setEnabled(false)
    if (next.warnings?.length) {
      failure.value = next.warnings.join(' ')
      await nextTick()
      failureNotice.value?.focus()
    } else {
      success.value = 'Low-footprint mode is off. BootUI recording has resumed.'
      await nextTick()
      successNotice.value?.focus()
    }
  } catch (e) {
    failure.value = e?.body?.reason || describeLoadError(e, 'Unable to turn off low-footprint mode').message
    await nextTick()
    failureNotice.value?.focus()
  } finally {
    busy.value = false
  }
}

// The status endpoint belongs to the Live Memory panel. The manifest can arrive after this view mounts.
watch(
  owner,
  (panel) => {
    if (status.value === null && panel && panel.enabled !== false && panel.available !== false) refresh()
  },
  {immediate: true}
)
</script>

<template>
  <div>
    <div v-if="status?.active" class="alert alert-info" data-testid="low-footprint-banner" role="status">
      <i aria-hidden="true" class="bi bi-pause-circle-fill me-2"></i>
      <strong>Paused by low-footprint mode.</strong>
      BootUI has paused capture for this data. New captured entries will appear after you resume.
      <button
        :disabled="busy || readOnly"
        :title="readOnly ? readOnlyReason : undefined"
        class="btn btn-sm btn-outline-primary ms-2"
        data-testid="low-footprint-banner-off"
        type="button"
        @click="turnOff"
      >
        Turn off low-footprint mode
      </button>
      <span v-if="readOnly" class="ms-2 small text-muted">{{ readOnlyReason }}</span>
    </div>
    <div
      v-if="failure"
      ref="failureNotice"
      class="alert alert-danger"
      data-testid="low-footprint-banner-error"
      role="alert"
      tabindex="-1"
    >
      {{ failure }}
    </div>
    <div
      v-if="success"
      ref="successNotice"
      class="alert alert-success"
      data-testid="low-footprint-banner-success"
      role="status"
      tabindex="-1"
    >
      {{ success }}
    </div>
  </div>
</template>
