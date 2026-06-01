import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Architecture from './Architecture.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Coding practices',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`
  }
}

function architectureReport(
  results,
  violationsFound = results.filter((result) => result.status === 'VIOLATION').length
) {
  return {
    localOnly: true,
    disclaimer: 'Architecture disclaimer.',
    basePackages: ['com.example'],
    classesAnalyzed: 12,
    rulesEvaluated: 15,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI ArchUnit hygiene',
      status: 'SCANNED',
      message: 'Architecture rules completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 15,
      classesAnalyzed: 12,
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

  const wrapper = mount(Architecture)
  await flushPromises()
  return wrapper
}

describe('Architecture', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows only violation results sorted by importance', async () => {
    const wrapper = await mountWithReport(
      architectureReport([
        ruleResult('ARCH-CODE-005', 'Low severity violation', 'LOW', 'VIOLATION', 1),
        ruleResult('ARCH-CODE-004', 'Passing informational rule', 'INFO', 'PASS'),
        ruleResult('ARCH-SPRING-001', 'Medium severity violation', 'MEDIUM', 'VIOLATION', 3),
        ruleResult('ARCH-SPRING-004', 'High severity violation', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 violating rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('3 violations found for this rule.')
    expect(wrapper.text()).not.toContain('Passing informational rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity violation',
      'Medium severity violation',
      'Low severity violation'
    ])
  })

  it('shows an empty violation state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      architectureReport([ruleResult('ARCH-CODE-004', 'Passing informational rule', 'INFO', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No architecture rule violations found')
    expect(wrapper.text()).not.toContain('Passing informational rule')
  })
})
