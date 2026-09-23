import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SqlTrace from './SqlTrace.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))
vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function traceReport(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    captureParameters: true,
    bufferSize: 200,
    totalCaptured: 8,
    slowQueryThresholdMillis: 100,
    dataSources: ['dataSource'],
    stats: {
      totalQueries: 2,
      totalDurationMillis: 30,
      maxDurationMillis: 25,
      avgDurationMillis: 15,
      slowQueries: 1,
      failedQueries: 0,
      batchExecutions: 0,
      selectCount: 1,
      insertCount: 1,
      updateCount: 0,
      deleteCount: 0,
      otherCount: 0,
      evicted: 0
    },
    entries: [
      {
        id: 2,
        timestamp: 1700000000000,
        sql: 'select * from todo where id = ?',
        statementType: 'PREPARED',
        category: 'SELECT',
        durationMicros: 25_400,
        durationMillis: 25,
        success: true,
        errorMessage: null,
        affectedRows: null,
        batchSize: 0,
        connectionId: 'conn-1',
        thread: 'http-nio-1',
        slow: false,
        parameters: ["'42'"],
        callSite: 'com.example.TodoRepository.findById(TodoRepository.java:42)'
      },
      {
        id: 1,
        timestamp: 1700000000000,
        sql: 'insert into todo(title) values (?)',
        statementType: 'PREPARED',
        category: 'INSERT',
        durationMicros: 310,
        durationMillis: 0,
        success: true,
        errorMessage: null,
        affectedRows: 1,
        batchSize: 0,
        connectionId: 'conn-1',
        thread: 'http-nio-1',
        slow: false,
        parameters: ["'buy milk'"]
      }
    ],
    topStatements: [
      {
        sql: 'select * from todo where id = ?',
        category: 'SELECT',
        executions: 6,
        totalDurationMillis: 60,
        maxDurationMillis: 25,
        potentialNPlusOne: true,
        callSites: ['com.example.TodoRepository.findById(TodoRepository.java:42)']
      }
    ],
    warnings: ['Bound parameter values are captured in clear text.'],
    ...overrides
  }
}

