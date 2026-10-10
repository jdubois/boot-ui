<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {ApiError, apiFetch} from '../../api.js'
import SpinnerButton from './SpinnerButton.vue'
import {formatBytes, formatClockTime, formatNumber} from '../../utils/format.js'
import {formatLoadError} from '../../utils/loadError.js'
import {useConfirm} from '../../utils/useConfirm.js'
import {
  diagnosticActionError,
  getDiagnosticAcknowledgement,
  isJournalClearAcknowledgement
} from '../../utils/diagnosticAcknowledgement.js'

// The runtime journal's status block and its Clear recording action (docs/PLAN-v2.md §5.2). Mounted only when the
// developer opens it, so opening Live Activity makes no extra request.
const props = defineProps({
  readOnly: {type: Boolean, default: false},
  readOnlyReason: {type: String, default: ''}
})
const emit = defineEmits(['flash', 'loaded'])

const {confirm} = useConfirm()
const status = ref(null)
const loading = ref(false)
const clearing = ref(false)
const error = ref('')
let loadToken = 0

onBeforeUnmount(() => loadToken++)

const recorded = computed(() => Object.entries(status.value?.recorded ?? {}))
const dropped = computed(() => Object.entries(status.value?.dropped ?? {}))
const previousRuns = computed(() => status.value?.previousRuns ?? [])
// The BootUI agent's evidence kept outside the journal (docs/PLAN-v2.md §5.17, M5-11), null without the agent.
const agentEvidence = computed(() => status.value?.agentEvidence ?? null)
const EVIDENCE_STORES = {'code-paths': 'Code Paths', 'code-inventory': 'Code Inventory'}
const EVIDENCE_COUNTS = {
  requestTrees: ['request tree', 'request trees'],
  routes: ['route', 'routes'],
  routeNodes: ['route-tree node', 'route-tree nodes'],
  firstCalls: ['first call', 'first calls'],
  firstCallsWithRequest: ['with its request', 'with their request'],
  firstLoads: ['first load', 'first loads']
}

function storeLabel(store) {
  return EVIDENCE_STORES[store.store] ?? store.store
}

function storeCounts(store) {
  return Object.entries(store.counts ?? {})
    .filter(([name]) => EVIDENCE_COUNTS[name])
    .map(([name, count]) => {
      const [one, many] = EVIDENCE_COUNTS[name]
      return `${formatNumber(count)} ${count === 1 ? one : many}`
    })
    .join(', ')
}

// The method names a store keeps beside its evidence, which a clear keeps too.
function storeIndex(store) {
  const bytes = store.counts?.indexBytes ?? 0
  return bytes > 0 ? `${formatBytes(bytes)} of method names kept apart` : ''
}
const boundLabel = computed(() => {
  if (status.value?.bindingBound === 'COUNT') return 'the event-count bound is reached'
  if (status.value?.bindingBound === 'BYTES') return 'the memory bound is reached'
  return ''
})

async function load() {
  const token = ++loadToken
  const isCurrent = () => token === loadToken
  loading.value = true
  error.value = ''
  try {
    const res = await apiFetch('api/activity/journal')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const loaded = await res.json()
    if (typeof loaded?.enabled !== 'boolean') throw new Error('Invalid runtime journal status response')
    if (isCurrent()) {
      status.value = loaded
      emit('loaded', loaded)
    }
  } catch (err) {
    if (isCurrent()) {
      error.value = formatLoadError(err, 'Could not load the runtime journal status')
      emit('loaded', null)
    }
  } finally {
    if (isCurrent()) loading.value = false
  }
}

async function clearRecording() {
  if (props.readOnly) {
    emit('flash', props.readOnlyReason, 'warning')
    return
  }
  const confirmed = await confirm({
    title: 'Clear recording?',
    message:
      'Drops every event the runtime journal recorded in this run, and the aggregates computed from them. The ' +
      'counts of recorded, dropped, and evicted events are kept.' +
      (agentEvidence.value
        ? " It also drops the BootUI agent's evidence recorded with them: Code Paths' request and route trees, and" +
          " Code Inventory's first requests and routes. Which methods executed is kept."
        : ''),
    confirmLabel: 'Clear recording',
    danger: true,
    irreversible: true
  })
  if (!confirmed) return

  clearing.value = true
  try {
    const result = await getDiagnosticAcknowledgement(
      'api/activity/journal/clear',
      {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({confirm: true})
      },
      isJournalClearAcknowledgement
    )
    emit('flash', result.message, 'success')
    await load()
  } catch (err) {
    emit(
      'flash',
      diagnosticActionError(err, 'Could not clear the recording'),
      err instanceof ApiError ? 'warning' : 'danger'
    )
    if (!(err instanceof ApiError) || err.status >= 500) await load()
  } finally {
    clearing.value = false
  }
}

function counted(count, noun, plural = `${noun}s`) {
  return `${formatNumber(count)} ${count === 1 ? noun : plural}`
}

onMounted(load)
</script>

