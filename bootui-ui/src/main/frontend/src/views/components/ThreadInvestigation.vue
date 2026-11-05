<script setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiError, apiFetch, isPanelAccessError} from '../../api.js'
import {formatBytes, formatClockTime, formatNumber} from '../../utils/format.js'
import {formatLoadError, isAbortError} from '../../utils/loadError.js'
import {useAutoRefresh} from '../../utils/useAutoRefresh.js'
import {useConfirm} from '../../utils/useConfirm.js'
import {useLowFootprint} from '../../utils/useLowFootprint.js'
import PanelTabs from './PanelTabs.vue'
import PanelSkeleton from './PanelSkeleton.vue'
import ReadOnlyNotice from './ReadOnlyNotice.vue'

const props = defineProps({
  readOnly: Boolean,
  readOnlyReason: {type: String, default: ''},
  actionTarget: {type: Object, default: null},
  showBootUi: Boolean,
  liveHiddenBootUiThreads: {type: Number, default: 0}
})
const emit = defineEmits(['live-change', 'observation-active', 'update:showBootUi'])
const {confirm} = useConfirm()
const {status: footprint} = useLowFootprint()
const tabs = [
  {id: 'threads', label: 'Threads'},
  {id: 'contention', label: 'Contention'},
  {id: 'compare', label: 'Observe & compare'}
]
const tab = ref('threads')
const inventory = ref(null)
const selected = ref('')
const showingLive = computed(() => !selected.value && tab.value === 'threads')
const analysis = ref(null)
const rows = ref(null)
const detail = ref(null)
const lock = ref(null)
const detailHeading = ref(null)
const observation = ref(null)
const error = ref(null)
const busy = ref(false)
const loading = ref(false)
const filter = ref('')
const state = ref('')
const sort = ref('name')
const direction = ref('asc')
const offset = ref(0)
const analysisOffset = ref(0)
const comparisonOffset = ref(0)
const samples = ref(3)
const intervalMillis = ref(5000)
const intervalSeconds = computed({
  get: () => (Number.isNaN(intervalMillis.value) ? '' : intervalMillis.value / 1000),
  set: (value) => {
    intervalMillis.value = value === '' ? NaN : Math.round(value * 1000)
  }
})
const pollPaused = ref(false)
const capture = computed(() => analysis.value?.capture)
const hiddenBootUiThreads = computed(() =>
  tab.value === 'compare'
    ? (observation.value?.hiddenBootUiThreads ?? 0)
    : selected.value
      ? (rows.value?.hiddenBootUiThreads ?? 0)
      : props.liveHiddenBootUiThreads
)
const held = computed(() => footprint.value?.active === true || inventory.value?.lowFootprint === true)
const actionsDisabled = computed(
  () => !inventory.value || props.readOnly || busy.value || inventory.value.available === false
)
const activeObservation = computed(() => ['RUNNING', 'CANCEL_REQUESTED'].includes(observation.value?.status))
const maxObservationSamples = computed(() =>
  Math.min(inventory.value?.maxObservationSamples ?? 5, inventory.value?.captureLimit ?? 5)
)
const invalidObservationSettings = computed(
  () =>
    !Number.isInteger(samples.value) ||
    samples.value < 2 ||
    samples.value > maxObservationSamples.value ||
    !Number.isInteger(intervalMillis.value) ||
    intervalMillis.value < 1000 ||
    intervalMillis.value > 30000 ||
    intervalMillis.value * (samples.value - 1) + (inventory.value?.captureDeadlineMillis ?? 2000) > 30000
)
const states = ['NEW', 'RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'TERMINATED']
const controller = new AbortController()
let generation = 0
let detailGeneration = 0
let debounce = null
let pollStartedAt = 0
let disposed = false
let observationSettingsInitialized = false

async function request(path, options = {}) {
  const response = await apiFetch(`api/threads/${path}`, {...options, signal: controller.signal})
  if (!response.ok) {
    const error = await apiError(response)
    if (!isPanelAccessError(error)) error.message = error.body?.error || error.body?.message || error.message
    throw error
  }
  return response
}

async function loadInventory() {
  const response = await request('captures')
  const result = await response.json()
  if (disposed) return
  inventory.value = result
  if (!observationSettingsInitialized) {
    samples.value = result.observationSamples
    intervalMillis.value = result.observationIntervalMillis
    observationSettingsInitialized = true
  }
  if (!observation.value && result.observationId) {
    const includeBootUi = props.showBootUi
    const restored = await (
      await request(`observations/${encodeURIComponent(result.observationId)}?limit=25&includeBootUi=${includeBootUi}`)
    ).json()
    if (disposed || observation.value || props.showBootUi !== includeBootUi) return
    observation.value = restored
    pollStartedAt = Date.now()
    pollPaused.value = false
  }
}

async function loadCapture() {
  const id = selected.value
  const version = ++generation
  if (!id) {
    loading.value = false
    return
  }
  loading.value = true
  error.value = null
  const query = new URLSearchParams({
    q: filter.value,
    state: state.value,
    sort: sort.value,
    direction: direction.value,
    offset: String(offset.value),
    limit: '25',
    includeBootUi: String(props.showBootUi)
  })
  try {
    const [reportResponse, rowsResponse] = await Promise.all([
      request(`captures/${encodeURIComponent(id)}?offset=${analysisOffset.value}&limit=25`),
      request(`captures/${encodeURIComponent(id)}/threads?${query}`)
    ])
    const [report, page] = await Promise.all([reportResponse.json(), rowsResponse.json()])
    if (version !== generation || disposed) return
    analysis.value = report
    rows.value = page
  } catch (failure) {
    if (version === generation && !disposed && !isAbortError(failure))
      error.value = formatLoadError(failure, 'Could not read capture')
  } finally {
    if (version === generation && !disposed) loading.value = false
  }
}

async function runAction(title, message, label, action) {
  if (busy.value || props.readOnly) return
  busy.value = true
  error.value = null
  try {
    const approved = await confirm({title, message, confirmLabel: label})
    if (!approved || disposed || props.readOnly) return
    await action()
  } catch (failure) {
    if (!disposed && !isAbortError(failure)) error.value = formatLoadError(failure, 'Thread investigation')
  } finally {
    if (!disposed) busy.value = false
  }
}

function captureSnapshot() {
  if (actionsDisabled.value || activeObservation.value) return
  return runAction(
    'Capture thread snapshot?',
    'Collect bounded platform-thread stacks and retain masked evidence temporarily. JVM collection can briefly pause the application.',
    'Capture',
    async () => {
      const result = await (await request('captures', {method: 'POST'})).json()
      await loadInventory()
      if (disposed) return
      selected.value = result.id
      tab.value = 'contention'
    }
  )
}

function startObservation() {
  if (actionsDisabled.value || activeObservation.value || invalidObservationSettings.value) return
  return runAction(
    'Observe threads?',
    `Collect ${samples.value} bounded snapshots, ${intervalMillis.value / 1000} seconds apart. Each JVM collection can briefly pause the application. This is a finite investigation, not continuous monitoring.`,
    'Observe',
    async () => {
      observation.value = await (
        await request(`observations?includeBootUi=${props.showBootUi}`, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({samples: samples.value, intervalMillis: intervalMillis.value})
        })
      ).json()
      pollStartedAt = Date.now()
      pollPaused.value = false
      comparisonOffset.value = 0
      tab.value = 'compare'
    }
  )
}

