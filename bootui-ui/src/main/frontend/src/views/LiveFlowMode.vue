<script setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import UnavailableState from './components/UnavailableState.vue'
import {formatClockTime, formatNumber, formatRelative} from '../utils/format.js'
import {formatLoadError} from '../utils/loadError.js'
import {
  MAX_CONCURRENT_PULSES,
  OUTCOME_ICONS,
  OUTCOME_LABELS,
  PROTOCOL_ICONS,
  PROTOCOL_LABELS,
  PULSE_DURATION_MS,
  REDUCED_MOTION_HIGHLIGHT_MS,
  createFlowQueue,
  createTransientTargetStateManager,
  describeNewEvidence,
  diffFlowPulses,
  externalEndpointId,
  filterServiceMap,
  layoutServiceMap,
  normalizeServiceMap,
  pulseTone,
  sequenceFlowPulses
} from '../utils/serviceMap.js'

const props = defineProps({
  /**
   * Incremented by the parent panel after every Live Activity refresh (the SSE tick). The map re-reads
   * the same already-captured evidence on each change; it never polls on its own.
   */
  refreshTick: {type: Number, default: 0},
  /** Whether the parent panel's live stream is currently paused. */
  paused: {type: Boolean, default: false}
})

const PROTOCOL_OPTIONS = ['HTTP_INBOUND', 'CACHE', 'HTTP', 'JDBC', 'KAFKA', 'RABBITMQ']
const MIN_ZOOM = 0.6
const MAX_ZOOM = 1.6
const ZOOM_STEP = 0.2

const report = ref(null)
const error = ref(null)
const loading = ref(false)
const lastFetched = ref(null)
const protocolFilter = ref('')
const textFilter = ref('')
const selectedId = ref(null)
const zoom = ref(1)
const liveMessage = ref('')
const pulses = ref([])
const highlightedEdges = ref(new Set())
const targetStates = ref([])
const scrollElement = ref(null)
const svgElement = ref(null)

// Reduced motion is resolved once and then watched, so the same panel honors a mid-session change to
// the OS preference without a reload.
const reducedMotion = ref(false)
let motionQuery = null
let previousEdges = null
let requestInFlight = false
let refreshPending = false
let unmounted = false
let pauseGeneration = 0
const highlightTimers = new Map()

const queue = createFlowQueue({maxConcurrent: MAX_CONCURRENT_PULSES, duration: PULSE_DURATION_MS})
const unsubscribeQueue = queue.subscribe((active) => {
  pulses.value = [...active]
})
const targetStateManager = createTransientTargetStateManager()
const unsubscribeTargetStates = targetStateManager.subscribe((active) => {
  targetStates.value = [...active]
})

const map = computed(() => normalizeServiceMap(report.value))
const filtered = computed(() => filterServiceMap(map.value, {protocol: protocolFilter.value, text: textFilter.value}))
const layout = computed(() => layoutServiceMap(filtered.value))
const nodesById = computed(() => {
  const index = new Map()
  if (map.value.application) index.set(map.value.application.id, map.value.application)
  for (const node of map.value.nodes) index.set(node.id, node)
  return index
})
const selected = computed(() => (selectedId.value ? (nodesById.value.get(selectedId.value) ?? null) : null))
const selectedEdge = computed(() => {
  if (!selected.value) return null
  return (
    filtered.value.edges.find((edge) => edge.fromId === selected.value.id || edge.toId === selected.value.id) ?? null
  )
})
const targetStatesById = computed(() => new Map(targetStates.value.map((state) => [state.targetId, state])))
const truncation = computed(() => map.value.truncation)
// Roving tabindex anchor. Falls back to the first visible node whenever the selected one is filtered
// away, so the graph can never become unreachable by keyboard.
const rovingId = computed(() => {
  const visible = filtered.value.nodes
  if (selectedId.value && visible.some((node) => node.id === selectedId.value)) return selectedId.value
  return visible[0]?.id ?? null
})
const hasFilters = computed(() => Boolean(protocolFilter.value || textFilter.value.trim()))
const dependencyCount = computed(() => filtered.value.nodes.filter((node) => node.kind === 'DEPENDENCY').length)
const failingCount = computed(() => filtered.value.nodes.filter((node) => node.outcome === 'RETAINED_FAILURES').length)

async function loadServiceMap() {
  if (requestInFlight) {
    refreshPending = true
    return
  }
  requestInFlight = true
  const requestPauseGeneration = pauseGeneration
  const animationEligibleAtStart = !props.paused
  loading.value = true
  try {
    const response = await apiFetch('api/activity/service-map')
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    const next = normalizeServiceMap(await response.json())
    if (unmounted) return
    applyNewEvidence(next, animationEligibleAtStart && !props.paused && requestPauseGeneration === pauseGeneration)
    report.value = next
    error.value = null
    lastFetched.value = Date.now()
  } catch (err) {
    if (unmounted) return
    error.value = formatLoadError(err, 'Could not load the service map')
  } finally {
    requestInFlight = false
    if (unmounted) return
    if (refreshPending) {
      refreshPending = false
      void loadServiceMap()
    } else {
      loading.value = false
    }
  }
}

