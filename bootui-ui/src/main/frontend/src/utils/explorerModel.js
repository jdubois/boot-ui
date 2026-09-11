import {MAX_CONCURRENT_PULSES, MAX_PULSES_PER_EDGE, pulseDurationMs} from './serviceMap.js'

export const EXPLORER_TYPES = [
  'REQUEST',
  'SQL',
  'EXCEPTION',
  'SECURITY',
  'CACHE',
  'SCHEDULED',
  'MESSAGING',
  'MAIL',
  'REST_CLIENT',
  'FAULT_TOLERANCE'
]
export const TOPOLOGY_LIMIT = 80
export const REPLAY_LIMIT_MS = 12000
export const STAGES = [
  'HTTP / background',
  'Controllers',
  'Services / components',
  'Repositories',
  'SQL',
  'SQL references',
  'Cache / external',
  'Runtime signals'
]
const identity = (...parts) => JSON.stringify(parts)
const eventKey = (id) => `event:${id}`
const invocationKey = (id) => `invocation:${id}`
const finiteTime = (value) => (Number.isFinite(value) && value >= 0 ? value : 0)
const compare = (a, b) => a.id.localeCompare(b.id)

export const explorerEventVersionKey = (event) =>
  identity(event?.id || '', Number.isFinite(event?.timestamp) ? event.timestamp : null)

export function mergeExplorerEvents(head = [], older = []) {
  const merged = []
  const seen = new Set()
  for (const event of [...head, ...older]) {
    const key = explorerEventVersionKey(event)
    if (seen.has(key)) continue
    seen.add(key)
    merged.push(event)
  }
  return merged
}

export function appendOlderExplorerEvents(head = [], older = [], next = []) {
  const seen = new Set([...head, ...older].map(explorerEventVersionKey))
  const appended = [...older]
  for (const event of next) {
    const key = explorerEventVersionKey(event)
    if (seen.has(key)) continue
    seen.add(key)
    appended.push(event)
  }
  return appended
}

function qualifiedIdentifier(identifier) {
  let closingQuote = null
  for (let index = 0; index < identifier.length; index++) {
    const char = identifier[index]
    if (closingQuote) {
      if (char === closingQuote) {
        if (identifier[index + 1] === closingQuote) index++
        else closingQuote = null
      }
    } else if (char === '"' || char === '`' || char === '[') closingQuote = char === '[' ? ']' : char
    else if (char === '.') return true
  }
  return false
}

export function evidenceTone(value) {
  if (value?.failed === true || value?.severity === 'ERROR') return 'failed'
  // WARN is not latency. In particular, a canonical cache MISS stays neutral in the scene.
  if (value?.slow === true || value?.severity === 'SLOW') return 'slow'
  return 'ok'
}

export function durationLabel(value) {
  if (!Number.isFinite(value) || value < 0) return 'Untimed'
  return `${Number(value.toFixed(2)).toLocaleString()} ms`
}

export function cacheMeaning(operation) {
  return (
    {
      HIT: 'Lookup → cache → caller. A cached result was returned.',
      MISS: 'Lookup → cache. No cached result returned; this is not a failure.',
      PUT: 'Caller → cache. A write was observed.',
      EVICT: 'Invalidation: one entry removed; no return transfer.',
      CLEAR: 'Invalidation: cache cleared; no return transfer.'
    }[operation] || 'Operation metadata unavailable; no direction inferred.'
  )
}

function stageFor(value, invocation = false) {
  if (invocation) return {CONTROLLER: 1, SERVICE: 2, COMPONENT: 2, REPOSITORY: 3}[value.role] ?? 2
  if (value.type === 'SQL') return 4
  if (['CACHE', 'REST_CLIENT', 'MAIL', 'MESSAGING'].includes(value.type)) return 6
  if (['EXCEPTION', 'SECURITY', 'FAULT_TOLERANCE'].includes(value.type)) return 7
  return 0
}

/**
 * One evidence graph drives both DOM and GPU views. Only canonical parent IDs and explicit invocation
 * links create edges. A trace ID, summary, timestamp, or stereotype never establishes parentage.
 */