function clearCaptures() {
  return runAction(
    'Clear retained captures?',
    'Discard retained thread evidence. An active observation can still collect remaining samples; cancel it first to stop collection.',
    'Clear',
    async () => {
      await request('captures/clear', {method: 'POST'})
      selected.value = ''
      analysis.value = null
      rows.value = null
      closeEvidence()
      await loadInventory()
      if (observation.value) await refreshObservation()
    }
  )
}

function cancelObservation() {
  if (!observation.value) return
  return runAction(
    'Cancel thread observation?',
    'Stop future samples and discard any in-flight result. A JVM management call already running cannot be interrupted.',
    'Cancel observation',
    async () => {
      observation.value = await (
        await request(
          `observations/${encodeURIComponent(observation.value.id)}/cancel?includeBootUi=${props.showBootUi}`,
          {method: 'POST'}
        )
      ).json()
      await loadInventory()
    }
  )
}

function exportCapture() {
  if (!selected.value || actionsDisabled.value) return
  const id = selected.value
  return runAction(
    'Export this capture?',
    'Download the retained, masked evidence in BootUI JSON format. This does not acquire fresh JVM stacks and is not a native JVM thread dump.',
    'Export',
    async () => {
      const response = await request(`captures/${encodeURIComponent(id)}/export`, {method: 'POST'})
      const url = URL.createObjectURL(await response.blob())
      const anchor = document.createElement('a')
      try {
        anchor.href = url
        anchor.download = `bootui-thread-capture-${id}.json`
        document.body.appendChild(anchor)
        anchor.click()
      } finally {
        anchor.remove()
        URL.revokeObjectURL(url)
      }
    }
  )
}

