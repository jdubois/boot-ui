import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import HttpExchanges from './HttpExchanges.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'

const routeState = vi.hoisted(() => ({query: {}}))
vi.mock('vue-router', () => ({useRoute: () => routeState}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

function mountExchanges() {
  return mount(HttpExchanges, {global: {stubs: {RouterLink: RouterLinkStub}}})
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    total: 1,
    recorded: 2,
    hiddenSelf: 1,
    unavailableReason: null,
    page: {total: 1, matched: 1, offset: 0, limit: 200, returned: 1, hasMore: false},
    exchanges: [
      {
        id: 'exchange-1',
        timestamp: '2026-06-03T09:15:00Z',
        method: 'POST',
        path: '/api/orders',
        query: 'token=******&page=1',
        uri: 'http://localhost/api/orders?token=******&page=1',
        status: 201,
        statusFamily: '2xx',
        durationMs: 37,
        responseSizeBytes: 42,
        remoteAddress: '127.0.0.1',
        principal: null,
        sessionId: null,
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
        requestHeaders: [
          {name: 'Accept', values: ['application/json'], masked: false},
          {name: 'Authorization', values: ['******'], masked: true}
        ],
        responseHeaders: [{name: 'Content-Length', values: ['42'], masked: false}]
      }
    ],
    ...overrides
  }
}

describe('HTTP Exchanges', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    routeState.query = {}
  })

  it('renders recorded exchanges with masked details and auto-refresh controls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mountExchanges()
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith(
      'api/http-exchanges?offset=0&limit=200',
      expect.objectContaining({signal: expect.any(AbortSignal)})
    )
    expect(wrapper.text()).toContain('HTTP Exchanges')
    expect(wrapper.text()).toContain('/api/orders?token=******&page=1')
    expect(wrapper.text()).toContain('201')
    expect(wrapper.text()).toContain('37 ms')
    expect(wrapper.text()).toContain('42 B')
    expect(wrapper.text()).toContain('4bf92f3577b34da6a3ce929d0e0e4736')
    expect(wrapper.text()).not.toContain('Authorization')
    expect(wrapper.text()).not.toContain('BootUI self-request')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(true)

    const detailsButton = wrapper.find('.http-exchanges-detail-toggle')
    expect(detailsButton.text()).toContain('View details')
    expect(detailsButton.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.http-exchanges-detail').exists()).toBe(false)

    await detailsButton.trigger('click')

    expect(wrapper.find('.http-exchanges-detail-toggle').text()).toContain('Hide details')
    expect(wrapper.find('.http-exchanges-detail-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.http-exchanges-detail').exists()).toBe(true)
    expect(wrapper.text()).toContain('Authorization')
    expect(wrapper.text()).toContain('******')
  })

  it('sends method and status filters to the server', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(report({exchanges: [], total: 0, recorded: 0, hiddenSelf: 0})))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountExchanges()
    await flushPromises()

    await wrapper.find('select').setValue('POST')
    await wrapper.findAll('select')[1].setValue('4xx')
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/http-exchanges?method=POST&statusClass=4xx&offset=0&limit=200',
      expect.objectContaining({signal: expect.any(AbortSignal)})
    )
  })

  async function openDetails(overrides) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report(overrides))))
    const wrapper = mountExchanges()
    await flushPromises()
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')
    return wrapper
  }

  it('copies a safe cURL template without secrets, values, or a request', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.assign(navigator, {clipboard: {writeText}})

    const wrapper = await openDetails()
    const copyButton = wrapper.find('.http-exchanges-curl-copy')
    expect(copyButton.attributes('aria-disabled')).toBeUndefined()
    expect(copyButton.text()).toContain('Copy as cURL')

    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    const command = writeText.mock.calls[0][0]
    expect(command).toBe(
      [
        "curl --globoff -X 'POST' 'http://localhost/api/orders?token=VALUE&page=VALUE' \\",
        "  -H 'Accept: application/json'"
      ].join('\n')
    )
    expect(command).not.toContain('Authorization')
    expect(command).not.toContain('******')
    // Only the initial list and route loads happened: copying never calls the backend.
    expect(fetch).toHaveBeenCalledTimes(2)

    expect(wrapper.find('.http-exchanges-curl-copy').text()).toContain('Copied')
    expect(wrapper.find('.http-exchanges-curl-command').text()).toBe(command)
    const status = wrapper.find('.http-exchanges-copy-status')
    expect(status.text()).toContain('cURL template copied')
    // The announcement never repeats a recorded query value.
    expect(status.text()).not.toContain('token')
    expect(status.attributes('role')).toBe('status')
  })

  it('explains the omitted body, query values, and headers', async () => {
    const wrapper = await openDetails()
    const notes = wrapper.find('.http-exchanges-curl-notes').text()

    expect(notes).toContain('BootUI never captures request bodies')
    expect(notes).toContain('2 query parameter names are kept')
    expect(notes).toContain('1 request header was omitted')
    expect(notes).toContain('add your own --data')
    expect(wrapper.find('.http-exchanges-curl-unavailable').exists()).toBe(false)
  })

  it('surfaces a clipboard denial instead of pretending the copy worked', async () => {
    Object.assign(navigator, {clipboard: {writeText: vi.fn().mockRejectedValue(new Error('denied'))}})

    const wrapper = await openDetails()
    await wrapper.find('.http-exchanges-curl-copy').trigger('click')
    await flushPromises()

    const alert = wrapper.find('.http-exchanges-curl [role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('blocked clipboard access')
    // The fallback tells the user to copy the command manually, so it must be on screen.
    expect(wrapper.find('.http-exchanges-curl-command').text()).toContain('curl --globoff')
    expect(wrapper.find('.http-exchanges-curl-copy').text()).toContain('Copy as cURL')
    expect(wrapper.find('.http-exchanges-copy-status').text()).toBe('')

    // Collapsing the row clears the stale failure so reopening it does not show an old alert.
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')
    await wrapper.find('.http-exchanges-detail-toggle').trigger('click')
    expect(wrapper.find('.http-exchanges-curl [role="alert"]').exists()).toBe(false)
  })

  it('deactivates the action with a clear, announced reason when the request URL was not recorded', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.assign(navigator, {clipboard: {writeText}})

    const wrapper = await openDetails({
      exchanges: [{...report().exchanges[0], uri: null, query: null}]
    })

    const copyButton = wrapper.find('.http-exchanges-curl-copy')
    // aria-disabled keeps the control focusable so assistive technology can reach its reason.
    expect(copyButton.attributes('aria-disabled')).toBe('true')
    expect(copyButton.attributes('disabled')).toBeUndefined()
    const reason = wrapper.find('.http-exchanges-curl-unavailable')
    expect(copyButton.attributes('aria-describedby')).toBe(reason.attributes('id'))
    expect(reason.text()).toContain('no recorded absolute http(s) request URL')
    expect(wrapper.find('.http-exchanges-curl-notes').exists()).toBe(false)
    expect(wrapper.find('.http-exchanges-curl-command').exists()).toBe(false)

    // Clicking it copies nothing and announces why rather than failing silently.
    await copyButton.trigger('click')
    await flushPromises()
    expect(writeText).not.toHaveBeenCalled()
    expect(wrapper.find('.http-exchanges-curl [role="alert"]').exists()).toBe(false)
    expect(wrapper.find('.http-exchanges-copy-status').text()).toContain('no recorded absolute http(s) request URL')
  })

  describe('route rankings', () => {
    function route(overrides = {}) {
      return {
        id: 'GET /api/orders/{id}',
        method: 'GET',
        route: '/api/orders/{id}',
        routeSource: 'FRAMEWORK_TEMPLATE',
        requests: 12,
        status2xx: 10,
        status3xx: 0,
        status4xx: 1,
        status5xx: 1,
        statusOther: 0,
        errorCount: 2,
        timedRequests: 11,
        totalDurationMs: 440,
        avgDurationMs: 40,
        p50DurationMs: 30,
        p95DurationMs: 120,
        p99DurationMs: 150,
        maxDurationMs: 150,
        shareOfRetainedTimePercent: 88,
        topFor: ['REQUESTS', 'TOTAL_DURATION', 'ERROR_COUNT'],
        ...overrides
      }
    }

    function routesReport(overrides = {}) {
      return {
        available: true,
        unavailableReason: null,
        window: {
          retainedExchanges: 16,
          bufferSize: 200,
          evicted: null,
          hiddenSelfExchanges: 2,
          summarizedExchanges: 14,
          timedExchanges: 13,
          oldestTimestamp: 1780000000000,
          newestTimestamp: 1780000060000,
          totalDurationMs: 500
        },
        routes: [
          route(),
          route({
            id: 'GET /api/items/{value}',
            route: '/api/items/{value}',
            routeSource: 'MASKED_PATH',
            requests: 2,
            status2xx: 2,
            status4xx: 0,
            status5xx: 0,
            errorCount: 0,
            timedRequests: 2,
            totalDurationMs: 60,
            p95DurationMs: 900,
            maxDurationMs: 900,
            shareOfRetainedTimePercent: 12,
            topFor: ['REQUESTS', 'P95_DURATION', 'MAX_DURATION']
          })
        ],
        topPerCriterion: 25,
        routesTruncated: false,
        distinctRoutes: 2,
        notes: ['Every figure covers only the 14 retained, visible exchanges.'],
        ...overrides
      }
    }

    function stubFetch(routes = routesReport(), exchanges = report()) {
      const fetchMock = vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).startsWith('api/http-exchanges/routes') ? routes : exchanges))
      )
      vi.stubGlobal('fetch', fetchMock)
      return fetchMock
    }

    it('ranks routes over the stated window and labels how each route was resolved', async () => {
      stubFetch()
      const wrapper = mountExchanges()
      await flushPromises()

      expect(fetch).toHaveBeenCalledWith('api/http-exchanges/routes', expect.anything())
      const windowText = wrapper.find('.http-routes-window').text()
      expect(windowText).toContain('14 retained exchanges')
      expect(windowText).toContain('buffer 200')
      expect(windowText).toContain('evictions not reported')
      expect(windowText).toContain('2 BootUI exchanges hidden')

      const rows = wrapper.findAll('.http-routes-table tbody tr')
      expect(rows.map((row) => row.attributes('data-route-id'))).toEqual([
        'GET /api/orders/{id}',
        'GET /api/items/{value}'
      ])
      expect(rows[0].text()).toContain('template')
      expect(rows[0].text()).toContain('11 timed')
      expect(rows[0].text()).toContain('40.0 ms')
      expect(rows[0].text()).toContain('88.0%')
      expect(rows[1].text()).toContain('masked path')
      expect(wrapper.find('.http-routes-notes').text()).toContain('14 retained, visible exchanges')

      await wrapper.find('#http-routes-metric').setValue('p95DurationMs')
      expect(wrapper.findAll('.http-routes-table tbody tr').map((row) => row.attributes('data-route-id'))).toEqual([
        'GET /api/items/{value}',
        'GET /api/orders/{id}'
      ])

      await wrapper.find('#http-routes-metric').setValue('errorCount')
      expect(wrapper.findAll('.http-routes-table tbody tr').map((row) => row.attributes('data-route-id'))).toEqual([
        'GET /api/orders/{id}'
      ])
    })

    it('states how many routes a bounded ranking leaves out', async () => {
      stubFetch(routesReport({topPerCriterion: 1, routesTruncated: true, distinctRoutes: 40}))
      const wrapper = mountExchanges()
      await flushPromises()

      expect(wrapper.findAll('.http-routes-table tbody tr')).toHaveLength(1)
      expect(wrapper.find('.http-routes-truncation').text()).toContain('Showing 1 of 40 routes')
      expect(wrapper.find('.http-routes-truncation').text()).toContain('39 more routes')
    })

    it('drills down from a route to exactly its exchanges and back', async () => {
      vi.useFakeTimers()
      const fetchMock = stubFetch()
      const wrapper = mountExchanges()
      await flushPromises()

      const button = wrapper.find('.http-routes-exchanges-link')
      expect(button.attributes('aria-pressed')).toBe('false')
      await button.trigger('click')
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()

      expect(fetchMock).toHaveBeenLastCalledWith(
        'api/http-exchanges?route=GET+%2Fapi%2Forders%2F%7Bid%7D&offset=0&limit=200',
        expect.objectContaining({signal: expect.any(AbortSignal)})
      )
      expect(wrapper.find('.http-exchanges-route-filter').text()).toContain('GET /api/orders/{id}')
      expect(wrapper.find('.http-routes-exchanges-link').attributes('aria-pressed')).toBe('true')
      expect(wrapper.find('.http-routes-row-active').attributes('data-route-id')).toBe('GET /api/orders/{id}')

      await wrapper.find('.http-exchanges-route-filter button').trigger('click')
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()

      expect(fetchMock).toHaveBeenLastCalledWith(
        'api/http-exchanges?offset=0&limit=200',
        expect.objectContaining({signal: expect.any(AbortSignal)})
      )
      expect(wrapper.find('.http-exchanges-route-filter').exists()).toBe(false)
    })

    it('opens on the route and ranking a Live Activity link names', async () => {
      routeState.query = {route: 'GET /api/items/{value}', rank: 'maxDurationMs'}
      const fetchMock = stubFetch()
      const wrapper = mountExchanges()
      await flushPromises()

      expect(wrapper.find('#http-routes-metric').element.value).toBe('maxDurationMs')
      expect(wrapper.find('.http-routes-row-active').attributes('data-route-id')).toBe('GET /api/items/{value}')
      expect(wrapper.find('.http-routes-row-active').attributes('aria-current')).toBe('true')
      expect(fetchMock).toHaveBeenCalledWith(
        'api/http-exchanges?route=GET+%2Fapi%2Fitems%2F%7Bvalue%7D&offset=0&limit=200',
        expect.anything()
      )
    })

    it('links every exchange to its request profile and shows its route', async () => {
      stubFetch(
        routesReport(),
        report({
          exchanges: [
            {
              ...report().exchanges[0],
              path: '/api/orders/42',
              route: '/api/orders/{id}',
              routeSource: 'FRAMEWORK_TEMPLATE'
            }
          ]
        })
      )
      const wrapper = mountExchanges()
      await flushPromises()

      const link = wrapper.find('.http-exchanges-profile-link')
      expect(JSON.parse(link.attributes('data-to'))).toEqual({path: '/activity', query: {request: 'exchange-1'}})
      expect(link.attributes('aria-label')).toBe('Open the request profile of POST /api/orders/42')
      expect(wrapper.find('.http-exchanges-route').text()).toContain('/api/orders/{id}')
    })

    it('keeps the exchange list usable when route rankings cannot load', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn((url) =>
          Promise.resolve(
            String(url).startsWith('api/http-exchanges/routes') ? jsonResponse({}, false, 500) : jsonResponse(report())
          )
        )
      )
      const wrapper = mountExchanges()
      await flushPromises()

      expect(wrapper.text()).toContain('Could not load route rankings')
      expect(wrapper.find('.http-routes').exists()).toBe(false)
      expect(wrapper.find('.http-exchanges-table').text()).toContain('/api/orders')
    })

    it('says so when no application request is retained yet', async () => {
      stubFetch(routesReport({routes: [], distinctRoutes: 0}))
      const wrapper = mountExchanges()
      await flushPromises()

      expect(wrapper.find('.http-routes').text()).toContain('no route to rank')
      expect(wrapper.find('.http-routes-table').exists()).toBe(false)
    })
  })
})
