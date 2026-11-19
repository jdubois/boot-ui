import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {createMemoryHistory, createRouter} from 'vue-router'
import Hibernate from './Hibernate.vue'
import DatabaseAdvisor from './DatabaseAdvisor.vue'
import AdvisorRuleResults from './components/AdvisorRuleResults.vue'

const wrappers = []
const routers = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  routers.splice(0).forEach((router) => router.options.history.destroy())
  vi.unstubAllGlobals()
})

describe.each([
  ['hibernate', Hibernate],
  ['database-advisor', DatabaseAdvisor]
])('%s suppressed evidence export', (path, component) => {
  it('shares the explicitly loaded all-dismissed page with both export actions and cleans up old evidence', async () => {
    const rule = {
      id: 'DISMISSED-FINDINGS',
      name: 'Retained rule',
      category: 'TEST',
      severity: 'HIGH',
      status: 'VIOLATION',
      dismissed: false,
      violationCount: 0,
      suppressedCount: 2,
      description: 'Captured description',
      recommendation: 'Review locally',
      sampleViolations: [],
      coverageNote: 'Partial unit coverage ******'
    }
    let report = {
      scan: {status: 'SCANNED', scannedAt: 1700000000000},
      evidence: {usable: true, coverageComplete: false, limitations: ['Partial assessment']},
      violationsFound: 0,
      rulesEvaluated: 1,
      results: [rule],
      severityCounts: [{severity: 'HIGH', count: 0}],
      suppression: {suppressedFindings: 2, omitted: 0, incompleteRules: []},
      violationDetails: {scanId: 'scan-1', total: 2, retained: 2, retentionLimit: 100, truncated: false}
    }
    let page = {
      scanId: 'scan-1',
      ruleId: rule.id,
      violationCount: 2,
      suppressedCount: 2,
      retainedCount: 2,
      truncated: false,
      violations: ['loaded dismissed ******', 'second dismissed ******'],
      locations: [],
      identities: ['v1:one', 'v1:two'],
      suppressed: [true, true],
      page: {offset: 0, returned: 2, limit: 100, hasMore: false}
    }
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', {...navigator, clipboard: {writeText}})
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          new Response(JSON.stringify(String(url).includes('/violations?') ? page : report), {status: 200})
        )
      )
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{path: `/${path}`, component: {template: '<div />'}}]
    })
    routers.push(router)
    await router.push({path: `/${path}`, query: {rule: rule.id}})
    await router.isReady()
    const wrapper = mount(component, {attachTo: document.body, global: {plugins: [router]}})
    wrappers.push(wrapper)
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(1)
    await wrapper.get(`button[aria-label="View violations for ${rule.id}"]`).trigger('click')
    await flushPromises()
    const reads = [...fetch.mock.calls]
    expect(reads).toHaveLength(2)
    const exportRule = async (label, containsPage, active = 0) => {
      const copy =
        label === 'Copy visible findings'
          ? wrapper.findAll('button').find((button) => button.text() === label)
          : wrapper.get(`button[aria-label="${label}"]`)
      await copy.trigger('click')
      const preview = wrapper.get('textarea').element.value
      expect(preview.includes('loaded dismissed ******')).toBe(containsPage)
      expect(preview).toContain(`Active findings: ${active}`)
      expect(preview).toContain('pre-dismissal rule findings: 2')
      expect(preview).toContain('Coverage note: Partial unit coverage ******')
      if (containsPage) {
        expect(preview).toContain('current loaded page, offset 0, returned 2, limit 100')
        expect(preview).toContain('Finding 1 — dismissed, not counted in score')
      } else {
        expect(preview).toContain('cached report samples only')
      }
      await wrapper.get('.ai-export-copy').trigger('click')
      expect(writeText).toHaveBeenLastCalledWith(preview)
      await wrapper.get('.ai-export-back').trigger('click')
    }
    await wrapper.get('.advisor-rule-results input').setValue('no matching active rule')
    await exportRule(`Copy ${rule.id} for AI`, true)
    await exportRule('Copy visible findings', true)
    expect(fetch.mock.calls).toEqual(reads)
    await wrapper.get(`button[aria-label="Back to samples for ${rule.id}"]`).trigger('click')
    await exportRule('Copy visible findings', false)
    expect(fetch.mock.calls).toEqual(reads)
    await wrapper.get(`button[aria-label="View violations for ${rule.id}"]`).trigger('click')
    await flushPromises()
    report = {...report, violationDetails: {...report.violationDetails, scanId: 'scan-2'}}
    await wrapper.getComponent(AdvisorRuleResults).props('panel').loadReport()
    await flushPromises()
    const afterRefresh = [...fetch.mock.calls]
    await exportRule('Copy visible findings', false)
    expect(fetch.mock.calls).toEqual(afterRefresh)
    // A cached report can move a rule back to the active list after an explicit restore elsewhere.
    // The old suppressed component must neither leak its page nor erase its replacement's evidence.
    report = {
      ...report,
      violationsFound: 1,
      results: [
        {
          ...rule,
          violationCount: 1,
          suppressedCount: 1,
          sampleViolations: ['new active sample ******']
        }
      ],
      suppression: {...report.suppression, suppressedFindings: 1}
    }
    await wrapper.getComponent(AdvisorRuleResults).props('panel').loadReport()
    await flushPromises()
    await exportRule('Copy visible findings', false, 1)
    page = {...page, scanId: 'scan-2', suppressedCount: 1, suppressed: [true, false]}
    await wrapper.get(`button[aria-label="View violations for ${rule.id}"]`).trigger('click')
    await flushPromises()
    const afterMove = [...fetch.mock.calls]
    await exportRule('Copy visible findings', true, 1)
    expect(fetch.mock.calls).toEqual(afterMove)
    expect(fetch.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
    expect(fetch.mock.calls.filter(([url]) => String(url).includes('/violations?'))).toHaveLength(3)
  })
})
