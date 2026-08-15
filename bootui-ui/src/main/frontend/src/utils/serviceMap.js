/**
 * Pure helpers behind Live Activity's Live Flow map.
 *
 * Everything here is deliberately framework-free and side-effect-free so the map's interpretation,
 * layout, and motion rules can be unit tested without a DOM: the Vue component only wires these
 * functions to markup.
 *
 * The motion model is the important part. The map never animates on its own; it only animates
 * *newly observed, already completed* evidence. A pulse is emitted when, and only when:
 *
 *   1. an edge existed in the previous snapshot and still exists in the next one (a stable edge), and
 *   2. that edge's newest-first interaction tail contains an interaction id the previous snapshot did
 *      not carry.
 *
 * A first load therefore produces no motion, a brand-new dependency appears without a pulse, and an
 * idle application stays completely still.
 *
 * Causal sequencing builds on that model rather than replacing it: when several fresh pulses share the
 * server-derived, opaque `flowId` (see `sequenceFlowPulses`), they are evidence of one request's actual
 * path through the application. Inbound HTTP arrives first; downstream completions then replay in their
 * retained timestamp order (with cache before JDBC/HTTP only as the deterministic same-millisecond
 * tie-break), so their *start* reads as one causal story instead of simultaneous, unrelated blips. Motion
 * still only ever depicts evidence that already completed; sequencing never changes the evidence, only
 * how its replay is paced.
 */

export const PROTOCOL_HTTP_INBOUND = 'HTTP_INBOUND'
export const PROTOCOL_HTTP = 'HTTP'
export const PROTOCOL_JDBC = 'JDBC'
export const PROTOCOL_KAFKA = 'KAFKA'
export const PROTOCOL_RABBITMQ = 'RABBITMQ'
export const PROTOCOL_CACHE = 'CACHE'

export const PROTOCOL_LABELS = {
  APPLICATION: 'Application',
  [PROTOCOL_HTTP_INBOUND]: 'Incoming HTTP',
  [PROTOCOL_HTTP]: 'HTTP',
  [PROTOCOL_JDBC]: 'JDBC',
  [PROTOCOL_KAFKA]: 'Kafka',
  [PROTOCOL_RABBITMQ]: 'RabbitMQ',
  [PROTOCOL_CACHE]: 'Cache'
}

export const PROTOCOL_ICONS = {
  APPLICATION: 'bi-box-seam',
  [PROTOCOL_HTTP_INBOUND]: 'bi-box-arrow-in-right',
  [PROTOCOL_HTTP]: 'bi-globe2',
  [PROTOCOL_JDBC]: 'bi-database',
  [PROTOCOL_KAFKA]: 'bi-broadcast-pin',
  [PROTOCOL_RABBITMQ]: 'bi-diagram-3',
  [PROTOCOL_CACHE]: 'bi-lightning-charge'
}

/**
 * Outcome copy. Deliberately describes retained evidence, never remote health: BootUI has not
 * contacted any of these systems, it has only re-read what already happened.
 */
export const OUTCOME_LABELS = {
  NO_EVIDENCE: 'No recent evidence',
  OBSERVED_OK: 'Recent activity completed',
  RETAINED_FAILURES: 'Recent failures retained'
}

export const OUTCOME_ICONS = {
  NO_EVIDENCE: 'bi-dash-circle',
  OBSERVED_OK: 'bi-check-circle',
  RETAINED_FAILURES: 'bi-exclamation-triangle'
}

/** Above this, a completed interaction is drawn as a slow (amber) pulse rather than a normal one. */
export const SLOW_INTERACTION_MS = 500

/** Bounds on motion, so a traffic burst can never turn the map into a fireworks display. */
export const MAX_CONCURRENT_PULSES = 6
export const MAX_PULSES_PER_EDGE = 2
export const REDUCED_MOTION_HIGHLIGHT_MS = 1200

/**
 * Per-tone pulse travel durations. Slow is deliberately the longest and most unmistakable (a calm amber
 * comet, never a flash), failed is a shorter, firmer beat, and a normal completion is the briskest of the
 * three - the timing itself is part of what makes "slow" legible without relying on color alone.
 */
export const PULSE_DURATION_OK_MS = 750
export const PULSE_DURATION_SLOW_MS = 1350
export const PULSE_DURATION_FAILED_MS = 1000
/** Back-compat alias equal to the normal-tone duration; existing call sites keep working unchanged. */
export const PULSE_DURATION_MS = PULSE_DURATION_OK_MS

/** The travel duration for one pulse, keyed by the tone `pulseTone` classified it into. */
export function pulseDurationMs(tone) {
  if (tone === 'slow') return PULSE_DURATION_SLOW_MS
  if (tone === 'failed') return PULSE_DURATION_FAILED_MS
  return PULSE_DURATION_OK_MS
}

