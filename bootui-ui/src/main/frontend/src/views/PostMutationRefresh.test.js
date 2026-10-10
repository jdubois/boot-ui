import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import DevServices from './DevServices.vue'
import DevTools from './DevTools.vue'
import Email from './Email.vue'
import HttpSessions from './HttpSessions.vue'
import Jms from './Jms.vue'
import Kafka from './Kafka.vue'
import RabbitMQ from './RabbitMQ.vue'
import SpringCache from './SpringCache.vue'
import Traces from './Traces.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))
vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return {promise, resolve, reject}
}

function json(body) {
  return new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}})
}

function messages(name) {
  return {
    available: true,
    capturing: true,
    total: 1,
    totalCaptured: 1,
    maxEntries: 200,
    messages: [
      {
        id: 1,
        timestamp: 1,
        direction: 'PRODUCE',
        destination: name,
        topic: name,
        exchange: name,
        routingKey: name,
        from: name,
        to: [],
        cc: [],
        bcc: [],
        subject: name,
        attachments: [],
        success: true
      }
    ]
  }
}

const cases = [
  {
    name: 'Traces',
    component: Traces,
    endpoint: 'api/traces',
    button: 'Clear',
    report: (name) => ({
      enabled: true,
      retained: 1,
      capacity: 1000,
      traces: [{traceId: name, rootSpanName: name, httpPath: name, services: [], spanCount: 1, startEpochMillis: 1}]
    })
  },
  ...[
    [Email, 'Email', 'email'],
    [Jms, 'JMS', 'jms'],
    [Kafka, 'Kafka', 'kafka'],
    [RabbitMQ, 'RabbitMQ', 'rabbitmq']
  ].map(([component, name, path]) => ({name, component, endpoint: `api/${path}`, button: 'Clear', report: messages})),
  {
    name: 'HTTP Sessions',
    component: HttpSessions,
    endpoint: 'api/http-sessions',
    button: 'Clear',
    acknowledgement: {
      status: 'cleared',
      message: 'Attributes cleared.',
      sessionKey: 'session-key',
      affectedAttributes: 1
    },
    report: (name) => ({
      available: true,
      totalSessions: 1,
      returnedSessions: 1,
      limit: 50,
      actionEnabled: true,
      valueExposure: 'MASKED',
      sessions: [{sessionKey: 'session-key', id: name, attributeCount: 1, attributes: []}]
    })
  },
  {
    name: 'Cache',
    component: SpringCache,
    endpoint: 'api/cache',
    button: 'Clear all',
    acknowledgement: {status: 'cleared', message: 'Cache cleared.', clearedCaches: 1, caches: ['manager/cache']},
    report: (name) => ({
      cacheAvailable: true,
      clearEnabled: true,
      managerCount: 1,
      cacheCount: 1,
      operationCount: 0,
      tierCount: 0,
      operations: [],
      warnings: [],
      managers: [{name: 'manager', type: 'Caffeine', caches: [{managerName: 'manager', name, size: 1, tiers: []}]}]
    })
  },
  {
    name: 'Dev Services',
    component: DevServices,
    endpoint: 'api/dev-services',
    button: 'Restart',
    acknowledgement: {id: 'service', status: 'restarted', message: 'Service restarted.'},
    report: (name) => ({
      total: 1,
      warnings: [],
      snapshotTimestamp: 1,
      dockerComposePresent: true,
      testcontainersPresent: false,
      services: [
        {id: 'service', name, type: 'Database', status: 'RUNNING', ports: [], connectionDetails: {}, restartable: true}
      ]
    })
  },
  {
    name: 'DevTools',
    component: DevTools,
    endpoint: 'api/devtools',
    button: 'Trigger LiveReload',
    acknowledgement: {action: 'livereload', status: 'triggered', message: 'LiveReload triggered.'},
    report: (name) => ({
      restartAvailable: false,
      restartPending: false,
      restartUnavailableReason: name,
      liveReloadAvailable: true,
      liveReloadPort: 35729,
      liveReloadConnections: 1
    })
  }
]

