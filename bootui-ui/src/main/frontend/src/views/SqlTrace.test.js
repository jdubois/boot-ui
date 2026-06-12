import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SqlTrace from './SqlTrace.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    recording: true,
    captureParameters: true,
    maxQueries: 200,
    slowQueryThresholdMillis: 200,
    dataSources: ['dataSource'],
    stats: {
      recorded: 2,
      captured: 2,
      evicted: 0,
      totalElapsedMillis: 12,
      maxElapsedMillis: 8,
      avgElapsedMillis: 6,
      slowQueries: 1,
      failedQueries: 0,
      batchExecutions: 0,
      selectCount: 2,
      insertCount: 0,
      updateCount: 0,
      deleteCount: 0,
      otherCount: 0
    },
    queries: [
      {
        id: 2,
        timestamp: Date.now(),
        dataSource: 'dataSource',
        connectionId: 'conn-1',
        type: 'PREPARED',
        category: 'SELECT',
        batch: false,
        batchSize: 0,
        elapsedMillis: 8,
        success: true,
        slow: true,
        error: null,
        thread: 'http-nio-1',
        statements: ['select * from sample_products where active = ?'],
        parameters: ['[true]']
      },
      {
        id: 1,
        timestamp: Date.now(),
        dataSource: 'dataSource',
        connectionId: 'conn-1',
        type: 'STATEMENT',
        category: 'INSERT',
        batch: false,
        batchSize: 0,
        elapsedMillis: 4,
        success: true,
        slow: false,
        error: null,
        thread: 'http-nio-1',
        statements: ['insert into sample_products values (?)'],
        parameters: []
      }
    ],
    topStatements: [
      {
        sql: 'select * from sample_products where active = ?',
        category: 'SELECT',
        executions: 5,
        totalElapsedMillis: 40,
        maxElapsedMillis: 8,
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

  it('reports the unavailable reason when tracing is not active', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          report({
            available: false,
            unavailableReason: 'datasource-proxy is not on the classpath.',
            queries: [],
            topStatements: []
          })
        )
      )
    )

    wrapper = mount(SqlTrace)
    await flushPromises()

    const alert = wrapper.get('.alert-secondary')
    expect(alert.text()).toContain('datasource-proxy is not on the classpath')
  })

  it('renders recorded executions and the N+1 hint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    wrapper = mount(SqlTrace)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/sql-trace', expect.anything())
    expect(wrapper.text()).toContain('sample_products')
    expect(wrapper.findAll('.badge').some((b) => b.text() === 'SELECT')).toBe(true)
    expect(wrapper.text()).toContain('possible N+1')
    // The capture warning is surfaced.
    expect(wrapper.text()).toContain('captured in clear text')
  })

  it('expands a row to reveal parameters and connection metadata', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    wrapper = mount(SqlTrace)
    await flushPromises()

    await wrapper.get('tbody tr.sql-row').trigger('click')

    expect(wrapper.text()).toContain('[true]')
    expect(wrapper.text()).toContain('Data source')
    expect(wrapper.text()).toContain('conn-1')
  })
})
