import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import {safeLocalStorage} from '../utils/safeStorage.js'
import LiveActivity from './LiveActivity.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

const routeState = vi.hoisted(() => ({query: {}}))
vi.mock('vue-router', () => ({useRoute: () => routeState}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

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

function stubFetch(activity, profile, journalProfile = {available: false, unavailableReason: 'Not retained.'}) {
  return vi.fn((url) => {
    if (typeof url === 'string' && url.startsWith('api/activity/request/') && url.endsWith('/journal')) {
      return Promise.resolve(jsonResponse(journalProfile))
    }
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

  it('does not present unavailable SQL execution timing as measured zero', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport(),
        requestProfile({
          sqlGroups: [],
          timing: {sqlCount: 0, sqlMs: 0, sqlPercent: 0, restCallCount: 1, restCallMs: 12},
          sections: [{type: 'SQL', available: false, unavailableReason: 'Only SQL preparation was observed.'}]
        })
      )
    )
    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()
    const drawer = wrapper.get('.activity-drawer')
    expect(drawer.text()).toContain('SQL execution timing unavailable')
    expect(drawer.text()).toContain('1 REST client call(s), 12 ms outbound')
    expect(drawer.text()).not.toContain('0 SQL statement(s)')
    expect(drawer.text()).not.toContain('0% of request')
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    safeLocalStorage.removeItem('bootui.activity.flowCollapsed')
    safeLocalStorage.removeItem('bootui.activity.filters')
    vi.useRealTimers()
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

  it('opens the runtime journal status only on demand', async () => {
    const fetchMock = stubFetch(activityReport(), requestProfile())
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => url === 'api/activity/journal')).toBe(false)
    const toggle = wrapper.findAll('button').find((button) => button.text().includes('Recording'))
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-controls')).toBe('activity-runtime-journal')

    await toggle.trigger('click')
    await flushPromises()

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(fetchMock.mock.calls.some(([url]) => url === 'api/activity/journal')).toBe(true)
    expect(wrapper.find('#activity-runtime-journal').exists()).toBe(true)
  })

  it('asks for the runtime journal feed with its route, request, and no-request filters only when chosen', async () => {
    vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
    try {
      safeLocalStorage.removeItem('bootui.activity.filters')
      const fetchMock = stubFetch(activityReport(), requestProfile())
      vi.stubGlobal('fetch', fetchMock)
      const feedUrls = () =>
        fetchMock.mock.calls
          .map(([url]) => url)
          .filter((url) => url === 'api/activity' || url.startsWith('api/activity?'))

      wrapper = mountLiveActivity()
      await flushPromises()

      expect(feedUrls().every((url) => !url.includes('source='))).toBe(true)
      expect(wrapper.find('#activity-route-filter').exists()).toBe(false)

      await wrapper.get('#activity-feed-source').setValue('journal')
      await wrapper.get('#activity-route-filter').setValue('GET /api/orders/{id}')
      await wrapper.get('#activity-no-request').setValue(true)
      vi.advanceTimersByTime(400)
      await flushPromises()

      const last = new URLSearchParams(feedUrls().at(-1).split('?')[1])
      expect(last.get('source')).toBe('journal')
      expect(last.get('route')).toBe('GET /api/orders/{id}')
      expect(last.get('noRequest')).toBe('true')
      expect(last.has('requestId')).toBe(false)

      await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Clear')
        .trigger('click')
      vi.advanceTimersByTime(400)
      await flushPromises()
      const cleared = new URLSearchParams(feedUrls().at(-1).split('?')[1])
      expect(cleared.get('source')).toBe('journal')
      expect(cleared.has('route')).toBe(false)
      expect(cleared.has('noRequest')).toBe(false)
    } finally {
      vi.useRealTimers()
      safeLocalStorage.removeItem('bootui.activity.filters')
    }
  })

  it('loads the runtime journal record of an opened request by its request id', async () => {
    const profile = requestProfile({request: {...requestProfile().request, requestId: '0a1b2c3d4e5f6a7b'}})
    const fetchMock = stubFetch(activityReport(), profile, {
      available: true,
      route: 'GET /api/todos',
      durationMicros: 120000,
      timeline: [],
      gcPauses: [],
      touched: {tables: ['todos']},
      notes: []
    })
    vi.stubGlobal('fetch', fetchMock)
    routeState.query = {request: 'exchange-7'}
    try {
      wrapper = mountLiveActivity()
      await flushPromises()

      expect(fetchMock).toHaveBeenCalledWith('api/activity/request/exchange-7', expect.anything())
      expect(fetchMock).toHaveBeenCalledWith('api/activity/request/0a1b2c3d4e5f6a7b/journal', expect.anything())
      expect(wrapper.text()).toContain('Recorded by the runtime journal')
      expect(wrapper.text()).toContain('todos')
    } finally {
      routeState.query = {}
    }
  })

  it('offers the journal filters when the default feed comes from the runtime journal', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    vi.stubGlobal('fetch', stubFetch(activityReport({sources: ['Runtime journal']}), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.find('#activity-route-filter').exists()).toBe(true)
    expect(
      wrapper
        .get('#activity-feed-source')
        .findAll('option')
        .map((option) => option.text())
    ).toEqual(['Default', 'Runtime journal', 'Panel buffers'])
  })

  it('shows a retained journal profile when its HTTP-exchange details have been evicted', async () => {
    const fetchMock = stubFetch(
      activityReport({sources: ['Runtime journal']}),
      requestProfile({available: false, unavailableReason: 'Request req-1 is no longer in the buffer.', request: null}),
      {
        available: true,
        route: 'GET /api/todos',
        durationMicros: 120000,
        status: 200,
        timeline: [{source: 'sql', label: 'select from todo', offsetMillis: 2, durationMicros: 1000}],
        gcPauses: [],
        touched: {tables: ['todo']},
        notes: []
      }
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()

    await wrapper.find('.activity-table tbody tr .bootui-keyboard-target').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/activity/request/req-1/journal', expect.anything())
    expect(wrapper.find('.request-journal').exists()).toBe(true)
    expect(wrapper.text()).toContain('Recorded by the runtime journal')
    expect(wrapper.text()).toContain('HTTP-exchange details unavailable')
    expect(wrapper.text()).toContain('select from todo')
  })

  it('opens scheduled executions directly from the journal without an HTTP-exchange lookup', async () => {
    const fetchMock = stubFetch(
      activityReport({
        sources: ['Runtime journal'],
        entries: [requestEntry({id: 'scheduled-1', type: 'SCHEDULED', summary: 'Cleanup', profileable: true})]
      }),
      requestProfile({available: false, unavailableReason: 'not an HTTP request'}),
      {
        available: true,
        route: 'Scheduled: Cleanup',
        durationMicros: 25000,
        status: null,
        timeline: [],
        gcPauses: [],
        touched: {tables: []},
        notes: []
      }
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()

    await wrapper.find('.activity-table tbody tr .bootui-keyboard-target').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/activity/request/scheduled-1/journal', expect.anything())
    expect(fetchMock.mock.calls.some(([url]) => url === 'api/activity/request/scheduled-1')).toBe(false)
    expect(wrapper.text()).toContain('Scheduled: Cleanup')
    expect(wrapper.text()).not.toContain('HTTP-exchange details unavailable')
  })

  it("never shows an earlier row's profile when a second row is opened while the first loads", async () => {
    const first = requestProfile({request: {...requestProfile().request, path: '/api/first'}})
    const second = requestProfile({request: {...requestProfile().request, path: '/api/second'}})
    let releaseFirst
    const firstGate = new Promise((resolve) => {
      releaseFirst = resolve
    })
    const fetchMock = vi.fn((url) => {
      if (url.endsWith('/journal')) return Promise.resolve(jsonResponse({available: false, unavailableReason: 'none'}))
      if (url === 'api/activity/request/req-1') return firstGate.then(() => jsonResponse(first))
      if (url === 'api/activity/request/req-2') return Promise.resolve(jsonResponse(second))
      return Promise.resolve(
        jsonResponse(activityReport({entries: [requestEntry(), requestEntry({id: 'req-2', path: '/api/second'})]}))
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()

    const rows = wrapper.findAll('.activity-table tbody tr .bootui-keyboard-target')
    await rows[0].trigger('click')
    await rows[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('/api/second')

    releaseFirst()
    await flushPromises()

    expect(wrapper.text()).toContain('/api/second')
    expect(wrapper.text()).not.toContain('/api/first')
    expect(wrapper.text()).not.toContain('Loading…')
  })

  it('withholds the journal-only filters while persisted history serves the feed', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    const persisted = activityReport({
      sources: ['Runtime journal'],
      pageInfo: {persistent: true, hasMore: false, nextCursor: null}
    })
    vi.stubGlobal('fetch', stubFetch(persisted, requestProfile()))
    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.find('#activity-run-filter').exists()).toBe(false)
    expect(wrapper.find('#activity-route-filter').exists()).toBe(false)
    expect(wrapper.find('#activity-feed-source').exists()).toBe(false)
    expect(wrapper.text()).toContain('keeps no run or request grouping')
  })

  it('does not send a feed source while persisted history serves the feed', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    const persisted = activityReport({
      sources: ['Runtime journal'],
      pageInfo: {persistent: true, hasMore: false, nextCursor: null}
    })
    const fetchMock = stubFetch(persisted, requestProfile())
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()

    const urls = fetchMock.mock.calls.map(([url]) => String(url)).filter((url) => url.startsWith('api/activity'))
    expect(urls.length).toBeGreaterThan(0)
    expect(urls.every((url) => !url.includes('source='))).toBe(true)
  })

  it('does not blame hidden journal filters for an empty persisted page', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    let persistedNow = false
    const base = {entries: [], typeCounts: {}, sources: ['Runtime journal']}
    const memory = activityReport({...base, pageInfo: {persistent: false, hasMore: false, nextCursor: null}})
    const persisted = activityReport({...base, pageInfo: {persistent: true, hasMore: false, nextCursor: null}})
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(persistedNow ? persisted : memory)))
    )
    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('#activity-route-filter').setValue('GET /x')
    persistedNow = true
    await wrapper.get('#activity-errors-only').setValue(true)
    await wrapper.get('#activity-errors-only').setValue(false)
    await new Promise((resolve) => setTimeout(resolve, 400))
    await flushPromises()

    expect(wrapper.find('#activity-route-filter').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('No activity matches the current filters')
    expect(wrapper.text()).toContain('No visible rows on this page')
  })

  it('does not claim nothing was recorded when persisted rows are all hidden', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    const persisted = activityReport({
      entries: [],
      typeCounts: {},
      sources: ['Runtime journal'],
      pageInfo: {persistent: true, hasMore: false, nextCursor: null}
    })
    vi.stubGlobal('fetch', stubFetch(persisted, requestProfile()))
    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.text()).toContain('No visible rows on this page')
    expect(wrapper.text()).not.toContain('No activity recorded yet')
    expect(wrapper.text()).not.toContain('older history is available')
  })

  it('explains an empty persisted page that still has older history', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    const persisted = activityReport({
      entries: [],
      typeCounts: {},
      sources: ['Runtime journal'],
      pageInfo: {persistent: true, hasMore: true, nextCursor: 'abc'}
    })
    vi.stubGlobal('fetch', stubFetch(persisted, requestProfile()))
    wrapper = mountLiveActivity()
    await flushPromises()

    expect(wrapper.text()).toContain('No visible rows on this page')
    expect(wrapper.text()).not.toContain('No activity recorded yet')
    expect(wrapper.text()).toContain('Load older activity')
  })

  it('asks for one run only when a run id is entered', async () => {
    vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
    try {
      safeLocalStorage.removeItem('bootui.activity.filters')
      const fetchMock = stubFetch(activityReport({sources: ['Runtime journal']}), requestProfile())
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountLiveActivity()
      await flushPromises()
      await wrapper.get('#activity-run-filter').setValue('run-3')
      vi.advanceTimersByTime(400)
      await flushPromises()
      const feedUrls = fetchMock.mock.calls.map(([url]) => url).filter((url) => url.startsWith('api/activity?'))
      expect(new URLSearchParams(feedUrls.at(-1).split('?')[1]).get('run')).toBe('run-3')
    } finally {
      vi.useRealTimers()
      safeLocalStorage.removeItem('bootui.activity.filters')
    }
  })

  it.each(['resolve', 'reject'])(
    'ignores an old-filter cursor page that %ss without changing the new page or its busy state',
    async (settlement) => {
      vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
      safeLocalStorage.removeItem('bootui.activity.filters')
      let finishOld
      let failOld
      const oldPage = new Promise((resolve, reject) => {
        finishOld = resolve
        failOld = reject
      })
      let finishNew
      const newPage = new Promise((resolve) => {
        finishNew = resolve
      })
      const head = (cursor, entries) =>
        activityReport({
          entries,
          pageInfo: {persistent: true, hasMore: true, nextCursor: cursor}
        })
      const fetchMock = vi.fn((url) => {
        const query = new URLSearchParams(String(url).split('?')[1])
        if (query.get('cursor') === 'old-cursor') return oldPage
        if (query.get('cursor') === 'new-cursor') return newPage
        return Promise.resolve(
          jsonResponse(
            head(query.has('severity') ? 'new-cursor' : 'old-cursor', [
              requestEntry({severity: 'ERROR', summary: 'Current head'})
            ])
          )
        )
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountLiveActivity()
      await flushPromises()
      const olderButton = () =>
        wrapper.findAll('button').find((button) => ['Load older activity', 'Loading…'].includes(button.text()))
      await olderButton().trigger('click')
      await wrapper.get('#activity-severity-filter').setValue('ERROR')
      await vi.advanceTimersByTimeAsync(301)
      await flushPromises()
      expect(olderButton().attributes('disabled')).toBeUndefined()
      await olderButton().trigger('click')
      expect(olderButton().attributes('disabled')).toBeDefined()

      if (settlement === 'resolve') {
        finishOld(
          jsonResponse(
            head('wrong-cursor', [
              requestEntry({
                id: 'old-query',
                severity: 'ERROR',
                summary: 'Obsolete page'
              })
            ])
          )
        )
      } else failOld(new Error('Obsolete page failed'))
      await flushPromises()
      expect(wrapper.text()).not.toContain('Obsolete page')
      expect(olderButton().attributes('disabled')).toBeDefined()

      finishNew(
        jsonResponse(
          head('next-new-cursor', [
            requestEntry({
              id: 'new-query',
              severity: 'ERROR',
              summary: 'Current older page'
            })
          ])
        )
      )
      await flushPromises()
      expect(wrapper.text()).toContain('Current older page')
      expect(olderButton().attributes('disabled')).toBeUndefined()
      await olderButton().trigger('click')
      await flushPromises()
      expect(fetchMock.mock.calls.some(([url]) => String(url).includes('cursor=next-new-cursor'))).toBe(true)
      vi.useRealTimers()
    }
  )

  it('invalidates older pages when the backing feed becomes in-memory or its source changes', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    let finishOld
    const oldPage = new Promise((resolve) => {
      finishOld = resolve
    })
    let memory = false
    const persisted = activityReport({
      pageInfo: {persistent: true, hasMore: true, nextCursor: 'old-cursor'}
    })
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        String(url).includes('cursor=') ? oldPage : Promise.resolve(jsonResponse(memory ? activityReport() : persisted))
      )
    )
    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Load older activity')
      .trigger('click')
    memory = true
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()
    await wrapper.get('#activity-feed-source').setValue('buffers')
    finishOld(
      jsonResponse(
        activityReport({
          entries: [requestEntry({id: 'obsolete', summary: 'Obsolete persisted row'})],
          pageInfo: {persistent: true, hasMore: true, nextCursor: 'obsolete-cursor'}
        })
      )
    )
    await flushPromises()
    expect(wrapper.text()).not.toContain('Obsolete persisted row')
    expect(wrapper.text()).not.toContain('Load older activity')
    expect(wrapper.text()).not.toContain('Loading…')
  })

  it('keeps ordinary pagination merged with the current live head and follows the older cursor', async () => {
    const head = (entries, cursor) =>
      activityReport({
        entries,
        pageInfo: {persistent: true, hasMore: cursor !== null, nextCursor: cursor}
      })
    let finishOlder
    const pending = new Promise((resolve) => {
      finishOlder = resolve
    })
    let refreshed = false
    const fetchMock = vi.fn((url) => {
      const cursor = new URLSearchParams(String(url).split('?')[1]).get('cursor')
      if (cursor === 'first-page') return pending
      if (cursor === 'second-page')
        return Promise.resolve(jsonResponse(head([requestEntry({id: 'oldest', summary: 'Oldest page'})], null)))
      return Promise.resolve(
        jsonResponse(head([requestEntry({summary: refreshed ? 'Updated current head' : 'Initial head'})], 'first-page'))
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()
    const olderButton = () => wrapper.findAll('button').find((button) => button.text() === 'Load older activity')
    await olderButton().trigger('click')
    refreshed = true
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()
    finishOlder(
      jsonResponse(
        head(
          [requestEntry({summary: 'Duplicate old head'}), requestEntry({id: 'older', summary: 'Older page'})],
          'second-page'
        )
      )
    )
    await flushPromises()
    expect(wrapper.text()).toContain('Updated current head')
    expect(wrapper.text()).not.toContain('Duplicate old head')
    expect(wrapper.text()).toContain('Older page')
    await olderButton().trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Oldest page')
    expect(olderButton()).toBeUndefined()
  })

  it('ignores a superseded filter head while the new query waits for the outstanding read', async () => {
    vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
    const head = (summary, cursor) =>
      activityReport({
        entries: [requestEntry({severity: 'ERROR', summary})],
        pageInfo: {persistent: true, hasMore: true, nextCursor: cursor}
      })
    let finishOld
    const pending = new Promise((resolve) => {
      finishOld = resolve
    })
    let finishCurrent
    const current = new Promise((resolve) => {
      finishCurrent = resolve
    })
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(head('Initial head', 'initial')))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()
    fetchMock.mockImplementation((url) => (String(url).includes('severity=ERROR') ? current : pending))
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()
    await wrapper.get('#activity-severity-filter').setValue('ERROR')
    await vi.advanceTimersByTimeAsync(301)
    finishOld(jsonResponse(head('Obsolete query head', 'obsolete')))
    await flushPromises()
    expect(wrapper.text()).not.toContain('Obsolete query head')
    expect(wrapper.findAll('button').find((button) => button.text() === 'Load older activity')).toBeUndefined()
    finishCurrent(jsonResponse(head('Current query head', 'current')))
    await flushPromises()
    expect(wrapper.text()).toContain('Current query head')
    expect(wrapper.findAll('button').find((button) => button.text() === 'Load older activity')).toBeTruthy()
  })

  it('says no activity is recorded yet when the feed is empty and nothing narrows it', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    vi.stubGlobal('fetch', stubFetch(activityReport({entries: [], typeCounts: {}}), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    const text = wrapper.get('table tbody').text()
    expect(text).toContain('No activity recorded yet. Send a request to the application')
    expect(text).not.toContain('No activity matches the current filters.')
  })

  it('says no activity matches the filters only while a filter, search, or toggle narrows the feed', async () => {
    safeLocalStorage.removeItem('bootui.activity.filters')
    vi.stubGlobal('fetch', stubFetch(activityReport({sources: ['Runtime journal']}), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()
    const emptyText = () => wrapper.get('table tbody').text()

    await wrapper.get('#activity-severity-filter').setValue('ERROR')
    expect(emptyText()).toContain('No activity matches the current filters.')
    expect(emptyText()).not.toContain('No activity recorded yet.')

    await wrapper.get('#activity-severity-filter').setValue('')
    await wrapper.get('#activity-text-filter').setValue('no-such-path')
    expect(emptyText()).toContain('No activity matches the current filters.')

    await wrapper.get('#activity-text-filter').setValue('')
    await wrapper.get('#activity-errors-only').setValue(true)
    expect(emptyText()).toContain('No activity matches the current filters.')

    await wrapper.get('#activity-errors-only').setValue(false)
    expect(wrapper.text()).toContain('GET /api/todos → 200')
  })

  it('says no activity matches the filters when a journal filter narrows an empty feed', async () => {
    vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
    try {
      safeLocalStorage.removeItem('bootui.activity.filters')
      vi.stubGlobal(
        'fetch',
        stubFetch(activityReport({entries: [], typeCounts: {}, sources: ['Runtime journal']}), requestProfile())
      )

      wrapper = mountLiveActivity()
      await flushPromises()
      expect(wrapper.get('table tbody').text()).toContain('No activity recorded yet.')

      await wrapper.get('#activity-no-request').setValue(true)
      expect(wrapper.get('table tbody').text()).toContain('No activity matches the current filters.')
      expect(wrapper.get('table tbody').text()).not.toContain('No activity recorded yet.')
    } finally {
      vi.useRealTimers()
    }
  })

  it('opens the resource track only on demand', async () => {
    const fetchMock = stubFetch(activityReport(), requestProfile())
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => url === 'api/activity/resources')).toBe(false)
    const toggle = wrapper.findAll('button').find((button) => button.text() === 'Resources')
    expect(toggle.attributes('aria-controls')).toBe('activity-runtime-resources')
    await toggle.trigger('click')
    await flushPromises()

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(fetchMock.mock.calls.some(([url]) => url === 'api/activity/resources')).toBe(true)
    expect(wrapper.find('#activity-runtime-resources').exists()).toBe(true)
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

  it('nests a propagated task under its request with its after-response and running badges', async () => {
    const handoff = requestEntry({
      id: 'running:async-1',
      parentId: 'req-1',
      profileable: false,
      type: 'ASYNC',
      severity: 'WARN',
      summary: 'Async task FutureTask',
      detail: 'still running · ThreadPoolExecutor.runWorker',
      badges: ['RUNNING', 'AFTER_RESPONSE']
    })
    vi.stubGlobal('fetch', stubFetch(activityReport({entries: [requestEntry(), handoff]}), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()

    const child = wrapper.get('tr.activity-child-row')
    expect(child.text()).toContain('ASYNC')
    expect(child.text()).toContain('Async task FutureTask')
    expect(child.findAll('.activity-entry-badge').map((badge) => badge.text())).toEqual(['running', 'after response'])
    expect(child.get('.activity-entry-badge').attributes('title')).toBe('Still running now')
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
    const profileCalls = fetchMock.mock.calls.filter(
      ([url]) => String(url).startsWith('api/activity/request/') && !String(url).endsWith('/journal')
    )
    expect(profileCalls).toHaveLength(2)
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

  it('shows the performance deep dives in the request profile drawer', async () => {
    const journalProfile = {
      available: true,
      requestId: 'req-1',
      route: 'GET /api/todos',
      status: 200,
      durationMicros: 120_000
    }
    const codePath = {
      available: true,
      found: true,
      route: 'GET /api/todos',
      assemblyOnly: false,
      topMethods: [],
      limitations: []
    }
    const fetchMock = vi.fn((url) => {
      if (String(url).endsWith('/journal')) return Promise.resolve(jsonResponse(journalProfile))
      if (String(url).startsWith('api/activity/request/')) return Promise.resolve(jsonResponse(requestProfile()))
      if (String(url).startsWith('api/code-paths/requests/')) return Promise.resolve(jsonResponse(codePath))
      return Promise.resolve(jsonResponse(activityReport()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity({
      global: {
        provide: {
          panels: ref({
            panels: [
              {id: 'runtime-insights', enabled: true, available: true},
              {id: 'code-paths', enabled: true, available: true}
            ]
          })
        }
      }
    })
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const section = wrapper.get('.activity-drawer .request-code-path')
    expect(section.text()).toContain('Performance deep dives')
    expect(section.text()).toContain('Open the JFR profile in Runtime Insights')
    expect(section.text()).toContain('Open GET /api/todos in Code Paths')
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

  it('previews Copy for AI with each correlated exception detail and copies exactly the preview', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    const exceptionDetail = {
      group: {id: 'g-1', exceptionClassName: 'java.lang.IllegalStateException', message: 'boom'},
      frames: [
        {
          declaringClass: 'com.example.TodoService',
          methodName: 'load',
          fileName: 'TodoService.java',
          lineNumber: 10,
          applicationFrame: true
        }
      ],
      causes: [],
      occurrences: []
    }
    const profile = requestProfile({
      exceptions: [
        {
          exceptionClassName: 'java.lang.IllegalStateException',
          message: 'boom',
          location: null,
          timestamp: 1700000000010,
          thread: 'http-nio-1',
          handler: null,
          source: 'web',
          exceptionGroupId: 'g-1'
        }
      ]
    })
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/exceptions/g-1') return Promise.resolve(jsonResponse(exceptionDetail))
      if (url.startsWith('api/activity/request/')) return Promise.resolve(jsonResponse(profile))
      if (url.startsWith('api/activity')) return Promise.resolve(jsonResponse(activityReport()))
      return Promise.reject(new Error(`unexpected ${url} ${init?.method}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()
    await wrapper.get('.activity-copy-ai').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/exceptions/g-1', {})
    const preview = wrapper.get('.activity-drawer textarea.ai-export-markdown').element.value
    expect(preview).toContain('# BootUI request profile: `GET /api/todos`')
    expect(preview).toContain('### Statement group 1: N+1 suspected')
    expect(preview).toContain('#### Stack trace')
    expect(preview).toContain('→ at com.example.TodoService.load(TodoService.java:10)')
    expect(wrapper.get('.ai-export-omissions').text()).toContain('Nothing was masked, truncated, or left out.')
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)

    const reads = fetchMock.mock.calls.length
    await wrapper.get('.ai-export-copy').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(preview)
    expect(fetchMock).toHaveBeenCalledTimes(reads)

    await wrapper.get('.ai-export-back').trigger('click')
    expect(wrapper.find('.ai-export').exists()).toBe(false)
    expect(wrapper.get('.activity-drawer').text()).toContain('Request profile')
  })

  it('includes N+1 call sites when copying the Markdown profile report', async () => {
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
    expect(report).toContain('### Statement group 1: N+1 suspected')
    expect(report).toContain('```sql\nselect * from todo where id = ?\n```')
    expect(report).toContain('- `com.example.TodoRepository.findById(TodoRepository.java:42)`')
  })

  function profileWithOutboundEvidence(overrides = {}) {
    return requestProfile({
      restCalls: [
        {
          id: 7,
          timestamp: 1700000000010,
          method: 'GET',
          uri: 'https://inventory.example/items?token=******',
          host: 'inventory.example',
          path: '/items',
          status: null,
          durationMillis: 42,
          success: false,
          errorMessage: 'Connection refused',
          slow: false,
          clientType: 'RestClient',
          requestHeaders: {},
          traceId: null,
          thread: 'http-nio-1',
          callSite: 'com.example.InventoryClient.items(InventoryClient.java:12)'
        }
      ],
      cacheAccesses: [
        {
          timestamp: 1700000000020,
          managerName: 'cacheManager',
          cacheName: 'todos',
          operation: 'HIT',
          keyHash: 'a1b2c3d4e5f60718',
          thread: 'http-nio-1'
        }
      ],
      sections: [
        {
          type: 'SQL',
          available: true,
          unavailableReason: null,
          tier: 'SERVING_THREAD',
          total: 6,
          truncated: 0,
          ambiguous: 0
        },
        {type: 'EXCEPTION', available: true, unavailableReason: null, tier: null, total: 0, truncated: 0, ambiguous: 0},
        {type: 'SECURITY', available: true, unavailableReason: null, tier: null, total: 0, truncated: 0, ambiguous: 0},
        {
          type: 'REST_CLIENT',
          available: true,
          unavailableReason: null,
          tier: 'SERVING_THREAD',
          childTiers: ['SERVING_THREAD'],
          total: 3,
          truncated: 2,
          ambiguous: 0
        },
        {
          type: 'SECURITY',
          available: true,
          unavailableReason: null,
          tier: 'TIME_WINDOW',
          childTiers: ['SERVING_THREAD', 'TIME_WINDOW'],
          total: 205,
          truncated: 203,
          ambiguous: 0
        },
        {
          type: 'CACHE',
          available: true,
          unavailableReason: null,
          tier: 'TIME_WINDOW',
          total: 1,
          truncated: 0,
          ambiguous: 0
        }
      ],
      correlationTiers: [
        {tier: 'TRACE_ID', available: true, unavailableReason: null},
        {tier: 'SERVING_THREAD', available: true, unavailableReason: null},
        {tier: 'TIME_WINDOW', available: true, unavailableReason: null}
      ],
      security: [
        {type: 'AUTHENTICATION_SUCCESS', principal: 'alice', timestamp: 1, principalMatched: true, threadMatched: true},
        {type: 'LOGOUT_SUCCESS', principal: null, timestamp: 2, principalMatched: false, threadMatched: false}
      ],
      approximate: true,
      timing: {sqlCount: 6, sqlMs: 60, sqlPercent: 50, restCallCount: 3, restCallMs: 126},
      ...overrides
    })
  }

  it('shows correlated REST client calls and cache accesses with their tier and truncation', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), profileWithOutboundEvidence()))

    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const drawer = wrapper.get('.activity-drawer')
    const sectionByHeading = (heading) =>
      drawer
        .findAll('section')
        .find((section) => section.find('h3').exists() && section.get('h3').text().startsWith(heading))
    const rest = sectionByHeading('REST client calls')
    expect(rest.get('.activity-tier').text()).toBe('serving thread')
    expect(rest.text()).toContain('GET inventory.example/items → failed')
    expect(rest.text()).toContain('Connection refused')
    expect(rest.text()).toContain('at com.example.InventoryClient.items(InventoryClient.java:12)')
    expect(rest.text()).toContain('Showing the first 1 of 3 REST client calls.')
    const cache = sectionByHeading('Cache accesses')
    expect(cache.get('.activity-tier').text()).toBe('time window')
    expect(cache.text()).toContain('HIT todos')
    expect(cache.text()).toContain('key a1b2c3d4e5f60718')
    expect(sectionByHeading('SQL').get('.activity-tier').text()).toBe('serving thread')
    expect(drawer.text()).toContain('Parts of this profile are approximate')
    expect(drawer.text()).toContain('3 REST client call(s), 126 ms outbound')
    const security = sectionByHeading('Security events')
    expect(security.text()).toContain('Showing the first 2 of 205 security events.')
    expect(security.findAll('.activity-child-tier').map((label) => label.text())).toEqual([
      '· serving thread',
      '· time window'
    ])
  })

  it('explains unavailable sections and tiers instead of showing empty evidence', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        activityReport(),
        profileWithOutboundEvidence({
          restCalls: [],
          cacheAccesses: [],
          approximate: false,
          sections: [
            {type: 'SQL', available: true, tier: 'TRACE_ID', total: 6, truncated: 0, ambiguous: 0},
            {type: 'REST_CLIENT', available: true, tier: null, total: 0, truncated: 0, ambiguous: 0},
            {
              type: 'EXCEPTION',
              available: false,
              unavailableReason: 'The Exceptions panel is disabled.',
              tier: null,
              total: 0,
              truncated: 0,
              ambiguous: 0
            },
            {
              type: 'SECURITY',
              available: false,
              unavailableReason: 'Security Logs is not capturing on this application.',
              tier: null,
              total: 0,
              truncated: 0,
              ambiguous: 0
            },
            {
              type: 'CACHE',
              available: false,
              unavailableReason: 'Cache access capture is not available on Quarkus.',
              tier: null,
              total: 0,
              truncated: 0,
              ambiguous: 0
            }
          ],
          correlationTiers: [
            {tier: 'TRACE_ID', available: true, unavailableReason: null},
            {tier: 'SERVING_THREAD', available: false, unavailableReason: 'Event loop.'},
            {tier: 'TIME_WINDOW', available: false, unavailableReason: 'Event loop.'}
          ]
        })
      )
    )

    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const drawer = wrapper.get('.activity-drawer')
    expect(drawer.text()).toContain('No REST client calls correlated to this request.')
    expect(drawer.text()).toContain('Cache access capture is not available on Quarkus.')
    expect(drawer.text()).toContain('The Exceptions panel is disabled.')
    expect(drawer.text()).toContain('Security Logs is not capturing on this application.')
    expect(drawer.text()).toContain(
      'Serving thread and time window correlation are unavailable on this adapter: Event loop.'
    )
    expect(drawer.text()).not.toContain('Parts of this profile are approximate')
  })

  it('renders an older server profile without the added sections', async () => {
    vi.stubGlobal('fetch', stubFetch(activityReport(), requestProfile()))

    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()

    const drawer = wrapper.get('.activity-drawer')
    expect(drawer.text()).not.toContain('REST client calls')
    expect(drawer.text()).not.toContain('Cache accesses')
    expect(drawer.find('.activity-tier').exists()).toBe(false)
  })

  it('copies REST client calls, cache accesses, tiers, and truncation into the profile report', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', stubFetch(activityReport(), profileWithOutboundEvidence()))

    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper.get('tr.activity-row-clickable').trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Copy profile'))
      .trigger('click')
    await flushPromises()

    const report = writeText.mock.calls[0][0]
    expect(report).toContain('- **Correlation:** approximate; some signals were matched by time window only')
    expect(report).toContain('## SQL (exact, serving thread)')
    expect(report).toContain('- `LOGOUT_SUCCESS` · time window')
    expect(report).toContain('Showing the first 2 of 205 security events.')
    expect(report).toContain('## REST client calls (serving thread)')
    expect(report).toContain('- `GET inventory.example/items → failed` · 42 ms')
    expect(report).toContain('  - Error: Connection refused')
    expect(report).toContain('Showing the first 1 of 3 REST client calls.')
    expect(report).toContain('## Cache accesses (time window)')
    expect(report).toContain('- `HIT todos` · key hash `a1b2c3d4e5f60718`')
    expect(report).toContain('## Omitted from this export')
    expect(report).not.toContain('token=')
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

  it.each(['success', 'already-active'])(
    'switches to the database with a %s acknowledgement and a custom table',
    async (status) => {
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
              status,
              message: 'Live Activity is now saving to the "custom_activity" table.',
              tableName: 'custom_activity',
              futureField: true
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
      expect(wrapper.text()).toContain('Live Activity is now saving to the "custom_activity" table.')
      expect(wrapper.findAll('button').find((b) => b.text().includes('Use a database'))).toBeFalsy()
    }
  )

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

  it.each(['', '{', 'null', '{}', '{"status":"unexpected","message":"Saved","tableName":"custom_activity"}'])(
    'does not acknowledge an invalid 2xx datasource response %j and re-reads without retrying the POST',
    async (body) => {
      safeLocalStorage.removeItem('bootui.activity.filters')
      let reads = 0
      const before = activityReport({
        persistenceOption: {active: false, dataSourceAvailable: true, tableName: 'custom_activity'}
      })
      const fetchMock = vi.fn((url) => {
        if (url === 'api/activity/use-existing-datasource') {
          return Promise.resolve(new Response(body, {status: 200}))
        }
        if (url === 'api/activity') {
          reads++
          if (reads > 1) return Promise.reject(new Error('Feed read unavailable'))
        }
        return Promise.resolve(jsonResponse(before))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountLiveActivity()
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes('Use a database'))
        .trigger('click')
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes('Use the existing datasource'))
        .trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('outcome is unknown')
      expect(wrapper.text()).not.toContain('Live Activity is now saving to a database.')
      expect(wrapper.find('.alert-success').exists()).toBe(false)
      expect(reads).toBe(2)
      expect(fetchMock.mock.calls.filter(([url]) => url === 'api/activity/use-existing-datasource')).toHaveLength(1)
      expect(wrapper.text()).toContain('GET /api/todos')
    }
  )

  it.each([
    [403, '', 'HTTP 403'],
    [503, '<html>Unavailable</html>', 'HTTP 503'],
    [409, '{"reason":"Datasource changed"}', 'Datasource changed']
  ])('preserves HTTP %s datasource errors without inventing a success', async (code, body, message) => {
    const before = activityReport({
      persistenceOption: {active: false, dataSourceAvailable: true, tableName: 'custom_activity'}
    })
    const fetchMock = vi.fn((url) =>
      url === 'api/activity/use-existing-datasource'
        ? Promise.resolve(new Response(body, {status: code, headers: {'content-type': 'application/json'}}))
        : Promise.resolve(jsonResponse(before))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountLiveActivity()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Use a database'))
      .trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Use the existing datasource'))
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(message)
    expect(wrapper.find('.alert-success').exists()).toBe(false)
    expect(fetchMock.mock.calls.filter(([url]) => url === 'api/activity/use-existing-datasource')).toHaveLength(1)
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

  describe('route-aware latency KPIs', () => {
    afterEach(() => {
      routeState.query = {}
    })

    it('labels the slowest request with its route and links to that route summary row', async () => {
      vi.stubGlobal(
        'fetch',
        stubFetch(
          activityReport({
            kpis: {
              ...activityReport().kpis,
              slowestEndpoint: '/api/orders/42',
              slowestEndpointMs: 900,
              latencySampleCount: 7,
              slowestEndpointRoute: '/api/orders/{id}',
              slowestEndpointRouteId: 'GET /api/orders/{id}',
              slowestEndpointRouteSource: 'FRAMEWORK_TEMPLATE'
            }
          }),
          requestProfile()
        )
      )

      wrapper = mountLiveActivity({global: {stubs: {RouterLink: RouterLinkStub}}})
      await flushPromises()

      const card = wrapper.get('.activity-kpi-slowest')
      expect(card.get('.activity-kpi-slowest-route').text()).toBe('GET /api/orders/{id}')
      expect(JSON.parse(card.attributes('data-to'))).toEqual({
        path: '/http-exchanges',
        query: {route: 'GET /api/orders/{id}', rank: 'maxDurationMs'}
      })
      expect(card.attributes('title')).toBe(
        'Open GET /api/orders/{id}, the route of the slowest request (/api/orders/42), in HTTP Exchanges'
      )
      expect(wrapper.get('.activity-kpi-latency-samples').text()).toBe('over 7 retained requests')
      expect(JSON.parse(wrapper.get('.activity-why-slow a').attributes('data-to'))).toEqual({
        path: '/runtime-insights',
        query: {q: 'GET /api/orders/{id}'}
      })
    })

    it('falls back to a path search when an older server sends no route', async () => {
      vi.stubGlobal(
        'fetch',
        stubFetch(
          activityReport({
            kpis: {...activityReport().kpis, slowestEndpoint: '/api/orders/42', slowestEndpointMs: 900}
          }),
          requestProfile()
        )
      )

      wrapper = mountLiveActivity({global: {stubs: {RouterLink: RouterLinkStub}}})
      await flushPromises()

      const card = wrapper.get('.activity-kpi-slowest')
      expect(card.get('.activity-kpi-slowest-route').text()).toBe('/api/orders/42')
      expect(JSON.parse(card.attributes('data-to'))).toEqual({
        path: '/http-exchanges',
        query: {q: '/api/orders/42'}
      })
      expect(wrapper.find('.activity-kpi-latency-samples').exists()).toBe(false)
      expect(wrapper.find('.activity-why-slow').exists()).toBe(false)
    })

    it('opens the request profile an HTTP Exchanges link names', async () => {
      routeState.query = {request: 'exchange-7'}
      const fetchMock = stubFetch(activityReport(), requestProfile())
      vi.stubGlobal('fetch', fetchMock)

      wrapper = mountLiveActivity()
      await flushPromises()

      expect(fetchMock).toHaveBeenCalledWith('api/activity/request/exchange-7', expect.anything())
      expect(wrapper.find('[aria-label="Request profile"]').exists()).toBe(true)
      expect(wrapper.text()).toContain('select * from todo where id = ?')
    })
  })
})