/**
 * Turns the difference between the previous and the next snapshot into motion.
 *
 * Nothing animates on a first load, on a brand-new edge, or when no new interaction id arrived — so an
 * idle application shows a completely still map, and a busy one shows a small, bounded burst.
 *
 * Full motion sequences causally-related pulses before they are queued (`sequenceFlowPulses`): inbound
 * HTTP starts first; downstream completions sharing the flow then start in retained timestamp order.
 * Reduced motion deliberately skips sequencing — there is no travel to pace, so every changed edge is
 * emphasized immediately, without the causal stagger's delay, and the polite announcement below already
 * narrates the same causal story in full sentences instead.
 */
function applyNewEvidence(next, animationEligible) {
  // A response that crossed a pause boundary is baseline-only even if resume won the race before it
  // resolved. This prevents evidence captured by an old request generation from replaying after resume.
  if (!animationEligible) {
    previousEdges = next.edges
    return
  }
  const fresh = diffFlowPulses(previousEdges, next.edges)
  previousEdges = next.edges
  if (!fresh.length) return
  liveMessage.value = describeNewEvidence(fresh, nodesById.value)
  if (reducedMotion.value) {
    for (const pulse of fresh) highlightEdge(pulse.edgeId)
    targetStateManager.enqueue(fresh, {
      durationOverride: REDUCED_MOTION_HIGHLIGHT_MS,
      immediate: true
    })
    return
  }
  const accepted = queue.enqueue(sequenceFlowPulses(fresh))
  targetStateManager.enqueue(accepted)
}

/** The reduced-motion equivalent of a pulse: a brief, static emphasis on the edge that changed. */
function highlightEdge(edgeId) {
  const next = new Set(highlightedEdges.value)
  next.add(edgeId)
  highlightedEdges.value = next
  const existing = highlightTimers.get(edgeId)
  if (existing) clearTimeout(existing)
  highlightTimers.set(
    edgeId,
    setTimeout(() => {
      const cleared = new Set(highlightedEdges.value)
      cleared.delete(edgeId)
      highlightedEdges.value = cleared
      highlightTimers.delete(edgeId)
    }, REDUCED_MOTION_HIGHLIGHT_MS)
  )
}

/** Cancels every transient visual and its timer; used by pause, preference changes, and unmount. */
function clearTransientEvidence() {
  for (const timer of highlightTimers.values()) clearTimeout(timer)
  highlightTimers.clear()
  highlightedEdges.value = new Set()
  queue.clear()
  targetStateManager.clear()
  liveMessage.value = ''
}

function pulseGeometry(pulse) {
  const edge = layout.value.edges.find((candidate) => candidate.id === pulse.edgeId)
  if (!edge) return null
  const duration = `${pulse.durationMs ?? PULSE_DURATION_MS}ms`
  const delay = `${pulse.startDelayMs ?? 0}ms`
  return {
    key: pulse.id,
    tone: pulse.tone,
    path: edge.animationPath,
    duration,
    delay,
    // CSS Motion Path is intentionally used instead of SMIL: CSS animation delays are relative to the
    // dynamically inserted element's own animation timeline in Chromium, BootUI's supported E2E browser.
    // The exact visible SVG path is reused, so curved and corridor-routed pulses cannot drift off-route.
    style: {
      offsetPath: `path("${edge.animationPath}")`,
      animationDuration: duration,
      animationDelay: delay
    }
  }
}

const visiblePulses = computed(() => pulses.value.map(pulseGeometry).filter(Boolean))
/** The subset of visible pulses that also render a restrained trailing halo (slow tone only). */
const visibleSlowPulses = computed(() => visiblePulses.value.filter((pulse) => pulse.tone === 'slow'))

function isHighlighted(edgeId) {
  return highlightedEdges.value.has(edgeId)
}

function targetState(nodeId) {
  return targetStatesById.value.get(nodeId) ?? null
}

function select(id) {
  selectedId.value = selectedId.value === id ? null : id
}

function onNodeKeydown(event) {
  const nodes = [...(svgElement.value?.querySelectorAll('.flow-node[role="button"]') ?? [])]
  const current = nodes.indexOf(event.currentTarget)
  const target = {
    ArrowRight: (current + 1) % nodes.length,
    ArrowDown: (current + 1) % nodes.length,
    ArrowLeft: (current - 1 + nodes.length) % nodes.length,
    ArrowUp: (current - 1 + nodes.length) % nodes.length,
    Home: 0,
    End: nodes.length - 1
  }[event.key]
  if (target !== undefined && nodes.length) {
    event.preventDefault()
    nodes[target]?.focus()
  }
}