export function buildExplorerModel(entries = [], detail = null) {
  const eventVersions = new Map()
  for (const value of detail ? [detail.event, ...(detail.related || [])] : entries) {
    if (value?.id) eventVersions.set(explorerEventVersionKey(value), value)
  }
  const eventValues = [...eventVersions.values()]
  const eventsById = new Map()
  for (const event of eventValues) {
    const matches = eventsById.get(event.id) || []
    matches.push(event)
    eventsById.set(event.id, matches)
  }
  const eventRowId = (event) =>
    eventsById.get(event.id)?.length > 1 ? `event:${explorerEventVersionKey(event)}` : eventKey(event.id)
  const uniqueEventRowId = (id) => {
    const matches = eventsById.get(id)
    return matches?.length === 1 ? eventRowId(matches[0]) : null
  }
  const calls = new Map((detail?.invocations || []).filter((value) => value?.id).map((value) => [value.id, value]))
  const links = new Map((detail?.links || []).map((value) => [value.eventId, value.invocationId]))
  const sql = new Map((detail?.sqlReferences || []).map((value) => [value.eventId, value]))
  const caches = new Map((detail?.cacheOperations || []).map((value) => [value.eventId, value]))
  const rows = []
  const nodes = new Map()
  const edges = []

  function add(row) {
    rows.push(row)
    let node = nodes.get(row.nodeId)
    if (!node) {
      node = {
        id: row.nodeId,
        label: row.nodeLabel || row.label,
        kind: row.kind,
        role: row.call?.role,
        context: row.kind === 'FAULT_TOLERANCE' && row.event?.detail?.startsWith('state ') ? row.event.detail : '',
        stage: row.stage,
        rowIds: []
      }
      nodes.set(node.id, node)
    }
    node.rowIds.push(row.id)
  }

  for (const event of eventValues) {
    const cache = caches.get(event.id)
    const exact = eventsById.get(event.id)?.length === 1 ? links.get(event.id) : null
    const canonicalParent = event.parentId && event.parentId !== event.id ? uniqueEventRowId(event.parentId) : null
    const nodeId = cache
      ? `cache:${identity(cache.managerName || event.id, cache.cacheName || event.id)}`
      : event.type === 'FAULT_TOLERANCE' && canonicalParent && event.summary
        ? `signal:${identity(exact || canonicalParent, event.summary, event.detail, event.severity)}`
        : eventRowId(event)
    add({
      id: eventRowId(event),
      sourceId: event.id,
      nodeId,
      kind: event.type || 'EVENT',
      stage: stageFor(event),
      label: event.summary || event.type || 'Activity',
      nodeLabel: cache
        ? cache.cacheName || 'Cache (unknown name)'
        : event.type === 'SQL'
          ? 'SQL statement'
          : event.type === 'MAIL'
            ? 'Mail activity'
            : event.summary,
      parent: exact && calls.has(exact) ? invocationKey(exact) : canonicalParent,
      relationship:
        exact && calls.has(exact)
          ? 'Exact capture-time invocation'
          : canonicalParent
            ? 'Canonical activity correlation (not an exact bean link)'
            : event.parentId || exact
              ? eventsById.get(event.parentId)?.length > 1
                ? 'Parent evidence is ambiguous across retained event versions'
                : 'Parent evidence not retained'
              : 'Independent activity',
      event,
      cache,
      sql: sql.get(event.id),
      tone: evidenceTone(event),
      durationMs: event.durationMs,
      timestamp: event.timestamp,
      operation: cache?.operation,
      direction:
        event.type === 'MESSAGING'
          ? event.summary?.startsWith('←')
            ? 'INBOUND'
            : event.summary?.startsWith('→')
              ? 'OUTBOUND'
              : null
          : null
    })
  }
  for (const call of calls.values()) {
    const parent =
      calls.has(call.parentId) && call.parentId !== call.id
        ? invocationKey(call.parentId)
        : uniqueEventRowId(call.parentId)
    add({
      id: invocationKey(call.id),
      sourceId: call.id,
      nodeId: `bean:${identity(call.beanName, call.typeName)}`,
      kind: 'CALL',
      stage: stageFor(call, true),
      label: `${call.beanName}.${call.method}`,
      nodeLabel: call.beanName || call.typeName || 'Application bean',
      parent,
      relationship: parent ? 'Observed invocation parent' : 'Invocation parent not retained',
      call,
      tone: evidenceTone(call),
      durationMs: call.durationMs,
      offsetMs: finiteTime(call.offsetMs)
    })
  }
  for (const reference of sql.values()) {
    const parent = uniqueEventRowId(reference.eventId)
    if (!parent) continue
    for (const identifier of new Set(reference.identifiers || [])) {
      // Unqualified names have unknown schema, and missing datasources have unknown database scope.
      // Never collapse these observations into a claimed physical table shared by different statements.
      const knownScope = reference.dataSource && qualifiedIdentifier(identifier)
      const scope = knownScope ? reference.dataSource : identity(reference.dataSource, parent)
      add({
        id: `reference:${identity(parent, identifier)}`,
        nodeId: `reference:${identity(scope, identifier)}`,
        kind: 'SQL_REFERENCE',
        stage: 5,
        label: identifier,
        parent,
        relationship: 'SQL reference, not physical-table health',
        tone: 'ok',
        reference,
        scopeKnown: !!knownScope
      })
    }
  }
  const byId = new Map(rows.map((row) => [row.id, row]))
  const warnings = [...(detail?.warnings || [])]
  for (const row of rows) {
    const visited = new Set([row.id])
    let parent = row.parent
    while (parent && byId.has(parent)) {
      if (visited.has(parent)) {
        row.parent = null
        row.relationship = 'Cyclic parent evidence; shown independently'
        if (!warnings.includes(row.relationship)) warnings.push(row.relationship)
        break
      }
      visited.add(parent)
      parent = byId.get(parent).parent
    }
    if (row.parent && byId.has(row.parent)) {
      const parentRow = byId.get(row.parent)
      edges.push({
        id: row.id,
        fromId: parentRow.nodeId,
        toId: row.nodeId,
        rowId: row.id,
        parentRowId: parentRow.id,
        relationship: row.relationship,
        // Propagation is a second reading of this exact edge, never a new exception occurrence.
        propagates: row.call?.failed === true && parentRow.call?.failed === true
      })
    }
  }
  const children = new Map()
  for (const row of rows) {
    const key = row.parent && byId.has(row.parent) ? row.parent : null
    if (!children.has(key)) children.set(key, [])
    children.get(key).push(row)
  }
  const flat = []
  function walk(parent, depth) {
    for (const row of (children.get(parent) || []).sort(
      (a, b) => (a.offsetMs ?? a.timestamp ?? 0) - (b.offsetMs ?? b.timestamp ?? 0) || compare(a, b)
    )) {
      flat.push({...row, depth})
      walk(row.id, depth + 1)
    }
  }
  walk(null, 0)
  for (const node of nodes.values()) {
    const observed = node.rowIds.map((id) => byId.get(id))
    const failure = observed.find((row) => row.tone === 'failed')
    const slow = observed.find((row) => row.tone === 'slow' || row.call?.slow)
    node.status = failure ? 'Failed' : slow ? `Slow · ${durationLabel(slow.durationMs)}` : ''
    node.operation = observed.find((row) => row.operation)?.operation || ''
  }
  return {rows: flat, byId, nodes: [...nodes.values()].sort(compare), edges, warnings}
}

