import {config, flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {reactive, ref} from 'vue'

import Exceptions from './Exceptions.vue'

const routeState = vi.hoisted(() => ({route: null}))
vi.mock('vue-router', () => ({useRoute: () => routeState.route}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function group(overrides = {}) {
  return {
    id: 'abc123',
    exceptionClassName: 'java.lang.IllegalStateException',
    message: 'token=****** rejected',
    count: 3,
    firstSeen: 1700000000000,
    lastSeen: 1700000005000,
    location: 'com.example.OrderService.place(OrderService.java:42)',
    applicationException: true,
    lastThread: 'http-nio-8080-exec-1',
    lastRequestMethod: 'POST',
    lastRequestPath: '/api/orders',
    lastHandler: 'OrderController#place',
    lastSource: 'web',
    status: 'OPEN',
    regressionCount: 0,
    ...overrides
  }
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    maxGroups: 100,
    totalExceptions: 4,
    groups: [
      group(),
      group({
        id: 'def456',
        exceptionClassName: 'java.lang.NullPointerException',
        message: null,
        count: 1,
        lastSeen: 1700000001000,
        location: 'org.springframework.web.Foo.bar(Foo.java:10)',
        applicationException: false,
        lastSource: 'log',
        lastRequestMethod: null,
        lastRequestPath: null,
        lastHandler: null
      })
    ],
    ...overrides
  }
}

function caughtReport(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    limitations: [],
    settling: 0,
    occurrences: 0,
    findings: 0,
    rows: [],
    ...overrides
  }
}

function detail() {
  return {
    group: group(),
    frames: [
      {
        declaringClass: 'com.example.OrderService',
        methodName: 'place',
        fileName: 'OrderService.java',
        lineNumber: 42,
        applicationFrame: true
      },
      {
        declaringClass: 'org.springframework.web.Dispatcher',
        methodName: 'doDispatch',
        fileName: 'Dispatcher.java',
        lineNumber: 100,
        applicationFrame: false
      }
    ],
    causes: [
      {
        exceptionClassName: 'java.lang.NumberFormatException',
        message: 'For input string: "x"',
        frames: [
          {
            declaringClass: 'java.lang.Integer',
            methodName: 'parseInt',
            fileName: 'Integer.java',
            lineNumber: 580,
            applicationFrame: false
          }
        ],
        commonFrames: 12
      }
    ],
    occurrences: [
      {
        timestamp: 1700000005000,
        thread: 'http-nio-8080-exec-1',
        requestMethod: 'POST',
        requestPath: '/api/orders',
        handler: 'OrderController#place',
        source: 'web'
      }
    ]
  }
}

function mockFetch(routes = {}) {
  const defaults = {
    'api/overview': {},
    'api/exceptions': report(),
    'api/exceptions/caught': caughtReport()
  }
  return vi.fn((url, init) => {
    const key = String(url)
    const route = Object.hasOwn(routes, key) ? routes[key] : defaults[key]
    if (typeof route === 'function') return route(url, init)
    if (route !== undefined) return Promise.resolve(jsonResponse(route))
    return Promise.reject(new Error(`unexpected ${key} ${init?.method}`))
  })
}

// The handler attribution links into the REST API panel, so a router is not needed to render the panel.
config.global.stubs.RouterLink = {props: ['to'], template: '<a :href="JSON.stringify(to)"><slot /></a>'}

describe('Exceptions', () => {
  beforeEach(() => {
    routeState.route = reactive({query: {}})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('opens the exact linked group, not another group of the same class, and preserves the originating request', async () => {
    routeState.route.query = {id: 'second/group', request: 'request-7', q: 'not this filter'}
    const second = group({id: 'second/group', message: 'Second failure'})
    const fetchMock = mockFetch({
      'api/exceptions': report({groups: [group(), second]}),
      'api/exceptions/second%2Fgroup': {...detail(), group: second}
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Exceptions, {attachTo: document.body})
    try {
      await flushPromises()
      expect(fetchMock).toHaveBeenCalledWith('api/exceptions/second%2Fgroup', {})
      expect(fetchMock).not.toHaveBeenCalledWith('api/exceptions/abc123', {})
      expect(wrapper.get('.exception-drawer').text()).toContain('Second failure')
      expect(wrapper.get('.exception-drawer').text()).toContain('Caused by: java.lang.NumberFormatException')
      expect(document.activeElement).toBe(wrapper.get('.exception-drawer').element)
      expect(JSON.parse(wrapper.get('a').attributes('href'))).toEqual({
        path: '/activity',
        query: {request: 'request-7'}
      })
      expect(wrapper.text()).toContain('request-7')
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps legacy class-name filtering without selecting an arbitrary group', async () => {
    routeState.route.query = {q: 'IllegalStateException'}
    const fetchMock = mockFetch()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Exceptions)
    await flushPromises()
    expect(wrapper.get('input').element.value).toBe('IllegalStateException')
    expect(wrapper.text()).not.toContain('NullPointerException')
    expect(wrapper.find('.exception-drawer').exists()).toBe(false)
    expect(fetchMock.mock.calls).toHaveLength(2)
    wrapper.unmount()
  })

  it('shows a stale group explicitly without opening another same-class group', async () => {
    routeState.route.query = {id: 'evicted', request: 'request-7'}
    const fetchMock = mockFetch()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Exceptions)
    await flushPromises()
    expect(wrapper.get('.alert-warning[role="status"]').text()).toContain('no longer retained')
    expect(wrapper.text()).toContain('evicted')
    expect(wrapper.text()).toContain('Back to request profile')
    expect(wrapper.find('.exception-drawer').exists()).toBe(false)
    expect(fetchMock.mock.calls).toHaveLength(2)
    wrapper.unmount()
  })

  it.each([403, 404, 500])('reports a detail read rejected with HTTP %s inside the selected group', async (status) => {
    routeState.route.query = {id: 'abc123'}
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions/abc123': () => Promise.resolve(jsonResponse({}, false, status))
      })
    )
    const wrapper = mount(Exceptions)
    await flushPromises()
    const drawer = wrapper.get('.exception-drawer')
    expect(drawer.get('[role="status"]').text()).toContain(status === 404 ? 'no longer retained' : 'unavailable')
    expect(drawer.find('.stack-pane').exists()).toBe(false)
    expect(drawer.find('.exceptions-copy-ai').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not read disabled exception data, even with a group deep link', async () => {
    routeState.route.query = {id: 'abc123', request: 'request-7'}
    const fetchMock = mockFetch()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Exceptions, {props: {panel: {id: 'exceptions', enabled: false}}})
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('.alert-info[role="status"]').text()).toContain('bootui.panels.exceptions.enabled=false')
    expect(wrapper.text()).toContain('Back to request profile')
    wrapper.unmount()
  })

  it('waits for the shell manifest before loading a deep-linked group', async () => {
    routeState.route.query = {id: 'abc123'}
    const panels = ref(null)
    const fetchMock = mockFetch({'api/exceptions/abc123': detail()})
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Exceptions, {global: {provide: {panels}}})
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    await wrapper.setProps({panel: {id: 'exceptions', enabled: false}})
    panels.value = {panels: [{id: 'exceptions', enabled: false}]}
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('.alert-info[role="status"]').text()).toContain('Exception details unavailable')
    wrapper.unmount()
  })

  it('does not display a successful response for a different group', async () => {
    routeState.route.query = {id: 'abc123'}
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions/abc123': {...detail(), group: group({id: 'different', message: 'Unrelated failure'})}
      })
    )
    const wrapper = mount(Exceptions)
    await flushPromises()
    const drawer = wrapper.get('.exception-drawer')
    expect(drawer.get('[role="status"]').text()).toContain('unavailable')
    expect(drawer.text()).not.toContain('Unrelated failure')
    expect(drawer.find('.stack-pane').exists()).toBe(false)
    wrapper.unmount()
  })

  it('removes open detail when a refreshed report no longer retains its group', async () => {
    let reads = 0
    routeState.route.query = {id: 'abc123'}
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions': () => Promise.resolve(jsonResponse(reads++ ? report({groups: []}) : report())),
        'api/exceptions/abc123': detail()
      })
    )
    const wrapper = mount(Exceptions)
    await flushPromises()
    expect(wrapper.get('.exception-drawer').text()).toContain('Stack trace')
    wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(wrapper.find('.exception-drawer').exists()).toBe(false)
    expect(wrapper.get('.alert-warning[role="status"]').text()).toContain('no longer retained')
    wrapper.unmount()
  })

  it('ignores a late detail response when navigating to another group on the same route', async () => {
    let resolveFirst
    routeState.route.query = {id: 'abc123'}
    const second = group({id: 'def456', message: 'Second failure'})
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions': report({groups: [group(), second]}),
        'api/exceptions/abc123': () => new Promise((resolve) => (resolveFirst = resolve)),
        'api/exceptions/def456': {...detail(), group: second}
      })
    )
    const wrapper = mount(Exceptions)
    await flushPromises()
    routeState.route.query = {id: 'def456'}
    await flushPromises()
    expect(wrapper.get('.exception-drawer').text()).toContain('Second failure')
    resolveFirst(jsonResponse(detail()))
    await flushPromises()
    expect(wrapper.get('.exception-drawer').text()).toContain('Second failure')
    expect(wrapper.get('.exception-drawer').text()).not.toContain('token=****** rejected')
    wrapper.unmount()
  })

  it('keeps an acknowledged status after an older manual read finishes with auto-refresh off', async () => {
    let reads = 0
    let resolveStale
    const updated = group({status: 'RESOLVED'})
    const fetchMock = mockFetch({
      'api/exceptions': () => {
        reads++
        if (reads === 2) return new Promise((resolve) => (resolveStale = resolve))
        return Promise.resolve(jsonResponse(reads === 1 ? report() : report({groups: [updated]})))
      },
      'api/exceptions/abc123/status': updated
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(Exceptions)
    try {
      await flushPromises()
      const header = wrapper.findComponent({name: 'PanelHeader'})
      header.vm.$emit('update:autoRefresh', false)
      header.vm.$emit('refresh')
      await flushPromises()
      expect(reads).toBe(2)
      const row = wrapper.findAll('tbody tr')[0]
      await row
        .findAll('button')
        .find((button) => button.text() === 'Resolved')
        .trigger('click')
      await flushPromises()
      expect(row.findAll('td')[3].find('.badge').text()).toBe('Resolved')
      resolveStale(jsonResponse(report()))
      await flushPromises()
      expect(wrapper.findAll('tbody tr')[0].findAll('td')[3].find('.badge').text()).toBe('Resolved')
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    } finally {
      wrapper.unmount()
    }
  })

  it('renders grouped exceptions with masked messages, counts, and locations', async () => {
    const fetchMock = mockFetch()
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/exceptions', {})
    expect(fetchMock).toHaveBeenCalledWith('api/exceptions/caught', {})
    expect(wrapper.text()).toContain('Exceptions')
    expect(wrapper.text()).toContain('IllegalStateException')
    expect(wrapper.text()).toContain('token=****** rejected')
    expect(wrapper.text()).toContain('com.example.OrderService.place(OrderService.java:42)')
    expect(wrapper.text()).toContain('POST /api/orders')
    expect(wrapper.text()).toContain('2 groups · 4 occurrences')
  })

  it('filters groups by source', async () => {
    vi.stubGlobal('fetch', mockFetch())

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(wrapper.text()).toContain('IllegalStateException')
    expect(wrapper.text()).toContain('NullPointerException')

    await wrapper.find('select').setValue('log')

    expect(wrapper.text()).not.toContain('IllegalStateException')
    expect(wrapper.text()).toContain('NullPointerException')
  })

  it('filters groups by status', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions': report({
          groups: [
            group({id: 'a1', exceptionClassName: 'java.lang.IllegalStateException', status: 'OPEN'}),
            group({id: 'a2', exceptionClassName: 'java.lang.NullPointerException', status: 'RESOLVED'})
          ]
        })
      })
    )

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(wrapper.text()).toContain('IllegalStateException')
    expect(wrapper.text()).toContain('NullPointerException')

    const selects = wrapper.findAll('select')
    await selects[1].setValue('RESOLVED')

    expect(wrapper.text()).not.toContain('IllegalStateException')
    expect(wrapper.text()).toContain('NullPointerException')
  })

  it('renders a status badge and lets the user change status', async () => {
    const updated = group({status: 'RESOLVED'})
    const fetchMock = mockFetch({
      'api/exceptions/abc123/status': updated
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Exceptions)
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    expect(firstRow.text()).toContain('Open')

    const resolveButton = firstRow.findAll('button').find((b) => b.text() === 'Resolved')
    await resolveButton.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/exceptions/abc123/status',
      expect.objectContaining({method: 'POST', body: JSON.stringify({status: 'RESOLVED'})})
    )
    expect(firstRow.text()).toContain('Resolved')
  })

  it('shows a reopened badge when regressionCount is greater than zero', async () => {
    vi.stubGlobal('fetch', mockFetch({'api/exceptions': report({groups: [group({regressionCount: 2})]})}))

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(wrapper.text()).toContain('Reopened ×2')
  })

  it('loads exception detail with stack trace and cause chain on open', async () => {
    const fetchMock = mockFetch({'api/exceptions/abc123': detail()})
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Exceptions)
    await flushPromises()

    await wrapper.find('tbody button.btn-outline-primary').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith('api/exceptions/abc123', {})
    expect(wrapper.text()).toContain('at com.example.OrderService.place(OrderService.java:42)')
    expect(wrapper.text()).toContain('Caused by: java.lang.NumberFormatException')
    expect(wrapper.text()).toContain('... 12 more')
    expect(wrapper.text()).toContain('Recent occurrences')
  })

  it('previews a Copy for AI document with the correlated SQL and copies exactly the preview', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    const activity = {
      available: true,
      entries: [
        {id: 'exc-abc123', type: 'EXCEPTION', parentId: 'req-9'},
        {id: 'req-9', type: 'REQUEST', profileable: true}
      ]
    }
    const profile = {
      available: true,
      request: {id: 'req-9', method: 'POST', path: '/api/orders', status: 500, durationMs: 40},
      sqlGroups: [
        {
          sql: 'select * from orders where id = ?',
          executions: 4,
          totalDurationMillis: 8,
          potentialNPlusOne: true,
          callSites: ['com.example.OrderRepository.find(OrderRepository.java:7)']
        }
      ],
      sections: [],
      notes: []
    }
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/exceptions') return Promise.resolve(jsonResponse(report()))
      if (url === 'api/exceptions/caught') return Promise.resolve(jsonResponse(caughtReport()))
      if (url === 'api/exceptions/abc123') return Promise.resolve(jsonResponse(detail()))
      if (url === 'api/activity') return Promise.resolve(jsonResponse(activity))
      if (url === 'api/activity/request/req-9') return Promise.resolve(jsonResponse(profile))
      return Promise.reject(new Error(`unexpected ${url} ${init?.method}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(Exceptions)
    await flushPromises()
    await wrapper.find('tbody button.btn-outline-primary').trigger('click')
    await flushPromises()
    await wrapper.get('.exceptions-copy-ai').trigger('click')
    await flushPromises()

    const preview = wrapper.get('textarea.ai-export-markdown').element.value
    expect(preview).toContain('# BootUI exception: `java.lang.IllegalStateException`')
    expect(preview).toContain('```text\ntoken=****** rejected\n```')
    expect(preview).toContain('→ at com.example.OrderService.place(OrderService.java:42)')
    expect(preview).toContain('  Caused by: java.lang.NumberFormatException: For input string: "x"')
    expect(preview).toContain('## Correlated request: `POST /api/orders` → 500')
    expect(preview).toContain('- `com.example.OrderRepository.find(OrderRepository.java:7)`')
    expect(wrapper.get('.ai-export-omissions').text()).toContain('1 value was masked by BootUI')
    // Preparing the preview only reads: every call is a GET to an existing endpoint.
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)

    const reads = fetchMock.mock.calls.length
    await wrapper.get('.ai-export-copy').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(preview)
    expect(fetchMock).toHaveBeenCalledTimes(reads)
  })

  it('keeps the Copy for AI document selectable when clipboard access is denied', async () => {
    vi.stubGlobal('navigator', {clipboard: {writeText: vi.fn().mockRejectedValue(new Error('denied'))}})
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/exceptions') return Promise.resolve(jsonResponse(report()))
        if (url === 'api/exceptions/caught') return Promise.resolve(jsonResponse(caughtReport()))
        if (url === 'api/exceptions/abc123') return Promise.resolve(jsonResponse(detail()))
        return Promise.resolve(jsonResponse({}, false, 404))
      })
    )

    const wrapper = mount(Exceptions, {attachTo: document.body})
    await flushPromises()
    await wrapper.find('tbody button.btn-outline-primary').trigger('click')
    await flushPromises()
    await wrapper.get('.exceptions-copy-ai').trigger('click')
    await flushPromises()

    expect(wrapper.get('.ai-export-omissions').text()).toContain('Correlated SQL: Live Activity could not be read')
    await wrapper.get('.ai-export-copy').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('copy it manually')
    expect(document.activeElement).toBe(wrapper.get('textarea.ai-export-markdown').element)
    wrapper.unmount()
  })

  it('links a retained failure to its declared handler only when the engine attributed one', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions': report({
          groups: [
            group({
              errorContract: {
                entryId: 'com.example.GlobalAdvice#handleOrder(java.lang.IllegalStateException)',
                component: 'com.example.GlobalAdvice',
                componentSimpleName: 'GlobalAdvice',
                method: 'handleOrder',
                scope: 'GLOBAL',
                status: '409',
                bodyCategory: 'PROBLEM_DETAIL'
              }
            }),
            group({id: 'def456', errorContract: null})
          ]
        })
      })
    )

    const wrapper = mount(Exceptions)
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0].text()).toContain('Handled by')
    expect(rows[0].text()).toContain('GlobalAdvice#handleOrder')
    expect(rows[0].text()).toContain('409')
    // The attribution is a real cross-link into the REST API panel's catalogue, filtered to that handler.
    const link = rows[0].find('a')
    expect(JSON.parse(link.attributes('href'))).toEqual({
      path: '/rest-api',
      query: {errorContract: 'com.example.GlobalAdvice'}
    })
    // An unattributed failure must not imply a handler exists.
    expect(rows[1].text()).not.toContain('Handled by')
  })

  it('shows a disabled notice when capture is unavailable', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch({
        'api/exceptions': {
          available: false,
          unavailableReason: 'Exception capture is disabled',
          maxGroups: 100,
          totalExceptions: 0,
          groups: []
        },
        'api/exceptions/caught': caughtReport({
          available: false,
          unavailableReason: 'The BootUI agent is not attached'
        })
      })
    )

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(wrapper.text()).toContain('Exception capture is disabled')
  })
})