function zoomBy(delta) {
  zoom.value = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Number((zoom.value + delta).toFixed(1))))
}

function resetZoom() {
  zoom.value = 1
}

function clearFilters() {
  protocolFilter.value = ''
  textFilter.value = ''
}

function nodeLabel(node) {
  const protocol = PROTOCOL_LABELS[node.protocol] ?? node.protocol
  const state = node.configured && !node.observed ? 'configured, no recent evidence' : OUTCOME_LABELS[node.outcome]
  return `${node.label}. ${protocol}. ${state}. ${formatNumber(node.interactions)} retained interactions.`
}

function relationshipEndpointLabel(edge) {
  const endpointId = externalEndpointId(edge)
  return nodesById.value.get(endpointId)?.label || endpointId
}

function shortLabel(value) {
  if (!value) return ''
  const characters = Array.from(String(value))
  return characters.length > 26 ? `${characters.slice(0, 25).join('')}…` : value
}

function stateBadge(node) {
  if (!node.observed) return {text: 'Configured', icon: 'bi-sliders', tone: 'muted'}
  if (node.outcome === 'RETAINED_FAILURES')
    return {text: 'Failures retained', icon: 'bi-exclamation-triangle', tone: 'danger'}
  return {text: 'Observed', icon: 'bi-check-circle', tone: 'ok'}
}

onMounted(() => {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    reducedMotion.value = motionQuery.matches === true
    motionQuery.addEventListener?.('change', onMotionPreferenceChange)
  }
  loadServiceMap()
})

function onMotionPreferenceChange(event) {
  reducedMotion.value = event.matches === true
  if (reducedMotion.value) clearTransientEvidence()
}

onBeforeUnmount(() => {
  unmounted = true
  refreshPending = false
  motionQuery?.removeEventListener?.('change', onMotionPreferenceChange)
  clearTransientEvidence()
  unsubscribeQueue()
  unsubscribeTargetStates()
})

watch(
  () => props.refreshTick,
  () => loadServiceMap()
)

watch(
  () => ({
    nodeIds: [map.value.application?.id, ...filtered.value.nodes.map((node) => node.id)],
    edgeIds: filtered.value.edges.map((edge) => edge.id)
  }),
  ({nodeIds, edgeIds}) => targetStateManager.reconcile(new Set(nodeIds), new Set(edgeIds))
)

watch(
  () => props.paused,
  (paused) => {
    if (!paused) return
    pauseGeneration += 1
    clearTransientEvidence()
  }
)

watch(selectedId, async (id) => {
  if (!id) return
  await nextTick()
  scrollElement.value?.querySelector('[aria-pressed="true"]')?.focus?.()
})
</script>

