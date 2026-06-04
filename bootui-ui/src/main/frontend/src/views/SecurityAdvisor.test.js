import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SecurityAdvisor from './SecurityAdvisor.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Authorization',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/security-check'
  }
}

function advisorReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    disclaimer: 'Security disclaimer.',
    filterChains: ['any request'],
    filterChainsAnalyzed: 1,
    rulesEvaluated: 37,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI Spring Security Advisor',
      status: 'SCANNED',
      message: 'Security Advisor completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 37,
      filterChainsAnalyzed: 1,
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

  const wrapper = mount(SecurityAdvisor)
  await flushPromises()
  return wrapper
}

describe('SecurityAdvisor', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows only advisor findings sorted by importance', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('SEC-AUTHZ-004', 'Informational ordering finding', 'INFO', 'VIOLATION', 2),
        ruleResult('SEC-HEAD-003', 'Passing header rule', 'LOW', 'PASS'),
        ruleResult('SEC-CSRF-002', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('SEC-AUTHZ-002', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 violating rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('2 findings found for this rule.')
    expect(wrapper.text()).toContain('Learn more')
    expect(wrapper.text()).toContain('Filter chains analysed')
    expect(wrapper.text()).not.toContain('Passing header rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Informational ordering finding'
    ])
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('SEC-HEAD-003', 'Passing header rule', 'LOW', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No Security Advisor findings')
    expect(wrapper.text()).not.toContain('Passing header rule')
  })
})