/** Bounded scene only; the complete keyboard execution list is never capped. */
export function layoutExplorer(model, {group = null, page = 0, focusId = null} = {}) {
  const all = group == null ? model.nodes : model.nodes.filter((node) => node.stage === group)
  let expanded = all
  let groups = []
  if (all.length > TOPOLOGY_LIMIT) {
    const budget = TOPOLOGY_LIMIT - STAGES.length
    const start = Math.max(0, page) * budget
    expanded = all.slice(start, start + budget)
    const focused = all.find((node) => node.rowIds.includes(focusId))
    if (focused && !expanded.includes(focused)) expanded = [...expanded.slice(0, -1), focused]
    const expandedIds = new Set(expanded.map((node) => node.id))
    const overflow = all.filter((node) => !expandedIds.has(node.id))
    groups = STAGES.flatMap((stage, index) => {
      const members = overflow.filter((node) => node.stage === index)
      return members.length
        ? [
            {
              id: `group:${index}`,
              kind: 'GROUP',
              label: `${members.length} more ${stage.toLowerCase()}`,
              stage: index,
              rowIds: members.flatMap((node) => node.rowIds),
              count: members.length
            }
          ]
        : []
    })
  }
  const mapped = new Map()
  for (const node of expanded) mapped.set(node.id, node.id)
  for (const node of groups) {
    for (const original of all.filter((candidate) => candidate.stage === node.stage && !mapped.has(candidate.id))) {
      mapped.set(original.id, node.id)
    }
  }
  const visible = [...expanded, ...groups].sort(compare)
  const positioned = []
  const columnGap = 6.5,
    rowGap = 8
  let nextColumn = 0
  for (let stage = 0; stage < 6; stage++) {
    const lane = visible.filter((node) => node.stage === stage)
    for (const [index, node] of lane.entries()) {
      const column = Math.floor(index / 3)
      const columnSize = Math.min(3, lane.length - column * 3)
      positioned.push({
        ...node,
        x: nextColumn + column * columnGap,
        y: 0.6,
        z: ((index % 3) - (columnSize - 1) / 2) * rowGap
      })
    }
    nextColumn += Math.ceil(lane.length / 3) * columnGap
  }
  const center = Math.max(0, nextColumn - columnGap) / 2
  for (const node of positioned) node.x -= center

  // Side signals are observations, not additional architectural call layers. Fan them out beside
  // each other below the call path rather than stacking them behind the HTTP model.
  const sideLanes = [6, 7]
    .map((stage) => {
      const nodes = visible.filter((node) => node.stage === stage)
      return {nodes, columns: Math.min(4, nodes.length)}
    })
    .filter((lane) => lane.nodes.length)
  const sideDepth = positioned.length ? Math.max(...positioned.map((node) => node.z)) + 10 : 0
  const sideWidth = sideLanes.reduce((sum, lane) => sum + lane.columns * columnGap, 0)
  let sideStart = -sideWidth / 2
  for (const {nodes, columns} of sideLanes) {
    for (const [index, node] of nodes.entries()) {
      const row = Math.floor(index / columns)
      const rowSize = Math.min(columns, nodes.length - row * columns)
      positioned.push({
        ...node,
        x: sideStart + (columns * columnGap) / 2 + ((index % columns) - (rowSize - 1) / 2) * columnGap,
        y: 0.6,
        z: sideDepth + row * rowGap
      })
    }
    sideStart += columns * columnGap
  }
  const edgeMap = new Map()
  for (const edge of model.edges) {
    const fromId = mapped.get(edge.fromId),
      toId = mapped.get(edge.toId)
    if (!fromId || !toId) continue
    const id = identity(fromId, toId)
    if (!edgeMap.has(id)) edgeMap.set(id, {...edge, id, fromId, toId, rowIds: [], evidenceEdges: []})
    edgeMap.get(id).rowIds.push(edge.rowId)
    edgeMap.get(id).evidenceEdges.push(edge)
  }
  return {
    nodes: positioned.sort(compare),
    edges: [...edgeMap.values()],
    total: model.nodes.length,
    grouped: groups.reduce((sum, node) => sum + node.count, 0),
    pages: Math.ceil(all.length / (TOPOLOGY_LIMIT - STAGES.length))
  }
}