<template>
  <div class="flow-map">
    <div class="flow-toolbar">
      <div class="flow-filters">
        <label class="visually-hidden" for="flow-protocol">Filter dependencies by protocol</label>
        <select id="flow-protocol" v-model="protocolFilter" class="form-select form-select-sm">
          <option value="">All protocols</option>
          <option v-for="option in PROTOCOL_OPTIONS" :key="option" :value="option">
            {{ PROTOCOL_LABELS[option] }}
          </option>
        </select>
        <label class="visually-hidden" for="flow-text">Filter dependencies by name</label>
        <input
          id="flow-text"
          v-model="textFilter"
          type="search"
          class="form-control form-control-sm"
          placeholder="Filter by host, target, or topic"
        />
        <button v-if="hasFilters" type="button" class="btn btn-sm btn-outline-secondary" @click="clearFilters">
          Clear
        </button>
      </div>
      <div class="flow-zoom" role="group" aria-label="Map zoom">
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary"
          aria-label="Zoom out"
          :disabled="zoom <= MIN_ZOOM"
          @click="zoomBy(-ZOOM_STEP)"
        >
          <i class="bi bi-dash-lg" aria-hidden="true"></i>
        </button>
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary flow-zoom-reset"
          aria-label="Reset zoom"
          :disabled="zoom === 1"
          @click="resetZoom"
        >
          {{ Math.round(zoom * 100) }}%
        </button>
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary"
          aria-label="Zoom in"
          :disabled="zoom >= MAX_ZOOM"
          @click="zoomBy(ZOOM_STEP)"
        >
          <i class="bi bi-plus-lg" aria-hidden="true"></i>
        </button>
      </div>
    </div>

    <p class="flow-lede">
      Assembled from evidence BootUI already retained. Opening this map contacts nothing and probes nothing, and a
      retained failure is debugging evidence — not a health check of the remote system.
    </p>

    <UnavailableState v-if="error" icon="bi-exclamation-triangle" variant="warning" :message="error" />

    <UnavailableState
      v-else-if="report && !map.available"
      icon="bi-diagram-2"
      :message="map.unavailableReason || 'No service map source is available yet.'"
    />

    <template v-else-if="report">
      <div v-if="truncation?.truncated" class="flow-note" role="status">
        <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
        Showing {{ formatNumber(truncation.dependenciesShown) }} of
        {{ formatNumber(truncation.dependenciesShown + truncation.dependenciesOmitted) }} dependencies.
        {{ formatNumber(truncation.dependenciesOmitted) }} less recently used
        {{ truncation.dependenciesOmitted === 1 ? 'dependency is' : 'dependencies are' }} not drawn.
      </div>

      <div v-for="warning in map.warnings" :key="warning" class="flow-note flow-note--warning" role="status">
        <i class="bi bi-exclamation-circle me-1" aria-hidden="true"></i>{{ warning }}
      </div>

      <p class="flow-summary">
        <span>{{ formatNumber(dependencyCount) }} {{ dependencyCount === 1 ? 'dependency' : 'dependencies' }}</span>
        <span v-if="failingCount" class="flow-summary__failing">
          <i class="bi bi-exclamation-triangle me-1" aria-hidden="true"></i>{{ formatNumber(failingCount) }} with
          retained failures
        </span>
        <span v-if="map.sources.length">Evidence from {{ map.sources.join(', ') }}</span>
        <span v-if="lastFetched">Read {{ formatClockTime(lastFetched) }}</span>
        <span v-if="paused" class="flow-summary__paused">
          <i class="bi bi-pause-circle me-1" aria-hidden="true"></i>Live updates paused
        </span>
      </p>

      <div
        v-if="filtered.nodes.length"
        ref="scrollElement"
        class="flow-stage"
        role="region"
        aria-label="Service map of this application's local integration surface"
      >
        <svg
          ref="svgElement"
          class="flow-svg"
          :width="Math.round(layout.width * zoom)"
          :height="Math.round(layout.height * zoom)"
          :viewBox="`0 0 ${layout.width} ${layout.height}`"
          role="group"
          :aria-label="`${filtered.nodes.length} mapped nodes and ${filtered.edges.length} relationships`"
        >
          <defs>
            <!-- The brand gradient means "selected" here and nowhere else on the map. -->
            <linearGradient id="flow-selected-fill" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#198754" />
              <stop offset="100%" stop-color="#0d6efd" />
            </linearGradient>
            <marker
              id="flow-arrow"
              markerWidth="8"
              markerHeight="6"
              refX="7"
              refY="3"
              orient="auto"
              markerUnits="userSpaceOnUse"
            >
              <polygon class="flow-arrow-head" points="0 0, 8 3, 0 6" />
            </marker>
          </defs>

          <g aria-hidden="true">
            <path
              v-for="(edge, index) in layout.edges"
              :key="edge.id"
              :id="`flow-route-${index}`"
              :d="edge.path"
              :class="[
                'flow-edge',
                {'flow-edge--no_evidence': edge.edge.outcome === 'NO_EVIDENCE'},
                {'flow-edge--highlighted': isHighlighted(edge.id)}
              ]"
              marker-end="url(#flow-arrow)"
            />
          </g>

          <g aria-hidden="true" class="flow-pulses">
            <!-- Slow pulses alone get a restrained, low-opacity trailing halo on the exact same path and
                 timing as their comet head, so "slow" reads as unmistakable without ever flashing. -->
            <circle
              v-for="pulse in visibleSlowPulses"
              :key="`${pulse.key}-trail`"
              class="flow-pulse-trail"
              r="9"
              :style="pulse.style"
            />
            <circle
              v-for="pulse in visiblePulses"
              :key="pulse.key"
              :class="['flow-pulse', `flow-pulse--${pulse.tone}`]"
              r="4.5"
              :style="pulse.style"
            />
          </g>

          <g
            v-if="layout.application"
            :transform="`translate(${layout.application.x - layout.application.w / 2},${layout.application.y - layout.application.h / 2})`"
            :class="[
              'flow-node',
              'flow-node--app',
              targetState(layout.application.node.id) &&
                `flow-node--transient-${targetState(layout.application.node.id).tone}`
            ]"
            role="img"
            aria-label="This application, the centre of the map"
          >
            <rect
              v-if="targetState(layout.application.node.id)"
              class="flow-target-ring"
              x="-5"
              y="-5"
              :width="layout.application.w + 10"
              :height="layout.application.h + 10"
              rx="14"
            />
            <rect class="flow-node-shape" :width="layout.application.w" :height="layout.application.h" rx="10" />
            <text
              :x="layout.application.w / 2"
              :y="layout.application.h / 2"
              text-anchor="middle"
              dominant-baseline="central"
            >
              This application
            </text>
            <g
              v-if="targetState(layout.application.node.id)"
              class="flow-target-chip"
              :transform="`translate(${layout.application.w / 2},-13)`"
            >
              <rect x="-48" y="-10" width="96" height="20" rx="10" />
              <text text-anchor="middle" dominant-baseline="central">
                {{ targetState(layout.application.node.id).label }}
              </text>
            </g>
          </g>

          <g
            v-for="box in [layout.inbound, ...layout.dependencies].filter(Boolean)"
            :key="box.node.id"
            :transform="`translate(${box.x - box.w / 2},${box.y - box.h / 2})`"
            :class="[
              'flow-node',
              `flow-node--${box.node.protocol.toLowerCase()}`,
              {
                'flow-node--selected': selectedId === box.node.id,
                'flow-node--unobserved': !box.node.observed,
                [`flow-node--transient-${targetState(box.node.id)?.tone}`]: targetState(box.node.id)
              }
            ]"
            role="button"
            :tabindex="rovingId === box.node.id ? 0 : -1"
            :aria-pressed="selectedId === box.node.id"
            :aria-label="nodeLabel(box.node)"
            @click="select(box.node.id)"
            @keydown.enter="select(box.node.id)"
            @keydown.space.prevent="select(box.node.id)"
            @keydown="onNodeKeydown"
          >
            <rect
              class="flow-focus-ring flow-focus-ring--outer"
              x="-5"
              y="-5"
              :width="box.w + 10"
              :height="box.h + 10"
            />
            <rect class="flow-focus-ring flow-focus-ring--inner" x="-2" y="-2" :width="box.w + 4" :height="box.h + 4" />
            <rect
              v-if="targetState(box.node.id)"
              class="flow-target-ring"
              x="-5"
              y="-5"
              :width="box.w + 10"
              :height="box.h + 10"
              rx="12"
            />
            <rect class="flow-node-shape" :width="box.w" :height="box.h" rx="8" />
            <text class="flow-node-label" x="12" :y="box.h / 2 - 5" dominant-baseline="central">
              {{ shortLabel(box.node.label) }}
            </text>
            <text class="flow-node-meta" x="12" :y="box.h / 2 + 11" dominant-baseline="central">
              {{ PROTOCOL_LABELS[box.node.protocol] }} ·
              {{ box.node.observed ? `${formatNumber(box.node.interactions)} retained` : 'configured' }}
              <template v-if="box.node.failures">· {{ formatNumber(box.node.failures) }} failed</template>
            </text>
            <g v-if="targetState(box.node.id)" class="flow-target-chip" :transform="`translate(${box.w / 2},-13)`">
              <rect x="-48" y="-10" width="96" height="20" rx="10" />
              <text text-anchor="middle" dominant-baseline="central">{{ targetState(box.node.id).label }}</text>
            </g>
          </g>
        </svg>
      </div>

      <UnavailableState
        v-else
        icon="bi-diagram-2"
        :message="
          hasFilters
            ? 'No mapped dependency matches these filters.'
            : 'No dependency has been derived yet. Configure a datasource, or exercise an outbound HTTP call, Kafka publish, or RabbitMQ publish, and it will appear here.'
        "
      />

      <p class="visually-hidden" aria-live="polite">{{ liveMessage }}</p>

      <div v-if="selected" class="flow-detail">
        <div class="flow-detail__head">
          <i :class="['bi', PROTOCOL_ICONS[selected.protocol], 'me-2']" aria-hidden="true"></i>
          <span class="flow-detail__title">{{ selected.label }}</span>
          <span :class="['flow-state', `flow-state--${stateBadge(selected).tone}`]">
            <i :class="['bi', stateBadge(selected).icon]" aria-hidden="true"></i>{{ stateBadge(selected).text }}
          </span>
        </div>
        <p v-if="selected.detail" class="flow-detail__detail">{{ selected.detail }}</p>
        <dl class="flow-facts">
          <div>
            <dt>Protocol</dt>
            <dd>{{ PROTOCOL_LABELS[selected.protocol] }}</dd>
          </div>
          <div>
            <dt>Declared by configuration</dt>
            <dd>{{ selected.configured ? 'Yes' : 'No' }}</dd>
          </div>
          <div>
            <dt>Retained interactions</dt>
            <dd>{{ formatNumber(selected.interactions) }}</dd>
          </div>
          <div>
            <dt>Retained failures</dt>
            <dd>{{ formatNumber(selected.failures) }}</dd>
          </div>
          <div v-if="selected.distinctOperations != null">
            <dt>Distinct operations</dt>
            <dd>{{ formatNumber(selected.distinctOperations) }}</dd>
          </div>
          <div>
            <dt>Last seen</dt>
            <dd>{{ selected.lastSeen ? formatRelative(selected.lastSeen) : 'Never observed' }}</dd>
          </div>
          <div>
            <dt>Evidence state</dt>
            <dd>
              <i :class="['bi', OUTCOME_ICONS[selected.outcome], 'me-1']" aria-hidden="true"></i>
              {{ OUTCOME_LABELS[selected.outcome] }}
            </dd>
          </div>
        </dl>
        <p v-if="selected.note" class="flow-detail__note">{{ selected.note }}</p>
        <ul v-if="selectedEdge?.recentInteractions?.length" class="flow-recent">
          <li v-for="interaction in selectedEdge.recentInteractions" :key="interaction.id">
            <span class="flow-recent__op">{{ interaction.operation }}</span>
            <span
              :class="[
                'flow-recent__outcome',
                {
                  'flow-recent__outcome--failed': interaction.outcome === 'FAILED',
                  'flow-recent__outcome--slow': interaction.outcome !== 'FAILED' && pulseTone(interaction) === 'slow'
                }
              ]"
            >
              {{ interaction.outcome === 'FAILED' ? 'failed' : pulseTone(interaction) === 'slow' ? 'slow' : 'ok' }}
            </span>
            <span v-if="interaction.durationMs != null" class="flow-recent__duration"
              >{{ formatNumber(interaction.durationMs) }} ms</span
            >
            <span class="flow-recent__time">{{ formatClockTime(interaction.timestamp) }}</span>
          </li>
        </ul>
        <router-link v-if="selected.sourceRoute" class="flow-detail__link" :to="{path: selected.sourceRoute}">
          Open {{ selected.sourceLabel }}<i class="bi bi-box-arrow-up-right ms-1" aria-hidden="true"></i>
        </router-link>
      </div>

      <ul class="visually-hidden" aria-label="Service map relationships as text">
        <li v-for="node in filtered.nodes" :key="`text-${node.id}`">{{ nodeLabel(node) }}</li>
        <li v-for="edge in filtered.edges" :key="`text-edge-${edge.id}`">
          {{ edge.direction === 'INBOUND' ? 'Incoming into this application' : 'This application calls' }}
          {{ relationshipEndpointLabel(edge) }}: {{ formatNumber(edge.interactions) }} retained interactions,
          {{ formatNumber(edge.failures) }} failed.
        </li>
      </ul>

      <div class="flow-legend" aria-label="Map legend">
        <span class="flow-legend__item"
          ><span class="flow-legend__swatch flow-legend__swatch--observed" aria-hidden="true"></span>Observed</span
        >
        <span class="flow-legend__item"
          ><span class="flow-legend__swatch flow-legend__swatch--configured" aria-hidden="true"></span>Configured
          only</span
        >
        <span class="flow-legend__item"
          ><span class="flow-legend__swatch flow-legend__swatch--transient" aria-hidden="true"></span>New slow or failed
          evidence</span
        >
        <span class="flow-legend__note"
          >Amber/red emphasis is temporary; retained failures remain in counts and details.</span
        >
      </div>
    </template>
  </div>
