import {config, flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Exceptions from './Exceptions.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))

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

// The handler attribution links into the REST API panel, so a router is not needed to render the panel.
config.global.stubs.RouterLink = {props: ['to'], template: '<a :href="JSON.stringify(to)"><slot /></a>'}

describe('Exceptions', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders grouped exceptions with masked messages, counts, and locations', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/exceptions', {})
    expect(wrapper.text()).toContain('Exceptions')
    expect(wrapper.text()).toContain('IllegalStateException')
    expect(wrapper.text()).toContain('token=****** rejected')
    expect(wrapper.text()).toContain('com.example.OrderService.place(OrderService.java:42)')
    expect(wrapper.text()).toContain('POST /api/orders')
    expect(wrapper.text()).toContain('2 groups · 4 occurrences')
  })

  it('filters groups by source', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

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
      vi.fn().mockResolvedValue(
        jsonResponse(
          report({
            groups: [
              group({id: 'a1', exceptionClassName: 'java.lang.IllegalStateException', status: 'OPEN'}),
              group({id: 'a2', exceptionClassName: 'java.lang.NullPointerException', status: 'RESOLVED'})
            ]
          })
        )
      )
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
    const fetchMock = vi.fn((url) => {
      if (url === 'api/exceptions/abc123/status') return Promise.resolve(jsonResponse(updated))
      return Promise.resolve(jsonResponse(report()))
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report({groups: [group({regressionCount: 2})]}))))

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(wrapper.text()).toContain('Reopened ×2')
  })

  it('loads exception detail with stack trace and cause chain on open', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(report()))
      .mockResolvedValueOnce(jsonResponse(detail()))
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
      vi.fn().mockResolvedValue(
        jsonResponse(
          report({
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
        )
      )
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
      vi.fn().mockResolvedValue(
        jsonResponse({
          available: false,
          unavailableReason: 'Exception capture is disabled',
          maxGroups: 100,
          totalExceptions: 0,
          groups: []
        })
      )
    )

    const wrapper = mount(Exceptions)
    await flushPromises()

    expect(wrapper.text()).toContain('Exception capture is disabled')
  })
})