function replayTone(row, phase) {
  return phase === 'enter' && row.call?.failed ? (row.call.slow ? 'slow' : 'ok') : row.tone
}

/** Presentation time follows the execution tree; recorded timings remain separate evidence. */
export function buildReplay(model, {rowIds = null} = {}) {
  const timestamped = model.rows.filter((row) => Number.isFinite(row.timestamp))
  const baseline = timestamped.length ? Math.min(...timestamped.map((row) => row.timestamp)) : 0
  const selected = rowIds == null ? null : new Set(rowIds)
  const rows = model.rows.filter((row) => row.kind !== 'SQL_REFERENCE')
  const steps = [],
    unwinding = []
  const observedAt = (row, exit = false) =>
    row.offsetMs != null ? row.offsetMs + (exit ? finiteTime(row.durationMs) : 0) : finiteTime(row.timestamp - baseline)
  function append(row, phase) {
    steps.push({
      rowId: row.id,
      phase,
      observedAt: observedAt(row, phase === 'exit'),
      durationMs: pulseDurationMs(replayTone(row, phase))
    })
  }
  for (const row of rows) {
    while (unwinding.length && unwinding.at(-1).depth >= row.depth) append(unwinding.pop(), 'exit')
    if (selected && !selected.has(row.id)) continue
    append(row, row.call ? 'enter' : 'observe')
    if (row.call?.failed) unwinding.push(row)
  }
  while (unwinding.length) append(unwinding.pop(), 'exit')
  const observedMs = Math.max(
    0,
    ...rows.map((row) => observedAt(row, row.call?.failed)),
    ...model.rows.map((row) => (row.offsetMs || 0) + finiteTime(row.durationMs))
  )
  const pacedMs = steps.reduce((sum, step) => sum + step.durationMs, 0)
  const durationMs = Math.min(REPLAY_LIMIT_MS, pacedMs)
  const scale = pacedMs > 0 ? durationMs / pacedMs : 1
  let at = 0
  return {
    actions: steps.map((step) => {
      const action = {...step, at, durationMs: step.durationMs * scale}
      at += action.durationMs
      return action
    }),
    durationMs,
    observedMs,
    scale
  }
}