/**
 * Deterministic protocol precedence for interactions whose retained completion timestamps are identical.
 * Inbound is always first; cache precedes JDBC/HTTP only for a same-millisecond tie. The timestamp remains
 * authoritative so the replay never invents a cache-before-database order that the completed evidence does
 * not support. Kafka/RabbitMQ never carry a flowId, so they do not reach this lookup in practice.
 */
function flowStage(protocol) {
  if (protocol === PROTOCOL_HTTP_INBOUND) return 0
  if (protocol === PROTOCOL_CACHE) return 1
  return 2
}

/** Truthful replay order: inbound first, then observed completion time, then a stable protocol tie-break. */
function compareFlowPulses(a, b) {
  const stageDifference = flowStage(a?.protocol) - flowStage(b?.protocol)
  if (flowStage(a?.protocol) === 0 || flowStage(b?.protocol) === 0) return stageDifference
  const timestampDifference = (a?.interaction?.timestamp ?? 0) - (b?.interaction?.timestamp ?? 0)
  return timestampDifference || stageDifference || String(a?.id ?? '').localeCompare(String(b?.id ?? ''))
}

/** Small, bounded stagger between same-flow downstream pulses so a fan-out reads as distinguishable beats. */
export const FLOW_STAGE_STAGGER_MS = 90
export const MAX_FLOW_STAGGER_STEPS = 3

/** Normalizes a server report into the shape the map renders, tolerating partial payloads. */
export function normalizeServiceMap(report) {
  const nodes = Array.isArray(report?.nodes) ? report.nodes.filter(Boolean) : []
  const edges = Array.isArray(report?.edges) ? report.edges.filter(Boolean) : []
  return {
    available: report?.available === true,
    unavailableReason: report?.unavailableReason ?? null,
    generatedAt: report?.generatedAt ?? null,
    application: report?.application ?? null,
    nodes,
    edges,
    truncation: report?.truncation ?? null,
    sources: Array.isArray(report?.sources) ? report.sources : [],
    warnings: Array.isArray(report?.warnings) ? report.warnings : []
  }
}

/**
 * Applies the protocol and free-text filters. The application node is never filtered away — it is the
 * anchor every edge is drawn from — and an edge survives only while both of its endpoints do.
 */
export function filterServiceMap(map, {protocol = '', text = ''} = {}) {
  const needle = String(text || '')
    .trim()
    .toLowerCase()
  const nodes = map.nodes.filter((node) => {
    if (protocol && node.protocol !== protocol) return false
    if (!needle) return true
    return [node.label, node.detail, node.sourceLabel, PROTOCOL_LABELS[node.protocol]]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle))
  })
  const visibleIds = new Set(nodes.map((node) => node.id))
  if (map.application?.id) visibleIds.add(map.application.id)
  const edges = map.edges.filter((edge) => visibleIds.has(edge.fromId) && visibleIds.has(edge.toId))
  return {...map, nodes, edges}
}

/** Splits the visible nodes into the single inbound lane and the outbound dependencies. */
export function partitionNodes(map) {
  const inbound = map.nodes.find((node) => node.kind === 'INBOUND') ?? null
  const dependencies = map.nodes.filter((node) => node.kind === 'DEPENDENCY')
  return {inbound, dependencies}
}

const APP_W = 172
const APP_H = 60
const NODE_W = 196
const NODE_H = 46
const MARGIN = 32
const LANE_GAP = 112
const FAN_RADIUS = 288
const FAN_ROW_PITCH = 72
const FAN_MIN_HEIGHT = 320
const FAN_MIN_WIDTH = 800
const RACK_GAP = 72
const RACK_COLUMN_GAP = 32
const RACK_ROW_GAP = 26
const RACK_MIN_HEIGHT = 320
export const FAN_DEPENDENCY_THRESHOLD = 6
const EDGE_SOURCE_GAP = 4
const EDGE_TARGET_GAP = 8
const TARGET_RING_PADDING = 5
const TARGET_CHIP_WIDTH = 96
const TARGET_CHIP_HEIGHT = 20
const TARGET_CHIP_Y = -13
export const MAX_SERVICE_MAP_WIDTH = 1040

function boxRect(box) {
  return {
    left: box.x - box.w / 2,
    top: box.y - box.h / 2,
    right: box.x + box.w / 2,
    bottom: box.y + box.h / 2
  }
}

/**
 * Bounding rectangle reserved for a node's transient ring and SLOW/ERROR chip. Keeping this pure makes
 * the row-pitch contract executable: no transient decoration may intrude into any neighbouring node.
 */
