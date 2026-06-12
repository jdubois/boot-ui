import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SqlTrace from './SqlTrace.vue'

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
        durationMillis: 25,
        success: true,
        errorMessage: null,
        affectedRows: null,
        batchSize: 0,
        connectionId: 'conn-1',
        thread: 'http-nio-1',
        slow: false,
        parameters: ["'42'"]
      },
      {
        id: 1,
        timestamp: 1700000000000,
        sql: 'insert into todo(title) values (?)',
        statementType: 'PREPARED',
        category: 'INSERT',
        durationMillis: 5,
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
        potentialNPlusOne: true
      }
    ],
    warnings: ['Bound parameter values are captured in clear text.'],
    ...overrides
  }
}

describe('SqlTrace', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the unavailable reason when no DataSource is present', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          available: false,
          unavailableReason: 'No DataSource bean is available',
          stats: {totalQueries: 0},
          entries: [],
          topStatements: [],
          warnings: []
        })
      )
    )

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/sql-trace', expect.anything())
    expect(wrapper.text()).toContain('No DataSource bean is available')
  })

  it('renders captured executions, stats, warnings, and the N+1 hint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('select * from todo where id = ?')
    expect(text).toContain('insert into todo(title) values (?)')
    expect(text).toContain('SELECT')
    expect(text).toContain('INSERT')
    expect(text).toContain('Most frequent statements')
    expect(text).toContain('possible N+1')
    expect(text).toContain('captured since startup')
    expect(text).toContain('captured in clear text')
  })

  it('reveals parameters and thread when a row is expanded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(wrapper.text()).not.toContain('http-nio-1')
    await wrapper.get('tr.sql-row').trigger('click')
    const text = wrapper.text()
    expect(text).toContain("'42'")
    expect(text).toContain('http-nio-1')
  })

  it('filters executions by SQL text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('input.trace-filter').setValue('insert')
    const executions = wrapper.get('table.sql-table').text()
    expect(executions).toContain('insert into todo(title) values (?)')
    expect(executions).not.toContain('select * from todo where id = ?')
  })

  it('toggles recording when the pause action is clicked', async () => {
    const paused = traceReport({capturing: false})
    const fetchMock = vi.fn((url) => {
      if (url === 'api/sql-trace/recording') return Promise.resolve(jsonResponse(paused))
      return Promise.resolve(jsonResponse(traceReport()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-warning').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/sql-trace/recording',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: false})})
    )
  })

  it('clears the trace when the clear action is confirmed', async () => {
    const cleared = traceReport({stats: {...traceReport().stats, totalQueries: 0}, entries: [], topStatements: []})
    const fetchMock = vi.fn((url) => {
      if (url === 'api/sql-trace/clear') return Promise.resolve(jsonResponse(cleared))
      return Promise.resolve(jsonResponse(traceReport()))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/sql-trace/clear', {method: 'POST'})
    expect(wrapper.text()).toContain('No SQL has been captured yet')
  })
})
