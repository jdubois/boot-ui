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

function advisorReport(results, overrides = {}) {
  const violationsFound = overrides.violationsFound ?? results.filter((result) => result.status === 'VIOLATION').length
  return {
    localOnly: true,
    disclaimer: 'Database Advisor disclaimer.',
    dataSourceNames: ['default'],
    dataSources: [
      {
        name: 'default',
        product: 'PostgreSQL 16.1',
        dialect: 'PostgreSQL',
        identifierCase: 'LOWER',
        status: 'AVAILABLE',
        message: null,
        tablesAnalyzed: 5,
        truncated: false
      }
    ],
    tablesAnalyzed: 5,
    rulesEvaluated: 8,
    violationsFound,
    rulesSkipped: 0,
    rulesErrored: 0,
    assessmentEvidence: {usable: true, incomplete: false},
    truncated: false,
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
      tablesAnalyzed: 5,
      violationsFound
    },
    results,
    diagnostics: [],
    ...overrides
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

  const wrapper = mount(DatabaseAdvisor)
  await flushPromises()
  return wrapper
}

describe('DatabaseAdvisor', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders the datasources card with product and read status', async () => {
    const wrapper = await mountWithReport(advisorReport([]))

    expect(wrapper.text()).toContain('Datasources')
    expect(wrapper.text()).toContain('default')
    expect(wrapper.text()).toContain('PostgreSQL 16.1')
    expect(wrapper.text()).toContain('lower-case identifiers')
    expect(wrapper.text()).toContain('Read')
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

  it('scores every concrete finding rather than each violated rule', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('DB-SCHEMA-002', 'Foreign key columns without a supporting index', 'HIGH', 'VIOLATION', 8)
      ])
    )

    expect(wrapper.find('.advisor-summary__value').text()).toBe('20')
    expect(wrapper.text()).toContain('At risk')
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('DB-SCHEMA-001', 'Passing primary key rule', 'MEDIUM', 'PASS')])
    )

    expect(wrapper.text()).toContain('No Database findings')
    expect(wrapper.text()).not.toContain('Passing primary key rule')
  })

  it('reports an unreadable datasource without counting it as a finding', async () => {
    const wrapper = await mountWithReport(
      advisorReport([], {
        dataSourceNames: ['primary', 'reporting'],
        dataSources: [
          {
            name: 'primary',
            product: 'PostgreSQL 16.1',
            dialect: 'PostgreSQL',
            status: 'AVAILABLE',
            message: null,
            tablesAnalyzed: 5,
            truncated: false
          },
          {
            name: 'reporting',
            product: null,
            dialect: 'Generic JDBC',
            status: 'FAILED',
            message: 'connection refused',
            tablesAnalyzed: 0,
            truncated: false
          }
        ],
        scan: {
          analyzer: 'BootUI Database Advisor',
          status: 'PARTIAL',
          message: 'Database Advisor completed. 1 datasource(s) could not be read.',
          scannedAt: 1_700_000_000_000,
          rulesEvaluated: 8,
          tablesAnalyzed: 5,
          violationsFound: 0
        },
        diagnostics: [{source: 'reporting', level: 'ERROR', message: 'connection refused'}]
      })
    )

    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.find('.advisor-summary__value').text()).toBe('100')
    expect(wrapper.text()).toContain('Partial assessment')
    expect(wrapper.text()).toContain('missing checks are not passes')
    expect(wrapper.text()).toContain('1 datasource could not be read')
    expect(wrapper.text()).toContain('Unreadable')
    expect(wrapper.text()).toContain('No findings in the available results')
    expect(wrapper.text()).not.toContain('No Database findings')
  })

  it('surfaces truncation and skipped/errored rules as diagnostics on demand', async () => {
    const wrapper = await mountWithReport(
      advisorReport([], {
        truncated: true,
        rulesSkipped: 3,
        rulesErrored: 1,
        dataSources: [
          {
            name: 'default',
            product: 'MySQL 8.4',
            dialect: 'MySQL',
            status: 'PARTIAL',
            message: 'Only the first 300 tables were analyzed.',
            tablesAnalyzed: 300,
            truncated: true
          }
        ],
        diagnostics: [
          {source: 'default', level: 'WARNING', message: 'Only the first 300 tables were analyzed.'},
          {source: 'DB-PG-001', level: 'INFO', message: 'No PostgreSQL datasource was detected.'},
          {source: 'DB-HIB-003', level: 'WARNING', message: 'Rule could not be evaluated: boom'}
        ]
      })
    )

    expect(wrapper.text()).toContain('Truncated')
    expect(wrapper.text()).toContain('a scan bound was reached')
    expect(wrapper.text()).toContain('1 rule failed to evaluate')
    expect(wrapper.text()).toContain('Scan diagnostics')
    expect(wrapper.text()).toContain('3 notes')
    expect(wrapper.text()).toContain('Rules not run')
    expect(wrapper.text()).not.toContain('No PostgreSQL datasource was detected.')

    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Show diagnostics')
      .trigger('click')

    expect(wrapper.text()).toContain('No PostgreSQL datasource was detected.')
    expect(wrapper.text()).toContain('Rule could not be evaluated: boom')
  })
})