</template>

<style scoped>
.flow-map {
  --flow-line: #64748b;
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}

.flow-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  justify-content: space-between;
}

.flow-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  flex: 1 1 18rem;
}

.flow-filters .form-select {
  max-width: 12rem;
}

.flow-filters .form-control {
  max-width: 22rem;
}

.flow-zoom {
  display: flex;
}

.flow-zoom .btn {
  border-radius: 0;
  min-width: 2.25rem;
}

.flow-zoom .btn:first-child {
  border-radius: var(--bootui-radius-sm) 0 0 var(--bootui-radius-sm);
}

.flow-zoom .btn:last-child {
  border-radius: 0 var(--bootui-radius-sm) var(--bootui-radius-sm) 0;
}

.flow-zoom .btn + .btn {
  margin-left: -1px;
}

.flow-zoom-reset {
  font-family: var(--bs-font-monospace);
  min-width: 4.25rem;
}

.flow-lede,
.flow-summary,
.flow-note {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  margin: 0;
}

.flow-note {
  border-left: 3px solid var(--bootui-border-alt);
  padding: 0.15rem 0 0.15rem 0.6rem;
}

.flow-note--warning {
  border-left-color: var(--bootui-warning-text-strong);
  color: var(--bootui-warning-text);
}

.flow-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.9rem;
}

