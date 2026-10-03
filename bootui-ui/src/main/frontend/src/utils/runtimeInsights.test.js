import {describe, expect, it} from 'vitest'

import {
  availableThemes,
  evidenceShares,
  numericColumns,
  checksWithReasons,
  coverageSources,
  coverageSummary,
  emptyState,
  groupObservations,
  isMachineColumn,
  textParts,
  themeOf
} from './runtimeInsights.js'

const report = {
  available: true,
  window: {requests: 12},
  coverage: [
    {source: 'http', byRequestId: 12, byExecutionId: 0, byTraceId: 0, unlinked: 0},
    {source: 'sql', byRequestId: 70, byExecutionId: 8, byTraceId: 0, unlinked: 2},
    {source: 'ai', byRequestId: 0, byExecutionId: 0, byTraceId: 8, unlinked: 0},
    {source: 'gc', events: 40, byRequestId: 0, byExecutionId: 0, byTraceId: 0, unlinked: 40},
    {source: 'lifecycle', events: 1, byRequestId: 0, byExecutionId: 0, byTraceId: 0, unlinked: 1}
  ],
  checks: [
    {kind: 'route-time-breakdown', title: 'Route time breakdown', status: 'EVALUATED', reason: null},
    {kind: 'repeated-selects', title: 'Repeated SELECTs', status: 'EVALUATED', reason: null},
    {kind: 'event-loop-blocking', title: 'Blocking on event loops', status: 'NOT_APPLICABLE', reason: 'Spring MVC'},
    {kind: 'errors-behind-2xx', title: 'Errors behind 2xx responses', status: 'EVALUATED', reason: 'Without log'}
  ],
  observations: [
    {
      id: 'a',
      kind: 'repeated-selects',
      subject: 'GET /api/orders',
      sentence: 'select * from lines',
      status: 'INSUFFICIENT',
      affected: 1
    },
    {
      id: 'b',
      kind: 'repeated-selects',
      subject: 'GET /api/owners',
      sentence: 'select * from pets',
      status: 'OBSERVED',
      affected: 4
    },
    {
      id: 'c',
      kind: 'route-time-breakdown',
      subject: 'GET /api/orders',
      sentence: 'warm median 40 ms',
      status: 'OBSERVED',
      affected: 5
    }
  ]
}

describe('runtimeInsights helpers', () => {
  it('groups observations by check in report order, observed and most affected first', () => {
    const groups = groupObservations(report)

    expect(groups.map((group) => group.title)).toEqual(['Route time breakdown', 'Repeated SELECTs'])
    expect(groups[1].observations.map((observation) => observation.id)).toEqual(['b', 'a'])
  })

  it('filters by route, sentence text, and theme', () => {
    expect(
      groupObservations(report, {query: 'pets'})
        .flatMap((g) => g.observations)
        .map((o) => o.id)
    ).toEqual(['b'])
    expect(groupObservations(report, {query: '/api/orders', theme: 'time'})[0].observations[0].id).toBe('c')
    expect(groupObservations(report, {theme: 'errors'})).toEqual([])
    expect(themeOf('ai-usage-by-route')).toBe('ai')
  })

  it('offers only the themes whose checks the report carries', () => {
    expect(availableThemes(report).map((theme) => theme.id)).toEqual(['time', 'queries', 'errors'])
  })

  it('sums correlation coverage across sources into whole-percent shares', () => {
    const summary = coverageSummary(report)

    expect(summary.events).toBe(100)
    expect(summary.segments.map((segment) => [segment.id, segment.share])).toEqual([
      ['request', 82],
      ['trace', 8],
      ['execution', 8],
      ['none', 2]
    ])
    expect(coverageSummary({coverage: []}).segments.every((segment) => segment.share === 0)).toBe(true)
  })

  it('leaves run-level sources such as garbage collections and the run start out of the coverage', () => {
    expect(coverageSources(report).map((source) => source.source)).not.toContain('gc')
    expect(coverageSources(report).map((source) => source.source)).not.toContain('lifecycle')
  })

  it('renders backticked engine text as code and names machine columns', () => {
    expect(textParts('`GET /a` ran `select 1` twice')).toEqual([
      {value: 'GET /a', code: true},
      {value: ' ran ', code: false},
      {value: 'select 1', code: true},
      {value: ' twice', code: false}
    ])
    expect(isMachineColumn('Request')).toBe(true)
    expect(isMachineColumn('Phase')).toBe(false)
  })

  it('lists checks that did not fully run, so an empty list never reads as healthy', () => {
    expect(checksWithReasons(report).map((check) => check.kind)).toEqual(['event-loop-blocking', 'errors-behind-2xx'])
  })

  it('names the empty state', () => {
    expect(emptyState(null)).toBeNull()
    expect(emptyState({available: false})).toBe('disabled')
    expect(emptyState({available: true, window: {requests: 0}, observations: []})).toBe('no-requests')
    expect(emptyState({available: true, window: {requests: 3}, observations: []})).toBe('nothing-observed')
    expect(emptyState({available: true, window: {requests: 0}, observations: report.observations})).toBeNull()
    expect(emptyState({available: true, window: {requests: 0, retainedEvents: 4}, observations: []})).toBe(
      'no-requests'
    )
    expect(emptyState(report)).toBeNull()
  })
})

describe('evidenceShares', () => {
  it('reads each share and marks the largest phase', () => {
    const detail = {
      columns: ['Phase', 'Total (ms)', 'Share', 'Median per request'],
      rows: [
        {cells: ['SQL', '120', '60 %', '12.0']},
        {cells: ['Response write', '20', '10 %', '2.0']},
        {cells: ['Handler other work', '60', '30 %', '6.0']},
        {cells: ['Overlapping calls, counted once above', '5', '', '']}
      ]
    }
    expect(evidenceShares(detail)).toEqual({column: 2, shares: [60, 10, 30, null], top: 0})
  })

  it('is null when the evidence has no share column', () => {
    expect(evidenceShares({columns: ['Route', 'Calls'], rows: []})).toBeNull()
    expect(evidenceShares(null)).toBeNull()
  })

  it('marks no phase when every share is zero', () => {
    const detail = {columns: ['Phase', 'Share'], rows: [{cells: ['SQL', '0 %']}]}
    expect(evidenceShares(detail).top).toBe(-1)
  })
})

describe('numericColumns', () => {
  it('aligns the columns holding only numbers, ignoring blanks, and never the share or text columns', () => {
    const detail = {
      columns: ['Phase', 'Total (ms)', 'Share', 'Median per request', 'Request'],
      rows: [
        {cells: ['SQL', '1,120', '60 %', '12.0', 'r-1']},
        {cells: ['Overlapping calls, counted once above', '5', '', '', '42']}
      ]
    }
    expect([...numericColumns(detail)]).toEqual([1, 3])
    expect(numericColumns(null).size).toBe(0)
  })
})
