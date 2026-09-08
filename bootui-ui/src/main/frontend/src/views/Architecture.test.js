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
    evidence: {usable: true, coverageComplete: true, limitations: []},
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
  return results
    .filter((result) => result.status === 'VIOLATION' && result.severity === severity)
    .reduce((total, result) => total + result.violationCount, 0)
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
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
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

    expect(wrapper.text()).toContain('Results available')
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

  it.each([true, false])(
    'keeps score 91 and forwards scan notes independently of scan status (%s)',
    async (incomplete) => {
      const report = architectureReport([ruleResult('ARCH-1', 'Retained finding', 'MEDIUM', 'VIOLATION', 3)])
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

  it('keeps the last report and shows a warning when another scan is active', async () => {
    const existing = architectureReport([ruleResult('ARCH-SPRING-004', 'Existing finding', 'HIGH', 'VIOLATION', 1)])
    const busy = {
      error: 'BootUI action already in progress',
      operation: 'architecture.scan',
      activeOperation: 'architecture.scan',
      message: "Operation 'architecture.scan' cannot start while 'architecture.scan' is in progress."
    }
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(JSON.stringify(existing), {status: 200}))
        .mockResolvedValueOnce(
          new Response(JSON.stringify(busy), {status: 409, headers: {'Content-Type': 'application/json'}})
        )
    )
    const wrapper = mount(Architecture)
    await flushPromises()

    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Existing finding')
    expect(wrapper.text()).toContain(busy.message)
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('Unable to run architecture checks')
  })
})