.flow-summary__failing {
  color: var(--bootui-danger-text);
}

.flow-stage {
  overflow: auto;
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  height: clamp(22rem, 58vh, 38rem);
}

.flow-svg {
  display: block;
}

/* ── Edges ─────────────────────────────────────────────────────────────────── */
.flow-arrow-head {
  fill: var(--flow-line);
}

.flow-edge {
  stroke: var(--flow-line);
  stroke-width: 1.5;
  fill: none;
  opacity: 0.75;
}

.flow-edge--no_evidence {
  stroke-dasharray: 4 4;
  opacity: 0.55;
}

.flow-edge--highlighted {
  stroke-width: 3;
  opacity: 1;
}

/* ── Pulses ────────────────────────────────────────────────────────────────── */
/* CSS Motion Path starts on the dynamically mounted element's own animation timeline, unlike SMIL begin
   timestamps which are document-relative. `both` keeps the pulse invisible throughout its causal delay. */
.flow-pulse {
  fill: var(--bootui-green);
  opacity: 0;
  offset-distance: 0%;
  animation-name: flow-pulse-travel;
  animation-timing-function: linear;
  animation-iteration-count: 1;
  animation-fill-mode: both;
}

.flow-pulse--slow {
  fill: var(--bootui-warning-text-strong);
  /* A soft amber glow makes the slow tone unmistakable at a glance, distinct in both themes since it
     inherits the theme's own accessible warning token - never a flat color swap alone. */
  filter: drop-shadow(0 0 3px color-mix(in srgb, var(--bootui-warning-text-strong) 60%, transparent));
}