export function effectsForRows(model, rowIds, layout = null, phase = null) {
  const effects = []
  for (const id of rowIds) {
    const row = model.byId.get(id)
    if (!row) continue
    const edge =
      layout?.edges.find((value) => value.rowIds.includes(id)) || model.edges.find((value) => value.rowId === id)
    const target = layout?.nodes.find((node) => node.rowIds.includes(id))?.id || row.nodeId
    const tone = replayTone(row, phase)
    const durationMs = pulseDurationMs(tone)
    const base = {
      id,
      rowId: id,
      edgeId: edge?.id || target,
      fromId: edge?.fromId || target,
      toId: edge?.toId || target,
      tone,
      durationMs
    }
    if (row.operation === 'HIT') effects.push({...base, mode: 'lookup-return'})
    else if (row.operation === 'EVICT' || row.operation === 'CLEAR')
      effects.push({...base, mode: row.operation.toLowerCase()})
    else
      effects.push({...base, mode: row.operation === 'MISS' ? 'lookup' : row.operation === 'PUT' ? 'write' : 'forward'})
    // Never route a fault through a successful parent or turn canonical exception grouping into pulses.
    const propagates = edge?.evidenceEdges
      ? edge.evidenceEdges.some((value) => value.rowId === id && value.propagates)
      : edge?.propagates
    if (row.call?.failed && propagates && phase !== 'enter') {
      effects[effects.length - 1] = {...base, mode: 'propagate', fromId: base.toId, toId: base.fromId}
    } else if (row.call?.failed && phase === 'exit') {
      effects[effects.length - 1] = {...base, mode: 'failure', edgeId: target, fromId: target, toId: target}
    } else if (row.direction === 'INBOUND') {
      effects[effects.length - 1] = {...base, mode: 'inbound', fromId: base.toId, toId: base.fromId}
    }
  }
  const counts = new Map()
  return effects
    .filter((effect) => {
      const count = counts.get(effect.edgeId) || 0
      counts.set(effect.edgeId, count + 1)
      return count < MAX_PULSES_PER_EDGE
    })
    .slice(0, MAX_CONCURRENT_PULSES)
}

/** Bounded identity baseline; fresh means beyond the previous newest timestamp, not paged history. */
export function createFreshEvidenceTracker() {
  let seen = new Set()
  let watermark = null
  return {
    reset() {
      watermark = null
      seen.clear()
    },
    accept(entries, eligible = true) {
      const fresh =
        watermark == null || !eligible
          ? []
          : entries.filter(
              (event) => !seen.has(explorerEventVersionKey(event)) && finiteTime(event.timestamp) >= watermark
            )
      watermark = Math.max(watermark ?? 0, ...entries.map((event) => finiteTime(event.timestamp)))
      seen = new Set([...seen, ...entries.map(explorerEventVersionKey)].slice(-2000))
      return fresh.map(explorerEventVersionKey)
    }
  }
}
