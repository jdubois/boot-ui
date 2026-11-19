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
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

describe.each([
  ['hibernate', Hibernate],
  ['database-advisor', DatabaseAdvisor]
])('%s hidden loaded pages', (path, component) => {
  it.each(['collapse', 'filter'])(
    'does not refresh hidden A after %s and a B dismissal, nor when A is revealed or exported',
    async (hide) => {
      document.cookie = 'XSRF-TOKEN=fixture; path=/'
      const rules = ['A', 'B'].map((id) => ({
        id,
        name: `Rule ${id}`,
        category: 'TEST',
        severity: 'HIGH',
        status: 'VIOLATION',
        dismissed: false,
        violationCount: 3,
        suppressedCount: 0,
        sampleViolations: [`cached ${id}`]
      }))
      const report = {
        scan: {status: 'SCANNED', scannedAt: 1700000000000},
        evidence: {usable: true, coverageComplete: true, limitations: []},
        violationsFound: 2,
        rulesEvaluated: 2,
        results: rules,
        severityCounts: [{severity: 'HIGH', count: 6}],
        suppression: {suppressedFindings: 0, omitted: 0, incompleteRules: []},
        violationDetails: {scanId: 'scan-1', total: 6, retained: 6, retentionLimit: 100, truncated: false}
      }
      let dismissed = false
      const pageReads = []
      const mutations = []
      vi.stubGlobal(
        'fetch',
        vi.fn((url, options = {}) => {
          let body = report
          if (url === 'api/dismissed-findings') {
            mutations.push([options.method, JSON.parse(options.body)])
            dismissed = true
            rules[1] = {...rules[1], violationCount: 2, suppressedCount: 1}
            report.suppression.suppressedFindings = 1
            report.severityCounts[0].count = 5
            body = {}
          } else if (String(url).includes('/violations?')) {
            const id = String(url).includes('/rules/A/') ? 'A' : 'B'
            pageReads.push(id)
            body = {
              scanId: 'scan-1',
              ruleId: id,
              violationCount: 3,
              retainedCount: 3,
              suppressedCount: id === 'B' && dismissed ? 1 : 0,
              suppressed: id === 'B' && dismissed ? [true, false, false] : [],
              identities: [1, 2, 3].map((n) => `v1:${id}${n}`),
              violations: [1, 2, 3].map((n) => `loaded ${id}${n}`),
              truncated: false,
              page: {offset: 0, limit: 100, returned: 3, total: 3, matched: 3, hasMore: false}
            }
          } else {
            expect(url).toBe(`api/${path}`)
            expect(options.method || 'GET').toBe('GET')
          }
          return Promise.resolve(new Response(JSON.stringify(body), {status: 200}))
        })
      )
      const router = createRouter({
        history: createMemoryHistory(),
        routes: [{path: `/${path}`, component: {template: '<div />'}}]
      })
      routers.push(router)
      await router.push({path: `/${path}`, query: {rule: 'A'}})
      await router.isReady()
      const wrapper = mount(component, {attachTo: document.body, global: {plugins: [router]}})
      wrappers.push(wrapper)
      await flushPromises()
      const row = (id) => wrapper.get(`.advisor-rule-result[data-rule-id="${id}"]`)
      await row('A').get('button[aria-label="View violations for A"]').trigger('click')
      await flushPromises()
      if (hide === 'collapse') await row('A').get('.advisor-rule-toggle').trigger('click')
      else await wrapper.get('.advisor-rule-results input').setValue('Rule B')
      await row('B').get('.advisor-rule-toggle').trigger('click')
      await flushPromises()
      await row('B').get('button[aria-label="View violations for B"]').trigger('click')
      await flushPromises()
      expect(row('A').find('.advisor-rule-violations').isVisible()).toBe(false)
      const panel = wrapper.getComponent(AdvisorRuleResults).props('panel')
      expect(await panel.dismissFinding(panel.report.results[1], 'v1:B1')).toBe(true)
      await flushPromises()
      expect(mutations).toEqual([['POST', {ruleId: 'B', identity: 'v1:B1'}]])
      expect(pageReads).toEqual(['A', 'B', 'B'])
      expect(row('B').findAll('[data-testid="suppressed-finding"]')).toHaveLength(1)
      await router.push({path: `/${path}`, query: {rule: 'A'}})
      await flushPromises()
      expect(row('A').text()).toContain('loaded A3')
      expect(row('A').text()).toContain('Dismissal state may be outdated')
      expect(
        row('A')
          .findAll('.advisor-finding-action')
          .every((button) => button.element.disabled)
      ).toBe(true)
      await row('A').get('button[aria-label="Copy A for AI"]').trigger('click')
      expect(wrapper.get('textarea').element.value).toContain('Dismissal state may be outdated')
      await wrapper.get('.ai-export-back').trigger('click')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Copy visible findings')
        .trigger('click')
      expect(wrapper.get('textarea').element.value).toContain('Dismissal state may be outdated')
      await wrapper.get('.ai-export-back').trigger('click')
      expect(pageReads).toEqual(['A', 'B', 'B'])
      await row('A').get('button[aria-label="Refresh dismissal state for A"]').trigger('click')
      await flushPromises()
      expect(pageReads).toEqual(['A', 'B', 'B', 'A'])
      expect(row('A').text()).not.toContain('Dismissal state may be outdated')
      expect(
        row('A')
          .findAll('.advisor-finding-action')
          .every((button) => !button.element.disabled)
      ).toBe(true)
    }
  )
})
