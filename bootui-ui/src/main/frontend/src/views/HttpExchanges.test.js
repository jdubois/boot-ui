import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import HttpExchanges from './HttpExchanges.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    total: 1,
    recorded: 2,
    hiddenSelf: 1,
    unavailableReason: null,
    retention: {
      applicationManaged: false,
      capacity: 200,
      reservedCapacity: 50,
      retained: 2,
      reserved: 1,
      evicted: 0,
      slowThresholdMillis: 1000
    },
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
  })

  it('renders recorded exchanges with masked details and auto-refresh controls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(HttpExchanges)
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
    expect(wrapper.get('.capture-retention').text()).toContain('keeping 2 of 200 exchanges')
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
    const wrapper = mount(HttpExchanges)
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
    const wrapper = mount(HttpExchanges)
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
    // Only the initial list load happened: copying never calls the backend.
    expect(fetch).toHaveBeenCalledTimes(1)

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
})
