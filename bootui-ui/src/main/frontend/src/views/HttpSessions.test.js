import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import HttpSessions from './HttpSessions.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    totalSessions: 1,
    returnedSessions: 1,
    limit: 50,
    limited: false,
    actionEnabled: true,
    valueExposure: 'MASKED',
    sessions: [
      {
        sessionKey: 'session-key-one',
        id: 'session-...',
        idMasked: true,
        current: false,
        creationTime: '2026-06-03T09:00:00Z',
        lastAccessedTime: '2026-06-03T09:15:00Z',
        idleSeconds: 12,
        maxInactiveIntervalSeconds: 1800,
        attributeCount: 2,
        attributes: [
          {
            name: 'apiToken',
            type: 'java.lang.String',
            value: '******',
            masked: true,
            truncated: false
          },
          {
            name: 'sampleCount',
            type: 'java.lang.Integer',
            value: '******',
            masked: true,
            truncated: false
          }
        ]
      }
    ],
    ...overrides
  }
}

describe('HTTP Sessions', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('renders sessions with masked ids and expandable attributes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(HttpSessions, {props: {panel: {}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/http-sessions', {})
    expect(wrapper.text()).toContain('HTTP Sessions')
    expect(wrapper.text()).toContain('session-...')
    expect(wrapper.text()).toContain('masked')
    expect(wrapper.text()).not.toContain('apiToken')

    await wrapper.find('.http-sessions-detail-toggle').trigger('click')

    expect(wrapper.text()).toContain('apiToken')
    expect(wrapper.text()).toContain('sampleCount')
    expect(wrapper.text()).toContain('******')
  })

  it('renders full value exposure without masked badges', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          report({
            valueExposure: 'FULL',
            sessions: [
              {
                sessionKey: 'session-key-one',
                id: 'session-one-abcdef',
                idMasked: false,
                current: true,
                creationTime: '2026-06-03T09:00:00Z',
                lastAccessedTime: '2026-06-03T09:15:00Z',
                idleSeconds: 12,
                maxInactiveIntervalSeconds: 1800,
                attributeCount: 1,
                attributes: [
                  {
                    name: 'sampleMessage',
                    type: 'java.lang.String',
                    value: 'Hello session',
                    masked: false,
                    truncated: false
                  }
                ]
              }
            ]
          })
        )
      )
    )

    const wrapper = mount(HttpSessions, {props: {panel: {}}})
    await flushPromises()

    expect(wrapper.text()).toContain('Full value exposure is enabled')
    expect(wrapper.text()).toContain('session-one-abcdef')
    expect(wrapper.text()).not.toContain('masked')

    await wrapper.find('.http-sessions-detail-toggle').trigger('click')

    expect(wrapper.text()).toContain('sampleMessage')
    expect(wrapper.text()).toContain('Hello session')
    expect(wrapper.text()).not.toContain('******')
  })

  it('shows unavailable and limited states', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          jsonResponse(report({available: false, unavailableReason: 'HTTP Sessions require embedded Tomcat'}))
        )
        .mockResolvedValueOnce(jsonResponse(report({limited: true, totalSessions: 75, returnedSessions: 50})))
    )

    const unavailable = mount(HttpSessions, {props: {panel: {}}})
    await flushPromises()
    expect(unavailable.text()).toContain('HTTP Sessions require embedded Tomcat')
    unavailable.unmount()

    const limited = mount(HttpSessions, {props: {panel: {}}})
    await flushPromises()
    expect(limited.text()).toContain('bootui.http-sessions.max-sessions')
  })

  it('posts confirmed clear and destroy actions using the opaque session key', async () => {
    const fetchMock = vi.fn((url) => {
      if (String(url).includes('/clear')) {
        return Promise.resolve(jsonResponse({status: 'cleared', message: 'Cleared 2 HTTP session attributes.'}))
      }
      if (String(url).includes('/invalidate')) {
        return Promise.resolve(jsonResponse({status: 'destroyed', message: 'Destroyed HTTP session.'}))
      }
      return Promise.resolve(jsonResponse(report()))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(HttpSessions, {props: {panel: {}}})
    await flushPromises()

    await wrapper.find('button.btn-outline-warning').trigger('click')
    await flushPromises()
    await wrapper.find('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls).toEqual(
      expect.arrayContaining([
        [
          'api/http-sessions/session-key-one/clear',
          expect.objectContaining({method: 'POST', body: JSON.stringify({confirm: true})})
        ],
        [
          'api/http-sessions/session-key-one/invalidate',
          expect.objectContaining({method: 'POST', body: JSON.stringify({confirm: true})})
        ]
      ])
    )
  })

  it('disables actions when the panel is read-only', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))

    const wrapper = mount(HttpSessions, {
      props: {panel: {readOnly: true, readOnlyReason: 'BootUI is read-only'}}
    })
    await flushPromises()

    expect(wrapper.text()).toContain('HTTP session actions are read-only')
    expect(wrapper.find('button.btn-outline-warning').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button.btn-outline-danger').attributes('disabled')).toBeDefined()
  })
})
