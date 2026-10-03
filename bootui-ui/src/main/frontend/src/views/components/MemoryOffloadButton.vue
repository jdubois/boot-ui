<script setup>
import {computed, inject, onBeforeUnmount, ref, useId, watch} from 'vue'
import {getJson} from '../../api.js'
import {formatBytes, formatNumber} from '../../utils/format.js'
import {describeLoadError} from '../../utils/loadError.js'
import {useConfirm} from '../../utils/useConfirm.js'

// Shared "Free BootUI memory" header action for every panel that analyzes JVM memory. BootUI's
// own capture buffers (runtime journal, traces, exchanges, events…) live in the same heap the panel
// measures, so this lets the user drop them and request a GC before reading the numbers.
//
// The endpoint belongs to the Live Memory panel (`POST api/live-memory/offload`), so its enabled,
// available, and read-only state in the injected panel manifest decide whether the action is shown
// and allowed — the same state the backend panel access filter enforces.

const props = defineProps({
  // Optional host-specific sentence shown after a successful run (e.g. "Run memory checks again…").
  followUp: {type: String, default: null}
})

const emit = defineEmits(['offloaded'])

const panels = inject('panels', ref(null))
const {confirm} = useConfirm()
const id = useId()
const disclosureId = `memory-offload-${id}`

const open = ref(false)
const busy = ref(false)
const report = ref(null)
const failure = ref(null)
const root = ref(null)

const ownerPanel = computed(() => (panels.value?.panels ?? []).find((panel) => panel.id === 'live-memory') ?? null)
// No manifest entry (standalone render) is treated as available; the backend still enforces access.
const visible = computed(
  () => !ownerPanel.value || (ownerPanel.value.enabled !== false && ownerPanel.value.available !== false)
)
const readOnly = computed(() => ownerPanel.value?.readOnly === true)
const readOnlyReason = computed(() => ownerPanel.value?.readOnlyReason || 'BootUI actions are read-only.')
const failedStores = computed(() => (report.value?.stores ?? []).filter((store) => !store.cleared))
const clearedStores = computed(() => (report.value?.stores ?? []).filter((store) => store.cleared))
const buttonTitle = computed(() =>
  readOnly.value
    ? readOnlyReason.value
    : "Drop BootUI's buffered diagnostics and request a garbage collection so memory figures reflect your application"
)

function toggle() {
  open.value = !open.value
}

function close() {
  open.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape' && open.value) {
    event.stopPropagation()
    close()
  }
}

function onDocumentPointer(event) {
  if (open.value && root.value && !root.value.contains(event.target)) close()
}

watch(open, (value) => {
  if (typeof document === 'undefined') return
  if (value) document.addEventListener('pointerdown', onDocumentPointer)
  else document.removeEventListener('pointerdown', onDocumentPointer)
})

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') document.removeEventListener('pointerdown', onDocumentPointer)
})

