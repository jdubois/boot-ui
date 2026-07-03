import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import LiveActivity from './LiveActivity.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function requestEntry(overrides = {}) {
  return {
    id: 'req-1',
    type: 'REQUEST',
    timestamp: 1700000000000,
    severity: 'OK',
    summary: 'GET /api/todos → 200',
    detail: '6 SQL statement(s), 60 ms in SQL',
    durationMs: 120,
    correlationId: null,
    method: 'GET',
    path: '/api/todos',
    status: 200,
    thread: 'http-nio-1',
    profileable: true,
    parentId: null,
    securedPrincipal: null,
    sqlNPlusOneSuspected: false,
    ...overrides
  }
}

function activityReport(overrides = {}) {
  return {
    available: true,
    kpis: {
      requestsPerMinute: 12,
      errorRatePercent: 0,
      p50LatencyMs: 40,
      p95LatencyMs: 120,
      sqlPerMinute: 6,
      slowestEndpoint: null,
      slowestEndpointMs: null,
      activeExceptionCount: 0,
      healthStatus: 'UP',
      heapUsedBytes: 104857600
    },
    sources: ['http', 'sql'],
    warnings: [],
    typeCounts: {REQUEST: 1, SQL: 0, EXCEPTION: 0, SECURITY: 0},
    entries: [requestEntry()],
    ...overrides
  }
}

function requestProfile(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    request: {
      method: 'GET',
      path: '/api/todos',
      status: 200,
      durationMs: 120,
      principal: null,
      traceId: null
    },
    sql: [],
    sqlGroups: [
      {
        sql: 'select * from todo where id = ?',
        category: 'SELECT',
        executions: 6,
        totalDurationMillis: 60,
        maxDurationMillis: 20,
        potentialNPlusOne: true,
        callSites: ['com.example.TodoRepository.findById(TodoRepository.java:42)']
      }
    ],
    sqlCorrelationApproximate: false,
    exceptions: [],
    security: [],
    trace: null,
    timing: {sqlCount: 6, sqlMs: 60, sqlPercent: 50},
    notes: [],
    ...overrides
  }
}

function stubFetch(activity, profile) {
  return vi.fn((url) => {
    if (typeof url === 'string' && url.startsWith('api/activity/request/')) {
      return Promise.resolve(jsonResponse(profile))
    }
    return Promise.resolve(jsonResponse(activity))
  })
}

describe('LiveActivity', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('renders a list-level N+1 badge for a request with a suspected N+1 pattern', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({entries: [requestEntry({sqlNPlusOneSuspected: true})]}), requestProfile())
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    const row = wrapper.get('tr.activity-row-clickable')
    expect(row.text()).toContain('N+1')
  })

  it('does not render the N+1 badge for a request without a suspected pattern', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mount(LiveActivity)
    await flushPromises()

    const row = wrapper.get('tr.activity-row-clickable')
    expect(row.text()).not.toContain('N+1')
  })

  it('shows call sites for a flagged SQL group in the request profile drawer', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({entries: [requestEntry({sqlNPlusOneSuspected: true})]}), requestProfile())
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const drawer = wrapper.get('.activity-drawer')
    expect(drawer.text()).toContain('N+1 · 6 identical')
    expect(drawer.text()).toContain('at com.example.TodoRepository.findById(TodoRepository.java:42)')
  })

  it('includes N+1 call sites when copying the plain-text profile report', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({entries: [requestEntry({sqlNPlusOneSuspected: true})]}), requestProfile())
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const copyButton = wrapper.findAll('button').find((b) => b.text().includes('Copy profile'))
    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    const report = writeText.mock.calls[0][0]
    expect(report).toContain('[N+1]')
    expect(report).toContain('at com.example.TodoRepository.findById(TodoRepository.java:42)')
  })
})