async function refreshObservation() {
  if (!observation.value || disposed) return
  const id = observation.value.id
  const includeBootUi = props.showBootUi
  const requestedOffset = comparisonOffset.value
  try {
    const result = await (
      await request(
        `observations/${encodeURIComponent(id)}?offset=${requestedOffset}&limit=25&includeBootUi=${includeBootUi}`
      )
    ).json()
    if (
      disposed ||
      observation.value?.id !== id ||
      props.showBootUi !== includeBootUi ||
      comparisonOffset.value !== requestedOffset
    )
      return
    const changed =
      result.completedSamples !== observation.value.completedSamples || result.status !== observation.value.status
    observation.value = result
    if (changed) await loadInventory()
    if (activeObservation.value && Date.now() - pollStartedAt > 45_000) {
      pollPaused.value = true
      error.value =
        'Automatic status checks paused. Refresh observation status to check the server; no additional collection has been requested.'
    }
  } catch (failure) {
    if (
      !disposed &&
      !isAbortError(failure) &&
      observation.value?.id === id &&
      props.showBootUi === includeBootUi &&
      comparisonOffset.value === requestedOffset
    ) {
      pollPaused.value = true
      error.value = formatLoadError(failure, 'Could not read observation; refresh status to retry')
    }
  }
}

useAutoRefresh(refreshObservation, {
  enabled: computed(() => activeObservation.value && !pollPaused.value),
  intervalMs: 1000,
  initialLoading: false
})

async function inspectThread(id) {
  const version = ++detailGeneration
  detail.value = null
  lock.value = null
  const captureId = id.split(':')[0]
  try {
    const result = await (
      await request(`captures/${encodeURIComponent(captureId)}/threads/${encodeURIComponent(id)}`)
    ).json()
    if (version !== detailGeneration || disposed) return
    detail.value = result
    await nextTick()
    detailHeading.value?.focus()
  } catch (failure) {
    if (version === detailGeneration && !disposed && !isAbortError(failure))
      error.value = formatLoadError(failure, 'Could not read thread')
  }
}

async function inspectLock() {
  const thread = detail.value
  if (!thread) return
  const version = detailGeneration
  const captureId = thread.observationId.split(':')[0]
  try {
    const result = await (
      await request(
        `captures/${encodeURIComponent(captureId)}/locks/${encodeURIComponent(thread.observationId)}?limit=25`
      )
    ).json()
    if (!disposed && version === detailGeneration && detail.value?.observationId === thread.observationId)
      lock.value = result
  } catch (failure) {
    if (!disposed && version === detailGeneration && !isAbortError(failure))
      error.value = formatLoadError(failure, 'Could not read lock evidence')
  }
}

function pageThreads(delta) {
  offset.value = Math.max(0, offset.value + delta * 25)
  loadCapture()
}

function pageAnalysis(delta) {
  analysisOffset.value = Math.max(0, analysisOffset.value + delta * 25)
  loadCapture()
}

function pageComparisons(delta) {
  comparisonOffset.value = Math.max(0, comparisonOffset.value + delta * 25)
  refreshObservation()
}

function closeEvidence() {
  detailGeneration++
  detail.value = null
  lock.value = null
}

watch(showingLive, (live) => emit('live-change', live), {immediate: true})
watch(activeObservation, (active) => emit('observation-active', active), {immediate: true})
watch(
  () => props.showBootUi,
  () => {
    offset.value = 0
    comparisonOffset.value = 0
    closeEvidence()
    loadCapture()
    if (observation.value) refreshObservation()
    else if (inventory.value?.observationId) reload()
  }
)
watch(selected, () => {
  generation++
  offset.value = 0
  analysisOffset.value = 0
  analysis.value = null
  rows.value = null
  closeEvidence()
  loadCapture()
})
watch([filter, state, sort, direction], () => {
  clearTimeout(debounce)
  offset.value = 0
  debounce = setTimeout(loadCapture, 250)
})
async function reload({discard = false} = {}) {
  error.value = null
  if (discard) {
    generation++
    selected.value = ''
    analysis.value = null
    rows.value = null
    closeEvidence()
    observation.value = null
  }
  try {
    await loadInventory()
    if (disposed) return
    if (selected.value && !inventory.value.captures.some((item) => item.id === selected.value)) selected.value = ''
  } catch (failure) {
    if (!disposed && !isAbortError(failure)) error.value = formatLoadError(failure, 'Could not list captures')
  }
}
defineExpose({reload})
onMounted(reload)
onBeforeUnmount(() => {
  disposed = true
  generation++
  detailGeneration++
  clearTimeout(debounce)
  controller.abort()
})
</script>

