import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import Spring from './Spring.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Bean wiring',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/spring-check'
  }
}

function advisorReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    disclaimer: 'Spring disclaimer.',
    inspected: ['Active profiles: none', 'Bean definitions: 120'],
    componentsAnalyzed: 120,
    rulesEvaluated: 16,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI Spring Advisor',
      status: 'SCANNED',
      message: 'Spring Advisor completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 16,
      componentsAnalyzed: 120,
      violationsFound
    },
    results
  }
}

function severityCount(results, severity) {
  return results.filter((result) => result.status === 'VIOLATION' && result.severity === severity).length
}

async function mountWithReport(report) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(report), {status: 200})))
  )

  const wrapper = mount(Spring)
  await flushPromises()
  return wrapper
}

describe('Spring', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows only advisor findings sorted by importance', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('SPRING-WEB-003', 'Informational ordering finding', 'INFO', 'VIOLATION', 2),
        ruleResult('SPRING-CONFIG-001', 'Passing config rule', 'LOW', 'PASS'),
        ruleResult('SPRING-WEB-002', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('SPRING-WIRING-001', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 violating rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('2 findings found for this rule.')
    expect(wrapper.text()).toContain('Learn more')
    expect(wrapper.text()).toContain('Beans analysed')
    expect(wrapper.text()).not.toContain('Passing config rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Informational ordering finding'
    ])
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('SPRING-CONFIG-001', 'Passing config rule', 'LOW', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No Spring Advisor findings')
    expect(wrapper.text()).not.toContain('Passing config rule')
  })

  it('shows the advisor score and raises it when a finding is dismissed', async () => {
    const dismissed = new Set()
    const baseResults = [
      ruleResult('SPRING-WIRING-001', 'High severity finding', 'HIGH', 'VIOLATION', 1),
      ruleResult('SPRING-WEB-002', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 1)
    ]

    function currentReport() {
      const results = baseResults.map((result) => ({...result, dismissed: dismissed.has(result.id)}))
      const active = results.filter((result) => result.status === 'VIOLATION' && !result.dismissed)
      const count = (severity) => active.filter((result) => result.severity === severity).length
      return {
        ...advisorReport(results, active.length),
        severityCounts: [
          {severity: 'HIGH', count: count('HIGH')},
          {severity: 'MEDIUM', count: count('MEDIUM')},
          {severity: 'LOW', count: 0},
          {severity: 'INFO', count: 0}
        ],
        results
      }
    }

    vi.stubGlobal(
      'fetch',
      vi.fn((input, init) => {
        const url = typeof input === 'string' ? input : input.url
        const method = (init?.method || 'GET').toUpperCase()
        if (url.includes('api/dismissed-rules/')) {
          const id = decodeURIComponent(url.split('api/dismissed-rules/')[1])
          if (method === 'POST') dismissed.add(id)
          if (method === 'DELETE') dismissed.delete(id)
          return Promise.resolve(new Response('{}', {status: 200}))
        }
        return Promise.resolve(new Response(JSON.stringify(currentReport()), {status: 200}))
      })
    )

    const wrapper = mount(Spring)
    await flushPromises()

    // HIGH (10) + MEDIUM (3) penalty => 100 - 13 = 87.
    const scoreCard = wrapper.find('.advisor-score-card')
    expect(scoreCard.exists()).toBe(true)
    expect(scoreCard.text()).toContain('Advisor score')
    expect(scoreCard.text()).toContain('87')

    // The first dismiss button targets the highest-importance finding (HIGH).
    const dismissButton = wrapper.findAll('button').find((button) => button.text().includes('Dismiss'))
    await dismissButton.trigger('click')
    await flushPromises()

    // Removing the HIGH finding drops the penalty to 3 => 97, and the exclusion is noted.
    expect(wrapper.find('.advisor-score-card').text()).toContain('97')
    expect(wrapper.text()).toContain('1 dismissed rule(s) excluded from this score')
  })

  it('renders Quarkus advisor copy when the platform is quarkus', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(JSON.stringify(advisorReport([])), {status: 200})))
    )

    const wrapper = mount(Spring, {
      global: {provide: {panels: ref({platform: 'quarkus'})}}
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Run Quarkus checks')
    expect(wrapper.text()).toContain('Managed beans')
    expect(wrapper.text()).not.toContain('Beans analysed')
  })
})