export function transientTargetBounds(box) {
  const node = boxRect(box)
  const chipCenterX = box.x
  const chipCenterY = node.top + TARGET_CHIP_Y
  return {
    left: Math.min(node.left - TARGET_RING_PADDING, chipCenterX - TARGET_CHIP_WIDTH / 2),
    top: Math.min(node.top - TARGET_RING_PADDING, chipCenterY - TARGET_CHIP_HEIGHT / 2),
    right: Math.max(node.right + TARGET_RING_PADDING, chipCenterX + TARGET_CHIP_WIDTH / 2),
    bottom: node.bottom + TARGET_RING_PADDING
  }
}

function pathFromPoints(points) {
  return points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' ')
}

function cubicPoint(start, control1, control2, end, progress) {
  const inverse = 1 - progress
  return {
    x:
      inverse ** 3 * start.x +
      3 * inverse ** 2 * progress * control1.x +
      3 * inverse * progress ** 2 * control2.x +
      progress ** 3 * end.x,
    y:
      inverse ** 3 * start.y +
      3 * inverse ** 2 * progress * control1.y +
      3 * inverse * progress ** 2 * control2.y +
      progress ** 3 * end.y
  }
}

function curvedPath(start, end) {
  const travel = end.x - start.x
  const control1 = {x: start.x + travel * 0.42, y: start.y}
  const control2 = {x: end.x - travel * 0.38, y: end.y}
  return cubicPath(start, control1, control2, end)
}

function cubicPath(start, control1, control2, end) {
  const points = Array.from({length: 25}, (unused, index) => cubicPoint(start, control1, control2, end, index / 24))
  const path = `M ${start.x} ${start.y} C ${control1.x} ${control1.y} ${control2.x} ${control2.y} ${end.x} ${end.y}`
  return {points, path, animationPath: path}
}

function segmentCrossesRectangle(start, end, rectangle) {
  const axisInterval = (origin, delta, minimum, maximum) => {
    if (Math.abs(delta) < 0.0001) return origin > minimum && origin < maximum ? [-Infinity, Infinity] : null
    const first = (minimum - origin) / delta
    const second = (maximum - origin) / delta
    return [Math.min(first, second), Math.max(first, second)]
  }
  const xInterval = axisInterval(start.x, end.x - start.x, rectangle.left, rectangle.right)
  const yInterval = axisInterval(start.y, end.y - start.y, rectangle.top, rectangle.bottom)
  if (!xInterval || !yInterval) return false
  return Math.max(0, xInterval[0], yInterval[0]) < Math.min(1, xInterval[1], yInterval[1])
}

function shortestClearRoute(start, end, obstacles) {
  const clearance = 4
  const rectangles = obstacles.map((box) => {
    const rectangle = boxRect(box)
    return {
      left: rectangle.left - clearance,
      top: rectangle.top - clearance,
      right: rectangle.right + clearance,
      bottom: rectangle.bottom + clearance
    }
  })
  const nodes = [
    start,
    end,
    ...rectangles.flatMap((rectangle) => [
      {x: rectangle.left, y: rectangle.top},
      {x: rectangle.right, y: rectangle.top},
      {x: rectangle.right, y: rectangle.bottom},
      {x: rectangle.left, y: rectangle.bottom}
    ])
  ]
  const visible = (left, right) => rectangles.every((rectangle) => !segmentCrossesRectangle(left, right, rectangle))
  const distances = Array(nodes.length).fill(Number.POSITIVE_INFINITY)
  const previous = Array(nodes.length).fill(-1)
  const visited = new Set()
  distances[0] = 0

  while (visited.size < nodes.length) {
    let current = -1
    for (let index = 0; index < nodes.length; index += 1) {
      if (!visited.has(index) && (current < 0 || distances[index] < distances[current])) current = index
    }
    if (current < 0 || !Number.isFinite(distances[current]) || current === 1) break
    visited.add(current)
    for (let next = 0; next < nodes.length; next += 1) {
      if (visited.has(next) || next === current || !visible(nodes[current], nodes[next])) continue
      const candidate =
        distances[current] + Math.hypot(nodes[next].x - nodes[current].x, nodes[next].y - nodes[current].y)
      if (candidate < distances[next]) {
        distances[next] = candidate
        previous[next] = current
      }
    }
  }

  const points = []
  for (let current = 1; current >= 0; current = previous[current]) {
    points.unshift(nodes[current])
    if (current === 0) break
  }
  if (points[0] !== start)
    return {points: [start, end], path: pathFromPoints([start, end]), animationPath: pathFromPoints([start, end])}
  const path = pathFromPoints(points)
  return {points, path, animationPath: path}
}

