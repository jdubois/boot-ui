import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import MemoryAdvisor from './MemoryAdvisor.vue'

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
    disclaimer: 'Memory disclaimer.',
    rulesEvaluated: 16,
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
      rulesEvaluated: 16,
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

  const wrapper = mount(MemoryAdvisor)
  await flushPromises()
  return wrapper
}

describe('MemoryAdvisor', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows only advisor findings sorted by importance with CRITICAL first', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('MEM-CONTENT-001', 'Informational big objects', 'INFO', 'VIOLATION', 2),
        ruleResult('MEM-HEAP-002', 'Passing old gen rule', 'MEDIUM', 'PASS'),
        ruleResult('MEM-THREAD-002', 'Medium blocked finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('MEM-THREAD-001', 'Deadlock detected', 'CRITICAL', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
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
})