function insightsReport(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    window: {
      retainedStatements: 2,
      bufferSize: 200,
      evicted: 3,
      totalCaptured: 8,
      oldestTimestamp: 1700000000000,
      newestTimestamp: 1700000005000,
      totalDurationMillis: 30
    },
    statements: [
      {
        id: 'select * from todo where id = ?',
        sql: 'select * from todo where id = ?',
        category: 'SELECT',
        executions: 6,
        totalDurationMillis: 60,
        maxDurationMillis: 25,
        avgDurationMillis: 10,
        errorCount: 0,
        p50DurationMillis: 9,
        p95DurationMillis: 24,
        p99DurationMillis: 25,
        shareOfRetainedTimePercent: 75,
        potentialNPlusOne: true,
        topFor: ['TOTAL_DURATION', 'EXECUTIONS'],
        callSites: ['com.example.TodoRepository.findById(TodoRepository.java:42)'],
        entryIds: [2],
        entryIdsTruncated: true
      },
      {
        id: 'insert into todo(title) values (?)',
        sql: 'insert into todo(title) values (?)',
        category: 'INSERT',
        executions: 1,
        totalDurationMillis: 5,
        maxDurationMillis: 90,
        avgDurationMillis: 90,
        errorCount: 2,
        p50DurationMillis: 90,
        p95DurationMillis: 90,
        p99DurationMillis: 90,
        shareOfRetainedTimePercent: 25,
        potentialNPlusOne: false,
        topFor: ['MAX_DURATION', 'AVG_DURATION', 'ERROR_COUNT', 'P95_DURATION', 'P99_DURATION'],
        callSites: [],
        entryIds: [1],
        entryIdsTruncated: false
      }
    ],
    topPerCriterion: 10,
    statementsTruncated: true,
    distinctStatements: 7,
    attribution: {
      available: true,
      unavailableReason: null,
      supportedCorrelations: ['SERVING_THREAD', 'TIME_WINDOW', 'TRACE_ID'],
      requestsConsidered: 4,
      routes: [
        {
          id: 'GET /api/todos/{id}',
          method: 'GET',
          route: '/api/todos/{id}',
          routeSource: 'ROUTE_TEMPLATE',
          requests: 3,
          executions: 6,
          totalDurationMillis: 60,
          maxDurationMillis: 25,
          avgDurationMillis: 10,
          errorCount: 0,
          distinctStatements: 1,
          shareOfRetainedTimePercent: 75,
          traceCorrelated: 4,
          threadCorrelated: 2,
          timeWindowCorrelated: 0,
          topStatements: [
            {
              statementId: 'select * from todo where id = ?',
              sql: 'select * from todo where id = ?',
              category: 'SELECT',
              executions: 6,
              totalDurationMillis: 60,
              maxDurationMillis: 25,
              errorCount: 0
            }
          ],
          topStatementsTruncated: true,
          entryIds: [2]
        },
        {
          id: 'POST /api/todos/{value}/archive',
          method: 'POST',
          route: '/api/todos/{value}/archive',
          routeSource: 'MASKED_PATH',
          requests: 1,
          executions: 1,
          totalDurationMillis: 5,
          maxDurationMillis: 5,
          avgDurationMillis: 5,
          errorCount: 0,
          distinctStatements: 1,
          shareOfRetainedTimePercent: 25,
          traceCorrelated: 0,
          threadCorrelated: 0,
          timeWindowCorrelated: 1,
          topStatements: [],
          topStatementsTruncated: false,
          entryIds: [1]
        }
      ],
      routesTruncated: true,
      distinctRoutes: 9,
      attributedExecutions: 7,
      unattributed: {
        executions: 4,
        totalDurationMillis: 12,
        errorCount: 0,
        shareOfRetainedTimePercent: 8.5,
        reason: 'No captured request was in flight when these statements ran.'
      },
      ambiguous: {
        executions: 2,
        totalDurationMillis: 6,
        errorCount: 0,
        shareOfRetainedTimePercent: 4.25,
        reason: 'More than one captured request was an equally plausible source.'
      },
      notes: ['Time-window correlation is used only when exactly one captured request was in flight;']
    },
    notes: ['Rankings describe the retained trace window, not lifetime metrics.'],
    ...overrides
  }
}

