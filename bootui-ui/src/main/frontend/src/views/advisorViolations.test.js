import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import Architecture from './Architecture.vue'
import Hibernate from './Hibernate.vue'
import Spring from './Spring.vue'
import RestApi from './RestApi.vue'
import Memory from './Memory.vue'
import Security from './Security.vue'
import DatabaseAdvisor from './DatabaseAdvisor.vue'

vi.mock('vue-router', () => ({useRoute: () => ({query: {}})}))

const cases = [
  ['architecture', Architecture],
  ['hibernate', Hibernate],
  ['spring', Spring],
  ['rest-api', RestApi],
  ['memory', Memory],
  ['security', Security],
  ['database-advisor', DatabaseAdvisor]
]
const findings = Array.from({length: 29}, (_, index) => `example.Service${index}`)
const rule = {
  id: 'RULE-1',
  name: 'A finding',
  status: 'VIOLATION',
  severity: 'HIGH',
  category: 'TEST',
  violationCount: 29,
  sampleViolations: findings.slice(0, 10),
  recommendation: 'Review this finding.'
}
function report(scanId = 'scan-1') {
  return {
    scan: {status: 'SCANNED'},
    evidence: {usable: true, coverageComplete: true, limitations: []},
    severityCounts: [{severity: 'HIGH', count: 29}],
    rulesEvaluated: 1,
    violationsFound: 1,
    violationDetails: {scanId, total: 29, retained: 29, retentionLimit: 10000, truncated: false},
    basePackages: [],
    entityPackages: [],
    inspected: [],
    results: [rule]
  }
}
function response(body, status = 200) {
  return new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}})
}
const detailPage = {
  scanId: 'scan-1',
  ruleId: 'RULE-1',
  violationCount: 29,
  retainedCount: 29,
  truncated: false,
  violations: findings,
  page: {total: 29, matched: 29, offset: 0, limit: 100, returned: 29, hasMore: false}
}
const wrappers = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('advisor detail integration', () => {
  it.each(cases)('%s only reads detail on demand, including in read-only mode', async (id, view) => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          response(
            url.includes('/violations?')
              ? detailPage
              : url.includes('/error-contract?')
                ? {available: true, entries: [], total: 0}
                : report()
          )
        )
      )
    )
    const wrapper = mount(view, {props: {panel: {id, readOnly: true}}})
    wrappers.push(wrapper)
    await flushPromises()
    // REST API also reads its error contract; Memory also counts its Runtime Insights rows (docs/PLAN-v2.md M4-19).
    expect(fetch).toHaveBeenCalledTimes(id === 'rest-api' || id === 'memory' ? 2 : 1)
    expect(fetch.mock.calls[0][0]).toBe(`api/${id}`)
    if (id === 'memory') expect(fetch.mock.calls[1][0]).toBe('api/runtime-insights')
    const detail = wrapper.get('.advisor-rule-violations')
    expect(detail.findAll('li')).toHaveLength(10)
    expect(wrapper.get('.btn-primary').attributes('disabled')).toBeDefined()
    const control = detail.get('button')
    expect(control.attributes('disabled')).toBeUndefined()
    await control.trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.at(-1)[0]).toBe(`api/${id}/rules/RULE-1/violations?scanId=scan-1&offset=0&limit=100`)
    expect(detail.findAll('li').map((item) => item.text())).toEqual(findings)
    expect(detail.findAll('[role="status"], [role="alert"], [aria-live]')).toHaveLength(1)
  })

  it('refreshes only the cached report after a stale detail response', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(response(report()))
        .mockResolvedValueOnce(response({}, 409))
        .mockResolvedValueOnce(response(report('scan-2')))
    )
    const wrapper = mount(Architecture)
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('[aria-label="View violations for RULE-1"]').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)
    await wrapper.get('[aria-label="Refresh cached report for RULE-1"]').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      'api/architecture',
      'api/architecture/rules/RULE-1/violations?scanId=scan-1&offset=0&limit=100',
      'api/architecture'
    ])
    expect(wrapper.get('.advisor-rule-violations').findAll('li')).toHaveLength(10)
    expect(wrapper.find('[aria-label^="Refresh cached report"]').exists()).toBe(false)
  })

  it('does not wire analysis errors or dismissed summaries as active findings', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response({
          ...report(),
          results: [rule, {...rule, id: 'DISMISSED', dismissed: true}],
          analysisErrors: [{...rule, id: 'ERROR', status: 'ERROR', sampleViolations: ['Analysis failed']}]
        })
      )
    )
    const wrapper = mount(Architecture)
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.findAll('.advisor-rule-violations')).toHaveLength(1)
    expect(wrapper.text()).toContain('Analysis failed')
    expect(wrapper.text()).toContain('Dismissed rules (1)')
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('keeps another rule’s accepted page across dismissal/restore without another detail read', async () => {
    let dismissed = false
    vi.stubGlobal(
      'fetch',
      vi.fn((url, options = {}) => {
        if (url === 'api/dismissed-rules/RULE-2') dismissed = options.method === 'POST'
        return Promise.resolve(
          response(
            url.includes('/violations?')
              ? detailPage
              : {
                  ...report(),
                  results: [rule, {...rule, id: 'RULE-2', name: 'Another finding', dismissed}]
                }
          )
        )
      })
    )
    const wrapper = mount(Architecture)
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('[aria-label="View violations for RULE-1"]').trigger('click')
    await flushPromises()
    const secondRule = () => wrapper.findAll('.list-group-item').find((item) => item.text().includes('RULE-2'))
    await secondRule().get('button[title="Dismiss this rule"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.advisor-rule-violations')).toHaveLength(1)
    expect(wrapper.get('.advisor-rule-violations').findAll('li')).toHaveLength(29)
    await secondRule().get('button[title="Restore this rule"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.advisor-rule-violations')).toHaveLength(2)
    expect(wrapper.findAll('.advisor-rule-violations')[0].findAll('li')).toHaveLength(29)
    expect(fetch.mock.calls.filter(([url]) => url.includes('/violations?'))).toHaveLength(1)
  })
})
