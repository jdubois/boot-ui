<script setup>
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiFetch, getJson} from '../../api.js'
import {formatClockTime, formatNumber, shortName} from '../../utils/format.js'
import {formatLoadError} from '../../utils/loadError.js'
import {useConfirm} from '../../utils/useConfirm.js'
import {useFlashMessage} from '../../utils/useFlashMessage.js'
import FlashBanner from './FlashBanner.vue'
import ReadOnlyNotice from './ReadOnlyNotice.vue'

// Method probes (docs/PLAN-v2.md §5.14, M5-8): Code Paths' only actions. Starting one is user-triggered and confirmed;
// the list polls only while a probe is starting, active, or ending, and stops polling when the panel unmounts.
const props = defineProps({
  // The method key a Probe this method button offers, or null.
  method: {type: String, default: null},
  readOnly: {type: Boolean, default: false},
  readOnlyReason: {type: String, default: ''}
})

const POLL_MILLIS = 1000
const LIVE_STATES = new Set(['starting', 'active', 'ending'])

const {confirm} = useConfirm()
const {message: banner, flash, show, clear} = useFlashMessage(6000)
const report = ref(null)
const loadError = ref(null)
const busy = ref(false)
const expanded = ref(new Set())
// Opt-in per probe (D44): argument and return shapes, never values; off by default.
const recordShapes = ref(false)
let timer = null
let unmounted = false

const probes = computed(() => report.value?.probes ?? [])
const live = computed(() => probes.value.some((probe) => LIVE_STATES.has(probe.state)))
const liveCount = computed(() => probes.value.filter((probe) => LIVE_STATES.has(probe.state)).length)
const probing = computed(() =>
  props.method
    ? probes.value.find(
        (probe) => LIVE_STATES.has(probe.state) && (probe.method === props.method || matches(probe, props.method))
      )
    : null
)
const shapesAvailable = computed(() => report.value?.shapesAvailable === true)
const shapesWhy = computed(() =>
  report.value?.available && !shapesAvailable.value ? (report.value?.shapesUnavailableReason ?? null) : null
)
const canStart = computed(
  () =>
    report.value?.available === true &&
    !props.readOnly &&
    !busy.value &&
    !probing.value &&
    liveCount.value < (report.value?.maxActive ?? 5)
)

/** A probe started without a descriptor matches the method key it resolved to. */
function matches(probe, key) {
  const hash = key.indexOf('#')
  const paren = key.indexOf('(', hash)
  const bare = paren < 0 ? key : key.slice(0, paren)
  return probe.className + '#' + probe.methodName === bare
}

async function load() {
  try {
    report.value = await getJson('api/code-paths/probes')
    loadError.value = null
  } catch (e) {
    loadError.value = formatLoadError(e, 'Unable to load method probes')
  }
  schedule()
}

function schedule() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
  if (live.value && !unmounted) {
    timer = setTimeout(load, POLL_MILLIS)
  }
}

async function start() {
  if (props.readOnly) {
    flash(props.readOnlyReason, 'warning')
    return
  }
  const bounds = report.value
  const shapes = recordShapes.value && shapesAvailable.value
  if (
    !(await confirm({
      title: 'Probe this method?',
      message:
        `BootUI's agent retransforms this one method of the running application and records its next ` +
        `${bounds?.maxInvocations ?? 20} invocations, for at most ${bounds?.windowSeconds ?? 60} seconds: ` +
        (shapes
          ? 'durations, thread kinds, request ids, outcomes, calling frames, and the shapes of the arguments and the ' +
            'return value (types, null or not, and sizes, read without running application code), never argument or ' +
            'return values.'
          : 'durations, thread kinds, request ids, outcomes, and calling frames, never argument or return values.'),
      resource: props.method,
      confirmLabel: 'Start probe'
    }))
  )
    return
  busy.value = true
  clear()
  try {
    const res = await apiFetch('api/code-paths/probes', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(shapes ? {method: props.method, recordShapes: true} : {method: props.method})
    })
    const body = await res.json().catch(() => null)
    if (!res.ok) {
      show(body?.error ?? `Could not start the probe (HTTP ${res.status}).`, 'danger')
    } else {
      expanded.value = new Set([...expanded.value, body.id])
      flash(`Probe ${body.id} started: call the method now.`, 'success')
    }
  } catch (e) {
    show(formatLoadError(e, 'Could not start the probe'), 'danger')
  } finally {
    busy.value = false
    await load()
  }
}

