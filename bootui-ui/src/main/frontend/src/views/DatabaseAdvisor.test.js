import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import DatabaseAdvisor from './DatabaseAdvisor.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Schema',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/database-advisor-check'
  }
}

function advisorReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    disclaimer: 'Database Advisor disclaimer.',
    dataSourceNames: ['default'],
    tablesAnalyzed: 5,
    rulesEvaluated: 8,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI Database Advisor',
      status: 'SCANNED',
      message: 'Database Advisor completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 8,
      entitiesAnalyzed: 5,
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

  const wrapper = mount(DatabaseAdvisor)
  await flushPromises()
  return wrapper
}

describe('DatabaseAdvisor', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders the datasources card', async () => {
    const wrapper = await mountWithReport(advisorReport([]))

    expect(wrapper.text()).toContain('Datasources')
    expect(wrapper.text()).toContain('default')
  })

  it('shows only advisor findings sorted by importance', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('DB-SCHEMA-003', 'Informational duplicate index', 'INFO', 'VIOLATION', 2),
        ruleResult('DB-SCHEMA-001', 'Passing primary key rule', 'MEDIUM', 'PASS'),
        ruleResult('DB-HIB-002', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('DB-SCHEMA-002', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 violating rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('2 findings found for this rule.')
    expect(wrapper.text()).toContain('Learn more')
    expect(wrapper.text()).not.toContain('Passing primary key rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Informational duplicate index'
    ])
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('DB-SCHEMA-001', 'Passing primary key rule', 'MEDIUM', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No Database Advisor findings')
    expect(wrapper.text()).not.toContain('Passing primary key rule')
  })
})