.flow-pulse--failed {
  fill: none;
  stroke: var(--bootui-danger-text);
  stroke-width: 2;
}

/* The slow tone's restrained trailing halo: a larger, softer ring following the exact same path and
   timing as its comet head (see the template), peaking at a low, calm opacity rather than a bright flash. */
.flow-pulse-trail {
  fill: none;
  stroke: var(--bootui-warning-text-strong);
  stroke-width: 1.75;
  opacity: 0;
  offset-distance: 0%;
  animation-name: flow-pulse-trail-travel;
  animation-timing-function: linear;
  animation-iteration-count: 1;
  animation-fill-mode: both;
}

@keyframes flow-pulse-travel {
  0% {
    offset-distance: 0%;
    opacity: 0;
  }
  8% {
    opacity: 1;
  }
  92% {
    opacity: 1;
  }
  100% {
    offset-distance: 100%;
    opacity: 0;
  }
}

@keyframes flow-pulse-trail-travel {
  0% {
    offset-distance: 0%;
    opacity: 0;
  }
  10% {
    opacity: 0.42;
  }
  90% {
    opacity: 0.42;
  }
  100% {
    offset-distance: 100%;
    opacity: 0;
  }
}

/* ── Nodes ─────────────────────────────────────────────────────────────────── */
.flow-node {
  cursor: pointer;
  outline: none;
}

.flow-node--app {
  cursor: default;
}

.flow-focus-ring {
  fill: none;
  opacity: 0;
  pointer-events: none;
  rx: calc(var(--bootui-radius-xs) + 2px);
}

.flow-focus-ring--outer {
  stroke: #fff;
  stroke-width: 6;
}

.flow-focus-ring--inner {
  stroke: #000;
  stroke-width: 3;
}

.flow-node:focus-visible .flow-focus-ring {
  opacity: 1;
}

.flow-node-shape {
  fill: var(--bootui-surface-solid);
  stroke: var(--bootui-border-alt);
  stroke-width: 1.5;
}

.flow-node text {
  pointer-events: none;
  user-select: none;
}

.flow-node--app .flow-node-shape {
  fill: var(--bootui-green-dark);
  stroke: var(--bootui-green-dark);
}

.flow-node--app text {
  fill: #fff;
  font-weight: 600;
  font-size: 13px;
}

.flow-node-label {
  fill: var(--bootui-text);
  font-family: var(--bs-font-monospace, ui-monospace, monospace);
  font-size: 12px;
}

.flow-node-meta {
  fill: var(--bootui-text-muted);
  font-size: 11px;
}

.flow-node--unobserved .flow-node-shape {
  stroke-dasharray: 5 4;
}

