import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {defineComponent, h, KeepAlive, ref} from 'vue'

import Overview from './Overview.vue'
import ScannerScoreCard from './components/ScannerScoreCard.vue'

function architectureScore(wrapper) {
  const card = wrapper
    .findAllComponents(ScannerScoreCard)
    .find((component) => component.props('title') === 'Architecture')
  return card.find('.scanner-score').text()
}

function severityReport(severityCounts, status = 'SCANNED') {
  return {severityCounts, scan: {status}, coverage: {status: 'COMPLETE'}}
}

function githubReport({connected = true, authenticated = true, alerts = 0} = {}) {
  return {
    available: true,
    connected,
    status: connected ? 'CONNECTED' : 'READY',
    credential: {authenticated},
    securitySignals: [{label: 'Dependabot', status: 'AVAILABLE', count: alerts}]
  }
}

function stubFetch(handlers) {
  vi.stubGlobal(
    'fetch',
    vi.fn((input) => {
      const url = typeof input === 'string' ? input : input.url
      const match = Object.keys(handlers).find((key) => url.includes(key))
      const body = match ? handlers[match] : severityReport([], 'NOT_SCANNED')
      return Promise.resolve(new Response(JSON.stringify(body), {status: 200}))
    })
  )
}

function mountOverview(panels) {
  return mount(Overview, {
    global: {
      provide: {panels: ref(panels)},
      stubs: {RouterLink: {template: '<a><slot /></a>'}}
    }
  })
}

// Mounts the dashboard inside a <KeepAlive> with a toggle, mirroring App.vue's
// `<keep-alive include="Overview">`. Flipping `show` deactivates and re-activates
// the cached Overview so its `onActivated` refresh runs, just like navigating away
// to a panel and back.
function mountKeptAlive(panels) {
  const show = ref(true)
  const Host = defineComponent({
    setup() {
      return () => h(KeepAlive, null, {default: () => (show.value ? h(Overview) : h('div', 'away'))})
    }
  })
  const wrapper = mount(Host, {
    global: {
      provide: {panels: ref(panels)},
      stubs: {RouterLink: {template: '<a><slot /></a>'}}
    }
  })
  return {wrapper, show}
}

const allPanels = {
  panels: [
    {id: 'vulnerabilities', available: true},
    {id: 'pentesting', available: true},
    {id: 'architecture', available: true},
    {id: 'hibernate', available: true},
    {id: 'database-advisor', available: true},
    {id: 'github', available: true}
  ]
}

function onlyPanels(...available) {
  return {
    panels: [
      'architecture',
      'memory',
      'rest-api',
      'spring',
      'database-advisor',
      'hibernate',
      'security',
      'pentesting',
      'vulnerabilities',
      'github'
    ].map((id) => ({id, available: available.includes(id)}))
  }
}

function scannerCard(wrapper, title) {
  return wrapper.findAllComponents(ScannerScoreCard).find((card) => card.props('title') === title)
}