async function offload() {
  if (busy.value || readOnly.value) return
  const confirmed = await confirm({
    title: 'Free BootUI memory?',
    message:
      "BootUI's buffered diagnostics — runtime journal, traces, captured exchanges, SQL statements, " +
      'messages, exceptions, and emails — will be discarded, then a garbage collection is requested. ' +
      'Settings and configuration overrides are kept.',
    confirmLabel: 'Free memory',
    danger: true,
    irreversible: true
  })
  if (!confirmed) return
  busy.value = true
  failure.value = null
  try {
    report.value = await getJson('api/live-memory/offload', {method: 'POST'})
    open.value = true
    emit('offloaded', report.value)
  } catch (e) {
    report.value = null
    failure.value = e?.body?.reason || describeLoadError(e, 'Unable to free BootUI memory').message
    open.value = true
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="visible" ref="root" class="memory-offload" @keydown="onKeydown">
    <div class="btn-group btn-group-sm" role="group" aria-label="BootUI memory">
      <button
        :aria-busy="busy || undefined"
        :disabled="busy || readOnly"
        :title="buttonTitle"
        class="btn btn-outline-secondary"
        data-testid="memory-offload"
        type="button"
        @click="offload"
      >
        <span v-if="busy" aria-hidden="true" class="spinner-border spinner-border-sm me-1"></span>
        <i v-else aria-hidden="true" class="bi bi-recycle me-1"></i>
        {{ busy ? 'Freeing…' : 'Free BootUI memory' }}
      </button>
      <button
        :aria-controls="disclosureId"
        :aria-expanded="open ? 'true' : 'false'"
        aria-label="What does Free BootUI memory do?"
        class="btn btn-outline-secondary"
        data-testid="memory-offload-info"
        title="What does this do?"
        type="button"
        @click="toggle"
      >
        <i aria-hidden="true" class="bi bi-info-circle"></i>
      </button>
    </div>

    <div v-show="open" :id="disclosureId" class="memory-offload__panel" data-testid="memory-offload-panel">
      <div class="d-flex align-items-start justify-content-between gap-2 mb-2">
        <strong class="memory-offload__title">Free BootUI memory</strong>
        <button aria-label="Close" class="btn-close btn-close-sm" type="button" @click="close"></button>
      </div>

      <div v-if="report" class="memory-offload__result" data-testid="memory-offload-result" role="status">
        <p class="mb-1">
          Heap used <strong>{{ formatBytes(report.heapUsedBeforeBytes) }}</strong> →
          <strong>{{ formatBytes(report.heapUsedAfterBytes) }}</strong>
          <span v-if="report.reclaimedBytes > 0"> ({{ formatBytes(report.reclaimedBytes) }} reclaimed)</span>
          <span v-else> (no measurable reduction yet)</span>
        </p>
        <p class="mb-1 text-muted">
          {{ formatNumber(report.entriesCleared) }} buffered
          {{ report.entriesCleared === 1 ? 'entry' : 'entries' }} cleared from {{ clearedStores.length }}
          {{ clearedStores.length === 1 ? 'store' : 'stores' }}.
        </p>
        <p v-if="report.explicitGcDisabled" class="mb-1 memory-offload__warning">
          <i aria-hidden="true" class="bi bi-exclamation-triangle me-1"></i>The JVM runs with
          <code>-XX:+DisableExplicitGC</code>, so the collection request was ignored; memory is reclaimed at the next
          regular GC.
        </p>
        <p v-if="failedStores.length" class="mb-1 memory-offload__warning">
          <i aria-hidden="true" class="bi bi-exclamation-triangle me-1"></i>Could not clear:
          {{ failedStores.map((store) => store.label).join(', ') }}.
        </p>
        <p v-if="props.followUp" class="mb-0">{{ props.followUp }}</p>
      </div>
      <p v-else-if="failure" class="memory-offload__warning" role="alert">{{ failure }}</p>

      <div class="memory-offload__explain">
        <p class="mb-2">
          BootUI keeps diagnostics in your application's heap, so they inflate the figures this panel analyzes. This
          action discards BootUI's buffered data and then asks the JVM to run a garbage collection, so measurements
          reflect your application rather than BootUI.
        </p>
        <p class="mb-1">Cleared:</p>
        <ul class="mb-2 ps-3">
          <li>Runtime journal and its aggregates, and in-memory Live Activity</li>
          <li>HTTP exchanges, traces, SQL statements, REST client calls, and transactions</li>
          <li>Kafka, RabbitMQ, JMS, and WebSocket activity; cache events and scheduled runs</li>
          <li>Fault-tolerance events, exceptions (with their triage status), security events, and captured emails</li>
        </ul>
        <p class="mb-2">
          Kept: settings, configuration overrides, dismissed rules, scan reports, and persisted Live Activity history.
          Buffers start filling again with new traffic.
        </p>
        <p class="mb-0 text-muted">
          The cleared data cannot be recovered. A garbage collection request is only a hint: the JVM may defer it, and
          ignores it entirely under <code>-XX:+DisableExplicitGC</code>.
        </p>
        <p v-if="readOnly" class="mt-2 mb-0 memory-offload__warning">
          <i aria-hidden="true" class="bi bi-lock me-1"></i>{{ readOnlyReason }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-offload {
  position: relative;
}

.memory-offload__panel {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  box-shadow: var(--bootui-shadow-md);
  color: var(--bootui-text);
  font-size: 0.82rem;
  line-height: 1.45;
  max-width: calc(100vw - 2rem);
  padding: 0.85rem 1rem;
  position: absolute;
  right: 0;
  top: calc(100% + 0.4rem);
  width: 26rem;
  z-index: 1040;
}

.memory-offload__title {
  font-size: 0.9rem;
}

.memory-offload__result {
  border-bottom: 1px solid var(--bootui-border-subtle);
  margin-bottom: 0.75rem;
  padding-bottom: 0.65rem;
}

.memory-offload__warning {
  color: var(--bootui-warning-text);
}

.btn-close-sm {
  font-size: 0.65rem;
}

@media (max-width: 575.98px) {
  .memory-offload__panel {
    left: 0;
    right: auto;
    width: calc(100vw - 2rem);
  }
}
</style>
