import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import Config from './Config.vue'
import HibernateStatistics from './HibernateStatistics.vue'
import Loggers from './Loggers.vue'
import RestClientTrace from './RestClientTrace.vue'
import SqlTrace from './SqlTrace.vue'
import Transactions from './Transactions.vue'
import WebSockets from './WebSockets.vue'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))
vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}})
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return {promise, resolve, reject}
}

function captureReport(overrides = {}) {
  const {stats = {}, ...fields} = overrides
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    captureParameters: false,
    captureHeaders: false,
    bufferSize: 200,
    totalCaptured: 0,
    slowQueryThresholdMillis: 0,
    slowTransactionThresholdMillis: 0,
    connectionHoldThresholdMillis: 0,
    slowCallThresholdMillis: 0,
    stats: {
      totalQueries: 0,
      totalTransactions: 0,
      totalCalls: 0,
      totalDurationMillis: 0,
      maxDurationMillis: 0,
      avgDurationMillis: 0,
      slowQueries: 0,
      failedQueries: 0,
      batchExecutions: 0,
      selectCount: 0,
      insertCount: 0,
      updateCount: 0,
      deleteCount: 0,
      otherCount: 0,
      evicted: 0,
      slowTransactions: 0,
      connectionHeldTransactions: 0,
      committedCount: 0,
      rolledBackCount: 0,
      unknownCount: 0,
      nestedCount: 0,
      slowCalls: 0,
      failedCalls: 0,
      errorStatusCalls: 0,
      getCount: 0,
      postCount: 0,
      putCount: 0,
      endpoints: 0,
      openSessions: 0,
      closedSessions: 0,
      subscriptions: 0,
      inboundFrames: 0,
      outboundFrames: 0,
      inboundBytes: 0,
      outboundBytes: 0,
      failedFrames: 0,
      capturedActivity: 0,
      evictedActivity: 0,
      ...stats
    },
    entries: [],
    warnings: [],
    dataSources: [],
    clientTypes: [],
    topStatements: [],
    topCalls: [],
    frameCaptureSupported: true,
    frameCaptureUnavailableReason: null,
    sessionTrackingSupported: true,
    sessionTrackingUnavailableReason: null,
    framework: 'spring-websocket',
    userDestinationPrefix: null,
    maxEndpoints: 200,
    maxSessions: 200,
    maxSubscriptions: 200,
    maxActivityEntries: 200,
    endpointsTruncated: false,
    sessionsTruncated: false,
    subscriptionsTruncated: false,
    endpoints: [],
    sessions: [],
    subscriptions: [],
    activity: [],
    brokerPrefixes: [],
    applicationDestinationPrefixes: [],
    retention: null,
    ...fields
  }
}

const capturePanels = [
  {name: 'SQL Trace', component: SqlTrace, endpoint: 'api/sql-trace', action: 'recording'},
  {name: 'Transactions', component: Transactions, endpoint: 'api/transactions', action: 'recording'},
  {name: 'REST Client', component: RestClientTrace, endpoint: 'api/rest-client-trace', action: 'recording'},
  {name: 'WebSockets', component: WebSockets, endpoint: 'api/websockets', action: 'capture'}
]

const disabledStatistics = {
  available: false,
  enableAvailable: true,
  unavailableReason: 'Hibernate statistics are disabled.',
  statistics: null
}
const enabledStatistics = {
  available: true,
  enableAvailable: false,
  unavailableReason: null,
  statistics: {secondLevelCacheRegions: []}
}
const logger = {name: 'io.github.jdubois.bootui.VisibleLogger', configuredLevel: 'INFO', effectiveLevel: 'INFO'}
const loggerReport = {
  availableLevels: ['INFO', 'DEBUG'],
  loggers: [logger],
  page: {matched: 1, total: 1}
}
const property = {
  name: 'sample.greeting',
  value: 'override',
  source: 'bootuiOverrides',
  override: true,
  masked: false
}
const configReport = {
  properties: [property],
  overrideCount: 1,
  page: {matched: 1, total: 1},
  sources: [],
  activeProfiles: [],
  propertySuggestions: []
}