/**
 * Routes one dense-rack edge around reserved node rectangles.
 *
 * First-column dependencies use a simple curve. Second-column edges use a deterministic visibility graph
 * around every unrelated node and take its shortest clear route.
 */
function routeRackEdge(edge, from, to, {application, dependencies, columnCount}) {
  const fromRect = boxRect(from)
  const toRect = boxRect(to)
  let points

  if (edge.direction === 'INBOUND') {
    points = [
      {x: fromRect.right + EDGE_SOURCE_GAP, y: from.y},
      {x: toRect.left - EDGE_TARGET_GAP, y: to.y}
    ]
  } else if (from === application) {
    const start = {x: fromRect.right + EDGE_SOURCE_GAP, y: from.y}
    const end = {x: toRect.left - EDGE_TARGET_GAP, y: to.y}

    if (columnCount === 2 && to.column === 1) {
      const obstacles = dependencies.filter((box) => box !== to)
      return shortestClearRoute(start, end, obstacles)
    } else {
      return curvedPath(start, end)
    }
  } else {
    const dx = to.x - from.x
    const dy = to.y - from.y
    const distance = Math.sqrt(dx * dx + dy * dy)
    if (distance < 1) return null
    const ux = dx / distance
    const uy = dy / distance
    const start = trim(from, ux, uy, EDGE_SOURCE_GAP)
    const end = trim(to, ux, uy, EDGE_TARGET_GAP)
    points = [
      {x: from.x + ux * start, y: from.y + uy * start},
      {x: to.x - ux * end, y: to.y - uy * end}
    ]
  }

  const path = pathFromPoints(points)
  return {points, path, animationPath: path}
}

function routeFanEdge(edge, from, to, application) {
  const fromRect = boxRect(from)
  const toRect = boxRect(to)
  if (edge.direction === 'INBOUND') {
    return curvedPath({x: fromRect.right + EDGE_SOURCE_GAP, y: from.y}, {x: toRect.left - EDGE_TARGET_GAP, y: to.y})
  }
  if (from === application) {
    return curvedPath({x: fromRect.right + EDGE_SOURCE_GAP, y: from.y}, {x: toRect.left - EDGE_TARGET_GAP, y: to.y})
  }

  const dx = to.x - from.x
  const dy = to.y - from.y
  const distance = Math.sqrt(dx * dx + dy * dy)
  if (distance < 1) return null
  const ux = dx / distance
  const uy = dy / distance
  const start = trim(from, ux, uy, EDGE_SOURCE_GAP)
  const end = trim(to, ux, uy, EDGE_TARGET_GAP)
  return curvedPath({x: from.x + ux * start, y: from.y + uy * start}, {x: to.x - ux * end, y: to.y - uy * end})
}

function fanLayout(dependencies, applicationX, centerY, nodeWidth, nodeHeight) {
  const middle = (dependencies.length - 1) / 2
  return dependencies.map((node, index) => {
    const verticalOffset = (index - middle) * FAN_ROW_PITCH
    const horizontalOffset = Math.sqrt(Math.max(0, FAN_RADIUS ** 2 - verticalOffset ** 2))
    return {
      node,
      column: 0,
      row: index,
      x: applicationX + horizontalOffset,
      y: centerY + verticalOffset,
      w: nodeWidth,
      h: nodeHeight
    }
  })
}

function rackLayout(dependencies, firstRackX, nodeWidth, nodeHeight) {
  return dependencies.map((node, index) => {
    const column = index % 2
    const row = Math.floor(index / 2)
    return {
      node,
      column,
      row,
      x: firstRackX + column * (nodeWidth + RACK_COLUMN_GAP),
      y: MARGIN + nodeHeight / 2 + row * (nodeHeight + RACK_ROW_GAP),
      w: nodeWidth,
      h: nodeHeight
    }
  })
}

/**
 * Places the inbound lane, application hub, and outbound dependencies in a bounded hybrid topology.
 * Up to six dependencies form an airy right-facing fan; denser maps switch to a spacious two-column
 * rack. Both modes keep width bounded and reserve the full transient target envelope.
 */