async function stop(probe) {
  if (props.readOnly) {
    flash(props.readOnlyReason, 'warning')
    return
  }
  try {
    const res = await apiFetch(`api/code-paths/probes/${encodeURIComponent(probe.id)}/stop`, {method: 'POST'})
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      show(body?.error ?? `Could not stop probe ${probe.id} (HTTP ${res.status}).`, 'danger')
    }
  } catch (e) {
    show(formatLoadError(e, 'Could not stop the probe'), 'danger')
  }
  await load()
}

function toggle(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function methodLabel(probe) {
  return `${shortName(probe.className)}.${probe.methodName}`
}

function stateLabel(probe) {
  if (probe.state === 'active' && probe.waitingForClass) return 'waiting for its class'
  return probe.state
}

function stateClass(probe) {
  return (
    {
      starting: 'text-bg-secondary',
      active: 'text-bg-info',
      ending: 'text-bg-secondary',
      ended: 'text-bg-light border',
      failed: 'text-bg-danger'
    }[probe.state] ?? 'text-bg-secondary'
  )
}

const END_REASONS = {
  invocations: 'recorded its invocations',
  window: 'its window ended',
  stopped: 'stopped',
  'run-ended': 'the run ended'
}

function endLabel(probe) {
  if (probe.failure) return probe.failure
  return END_REASONS[probe.endReason] ?? probe.endReason ?? ''
}

/** Whether a probe's removal is worth a warning: it failed, or the agent never confirmed it. */
function removalConcern(probe) {
  return /^(failed|unknown)/.test(probe.removal ?? '')
}

function duration(hit) {
  const micros = hit.durationMicros
  if (micros < 1000) return `${formatNumber(Math.round(micros))} µs`
  return `${formatNumber(Math.round(micros / 100) / 10)} ms`
}

/** Whether any shape of a probe's hits has a detail the exposure withholds. */
function withholds(probe) {
  return probe.hits.some((hit) => [...(hit.arguments ?? []), hit.returned].some((shape) => shape?.withheld))
}

/** A shape's tooltip, also read out to screen readers, when it says more than its label. */
function shapeNote(shape) {
  const title = shapeTitle(shape)
  return title && title !== (shape.type ?? shape.declaredType) ? title : ''
}

/** Whether a probe's hits carry shapes this read shows. */
function showsShapes(probe) {
  return probe.recordShapes && !probe.shapesHiddenReason
}

const WITHHELD = 'shown with bootui.expose-values=FULL'

/** A shape as one short phrase: its type, and its size, length, presence, or constant when shown. */
function shapeLabel(shape) {
  if (!shape) return ''
  const type = shortName(shape.type ?? shape.declaredType)
  switch (shape.kind) {
    case 'null':
      return 'null'
    case 'primitive':
      return shape.declaredType
    case 'string':
      return shape.size == null ? type : `${type} (${formatNumber(shape.size)} chars)`
    case 'collection':
    case 'map':
      return `${type} (size ${formatNumber(shape.size)})`
    case 'array':
      return shape.size == null ? type : `${type} (length ${formatNumber(shape.size)})`
    case 'optional':
      return `${type} (${shape.present ? 'present' : 'empty'})`
    case 'enum':
      return shape.constant ? `${type}.${shape.constant}` : type
    case 'unknown':
      return 'lost'
    default:
      return type
  }
}

/** The full types, and what the exposure withholds, for a shape's tooltip. */
function shapeTitle(shape) {
  if (!shape) return ''
  const parts = [shape.type ?? shape.declaredType]
  if (shape.type && shape.declaredType && shape.type !== shape.declaredType)
    parts.push(`declared ${shape.declaredType}`)
  if (shape.withheld) parts.push(shape.kind === 'enum' ? `constant ${WITHHELD}` : `length ${WITHHELD}`)
  if (shape.kind === 'unknown') parts.push("lost by the agent's transport or a Clear recording")
  return parts.filter(Boolean).join(' · ')
}

function clock(iso) {
  return iso ? formatClockTime(Date.parse(iso)) : '—'
}

watch(
  () => props.method,
  () => clear()
)
onMounted(load)
onBeforeUnmount(() => {
  unmounted = true
  if (timer) clearTimeout(timer)
})

defineExpose({load})
</script>

<template>
  <section class="card mb-4 code-paths-probes" aria-labelledby="code-paths-probes-heading">
    <div class="card-body">
      <div class="d-flex flex-wrap align-items-start justify-content-between gap-2 mb-2">
        <div>
          <h3 id="code-paths-probes-heading" class="h6 fw-semibold mb-1">Method probes</h3>
          <p class="small text-muted mb-0">
            Record one method's next {{ report?.maxInvocations ?? 20 }} invocations, for at most
            {{ report?.windowSeconds ?? 60 }} seconds, {{ report?.maxActive ?? 5 }} probes at once: metadata, and
            argument and return shapes when you ask for them, never values.
          </p>
        </div>
        <div v-if="method" class="d-flex flex-wrap align-items-center gap-3">
          <div class="form-check small mb-0 code-paths-probe-shapes-option">
            <input
              id="code-paths-probe-shapes"
              v-model="recordShapes"
              class="form-check-input"
              type="checkbox"
              :disabled="!shapesAvailable || readOnly"
              :aria-describedby="shapesWhy ? 'code-paths-probe-shapes-why' : undefined"
            />
            <label class="form-check-label" for="code-paths-probe-shapes">Record argument and return shapes</label>
          </div>
          <button
            type="button"
            class="btn btn-sm btn-outline-primary code-paths-probe-start"
            :disabled="!canStart"
            :aria-describedby="method ? 'code-paths-probe-target' : undefined"
            @click="start"
          >
            <i class="bi bi-crosshair me-1" aria-hidden="true"></i>Probe this method
          </button>
        </div>
      </div>
      <p v-if="method" id="code-paths-probe-target" class="small mb-2">
        <code class="bootui-break-anywhere">{{ method }}</code>
        <span v-if="probing" class="text-muted"> — probe {{ probing.id }} is {{ stateLabel(probing) }}.</span>
      </p>
      <p v-if="method && shapesWhy" id="code-paths-probe-shapes-why" class="small text-muted mb-2">
        {{ report.shapesUnavailableReason }}
      </p>
      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason"
        >Starting and stopping probes is read-only; a running probe ends by itself within
        {{ report?.windowSeconds ?? 60 }} seconds.</ReadOnlyNotice
      >
      <p v-if="report && !report.available" class="small text-muted mb-2 code-paths-probes-unavailable">
        {{ report.unavailableReason }}
      </p>
      <p v-if="loadError" class="small text-danger mb-2" role="alert">{{ loadError }}</p>
      <FlashBanner :message="banner" @dismiss="clear" />

      <p v-if="report && !probes.length" class="small text-muted mb-0 code-paths-probes-empty">
        No probe in this run. Select a method in a route's tree, then Probe this method.
      </p>
      <ul v-else class="list-unstyled mb-0 code-paths-probe-list">
        <li v-for="probe in probes" :key="probe.id" class="border-top pt-2 mt-2 code-paths-probe">
          <div class="d-flex flex-wrap align-items-center gap-2">
            <button
              type="button"
              class="btn btn-link p-0 text-start"
              :aria-expanded="expanded.has(probe.id)"
              :aria-controls="`code-paths-probe-${probe.id}`"
              @click="toggle(probe.id)"
            >
              <code :title="probe.method">{{ methodLabel(probe) }}</code>
            </button>
            <span class="badge code-paths-probe-state" :class="stateClass(probe)">{{ stateLabel(probe) }}</span>
            <span class="small text-muted">
              {{ formatNumber(probe.recorded) }} of {{ formatNumber(probe.maxInvocations) }} invocations
              <template v-if="probe.dropped"> · {{ formatNumber(probe.dropped) }} dropped</template>
              <template v-if="probe.state === 'active' && probe.endsAt"> · until {{ clock(probe.endsAt) }}</template>
              <template v-if="endLabel(probe)"> · {{ endLabel(probe) }}</template>
            </span>
            <span v-if="probe.async" class="badge text-bg-secondary" title="Times the result's assembly only"
              >assembly only</span
            >
            <span
              v-if="probe.recordShapes"
              class="badge text-bg-light border code-paths-probe-shapes-badge"
              title="Records argument and return shapes: types, null or not, and sizes, never values"
              >shapes</span
            >
            <button
              v-if="probe.state === 'starting' || probe.state === 'active'"
              type="button"
              class="btn btn-sm btn-outline-secondary ms-auto code-paths-probe-stop"
              :disabled="readOnly"
              @click="stop(probe)"
            >
              Stop
            </button>
          </div>
          <p
            v-if="probe.removal && probe.removal !== 'removed'"
            class="small mb-1 code-paths-probe-removal"
            :class="removalConcern(probe) ? 'text-warning-emphasis' : 'text-muted'"
          >
            {{ probe.removal }}
          </p>
          <div v-if="expanded.has(probe.id)" :id="`code-paths-probe-${probe.id}`" class="mt-2">
            <p v-if="probe.recordShapes && probe.shapesHiddenReason" class="small text-muted mb-1">
              {{ probe.shapesHiddenReason }}
            </p>
            <p v-if="probe.shapesDropped" class="small text-warning-emphasis mb-1">
              {{ formatNumber(probe.shapesDropped) }} shape records were dropped: the agent's transport was full.
            </p>
            <p v-if="!probe.hits.length" class="small text-muted mb-0">
              No invocation recorded<template v-if="probe.state === 'active'"> yet</template>.
            </p>
            <div v-else class="table-responsive">
              <table class="table table-sm align-middle mb-0 code-paths-probe-hits">
                <caption class="visually-hidden">
                  Invocations of
                  {{
                    methodLabel(probe)
                  }}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Time</th>
                    <th scope="col" class="text-end">Duration</th>
                    <th scope="col">Outcome</th>
                    <th v-if="showsShapes(probe)" scope="col">Arguments</th>
                    <th scope="col">Request</th>
                    <th scope="col">Thread</th>
                    <th scope="col">Called from</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(hit, index) in probe.hits" :key="index">
                    <td class="small">{{ clock(hit.time) }}</td>
                    <td class="text-end font-monospace small">{{ duration(hit) }}</td>
                    <td class="small">
                      <span v-if="hit.outcome === 'threw'" class="text-danger-emphasis">
                        threw <code>{{ shortName(hit.exceptionType) }}</code>
                      </span>
                      <span v-else>returned</span>
                      <template v-if="showsShapes(probe) && hit.returned">
                        <code class="ms-1 code-paths-probe-shape" :title="shapeTitle(hit.returned)">{{
                          shapeLabel(hit.returned)
                        }}</code
                        ><span v-if="shapeNote(hit.returned)" class="visually-hidden">
                          ({{ shapeNote(hit.returned) }})</span
                        >
                      </template>
                    </td>
                    <td v-if="showsShapes(probe)" class="small code-paths-probe-arguments">
                      <span v-if="!hit.arguments?.length" class="text-muted">none</span>
                      <template v-for="(shape, position) in hit.arguments ?? []" :key="position">
                        <span v-if="position" class="text-muted">, </span>
                        <code class="code-paths-probe-shape" :title="shapeTitle(shape)">{{ shapeLabel(shape) }}</code
                        ><span v-if="shapeNote(shape)" class="visually-hidden"> ({{ shapeNote(shape) }})</span>
                      </template>
                      <span v-if="hit.argumentsNotRecorded" class="text-muted">{{
                        `, +${formatNumber(hit.argumentsNotRecorded)} more`
                      }}</span>
                      <span v-if="hit.shapesIncomplete" class="text-warning-emphasis"> (some shapes lost)</span>
                    </td>
                    <td class="small">
                      <router-link v-if="hit.requestId" :to="{path: '/activity', query: {request: hit.requestId}}">
                        <code>{{ hit.requestId }}</code>
                      </router-link>
                      <span v-else class="text-muted">—</span>
                    </td>
                    <td class="small">{{ hit.threadKind }}</td>
                    <td class="small">
                      <code v-if="hit.caller" class="bootui-break-anywhere">{{ hit.caller }}</code>
                      <span v-else class="text-muted">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p
                v-if="showsShapes(probe) && withholds(probe)"
                class="small text-muted mt-1 mb-0 code-paths-probe-withheld"
              >
                String lengths, char[] and byte[] lengths, and enum constants are shown with bootui.expose-values=FULL.
              </p>
            </div>
          </div>
        </li>
      </ul>
      <details v-if="report?.limitations?.length" class="mt-3 small code-paths-limitations">
        <summary>What probes cannot see ({{ report.limitations.length }})</summary>
        <ul class="mb-0 mt-2">
          <li v-for="limitation in report.limitations" :key="limitation">{{ limitation }}</li>
        </ul>
      </details>
    </div>
  </section>
</template>
