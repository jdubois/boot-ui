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
  return {severityCounts, scan: {status}}
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
      const body = match ? handlers[match] : {}
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
    {id: 'github', available: true}
  ]
}

describe('Overview', () => {
  afterEach(() => {
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

  it('renders a card per available scanner and hides unavailable ones', async () => {
    stubFetch({})
    const wrapper = mountOverview({
      panels: [
        {id: 'vulnerabilities', available: true},
        {id: 'pentesting', available: false},
        {id: 'architecture', available: true},
        {id: 'hibernate', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Vulnerabilities')
    expect(wrapper.text()).toContain('Architecture')
    expect(wrapper.text()).not.toContain('Pentesting')
    expect(wrapper.text()).not.toContain('Hibernate')
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
    // No POST scan endpoint should have been hit on mount; only panels/overview reads.
    const calls = fetch.mock.calls.map((call) => call[0])
    expect(calls.some((url) => String(url).includes('/scan'))).toBe(false)
    expect(wrapper.text()).toContain('Run all scanners')
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

  it('aggregates scanner scores into the overall score with Run all', async () => {
    stubFetch({
      'api/vulnerabilities/scan': severityReport([{severity: 'CRITICAL', count: 1}]),
      'api/pentesting/scan': severityReport([]),
      'api/architecture/scan': severityReport([]),
      'api/hibernate/scan': severityReport([]),
      'api/github/refresh': githubReport({alerts: 0})
    })
    const wrapper = mountOverview(allPanels)
    await flushPromises()

    const runAll = wrapper.findAll('button').find((b) => b.text().includes('Run all scanners'))
    await runAll.trigger('click')
    await flushPromises()

    // Scores: vuln 75, all other advisors 100, github 100 => mean 875/9 => 97
    expect(wrapper.text()).toContain('97')
    expect(wrapper.text()).toContain('9 of 9 scanners scored')
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
})