export function layoutServiceMap(map, {nodeWidth = NODE_W, nodeHeight = NODE_H} = {}) {
  const {inbound, dependencies} = partitionNodes(map)
  const count = dependencies.length
  const dense = count > FAN_DEPENDENCY_THRESHOLD
  const rowCount = dense ? Math.ceil(count / 2) : count
  const contentHeight = dense
    ? rowCount * nodeHeight + Math.max(0, rowCount - 1) * RACK_ROW_GAP
    : count
      ? (count - 1) * FAN_ROW_PITCH + nodeHeight
      : APP_H
  const height = Math.max(dense ? RACK_MIN_HEIGHT : FAN_MIN_HEIGHT, contentHeight + MARGIN * 2)
  const cy = height / 2
  // Keep the application visually central even when filtering hides the inbound lane.
  const cx = MARGIN + nodeWidth + LANE_GAP + APP_W / 2
  const firstRackX = cx + APP_W / 2 + RACK_GAP + nodeWidth / 2

  const positions = new Map()
  const application = map.application ? {node: map.application, x: cx, y: cy, w: APP_W, h: APP_H} : null
  if (application) positions.set(application.node.id, application)

  let inboundBox = null
  if (inbound) {
    inboundBox = {node: inbound, x: MARGIN + nodeWidth / 2, y: cy, w: nodeWidth, h: nodeHeight}
    positions.set(inbound.id, inboundBox)
  }

  const dependencyBoxes = dense
    ? rackLayout(dependencies, firstRackX, nodeWidth, nodeHeight)
    : fanLayout(dependencies, cx, cy, nodeWidth, nodeHeight)
  for (const box of dependencyBoxes) {
    positions.set(box.node.id, box)
  }

  const furthestRight = Math.max(cx + APP_W / 2, ...dependencyBoxes.map((box) => box.x + box.w / 2))
  const width = dense ? MAX_SERVICE_MAP_WIDTH : Math.max(FAN_MIN_WIDTH, Math.ceil(furthestRight + MARGIN))

  const edges = map.edges
    .map((edge) => {
      const from = positions.get(edge.fromId)
      const to = positions.get(edge.toId)
      if (!from || !to) return null
      const route = dense
        ? routeRackEdge(edge, from, to, {
            application,
            dependencies: dependencyBoxes,
            columnCount: 2
          })
        : routeFanEdge(edge, from, to, application)
      if (!route) return null
      return {
        edge,
        id: edge.id,
        ...route
      }
    })
    .filter(Boolean)

  return {
    width: Math.round(width),
    height: Math.round(height),
    application,
    inbound: inboundBox,
    dependencies: dependencyBoxes,
    edges,
    mode: dense ? 'rack' : 'fan'
  }
}

/** Distance from a box centre to its border along the given unit vector, plus a small gap. */
function trim(box, ux, uy, gap) {
  const horizontal = Math.abs(ux) > 0.0001 ? box.w / 2 / Math.abs(ux) : Number.POSITIVE_INFINITY
  const vertical = Math.abs(uy) > 0.0001 ? box.h / 2 / Math.abs(uy) : Number.POSITIVE_INFINITY
  return Math.min(horizontal, vertical) + gap
}

/** Classifies one completed interaction into the tone its pulse is drawn with. */
export function pulseTone(interaction) {
  if (!interaction) return 'ok'
  if (interaction.outcome === 'FAILED') return 'failed'
  if (interaction.durationMs != null && interaction.durationMs >= SLOW_INTERACTION_MS) return 'slow'
  return 'ok'
}

/**
 * Finds the completed interactions that are new since the previous snapshot.
 *
 * Only stable edges are considered, and only the capped interaction tail is compared, so the amount of
 * motion is bounded by the contract itself rather than by how much traffic the application handled. Each
 * pulse carries the edge's `protocol` (used to order a causal sequence) and a per-tone `durationMs` (see
 * `pulseDurationMs`), both consumed by `sequenceFlowPulses` and the queue below.
 */
export function diffFlowPulses(previousEdges, nextEdges, {maxPerEdge = MAX_PULSES_PER_EDGE} = {}) {
  if (!Array.isArray(previousEdges) || !previousEdges.length || !Array.isArray(nextEdges)) return []
  const previousById = new Map(previousEdges.map((edge) => [edge.id, edge]))
  const pulses = []
  for (const edge of nextEdges) {
    const previous = previousById.get(edge.id)
    // A brand-new edge is not animated: its arrival is already the visible change.
    if (!previous) continue
    const seen = new Set((previous.recentInteractions ?? []).map((interaction) => interaction.id))
    const fresh = (edge.recentInteractions ?? []).filter((interaction) => !seen.has(interaction.id))
    for (const interaction of fresh.slice(0, maxPerEdge)) {
      const tone = pulseTone(interaction)
      pulses.push({
        id: `${edge.id}#${interaction.id}`,
        edgeId: edge.id,
        direction: edge.direction,
        fromId: edge.fromId,
        toId: edge.toId,
        protocol: edge.protocol,
        tone,
        durationMs: pulseDurationMs(tone),
        interaction
      })
    }
  }
  return pulses
}