describe('post-mutation polling refresh', () => {
  let wrapper
  beforeEach(() => {
    vi.useFakeTimers()
    document.cookie = 'XSRF-TOKEN=test-token'
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })
  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it.each(cases)(
    '$name re-reads after a stalled manual GET with auto-refresh off without accepting stale data',
    async (panel) => {
      const stale = deferred()
      const fresh = deferred()
      let reads = 0
      const fetchMock = vi.fn((url, init = {}) => {
        if (init.method === 'POST' || init.method === 'DELETE') {
          return Promise.resolve(
            panel.acknowledgement ? json(panel.acknowledgement) : new Response(null, {status: 204})
          )
        }
        expect(url).toBe(panel.endpoint)
        reads++
        if (reads === 1) return Promise.resolve(json(panel.report('initial-evidence')))
        return reads === 2 ? stale.promise : fresh.promise
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(panel.component)
      await flushPromises()
      const header = wrapper.getComponent(PanelHeader)
      header.vm.$emit('update:autoRefresh', false)
      await flushPromises()
      header.vm.$emit('refresh')
      await flushPromises()
      expect(reads).toBe(2)
      const action = wrapper.findAll('button').find((button) => button.text().trim() === panel.button)
      expect(action?.element.disabled).toBe(false)
      await action.trigger('click')
      await flushPromises()
      stale.resolve(json(panel.report('obsolete-evidence')))
      await flushPromises()
      expect(reads).toBe(3)
      expect(wrapper.text()).not.toContain('obsolete-evidence')
      fresh.resolve(json(panel.report('fresh-post-action-evidence')))
      await flushPromises()
      expect(wrapper.text()).toContain('fresh-post-action-evidence')
      expect(fetchMock.mock.calls.filter(([, init]) => ['POST', 'DELETE'].includes(init?.method))).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(10_000)
      expect(reads).toBe(3)
    }
  )

  const jsonActions = [
    ...cases.filter((panel) => ['Cache', 'Dev Services', 'DevTools'].includes(panel.name)),
    {
      ...cases.find((panel) => panel.name === 'DevTools'),
      name: 'DevTools restart',
      button: 'Restart app',
      report: (name) => ({
        restartAvailable: true,
        restartPending: false,
        restartUnavailableReason: name,
        liveReloadAvailable: true,
        liveReloadPort: 35729,
        liveReloadConnections: 1
      })
    }
  ]
  it.each(jsonActions.flatMap((panel) => ['empty', 'malformed', 'wrong shape'].map((kind) => ({...panel, kind}))))(
    '$name reports an unknown outcome for a $kind 200 acknowledgement without retrying',
    async (panel) => {
      let reads = 0
      const fetchMock = vi.fn((url, init = {}) => {
        if (init.method === 'POST') {
          return Promise.resolve(new Response(panel.kind === 'empty' ? '' : panel.kind === 'malformed' ? '{' : '{}'))
        }
        expect(url).toBe(panel.endpoint)
        reads++
        return Promise.resolve(json(panel.report('accepted-evidence')))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(panel.component)
      await flushPromises()
      wrapper.getComponent(PanelHeader).vm.$emit('update:autoRefresh', false)
      const action = wrapper.findAll('button').find((button) => button.text().trim() === panel.button)
      expect(action?.element.disabled).toBe(false)
      await action.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('action outcome is unknown')
      expect(wrapper.find('.alert-success').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('Restart scheduled.')
      expect(wrapper.text()).toContain('accepted-evidence')
      expect(reads).toBe(2)
      await vi.advanceTimersByTimeAsync(1500)
      expect(reads).toBe(2)
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    }
  )

  it.each(
    jsonActions.flatMap((panel) =>
      [
        {
          code: 403,
          body: {error: 'BootUI panel access denied', reason: 'This panel is read-only.'},
          expected: 'This panel is read-only.'
        },
        {
          code: 409,
          body: {status: 'unavailable', message: 'The action is unavailable.'},
          expected: 'The action is unavailable.'
        },
        {code: 500, body: 'not JSON', expected: 'HTTP 500'}
      ].map((fault) => ({...panel, ...fault}))
    )
  )('$name preserves a real HTTP $code refusal without claiming an unknown or successful outcome', async (panel) => {
    const fetchMock = vi.fn((url, init = {}) => {
      if (init.method === 'POST') {
        const text = typeof panel.body === 'string' ? panel.body : JSON.stringify(panel.body)
        return Promise.resolve(new Response(text, {status: panel.code, headers: {'Content-Type': 'application/json'}}))
      }
      return Promise.resolve(json(panel.report('accepted-evidence')))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(panel.component)
    await flushPromises()
    wrapper.getComponent(PanelHeader).vm.$emit('update:autoRefresh', false)
    const action = wrapper.findAll('button').find((button) => button.text().trim() === panel.button)
    await action.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(panel.expected)
    expect(wrapper.text()).not.toContain('action outcome is unknown')
    expect(wrapper.find('.alert-success').exists()).toBe(false)
    expect(wrapper.text()).toContain('accepted-evidence')
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('keeps a scheduled restart pending until a valid status report arrives', async () => {
    let reads = 0
    const status = {
      restartAvailable: true,
      restartPending: false,
      liveReloadAvailable: false,
      liveReloadConnections: 0
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url, init = {}) => {
        if (init.method === 'POST')
          return Promise.resolve(json({action: 'restart', status: 'scheduled', message: 'Restart scheduled.'}))
        reads++
        return Promise.resolve(json(reads === 2 ? {} : status))
      })
    )
    wrapper = mount(DevTools)
    await flushPromises()
    wrapper.getComponent(PanelHeader).vm.$emit('update:autoRefresh', false)
    await wrapper
      .findAll('button')
      .find((button) => button.text().trim() === 'Restart app')
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Restarting application')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.text()).not.toContain('Application is available again.')
    expect(wrapper.text()).toContain('Invalid Spring DevTools status response')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.text()).toContain('Application is available again.')
    expect(reads).toBe(3)
  })
})
