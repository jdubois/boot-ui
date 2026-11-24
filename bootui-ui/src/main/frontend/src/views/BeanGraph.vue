<script setup>
import {computed, nextTick, onActivated, onMounted, ref, watch} from 'vue'
import {MAX_GRAPH_DEPTH, MAX_GRAPH_NODES} from '../utils/useBeanGraph.js'

const props = defineProps({
  /** The neighbourhood returned by {@link traverseNeighborhood}. */
  graph: {type: Object, required: true},
  /** Map<name, BeanSummary> used to look up types for tooltip titles. */
  byName: {type: Object, required: true},
  /** Map<name, BeanSummary[]> retaining duplicate definitions merged into a graph node. */
  definitionsByName: {type: Object, required: true},
  /** Name of the currently focused bean. */
  focusName: {type: String, required: true},
  /** Incremented whenever the current bean is explicitly selected, including repeated selections. */
  centerRequest: {type: Number, default: 0}
})

const emit = defineEmits(['focus'])
const graphElement = ref(null)
const graphScrollElement = ref(null)
const zoom = ref(1)
const MIN_ZOOM = 0.6
const MAX_ZOOM = 2
const ZOOM_STEP = 0.2
const zoomPercent = computed(() => Math.round(zoom.value * 100))

watch(
  () => props.focusName,
  async () => {
    zoom.value = 1
    await nextTick()
    centerFocusedNode()
  }
)
watch(
  () => props.centerRequest,
  async () => {
    zoom.value = 1
    await nextTick()
    centerFocusedNode()
  }
)
watch(zoom, async () => {
  await nextTick()
  centerFocusedNode()
})
onMounted(centerFocusedNode)
onActivated(async () => {
  await nextTick()
  centerFocusedNode()
})

// ── Layout constants ──────────────────────────────────────────────────────────
const NODE_W = 148
const NODE_H = 36
const NODE_RX = 4
const FONT_SIZE = 11
/** Minimum arc spacing (px) between adjacent nodes in the same ring. */
const MIN_ARC_SPACING = NODE_W + 24
const MIN_RING_GAP = NODE_W + 32

/**
 * Compute the ring radius so adjacent nodes in a ring are at least
 * MIN_ARC_SPACING apart; clamp to a minimum radius.
 */
function ringRadius(n, minR) {
  if (n <= 1) return minR
  return Math.max(minR, (MIN_ARC_SPACING * n) / (2 * Math.PI))
}

// ── Dynamic SVG layout ────────────────────────────────────────────────────────
const layout = computed(() => {
  const {nodes, edges} = props.graph

  // Group nodes by depth
  const byDepth = new Map()
  for (const node of nodes) {
    if (!byDepth.has(node.depth)) byDepth.set(node.depth, [])
    byDepth.get(node.depth).push(node)
  }

  // Compute ring radii
  const radii = new Map()
  radii.set(0, 0)
  const depths = [...byDepth.keys()].filter((d) => d > 0).sort((a, b) => a - b)
  let prevR = 0
  for (const d of depths) {
    const count = byDepth.get(d).length
    const minR = prevR + MIN_RING_GAP
    const r = ringRadius(count, minR)
    radii.set(d, r)
    prevR = r
  }

  const maxR = Math.max(0, ...radii.values())
  const svgW = Math.max(520, (maxR + NODE_W * 0.7) * 2)
  const svgH = Math.max(420, (maxR + NODE_H * 1.2) * 2)
  const cx = svgW / 2
  const cy = svgH / 2

  // Position each node
  const pos = new Map()
  for (const [depth, ring] of byDepth) {
    const r = radii.get(depth) ?? depth * 160
    // Sort for deterministic placement: focus first, then by name
    const sorted = [...ring].sort((a, b) => (a.name === b.name ? 0 : a.name < b.name ? -1 : 1))
    sorted.forEach((node, i) => {
      if (r === 0) {
        pos.set(node.name, {x: cx, y: cy})
      } else {
        const angle = (2 * Math.PI * i) / sorted.length - Math.PI / 2
        pos.set(node.name, {x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle)})
      }
    })
  }

  // Compute trimmed edge endpoints (offset from node center toward the target)
  const trimmedEdges = edges
    .filter((e) => pos.has(e.from) && pos.has(e.to))
    .map((e) => {
      const {x: x1, y: y1} = pos.get(e.from)
      const {x: x2, y: y2} = pos.get(e.to)
      // Direction vector
      const dx = x2 - x1
      const dy = y2 - y1
      const dist = Math.sqrt(dx * dx + dy * dy)
      if (dist < 1) return null
      const ux = dx / dist
      const uy = dy / dist
      const horizontalIntersection = Math.abs(ux) > 0.0001 ? NODE_W / 2 / Math.abs(ux) : Number.POSITIVE_INFINITY
      const verticalIntersection = Math.abs(uy) > 0.0001 ? NODE_H / 2 / Math.abs(uy) : Number.POSITIVE_INFINITY
      const boundaryOffset = Math.min(horizontalIntersection, verticalIntersection)
      const srcOffset = boundaryOffset + 4
      const dstOffset = boundaryOffset + 12
      return {
        key: `${e.from}=>${e.to}`,
        x1: x1 + ux * srcOffset,
        y1: y1 + uy * srcOffset,
        x2: x2 - ux * dstOffset,
        y2: y2 - uy * dstOffset
      }
    })
    .filter(Boolean)

  return {svgW, svgH, pos, trimmedEdges, nodes}
})