/** Returns the non-application endpoint represented by a directional service-map edge. */
export function externalEndpointId(edge) {
  return edge?.direction === 'INBOUND' ? edge.fromId : edge?.toId
}

/** The node that causally receives temporary slow/failure evidence from a pulse. */
export function pulseTargetId(pulse, applicationId = 'app') {
  if (!pulse) return null
  return pulse.direction === 'INBOUND' ? pulse.toId || applicationId : pulse.toId
}

/** Non-color text displayed beside a target while exceptional evidence is in flight. */
export function pulseTargetLabel(pulse) {
  if (pulse?.tone === 'failed') return 'ERROR'
  if (pulse?.tone !== 'slow') return ''
  const durationMs = pulse.interaction?.durationMs
  if (durationMs == null) return 'SLOW'
  const seconds = (durationMs / 1000).toFixed(durationMs % 1000 === 0 ? 0 : 1)
  return `SLOW · ${seconds} s`
}

/**
 * Sequences a batch of freshly diffed pulses into a causal story, per flow.
 *
 * Pulses that share a server-derived `interaction.flowId` are evidence of one request's actual path
 * through the application. Inbound HTTP reaches the app first. Downstream interactions replay in ascending
 * retained completion-time order, using cache-before-JDBC/HTTP only as a deterministic tie-break when
 * timestamps are equal - the truthful order documented in `docs/SPECIFICATION.md`. Exactly one inbound
 * pulse *retained in this very batch* is required to anchor that sequence: everything downstream of it
 * starts once it has finished arriving (its own `durationMs`), then downstream pulses are staggered by a
 * small, bounded step so several of them do not all start in the same instant.
 *
 * Every other pulse is untouched:
 *
 *   - a pulse with no `flowId` is not part of any flow and is never delayed;
 *   - a flow whose batch carries no inbound pulse (the common case once the inbound leg has already
 *     scrolled out of the retained tail) never delays its downstream pulses either - they fire
 *     immediately rather than waiting for an inbound arrival this batch will never carry;
 *   - a flow with multiple inbound pulses is ambiguous and remains entirely immediate rather than choosing
 *     an arbitrary inbound pulse and inventing causal delays;
 *   - a flow's own inbound pulse always starts immediately, since it is the first stage.
 *
 * Returns the same pulses, each with a `startDelayMs` (`0` unless actually sequenced) that the caller uses
 * to pace admission into the animation queue.
 */
export function sequenceFlowPulses(
  pulses,
  {stageDelayMs = null, staggerMs = FLOW_STAGE_STAGGER_MS, maxStaggerSteps = MAX_FLOW_STAGGER_STEPS} = {}
) {
  if (!Array.isArray(pulses) || !pulses.length) return Array.isArray(pulses) ? pulses : []

  const groups = new Map()
  for (const pulse of pulses) {
    const flowId = pulse?.interaction?.flowId
    if (!flowId) continue
    if (!groups.has(flowId)) groups.set(flowId, [])
    groups.get(flowId).push(pulse)
  }

  const delayById = new Map()
  for (const group of groups.values()) {
    const inboundPulses = group.filter((pulse) => pulse.direction === 'INBOUND')
    if (inboundPulses.length !== 1) continue
    const [inbound] = inboundPulses
    const arrival = stageDelayMs ?? inbound.durationMs ?? PULSE_DURATION_OK_MS
    const downstream = [...group].filter((pulse) => pulse !== inbound).sort(compareFlowPulses)
    downstream.forEach((pulse, index) => {
      delayById.set(pulse.id, arrival + staggerMs * Math.min(index, maxStaggerSteps))
    })
  }

  return pulses.map((pulse) => ({...pulse, startDelayMs: delayById.get(pulse.id) ?? 0}))
}

/** Renders one causal step for `describeFlowSequence`'s complete flow narration. */
function describeFlowStep(pulse, nodesById) {
  const endpointId = externalEndpointId(pulse)
  const label = nodesById?.get?.(endpointId)?.label ?? endpointId
  const operation = pulse.interaction?.operation ?? ''
  const slow = pulseTone(pulse.interaction) === 'slow' ? ' slow' : ''
  const failed = pulse.interaction?.outcome === 'FAILED' ? ' failed' : ''
  const duration = pulse.interaction?.durationMs != null ? ` (${pulse.interaction.durationMs} ms)` : ''
  return `${label} ${operation}${slow}${failed}${duration}`.replace(/\s+/g, ' ').trim()
}

/**
 * Narrates every qualifying flow's complete causal chain, for screen-reader users who cannot see the
 * sequenced motion. A "slow" step is called out by name - never by color alone - matching the same
 * non-color labelling used in the map's node detail view.
 *
 * Only flows with at least two pulses in this batch produce a sentence: a single event alone is not a
 * "flow" worth narrating and is already covered by the generic summary in `describeNewEvidence`.
 */
