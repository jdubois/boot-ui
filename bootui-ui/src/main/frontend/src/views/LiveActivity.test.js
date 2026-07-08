import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import LiveActivity from './LiveActivity.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

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
    pageInfo: null,
    persistenceOption: {active: false, dataSourceAvailable: false, tableName: 'bootui_activity'},
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

  it('renders a scheduled-task-run entry with its own icon and links the KPI card to the Scheduled Tasks panel', async () => {
    const scheduledEntry = {
      id: 'sched-1',
      type: 'SCHEDULED_TASK',
      timestamp: 1700000000000,
      severity: 'ERROR',
      summary: 'com.example.jobs.NightlyJob.run',
      detail: 'java.lang.IllegalStateException: boom',
      durationMs: 45,
      correlationId: null,
      method: null,
      path: null,
      status: null,
      thread: 'scheduling-1',
      profileable: false,
      parentId: null,
      securedPrincipal: null,
      sqlNPlusOneSuspected: false
    }
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({
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
            heapUsedBytes: 104857600,
            scheduledTaskFailureCount: 3
          },
          typeCounts: {REQUEST: 0, SQL: 0, EXCEPTION: 0, SECURITY: 0, SCHEDULED_TASK: 1},
          entries: [scheduledEntry]
        }),
        requestProfile()
      )
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('SCHEDULED_TASK')
    expect(row.find('i.bi-clock-history').exists()).toBe(true)

    const scheduledLink = wrapper.findAll('router-link').find((a) => a.text().includes('Scheduled failures'))
    expect(scheduledLink).toBeTruthy()
    expect(scheduledLink.text()).toContain('3')
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

  it('shows a tip with the current in-memory event count when persistence is not active', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mount(LiveActivity)
    await flushPromises()

    expect(wrapper.text()).toContain('Currently saving 1 event in memory')
  })

  it('hides the in-memory event count tip once persistence is active', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({
          pageInfo: {persistent: true, nextCursor: null, hasMore: false},
          persistenceOption: {active: true, dataSourceAvailable: true, tableName: 'bootui_activity'}
        }),
        requestProfile()
      )
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    expect(wrapper.text()).not.toContain('Currently saving')
  })

  it('shows the "Use a database" button when persistence is not active', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mount(LiveActivity)
    await flushPromises()

    expect(wrapper.findAll('button').find((b) => b.text().includes('Use a database'))).toBeTruthy()
  })

  it('hides the "Use a database" button once persistence is active', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({
          pageInfo: {persistent: true, nextCursor: null, hasMore: false},
          persistenceOption: {active: true, dataSourceAvailable: true, tableName: 'bootui_activity'}
        }),
        requestProfile()
      )
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    expect(wrapper.findAll('button').find((b) => b.text().includes('Use a database'))).toBeFalsy()
  })

  it('points to setup documentation when no datasource is available', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({persistenceOption: {active: false, dataSourceAvailable: false, tableName: 'bootui_activity'}}),
        requestProfile()
      )
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Use a database'))
      .trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('No')
    expect(wrapper.text()).toContain('DataSource')
    expect(wrapper.text()).toContain('bean was found')
    expect(wrapper.findAll('button').find((b) => b.text().includes('Use the existing datasource'))).toBeFalsy()
    expect(wrapper.get('a[href*="julien-dubois.com"]').text()).toContain('View setup documentation')
  })

  it('offers to switch to the existing datasource when one is already configured', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({persistenceOption: {active: false, dataSourceAvailable: true, tableName: 'bootui_activity'}}),
        requestProfile()
      )
    )

    wrapper = mount(LiveActivity)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Use a database'))
      .trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('reuse the existing one right now')
    expect(wrapper.findAll('button').find((b) => b.text().includes('Use the existing datasource'))).toBeTruthy()
  })

  it('switches to the database when the existing-datasource action is confirmed', async () => {
    let persistedNow = false
    const notPersisted = activityReport({
      persistenceOption: {active: false, dataSourceAvailable: true, tableName: 'bootui_activity'}
    })
    const persisted = activityReport({
      pageInfo: {persistent: true, nextCursor: null, hasMore: false},
      persistenceOption: {active: true, dataSourceAvailable: true, tableName: 'bootui_activity'}
    })
    const fetchMock = vi.fn((url) => {
      if (url === 'api/activity/use-existing-datasource') {
        persistedNow = true
        return Promise.resolve(
          jsonResponse({
            status: 'success',
            message: 'Live Activity is now saving to the "bootui_activity" table.',
            tableName: 'bootui_activity'
          })
        )
      }
      if (typeof url === 'string' && url.startsWith('api/activity/request/')) {
        return Promise.resolve(jsonResponse(requestProfile()))
      }
      return Promise.resolve(jsonResponse(persistedNow ? persisted : notPersisted))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(LiveActivity)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Use a database'))
      .trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Use the existing datasource'))
      .trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/activity/use-existing-datasource',
      expect.objectContaining({method: 'POST', body: JSON.stringify({confirm: true})})
    )
    expect(wrapper.text()).toContain('Live Activity is now saving to the "bootui_activity" table.')
    expect(wrapper.findAll('button').find((b) => b.text().includes('Use a database'))).toBeFalsy()
  })

  it('disables the existing-datasource switch action when the panel is read-only', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({persistenceOption: {active: false, dataSourceAvailable: true, tableName: 'bootui_activity'}}),
        requestProfile()
      )
    )

    wrapper = mount(LiveActivity, {
      props: {panel: {readOnly: true, readOnlyReason: 'BootUI is read-only'}}
    })
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Use a database'))
      .trigger('click')
    await flushPromises()

    const switchButton = wrapper.findAll('button').find((b) => b.text().includes('Use the existing datasource'))
    expect(switchButton.attributes('disabled')).toBeDefined()
  })
})
