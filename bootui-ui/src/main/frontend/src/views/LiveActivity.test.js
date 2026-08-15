import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import {safeLocalStorage} from '../utils/safeStorage.js'
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
      heapUsedBytes: 104857600,
      restCallErrorRatePercent: 12.5,
      restCallP95LatencyMs: 240
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

function mountLiveActivity(options = {}) {
  const {global: globalOptions = {}, ...rest} = options
  return mount(LiveActivity, {
    ...rest,
    global: {
      stubs: {RouterLink: {template: '<a><slot /></a>'}},
      ...globalOptions
    }
  })
}

describe('LiveActivity', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('renders the cache hit ratio KPI tile when cache events are captured', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({kpis: {...activityReport().kpis, cacheHitRatioPercent: 75}}), requestProfile())
    )

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.text()).toContain('Cache hit ratio')
    expect(wrapper.text()).toContain('75%')
  })

  it('renders a dash for the cache hit ratio KPI tile when no cache events have been captured', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.text()).toContain('Cache hit ratio')
    expect(wrapper.text()).toContain('—')
  })

  it('keeps filters usable when browser storage reads and writes are denied', async () => {
    vi.stubGlobal('localStorage', {
      getItem() {
        throw new DOMException('Read denied', 'SecurityError')
      },
      setItem() {
        throw new DOMException('Quota denied', 'QuotaExceededError')
      },
      removeItem() {
        throw new DOMException('Remove denied', 'SecurityError')
      }
    })
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()
    const filter = wrapper.get('#activity-text-filter')
    await filter.setValue('/api/orders')

    expect(filter.element.value).toBe('/api/orders')
    expect(wrapper.find('button').text()).toBeTruthy()
    await filter.setValue('')
  })

  it('renders a list-level N+1 badge for a request with a suspected N+1 pattern', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({entries: [requestEntry({sqlNPlusOneSuspected: true})]}), requestProfile())
    )

    wrapper = mountLiveActivity()
    await flushPromises()

    const row = wrapper.get('tr.activity-row-clickable')
    expect(row.text()).toContain('N+1')
  })

  it('does not render the N+1 badge for a request without a suspected pattern', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    const row = wrapper.get('tr.activity-row-clickable')
    expect(row.text()).not.toContain('N+1')
  })

  it('keeps row pointer activation and nested keyboard actions independent', async () => {
    const child = requestEntry({
      id: 'sql-1',
      parentId: 'req-1',
      profileable: false,
      type: 'SQL',
      summary: 'select from todo'
    })
    const fetchMock = stubFetch(activityReport({entries: [requestEntry(), child]}), requestProfile())
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()

    const row = wrapper.get('tr.activity-row-clickable')
    expect(row.attributes('role')).toBeUndefined()
    expect(row.attributes('tabindex')).toBeUndefined()

    const disclosure = row.get('button.activity-disclosure')
    await disclosure.trigger('keydown', {key: 'Enter'})
    await disclosure.trigger('click')
    expect(fetchMock.mock.calls.filter(([url]) => String(url).startsWith('api/activity/request/'))).toHaveLength(0)

    await row.trigger('click')
    await flushPromises()

    await row.get('button.btn-outline-primary').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).startsWith('api/activity/request/'))).toHaveLength(2)
  })

  it('renders a scheduled-task-run entry with its own icon and links the KPI card to the Scheduled Tasks panel', async () => {
    const scheduledEntry = {
      id: 'sched-1',
      type: 'SCHEDULED',
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
          typeCounts: {REQUEST: 0, SQL: 0, EXCEPTION: 0, SECURITY: 0, SCHEDULED: 1},
          entries: [scheduledEntry]
        }),
        requestProfile()
      )
    )

    wrapper = mountLiveActivity()
    await flushPromises()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('SCHEDULED')
    expect(row.find('i.bi-clock-history').exists()).toBe(true)

    const scheduledLink = wrapper.findAll('a').find((a) => a.text().includes('Scheduled failures'))
    expect(scheduledLink).toBeTruthy()
    expect(scheduledLink.text()).toContain('3')
  })

  it('renders a mail entry with a deep link to its message in the Email panel', async () => {
    const mailEntry = {
      id: 'msg-1',
      type: 'MAIL',
      timestamp: 1700000000000,
      severity: 'OK',
      summary: 'Order shipped',
      detail: 'to customer@example.com',
      durationMs: null,
      correlationId: null,
      method: null,
      path: null,
      status: null,
      thread: 'mail-1',
      profileable: false,
      parentId: null,
      securedPrincipal: null,
      sqlNPlusOneSuspected: false
    }
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport({
          typeCounts: {REQUEST: 0, SQL: 0, EXCEPTION: 0, SECURITY: 0, MAIL: 1},
          entries: [mailEntry]
        }),
        requestProfile()
      )
    )

    wrapper = mountLiveActivity()
    await flushPromises()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('MAIL')
    expect(row.find('i.bi-envelope').exists()).toBe(true)

    const mailLink = row.find('[title="Open in Email"]')
    expect(mailLink.exists()).toBe(true)
  })

  it('shows call sites for a flagged SQL group in the request profile drawer', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({entries: [requestEntry({sqlNPlusOneSuspected: true})]}), requestProfile())
    )

    wrapper = mountLiveActivity()
    await flushPromises()

    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const drawer = wrapper.get('.activity-drawer')
    expect(drawer.text()).toContain('N+1 · 6 identical')
    expect(drawer.text()).toContain('at com.example.TodoRepository.findById(TodoRepository.java:42)')
  })

  it('restores focus to the drawer opener after close button, Escape, and backdrop closes', async () => {
    vi.stubGlobal('requestAnimationFrame', (callback) => callback())
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity({attachTo: document.body})
    await flushPromises()
    const opener = wrapper.get('button.bootui-keyboard-target')

    for (const close of ['button', 'escape', 'backdrop']) {
      opener.element.focus()
      await opener.trigger('click')
      await flushPromises()
      expect(wrapper.get('.activity-drawer').element).toBe(document.activeElement)

      if (close === 'button') {
        await wrapper.get('.activity-drawer .btn-close').trigger('click')
      } else if (close === 'escape') {
        window.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))
      } else {
        await wrapper.get('.activity-drawer-backdrop').trigger('click')
      }

      await flushPromises()
      expect(wrapper.find('.activity-drawer').exists()).toBe(false)
      expect(document.activeElement).toBe(opener.element)
    }
  })

  it('includes N+1 call sites when copying the plain-text profile report', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal(
      'fetch',
      stubFetch(activityReport({entries: [requestEntry({sqlNPlusOneSuspected: true})]}), requestProfile())
    )

    wrapper = mountLiveActivity()
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

  it('renders the outbound REST KPI tile', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.text()).toContain('Outbound errors / p95')
    expect(wrapper.text()).toContain('12.5% / 240 ms')
  })

  it('shows a tip with the current in-memory event count when persistence is not active', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
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

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.text()).not.toContain('Currently saving')
  })

  it('shows the "Use a database" button when persistence is not active', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
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

    wrapper = mountLiveActivity()
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

    wrapper = mountLiveActivity()
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

    wrapper = mountLiveActivity()
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

    wrapper = mountLiveActivity()
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

    wrapper = mountLiveActivity({
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

  it('opens on the feed and offers Live flow as a second reading of the same evidence', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    const options = wrapper.findAll('.activity-view-switcher__option')
    expect(options.map((option) => option.text())).toEqual(['Feed', 'Live flow'])
    expect(options[0].attributes('aria-pressed')).toBe('true')
    expect(options[1].attributes('aria-pressed')).toBe('false')
    expect(wrapper.find('.activity-table').exists()).toBe(true)
  })

  it('swaps the feed for the Live flow map without fetching the map until that mode is opened', async () => {
    const fetchMock = stubFetch(activityReport(), requestProfile())
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('service-map'))).toBe(false)

    await wrapper.findAll('.activity-view-switcher__option')[1].trigger('click')
    await flushPromises()

    expect(wrapper.find('.activity-table').exists()).toBe(false)
    expect(wrapper.findAll('.activity-view-switcher__option')[1].attributes('aria-pressed')).toBe('true')
  })

  it('keeps feed unavailability scoped to Feed while Live flow renders configured JDBC evidence', async () => {
    safeLocalStorage.removeItem('bootui.activity.mode')
    const configuredJdbcMap = {
      available: true,
      unavailableReason: null,
      generatedAt: 1700000000000,
      application: {
        id: 'app',
        kind: 'APPLICATION',
        protocol: 'APPLICATION',
        label: 'This application',
        configured: true,
        observed: true,
        interactions: 0,
        failures: 0,
        outcome: 'OBSERVED_OK'
      },
      nodes: [
        {
          id: 'jdbc:pool:dataSource',
          kind: 'DEPENDENCY',
          protocol: 'JDBC',
          label: 'jdbc:postgresql://localhost:5432/shop',
          detail: 'Connection pool dataSource',
          configured: true,
          observed: false,
          interactions: 0,
          failures: 0,
          distinctOperations: null,
          lastSeen: null,
          outcome: 'NO_EVIDENCE',
          sourcePanelId: 'connection-pools',
          sourceRoute: '/connection-pools',
          sourceLabel: 'Connection Pools',
          note: 'Configured pool with no retained SQL evidence.'
        }
      ],
      edges: [
        {
          id: 'app->jdbc:pool:dataSource',
          fromId: 'app',
          toId: 'jdbc:pool:dataSource',
          protocol: 'JDBC',
          direction: 'OUTBOUND',
          interactions: 0,
          failures: 0,
          lastSeen: null,
          outcome: 'NO_EVIDENCE',
          recentInteractions: []
        }
      ],
      truncation: {
        truncated: false,
        dependencyLimit: 28,
        dependenciesShown: 1,
        dependenciesOmitted: 0,
        interactionLimit: 6
      },
      sources: ['Connection Pools'],
      warnings: []
    }
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        jsonResponse(String(url).includes('service-map') ? configuredJdbcMap : activityReport({available: false}))
      )
    )
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()

    const feedUnavailable =
      'No live activity sources are available yet. Enable HTTP exchange recording, SQL tracing, REST client tracing, exception capture, or security logs to populate this stream.'
    expect(wrapper.text()).toContain(feedUnavailable)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('service-map'))).toBe(false)

    await wrapper.findAll('.activity-view-switcher__option')[1].trigger('click')
    await vi.waitFor(() => {
      expect(fetchMock.mock.calls.some(([url]) => String(url).includes('service-map'))).toBe(true)
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain(feedUnavailable)
    const jdbcNode = wrapper.get('.flow-node--jdbc')
    expect(jdbcNode.attributes('aria-label')).toContain('jdbc:postgresql://localhost:5432/shop')
    expect(jdbcNode.attributes('aria-label')).toContain('configured, no recent evidence')
  })
})