<template>
  <section class="card mb-3 runtime-journal" aria-labelledby="runtime-journal-title">
    <div class="card-body py-2">
      <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
        <h2 id="runtime-journal-title" class="h6 mb-0">
          <i class="bi bi-journal-text me-1" aria-hidden="true"></i>Runtime journal
        </h2>
        <span v-if="status?.runId" class="text-muted small"
          >run <code>{{ status.runId }}</code></span
        >
        <div class="ms-auto d-flex gap-2">
          <SpinnerButton
            :loading="loading"
            :disabled="loading"
            class="btn btn-sm btn-outline-secondary"
            icon="bi-arrow-clockwise"
            label="Refresh"
            @click="load"
          />
          <SpinnerButton
            v-if="status?.enabled"
            :loading="clearing"
            :disabled="readOnly || clearing"
            :title="readOnly ? readOnlyReason : 'Drop the events and aggregates recorded in this run'"
            class="btn btn-sm btn-outline-danger"
            icon="bi-trash3"
            label="Clear recording"
            @click="clearRecording"
          />
        </div>
      </div>
      <p v-if="error" class="small text-danger mb-0" role="alert">{{ error }}</p>
      <p v-else-if="!status" class="small text-muted mb-0">Loading the runtime journal status…</p>
      <p v-else-if="!status.enabled" class="small text-muted mb-0">
        The runtime journal is disabled. Set <code>bootui.runtime-journal.enabled=true</code> to record this
        application's runtime events.
      </p>
      <dl v-else class="row small mb-0 runtime-journal-facts">
        <dt class="col-sm-3">Retained</dt>
        <dd class="col-sm-9">
          {{ formatNumber(status.retainedEvents) }} of {{ formatNumber(status.maxEvents) }} events,
          {{ formatBytes(status.retainedBytes) }} of {{ formatBytes(status.maxBytes) }}
          <span v-if="status.oldestRetainedAt != null">, oldest at {{ formatClockTime(status.oldestRetainedAt) }}</span>
          <span v-if="status.reservedCapacity > 0" class="text-muted">
            ({{ formatNumber(status.reservedEvents) }} of {{ formatNumber(status.reservedCapacity) }} kept for failed or
            slow events)
          </span>
        </dd>
        <template v-if="agentEvidence">
          <dt class="col-sm-3">Agent evidence</dt>
          <dd class="col-sm-9 runtime-journal-agent-evidence">
            {{ formatBytes(agentEvidence.retainedBytes) }} of {{ formatBytes(agentEvidence.maxBytes) }}, kept outside
            the journal and cleared with it
            <ul class="list-unstyled mb-0">
              <li v-for="store in agentEvidence.stores" :key="store.store">
                {{ storeLabel(store) }}:
                <span v-if="!store.visible || store.retainedBytes == null" class="text-muted">{{ store.note }}</span>
                <template v-else>
                  {{ formatBytes(store.retainedBytes)
                  }}<span v-if="storeCounts(store)" class="text-muted">, {{ storeCounts(store) }}</span
                  ><span v-if="storeIndex(store)" class="text-muted">; {{ storeIndex(store) }}</span>
                </template>
              </li>
            </ul>
          </dd>
        </template>
        <dt class="col-sm-3">Recorded this run</dt>
        <dd class="col-sm-9">
          <span v-if="recorded.length === 0" class="text-muted">Nothing yet.</span>
          <ul v-else class="list-inline mb-0">
            <li v-for="[source, count] in recorded" :key="source" class="list-inline-item">
              <code>{{ source }}</code> {{ formatNumber(count) }}
            </li>
          </ul>
        </dd>
        <dt class="col-sm-3">Evicted</dt>
        <dd class="col-sm-9">
          {{ formatNumber(status.evictedEvents) }}
          <span v-if="boundLabel" class="text-muted">, oldest events leave as {{ boundLabel }}</span>
        </dd>
        <dt class="col-sm-3">Dropped</dt>
        <dd class="col-sm-9">
          <template v-if="status.droppedEvents === 0">None: every event was recorded.</template>
          <template v-else>
            <span class="text-warning-emphasis">{{ formatNumber(status.droppedEvents) }}</span
            >, because the queue was full:
            <ul class="list-inline mb-0 d-inline">
              <li v-for="[source, count] in dropped" :key="source" class="list-inline-item">
                <code>{{ source }}</code> {{ formatNumber(count) }}
              </li>
            </ul>
          </template>
        </dd>
        <dt class="col-sm-3">Previous runs</dt>
        <dd class="col-sm-9">
          <span v-if="status.previousRunsUnavailable" class="text-muted">{{ status.previousRunsUnavailable }}</span>
          <span v-else-if="previousRuns.length === 0" class="text-muted">
            None kept yet. When the application restarts in this JVM, as after a DevTools restart or a Quarkus live
            reload, the summary of this run is kept here, for up to 5 runs.
          </span>
          <ul v-else class="list-unstyled mb-0 runtime-journal-runs">
            <li v-for="run in previousRuns" :key="run.runId">
              Run {{ run.ordinal }} <code>{{ run.runId }}</code
              >, {{ formatClockTime(run.startedAt) }} to {{ formatClockTime(run.endedAt) }}:
              {{ counted(run.requests, 'request')
              }}<span v-if="run.failedRequests > 0" class="text-warning-emphasis"
                >, {{ formatNumber(run.failedRequests) }} failed</span
              >, {{ counted(run.events, 'event') }}
              <span class="text-muted"
                >({{ formatBytes(run.summaryBytes)
                }}<template v-if="run.omittedEntries > 0"
                  >, {{ counted(run.omittedEntries, 'least-used entry', 'least-used entries') }} left out</template
                >)</span
              >
            </li>
          </ul>
        </dd>
      </dl>
    </div>
  </section>
</template>
