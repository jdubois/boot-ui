import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import DiagnosticsDashboard from './DiagnosticsDashboard.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    tracingActive: true,
    sources: {
      httpExchanges: true,
      sqlTrace: true,
      exceptions: true,
      securityLogs: false,
      traces: true,
      logTail: false
    },
    totalRequests: 1,
    requests: [
      {
        id: 'trace-abc',
        correlation: 'TRACE',
        traceId: 'abc123def456',
        method: 'GET',
        path: '/api/todos',
        status: 500,
        durationMs: 42,
        principal: 'alice',
        startTimestamp: 1700000000000,
        label: 'GET /api/todos',
        httpCount: 1,
        sqlCount: 2,
        exceptionCount: 1,
        securityCount: 0,
        logCount: 0,
        hasError: true,
        timeline: [
          {
            kind: 'HTTP',
            timestamp: 1700000000000,
            title: 'GET /api/todos',
            detail: null,
            durationMs: 42,
            severity: 'ERROR',
            thread: 'http-1',
            slow: false
          },
          {
            kind: 'SQL',
            timestamp: 1700000000010,
            title: 'SELECT',
            detail: 'select * from todo',
            durationMs: 30,
            severity: 'WARN',
            thread: 'http-1',
            slow: true
          },
          {
            kind: 'EXCEPTION',
            timestamp: 1700000000020,
            title: 'NullPointerException',
            detail: 'boom',
            durationMs: null,
            severity: 'ERROR',
            thread: 'http-1',
            slow: false
          }
        ]
      }
    ],
    unattributed: {
      sqlCount: 1,
      exceptionCount: 0,
      securityCount: 0,
      logCount: 0,
      entries: [
        {
          kind: 'SQL',
          timestamp: 1700000000100,
          title: 'SELECT',
          detail: 'select 1',
          durationMs: 1,
          severity: 'INFO',
          thread: 'pool-1',
          slow: false
        }
      ]
    },
    ...overrides
  }
}

const mountOptions = {props: {panel: {id: 'diagnostics-dashboard'}}, global: {stubs: {RouterLink: true}}}

describe('DiagnosticsDashboard', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the unavailable message when there are no sources', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({
            available: false,
            unavailableReason: 'No diagnostic sources are active',
            tracingActive: false,
            sources: {},
            totalRequests: 0,
            requests: [],
            unattributed: null
          })
        )
    )

    wrapper = mount(DiagnosticsDashboard, mountOptions)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/diagnostics-dashboard', expect.anything())
    expect(wrapper.text()).toContain('No diagnostic sources are active')
  })

  it('renders correlated requests and reveals the timeline on selection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    wrapper = mount(DiagnosticsDashboard, mountOptions)
    await flushPromises()

    expect(wrapper.text()).toContain('GET /api/todos')
    expect(wrapper.text()).toContain('Trace-linked')
    expect(wrapper.text()).toContain('Unattributed signals')

    await wrapper.find('.list-group-item-action').trigger('click')
    expect(wrapper.text()).toContain('NullPointerException')
    expect(wrapper.text()).toContain('select * from todo')
  })

  it('warns when tracing is inactive', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report({tracingActive: false}))))

    wrapper = mount(DiagnosticsDashboard, mountOptions)
    await flushPromises()

    expect(wrapper.text()).toContain('No distributed trace id was observed')
  })
})
