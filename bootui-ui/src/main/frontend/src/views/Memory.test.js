import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Memory from './Memory.vue'
import MemoryOffloadButton from './components/MemoryOffloadButton.vue'
import PanelHeader from './components/PanelHeader.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Threads',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/memory-check'
  }
}

function advisorReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    evidence: {usable: true, coverageComplete: true, limitations: []},
    disclaimer: 'Memory disclaimer.',
    rulesEvaluated: 22,
    violationsFound,
    summary: {
      heapUsedBytes: 536_870_912,
      heapMaxBytes: 2_147_483_648,
      heapUsedPercent: 25,
      liveThreads: 30,
      peakThreads: 35,
      deadlockDetected: false,
      loadedClasses: 9000,
      histogramAvailable: false
    },
    severityCounts: [
      {severity: 'CRITICAL', count: severityCount(results, 'CRITICAL')},
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI Memory Advisor',
      status: 'SCANNED',
      message: 'Memory Advisor completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 22,
      violationsFound
    },
    results
  }
}

function severityCount(results, severity) {
  return results
    .filter((result) => result.status === 'VIOLATION' && result.severity === severity)
    .reduce((total, result) => total + result.violationCount, 0)
}

async function mountWithReport(report) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(report), {status: 200})))
  )

  const wrapper = mount(Memory, {
    global: {stubs: {RouterLink: {props: ['to'], template: '<a :href="JSON.stringify(to)"><slot /></a>'}}}
  })
  await flushPromises()
  return wrapper
}

describe('Memory', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each([true, false])(
    'keeps score 91 and forwards scan notes independently of scan status (%s)',
    async (incomplete) => {
      const report = advisorReport([ruleResult('MEM-1', 'Retained finding', 'MEDIUM', 'VIOLATION', 3)])
      report.evidence.coverageComplete = !incomplete
      report.evidence.limitations = incomplete ? ['Metadata unavailable.'] : []
      const wrapper = await mountWithReport(report)

      expect(wrapper.get('.advisor-summary__value').text()).toBe('91')
      expect(wrapper.get('.advisor-summary__metric--status .badge').text()).toBe('Results available')
      expect(wrapper.find('.advisor-summary__assessment').exists()).toBe(false)
      expect(wrapper.find('details.advisor-summary__notes').exists()).toBe(incomplete)
      if (incomplete) {
        expect(wrapper.get('details.advisor-summary__notes').element.open).toBe(false)
        expect(wrapper.get('details.advisor-summary__notes p').text()).toContain('Metadata unavailable.')
      }
    }
  )

  it('shows only advisor findings sorted by importance with CRITICAL first', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('MEM-CONTENT-001', 'Informational big objects', 'INFO', 'VIOLATION', 2),
        ruleResult('MEM-HEAP-002', 'Passing old gen rule', 'MEDIUM', 'PASS'),
        ruleResult('MEM-THREAD-002', 'Medium blocked finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('MEM-THREAD-001', 'Deadlock detected', 'CRITICAL', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Results available')
    expect(wrapper.text()).toContain('3 findings, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('2 observations found for this rule.')
    expect(wrapper.text()).toContain('Learn more')
    expect(wrapper.text()).toContain('Runtime snapshot')
    expect(wrapper.text()).not.toContain('Passing old gen rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'Deadlock detected',
      'Medium blocked finding',
      'Informational big objects'
    ])
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('MEM-HEAP-002', 'Passing old gen rule', 'MEDIUM', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No Memory Advisor findings')
    expect(wrapper.text()).not.toContain('Passing old gen rule')
  })

  it('links the snapshot to the run-wide heap trend in Runtime Insights', async () => {
    const wrapper = await mountWithReport(advisorReport([]))
    const link = wrapper.findAll('a').find((anchor) => anchor.text() === 'Runtime Insights')
    expect(JSON.parse(link.attributes('href'))).toEqual({path: '/runtime-insights', query: {theme: 'memory', all: '1'}})
  })

  it('counts the garbage collection and heap rows Runtime Insights leaves out of its default list', async () => {
    const insights = {
      available: true,
      observations: [
        {id: 'heap-growth-after-gc:heap', kind: 'heap-growth-after-gc', status: 'OBSERVED', listed: false},
        {id: 'gc-inflated-latency:a', kind: 'gc-inflated-latency', status: 'INSUFFICIENT', listed: false},
        {id: 'repeated-selects:a', kind: 'repeated-selects', status: 'OBSERVED', listed: true}
      ]
    }
    const report = advisorReport([])
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          new Response(JSON.stringify(String(url).includes('runtime-insights') ? insights : report), {status: 200})
        )
      )
    )
    const wrapper = mount(Memory, {
      global: {stubs: {RouterLink: {props: ['to'], template: '<a :href="JSON.stringify(to)"><slot /></a>'}}}
    })
    await flushPromises()

    expect(wrapper.get('.memory-insights-count').text()).toBe('2 rows, 1 observed')
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('shows no count when Runtime Insights is not available', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(JSON.stringify(advisorReport([])), {status: 200})))
    )
    const wrapper = mount(Memory, {
      global: {
        provide: {panels: {value: {panels: [{id: 'runtime-insights', available: false}]}}},
        stubs: {RouterLink: {props: ['to'], template: '<a :href="JSON.stringify(to)"><slot /></a>'}}
      }
    })
    await flushPromises()

    expect(wrapper.find('.memory-insights-count').exists()).toBe(false)
    expect(fetch.mock.calls.map(([url]) => String(url))).not.toContain('api/runtime-insights')
  })

  it('offers the shared BootUI memory offload next to the scan action', async () => {
    const wrapper = await mountWithReport(advisorReport([]))

    const offload = wrapper.getComponent(PanelHeader).getComponent(MemoryOffloadButton)
    expect(offload.props('followUp')).toContain('Run memory checks again')
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
})