// ── Node helpers ──────────────────────────────────────────────────────────────

/** Last meaningful segment of a bean name (e.g. 'orderRepository' from FQN). */
function shortName(name) {
  if (!name) return ''
  const base = name.split('#')[0]
  const parts = base.split('.')
  const value = parts[parts.length - 1] || name
  const characters = Array.from(value)
  return characters.length > 18 ? `${characters.slice(0, 17).join('')}…` : value
}

/** Full type from the index if available, otherwise the name itself. */
function nodeTitle(name) {
  const definitions = props.definitionsByName.get(name) || []
  if (!definitions.length) return `${name}\nReferenced dependency is not present in the loaded bean inventory.`
  return [name, ...definitions.map((bean) => bean.type).filter(Boolean)].join('\n')
}

function roleDescription(node) {
  if (!props.byName.has(node.name)) return 'Referenced dependency is not present in the loaded bean inventory'
  return {
    focus: 'Focused bean',
    dep: 'Direct dependency of the focused bean',
    rdep: 'Direct dependent of the focused bean',
    both: 'Mutual dependency with the focused bean',
    deep: `Bean at depth ${node.depth}`
  }[node.role]
}

// ── Event handlers ────────────────────────────────────────────────────────────
async function refocus(name) {
  if (!props.byName.has(name)) return
  emit('focus', name)
  await nextTick()
  graphElement.value?.querySelector('[aria-pressed="true"]')?.focus()
}

function onKeydown(event) {
  const nodes = [...event.currentTarget.ownerSVGElement.querySelectorAll('.bg-node[role="button"]')]
  const current = nodes.indexOf(event.currentTarget)
  const targetIndex = {
    ArrowRight: (current + 1) % nodes.length,
    ArrowDown: (current + 1) % nodes.length,
    ArrowLeft: (current - 1 + nodes.length) % nodes.length,
    ArrowUp: (current - 1 + nodes.length) % nodes.length,
    Home: 0,
    End: nodes.length - 1
  }[event.key]
  if (targetIndex !== undefined) {
    event.preventDefault()
    nodes[targetIndex]?.focus()
  }
}

function zoomBy(delta) {
  zoom.value = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Number((zoom.value + delta).toFixed(1))))
}

function resetZoom() {
  zoom.value = 1
}

function centerFocusedNode() {
  const scrollElement = graphScrollElement.value
  const focusElement = graphElement.value?.querySelector('[aria-pressed="true"]')
  if (!scrollElement || !focusElement) return

  const scrollRect = scrollElement.getBoundingClientRect()
  const focusRect = focusElement.getBoundingClientRect()
  scrollElement.scrollLeft += focusRect.left + focusRect.width / 2 - (scrollRect.left + scrollElement.clientWidth / 2)
  scrollElement.scrollTop += focusRect.top + focusRect.height / 2 - (scrollRect.top + scrollElement.clientHeight / 2)
}
</script>

