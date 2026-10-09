<script setup>
import {computed, inject, ref, useId} from 'vue'
import {
  diagnosticActionError,
  getDiagnosticAcknowledgement,
  isJavaAgentReport
} from '../../utils/diagnosticAcknowledgement.js'

// The runtime switch of one BootUI agent sensor (docs/PLAN-v2.md M5-14), shared by the Java Agent and Side
// Effects panels. The endpoint belongs to the Java Agent panel (`POST api/java-agent/sensors/{id}`), so that panel's
// enabled, available, and read-only state in the injected manifest decide whether the switch shows and is usable: the
// same state the backend panel access filter enforces. A switch lasts until the JVM ends and is never written anywhere.

const props = defineProps({
  // A JavaAgentSensorToggleDto: id, configured, enabled, overridden, state, optInReason, available, unavailableReason,
  // failure.
  toggle: {type: Object, required: true}
})

// `switched` carries the Java Agent report after the switch; `stale` asks the panel to read its state again after a
// refused or failed switch, which the bridge may still have applied.
const emit = defineEmits(['switched', 'stale'])

const STATES = {
  off: 'Off',
  released: 'Off',
  installing: 'Installing',
  testing: 'Self-testing',
  installed: 'Recording',
  'self-test-failed': 'Self-test failed',
  failed: 'Failed',
  'release-failed': 'Failed'
}

const panels = inject('panels', ref(null))
const id = useId()
const inputId = `agent-sensor-${props.toggle.id}-${id}`
const detailsId = `${inputId}-details`
const busy = ref(false)
const failure = ref(null)

const ownerPanel = computed(() => (panels.value?.panels ?? []).find((panel) => panel.id === 'java-agent') ?? null)
// Fail closed: without a loaded manifest listing an enabled, available Java Agent panel, no switch is offered.
const visible = computed(
  () => ownerPanel.value !== null && ownerPanel.value.enabled !== false && ownerPanel.value.available !== false
)
const readOnly = computed(() => ownerPanel.value?.readOnly === true)
const blockedReason = computed(() => {
  if (readOnly.value) return ownerPanel.value?.readOnlyReason || 'The Java Agent panel is read-only.'
  if (!props.toggle.available) return props.toggle.unavailableReason || 'This sensor cannot be switched now.'
  return null
})
const stateLabel = computed(() => {
  const state = props.toggle.state || 'off'
  if (STATES[state]) return STATES[state]
  return state.startsWith('self-test-failed') ? 'Self-test failed' : state
})
const stateTone = computed(() => {
  const state = props.toggle.state || 'off'
  if (state === 'installed') return 'text-success-emphasis'
  if (state.includes('failed')) return 'text-danger-emphasis'
  if (state === 'off' || state === 'released') return 'text-body-secondary'
  return 'text-info-emphasis'
})

async function flip(event) {
  const target = event.target.checked
  // The switch follows the server's answer, not the click.
  event.target.checked = props.toggle.enabled
  if (busy.value || blockedReason.value) return
  busy.value = true
  failure.value = null
  try {
    const body = await getDiagnosticAcknowledgement(
      `api/java-agent/sensors/${encodeURIComponent(props.toggle.id)}`,
      {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({enabled: target})
      },
      isJavaAgentReport
    )
    emit('switched', body)
  } catch (e) {
    failure.value = diagnosticActionError(e, `Could not switch the ${props.toggle.id} sensor`)
    emit('stale')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="visible" class="agent-sensor-toggle" :data-testid="`agent-sensor-toggle-${toggle.id}`">
    <div class="d-flex flex-wrap align-items-center gap-2">
      <div class="form-check form-switch m-0">
        <input
          :id="inputId"
          class="form-check-input"
          type="checkbox"
          role="switch"
          :checked="toggle.enabled"
          :disabled="busy || blockedReason !== null"
          :aria-describedby="detailsId"
          @change="flip"
        />
        <label class="form-check-label fw-semibold" :for="inputId">
          Record with the <code>{{ toggle.id }}</code> sensor
        </label>
      </div>
      <span :class="['small', stateTone]" aria-live="polite">{{ busy ? 'Switching…' : stateLabel }}</span>
      <span v-if="toggle.overridden" class="badge text-bg-info" data-testid="agent-sensor-overridden">Overridden</span>
    </div>
    <p :id="detailsId" class="small text-muted mb-0 mt-1">
      {{ toggle.optInReason }}
      Configured: <strong>{{ toggle.configured ? 'on' : 'off' }}</strong> in <code>bootui.agent.sensors</code>.
      <template v-if="toggle.overridden">
        Switched {{ toggle.enabled ? 'on' : 'off' }} at run time until this JVM ends, across restarts and live reloads;
        never written to a file.
      </template>
    </p>
    <p v-if="blockedReason" class="small text-warning-emphasis mb-0 mt-1">{{ blockedReason }}</p>
    <p v-if="toggle.failure" class="small text-danger-emphasis mb-0 mt-1">{{ toggle.failure }}</p>
    <p v-if="failure" class="small text-danger-emphasis mb-0 mt-1" role="alert">{{ failure }}</p>
  </div>
</template>
