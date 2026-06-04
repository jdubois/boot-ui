import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import Overview from './Overview.vue'

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

const allPanels = {
  panels: [
    {id: 'vulnerabilities', available: true},
    {id: 'pentest', available: true},
    {id: 'architecture', available: true},
    {id: 'hibernate-advisor', available: true},
    {id: 'github', available: true}
  ]
}

describe('Overview', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps the welcome hero heading', async () => {
    stubFetch({})
    const wrapper = mountOverview(allPanels)
    await flushPromises()
    expect(wrapper.find('h2').text()).toBe('Overview')
    expect(wrapper.text()).toContain('Overall score')
  })

  it('renders a card per available scanner and hides unavailable ones', async () => {
    stubFetch({})
    const wrapper = mountOverview({
      panels: [
        {id: 'vulnerabilities', available: true},
        {id: 'pentest', available: false},
        {id: 'architecture', available: true},
        {id: 'hibernate-advisor', available: false},
        {id: 'github', available: false}
      ]
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Vulnerabilities')
    expect(wrapper.text()).toContain('Architecture')
    expect(wrapper.text()).not.toContain('Pentesting')
    expect(wrapper.text()).not.toContain('Hibernate Advisor')
    expect(wrapper.text()).not.toContain('Connect to GitHub')
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
        {id: 'pentest', available: false},
        {id: 'architecture', available: true},
        {id: 'hibernate-advisor', available: false},
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
      'api/dependencies/scan': severityReport([{severity: 'CRITICAL', count: 1}]),
      'api/pentest/scan': severityReport([]),
      'api/architecture/scan': severityReport([]),
      'api/hibernate-advisor/scan': severityReport([]),
      'api/github/refresh': githubReport({alerts: 0})
    })
    const wrapper = mountOverview(allPanels)
    await flushPromises()

    const runAll = wrapper.findAll('button').find((b) => b.text().includes('Run all scanners'))
    await runAll.trigger('click')
    await flushPromises()

    // Scores: vuln 75, pentest 100, architecture 100, hibernate 100, github 100 => mean 95
    expect(wrapper.text()).toContain('95')
    expect(wrapper.text()).toContain('5 of 5 scanners scored')
  })

  it('shows a connect button for GitHub and excludes it from the score until authenticated', async () => {
    stubFetch({
      'api/github/refresh': githubReport({connected: false, authenticated: false})
    })
    const wrapper = mountOverview({
      panels: [
        {id: 'vulnerabilities', available: false},
        {id: 'pentest', available: false},
        {id: 'architecture', available: false},
        {id: 'hibernate-advisor', available: false},
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
})