<template>
  <div>
    <Teleport :to="actionTarget ?? 'body'" :disabled="!actionTarget">
      <button
        class="btn btn-outline-primary"
        type="button"
        :disabled="actionsDisabled || activeObservation || invalidObservationSettings"
        @click="startObservation"
      >
        Observe threads
      </button>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="actionsDisabled || activeObservation"
        @click="captureSnapshot"
      >
        Capture snapshot
      </button>
    </Teleport>
    <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason"
      >Thread capture, observation, export, clearing, and dump download are read-only.</ReadOnlyNotice
    >
    <div v-if="held" class="alert alert-secondary">
      Low-footprint mode is on. Background recording stays paused; you can still capture a snapshot or run a finite
      observation. New captures use the usual memory limits and expiry.
    </div>
    <div v-if="error" class="alert alert-danger" role="alert">
      {{ error }}
      <button v-if="!inventory" class="btn btn-outline-danger btn-sm ms-2" type="button" @click="reload()">
        Retry loading captures
      </button>
    </div>
    <div v-if="tab !== 'compare'" class="row g-2 align-items-end mb-3">
      <div class="col-md">
        <label class="form-label" for="thread-capture-selection">Thread source</label>
        <select id="thread-capture-selection" v-model="selected" class="form-select" @change="tab = 'threads'">
          <option value="">Live</option>
          <option v-if="selected && !inventory?.captures?.some((item) => item.id === selected)" :value="selected">
            Capture no longer retained
          </option>
          <option v-for="item in inventory?.captures || []" :key="item.id" :value="item.id">
            {{ formatClockTime(item.capturedAt) }} · {{ item.quality.capturedThreads }} threads ·
            {{ item.id.slice(0, 8) }}
          </option>
        </select>
      </div>
      <div class="col-md-auto d-flex flex-wrap gap-2">
        <button
          class="btn btn-outline-secondary btn-sm"
          type="button"
          :disabled="actionsDisabled || !selected"
          @click="exportCapture"
        >
          Export capture
        </button>
        <button
          class="btn btn-outline-danger btn-sm"
          type="button"
          :disabled="readOnly || busy || !inventory?.captures?.length"
          @click="clearCaptures"
        >
          Clear captures
        </button>
      </div>
    </div>
    <PanelTabs
      :tabs="tabs"
      :selected="tab"
      label="Thread views"
      id-prefix="thread-investigation"
      class="mb-3"
      @select="tab = $event"
    />
    <div v-if="tab !== 'compare' || observation" class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <div class="form-check form-switch mb-0 thread-visibility-switch">
        <input
          id="thread-show-bootui"
          class="form-check-input"
          type="checkbox"
          role="switch"
          :checked="showBootUi"
          :disabled="busy || tab === 'contention' || (activeObservation && showingLive)"
          aria-describedby="thread-bootui-visibility"
          @change="emit('update:showBootUi', !showBootUi)"
        />
        <label class="form-check-label" for="thread-show-bootui">Show BootUI threads</label>
      </div>
      <span id="thread-bootui-visibility" class="small text-muted">
        <template v-if="tab === 'contention'">Contention always includes all threads.</template>
        <template v-else-if="!showBootUi"
          >{{ formatNumber(hiddenBootUiThreads) }} BootUI
          {{ hiddenBootUiThreads === 1 ? 'thread' : 'threads' }} hidden.</template
        >
        <template v-else>BootUI threads included.</template>
      </span>
    </div>
    <details v-if="tab !== 'compare'" class="mb-3">
      <summary>Capture limits</summary>
      <p v-if="inventory" class="small text-muted mt-2 mb-0">
        {{ inventory.captures.length }} / {{ inventory.captureLimit }} captures ·
        {{ formatBytes(inventory.retainedBytes) }} estimated retained /
        {{ formatBytes(inventory.retainedByteLimit) }} budget. Captures contain platform threads only.
      </p>
    </details>
    <div v-if="capture && tab !== 'compare'" class="small mb-3">
      Captured {{ formatClockTime(capture.capturedAt) }} · expires {{ formatClockTime(capture.expiresAt) }} ·
      {{ capture.quality.capturedThreads }} captured / {{ capture.quality.enumeratedThreads }} enumerated ·
      {{ capture.durationMillis }} ms collection.
      <p class="text-muted mb-0">{{ capture.quality.limitations.join(' ') }}</p>
    </div>
    <PanelSkeleton v-if="tab !== 'compare' && loading && !analysis" :rows="5" />

    <section
      v-show="tab === 'threads'"
      id="thread-investigation-panel-threads"
      role="tabpanel"
      aria-labelledby="thread-investigation-tab-threads"
      tabindex="0"
    >
      <slot v-if="!selected" name="live">
        <p class="text-muted">Live thread snapshots appear here. Capture a snapshot to retain stable evidence.</p>
      </slot>
      <template v-else>
        <div class="row g-2 mb-3">
          <div class="col-md-5">
            <input
              v-model="filter"
              class="form-control"
              aria-label="Filter captured threads"
              placeholder="Name, family, or visible stack frame"
            />
          </div>
          <div class="col-md-3">
            <select v-model="state" class="form-select" aria-label="Captured thread state">
              <option value="">All states</option>
              <option v-for="item in states" :key="item">{{ item }}</option>
            </select>
          </div>
          <div class="col-md-2">
            <select v-model="sort" class="form-select" aria-label="Sort captured threads">
              <option value="name">Name</option>
              <option value="id">ID</option>
              <option value="state">State</option>
              <option value="cpu">CPU total</option>
            </select>
          </div>
          <div class="col-md-2">
            <select v-model="direction" class="form-select" aria-label="Sort direction">
              <option value="asc">Ascending</option>
              <option value="desc">Descending</option>
            </select>
          </div>
        </div>
        <div v-if="rows" class="table-responsive">
          <table class="table table-sm table-hover">
            <thead>
              <tr>
                <th>Thread</th>
                <th>State / activity</th>
                <th>Family</th>
                <th>CPU total</th>
                <th>Evidence</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows.threads" :key="row.observationId">
                <td>
                  <button
                    class="btn btn-outline-secondary btn-sm"
                    type="button"
                    @click="inspectThread(row.observationId)"
                  >
                    {{ row.thread.name }}
                  </button>
                  <div class="small text-muted">ID {{ row.thread.id }}</div>
                </td>
                <td>
                  {{ row.thread.state }}
                  <div class="small text-muted">{{ row.activity }}</div>
                </td>
                <td>
                  <code>{{ row.family }}</code>
                </td>
                <td>
                  {{
                    row.thread.cpuTimeMillis == null ? 'Unavailable' : `${formatNumber(row.thread.cpuTimeMillis)} ms`
                  }}
                </td>
                <td>
                  {{ row.stackTruncated ? 'Stack truncated' : 'Visible stack' }}
                  <div v-if="!row.ownershipComplete" class="small text-muted">Partial ownership</div>
                </td>
              </tr>
              <tr v-if="!rows.threads.length">
                <td colspan="5" class="text-muted">No captured threads match these filters.</td>
              </tr>
            </tbody>
          </table>
          <nav class="d-flex gap-2 align-items-center" aria-label="Captured thread pages">
            <button
              class="btn btn-outline-secondary btn-sm"
              type="button"
              :disabled="loading || !offset"
              @click="pageThreads(-1)"
            >
              Previous threads
            </button>
            <span class="small">{{ rows.page.matched }} matching · {{ rows.page.returned }} shown</span>
            <button
              class="btn btn-outline-secondary btn-sm"
              type="button"
              :disabled="loading || !rows.page.hasMore"
              @click="pageThreads(1)"
            >
              Next threads
            </button>
          </nav>
        </div>
      </template>
    </section>

    <section
      v-show="tab === 'contention'"
      id="thread-investigation-panel-contention"
      role="tabpanel"
      aria-labelledby="thread-investigation-tab-contention"
      tabindex="0"
    >
      <p v-if="!analysis" class="text-muted">
        Capture a snapshot or select a retained capture to inspect contention. Opening this tab never starts collection.
      </p>
      <template v-else>
        <div v-if="capture.deadlockDetected" class="alert alert-danger">
          Native JVM deadlock detection reported thread IDs {{ capture.deadlockedThreadIds.join(', ') }}. This check and
          the captured stacks are separate observations.
        </div>
        <h3 class="h6">Root blockers</h3>
        <p class="small text-muted">
          Impact covers the captured population, not just this page or the current thread filter. Missing owners and
          incomplete coverage limit conclusions.
        </p>
        <div class="table-responsive">
          <table class="table table-sm">
            <thead>
              <tr>
                <th>Owner</th>
                <th>Directly blocked</th>
                <th>Total downstream</th>
                <th>Maximum depth</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="root in analysis.rootBlockers" :key="root.observationId">
                <td>
                  <button
                    class="btn btn-outline-secondary btn-sm"
                    type="button"
                    @click="inspectThread(root.observationId)"
                  >
                    {{ root.name }}
                  </button>
                  <div v-if="root.partial" class="small text-muted">Partial evidence</div>
                  <details v-if="root.blockedObservationIds.length" class="small mt-2">
                    <summary>Downstream thread evidence</summary>
                    <div class="d-flex flex-wrap gap-1 mt-2">
                      <button
                        v-for="id in root.blockedObservationIds"
                        :key="id"
                        class="btn btn-outline-secondary btn-sm"
                        type="button"
                        @click="inspectThread(id)"
                      >
                        Inspect downstream #{{ id.split(':')[1] }}
                      </button>
                    </div>
                    <p v-if="root.blockedListTruncated" class="text-muted mb-0">
                      Evidence IDs are truncated; impact counts cover the full captured graph.
                    </p>
                  </details>
                </td>
                <td>{{ root.directBlocked }}</td>
                <td>{{ root.transitiveBlocked }}</td>
                <td>{{ root.maxDepth }}</td>
              </tr>
              <tr v-if="!analysis.rootBlockers.length">
                <td colspan="4" class="text-muted">
                  No supported root blockers on this page. This is not proof that the application is contention-free.
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <h3 class="h6 mt-4">Derived wait cycles</h3>
        <div v-for="(cycle, index) in analysis.cycles" :key="index" class="small">
          {{ cycle.participantCount }} participants · {{ cycle.affectedCount }} incoming affected threads.
          Supported-wait cycle, not an independent native-deadlock claim.
          <button
            v-for="id in cycle.participantIds"
            :key="id"
            class="btn btn-outline-secondary btn-sm"
            type="button"
            @click="inspectThread(id)"
          >
            Inspect #{{ id.split(':')[1] }}
          </button>
          <span v-if="cycle.participantListTruncated">Participant evidence list is truncated.</span>
          <details v-if="cycle.affectedObservationIds.length" class="mt-2">
            <summary>Affected incoming threads</summary>
            <button
              v-for="id in cycle.affectedObservationIds"
              :key="id"
              class="btn btn-outline-secondary btn-sm me-1"
              type="button"
              @click="inspectThread(id)"
            >
              Inspect affected #{{ id.split(':')[1] }}
            </button>
            <span v-if="cycle.affectedListTruncated">Affected-thread evidence list is truncated.</span>
          </details>
        </div>
        <p v-if="!analysis.cycles.length" class="small text-muted">No supported wait cycles on this page.</p>
        <h3 class="h6 mt-4">Unresolved and non-exclusive waits</h3>
        <p v-for="wait in analysis.unresolvedWaits" :key="wait.waiterId" class="small">
          <button class="btn btn-outline-secondary btn-sm" type="button" @click="inspectThread(wait.waiterId)">
            Thread #{{ wait.waiterId.split(':')[1] }}
          </button>
          · {{ wait.kind }} · {{ wait.explanation }}
        </p>
        <p v-if="!analysis.unresolvedWaits.length" class="small text-muted">No unresolved waits on this page.</p>
        <h3 class="h6 mt-4">Exact visible-stack groups</h3>
        <p class="small text-muted">
          Identical visible stacks are a navigation aid, not proof of a fault; states and lock context can differ.
        </p>
        <div v-for="group in analysis.stackGroups" :key="group.id" class="small mb-2">
          <button class="btn btn-outline-secondary btn-sm" type="button" @click="inspectThread(group.representativeId)">
            Inspect {{ group.count }} matching threads
          </button>
          · {{ group.completeStacks ? 'complete captured stacks' : 'truncated visible stacks' }} ·
          {{ group.states.map((item) => `${item.state}: ${item.count}`).join(', ') }}
          <span v-if="group.memberListTruncated"> · Member list truncated</span>
          <details v-if="group.memberIds.length > 1" class="mt-1">
            <summary>Group members</summary>
            <div class="d-flex flex-wrap gap-1 mt-2">
              <button
                v-for="id in group.memberIds"
                :key="id"
                class="btn btn-outline-secondary btn-sm"
                type="button"
                @click="inspectThread(id)"
              >
                Inspect member #{{ id.split(':')[1] }}
              </button>
            </div>
          </details>
        </div>
        <nav class="d-flex gap-2 mt-3" aria-label="Contention evidence pages">
          <button
            class="btn btn-outline-secondary btn-sm"
            type="button"
            :disabled="loading || !analysisOffset"
            @click="pageAnalysis(-1)"
          >
            Previous evidence
          </button>
          <button
            class="btn btn-outline-secondary btn-sm"
            type="button"
            :disabled="
              loading ||
              ![analysis.blockerPage, analysis.cyclePage, analysis.unresolvedPage, analysis.groupPage].some(
                (page) => page.hasMore
              )
            "
            @click="pageAnalysis(1)"
          >
            Next evidence
          </button>
        </nav>
      </template>
    </section>

    <section
      v-show="tab === 'compare'"
      id="thread-investigation-panel-compare"
      role="tabpanel"
      aria-labelledby="thread-investigation-tab-compare"
      tabindex="0"
    >
      <div class="thread-observation-setup">
        <h2 class="h5">Compare threads over time</h2>
        <p v-if="!observation" class="text-muted">
          Use <strong>Observe threads</strong> above to collect a sequence of snapshots, then compare stacks, state
          changes, and interval CPU.
        </p>
        <h3 v-else class="h6 mt-3">Next observation settings</h3>
        <div class="row g-3 thread-observation-settings my-2">
          <div class="col-6">
            <label for="thread-observation-samples" class="form-label">Samples</label>
            <input
              id="thread-observation-samples"
              v-model.number="samples"
              class="form-control"
              type="number"
              min="2"
              :max="maxObservationSamples"
              :disabled="busy || activeObservation || readOnly"
            />
          </div>
          <div class="col-6">
            <label for="thread-observation-interval" class="form-label">Interval (seconds)</label>
            <input
              id="thread-observation-interval"
              v-model.number="intervalSeconds"
              class="form-control"
              type="number"
              min="1"
              max="30"
              step="0.001"
              :disabled="busy || activeObservation || readOnly"
            />
          </div>
        </div>
        <p class="small text-muted mt-3 mb-4">
          2–{{ maxObservationSamples }} samples within 30 seconds, limited by this application's capture budget.
          Collection starts only after confirmation and stops automatically.
        </p>
        <p v-if="activeObservation" class="small text-muted">
          Live refresh, snapshots, and downloads are paused here while observing. Other clients still share JVM
          acquisition; an overlapping or missed sample ends this observation rather than retrying.
        </p>
        <p v-if="invalidObservationSettings" class="text-danger" role="alert">
          Choose 2–{{ maxObservationSamples }} samples at least one second apart, leaving time for the final capture
          within 30 seconds.
        </p>
      </div>
      <div v-if="!observation" class="thread-observation-empty border-top pt-3">
        <p class="mb-1 fw-semibold">Observation results will appear here</p>
        <p class="small text-muted mb-0">
          Single captures belong to Threads and Contention. They are not compared here. Opening this tab never starts
          collection.
        </p>
      </div>
      <template v-else>
        <p role="status">
          {{ observation.status }} · {{ observation.completedSamples }} / {{ observation.requestedSamples }} samples ·
          {{ observation.clearedSamples }} cleared. {{ observation.message }}
        </p>
        <div class="d-flex flex-wrap gap-2 mb-3">
          <button class="btn btn-outline-secondary btn-sm" type="button" @click="refreshObservation">
            Refresh observation status
          </button>
          <button
            v-if="activeObservation"
            class="btn btn-outline-danger btn-sm"
            type="button"
            :disabled="readOnly || busy"
            @click="cancelObservation"
          >
            Cancel observation
          </button>
          <button
            class="btn btn-outline-danger btn-sm"
            type="button"
            :disabled="readOnly || busy || !inventory?.captures?.length"
            @click="clearCaptures"
          >
            Clear captures
          </button>
        </div>
        <p class="small text-muted">
          This comparison uses the observation's samples, not the selected thread source. Repeated sampled stacks do not
          establish continuous inactivity. CPU percentages describe measured adjacent samples on one core; missing/reset
          counters remain unavailable. Rows prioritize sampled acquisition waits, then interval CPU.
        </p>
        <div class="table-responsive">
          <table class="table table-sm">
            <thead>
              <tr>
                <th>Thread</th>
                <th>Observed samples</th>
                <th>Matching stack occurrences</th>
                <th>Consecutive streak</th>
                <th>State changes</th>
                <th>Interval CPU</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in observation.threads" :key="row.observationId">
                <td>
                  <button
                    class="btn btn-outline-secondary btn-sm"
                    type="button"
                    @click="inspectThread(row.observationId)"
                  >
                    {{ row.name }}
                  </button>
                </td>
                <td>{{ row.observedSamples }}</td>
                <td>{{ row.matchingStackOccurrences }}</td>
                <td>{{ row.consecutiveStackStreak }}</td>
                <td>{{ row.stateChanges }}</td>
                <td>
                  {{
                    row.singleCoreCpuPercent == null
                      ? row.cpuUnavailableReason
                      : `${row.singleCoreCpuPercent.toFixed(1)}% of one core`
                  }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav class="d-flex gap-2" aria-label="Thread comparison pages">
          <button
            class="btn btn-outline-secondary btn-sm"
            type="button"
            :disabled="!comparisonOffset"
            @click="pageComparisons(-1)"
          >
            Previous comparisons
          </button>
          <button
            class="btn btn-outline-secondary btn-sm"
            type="button"
            :disabled="!observation.page.hasMore"
            @click="pageComparisons(1)"
          >
            Next comparisons
          </button>
        </nav>
      </template>
    </section>

    <section v-if="detail" class="border-top mt-4 pt-3" aria-label="Thread evidence">
      <div class="d-flex flex-wrap justify-content-between gap-2">
        <h3 ref="detailHeading" class="h6" tabindex="-1">{{ detail.thread.name }} · ID {{ detail.thread.id }}</h3>
        <button class="btn btn-outline-secondary btn-sm" type="button" @click="closeEvidence">
          Close thread evidence
        </button>
      </div>
      <p class="small">
        {{ detail.thread.state }} · {{ detail.activity }} · family <code>{{ detail.family }}</code
        >. {{ detail.stackTruncated ? 'Stack depth limited.' : '' }}
        {{ detail.ownershipComplete ? '' : 'Owned-lock inventory is partial.' }}
      </p>
      <p v-if="detail.thread.lockName" class="small">
        Waiting on
        <button class="btn btn-outline-secondary btn-sm" type="button" @click="inspectLock">
          {{ detail.thread.lockName }}
        </button>
        <button
          v-if="detail.thread.lockOwnerId != null"
          class="btn btn-outline-secondary btn-sm"
          type="button"
          @click="inspectThread(`${detail.observationId.split(':')[0]}:${detail.thread.lockOwnerId}`)"
        >
          Inspect owner #{{ detail.thread.lockOwnerId }}
        </button>
      </p>
      <pre
        v-if="detail.thread.stackTrace.length"
        class="thread-evidence-stack"
      ><code>{{ detail.thread.stackTrace.join('\n') }}</code></pre>
      <p v-else class="small text-muted">No visible stack frames were retained under this capture's exposure policy.</p>
      <ul v-if="detail.ownedLocks.length" class="small">
        <li v-for="(owned, index) in detail.ownedLocks" :key="index">
          {{ owned.kind }} · <code>{{ owned.identity }}</code>
          <template v-if="owned.acquisitionDepth != null"> · acquisition depth {{ owned.acquisitionDepth }}</template>
        </li>
      </ul>
      <div v-if="lock" class="small">
        <h4 class="h6">Captured lock evidence</h4>
        <p>{{ lock.limitations.join(' ') }}</p>
        <button
          v-for="wait in lock.waiters"
          :key="wait.waiterId"
          class="btn btn-outline-secondary btn-sm"
          type="button"
          @click="inspectThread(wait.waiterId)"
        >
          Inspect waiter #{{ wait.waiterId.split(':')[1] }}
        </button>
        <p v-if="lock.page.hasMore">Additional matching waiters are not shown in this bounded page.</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.thread-observation-setup,
.thread-observation-empty {
  max-width: 55rem;
}

.thread-observation-setup {
  padding: 1.15rem 0 1.6rem;
}

.thread-observation-setup h2 {
  font-size: 1.15rem;
  font-weight: 700;
}

.thread-observation-setup p {
  max-width: 72ch;
  line-height: 1.6;
}

.thread-observation-settings {
  max-width: 34rem;
}

.thread-evidence-stack {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 24rem;
  overflow: auto;
  font-size: 0.85rem;
}

@media (max-width: 575.98px) {
  .thread-observation-settings .form-control {
    min-block-size: 44px;
  }

  .thread-visibility-switch {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    padding-inline-start: 0;
  }

  .thread-visibility-switch .form-check-input {
    min-block-size: 1rem;
    min-inline-size: 2em;
    margin: 0;
    flex-shrink: 0;
  }

  .thread-visibility-switch .form-check-label {
    display: inline-flex;
    align-items: center;
    min-block-size: 44px;
  }
}
</style>