export function describeFlowSequence(pulses, nodesById) {
  if (!Array.isArray(pulses) || !pulses.length) return []
  const groups = new Map()
  for (const pulse of pulses) {
    const flowId = pulse?.interaction?.flowId
    if (!flowId) continue
    if (!groups.has(flowId)) groups.set(flowId, [])
    groups.get(flowId).push(pulse)
  }
  const sentences = []
  for (const group of groups.values()) {
    if (group.length < 2) continue
    const ordered = [...group].sort(compareFlowPulses)
    sentences.push(`Flow: ${ordered.map((pulse) => describeFlowStep(pulse, nodesById)).join(' → ')}.`)
  }
  return sentences
}

/** A short, human sentence describing new evidence, for the map's polite live region. */
export function describeNewEvidence(pulses, nodesById) {
  if (!pulses.length) return ''
  const byEdge = new Map()
  for (const pulse of pulses) {
    byEdge.set(pulse.edgeId, (byEdge.get(pulse.edgeId) ?? 0) + 1)
  }
  const parts = []
  for (const [edgeId, count] of byEdge) {
    const pulse = pulses.find((candidate) => candidate.edgeId === edgeId)
    const endpointId = externalEndpointId(pulse)
    const label = nodesById?.get?.(endpointId)?.label ?? endpointId
    parts.push(`${count} on ${label}`)
  }
  const failures = pulses.filter((pulse) => pulse.tone === 'failed').length
  const slow = pulses.filter((pulse) => pulse.tone === 'slow').length
  // Non-color callouts: "slow" and "failed" are always named, never implied by amber/red alone.
  const notes = []
  if (failures) notes.push(`${failures} failed`)
  if (slow) notes.push(`${slow} slow`)
  const exceptionalTargets = pulses
    .filter((pulse) => pulse.tone === 'failed' || pulse.tone === 'slow')
    .map((pulse) => {
      const targetId = pulseTargetId(pulse)
      const target =
        nodesById?.get?.(targetId)?.label ?? (pulse.direction === 'INBOUND' ? 'This application' : targetId)
      const duration = pulse.interaction?.durationMs
      const timing = duration == null ? '' : ` (${duration} ms)`
      return `${target} ${pulse.tone === 'failed' ? 'failed' : 'slow'}${timing}`
    })
  const suffix = notes.length ? `, including ${notes.join(', ')}: ${exceptionalTargets.join('; ')}` : ''
  const generic = `New completed interactions: ${parts.join(', ')}${suffix}.`
  const flowSentences = describeFlowSequence(pulses, nodesById)
  return [...flowSentences, generic].join(' ')
}

/**
 * A bounded animation queue.
 *
 * Bursts are coalesced rather than buffered: anything over {@link MAX_CONCURRENT_PULSES} in flight is
 * dropped instead of queued, so motion can never lag behind reality or keep running after traffic
 * stops. Each accepted pulse is admitted to `active` immediately (so the concurrency cap is reserved and
 * `active()` stays an honest picture of everything in flight, sequenced or not) and releases itself after
 * its own `startDelayMs + durationMs` - the delay itself is a purely visual concern the CSS animation layer owns
 * (the pulse renders invisible for its `startDelayMs`, matching `sequenceFlowPulses`'s causal pacing)
 * rather than a second bookkeeping phase in here, so the queue's bounds and "no stale backlog" guarantee
 * never depend on whether any given pulse happens to be sequenced.
 */
export function createFlowQueue({
  maxConcurrent = MAX_CONCURRENT_PULSES,
  duration = PULSE_DURATION_OK_MS,
  schedule = (callback, delay) => setTimeout(callback, delay),
  cancel = (handle) => clearTimeout(handle)
} = {}) {
  let active = []
  const timers = new Map()
  const listeners = new Set()

  function notify() {
    for (const listener of listeners) listener(active)
  }

  function release(id) {
    const handle = timers.get(id)
    if (handle !== undefined) {
      cancel(handle)
      timers.delete(id)
    }
    const next = active.filter((pulse) => pulse.id !== id)
    if (next.length !== active.length) {
      active = next
      notify()
    }
  }

  return {
    /** Accepts as many pulses as the concurrency cap allows and drops the rest. */
    enqueue(pulses) {
      if (!Array.isArray(pulses) || !pulses.length) return []
      const known = new Set(active.map((pulse) => pulse.id))
      const room = Math.max(0, maxConcurrent - active.length)
      const accepted = pulses.filter((pulse) => !known.has(pulse.id)).slice(0, room)
      if (!accepted.length) return []
      active = [...active, ...accepted]
      for (const pulse of accepted) {
        const startDelayMs = Math.max(0, pulse.startDelayMs ?? 0)
        const pulseDuration = pulse.durationMs ?? duration
        timers.set(
          pulse.id,
          schedule(() => release(pulse.id), startDelayMs + pulseDuration)
        )
      }
      notify()
      return accepted
    },
    active: () => active,
    release,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    /**
     * Cancels everything in flight. Subscriptions survive on purpose: this is also called when the OS
     * reduced-motion preference is switched on, and dropping the subscriber there would silently freeze
     * the map's motion for the rest of the component's life.
     */
    clear() {
      for (const handle of timers.values()) cancel(handle)
      timers.clear()
      if (active.length) {
        active = []
        notify()
      }
    }
  }
}