/** Routes the two reads the panel performs, so a test never accidentally serves one payload for both. */
function stubFetch({trace = traceReport(), insights = insightsReport(), overrides = {}} = {}) {
  const fetchMock = vi.fn((url) => {
    if (overrides[url]) return Promise.resolve(jsonResponse(overrides[url]))
    if (url === 'api/sql-trace/insights') return Promise.resolve(jsonResponse(insights))
    return Promise.resolve(jsonResponse(trace))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('SqlTrace', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the unavailable reason when no DataSource is present', async () => {
    stubFetch({
      trace: {
        available: false,
        unavailableReason: 'No DataSource bean is available',
        stats: {totalQueries: 0},
        entries: [],
        topStatements: [],
        warnings: []
      },
      insights: {
        available: false,
        unavailableReason: 'SQL tracing is not active',
        capturing: false,
        window: null,
        statements: [],
        topPerCriterion: 10,
        statementsTruncated: false,
        distinctStatements: 0,
        attribution: {available: false, unavailableReason: 'SQL tracing is not active'},
        notes: []
      }
    })

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/sql-trace', expect.anything())
    expect(wrapper.text()).toContain('No DataSource bean is available')
  })

  it('renders captured executions, stats, warnings, and the N+1 hint', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('select * from todo where id = ?')
    expect(text).toContain('insert into todo(title) values (?)')
    expect(text).toContain('SELECT')
    expect(text).toContain('INSERT')
    expect(text).toContain('Statement rankings')
    expect(text).toContain('possible N+1')
    expect(text).toContain('captured since startup')
    expect(text).toContain('captured in clear text')
    expect(text).toContain('com.example.TodoRepository.findById(TodoRepository.java:42)')
  })

  it('shows a sub-millisecond execution at its real cost instead of as zero', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('0.310 ms')
    expect(text).toContain('25.4 ms')
  })

  it('reveals parameters, thread, and call site when a row is expanded', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(wrapper.text()).not.toContain('http-nio-1')
    await wrapper.get('tr.sql-row').trigger('click')
    const text = wrapper.text()
    expect(text).toContain("'42'")
    expect(text).toContain('http-nio-1')
    expect(text).toContain('com.example.TodoRepository.findById(TodoRepository.java:42)')
  })

  it('provides a native keyboard action without changing row pointer behavior', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const row = wrapper.get('tr.sql-row')
    const toggle = row.get('button.sql-row-toggle')
    expect(toggle.element.tagName).toBe('BUTTON')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')

    await row.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })

  it('filters executions by SQL text', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('input.trace-filter').setValue('insert')
    const executions = wrapper.get('table.sql-table').text()
    expect(executions).toContain('insert into todo(title) values (?)')
    expect(executions).not.toContain('select * from todo where id = ?')
  })

  it('toggles recording when the pause action is clicked', async () => {
    const paused = traceReport({capturing: false})
    const fetchMock = stubFetch({overrides: {'api/sql-trace/recording': paused}})

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-warning').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/sql-trace/recording',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: false})})
    )
  })

  it('ranks statements over the retained window and states that window', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/sql-trace/insights', expect.anything())
    const ranking = wrapper.get('table.sql-ranking-table').text()
    expect(ranking).toContain('select * from todo where id = ?')
    expect(ranking).toContain('possible N+1')
    expect(ranking).toContain('75.0%')

    const text = wrapper.text()
    expect(text).toContain('retained trace window')
    expect(text).toContain('not lifetime metrics')
    expect(text).toContain('3 older executions already evicted')
    expect(text).toContain('7 distinct statements were retained')
  })

  it('re-sorts the ranking when another criterion is selected', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const firstByTotal = wrapper.findAll('table.sql-ranking-table tbody tr')[0].text()
    expect(firstByTotal).toContain('select * from todo where id = ?')

    await wrapper.get('#sql-ranking-metric').setValue('maxDurationMillis')
    const firstByMax = wrapper.findAll('table.sql-ranking-table tbody tr')[0].text()
    expect(firstByMax).toContain('insert into todo(title) values (?)')

    await wrapper.get('#sql-ranking-metric').setValue('errorCount')
    expect(wrapper.findAll('table.sql-ranking-table tbody tr')[0].text()).toContain('insert into todo')

    await wrapper.get('#sql-ranking-metric').setValue('p99DurationMillis')
    expect(wrapper.findAll('table.sql-ranking-table tbody tr')[0].text()).toContain('insert into todo')
  })

  it('offers the tail percentiles as ranking criteria, not just averages', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const options = wrapper.findAll('#sql-ranking-metric option').map((option) => option.text())
    expect(options).toContain('p95 time')
    expect(options).toContain('p99 time')
  })

  it('never ranks a statement on a criterion it scores zero on', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('#sql-ranking-metric').setValue('errorCount')
    const rows = wrapper.findAll('table.sql-ranking-table tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('insert into todo')
  })

  it('bypasses the ranking cadence when the user asks for a refresh', async () => {
    const fetchMock = stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const insightsCalls = () => fetchMock.mock.calls.filter(([url]) => url === 'api/sql-trace/insights').length
    expect(insightsCalls()).toBe(1)

    // Pressing refresh is an explicit request for current evidence, so it must re-read the rankings even
    // though the cadence that throttles stream-driven reloads has not elapsed.
    wrapper.getComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()
    expect(insightsCalls()).toBe(2)
  })

  it('says the criterion cannot separate the window instead of hiding the retained statements', async () => {
    const insights = insightsReport()
    insights.statements = insights.statements.map((statement) => ({...statement, errorCount: 0}))
    stubFetch({insights})

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('#sql-ranking-metric').setValue('errorCount')
    // An in-memory database can round every duration down to zero: the retained evidence still matters,
    // so it stays visible and the panel says the criterion could not rank it.
    expect(wrapper.findAll('table.sql-ranking-table tbody tr')).toHaveLength(insights.statements.length)
    expect(wrapper.text()).toContain('No retained statement records a non-zero errors in this window')
  })

  it('discloses that a deep link covers only part of a heavily repeated statement', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const firstRow = wrapper.findAll('table.sql-ranking-table tbody tr')[0]
    expect(firstRow.text()).toContain('first 1')

    await firstRow
      .findAll('button')
      .find((button) => button.text() === 'Executions')
      .trigger('click')
    expect(wrapper.text()).toContain('ran more times than BootUI keeps deep links for')
  })

  it('shows per-route database totals with the correlation evidence behind them', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const routes = wrapper.get('table.sql-route-table').text()
    expect(routes).toContain('/api/todos/{id}')
    expect(routes).toContain('4 by trace id')
    expect(routes).toContain('2 by serving thread')
    expect(routes).toContain('masked path')
    expect(wrapper.text()).toContain('9 routes contributed database time')
  })

  it('keeps unattributed and ambiguous work in explicit visible buckets', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Unattributed')
    expect(text).toContain('Ambiguous')
    expect(text).toContain('No captured request was in flight when these statements ran.')
    expect(text).toContain('More than one captured request was an equally plausible source.')
    expect(text).toContain('BootUI never invents a request relationship.')
  })

  it('expands a route to its heaviest normalized statements', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('table.sql-route-table tbody button').trigger('click')
    const expanded = wrapper.get('table.sql-route-table tr.sql-detail-row').text()
    expect(expanded).toContain('select * from todo where id = ?')
    expect(expanded).toContain('Only the heaviest statements are listed for this route')
  })

  it('deep-links a ranked statement to exactly its retained executions', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const link = wrapper
      .findAll('table.sql-ranking-table tbody button')
      .find((button) => button.text() === 'Executions')
    expect(link.classes()).toContain('sql-executions-link')
    expect(link.get('i').classes()).toContain('bi-list-ul')
    await link.trigger('click')

    const executions = wrapper.get('table.sql-table').text()
    expect(executions).toContain('select * from todo where id = ?')
    expect(executions).not.toContain('insert into todo(title) values (?)')
    expect(wrapper.text()).toContain('Showing only the retained executions linked from')

    const showAll = wrapper.findAll('button').find((button) => button.text() === 'Show all executions')
    await showAll.trigger('click')
    expect(wrapper.get('table.sql-table').text()).toContain('insert into todo(title) values (?)')
  })

  it('deep-links a route to exactly the executions attributed to it', async () => {
    stubFetch()

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const link = wrapper.findAll('table.sql-route-table tbody button').find((button) => button.text() === 'Executions')
    await link.trigger('click')

    const executions = wrapper.get('table.sql-table').text()
    expect(executions).toContain('select * from todo where id = ?')
    expect(executions).not.toContain('insert into todo(title) values (?)')
  })

  it('falls back to the legacy statement list when rankings are unavailable', async () => {
    stubFetch({
      insights: {
        available: false,
        unavailableReason: 'SQL tracing is not active',
        capturing: false,
        window: null,
        statements: [],
        topPerCriterion: 10,
        statementsTruncated: false,
        distinctStatements: 0,
        attribution: {available: false, unavailableReason: 'SQL tracing is not active'},
        notes: []
      }
    })

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(wrapper.text()).toContain('Most frequent statements')
    expect(wrapper.text()).not.toContain('Statement rankings')
  })

  it('clears the trace when the clear action is confirmed', async () => {
    const cleared = traceReport({stats: {...traceReport().stats, totalQueries: 0}, entries: [], topStatements: []})
    const fetchMock = stubFetch({overrides: {'api/sql-trace/clear': cleared}})

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/sql-trace/clear', {method: 'POST'})
    expect(wrapper.text()).toContain('No SQL has been captured yet')
  })
})