describe('diagnostic control response boundaries', () => {
  let wrapper

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    vi.stubGlobal(
      'EventSource',
      class {
        addEventListener() {}
        close() {}
      }
    )
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
    vi.unstubAllGlobals()
  })

  function button(label) {
    return wrapper.findAll('button').find((candidate) => candidate.text().trim() === label)
  }

  function message() {
    return wrapper.findComponent(FlashBanner).props('message')
  }

  async function mountPanel(panel, actionResponse, readResponse = () => json(captureReport())) {
    let reads = 0
    const fetchMock = vi.fn((url, init = {}) => {
      if (url.endsWith('/insights')) return Promise.resolve(json({available: false}))
      if (init.method === 'POST' || init.method === 'DELETE') return actionResponse()
      reads++
      return Promise.resolve(readResponse(reads))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(panel.component)
    await flushPromises()
    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(false)
    return {fetchMock, reads: () => reads}
  }

  describe.each(capturePanels)('$name native report actions', (panel) => {
    it.each([
      ['empty object', () => json({})],
      ['null', () => json(null)],
      ['wrong capturing type', () => json(captureReport({capturing: 'false'}))],
      ['malformed JSON', () => new Response('<html>not JSON</html>')],
      ['bodyless 200', () => new Response(null)],
      ['bodyless 204', () => new Response(null, {status: 204})]
    ])(
      'preserves accepted state after a %s and performs one failed reconciliation, not a retry',
      async (_, response) => {
        const {fetchMock, reads} = await mountPanel(
          panel,
          () => Promise.resolve(response()),
          (count) => (count === 1 ? json(captureReport()) : Promise.reject(new TypeError('Failed to fetch')))
        )
        await button('Pause').trigger('click')
        await flushPromises()

        expect(message().text).toContain('outcome is unknown')
        expect(message().type).not.toBe('success')
        expect(button('Pause')).toBeDefined()
        expect(reads()).toBe(2)
        expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
        expect(wrapper.findComponent(PanelHeader).props('error')).not.toBeNull()
      }
    )

    it.each([
      [400, {error: 'Invalid capture request'}, 'Invalid capture request'],
      [
        403,
        {error: 'BootUI panel access denied', reason: 'Capture is read-only by policy.'},
        'Capture is read-only by policy.'
      ],
      [500, {message: 'Recorder disappeared'}, 'Recorder disappeared']
    ])('keeps the specific HTTP %s refusal and accepted state', async (status, body, detail) => {
      const {reads} = await mountPanel(panel, () => Promise.resolve(json(body, status)))
      await button('Pause').trigger('click')
      await flushPromises()
      expect(message().text).toContain(detail)
      expect(message().type).not.toBe('success')
      expect(button('Pause')).toBeDefined()
      expect(reads()).toBe(1)
    })

    it('reconciles an uncertain network outcome without retrying the mutation', async () => {
      const {fetchMock, reads} = await mountPanel(
        panel,
        () => Promise.reject(new TypeError('Failed to fetch')),
        (count) => json(captureReport({capturing: count === 1}))
      )
      await button('Pause').trigger('click')
      await flushPromises()
      expect(message().text).toContain('outcome is unknown')
      expect(button('Resume')).toBeDefined()
      expect(reads()).toBe(2)
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
    })

    it('accepts a legitimate unavailable report without inventing successful recording', async () => {
      await mountPanel(panel, () =>
        Promise.resolve(
          json(
            captureReport({
              available: false,
              unavailableReason: 'Recorder is unavailable.',
              capturing: false,
              bufferSize: 0,
              future: true
            })
          )
        )
      )
      await button('Pause').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('Recorder is unavailable.')
      expect(message().type).not.toBe('success')
      expect(message().text).not.toContain('outcome is unknown')
    })

    it('reports an unchanged recording state rather than the requested intent', async () => {
      await mountPanel(panel, () => Promise.resolve(json(captureReport({future: {supported: true}}))))
      await button('Pause').trigger('click')
      await flushPromises()
      expect(button('Pause')).toBeDefined()
      expect(message().type).not.toBe('success')
      expect(message().text).toContain('unchanged')
      expect(message().text).not.toContain('paused;')
    })

    it('rejects a late pre-action GET and bounds the fresh read with auto-refresh off', async () => {
      const stale = deferred()
      const fresh = deferred()
      const {fetchMock, reads} = await mountPanel(
        panel,
        () => Promise.resolve(json(captureReport({capturing: false, future: true}))),
        (count) => (count === 1 ? json(captureReport()) : count === 2 ? stale.promise : fresh.promise)
      )
      wrapper.findComponent(PanelHeader).vm.$emit('refresh')
      await flushPromises()
      expect(reads()).toBe(2)
      await button('Pause').trigger('click')
      await flushPromises()
      expect(button('Resume')).toBeDefined()
      expect(reads()).toBe(2)
      stale.resolve(json(captureReport({capturing: true})))
      await flushPromises()
      expect(reads()).toBe(3)
      expect(button('Resume')).toBeDefined()
      fresh.reject(new TypeError('Failed to fetch'))
      await flushPromises()
      expect(button('Resume')).toBeDefined()
      expect(message().type).toBe('success')
      expect(wrapper.findComponent(PanelHeader).props('autoRefresh')).toBe(false)
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
    })

    it('does not acknowledge a malformed clear report or discard the accepted buffer', async () => {
      const retained = captureReport({
        stats: {totalQueries: 1, totalTransactions: 1, totalCalls: 1, closedSessions: 1},
        entries: [
          {
            id: 1,
            sql: 'select retained_entry',
            category: 'SELECT',
            methodName: 'retained_entry',
            method: 'GET',
            host: 'localhost',
            path: '/retained_entry',
            status: 'COMMITTED',
            success: true
          }
        ]
      })
      const {fetchMock, reads} = await mountPanel(
        panel,
        () => Promise.resolve(json({})),
        () => json(retained)
      )
      await button('Clear').trigger('click')
      await flushPromises()
      expect(message().text).toContain('outcome is unknown')
      expect(message().type).not.toBe('success')
      expect(reads()).toBe(2)
      expect(
        fetchMock.mock.calls.filter(([, init]) => init.method === (panel.action === 'capture' ? 'DELETE' : 'POST'))
      ).toHaveLength(1)
      if (panel.action !== 'capture') expect(wrapper.text()).toContain('retained_entry')
    })
  })

  it('accepts the WebSocket metadata-only/no-recorder outcome without claiming capture resumed', async () => {
    await mountPanel(capturePanels[3], () =>
      Promise.resolve(
        json(
          captureReport({
            frameCaptureSupported: false,
            capturing: true,
            frameCaptureUnavailableReason: 'No frame interception seam.'
          })
        )
      )
    )
    await button('Pause').trigger('click')
    await flushPromises()
    expect(message().type).not.toBe('success')
    expect(message().text).toContain('No frame interception seam.')
    expect(button('Resume')).toBeUndefined()
  })

  describe('logger levels', () => {
    async function mountLoggers(response, reread = loggerReport) {
      let reads = 0
      const fetchMock = vi.fn((url, init = {}) => {
        if (init.method === 'POST') return response()
        return Promise.resolve(json(++reads === 1 ? loggerReport : reread))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Loggers)
      await flushPromises()
      return {fetchMock, reads: () => reads}
    }

    it.each([
      [
        400,
        {error: `Refusing to change the level of BootUI's own logger '${logger.name}'.`},
        "Refusing to change the level of BootUI's own logger"
      ],
      [
        403,
        {error: 'BootUI panel access denied', reason: 'Logger levels are read-only by policy.'},
        'Logger levels are read-only by policy.'
      ],
      [500, {message: 'No logger backend is available'}, 'No logger backend is available']
    ])('shows the reachable HTTP %s refusal without changing the logger', async (status, body, detail) => {
      const {fetchMock} = await mountLoggers(() => Promise.resolve(json(body, status)))
      await button('DEBUG').trigger('click')
      await flushPromises()
      expect(message().text).toContain(detail)
      expect(message().type).not.toBe('success')
      expect(button('INFO').classes()).toContain('active')
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
    })

    it.each([
      ['network rejection', () => Promise.reject(new TypeError('Failed to fetch'))],
      ['invalid 200', () => Promise.resolve(json({}))],
      ['wrong logger identity', () => Promise.resolve(json({...logger, name: 'other'}))]
    ])('reconciles a %s without inventing success or repeating the write', async (_, response) => {
      const {fetchMock, reads} = await mountLoggers(response)
      await button('DEBUG').trigger('click')
      await flushPromises()
      expect(message().text).toContain('outcome is unknown')
      expect(message().type).not.toBe('success')
      expect(button('INFO').classes()).toContain('active')
      expect(reads()).toBe(2)
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
    })

    it('accepts nullable configured level on reset and future fields', async () => {
      const reset = {...logger, configuredLevel: null, future: 'accepted'}
      await mountLoggers(() => Promise.resolve(json(reset)), {...loggerReport, loggers: [reset]})
      await wrapper.get('button[title="Reset"]').trigger('click')
      await flushPromises()
      expect(button('INFO').classes()).not.toContain('active')
      expect(message().type).toBe('success')
    })

    it('admits only one pending level change', async () => {
      const action = deferred()
      const updated = {...logger, configuredLevel: 'DEBUG', effectiveLevel: 'DEBUG'}
      const {fetchMock} = await mountLoggers(() => action.promise, {...loggerReport, loggers: [updated]})
      await button('DEBUG').trigger('click')
      expect(button('INFO').attributes('disabled')).toBeDefined()
      await button('DEBUG').trigger('click')
      await flushPromises()
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
      action.resolve(json(updated))
      await flushPromises()
      expect(button('DEBUG').classes()).toContain('active')
    })

    it('supersedes an old paged GET and preserves the accepted level when reconciliation fails', async () => {
      const stale = deferred()
      const updated = {...logger, configuredLevel: 'DEBUG', effectiveLevel: 'DEBUG'}
      let reads = 0
      let staleSignal
      const fetchMock = vi.fn((url, init = {}) => {
        if (init.method === 'POST') return Promise.resolve(json(updated))
        reads++
        if (reads === 1) return Promise.resolve(json(loggerReport))
        if (reads === 2) {
          staleSignal = init.signal
          return stale.promise
        }
        return Promise.reject(new TypeError('Failed to fetch'))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Loggers)
      await flushPromises()
      wrapper.findComponent(PanelHeader).vm.$emit('refresh')
      await flushPromises()
      await button('DEBUG').trigger('click')
      await flushPromises()
      expect(staleSignal.aborted).toBe(true)
      expect(reads).toBe(3)
      expect(button('DEBUG').classes()).toContain('active')
      stale.resolve(json(loggerReport))
      await flushPromises()
      expect(button('DEBUG').classes()).toContain('active')
      expect(button('INFO').classes()).not.toContain('active')
      expect(message().type).toBe('success')
      expect(wrapper.findComponent(PanelHeader).props('error')).not.toBeNull()
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
    })
  })

  describe('configuration override removal', () => {
    async function mountConfig(response) {
      let reads = 0
      const fetchMock = vi.fn((url, init = {}) => {
        if (init.method === 'DELETE') return response()
        reads++
        return Promise.resolve(json(configReport))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Config)
      await flushPromises()
      return {fetchMock, reads: () => reads}
    }

    it('preserves the specific canonical policy refusal and the override', async () => {
      await mountConfig(() =>
        Promise.resolve(
          json({error: 'BootUI panel access denied', reason: 'Configuration is read-only by policy.'}, 403)
        )
      )
      await wrapper.get('button[title="Remove override"]').trigger('click')
      await flushPromises()
      expect(message().text).toContain('Configuration is read-only by policy.')
      expect(wrapper.text()).toContain('override')
    })

    it.each([
      ['network rejection', () => Promise.reject(new TypeError('Failed to fetch'))],
      ['empty reply', () => Promise.resolve(json({}))],
      [
        'wrong identity',
        () =>
          Promise.resolve(json({name: 'other', value: null, previousValue: null, persisted: true, message: 'Stored.'}))
      ]
    ])('catches a %s and reconciles once without another DELETE', async (_, response) => {
      const {fetchMock, reads} = await mountConfig(response)
      await wrapper.get('button[title="Remove override"]').trigger('click')
      await flushPromises()
      expect(message().text).toContain('outcome is unknown')
      expect(message().type).not.toBe('success')
      expect(reads()).toBe(2)
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'DELETE')).toHaveLength(1)
      expect(wrapper.get('button[title="Remove override"]').attributes('disabled')).toBeUndefined()
    })

    it.each([null, 'override'])(
      'accepts the real persisted removal with previousValue %s, including no prior override',
      async (previousValue) => {
        await mountConfig(() =>
          Promise.resolve(
            json({
              name: property.name,
              value: null,
              previousValue,
              persisted: true,
              message: 'Restart may be required.',
              future: true
            })
          )
        )
        await wrapper.get('button[title="Remove override"]').trigger('click')
        await flushPromises()
        expect(message().type).toBe('success')
        expect(message().text).toContain('Override removed')
      }
    )
  })

  describe('Hibernate runtime activation', () => {
    async function mountStatistics(response, read = () => json(disabledStatistics)) {
      let reads = 0
      const fetchMock = vi.fn((url, init = {}) => {
        if (init.method === 'POST') return response()
        return Promise.resolve(read(++reads))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(HibernateStatistics)
      await flushPromises()
      await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(false)
      return {fetchMock, reads: () => reads}
    }

    it('keeps the enabled report when an older GET resolves late and the fresh read fails', async () => {
      const stale = deferred()
      const {reads} = await mountStatistics(
        () => Promise.resolve(json({...enabledStatistics, future: true})),
        (count) =>
          count === 1
            ? json(disabledStatistics)
            : count === 2
              ? stale.promise
              : Promise.reject(new TypeError('Failed to fetch'))
      )
      wrapper.findComponent(PanelHeader).vm.$emit('refresh')
      await flushPromises()
      await wrapper.get('#enable-hibernate-statistics').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('Collecting')
      stale.resolve(json(disabledStatistics))
      await flushPromises()
      expect(reads()).toBe(3)
      expect(wrapper.text()).toContain('Collecting')
      expect(message().type).toBe('success')
    })

    it('keeps the actual unavailable/no-effect report instead of declaring activation successful', async () => {
      await mountStatistics(() =>
        Promise.resolve(
          json({...disabledStatistics, enableAvailable: false, unavailableReason: 'SessionFactory disappeared.'})
        )
      )
      await wrapper.get('#enable-hibernate-statistics').trigger('click')
      await flushPromises()
      expect(message().type).not.toBe('success')
      expect(message().text).toContain('SessionFactory disappeared.')
      expect(wrapper.text()).not.toContain('Counters start collecting now.')
    })

    it('keeps a 403 reason instead of the generic HTTP error', async () => {
      await mountStatistics(() =>
        Promise.resolve(json({error: 'BootUI panel access denied', reason: 'Statistics activation is read-only.'}, 403))
      )
      await wrapper.get('#enable-hibernate-statistics').trigger('click')
      await flushPromises()
      expect(message().text).toContain('Statistics activation is read-only.')
    })

    it('reconciles malformed acknowledgement once and preserves disabled state on a failed read', async () => {
      const {fetchMock, reads} = await mountStatistics(
        () => Promise.resolve(json({})),
        (count) => (count === 1 ? json(disabledStatistics) : Promise.reject(new TypeError('Failed to fetch')))
      )
      await wrapper.get('#enable-hibernate-statistics').trigger('click')
      await flushPromises()
      expect(message().text).toContain('outcome is unknown')
      expect(wrapper.get('#enable-hibernate-statistics').exists()).toBe(true)
      expect(reads()).toBe(2)
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
    })
  })
})
