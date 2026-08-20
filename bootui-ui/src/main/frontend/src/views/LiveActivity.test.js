import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import {safeLocalStorage} from '../utils/safeStorage.js'
import LiveActivity from './LiveActivity.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

// The panel reads `?correlationLookupId=` so HTTP Exchanges can cross-link into it; tests drive that
// query through this mutable stub.
let routeQuery = {}
const routerReplace = vi.fn((to) => {
  routeQuery = {...(to?.query ?? {})}
})
vi.mock('vue-router', () => ({
  useRoute: () => ({
    get query() {
      return routeQuery
    }
  }),
  useRouter: () => ({replace: routerReplace})
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
    safeLocalStorage.removeItem('bootui.activity.flowCollapsed')
    routeQuery = {}
    routerReplace.mockClear()
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

  it('shows Live flow between the KPI summary and activity feed controls by default', async () => {
    const fetchMock = stubFetch(activityReport(), requestProfile())
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()

    const toggle = wrapper.get('[aria-controls="activity-live-flow"]')
    expect(toggle.text()).toContain('Minimize map')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.flow-map').isVisible()).toBe(true)
    expect(wrapper.find('.activity-table').exists()).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('service-map'))).toBe(true)

    const kpis = wrapper.get('.activity-kpis').element
    const flow = wrapper.get('.activity-flow').element
    const controls = wrapper.get('.activity-feed-controls').element
    expect(kpis.compareDocumentPosition(flow) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(flow.compareDocumentPosition(controls) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('remembers when Live flow is minimized without hiding the activity feed', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    await wrapper.get('[aria-controls="activity-live-flow"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[aria-controls="activity-live-flow"]').text()).toContain('Show map')
    expect(wrapper.get('[aria-controls="activity-live-flow"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.flow-map').isVisible()).toBe(false)
    expect(wrapper.find('.activity-table').exists()).toBe(true)
    expect(safeLocalStorage.getItem('bootui.activity.flowCollapsed')).toBe('true')

    wrapper.unmount()
    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.get('[aria-controls="activity-live-flow"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.flow-map').isVisible()).toBe(false)
  })

  it('shows configured Live flow evidence alongside the unavailable feed state', async () => {
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
    await vi.waitFor(() => {
      expect(fetchMock.mock.calls.some(([url]) => String(url).includes('service-map'))).toBe(true)
    })
    await flushPromises()

    const jdbcNode = wrapper.get('.flow-node--jdbc')
    expect(jdbcNode.attributes('aria-label')).toContain('jdbc:postgresql://localhost:5432/shop')
    expect(jdbcNode.attributes('aria-label')).toContain('configured, no recent evidence')
  })

  describe('correlation-id filtering', () => {
    const CORR_LOOKUP = '88b87faa5f574f9b'
    const OTHER_LOOKUP = '74a2f8fde4aec9c7'

    function correlatedReport() {
      return activityReport({
        typeCounts: {REQUEST: 2, SQL: 1},
        entries: [
          requestEntry({
            id: 'req-1',
            correlationIds: [
              {name: 'x-correlation-id', value: '******', masked: true, truncated: false, lookupId: CORR_LOOKUP}
            ],
            correlationLookupIds: [CORR_LOOKUP]
          }),
          {
            id: 'sql-1',
            type: 'SQL',
            timestamp: 1700000000100,
            severity: 'OK',
            summary: 'select * from todo',
            detail: null,
            durationMs: 3,
            correlationId: null,
            method: null,
            path: null,
            status: null,
            thread: 'http-nio-1',
            profileable: false,
            parentId: 'req-1',
            securedPrincipal: null,
            sqlNPlusOneSuspected: false,
            correlationIds: [],
            correlationLookupIds: [CORR_LOOKUP]
          },
          requestEntry({
            id: 'req-2',
            summary: 'GET /api/other → 200',
            path: '/api/other',
            correlationIds: [
              {name: 'x-request-id', value: '******', masked: true, truncated: false, lookupId: OTHER_LOOKUP}
            ],
            correlationLookupIds: [OTHER_LOOKUP]
          })
        ]
      })
    }

    it('offers a chip per captured identifier without revealing the masked value', async () => {
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      const chips = wrapper.findAll('.activity-correlation-chip')
      expect(chips).toHaveLength(2)
      expect(chips[0].text()).toContain('x-correlation-id')
      expect(chips[0].text()).not.toContain('******')
      expect(chips[0].attributes('title')).toContain('masked')
    })

    it('narrows the feed to the request and the activity correlated with it when a chip is used', async () => {
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      await wrapper.findAll('.activity-correlation-chip')[0].trigger('click')
      await flushPromises()

      const text = wrapper.text()
      expect(text).toContain('GET /api/todos')
      expect(text).toContain('select * from todo')
      expect(text).not.toContain('GET /api/other')
      expect(text).toContain('Clear correlation filter')
    })

    it('clears the correlation filter again from the same chip', async () => {
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      await wrapper.findAll('.activity-correlation-chip')[0].trigger('click')
      await flushPromises()
      await wrapper.findAll('.activity-correlation-chip')[0].trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('GET /api/other')
      expect(wrapper.text()).not.toContain('Clear correlation filter')
    })

    it('applies a correlation identity handed over by a cross-link, without any raw identifier', async () => {
      routeQuery.correlationLookupId = CORR_LOOKUP
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      expect(wrapper.text()).toContain('GET /api/todos')
      expect(wrapper.text()).not.toContain('GET /api/other')
    })

    it('ignores a correlation identity that is not a lookup identity at all', async () => {
      routeQuery.correlationLookupId = "'; drop table bootui_activity; --"
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      expect(wrapper.text()).toContain('GET /api/other')
      expect(wrapper.text()).not.toContain('Clear correlation filter')
    })

    it('derives the identity of a typed identifier locally and never sends it to the server', async () => {
      const fetchStub = stubFetch(correlatedReport(), requestProfile())
      vi.stubGlobal('fetch', fetchStub)
      vi.useFakeTimers()

      wrapper = mountLiveActivity()
      await flushPromises()

      await wrapper.find('#activity-correlation-filter').setValue('corr-1')
      await vi.advanceTimersByTimeAsync(300)
      vi.useRealTimers()
      await flushPromises()

      expect(wrapper.text()).toContain('GET /api/todos')
      expect(wrapper.text()).not.toContain('GET /api/other')
      const urls = fetchStub.mock.calls.map((call) => String(call[0]))
      expect(urls.some((url) => url.includes('corr-1'))).toBe(false)
      expect(urls.some((url) => url.includes(CORR_LOOKUP))).toBe(false)
    })

    it('rejects an identifier that could never have been captured', async () => {
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))
      vi.useFakeTimers()

      wrapper = mountLiveActivity()
      await flushPromises()

      await wrapper.find('#activity-correlation-filter').setValue('bad\u0007value')
      await vi.advanceTimersByTimeAsync(300)
      vi.useRealTimers()
      await flushPromises()

      expect(wrapper.text()).toContain('That is not a usable correlation identifier.')
      expect(wrapper.text()).toContain('GET /api/other')
    })

    it('keeps a chip filter when a typed value was still pending derivation', async () => {
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))
      vi.useFakeTimers()

      wrapper = mountLiveActivity()
      await flushPromises()

      await wrapper.find('#activity-correlation-filter').setValue('something-else')
      await wrapper.findAll('.activity-correlation-chip')[0].trigger('click')
      await vi.advanceTimersByTimeAsync(500)
      vi.useRealTimers()
      await flushPromises()

      expect(wrapper.text()).toContain('Clear correlation filter')
      expect(wrapper.text()).toContain('GET /api/todos')
      expect(wrapper.text()).not.toContain('GET /api/other')
    })

    it('drops the cross-link parameter from the URL when the filter is cleared', async () => {
      routeQuery.correlationLookupId = CORR_LOOKUP
      vi.stubGlobal('fetch', stubFetch(correlatedReport(), requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      await wrapper.find('.activity-correlation-banner button').trigger('click')
      await flushPromises()

      expect(routerReplace).toHaveBeenCalled()
      expect(routeQuery.correlationLookupId).toBeUndefined()
      expect(wrapper.text()).toContain('GET /api/other')
    })

    it('shows a non-interactive chip when the exposure policy withholds the identity', async () => {
      const report = correlatedReport()
      report.entries[0].correlationIds = [
        {name: 'x-correlation-id', value: null, masked: true, truncated: false, lookupId: null}
      ]
      report.entries[0].correlationLookupIds = []
      report.entries[1].correlationLookupIds = []
      vi.stubGlobal('fetch', stubFetch(report, requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      const chips = wrapper.findAll('.activity-correlation-chip')
      expect(chips[0].element.tagName).toBe('SPAN')
      expect(chips[0].attributes('title')).toContain('Filtering is unavailable')
    })

    it('explains that identifiers are not persisted when a correlation filter matches nothing', async () => {
      const report = correlatedReport()
      report.pageInfo = {...(report.pageInfo ?? {}), persistent: true}
      routeQuery.correlationLookupId = 'ffffffffffffffff'
      vi.stubGlobal('fetch', stubFetch(report, requestProfile()))

      wrapper = mountLiveActivity()
      await flushPromises()

      expect(wrapper.text()).toContain('No activity matches the current filters.')
      expect(wrapper.text()).toContain('Correlation identifiers are not persisted')
    })
  })
})