/**
 * Schedules temporary target evidence in lockstep with accepted pulse CSS timing.
 *
 * Entries are reference-counted by pulse id, so overlapping evidence on one target cannot clear another
 * pulse early. Failure wins while present; when it finishes, an overlapping slow state becomes visible
 * for the remainder of its own window. Normal pulses intentionally create no target state.
 */
export function createTransientTargetStateManager({
  applicationId = 'app',
  maxConcurrent = MAX_CONCURRENT_PULSES,
  schedule = (callback, delay) => setTimeout(callback, delay),
  cancel = (handle) => clearTimeout(handle)
} = {}) {
  const entries = new Map()
  const listeners = new Set()

  function snapshot() {
    const byTarget = new Map()
    for (const entry of entries.values()) {
      if (!entry.active) continue
      if (!byTarget.has(entry.targetId)) byTarget.set(entry.targetId, [])
      byTarget.get(entry.targetId).push(entry.pulse)
    }
    return [...byTarget].map(([targetId, pulses]) => {
      const failed = pulses.filter((pulse) => pulse.tone === 'failed')
      const slow = pulses.filter((pulse) => pulse.tone === 'slow')
      const tone = failed.length ? 'failed' : 'slow'
      const representative = tone === 'failed' ? failed[failed.length - 1] : slow[slow.length - 1]
      return {targetId, tone, label: pulseTargetLabel(representative), count: pulses.length}
    })
  }

  function notify() {
    const state = snapshot()
    for (const listener of listeners) listener(state)
  }

  function release(id) {
    const entry = entries.get(id)
    if (!entry) return
    if (entry.startHandle !== undefined) cancel(entry.startHandle)
    if (entry.endHandle !== undefined) cancel(entry.endHandle)
    entries.delete(id)
    if (entry.active) notify()
  }

  function start(entry) {
    if (!entries.has(entry.pulse.id)) return
    entry.active = true
    notify()
  }

  return {
    enqueue(pulses, {durationOverride = null, immediate = false} = {}) {
      const accepted = []
      for (const pulse of pulses ?? []) {
        if (!['failed', 'slow'].includes(pulse?.tone) || entries.has(pulse.id)) continue
        if (entries.size >= maxConcurrent) break
        const targetId = pulseTargetId(pulse, applicationId)
        if (!targetId) continue
        const startDelayMs = immediate ? 0 : Math.max(0, pulse.startDelayMs ?? 0)
        const durationMs = Math.max(0, durationOverride ?? pulse.durationMs ?? PULSE_DURATION_OK_MS)
        const entry = {pulse, targetId, active: false, startHandle: undefined, endHandle: undefined}
        entries.set(pulse.id, entry)
        if (startDelayMs === 0) start(entry)
        else entry.startHandle = schedule(() => start(entry), startDelayMs)
        entry.endHandle = schedule(() => release(pulse.id), startDelayMs + durationMs)
        accepted.push(pulse)
      }
      return accepted
    },
    release,
    reconcile(visibleTargetIds, visibleEdgeIds = null) {
      const visible = visibleTargetIds instanceof Set ? visibleTargetIds : new Set(visibleTargetIds ?? [])
      const edges =
        visibleEdgeIds == null ? null : visibleEdgeIds instanceof Set ? visibleEdgeIds : new Set(visibleEdgeIds)
      for (const [id, entry] of [...entries]) {
        if (!visible.has(entry.targetId) || (edges && !edges.has(entry.pulse.edgeId))) release(id)
      }
    },
    active: snapshot,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    clear() {
      const hadActive = [...entries.values()].some((entry) => entry.active)
      for (const entry of entries.values()) {
        if (entry.startHandle !== undefined) cancel(entry.startHandle)
        if (entry.endHandle !== undefined) cancel(entry.endHandle)
      }
      entries.clear()
      if (hadActive) notify()
    }
  }
}
