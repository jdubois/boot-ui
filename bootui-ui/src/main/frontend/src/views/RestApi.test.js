import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RestApi from './RestApi.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Routing & HTTP method mapping',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/learn'
  }
}

function restApiReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    disclaimer: 'REST API disclaimer.',
    basePackages: ['com.example'],
    controllersAnalyzed: 4,
    handlersAnalyzed: 9,
    rulesEvaluated: 30,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI REST API Advisor',
      status: 'SCANNED',
      message: 'REST API rules completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 30,
      controllersAnalyzed: 4,
      handlersAnalyzed: 9,
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

  const wrapper = mount(RestApi)
  await flushPromises()
  return wrapper
}

describe('RestApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows only violation results sorted by importance', async () => {
    const wrapper = await mountWithReport(
      restApiReport([
        ruleResult('RAPI-MAP-005', 'Low severity finding', 'LOW', 'VIOLATION', 1),
        ruleResult('RAPI-DOC-001', 'Passing informational rule', 'INFO', 'PASS'),
        ruleResult('RAPI-RESP-001', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 3),
        ruleResult('RAPI-DTO-001', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 flagged rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('3 findings found for this rule.')
    expect(wrapper.text()).not.toContain('Passing informational rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Low severity finding'
    ])
    expect(wrapper.find('a[href="https://example.com/learn"]').exists()).toBe(true)
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      restApiReport([ruleResult('RAPI-DOC-001', 'Passing informational rule', 'INFO', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No REST API rule findings')
    expect(wrapper.text()).not.toContain('Passing informational rule')
  })
})
