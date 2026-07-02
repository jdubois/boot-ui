import {flushPromises, mount} from '@vue/test-utils'
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