/* The green-to-blue gradient marks selection, and selection only. */
.flow-node--selected .flow-node-shape {
  fill: url(#flow-selected-fill);
  stroke: var(--bootui-green-dark);
  stroke-width: 2;
}

.flow-node--selected .flow-node-label,
.flow-node--selected .flow-node-meta {
  fill: #fff;
}

.flow-target-ring {
  pointer-events: none;
  stroke-width: 2;
}

.flow-target-chip {
  pointer-events: none;
}

.flow-target-chip text {
  fill: var(--bootui-surface-solid);
  font-family: var(--bs-font-sans-serif);
  font-size: 0.72rem;
  font-weight: 700;
}

.flow-node--transient-failed .flow-target-ring {
  fill: color-mix(in srgb, var(--bootui-danger) 14%, transparent);
  stroke: var(--bootui-danger-text);
}

.flow-node--transient-failed .flow-target-chip rect {
  fill: var(--bootui-danger-text);
}

.flow-node--transient-slow .flow-target-ring {
  fill: color-mix(in srgb, var(--bootui-warning) 18%, transparent);
  stroke: var(--bootui-warning-text-strong);
}

.flow-node--transient-slow .flow-target-chip rect {
  fill: var(--bootui-warning-text-strong);
}

/* ── Detail ────────────────────────────────────────────────────────────────── */
.flow-detail {
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  padding: 0.85rem 1rem;
  background: var(--bootui-surface);
}

.flow-detail__head {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.flow-detail__title {
  font-family: var(--bs-font-monospace);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.flow-state {
  align-items: center;
  border-radius: var(--bootui-radius-pill);
  display: inline-flex;
  font-size: 0.78rem;
  gap: 0.3rem;
  padding: 0.1rem 0.55rem;
}

.flow-state--ok {
  background: color-mix(in srgb, var(--bootui-green) 14%, transparent);
  color: var(--bootui-green-dark);
}

.flow-state--muted {
  background: color-mix(in srgb, var(--flow-line) 16%, transparent);
  color: var(--bootui-text-muted);
}

.flow-state--danger {
  background: color-mix(in srgb, var(--bootui-danger) 14%, transparent);
  color: var(--bootui-danger-text);
}

.flow-detail__detail,
.flow-detail__note {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  margin: 0.4rem 0 0;
}

.flow-facts {
  display: grid;
  gap: 0.35rem 1.5rem;
  grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
  margin: 0.65rem 0 0;
}

.flow-facts dt {
  color: var(--bootui-text-muted);
  font-size: 0.75rem;
  font-weight: 500;
}

.flow-facts dd {
  font-family: var(--bs-font-monospace);
  font-size: 0.85rem;
  margin: 0;
}

.flow-recent {
  display: flex;
  flex-direction: column;
  font-size: 0.8rem;
  gap: 0.15rem;
  list-style: none;
  margin: 0.65rem 0 0;
  padding: 0;
}

.flow-recent li {
  display: flex;
  gap: 0.6rem;
}

.flow-recent__op,
.flow-recent__duration,
.flow-recent__time {
  font-family: var(--bs-font-monospace);
}

.flow-recent__time,
.flow-recent__duration {
  color: var(--bootui-text-muted);
}

.flow-recent__outcome--failed {
  color: var(--bootui-danger-text);
}

/* Non-color label to match: "slow" is always spelled out here, in `describeFlowSequence`'s narration, and
   in the map's amber comet - never conveyed by color alone. The accessible warning token already differs
   per theme (see docs/design), so this reads clearly in both. */
.flow-recent__outcome--slow {
  color: var(--bootui-warning-text-strong);
}

.flow-detail__link {
  display: inline-block;
  font-size: 0.85rem;
  margin-top: 0.65rem;
}

/* ── Legend ────────────────────────────────────────────────────────────────── */
.flow-legend {
  align-items: center;
  color: var(--bootui-text-muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 0.82rem;
  gap: 0.85rem;
}

.flow-legend__item {
  align-items: center;
  display: flex;
  gap: 0.35rem;
}

.flow-legend__swatch {
  border: 1.5px solid var(--bootui-border-alt);
  border-radius: var(--bootui-radius-xs);
  display: inline-block;
  height: 14px;
  width: 14px;
}

.flow-legend__swatch--observed {
  border-color: var(--bootui-green);
}

.flow-legend__swatch--configured {
  border-style: dashed;
}

.flow-legend__swatch--transient {
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--bootui-warning) 28%, transparent) 0 50%,
    color-mix(in srgb, var(--bootui-danger) 22%, transparent) 50% 100%
  );
  border-color: var(--bootui-border-alt);
}

.flow-legend__note {
  margin-left: auto;
}

:global(:root[data-bootui-theme='dark']) .flow-map {
  --flow-line: #94a3b8;
}

@media (forced-colors: active) {
  .flow-focus-ring--outer,
  .flow-focus-ring--inner {
    stroke: Highlight;
  }
}

/* Reduced motion is handled in script by replacing travel with a brief static target/edge emphasis and
   a polite live-region update; this rule is the belt-and-braces guarantee that no particle moves. */
@media (prefers-reduced-motion: reduce) {
  .flow-pulse,
  .flow-pulse-trail {
    animation: none;
    display: none;
  }
}
</style>
