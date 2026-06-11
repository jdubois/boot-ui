import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SqlTrace from './SqlTrace.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function traceReport(overrides = {}) {
  return {
    available: true,
    capturing: true,
    captureParameters: true,
    bufferSize: 200,
    totalCaptured: 3,
    slowQueryThresholdMillis: 100,
    stats: {
      totalQueries: 2,
      totalDurationMillis: 30,
      maxDurationMillis: 25,
      avgDurationMillis: 15,
      slowQueries: 1,
      failedQueries: 0,
      selectCount: 1,
      updateCount: 1,
      batchCount: 0,
      otherCount: 0
    },
    entries: [
      {
        id: 2,
        timestamp: 1700000000000,
        sql: 'select * from todo where id = ?',
        statementType: 'PREPARED',
        operation: 'QUERY',
        durationMillis: 25,
        success: true,
        errorMessage: null,
        affectedRows: null,
        batchSize: 0,
        connectionId: 'conn-1',
        slow: false,
        parameters: ["'42'"]
      },
      {
        id: 1,
        timestamp: 1700000000000,
        sql: 'insert into todo(title) values (?)',
        statementType: 'PREPARED',
        operation: 'UPDATE',
        durationMillis: 5,
        success: true,
        errorMessage: null,
        affectedRows: 1,
        batchSize: 0,
        connectionId: 'conn-1',
        slow: false,
        parameters: ["'buy milk'"]
      }
    ],
    unavailableReason: null,
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
          entries: []
        })
      )
    )

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/sql-trace', expect.anything())
    expect(wrapper.text()).toContain('No DataSource bean is available')
  })

  it('renders captured executions, stats, and parameters', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('select * from todo where id = ?')
    expect(text).toContain('insert into todo(title) values (?)')
    expect(text).toContain("'42'")
    expect(text).toContain('QUERY')
    expect(text).toContain('UPDATE')
    expect(text).toContain('captured since startup')
  })

  it('filters executions by SQL text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(traceReport())))

    wrapper = mount(SqlTrace, {props: {panel: {id: 'sql-trace'}}})
    await flushPromises()

    await wrapper.get('input.trace-filter').setValue('insert')
    const text = wrapper.text()
    expect(text).toContain('insert into todo(title) values (?)')
    expect(text).not.toContain('select * from todo where id = ?')
  })

  it('clears the trace when the clear action is confirmed', async () => {
    const cleared = traceReport({stats: {...traceReport().stats, totalQueries: 0}, entries: []})
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
