import {describe, expect, it} from 'vitest'
import {call, detail, event, faultToleranceDetail, repeatedFailureDetail} from '../test/explorerFixtures.js'
import {
  buildExplorerModel,
  buildReplay,
  cacheMeaning,
  createFreshEvidenceTracker,
  explorerEventVersionKey,
  effectsForRows,
  evidenceTone,
  EXPLORER_TYPES,
  layoutExplorer,
  mergeExplorerEvents,
  appendOlderExplorerEvents,
  REPLAY_LIMIT_MS,
  TOPOLOGY_LIMIT
} from './explorerModel.js'
import {PULSE_DURATION_OK_MS, PULSE_DURATION_SLOW_MS, PULSE_DURATION_FAILED_MS} from './serviceMap.js'

describe('Explorer evidence model', () => {
  it('retains every canonical type, unknown future types and independent background work without a trace', () => {
    const events = [...EXPLORER_TYPES, 'FUTURE'].map((type) => event(type, {type, parentId: null, correlationId: null}))
    const model = buildExplorerModel(events)
    expect(model.rows.map((row) => row.sourceId).sort()).toEqual(events.map((item) => item.id).sort())
    expect(model.nodes).toHaveLength(11)
    expect(model.edges).toHaveLength(0)
    expect(model.rows.every((row) => row.depth === 0)).toBe(true)
  })

  it('uses only exact invocation links and canonical parent ids, identically in tree and scene', () => {
    const payload = detail()
    payload.related.push(event('sql-2', {type: 'SQL', parentId: 'request-1', correlationId: 'same', timestamp: 1010}))
    payload.related.push(event('background', {type: 'SCHEDULED', correlationId: 'same', timestamp: 1010}))
    const model = buildExplorerModel([], payload)
    expect(model.byId.get('event:sql-1').parent).toBe('invocation:repository')
    expect(model.byId.get('event:sql-2').parent).toBe('event:request-1')
    expect(model.byId.get('event:background').parent).toBeNull()
    for (const edge of model.edges) {
      expect(model.byId.get(edge.rowId).parent).toBe(edge.parentRowId)
      expect(model.byId.get(edge.rowId).nodeId).toBe(edge.toId)
      expect(model.byId.get(edge.parentRowId).nodeId).toBe(edge.fromId)
    }
  })

  it('deduplicates beans without collapsing invocation identity, recursion or different bean names', () => {
    const invocation = call('one', null, {beanName: 'repo', typeName: 'OrderRepository'})
    const model = buildExplorerModel(
      [],
      detail({
        invocations: [
          invocation,
          {...invocation, id: 'two', parentId: 'one'},
          {...invocation, id: 'three', beanName: 'other'}
        ]
      })
    )
    expect(model.rows.filter((row) => row.kind === 'CALL')).toHaveLength(3)
    expect(model.nodes.filter((node) => node.kind === 'CALL')).toHaveLength(2)
    const recursive = model.edges.find((edge) => edge.rowId === 'invocation:two')
    expect(recursive.fromId).toBe(recursive.toId)
  })

  it('keeps cycle and missing-parent evidence visible without guessing HTTP parentage', () => {
    const model = buildExplorerModel(
      [],
      detail({invocations: [call('a', 'b'), call('b', 'a'), call('missing', 'no-such-parent')]})
    )
    expect(model.rows.filter((row) => row.kind === 'CALL')).toHaveLength(3)
    expect(model.byId.get('invocation:missing').parent).toBeNull()
    expect(model.warnings).toContain('Cyclic parent evidence; shown independently')
  })

  it('retains reused source ids as timestamped versions without inventing ambiguous parentage', () => {
    const older = event('reused', {timestamp: 100})
    const newer = event('reused', {timestamp: 200})
    const child = event('child', {timestamp: 210, parentId: 'reused'})
    const model = buildExplorerModel([newer, child, older])

    expect(model.rows.filter((row) => row.sourceId === 'reused')).toHaveLength(2)
    expect(new Set(model.rows.filter((row) => row.sourceId === 'reused').map((row) => row.id)).size).toBe(2)
    expect(model.byId.get('event:child').parent).toBeNull()
    expect(model.byId.get('event:child').relationship).toContain('ambiguous')
    expect(mergeExplorerEvents([newer], [newer, older])).toEqual([newer, older])
    expect(appendOlderExplorerEvents([newer], [], [newer, older])).toEqual([older])
  })

  it('places SQL timing only on the statement and scopes unknown or unqualified references per observation', () => {
    const payload = detail()
    payload.related.push(event('sql-2', {type: 'SQL', parentId: 'request-1', durationMs: 13}))
    payload.sqlReferences = [
      {eventId: 'sql-1', dataSource: null, identifiers: ['orders', 'other'], status: 'PARTIAL'},
      {eventId: 'sql-2', dataSource: null, identifiers: ['orders'], status: 'PARTIAL'}
    ]
    const model = buildExplorerModel([], payload)
    expect(model.rows.filter((row) => row.kind === 'SQL_REFERENCE')).toHaveLength(3)
    expect(model.nodes.filter((row) => row.kind === 'SQL_REFERENCE')).toHaveLength(3)
    expect(model.rows.filter((row) => row.kind === 'SQL_REFERENCE').every((row) => row.durationMs == null)).toBe(true)
    expect(model.byId.get('event:sql-1').durationMs).toBe(15)
    payload.sqlReferences.forEach((reference) => {
      reference.dataSource = 'same'
      reference.identifiers = ['schema.orders']
    })
    expect(buildExplorerModel([], payload).nodes.filter((row) => row.kind === 'SQL_REFERENCE')).toHaveLength(1)
    payload.sqlReferences[1].dataSource = 'different'
    expect(buildExplorerModel([], payload).nodes.filter((row) => row.kind === 'SQL_REFERENCE')).toHaveLength(2)
    payload.sqlReferences.forEach((reference) => {
      reference.dataSource = 'same'
      reference.identifiers = ['"orders.archive"']
    })
    expect(buildExplorerModel([], payload).nodes.filter((row) => row.kind === 'SQL_REFERENCE')).toHaveLength(2)
  })

  it('preserves canonical severity instead of reusing the 500 ms Live Flow classification', () => {
    expect(evidenceTone(event('slow-in-flow-only', {durationMs: 750}))).toBe('ok')
    expect(evidenceTone(event('miss', {type: 'CACHE', severity: 'WARN'}))).toBe('ok')
    expect(evidenceTone({slow: true, failed: true})).toBe('failed')
    expect(evidenceTone({severity: 'SLOW'})).toBe('slow')
  })

  it('keeps mail content in canonical DOM evidence, never in scene labels', () => {
    const mail = event('mail-1', {type: 'MAIL', summary: 'Private subject', detail: 'to recipient@example.test'})
    const model = buildExplorerModel([mail])
    expect(model.nodes[0].label).toBe('Mail activity')
    expect(model.rows[0].event).toBe(mail)
  })

  it.each([
    ['HIT', 'lookup-return'],
    ['MISS', 'lookup'],
    ['PUT', 'write'],
    ['EVICT', 'evict'],
    ['CLEAR', 'clear']
  ])('renders %s as %s without invented cache duration, fill or return', (operation, mode) => {
    const payload = detail()
    payload.cacheOperations[0].operation = operation
    const model = buildExplorerModel([], payload)
    const effect = effectsForRows(model, ['event:cache-1'])[0]
    expect(effect.mode).toBe(mode)
    expect(effect.tone).toBe('ok')
    expect(effect.durationMs).toBe(PULSE_DURATION_OK_MS)
    expect(model.byId.get('event:cache-1').durationMs).toBeNull()
    expect(cacheMeaning(operation)).toBeTruthy()
    expect(model.rows.filter((row) => row.kind === 'CACHE')).toHaveLength(1)
  })

  it('separates same-named caches across managers', () => {
    const payload = detail()
    payload.related.push(event('cache-2', {type: 'CACHE'}))
    payload.cacheOperations.push({...payload.cacheOperations[0], eventId: 'cache-2', managerName: 'other'})
    expect(buildExplorerModel([], payload).nodes.filter((node) => node.kind === 'CACHE')).toHaveLength(2)
  })

  it('propagates only observed failed parent-child calls, stopping at a successful parent', () => {
    const payload = detail()
    payload.invocations[1].failed = true
    payload.invocations[1].slow = true
    payload.invocations[2].failed = true
    payload.related.push(
      event('exception', {
        type: 'EXCEPTION',
        severity: 'ERROR',
        summary: 'Failure (100 lifetime occurrences)',
        parentId: 'request-1'
      })
    )
    const model = buildExplorerModel([], payload)
    const effects = effectsForRows(model, ['invocation:repository', 'invocation:service', 'event:exception'])
    expect(effects[0].mode).toBe('propagate')
    expect(effects[1].mode).toBe('forward')
    expect(effects[1].tone).toBe('failed')
    expect(effects[1].durationMs).toBe(PULSE_DURATION_FAILED_MS)
    expect(effects.filter((effect) => effect.rowId === 'event:exception')).toHaveLength(1)
    payload.invocations[1].failed = false
    expect(effectsForRows(buildExplorerModel([], payload), ['invocation:service'])[0].durationMs).toBe(
      PULSE_DURATION_SLOW_MS
    )
  })

  it('does not inherit propagation from an earlier invocation aggregated onto the same scene edge', () => {
    const model = buildExplorerModel([], repeatedFailureDetail())
    const layout = layoutExplorer(model)
    const exactEdge = model.edges.find((edge) => edge.rowId === 'invocation:child-handled')
    const groupedEdge = layout.edges.find((edge) => edge.rowIds.includes('invocation:child-handled'))

    expect(exactEdge.propagates).toBe(false)
    expect(groupedEdge.evidenceEdges.some((edge) => edge.propagates)).toBe(true)
    expect(effectsForRows(model, ['invocation:child-handled'])[0].mode).toBe('forward')
    expect(effectsForRows(model, ['invocation:child-handled'], layout)[0].mode).toBe('forward')
  })

  it('keeps cache operation and selected row outcome exact when observations share scene topology', () => {
    const payload = detail()
    payload.related.push(event('cache-2', {type: 'CACHE', parentId: 'request-1', timestamp: 1010}))
    payload.links.push({eventId: 'cache-2', invocationId: 'service'})
    payload.cacheOperations.push({...payload.cacheOperations[0], eventId: 'cache-2', operation: 'PUT'})
    const model = buildExplorerModel([], payload)
    const layout = layoutExplorer(model)

    expect(effectsForRows(model, ['event:cache-1'], layout)[0].mode).toBe('lookup')
    expect(effectsForRows(model, ['event:cache-2'], layout)[0].mode).toBe('write')
    expect(effectsForRows(model, ['invocation:service'], layout)[0]).toMatchObject({mode: 'forward', tone: 'ok'})
  })

  it('caps and explicitly groups topology without ever limiting the tree; expansion and layout are deterministic', () => {
    const events = Array.from({length: 240}, (_, i) => event(`r-${String(i).padStart(3, '0')}`))
    const model = buildExplorerModel(events)
    const layout = layoutExplorer(model)
    expect(model.rows).toHaveLength(240)
    expect(layout.nodes.length).toBeLessThanOrEqual(TOPOLOGY_LIMIT)
    expect(layout.grouped + layout.nodes.filter((node) => node.kind !== 'GROUP').length).toBe(240)
    expect(layoutExplorer(buildExplorerModel([...events].reverse()))).toEqual(layout)
    const focused = layoutExplorer(model, {focusId: 'event:r-239'})
    expect(focused.nodes.some((node) => node.rowIds.includes('event:r-239') && node.kind !== 'GROUP')).toBe(true)
    const next = layoutExplorer(model, {group: 0, page: 1})
    expect(
      next.nodes
        .filter((node) => node.kind !== 'GROUP')
        .some((node) => !layout.nodes.some((first) => first.id === node.id))
    ).toBe(true)
  })

  it('fans circuit-breaker signals out separately from HTTP without changing their observed edges', () => {
    const model = buildExplorerModel([], faultToleranceDetail())
    const layout = layoutExplorer(model)
    const root = layout.nodes.find((node) => node.kind === 'REQUEST')
    const signals = layout.nodes.filter((node) => node.kind === 'FAULT_TOLERANCE')
    expect(signals).toHaveLength(3)
    expect(signals.map((node) => node.rowIds.length).sort()).toEqual([1, 2, 4])
    expect(new Set(signals.map((node) => node.x)).size).toBe(3)
    expect(new Set(signals.map((node) => node.z)).size).toBe(1)
    expect(signals.every((node) => node.stage !== root.stage && node.z > root.z)).toBe(true)
    expect(layout.edges.flatMap((edge) => edge.rowIds)).toHaveLength(model.edges.length)
    expect(model.rows.filter((row) => row.kind === 'FAULT_TOLERANCE')).toHaveLength(7)
    expect(layout.edges.every((edge) => edge.fromId === root.id)).toBe(true)
    expect(layoutExplorer({...model, nodes: [...model.nodes].reverse()})).toEqual(layout)
  })

  it('never merges similar resilience signals across requests, outcomes, or missing parent evidence', () => {
    const signal = {type: 'FAULT_TOLERANCE', summary: 'ERROR inventory-service', severity: 'ERROR', parentId: 'one'}
    const model = buildExplorerModel([
      event('one'),
      event('two'),
      event('first', signal),
      event('repeat', signal),
      event('different-request', {...signal, parentId: 'two'}),
      event('different-outcome', {...signal, severity: 'WARN'}),
      event('independent-a', {...signal, parentId: null}),
      event('independent-b', {...signal, parentId: null})
    ])
    expect(model.nodes.filter((node) => node.kind === 'FAULT_TOLERANCE')).toHaveLength(5)
    expect(model.rows.filter((row) => row.kind === 'FAULT_TOLERANCE')).toHaveLength(6)
  })

  it('wraps dense primary stages instead of making an arbitrarily deep stack', () => {
    const layout = layoutExplorer(buildExplorerModel(Array.from({length: 20}, (_, index) => event(`request-${index}`))))
    expect(new Set(layout.nodes.map((node) => node.z)).size).toBeLessThanOrEqual(5)
    expect(new Set(layout.nodes.map((node) => node.x)).size).toBe(7)
    for (const [index, node] of layout.nodes.entries()) {
      for (const other of layout.nodes.slice(index + 1)) {
        expect(Math.hypot(node.x - other.x, node.z - other.z)).toBeGreaterThanOrEqual(6.5)
      }
    }
  })

  it('paces the execution tree separately from recorded latency and does not duplicate SQL for references', () => {
    const payload = detail()
    payload.invocations[2].offsetMs = 100000
    const model = buildExplorerModel([], payload)
    const timeline = buildReplay(model)
    expect(timeline.durationMs).toBeLessThanOrEqual(REPLAY_LIMIT_MS)
    expect(timeline.actions.filter((action) => action.rowId === 'event:sql-1')).toHaveLength(1)
    expect(timeline.actions.some((action) => action.rowId.startsWith('reference:'))).toBe(false)
    expect(timeline.observedMs).toBeGreaterThanOrEqual(100000)
    expect(timeline.actions.map((action) => action.rowId)).toEqual(
      model.rows.filter((row) => row.kind !== 'SQL_REFERENCE').map((row) => row.id)
    )
    for (const [index, action] of timeline.actions.entries()) {
      if (index) expect(action.at).toBeCloseTo(timeline.actions[index - 1].at + timeline.actions[index - 1].durationMs)
    }
  })

  it('keeps long sequences bounded without scheduling overlapping steps or dropping repeated observations', () => {
    const model = buildExplorerModel(Array.from({length: 240}, (_, index) => event(`request-${index}`)))
    const timeline = buildReplay(model)
    expect(timeline.actions).toHaveLength(240)
    expect(timeline.durationMs).toBe(REPLAY_LIMIT_MS)
    expect(timeline.scale).toBeLessThan(1)
    expect(timeline.actions.at(-1).at + timeline.actions.at(-1).durationMs).toBeCloseTo(REPLAY_LIMIT_MS)
  })

  it('caps shared-edge effects to two and total effects to six across repeated calls', () => {
    const payload = detail({
      invocations: [
        call('parent'),
        ...Array.from({length: 20}, (_, i) =>
          call(`call-${i}`, 'parent', {beanName: 'shared', typeName: 'SharedService'})
        )
      ]
    })

    const model = buildExplorerModel([], payload),
      layout = layoutExplorer(model)
    const effects = effectsForRows(
      model,
      model.rows.map((row) => row.id),
      layout
    )
    expect(effects.length).toBeLessThanOrEqual(6)
    for (const effect of effects)
      expect(effects.filter((other) => other.edgeId === effect.edgeId).length).toBeLessThanOrEqual(2)
  })

  it('enters failed calls before their children and reveals failures only while unwinding', () => {
    const payload = detail({
      invocations: [
        call('parent', 'request-1', {failed: true, offsetMs: 0, durationMs: 100}),
        call('child', 'parent', {failed: true, offsetMs: 10, durationMs: 20})
      ]
    })
    const model = buildExplorerModel([], payload)
    const timeline = buildReplay(model)
    expect(
      timeline.actions
        .filter((action) => action.rowId.startsWith('invocation:'))
        .map(({rowId, phase}) => [rowId, phase])
    ).toEqual([
      ['invocation:parent', 'enter'],
      ['invocation:child', 'enter'],
      ['invocation:child', 'exit'],
      ['invocation:parent', 'exit']
    ])
    expect(
      timeline.actions.find((action) => action.rowId === 'invocation:child' && action.phase === 'exit').observedAt
    ).toBe(30)
    expect(
      timeline.actions.find((action) => action.rowId === 'invocation:parent' && action.phase === 'exit').observedAt
    ).toBe(100)
    expect(effectsForRows(model, ['invocation:child'], null, 'enter')[0]).toMatchObject({tone: 'ok', mode: 'forward'})
    expect(effectsForRows(model, ['invocation:child'], null, 'exit')[0]).toMatchObject({
      tone: 'failed',
      mode: 'propagate'
    })
    const handled = effectsForRows(model, ['invocation:parent'], null, 'exit')[0]
    expect(handled.mode).toBe('failure')
    expect(handled.fromId).toBe(handled.toId)
  })

  it('uses only the canonical messaging direction marker, never a matching topic to invent a remote edge', () => {
    const model = buildExplorerModel([
      event(),
      event('consume', {type: 'MESSAGING', summary: '← orders', parentId: 'request-1'}),
      event('publish', {type: 'MESSAGING', summary: '→ orders', parentId: null}),
      event('unknown', {type: 'MESSAGING', summary: 'orders', parentId: null})
    ])
    expect(effectsForRows(model, ['event:consume'])[0].mode).toBe('inbound')
    expect(model.byId.get('event:publish').parent).toBeNull()
    expect(model.byId.get('event:unknown').direction).toBeNull()
    expect(model.edges).toHaveLength(1)
  })

  it('baseline-loads initial, older, repeated and resume evidence; only fresh IDs animate', () => {
    const tracker = createFreshEvidenceTracker()
    expect(tracker.accept([event('first')])).toEqual([])
    expect(tracker.accept([event('first'), event('old', {timestamp: 500})])).toEqual([])
    expect(tracker.accept([event('first'), event('new', {timestamp: 1001})])).toEqual([
      explorerEventVersionKey(event('new', {timestamp: 1001}))
    ])
    expect(tracker.accept([event('new', {timestamp: 1001})])).toEqual([])
    expect(tracker.accept([event('first', {timestamp: 1002})])).toEqual([
      explorerEventVersionKey(event('first', {timestamp: 1002}))
    ])
    tracker.reset()
    expect(tracker.accept([event('backlog', {timestamp: 2000})])).toEqual([])
    expect(tracker.accept([event('disabled', {timestamp: 3000})], false)).toEqual([])
  })
})
