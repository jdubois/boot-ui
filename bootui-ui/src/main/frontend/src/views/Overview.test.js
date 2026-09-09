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
  const findings = severityCounts.filter((entry) => entry.count > 0).map(({severity}) => ({severity}))
  return {
    severityCounts,
    scan: {status},
    coverage: {status: 'COMPLETE'},
    evidence: {
      usable: findings.length === 0 || findings.some(({severity}) => severity !== 'UNKNOWN'),
      coverageComplete: status !== 'PARTIAL' && !findings.some(({severity}) => severity === 'UNKNOWN'),
      limitations: []
    },
    results: findings.map((finding) => ({...finding, status: 'VIOLATION'})),
    dependencies: [{assessment: {queryComplete: true, detailAssessmentComplete: true}, vulnerabilities: findings}]
  }
}

function githubReport({connected = true, authenticated = true, alerts = 0} = {}) {
  return {
    available: true,
    connected,
    status: connected ? 'CONNECTED' : 'READY',
    credential: {authenticated},
    securitySignals: [
      {label: 'Dependabot alerts', status: 'AVAILABLE', count: alerts},
      {label: 'Code scanning alerts', status: 'AVAILABLE', count: 0},
      {label: 'Secret scanning alerts', status: 'AVAILABLE', count: 0}
    ]
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
  it('keeps every unscored advisor compact after a full scan, with details left to its panel', async () => {
    const ids = onlyPanels()
      .panels.filter(({id}) => id !== 'github')
      .map(({id}) => id)
    const limitations = Array.from({length: 20}, (_, index) => `Required observation ${index + 1} was unavailable.`)
    const report = {
      severityCounts: [{severity: 'INFO', count: 1}],
      scan: {status: 'PARTIAL', message: 'Some required observations could not be collected.'},
      evidence: {usable: false, coverageComplete: false, limitations}
    }
    stubFetch(Object.fromEntries(ids.map((id) => [`api/${id}/scan`, report])))
    const wrapper = mountOverview(onlyPanels(...ids))
    await flushPromises()
    const runAll = wrapper.findAll('button').find((button) => button.text() === 'Run all scanners')
    await runAll.trigger('click')
    await flushPromises()

    const cards = wrapper.findAllComponents(ScannerScoreCard)
    expect(cards).toHaveLength(ids.length)
    for (const card of cards) {
      expect(card.get('.scanner-assessment').text()).toBe('Not scored')
      expect(card.get('.scanner-status').text()).toBe('Incomplete')
      expect(card.text()).toContain('1 info')
      expect(card.find('.scanner-score').exists()).toBe(false)
      expect(card.get('a').attributes('aria-label')).toBe(`Open panel: ${card.props('title')}`)
      expect(card.text()).not.toContain(report.scan.message)
      expect(card.text()).not.toContain('No usable assessment evidence')
      for (const reason of limitations) expect(card.text()).not.toContain(reason)
    }
    expect(wrapper.get('.overall-card').text()).toContain('9 of 9 advisors assessed')
    expect(wrapper.get('.assessment-summary').text()).toContain('9 advisors have scan notes')
    expect(wrapper.find('.overall-gauge').exists()).toBe(false)
  })

  it.each([
    [0, 'success'],
    [3, 'warning'],
    [6, 'danger']
  ])('colors GitHub with %i alerts as %s', async (alerts, tone) => {
    stubFetch({'api/github/refresh': githubReport({alerts})})
    const wrapper = mountOverview(onlyPanels('github'))
    await flushPromises()
    const card = scannerCard(wrapper, 'GitHub')
    await card.get('button').trigger('click')
    await flushPromises()
    expect(card.get('.scanner-score').classes()).toContain(`text-${tone}-emphasis`)
    expect(card.get('.scanner-score').text()).toBe(String(100 - alerts * 10))
    expect(card.get('.scanner-status').text()).toBe('Connected')
  })

  afterEach(() => {
    document.head.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it.each([
    [100, 'success', 'Good'],
    [80, 'success', 'Good'],
    [79, 'warning', 'Needs attention'],
    [50, 'warning', 'Needs attention'],
    [49, 'danger', 'At risk'],
    [0, 'danger', 'At risk']
  ])('restores the %i-point circular gauge with its %s band', async (score, tone, label) => {
    stubFetch({'api/architecture': severityReport([{severity: 'LOW', count: 100 - score}])})
    const wrapper = mountOverview(onlyPanels('architecture'))
    await flushPromises()

    const gauge = wrapper.get('.overall-gauge')
    expect(gauge.classes()).toContain(`overall-gauge--${tone}`)
    expect(gauge.get('.overall-gauge__value').text()).toBe(String(score))
    expect(gauge.get('.overall-gauge__max').text()).toBe('/ 100')
    expect(gauge.attributes('aria-label')).toBe(`Overall score: ${score} out of 100 — Average of 1 score`)
    expect(wrapper.get('.overall-card').findAll('[role="img"]')).toHaveLength(1)
    expect(wrapper.get('.overall-band').text()).toBe(label)
    expect(wrapper.get('.overall-band').classes()).toContain(`text-bg-${tone}`)
    const contribution = wrapper.get('.overall-contributions li')
    expect(contribution.text()).toContain('Architecture')
    expect(contribution.get('.fw-semibold').text()).toBe(String(score - 100))
  })

  it('lists only scored advisors in penalty order with platform-specific titles', async () => {
    stubFetch({
      'api/architecture': severityReport([{severity: 'MEDIUM', count: 3}], 'PARTIAL'),
      'api/spring': severityReport([{severity: 'HIGH', count: 2}]),
      'api/security': severityReport([], 'NOT_SCANNED')
    })
    const wrapper = mountOverview({...onlyPanels('architecture', 'spring', 'security'), platform: 'quarkus'})
    await flushPromises()

    const contributions = wrapper.findAll('.overall-contributions li')
    expect(contributions).toHaveLength(2)
    expect(contributions[0].findAll('span').map((span) => span.text())).toEqual(['Quarkus', '-20'])
    expect(contributions[1].findAll('span').map((span) => span.text())).toEqual(['Architecture', '-9'])
    expect(wrapper.get('.overall-gauge__value').text()).toBe('86')
    expect(wrapper.get('.assessment-summary').text()).toContain('1 advisor has scan notes')
    expect(wrapper.get('.overall-assessment').text()).toContain('1 not scanned')
  })

  it('renders the Overview panel header and an unscored overall state before scans', async () => {
    stubFetch({})
    const wrapper = mountOverview(allPanels)
    await flushPromises()
    expect(wrapper.find('h2').text()).toBe('Overview')
    expect(wrapper.text()).toContain('Overall score')
    expect(wrapper.get('.overall-card').text()).toContain('Not scored')
    expect(wrapper.find('.overall-score').exists()).toBe(false)
    expect(wrapper.find('.overall-gauge').exists()).toBe(false)
    expect(wrapper.find('.overall-contributions').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Known-findings score')
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

  it('stays unscored when returning from GraalVM and CRaC with no eligible advisor reports', async () => {
    stubFetch({
      'api/graalvm': severityReport([{severity: 'CRITICAL', count: 4}]),
      'api/crac': severityReport([{severity: 'CRITICAL', count: 4}])
    })
    const panels = onlyPanels('architecture', 'memory', 'spring', 'github')
    panels.panels.push({id: 'graalvm', available: true}, {id: 'crac', available: true})
    const {wrapper, show} = mountKeptAlive(panels)
    await flushPromises()

    for (const id of ['graalvm', 'crac']) {
      show.value = false
      await flushPromises()
      await fetch(`api/${id}/scan`, {method: 'POST'})
      show.value = true
      await flushPromises()
      const summary = wrapper.get('.overall-card')
      expect(summary.text()).toContain('Not scored')
      expect(summary.text()).not.toContain('At risk')
      expect(summary.find('.overall-gauge').exists()).toBe(false)
      expect(summary.find('.overall-contributions').exists()).toBe(false)
    }
    const reads = fetch.mock.calls.filter(([, options]) => options?.method !== 'POST').map(([url]) => url)
    expect(reads.some((url) => /api\/(graalvm|crac)/.test(url))).toBe(false)
    expect(reads.some((url) => url.includes('api/github/refresh'))).toBe(false)
    wrapper.unmount()
  })

  it('renders a card per available scanner and hides unavailable ones', async () => {
    stubFetch({})
    const wrapper = mountOverview(onlyPanels('vulnerabilities', 'architecture'))
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
    expect(wrapper.text()).toContain('9 of 9 advisors assessed')
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
      expect(card.find('.scanner-score').exists()).toBe(['SCANNED', 'PARTIAL'].includes(status))
      if (['SCANNED', 'PARTIAL'].includes(status)) expect(card.find('.scanner-score').text()).toBe('90')
      if (['SCANNED', 'PARTIAL'].includes(status)) {
        expect(card.get('.scanner-status').text()).toBe('Scan complete')
        expect(card.get('.scanner-status').classes()).toContain('text-bg-secondary')
        expect(card.props('incomplete')).toBe(status === 'PARTIAL')
      }
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
    expect(card.text()).toContain('Scan complete')
    expect(card.text()).toContain('1 high')
    expect(card.find('.scanner-score').text()).toBe('90')
    expect(card.text()).not.toContain('Scan notes available in panel.')
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
    expect(scannerCard(wrapper, 'Architecture').text()).toContain('Scan complete')
    expect(scannerCard(wrapper, 'Architecture').text()).toContain('1 high')
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
    expect(architectureScore(wrapper)).toBe('90')
  })

  it('computes a score after running a scanner on demand', async () => {
    stubFetch({
      'api/architecture/scan': severityReport([{severity: 'HIGH', count: 1}])
    })
    const wrapper = mountOverview(onlyPanels('architecture'))
    await flushPromises()

    const runButton = wrapper.findAll('button').find((b) => b.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    // 1 high finding => 100 - 10 = 90
    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('1 high')
  })

  it('shows the Database score independently', async () => {
    stubFetch({
      'api/database-advisor/scan': severityReport([{severity: 'CRITICAL', count: 1}])
    })
    const wrapper = mountOverview(onlyPanels('database-advisor'))
    await flushPromises()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('75')
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
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
    const wrapper = mountOverview(onlyPanels('vulnerabilities'))
    await flushPromises()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('1 high')
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
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
    const wrapper = mountOverview(onlyPanels('architecture'))
    await flushPromises()
    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))

    await runButton.trigger('click')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')

    await runButton.trigger('click')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('90')
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
    const wrapper = mountOverview(onlyPanels('architecture'))
    await flushPromises()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('Run scan'))
    await runButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Unable to run Architecture')
    expect(wrapper.text()).toContain('0 of 1 advisors assessed')
    expect(wrapper.text()).not.toContain('100 / 100')
  })

  it('averages eligible scanner scores and GitHub with Run all and a contributing count', async () => {
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

    expect(scannerCard(wrapper, 'Vulnerabilities').find('.scanner-score').text()).toBe('75')
    expect(wrapper.get('.overall-score').attributes('aria-label')).toBe(
      'Overall score: 98 out of 100 — Average of 10 scores'
    )
    expect(wrapper.text()).toContain('9 of 9 advisors assessed')
    expect(scannerCard(wrapper, 'GitHub').get('.scanner-score').text()).toBe('100')
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

  it('shows GitHub connection state, outside advisor counts', async () => {
    stubFetch({
      'api/github/refresh': githubReport({connected: false, authenticated: false})
    })
    const wrapper = mountOverview(onlyPanels('github'))
    await flushPromises()

    const connect = wrapper.findAll('button').find((b) => b.text().includes('Connect to GitHub'))
    expect(connect).toBeTruthy()
    await connect.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Not connected')
    expect(wrapper.text()).toContain('0 of 0 advisors assessed')
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

    const {wrapper, show} = mountKeptAlive(onlyPanels('architecture'))
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
    expect(wrapper.get('.overall-score .overview-score-value').text()).toBe('100')
    expect(wrapper.text()).not.toContain('1 high')
  })

  it('runs GitHub alone and displays actual counts and unknown signals without scoring them', async () => {
    stubFetch({
      'api/github/refresh': {
        ...githubReport(),
        securitySignals: [
          {label: 'Dependabot', status: 'AVAILABLE', count: 3},
          {label: 'Secret scanning', status: 'UNAVAILABLE', count: 0},
          {label: 'Code scanning', status: 'AVAILABLE', count: null}
        ]
      }
    })
    const wrapper = mountOverview(onlyPanels('github'))
    await flushPromises()
    const runAll = wrapper.findAll('button').find((button) => button.text() === 'Run all scanners')
    expect(runAll.attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('No technology scanners')
    expect(fetch).not.toHaveBeenCalled()
    await runAll.trigger('click')
    await flushPromises()
    const card = scannerCard(wrapper, 'GitHub')
    expect(card.text()).toContain('Connected · Authenticated')
    expect(card.text()).toContain('Dependabot: 3 open')
    expect(card.text()).toContain('Secret scanning: Unavailable')
    expect(card.text()).toContain('Code scanning: Unavailable')
    expect(card.text()).not.toMatch(/Good|100|high|No open security alerts/)
    expect(wrapper.text()).toContain('0 of 0 advisors assessed')
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/github/refresh'])
    await runAll.trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/github/refresh', 'api/github/refresh'])
  })

  it('keeps authenticated GitHub with unavailable signals distinct from confirmed zero counts', async () => {
    const handlers = {'api/github/refresh': {...githubReport(), securitySignals: []}}
    stubFetch(handlers)
    const wrapper = mountOverview(onlyPanels('github'))
    await flushPromises()
    const card = scannerCard(wrapper, 'GitHub')
    await card.get('button').trigger('click')
    await flushPromises()
    expect(card.text()).toContain('Security signals unavailable.')
    expect(card.find('[role="img"]').exists()).toBe(false)
    handlers['api/github/refresh'] = githubReport()
    await card.get('button').trigger('click')
    await flushPromises()
    expect(card.text()).toContain('Dependabot alerts: 0 open')
    expect(card.get('.scanner-score').text()).toBe('100')
    expect(wrapper.get('.overall-score').attributes('aria-label')).toBe(
      'Overall score: 100 out of 100 — Average of 1 score'
    )
    expect(card.text()).not.toMatch(/Good|high/)
  })

  it('leaves three MEDIUM findings at 91 when another advisor becomes clean', async () => {
    const handlers = {'api/architecture': severityReport([{severity: 'MEDIUM', count: 3}])}
    stubFetch(handlers)
    const wrapper = mountOverview(onlyPanels('architecture', 'memory'))
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('91')
    handlers['api/memory/scan'] = severityReport([])
    scannerCard(wrapper, 'Memory').vm.$emit('run')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('91')
    expect(wrapper.find('.overall-card').text()).toContain('3 medium')
    expect(wrapper.find('.overall-card').text()).toContain('2 of 2 advisors assessed')
    expect(wrapper.get('.overall-score .overview-score-value').text()).toBe('96')
  })

  it('includes GitHub only when scoreable and visible, without changing retained advisor penalties', async () => {
    const handlers = {
      'api/architecture': severityReport([{severity: 'MEDIUM', count: 3}], 'PARTIAL'),
      'api/github/refresh': githubReport({alerts: 2})
    }
    stubFetch(handlers)
    const panels = ref(onlyPanels('architecture', 'github'))
    const wrapper = mountOverview(panels)
    await flushPromises()
    expect(wrapper.get('.overall-score .overview-score-value').text()).toBe('91')
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['api/architecture'])
    const card = scannerCard(wrapper, 'GitHub')
    await card.get('button').trigger('click')
    await flushPromises()
    expect(card.get('[role="img"]').attributes('aria-label')).toBe('GitHub security-alert score: 80 out of 100')
    expect(wrapper.get('.overall-score').attributes('aria-label')).toBe(
      'Overall score: 86 out of 100 — Average of 2 scores'
    )
    expect(wrapper.get('.overall-card').text()).toContain('1 of 1 advisors assessed')
    expect(wrapper.get('.overall-card').text()).not.toContain('high')
    expect(
      wrapper.findAll('.overall-contributions li').map((item) => item.findAll('span').map((span) => span.text()))
    ).toEqual([
      ['GitHub', '-20'],
      ['Architecture', '-9']
    ])
    expect(architectureScore(wrapper)).toBe('91')

    panels.value = onlyPanels('architecture')
    await flushPromises()
    expect(wrapper.get('.overall-score .overview-score-value').text()).toBe('91')
    panels.value = onlyPanels('architecture', 'github')
    await flushPromises()
    for (const unavailable of [
      {...githubReport(), connected: false},
      {...githubReport(), securitySignals: undefined},
      {...githubReport(), securitySignals: [null]},
      {...githubReport(), securitySignals: {}}
    ]) {
      handlers['api/github/refresh'] = unavailable
      await scannerCard(wrapper, 'GitHub').get('button').trigger('click')
      await flushPromises()
      expect(scannerCard(wrapper, 'GitHub').find('[role="img"]').exists()).toBe(false)
      expect(wrapper.get('.overall-score').attributes('aria-label')).toBe(
        'Overall score: 91 out of 100 — Average of 1 score'
      )
      expect(wrapper.get('.overall-contributions').text()).not.toContain('GitHub')
    }
  })

  it('retains GitHub and the overall score during refresh and transport failure, then accepts unavailable data', async () => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    stubFetch({'api/github/refresh': githubReport({alerts: 2})})
    const wrapper = mountOverview(onlyPanels('github'))
    await flushPromises()
    const card = scannerCard(wrapper, 'GitHub')
    await card.get('button').trigger('click')
    await flushPromises()
    let rejectRefresh
    fetch.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          rejectRefresh = reject
        })
    )
    await card.get('button').trigger('click')
    await flushPromises()
    expect(card.text()).toContain('Showing the last report.')
    expect(card.get('.scanner-score').text()).toBe('80')
    expect(wrapper.get('.overall-score .overview-score-value').text()).toBe('80')
    rejectRefresh(new TypeError('offline'))
    await flushPromises()
    expect(card.text()).toContain('Unable to connect to GitHub')
    expect(card.get('.scanner-score').text()).toBe('80')
    expect(wrapper.get('.overall-score .overview-score-value').text()).toBe('80')
    stubFetch({'api/github/refresh': {...githubReport(), securitySignals: []}})
    await card.get('button').trigger('click')
    await flushPromises()
    expect(card.find('.scanner-score').exists()).toBe(false)
    expect(wrapper.find('.overall-score').exists()).toBe(false)
    expect(wrapper.get('.overall-card').text()).toContain('Not scored')
  })

  it('keeps score 91 when scan notes arrive and disappear without changing severity counts', async () => {
    const report = severityReport([{severity: 'MEDIUM', count: 3}])
    const handlers = {'api/architecture': report}
    stubFetch(handlers)
    const wrapper = mountOverview(onlyPanels('architecture'))
    await flushPromises()
    const card = scannerCard(wrapper, 'Architecture')
    for (const incomplete of [true, false]) {
      handlers['api/architecture'] = {
        ...report,
        evidence: {
          ...report.evidence,
          coverageComplete: !incomplete,
          limitations: incomplete ? ['Metadata unavailable.'] : []
        }
      }
      card.vm.$emit('run')
      await flushPromises()
      expect(architectureScore(wrapper)).toBe('91')
      expect(card.props('incomplete')).toBe(incomplete)
      expect(card.get('.scanner-status').text()).toBe('Scan complete')
      expect(card.get('.scanner-status').classes()).toContain('text-bg-secondary')
      expect(card.find('.scanner-assessment').exists()).toBe(false)
      expect(card.get('[role="img"]').attributes('aria-label')).toBe(
        `Architecture known-findings score: 91 out of 100${incomplete ? ' — Scan notes available' : ''}`
      )
      expect(wrapper.find('.assessment-summary').exists()).toBe(incomplete)
      expect(wrapper.text()).not.toContain('Metadata unavailable.')
    }
  })

  it.each(['ERROR', 'DISABLED', 'NOT_SCANNED', undefined, 'UNRECOGNIZED'])(
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
      expect(wrapper.text()).toContain('0 of 1 advisors assessed')
      expect(wrapper.find('.overall-score').exists()).toBe(false)
      expect(card.text()).not.toContain('No findings')
    }
  )

  it('averages usable partial scores without a penalty for scan notes or missing evidence', async () => {
    stubFetch({
      'api/architecture/scan': severityReport([{severity: 'MEDIUM', count: 3}]),
      'api/memory/scan': severityReport([{severity: 'HIGH', count: 3}], 'PARTIAL'),
      'api/security/scan': severityReport([], 'PARTIAL'),
      'api/vulnerabilities/scan': {...severityReport([]), evidence: undefined}
    })
    const wrapper = mountOverview(onlyPanels('architecture', 'memory', 'security', 'vulnerabilities'))
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Run all scanners'))
      .trigger('click')
    await flushPromises()
    expect(architectureScore(wrapper)).toBe('91')
    expect(wrapper.find('.overall-card').text()).toContain('3 of 4 advisors assessed')
    expect(wrapper.find('.overall-card').text()).toContain('3 medium')
    expect(wrapper.get('.overall-score').attributes('aria-label')).toBe(
      'Overall score: 87 out of 100 — Average of 3 scores'
    )
    expect(scannerCard(wrapper, 'Security').find('.scanner-score').text()).toBe('100')
    expect(scannerCard(wrapper, 'Security').text()).not.toContain('Scan notes available in panel.')
  })

  it('summarizes scan notes without counting idle, failed, disabled or unavailable scanners', async () => {
    const reports = {
      'api/architecture': {
        ...severityReport([{severity: 'HIGH', count: 1}], 'PARTIAL'),
        scan: {status: 'PARTIAL', message: 'Detailed architecture coverage explanation.'}
      },
      'api/vulnerabilities': severityReport([{severity: 'UNKNOWN', count: 1}]),
      'api/security': severityReport([], 'ERROR'),
      'api/hibernate': severityReport([], 'DISABLED'),
      'api/memory': severityReport([], 'NOT_SCANNED'),
      'api/pentesting': severityReport([], 'PARTIAL')
    }
    stubFetch(reports)
    const wrapper = mountOverview(onlyPanels('architecture', 'vulnerabilities', 'security', 'hibernate', 'memory'))
    await flushPromises()
    expect(wrapper.find('.assessment-summary').text()).toBe('2 advisors have scan notes. Open a panel for details.')
    expect(wrapper.text()).not.toContain('Detailed architecture coverage explanation.')
    expect(scannerCard(wrapper, 'Architecture').find('.scanner-score').text()).toBe('90')
    expect(scannerCard(wrapper, 'Vulnerabilities').find('.scanner-score').exists()).toBe(false)

    reports['api/architecture'] = severityReport([])
    scannerCard(wrapper, 'Architecture').vm.$emit('run')
    await flushPromises()
    expect(wrapper.find('.assessment-summary').text()).toBe('1 advisor has scan notes. Open a panel for details.')

    reports['api/vulnerabilities'] = severityReport([], 'NOT_SCANNED')
    scannerCard(wrapper, 'Vulnerabilities').vm.$emit('run')
    await flushPromises()
    expect(wrapper.find('.assessment-summary').exists()).toBe(false)
  })

  it('keeps confirmed empty scope out of scores and incomplete counts across scans and cached refreshes', async () => {
    const empty = {
      ...severityReport([]),
      evidence: {usable: false, coverageComplete: true, limitations: []}
    }
    const reports = {
      'api/architecture': empty,
      'api/memory': severityReport([{severity: 'HIGH', count: 1}])
    }
    stubFetch(reports)
    const {wrapper, show} = mountKeptAlive(onlyPanels('architecture', 'memory'))
    await flushPromises()
    const card = scannerCard(wrapper, 'Architecture')
    const expectEmpty = () => {
      expect(card.text()).toContain('Not applicable')
      expect(card.find('.scanner-score').exists()).toBe(false)
      expect(wrapper.find('.assessment-summary').exists()).toBe(false)
      expect(scannerCard(wrapper, 'Memory').find('.scanner-score').text()).toBe('90')
      expect(wrapper.text()).toContain('2 of 2 advisors assessed')
      expect(wrapper.get('.overall-score').attributes('aria-label')).toBe(
        'Overall score: 90 out of 100 — Average of 1 score'
      )
    }
    expectEmpty()

    reports['api/architecture'] = {...empty, evidence: undefined}
    card.vm.$emit('run')
    await flushPromises()
    expect(wrapper.find('.assessment-summary').text()).toBe('1 advisor has scan notes. Open a panel for details.')
    expect(card.find('.scanner-score').exists()).toBe(false)
    reports['api/architecture'] = empty
    card.vm.$emit('run')
    await flushPromises()
    expectEmpty()

    reports['api/architecture'] = {...empty, scan: {status: 'PARTIAL'}}
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(wrapper.find('.assessment-summary').text()).toBe('1 advisor has scan notes. Open a panel for details.')
    reports['api/architecture'] = empty
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expectEmpty()

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(card.text()).toContain('Unable to refresh Architecture')
    expectEmpty()
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
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
    for (const [unknown, score] of [
      [0, '90'],
      [1, '90'],
      [0, '90']
    ]) {
      body = {
        ...body,
        severityCounts: [
          {severity: 'UNKNOWN', count: unknown},
          {severity: 'HIGH', count: 1}
        ]
      }
      show.value = false
      await flushPromises()
      show.value = true
      await flushPromises()
      const card = scannerCard(wrapper, 'Vulnerabilities')
      expect(card.find('.scanner-score').exists()).toBe(score !== null)
      if (score) expect(card.find('.scanner-score').text()).toBe(score)
      expect(card.text()).toContain('1 high')
      expect(card.text()).not.toContain('Scan notes available in panel.')
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
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
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
    expect(wrapper.text()).toContain('0 of 1 advisors assessed')
  })

  it('never creates a score from UNKNOWN-only dismissal on return navigation', async () => {
    let dismissed = false
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              ...severityReport([{severity: 'UNKNOWN', count: dismissed ? 0 : 1}]),
              evidence: {
                usable: false,
                coverageComplete: false,
                limitations: ['Unknown severity.']
              },
              dependencies: [
                {
                  assessment: {queryComplete: true, detailAssessmentComplete: true},
                  vulnerabilities: [{severity: 'UNKNOWN', dismissed}]
                }
              ]
            })
          )
        )
      )
    )
    const {wrapper, show} = mountKeptAlive(onlyPanels('vulnerabilities'))
    await flushPromises()
    for (const next of [true, false]) {
      dismissed = next
      show.value = false
      await flushPromises()
      show.value = true
      await flushPromises()
      expect(scannerCard(wrapper, 'Vulnerabilities').find('.scanner-score').exists()).toBe(false)
      expect(wrapper.text()).toContain('1 of 1 advisors assessed')
      expect(wrapper.text()).toContain('1 advisor has scan notes. Open a panel for details.')
    }
    expect(fetch.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  })

  it.each(['SCANNED', 'PARTIAL'])(
    'rejects malformed %s evidence and labels the retained prior report',
    async (status) => {
      let body = severityReport([{severity: 'HIGH', count: 1}], 'PARTIAL')
      vi.stubGlobal(
        'fetch',
        vi.fn(() => Promise.resolve(new Response(JSON.stringify(body))))
      )
      const wrapper = mountOverview(onlyPanels('architecture'))
      await flushPromises()
      body = {...body, scan: {status}, evidence: {...body.evidence, usable: 'true'}}
      scannerCard(wrapper, 'Architecture').vm.$emit('run')
      await flushPromises()
      expect(architectureScore(wrapper)).toBe('90')
      expect(wrapper.text()).toContain('invalid assessment evidence')
      expect(wrapper.text()).toContain('Showing the last report')
      expect(scannerCard(wrapper, 'Architecture').text()).not.toContain('Scan notes available in panel.')
    }
  )

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
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(fetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(1)
    expect(wrapper.text()).toContain('Scanning')
    cachedStatus = 'PARTIAL'
    finishScan(new Response(JSON.stringify(severityReport([], cachedStatus))))
    await flushPromises()
    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('1 of 1 advisors assessed')
    expect(scannerCard(wrapper, 'Architecture').text()).not.toContain('Scan notes available in panel.')
    expect(fetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2)
  })

  it.each(['ERROR', 'DISABLED'])('accepts authoritative %s status even if its counts are malformed', async (status) => {
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
    expect(wrapper.text()).toContain('0 of 1 advisors assessed')
  })

  it('keeps findings and coverage visible alongside a neutral partial overall 100', async () => {
    const report = {
      ...severityReport([]),
      evidence: {
        usable: true,
        coverageComplete: false,
        limitations: ['Metadata unavailable.']
      }
    }
    stubFetch({
      'api/architecture': report,
      'api/security': {...severityReport([{severity: 'HIGH', count: 2}]), scan: {status: 'ERROR'}}
    })
    const wrapper = mountOverview(onlyPanels('architecture', 'security', 'github'))
    await flushPromises()
    const summary = wrapper.find('.overall-card')
    expect(summary.text()).toContain('1 of 2 advisors assessed')
    expect(summary.text()).toContain('1 failed')
    expect(summary.text()).toContain('2 high')
    expect(summary.findAll('[role="img"]')).toHaveLength(1)
    expect(summary.get('[role="img"]').attributes('aria-label')).toBe(
      'Overall score: 100 out of 100 — Average of 1 score'
    )
    expect(summary.get('.overall-band').text()).toBe('Good')
    expect(summary.get('.overall-band').attributes('title')).toContain('not a safety or coverage assessment')
    expect(summary.text()).not.toContain('passing')
    expect(summary.find('.overall-assessment .text-success').exists()).toBe(false)
    expect(summary.find('h2').text()).toBe('Overall score')
    expect(summary.find('details').exists()).toBe(false)
    expect(summary.find('.assessment-summary').text()).toBe('1 advisor has scan notes. Open a panel for details.')
    expect(scannerCard(wrapper, 'Architecture').text()).not.toContain('Metadata unavailable.')
    expect(fetch.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  })
})
