import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Hibernate from './Hibernate.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Fetching',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/hibernate-check'
  }
}

function advisorReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    evidence: {usable: true, coverageComplete: true, limitations: []},
    disclaimer: 'Hibernate disclaimer.',
    entityPackages: ['com.example'],
    entitiesAnalyzed: 3,
    rulesEvaluated: 9,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI Hibernate Advisor',
      status: 'SCANNED',
      message: 'Hibernate Advisor completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 9,
      entitiesAnalyzed: 3,
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

  const wrapper = mount(Hibernate)
  await flushPromises()
  return wrapper
}

describe('Hibernate', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each([true, false])(
    'keeps score 91 and forwards scan notes independently of scan status (%s)',
    async (incomplete) => {
      const report = advisorReport([ruleResult('HIB-1', 'Retained finding', 'MEDIUM', 'VIOLATION', 3)])
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

  it('lists scan diagnostics on demand and shows a partial rule coverage note', async () => {
    const partial = ruleResult('HIB-QUERY-006', 'Partly evaluated query rule', 'MEDIUM', 'VIOLATION', 8)
    partial.coverageNote = 'Partially evaluated in [default]: 3 repository query method(s) outside the readable shape.'
    const report = advisorReport([partial])
    report.scan.status = 'PARTIAL'
    report.diagnostics = [
      {
        source: 'HIB-QUERY-006',
        unit: 'default',
        level: 'WARNING',
        message: 'Partly evaluated; findings come from the evaluated part only.'
      },
      {source: 'HIB-FETCH-003', unit: 'default', level: 'ERROR', message: 'Rule evaluation failed.'}
    ]
    const wrapper = await mountWithReport(report)

    expect(wrapper.text()).toContain('Scan diagnostics')
    expect(wrapper.text()).toContain('2 notes — not counted as findings')
    expect(wrapper.text()).toContain('Coverage: ' + partial.coverageNote)
    const toggle = wrapper.findAll('button').find((button) => button.text() === 'Show diagnostics')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.text()).not.toContain('Rule evaluation failed.')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.text()).toContain('HIB-FETCH-003')
    expect(wrapper.text()).toContain('[default]')
    expect(wrapper.text()).toContain('Rule evaluation failed.')
  })

  it('hides the diagnostics card when a scan has no diagnostics', async () => {
    const wrapper = await mountWithReport({...advisorReport([]), diagnostics: []})

    expect(wrapper.text()).not.toContain('Scan diagnostics')
  })

  it('shows the Vlad Mihalcea best-practices note under the title', async () => {
    const wrapper = await mountWithReport(advisorReport([]))
    const link = wrapper.get('a[href="https://vladmihalcea.com"]')

    expect(wrapper.text()).toContain(
      'Many of those rules are best practices from Vlad Mihalcea, who reviewed the code himself - join him at'
    )
    expect(link.text()).toBe('https://vladmihalcea.com')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('shows only advisor findings sorted by importance', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('HIB-FETCH-002', 'Informational fetch finding', 'INFO', 'VIOLATION', 2),
        ruleResult('HIB-MAP-004', 'Passing mapping rule', 'MEDIUM', 'PASS'),
        ruleResult('HIB-ID-001', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('HIB-FETCH-001', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Results available')
    expect(wrapper.text()).toContain('3 violating rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('2 findings found for this rule.')
    expect(wrapper.text()).toContain('Learn more')
    expect(wrapper.text()).not.toContain('Passing mapping rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Informational fetch finding'
    ])
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('HIB-MAP-004', 'Passing mapping rule', 'MEDIUM', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No Hibernate Advisor findings')
    expect(wrapper.text()).not.toContain('Passing mapping rule')
  })
})