describe('Overview', () => {
  afterEach(() => {
    document.head.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('renders the Overview panel header and overall score', async () => {
    stubFetch({})
    const wrapper = mountOverview(allPanels)
    await flushPromises()
    expect(wrapper.find('h2').text()).toBe('Overview')
    expect(wrapper.text()).toContain('Overall score')
    // The marketing hero is gone; the host-app link is demoted to a small header action.
    expect(wrapper.find('a[href="/"]').text()).toContain('Application homepage')
  })

  it('links to the injected application root instead of the origin root', async () => {
    document.head.innerHTML = '<meta content="/host/" name="bootui-application-path" />'
    stubFetch({})

    const wrapper = mountOverview(allPanels)
    await flushPromises()

    expect(wrapper.get('a[href="/host/"]').text()).toContain('Application homepage')
  })

  it('renders a card per available scanner and hides unavailable ones', async () => {
    stubFetch({})
    const wrapper = mountOverview({
      panels: [
        {id: 'vulnerabilities', available: true},
        {id: 'pentesting', available: false},
        {id: 'architecture', available: true},
        {id: 'hibernate', available: false},
        {id: 'database-advisor', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Vulnerabilities')
    expect(wrapper.text()).toContain('Architecture')
    expect(wrapper.text()).not.toContain('Pentesting')
    expect(wrapper.text()).not.toContain('Hibernate')
    expect(wrapper.text()).not.toContain('Database')
    expect(wrapper.text()).not.toContain('Connect to GitHub')
  })

  it('labels the shared advisor "Quarkus" on the Quarkus platform', async () => {
    stubFetch({})
    const wrapper = mountOverview({
      platform: 'quarkus',
      panels: [{id: 'spring', available: true}]
    })
    await flushPromises()
    const titles = wrapper.findAllComponents(ScannerScoreCard).map((card) => card.props('title'))
    expect(titles).toContain('Quarkus')
    expect(titles).not.toContain('Spring')
  })

  it('labels the shared advisor "Spring" on the Spring Boot platform', async () => {
    stubFetch({})
    const wrapper = mountOverview({
      platform: 'spring-boot',
      panels: [{id: 'spring', available: true}]
    })
    await flushPromises()
    const titles = wrapper.findAllComponents(ScannerScoreCard).map((card) => card.props('title'))
    expect(titles).toContain('Spring')
    expect(titles).not.toContain('Quarkus')
  })

  it('does not run any scanner automatically', async () => {
    const fetchHandlers = {}
    stubFetch(fetchHandlers)
    const wrapper = mountOverview(allPanels)
    await flushPromises()
    // Cached report reads are safe; neither scans nor GitHub refreshes run on mount.
    const calls = fetch.mock.calls.map((call) => call[0])
    expect(calls.some((url) => String(url).includes('/scan'))).toBe(false)
    expect(calls.some((url) => String(url).includes('api/github'))).toBe(false)
    expect(wrapper.text()).toContain('Run all scanners')
  })

  it('reads every supported cached advisor once on initial KeepAlive activation', async () => {
    stubFetch(Object.fromEntries(onlyPanels().panels.map(({id}) => [`api/${id}`, severityReport([])])))
    const {wrapper} = mountKeptAlive({
      panels: onlyPanels().panels.map((panel) => ({...panel, available: true, enabled: true}))
    })
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url).sort()).toEqual(
      onlyPanels()
        .panels.filter(({id}) => id !== 'github')
        .map(({id}) => `api/${id}`)
        .sort()
    )
    expect(fetch.mock.calls.every(([, init]) => !init?.method)).toBe(true)
    expect(wrapper.text()).toContain('9 of 10 scanners scored')
  })

  it.each(['SCANNED', 'PARTIAL', 'ERROR', 'DISABLED', 'NOT_SCANNED'])(
    'loads an existing %s report on a direct Overview mount without rescanning',
    async (status) => {
      stubFetch({'api/hibernate': severityReport([{severity: 'HIGH', count: 1}], status)})
      const wrapper = mountOverview(onlyPanels('hibernate'))
      await flushPromises()
      const card = scannerCard(wrapper, 'Hibernate')
      expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/hibernate'])
      expect(card.text()).toContain('1 high')
      expect(card.find('.scanner-score').exists()).toBe(status === 'SCANNED')
      if (status === 'SCANNED') expect(card.find('.scanner-score').text()).toBe('90')
      if (status === 'PARTIAL') expect(card.text()).toContain('Incomplete')
      if (status === 'NOT_SCANNED') {
        expect(card.props('state')).toBe('idle')
        expect(card.find('button').text()).toBe('Run scan')
      }
    }
  )

  it('discovers a panel-originated report on return without a prior Overview scan', async () => {
    const handlers = {'api/hibernate': severityReport([], 'NOT_SCANNED')}
    stubFetch(handlers)
    const {wrapper, show} = mountKeptAlive(onlyPanels('hibernate'))
    await flushPromises()
    expect(scannerCard(wrapper, 'Hibernate').props('state')).toBe('idle')
    show.value = false
    await flushPromises()
    handlers['api/hibernate'] = severityReport([{severity: 'HIGH', count: 1}], 'PARTIAL')
    show.value = true
    await flushPromises()
    const card = scannerCard(wrapper, 'Hibernate')
    expect(card.text()).toContain('Incomplete')
    expect(card.text()).toContain('1 high')
    expect(card.find('.scanner-score').exists()).toBe(false)
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/hibernate', 'api/hibernate'])
  })

  it('waits for the shell manifest and skips disabled, unavailable, and unknown endpoints', async () => {
    stubFetch({'api/hibernate': severityReport([])})
    const panels = ref(null)
    const {wrapper} = mountKeptAlive(panels)
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()
    panels.value = {
      panels: [
        {id: 'hibernate', available: true, enabled: true},
        {id: 'architecture', available: true, enabled: false},
        {id: 'memory', available: false, enabled: true}
      ]
    }
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/hibernate'])
    expect(scannerCard(wrapper, 'Architecture')).toBeUndefined()
    expect(scannerCard(wrapper, 'Memory')).toBeUndefined()
  })

  it('defers discovery when the manifest arrives while Overview is inactive', async () => {
    stubFetch({'api/hibernate': severityReport([])})
    const panels = ref(null)
    const {show} = mountKeptAlive(panels)
    await flushPromises()
    show.value = false
    await flushPromises()
    panels.value = onlyPanels('hibernate')
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()
    show.value = true
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/hibernate'])
  })

  it('loads standalone availability before reading reports and surfaces manifest failures with retry', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('{}', {status: 503})))
    )
    const wrapper = mount(Overview, {global: {stubs: {RouterLink: true}}})
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/panels'])
    expect(wrapper.get('[role="alert"]').text()).toContain('Unable to load panel availability')
    stubFetch({'api/panels': onlyPanels('hibernate'), 'api/hibernate': severityReport([])})
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/panels', 'api/hibernate'])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(scannerCard(wrapper, 'Hibernate').find('.scanner-score').text()).toBe('100')
  })

  it('surfaces an initial cached report failure without claiming to have a previous report', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('offline')))
    )
    const wrapper = mountOverview(onlyPanels('hibernate'))
    await flushPromises()
    const card = scannerCard(wrapper, 'Hibernate')
    expect(card.text()).toContain('Unable to refresh Hibernate')
    expect(card.text()).not.toContain('last report')
    expect(card.find('.scanner-score').exists()).toBe(false)
  })

  it.each(['response', 'failure'])('ignores a stale initial GET %s after a newer explicit scan', async (outcome) => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    let resolveRead
    let rejectRead
    vi.stubGlobal(
      'fetch',
      vi.fn((_url, init) => {
        if (init?.method === 'POST')
          return Promise.resolve(new Response(JSON.stringify(severityReport([{severity: 'HIGH', count: 1}]))))
        return new Promise((resolve, reject) => {
          resolveRead = resolve
          rejectRead = reject
        })
      })
    )
    const wrapper = mountOverview(onlyPanels('architecture'))
    await flushPromises()
    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
    expect(fetch).toHaveBeenCalledTimes(2)
    if (outcome === 'response') resolveRead(new Response(JSON.stringify(severityReport([], 'NOT_SCANNED'))))
    else rejectRead(new TypeError('offline'))
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
    expect(scannerCard(wrapper, 'Architecture').props('state')).toBe('done')
    expect(wrapper.text()).not.toContain('Unable to refresh')
  })

  it('ignores a slower cached GET after returning to a newer panel report', async () => {
    let finishRead
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finishRead = resolve
            })
        )
        .mockResolvedValueOnce(new Response(JSON.stringify(severityReport([{severity: 'HIGH', count: 1}], 'PARTIAL'))))
    )
    const {wrapper, show} = mountKeptAlive(onlyPanels('architecture'))
    await flushPromises()
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    finishRead(new Response(JSON.stringify(severityReport([]))))
    await flushPromises()
    expect(scannerCard(wrapper, 'Architecture').text()).toContain('Incomplete')
    expect(scannerCard(wrapper, 'Architecture').text()).toContain('1 high')
    expect(wrapper.text()).toContain('0 of 1 scanners scored')
  })

  it('computes a score after running a scanner on demand', async () => {
    stubFetch({
      'api/architecture/scan': severityReport([{severity: 'HIGH', count: 1}])
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'vulnerabilities', available: false},
        {id: 'pentesting', available: false},
        {id: 'architecture', available: true},
        {id: 'hibernate', available: false},
        {id: 'database-advisor', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()

    const runButton = wrapper.findAll('button').find((b) => b.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    // 1 high finding => 100 - 10 = 90
    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('1 high')
  })

  it('includes the Database scanner in the overall score', async () => {
    stubFetch({
      'api/database-advisor/scan': severityReport([{severity: 'CRITICAL', count: 1}])
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'architecture', available: false},
        {id: 'memory', available: false},
        {id: 'rest-api', available: false},
        {id: 'spring', available: false},
        {id: 'database-advisor', available: true},
        {id: 'hibernate', available: false},
        {id: 'security', available: false},
        {id: 'pentesting', available: false},
        {id: 'vulnerabilities', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('75')
    expect(wrapper.text()).toContain('1 of 1 scanners scored')
    const databaseCard = wrapper
      .findAllComponents(ScannerScoreCard)
      .find((component) => component.props('title') === 'Database')
    expect(databaseCard.find('.scanner-score').text()).toBe('75')
  })

  it('accepts valid vulnerability findings when the summary includes UNKNOWN and NONE buckets', async () => {
    stubFetch({
      'api/vulnerabilities/scan': severityReport([
        {severity: 'HIGH', count: 1},
        {severity: 'UNKNOWN', count: 0},
        {severity: 'NONE', count: 0}
      ])
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'architecture', available: false},
        {id: 'memory', available: false},
        {id: 'rest-api', available: false},
        {id: 'spring', available: false},
        {id: 'hibernate', available: false},
        {id: 'database-advisor', available: false},
        {id: 'security', available: false},
        {id: 'pentesting', available: false},
        {id: 'vulnerabilities', available: true},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('1 high')
    expect(wrapper.text()).toContain('1 of 1 scanners scored')
    expect(wrapper.text()).not.toContain('Unable to run Vulnerabilities')
  })

  it('preserves a scanner score and shows a warning when a re-run is already active', async () => {
    const busy = {
      error: 'BootUI action already in progress',
      operation: 'architecture.scan',
      activeOperation: 'architecture.scan',
      message: "Operation 'architecture.scan' cannot start while 'architecture.scan' is in progress."
    }
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    let scans = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((input) => {
        if (String(input).includes('api/architecture/scan')) {
          scans++
          if (scans === 1) {
            return Promise.resolve(
              new Response(JSON.stringify(severityReport([{severity: 'HIGH', count: 1}])), {status: 200})
            )
          }
          return Promise.resolve(
            new Response(JSON.stringify(busy), {status: 409, headers: {'Content-Type': 'application/json'}})
          )
        }
        return Promise.resolve(new Response(JSON.stringify(severityReport([], 'NOT_SCANNED')), {status: 200}))
      })
    )
    const wrapper = mountOverview({
      panels: [{id: 'architecture', available: true}]
    })
    await flushPromises()
    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))

    await runButton.trigger('click')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')

    await runButton.trigger('click')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
    expect(wrapper.text()).toContain(busy.message)
    expect(wrapper.text()).toContain(busy.message)
  })

  it.each([
    ['polymorphic collection wrapper', ['java.util.ImmutableCollections$ListN', [{severity: 'HIGH', count: 1}]]],
    ['null count', [{severity: 'HIGH', count: null}]],
    ['negative count', [{severity: 'HIGH', count: -1}]],
    ['unknown severity', [{severity: 'UNKNOWN', count: 1}]]
  ])('does not turn a malformed %s into a perfect score', async (_description, severityCounts) => {
    stubFetch({
      'api/architecture/scan': {
        scan: {status: 'SCANNED'},
        severityCounts
      }
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'architecture', available: true},
        {id: 'memory', available: false},
        {id: 'rest-api', available: false},
        {id: 'spring', available: false},
        {id: 'hibernate', available: false},
        {id: 'database-advisor', available: false},
        {id: 'security', available: false},
        {id: 'pentesting', available: false},
        {id: 'vulnerabilities', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Unable to run Architecture')
    expect(wrapper.text()).toContain('0 of 1 scanners scored')
    expect(wrapper.text()).not.toContain('100 / 100')
  })

  it('aggregates scanner scores into the overall score with Run all', async () => {
    stubFetch({
      'api/vulnerabilities/scan': severityReport([{severity: 'CRITICAL', count: 1}]),
      'api/pentesting/scan': severityReport([]),
      'api/architecture/scan': severityReport([]),
      'api/memory/scan': severityReport([]),
      'api/rest-api/scan': severityReport([]),
      'api/spring/scan': severityReport([]),
      'api/hibernate/scan': severityReport([]),
      'api/database-advisor/scan': severityReport([]),
      'api/security/scan': severityReport([]),
      'api/github/refresh': githubReport({alerts: 0})
    })
    const wrapper = mountOverview(allPanels)
    await flushPromises()

    const runAll = wrapper.findAll('button').find((b) => b.text().includes('Run all scanners'))
    await runAll.trigger('click')
    await flushPromises()

    // Scores: vuln 75, all other advisors 100, github 100 => mean 975/10 => 98
    expect(wrapper.text()).toContain('98')
    expect(wrapper.text()).toContain('10 of 10 scanners scored')
  })

  it('surfaces a dismissible MCP Server tip after running all scanners', async () => {
    stubFetch({
      'api/architecture/scan': severityReport([]),
      'api/hibernate/scan': severityReport([]),
      'api/vulnerabilities/scan': severityReport([]),
      'api/pentesting/scan': severityReport([]),
      'api/github/refresh': githubReport({alerts: 0})
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'architecture', available: true},
        {id: 'mcp-server', available: true}
      ]
    })
    await flushPromises()

    // The tip is not shown before the scanners are run.
    expect(wrapper.find('.mcp-tip').exists()).toBe(false)

    const runAll = wrapper.findAll('button').find((b) => b.text().includes('Run all scanners'))
    await runAll.trigger('click')
    await flushPromises()

    const tip = wrapper.find('.mcp-tip')
    expect(tip.exists()).toBe(true)
    expect(tip.text()).toContain('BootUI MCP Server')

    await tip.find('.btn-close').trigger('click')
    expect(wrapper.find('.mcp-tip').exists()).toBe(false)
  })

  it('hides the MCP Server tip when the panel is unavailable', async () => {
    stubFetch({
      'api/architecture/scan': severityReport([])
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'architecture', available: true},
        {id: 'mcp-server', available: false}
      ]
    })
    await flushPromises()

    const runAll = wrapper.findAll('button').find((b) => b.text().includes('Run all scanners'))
    await runAll.trigger('click')
    await flushPromises()

    expect(wrapper.find('.mcp-tip').exists()).toBe(false)
  })

  it('shows a connect button for GitHub and excludes it from the score until authenticated', async () => {
    stubFetch({
      'api/github/refresh': githubReport({connected: false, authenticated: false})
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'vulnerabilities', available: false},
        {id: 'pentesting', available: false},
        {id: 'architecture', available: false},
        {id: 'hibernate', available: false},
        {id: 'database-advisor', available: false},
        {id: 'rest-api', available: false},
        {id: 'spring', available: false},
        {id: 'memory', available: false},
        {id: 'security', available: false},
        {id: 'github', available: true}
      ]
    })
    await flushPromises()

    const connect = wrapper.findAll('button').find((b) => b.text().includes('Connect to GitHub'))
    expect(connect).toBeTruthy()
    await connect.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Connect to GitHub to load live security metrics')
    expect(wrapper.text()).toContain('0 of 1 scanners scored')
  })

  it('refreshes a scored advisor from its report on re-activation so dismissals reflect on the dashboard', async () => {
    // POST /scan reports one HIGH finding (score 90); the GET report (re-fetched on
    // re-activation) reflects that finding having been dismissed server-side (clean => 100).
    vi.stubGlobal(
      'fetch',
      vi.fn((input, init) => {
        const url = typeof input === 'string' ? input : input.url
        const method = (init?.method || 'GET').toUpperCase()
        let body = {}
        if (url.includes('api/architecture/scan') && method === 'POST') {
          body = severityReport([{severity: 'HIGH', count: 1}])
        } else if (url.includes('api/architecture')) {
          body = severityReport([])
        }
        return Promise.resolve(new Response(JSON.stringify(body), {status: 200}))
      })
    )

    const {wrapper, show} = mountKeptAlive({
      panels: [
        {id: 'architecture', available: true},
        {id: 'memory', available: false},
        {id: 'rest-api', available: false},
        {id: 'spring', available: false},
        {id: 'hibernate', available: false},
        {id: 'database-advisor', available: false},
        {id: 'security', available: false},
        {id: 'pentesting', available: false},
        {id: 'vulnerabilities', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()

    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
    expect(wrapper.text()).toContain('1 high')

    // Navigate away (deactivate) then back (activate) -> onActivated refresh re-reads the report.
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()

    expect(architectureScore(wrapper)).toBe('100')
    expect(wrapper.text()).not.toContain('1 high')
  })

  it.each(['PARTIAL', 'ERROR', 'DISABLED', 'NOT_SCANNED', undefined, 'UNRECOGNIZED'])(
    'replaces a score with an authoritative %s report but retains findings',
    async (status) => {
      let body = severityReport([{severity: 'HIGH', count: 1}])
      vi.stubGlobal(
        'fetch',
        vi.fn(() => Promise.resolve(new Response(JSON.stringify(body))))
      )
      const wrapper = mountOverview(onlyPanels('architecture'))
      await flushPromises()
      const card = scannerCard(wrapper, 'Architecture')
      card.vm.$emit('run')
      await flushPromises()
      expect(architectureScore(wrapper)).toBe('90')
      body = {...body, scan: {status}}
      card.vm.$emit('run')
      await flushPromises()
      expect(card.find('.scanner-score').exists()).toBe(false)
      expect(card.text()).toContain('1 high')
      expect(wrapper.text()).toContain('0 of 1 scanners scored')
      expect(wrapper.find('.overall-gauge').exists()).toBe(false)
      expect(card.text()).not.toContain('No findings')
      if (status === 'PARTIAL') expect(card.text()).toContain('Incomplete')
    }
  )

  it('uses only numeric contributions for the mean, count, and breakdown', async () => {
    stubFetch({
      'api/architecture/scan': severityReport([{severity: 'HIGH', count: 1}]),
      'api/memory/scan': severityReport([{severity: 'HIGH', count: 3}]),
      'api/security/scan': severityReport([], 'PARTIAL'),
      'api/vulnerabilities/scan': {...severityReport([]), coverage: null}
    })
    const wrapper = mountOverview(onlyPanels('architecture', 'memory', 'security', 'vulnerabilities'))
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Run all scanners'))
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.overall-gauge__value').text()).toBe('80')
    expect(wrapper.find('.overall-card').text()).toContain('2 of 4 scanners scored')
    expect(wrapper.find('.overall-card').text()).not.toContain('Security')
    expect(wrapper.find('.overall-card').text()).not.toContain('Vulnerabilities')
    expect(wrapper.find('.overall-card').text()).toContain('Mean of scored scanners only')
  })

  it('refreshes vulnerability eligibility after UNKNOWN dismissal and restoration, without rescanning', async () => {
    let body = severityReport([
      {severity: 'UNKNOWN', count: 1},
      {severity: 'HIGH', count: 1}
    ])
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(body))))
    vi.stubGlobal('fetch', fetchMock)
    const {wrapper, show} = mountKeptAlive(onlyPanels('vulnerabilities'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('0 of 1 scanners scored')
    for (const [unknown, score] of [
      [0, '90'],
      [1, null],
      [0, '90']
    ]) {
      body = severityReport([
        {severity: 'UNKNOWN', count: unknown},
        {severity: 'HIGH', count: 1}
      ])
      show.value = false
      await flushPromises()
      show.value = true
      await flushPromises()
      const card = scannerCard(wrapper, 'Vulnerabilities')
      expect(card.find('.scanner-score').exists()).toBe(score !== null)
      if (score) expect(card.find('.scanner-score').text()).toBe(score)
      expect(card.text()).toContain('1 high')
    }
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    expect(fetchMock.mock.calls.filter(([url, init]) => url === 'api/vulnerabilities' && !init?.method)).toHaveLength(4)
  })

  it('preserves a cached score on transport failure, then accepts an unscanned GET report', async () => {
    let response = () => Promise.resolve(new Response(JSON.stringify(severityReport([{severity: 'HIGH', count: 1}]))))
    vi.stubGlobal(
      'fetch',
      vi.fn(() => response())
    )
    const {wrapper, show} = mountKeptAlive(onlyPanels('architecture'))
    await flushPromises()
    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    response = () => Promise.reject(new TypeError('offline'))
    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
    expect(wrapper.text()).toContain('1 of 1 scanners scored')
    expect(wrapper.text()).toContain('Showing the last report')
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
    expect(wrapper.text()).toContain('Unable to refresh Architecture')
    response = () => Promise.resolve(new Response(JSON.stringify(severityReport([], 'NOT_SCANNED'))))
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(scannerCard(wrapper, 'Architecture').find('.scanner-score').exists()).toBe(false)
    expect(scannerCard(wrapper, 'Architecture').props('state')).toBe('idle')
    expect(scannerCard(wrapper, 'Architecture').text()).not.toContain('1 high')
    expect(scannerCard(wrapper, 'Architecture').text()).not.toContain('Showing the last report')
    expect(wrapper.text()).toContain('0 of 1 scanners scored')
  })

  it('defers cached refresh until an active scan settles instead of invalidating its result', async () => {
    let finishScan
    let calls = 0
    let cachedStatus = 'SCANNED'
    vi.stubGlobal(
      'fetch',
      vi.fn((_url, init) => {
        if (init?.method === 'POST' && calls++ > 0)
          return new Promise((resolve) => {
            finishScan = resolve
          })
        return Promise.resolve(new Response(JSON.stringify(severityReport([], cachedStatus))))
      })
    )
    const {wrapper, show} = mountKeptAlive(onlyPanels('architecture'))
    await flushPromises()
    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    expect(wrapper.text()).toContain('1 of 1 scanners scored')
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(fetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(1)
    expect(wrapper.text()).toContain('Scanning')
    cachedStatus = 'PARTIAL'
    finishScan(new Response(JSON.stringify(severityReport([], cachedStatus))))
    await flushPromises()
    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.text()).toContain('0 of 1 scanners scored')
    expect(fetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2)
  })

  it.each(['PARTIAL', 'ERROR', 'DISABLED'])(
    'accepts authoritative %s status even if its counts are malformed',
    async (status) => {
      let body = severityReport([])
      vi.stubGlobal(
        'fetch',
        vi.fn(() => Promise.resolve(new Response(JSON.stringify(body))))
      )
      const wrapper = mountOverview(onlyPanels('architecture'))
      await flushPromises()
      const card = scannerCard(wrapper, 'Architecture')
      card.vm.$emit('run')
      await flushPromises()
      expect(architectureScore(wrapper)).toBe('100')
      body = severityReport([{count: 1}], status)
      card.vm.$emit('run')
      await flushPromises()
      expect(card.find('.scanner-score').exists()).toBe(false)
      expect(card.text()).toContain('invalid severity summary')
      expect(wrapper.text()).toContain('0 of 1 scanners scored')
    }
  )
})