<template>
  <div class="bean-graph-wrap">
    <!-- Truncation notice -->
    <div v-if="graph.truncated" class="alert alert-info py-2 small mb-2" role="status">
      <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
      <template v-if="graph.nodeLimited && graph.depthLimited">
        Graph limited to {{ MAX_GRAPH_NODES }} nodes and {{ MAX_GRAPH_DEPTH }} hops.
      </template>
      <template v-else-if="graph.nodeLimited"> Graph limited to {{ MAX_GRAPH_NODES }} nodes. </template>
      <template v-else> Graph limited to {{ MAX_GRAPH_DEPTH }} hops. </template>
      Focus any node to explore its neighbourhood.
    </div>

    <div v-if="definitionsByName.get(focusName)?.length > 1" class="alert alert-info py-2 small mb-2" role="status">
      <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
      {{ definitionsByName.get(focusName).length }} bean definitions share the name <code>{{ focusName }}</code>. Their
      recorded dependencies are combined in this graph.
    </div>

    <!-- No neighbours notice for isolated beans -->
    <div
      v-if="graph.nodes.length <= 1 && !graph.truncated"
      class="text-muted small mb-2"
      role="status"
      aria-live="polite"
    >
      <i class="bi bi-diagram-2 me-1" aria-hidden="true"></i>
      <strong>{{ focusName }}</strong> has no recorded dependencies or dependents.
    </div>

    <div v-if="graph.nodes.length > 1" class="bean-graph-toolbar" role="group" aria-label="Graph zoom">
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        aria-label="Zoom out"
        title="Zoom out"
        :disabled="zoom <= MIN_ZOOM"
        @click="zoomBy(-ZOOM_STEP)"
      >
        <i class="bi bi-dash-lg" aria-hidden="true"></i>
      </button>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary bean-graph-zoom-reset"
        aria-label="Reset zoom"
        title="Reset zoom"
        :disabled="zoom === 1"
        @click="resetZoom"
      >
        {{ zoomPercent }}%
      </button>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        aria-label="Zoom in"
        title="Zoom in"
        :disabled="zoom >= MAX_ZOOM"
        @click="zoomBy(ZOOM_STEP)"
      >
        <i class="bi bi-plus-lg" aria-hidden="true"></i>
      </button>
    </div>

    <div
      v-if="graph.nodes.length > 0"
      ref="graphScrollElement"
      class="bean-graph-scroll"
      role="region"
      aria-label="Bean dependency neighbourhood graph"
    >
      <div class="bean-graph-stage">
        <svg
          ref="graphElement"
          :width="Math.round(layout.svgW * zoom)"
          :height="Math.round(layout.svgH * zoom)"
          :viewBox="`0 0 ${layout.svgW} ${layout.svgH}`"
          class="bean-graph-svg"
          role="group"
          :aria-label="`Dependency neighbourhood for ${focusName}: ${graph.nodes.length} nodes, ${graph.edges.length} edges`"
        >
          <!-- Arrowhead marker (dependency direction) -->
          <defs>
            <marker
              id="bg-arrow"
              markerWidth="8"
              markerHeight="6"
              refX="7"
              refY="3"
              orient="auto"
              markerUnits="userSpaceOnUse"
            >
              <polygon class="bg-arrow-poly" points="0 0, 8 3, 0 6" />
            </marker>
          </defs>

          <!-- Edges -->
          <g aria-hidden="true">
            <line
              v-for="edge in layout.trimmedEdges"
              :key="edge.key"
              :x1="edge.x1"
              :y1="edge.y1"
              :x2="edge.x2"
              :y2="edge.y2"
              class="bg-edge"
              marker-end="url(#bg-arrow)"
            />
          </g>

          <!-- Nodes -->
          <g
            v-for="node in layout.nodes"
            :key="node.name"
            :transform="`translate(${(layout.pos.get(node.name)?.x ?? 0) - NODE_W / 2},${(layout.pos.get(node.name)?.y ?? 0) - NODE_H / 2})`"
            :class="['bg-node', `bg-node--${node.role}`]"
            :tabindex="byName.has(node.name) && node.role === 'focus' ? 0 : -1"
            :role="byName.has(node.name) ? 'button' : 'img'"
            :aria-label="`${node.name}. ${roleDescription(node)}.${
              byName.has(node.name) ? ' Use arrow keys to move between nodes; press Enter to focus.' : ''
            }`"
            :aria-pressed="node.role === 'focus'"
            @click="refocus(node.name)"
            @keydown.enter="refocus(node.name)"
            @keydown.space.prevent="refocus(node.name)"
            @keydown="onKeydown"
          >
            <title>{{ nodeTitle(node.name) }}</title>
            <rect class="bg-focus-ring bg-focus-ring--outer" x="-5" y="-5" :width="NODE_W + 10" :height="NODE_H + 10" />
            <rect class="bg-focus-ring bg-focus-ring--inner" x="-2" y="-2" :width="NODE_W + 4" :height="NODE_H + 4" />
            <rect class="bg-node-shape" :width="NODE_W" :height="NODE_H" :rx="NODE_RX" />
            <text
              :x="NODE_W / 2"
              :y="NODE_H / 2"
              text-anchor="middle"
              dominant-baseline="central"
              :font-size="FONT_SIZE"
            >
              {{ shortName(node.name) }}
            </text>
          </g>
        </svg>
      </div>
    </div>

    <ul v-if="graph.nodes.length > 1" class="visually-hidden" aria-label="Bean dependency relationships">
      <li v-for="node in graph.nodes" :key="`node-${node.name}`">{{ node.name }}: {{ roleDescription(node) }}.</li>
      <li v-for="edge in graph.edges" :key="`edge-${edge.from}-${edge.to}`">
        {{ edge.from }} depends on {{ edge.to }}.
      </li>
    </ul>

    <!-- Legend -->
    <div v-if="graph.nodes.length > 1" class="bg-legend" aria-label="Graph legend">
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--focus" aria-hidden="true"></span>
        <span>Focus</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--dep" aria-hidden="true"></span>
        <span>Dependency</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--rdep" aria-hidden="true"></span>
        <span>Dependent</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--both" aria-hidden="true"></span>
        <span>Mutual</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--deep" aria-hidden="true"></span>
        <span>Deeper hop</span>
      </span>
      <span class="bg-legend-arrow-note" aria-hidden="true"> <i class="bi bi-arrow-right me-1"></i>depends on </span>
    </div>
  </div>
</template>

<style scoped>
/* ── Container ─────────────────────────────────────────────────────────────── */
.bean-graph-wrap {
  --bean-graph-edge: #64748b;
  --bean-graph-focus: #146c43;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.bean-graph-scroll {
  overflow: auto;
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  height: clamp(20rem, 55vh, 36rem);
}

.bean-graph-stage {
  align-items: center;
  display: flex;
  height: max-content;
  justify-content: center;
  min-height: 100%;
  min-width: 100%;
  width: max-content;
}

.bean-graph-toolbar {
  align-self: flex-end;
  display: flex;
}

.bean-graph-toolbar .btn {
  border-radius: 0;
  min-width: 2.25rem;
}

.bean-graph-toolbar .btn:first-child {
  border-radius: var(--bootui-radius-sm) 0 0 var(--bootui-radius-sm);
}

.bean-graph-toolbar .btn:last-child {
  border-radius: 0 var(--bootui-radius-sm) var(--bootui-radius-sm) 0;
}

.bean-graph-toolbar .btn + .btn {
  margin-left: -1px;
}

.bean-graph-zoom-reset {
  font-family: var(--bs-font-monospace);
  min-width: 4.25rem !important;
}

/* ── SVG base ───────────────────────────────────────────────────────────────── */
.bean-graph-svg {
  display: block;
}

/* ── Arrowhead ──────────────────────────────────────────────────────────────── */
.bg-arrow-poly {
  fill: var(--bean-graph-edge);
}

/* ── Edges ──────────────────────────────────────────────────────────────────── */
.bg-edge {
  stroke: var(--bean-graph-edge);
  stroke-width: 1.5;
  fill: none;
}

/* ── Nodes shared ───────────────────────────────────────────────────────────── */
/* The SVG focus ring below replaces the UA outline, but only for
   :focus-visible. Keep the native outline anywhere that selector does not
   apply so keyboard focus is never silently removed. */
.bg-node:focus:not(:focus-visible) {
  outline: none;
}

.bg-node:focus-visible {
  outline: none;
}

.bg-node {
  cursor: pointer;
}

.bg-focus-ring {
  fill: none;
  opacity: 0;
  pointer-events: none;
  rx: calc(var(--bootui-radius-xs) + 2px);
}

.bg-focus-ring--outer {
  stroke: var(--bootui-surface-solid);
  stroke-width: 6;
}

.bg-focus-ring--inner {
  stroke: var(--bootui-blue);
  stroke-width: 3;
}

.bg-node:focus-visible .bg-focus-ring {
  opacity: 1;
}

.bg-node text {
  font-family: var(--bs-font-monospace, ui-monospace, monospace);
  pointer-events: none;
  user-select: none;
}

/* ── Node role colours ──────────────────────────────────────────────────────── */
/* Focus */
.bg-node--focus .bg-node-shape {
  fill: var(--bean-graph-focus);
  stroke: var(--bean-graph-focus);
  stroke-width: 1.5;
}
.bg-node--focus text {
  fill: #fff;
  font-weight: 600;
}

/* Direct dependency (focus → node) */
.bg-node--dep .bg-node-shape {
  fill: color-mix(in srgb, var(--bootui-blue) 14%, transparent);
  stroke: var(--bootui-blue);
  stroke-width: 1.5;
}
.bg-node--dep text {
  fill: var(--bootui-text);
}

/* Direct dependent (node → focus) */
.bg-node--rdep .bg-node-shape {
  fill: color-mix(in srgb, var(--bootui-green) 14%, transparent);
  stroke: var(--bootui-green);
  stroke-width: 1.5;
}
.bg-node--rdep text {
  fill: var(--bootui-green-dark);
}

/* Mutual (dep AND rdep at depth 1) */
.bg-node--both .bg-node-shape {
  fill: color-mix(in srgb, var(--bootui-warning) 20%, transparent);
  stroke: var(--bootui-warning-text-strong);
  stroke-width: 1.5;
}
.bg-node--both text {
  fill: var(--bootui-warning-text-strong);
}

/* Deeper hop */
.bg-node--deep .bg-node-shape {
  fill: var(--bootui-surface);
  stroke: var(--bean-graph-edge);
  stroke-width: 1.5;
}
.bg-node--deep text {
  fill: var(--bootui-text-muted);
}

/* ── Dark-mode overrides ─────────────────────────────────────────────────────── */
:global(:root[data-bootui-theme='dark']) .bg-node--dep .bg-node-shape {
  fill: color-mix(in srgb, var(--bootui-blue) 16%, transparent);
  stroke: var(--bootui-blue);
}
:global(:root[data-bootui-theme='dark']) .bg-node--dep text {
  fill: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark']) .bg-node--rdep .bg-node-shape {
  fill: color-mix(in srgb, var(--bootui-green) 16%, transparent);
  stroke: var(--bootui-green);
}
:global(:root[data-bootui-theme='dark']) .bg-node--rdep text {
  fill: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark']) .bg-node--both .bg-node-shape {
  fill: color-mix(in srgb, var(--bootui-warning-text-strong) 16%, transparent);
  stroke: var(--bootui-warning-text-strong);
}
:global(:root[data-bootui-theme='dark']) .bg-node--both text {
  fill: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark']) .bg-node--deep .bg-node-shape {
  fill: var(--bootui-surface-alt);
  stroke: #94a3b8;
}
:global(:root[data-bootui-theme='dark']) .bg-node--deep text {
  fill: var(--bootui-text-muted);
}

/* ── Legend ─────────────────────────────────────────────────────────────────── */
.bg-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
}

.bg-legend-item {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.bg-legend-swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: var(--bootui-radius-xs);
  border: 1.5px solid transparent;
}

.bg-legend-swatch--focus {
  background: var(--bean-graph-focus);
  border-color: var(--bean-graph-focus);
}
.bg-legend-swatch--dep {
  background: color-mix(in srgb, var(--bootui-blue) 14%, transparent);
  border-color: var(--bootui-blue);
}
.bg-legend-swatch--rdep {
  background: color-mix(in srgb, var(--bootui-green) 14%, transparent);
  border-color: var(--bootui-green);
}
.bg-legend-swatch--both {
  background: color-mix(in srgb, var(--bootui-warning) 20%, transparent);
  border-color: var(--bootui-warning-text-strong);
}
.bg-legend-swatch--deep {
  background: var(--bootui-surface);
  border-color: var(--bean-graph-edge);
}

:global(:root[data-bootui-theme='dark']) .bg-legend-swatch--dep {
  background: color-mix(in srgb, var(--bootui-blue) 16%, transparent);
  border-color: var(--bootui-blue);
}
:global(:root[data-bootui-theme='dark']) .bg-legend-swatch--rdep {
  background: color-mix(in srgb, var(--bootui-green) 16%, transparent);
  border-color: var(--bootui-green);
}
:global(:root[data-bootui-theme='dark']) .bg-legend-swatch--both {
  background: color-mix(in srgb, var(--bootui-warning-text-strong) 16%, transparent);
  border-color: var(--bootui-warning-text-strong);
}

:global(:root[data-bootui-theme='dark']) .bean-graph-wrap {
  --bean-graph-edge: #94a3b8;
}

@media (forced-colors: active) {
  .bg-focus-ring--outer,
  .bg-focus-ring--inner {
    stroke: Highlight;
  }
}

.bg-legend-arrow-note {
  margin-left: auto;
  font-size: 0.85rem;
}
</style>
