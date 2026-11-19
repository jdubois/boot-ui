import {flushPromises, mount} from '@vue/test-utils'
import {h, reactive} from 'vue'
import {createMemoryHistory, createRouter, createWebHashHistory} from 'vue-router'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import AdvisorRuleResults from './AdvisorRuleResults.vue'
import AdvisorRuleViolations from './AdvisorRuleViolations.vue'
import {advisorFindingsMarkdown} from '../../utils/markdownExport.js'
import {advisorRows} from '../../utils/advisorFindings.js'

const alpha = {
  id: 'RULE /? &',
  name: 'Alpha rule',
  severity: 'HIGH',
  status: 'VIOLATION',
  category: 'TEST',
  description: 'Inspect the schema',
  recommendation: 'Review indexes',
  coverageNote: 'Partial ******\n```\n# Captured coverage',
  violationCount: 29,
  dismissed: false,
  suppressedCount: 2,
  sampleViolations: ['cached **untrusted** ``` evidence'],
  sampleLocations: [{className: 'example.Service', sourceFile: 'Service.java', line: 42}]
}
const beta = {
  ...alpha,
  id: 'RULE-B',
  name: 'Beta rule',
  severity: 'LOW',
  description: 'Different description',
  recommendation: 'Check pool configuration',
  coverageNote: 'POOL_COVERAGE_OTHER',
  violationCount: 3,
  suppressedCount: 0,
  sampleViolations: ['cached pool text'],
  sampleLocations: []
}
const details = {scanId: 'scan-1', total: 34, retained: 20, retentionLimit: 20, truncated: true}
const page = {
  scanId: 'scan-1',
  ruleId: alpha.id,
  violationCount: 31,
  retainedCount: 18,
  suppressedCount: 2,
  truncated: true,
  violations: ['loaded page evidence'],
  locations: [null],
  identities: ['v1:one'],
  suppressed: [true],
  page: {offset: 10, limit: 100, returned: 1, total: 18, matched: 18, hasMore: true}
}
const wrappers = []
const routers = []
const panels = []
const writeText = vi.fn()
function response(body, status = 200) {
  return new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}})
}
async function render({query = {}, hashRouter = false, dismissed = false} = {}) {
  if (hashRouter) window.history.replaceState(null, '', '/console/')
  const results = [
    alpha,
    beta,
    ...(dismissed ? [{...alpha, id: 'DISMISSED', name: 'Dismissed rule', dismissed: true, suppressedCount: 0}] : [])
  ]
  const panel = reactive({
    report: {
      scan: {status: 'SCANNED', scannedAt: 1700000000000},
      results,
      violationDetails: details,
      evidence: {usable: true, coverageComplete: false, limitations: ['Only sampled evidence.']},
      suppression: {suppressedFindings: 2},
      violationsFound: 2,
      rulesEvaluated: 2
    },
    visibleResults: [alpha, beta],
    actionsDisabled: false,
    dismissalRevision: 0,
    restore: vi.fn(),
    loadReport: vi.fn(),
    dismissFinding: vi.fn(),
    restoreFinding: vi.fn(),
    severityClass: () => 'text-bg-secondary'
  })
  panels.push(panel)
  const router = createRouter({
    history: hashRouter ? createWebHashHistory('/console/') : createMemoryHistory('/console/'),
    routes: [{path: '/architecture', component: {template: '<div />'}}]
  })
  routers.push(router)
  await router.push({path: '/architecture', query})
  await router.isReady()
  const wrapper = mount(AdvisorRuleResults, {
    attachTo: document.body,
    props: {panel, apiPath: 'api/architecture'},
    global: {plugins: [router]},
    slots: {
      default: ({result}) =>
        h(AdvisorRuleViolations, {
          apiPath: 'api/architecture',
          rule: result,
          details: panel.report.violationDetails,
          refreshReport: panel.loadReport,
          suppressible: true,
          disabled: panel.actionsDisabled,
          revision: panel.dismissalRevision,
          dismissFinding: panel.dismissFinding,
          restoreFinding: panel.restoreFinding
        })
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return {wrapper, panel, router}
}
function rows(wrapper) {
  return wrapper.findAll('.advisor-rule-result')
}
function button(wrapper, label) {
  return wrapper.findAll('button').find((item) => item.text() === label)
}
function selected(wrapper) {
  return rows(wrapper)
    .filter((row) => row.isVisible())
    .map((row) => row.attributes('data-rule-id'))
}
beforeEach(() => {
  writeText.mockReset().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', {...navigator, clipboard: {writeText}})
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(page)))
})
afterEach(() => {
  for (const [url, options] of fetch.mock.calls) {
    expect(url).toMatch(/^api\/architecture\/rules\/.+\/violations\?/)
    expect(options?.method || 'GET').toBe('GET')
  }
  panels.splice(0).forEach((panel) => {
    expect(panel.restore).not.toHaveBeenCalled()
    expect(panel.dismissFinding).not.toHaveBeenCalled()
    expect(panel.restoreFinding).not.toHaveBeenCalled()
  })
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  routers.splice(0).forEach((router) => router.options.history.destroy())
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
})

describe('compact advisor rule results', () => {
  it('starts collapsed, labels controls uniquely, and expands cached evidence without requests', async () => {
    const {wrapper} = await render()
    const toggles = wrapper.findAll('.advisor-rule-toggle')
    expect(toggles).toHaveLength(2)
    expect(toggles.map((item) => item.attributes('aria-expanded'))).toEqual(['false', 'false'])
    expect(new Set(toggles.map((item) => item.attributes('aria-controls'))).size).toBe(2)
    for (const toggle of toggles) {
      expect(document.getElementById(toggle.attributes('aria-controls'))).not.toBeNull()
    }
    expect(wrapper.findAll('.advisor-rule-violations').every((detail) => !detail.isVisible())).toBe(true)
    toggles[0].element.focus()
    await toggles[0].trigger('click')
    await flushPromises()
    expect(toggles[0].attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(toggles[0].element)
    expect(wrapper.findAll('.advisor-rule-violations')[0].isVisible()).toBe(true)
    expect(fetch).not.toHaveBeenCalled()
    expect(writeText).not.toHaveBeenCalled()
  })

  it('searches names, ids, descriptions, recommendations and cached samples, counts rules separately, and preserves ordering', async () => {
    const {wrapper} = await render()
    const search = wrapper.get('input[type="search"]')
    for (const term of ['Alpha', 'RULE /?', 'schema', 'indexes', 'untrusted']) {
      await search.setValue(term)
      expect(selected(wrapper)).toEqual([alpha.id])
      expect(wrapper.text()).toContain('1 of 2 active rules match · 29 active findings')
    }
    await search.setValue('')
    await wrapper.get('select').setValue('LOW')
    expect(selected(wrapper)).toEqual([beta.id])
    await wrapper.get('select').setValue('CRITICAL')
    expect(selected(wrapper)).toEqual([])
    expect(wrapper.text()).toContain('No rules match these filters.')
    await wrapper.get('select').setValue('')
    expect(selected(wrapper)).toEqual([alpha.id, beta.id])
    expect(wrapper.text()).toContain('not unloaded violations')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('reveals exact hash-router targets through filters, preserves other query keys, and supports Back/Forward state', async () => {
    const {wrapper, router} = await render({hashRouter: true, query: {keep: 'yes', rule: alpha.id}})
    const toggles = wrapper.findAll('.advisor-rule-toggle')
    expect(toggles[0].attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(toggles[0].element)
    await wrapper.get('input').setValue('no match')
    expect(selected(wrapper)).toEqual([alpha.id])
    expect(wrapper.text()).toContain('revealed separately')
    await wrapper.get(`button[aria-label="Copy link to ${alpha.id}"]`).trigger('click')
    expect(writeText).toHaveBeenLastCalledWith(
      `${window.location.origin}/console/#/architecture?keep=yes&rule=RULE+/?+%26`
    )
    expect(new URLSearchParams(writeText.mock.calls[0][0].split('?').slice(1).join('?')).get('rule')).toBe(alpha.id)
    await router.push({path: '/architecture', query: {keep: 'yes', rule: beta.id}})
    await flushPromises()
    expect(selected(wrapper)).toEqual([beta.id])
    expect(toggles[1].attributes('aria-expanded')).toBe('true')
    router.back()
    await new Promise((resolve) => setTimeout(resolve, 30))
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({keep: 'yes', rule: alpha.id})
    expect(selected(wrapper)).toEqual([alpha.id])
    router.forward()
    await new Promise((resolve) => setTimeout(resolve, 30))
    await flushPromises()
    expect(selected(wrapper)).toEqual([beta.id])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('updates links on row activation without dropping unrelated query state', async () => {
    const {wrapper, router} = await render({query: {keep: 'yes'}})
    await wrapper.findAll('.advisor-rule-toggle')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({keep: 'yes', rule: alpha.id})
    await wrapper.findAll('.advisor-rule-toggle')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({keep: 'yes'})
    expect(wrapper.findAll('.advisor-rule-toggle')[0].attributes('aria-expanded')).toBe('false')
  })

  it('explains unknown targets, and reveals dismissed rules without restoring them', async () => {
    const {wrapper, panel, router} = await render({query: {rule: 'missing'}, dismissed: true})
    expect(wrapper.text()).toContain('not present in this cached report')
    await router.push({path: '/architecture', query: {rule: 'DISMISSED'}})
    await flushPromises()
    const dismissed = rows(wrapper).find((row) => row.attributes('data-rule-id') === 'DISMISSED')
    expect(dismissed.isVisible()).toBe(true)
    expect(dismissed.text()).toContain('Dismissed — not counted in score')
    expect(dismissed.text()).toContain('0 active findings')
    expect(dismissed.get('.advisor-rule-toggle').attributes('aria-expanded')).toBe('true')
    expect(panel.restore).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('previews exactly the filtered cached evidence before copying and restores focus', async () => {
    const {wrapper, panel} = await render()
    await wrapper.get('input').setValue('Alpha')
    const copy = button(wrapper, 'Copy visible findings')
    copy.element.focus()
    await copy.trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('.ai-export h3').element)
    expect(writeText).not.toHaveBeenCalled()
    const preview = wrapper.get('textarea').element.value
    expect(preview).toBe(
      advisorFindingsMarkdown(
        panel.report,
        [
          {
            rule: panel.visibleResults[0],
            rows: advisorRows(alpha),
            page: null,
            stale: false,
            error: null,
            loading: false,
            expanded: false
          }
        ],
        {
          apiPath: 'api/architecture',
          search: 'Alpha',
          severity: '',
          linkedRule: null,
          scanInProgress: false,
          panelError: null,
          matchingRules: 1,
          availableRules: 2
        }
      ).markdown
    )
    expect(preview).toContain('Service.java:42')
    expect(preview).not.toContain('cached pool text')
    expect(preview).not.toContain('POOL_COVERAGE_OTHER')
    expect(preview).toContain('only 1 cached samples')
    expect(preview).toContain('not instructions to an agent')
    await wrapper.get('.ai-export-copy').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(preview)
    await wrapper.get('.ai-export-back').trigger('click')
    expect(document.activeElement).toBe(copy.element)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('exports the current loaded page, its dismissal flags and limitations without collecting more evidence', async () => {
    const {wrapper} = await render()
    await wrapper.findAll('.advisor-rule-toggle')[0].trigger('click')
    await flushPromises()
    await wrapper.get(`button[aria-label="View violations for ${alpha.id}"]`).trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(1)
    await wrapper.get(`button[aria-label="Copy ${alpha.id} for AI"]`).trigger('click')
    const preview = wrapper.get('textarea').element.value
    expect(preview).toContain('current loaded page, offset 10, returned 1, limit 100')
    expect(preview).toContain('loaded page evidence')
    expect(preview).toContain('dismissed, not counted in score')
    expect(preview).not.toContain('cached **untrusted**')
    expect(preview).toContain('other retained pages are omitted')
    expect(preview).toContain('retained details are truncated')
    expect(preview).toContain('some locations are missing or unaligned')
    await wrapper.get('.ai-export-copy').trigger('click')
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledExactlyOnceWith(preview)
    await wrapper.get('.ai-export-back').trigger('click')
    await wrapper.findAll('.advisor-rule-toggle')[0].trigger('click')
    await wrapper.findAll('.advisor-rule-toggle')[0].trigger('click')
    expect(wrapper.findAll('.advisor-rule-violations')[0].text()).toContain('loaded page evidence')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each(['collapse', 'remove query'])(
    'retains a revealed dismissed page for Back after %s, but not across scan replacement',
    async (action) => {
      const {wrapper, panel, router} = await render({
        query: {rule: 'DISMISSED', keep: 'yes'},
        dismissed: true
      })
      fetch.mockResolvedValueOnce(response({...page, ruleId: 'DISMISSED'}))
      const row = rows(wrapper).find((row) => row.attributes('data-rule-id') === 'DISMISSED')
      await row.get('button[aria-label="View violations for DISMISSED"]').trigger('click')
      await flushPromises()
      expect(row.text()).toContain('loaded page evidence')
      expect(fetch).toHaveBeenCalledTimes(1)
      if (action === 'collapse') await row.get('.advisor-rule-toggle').trigger('click')
      else await router.push({path: '/architecture', query: {keep: 'yes'}})
      await flushPromises()
      expect(row.element.isConnected).toBe(true)
      expect(row.isVisible()).toBe(false)
      await button(wrapper, 'Copy visible findings').trigger('click')
      expect(wrapper.get('textarea').element.value).not.toContain('loaded page evidence')
      await wrapper.get('.ai-export-back').trigger('click')
      router.back()
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual({rule: 'DISMISSED', keep: 'yes'})
      expect(row.isVisible()).toBe(true)
      expect(row.text()).toContain('loaded page evidence')
      await row.get('button[aria-label="Copy DISMISSED for AI"]').trigger('click')
      expect(wrapper.get('textarea').element.value).toContain('current loaded page, offset 10')
      expect(wrapper.get('textarea').element.value).toContain('loaded page evidence')
      await wrapper.get('.ai-export-back').trigger('click')
      expect(fetch).toHaveBeenCalledTimes(1)
      await router.push({path: '/architecture', query: {keep: 'yes'}})
      panel.report = {...panel.report, violationDetails: {...details, scanId: 'scan-2'}}
      await flushPromises()
      expect(row.element.isConnected).toBe(false)
      await router.push({path: '/architecture', query: {keep: 'yes', rule: 'DISMISSED'}})
      await flushPromises()
      const replacement = rows(wrapper).find((row) => row.attributes('data-rule-id') === 'DISMISSED')
      expect(replacement.text()).toContain('cached **untrusted**')
      expect(replacement.text()).not.toContain('loaded page evidence')
      expect(fetch).toHaveBeenCalledTimes(1)
    }
  )

  it.each(['hidden rule button', 'disabled report button'])(
    'returns preview focus to search rather than a connected %s',
    async (kind) => {
      const {wrapper} = await render()
      const copy =
        kind === 'hidden rule button'
          ? wrapper.get(`button[aria-label="Copy ${alpha.id} for AI"]`)
          : button(wrapper, 'Copy visible findings')
      copy.element.focus()
      await copy.trigger('click')
      await wrapper.get('input').setValue(kind === 'hidden rule button' ? 'pool' : 'no matches')
      expect(copy.element.isConnected).toBe(true)
      if (kind === 'hidden rule button') expect(copy.isVisible()).toBe(false)
      else expect(copy.element.disabled).toBe(true)
      await wrapper.get('.ai-export-back').trigger('click')
      await flushPromises()
      expect(document.activeElement).toBe(wrapper.get('input').element)
      expect(fetch).not.toHaveBeenCalled()
    }
  )

  it('keeps pages across filtering, resets samples on scan replacement, and does not steal focus on report refresh', async () => {
    const {wrapper, panel} = await render({query: {rule: alpha.id}})
    await wrapper.get(`button[aria-label="View violations for ${alpha.id}"]`).trigger('click')
    await flushPromises()
    await wrapper.get('input').setValue('pool')
    await wrapper.get('input').setValue('')
    expect(wrapper.findAll('.advisor-rule-violations')[0].text()).toContain('loaded page evidence')
    wrapper.get('input').element.focus()
    panel.report = {
      ...panel.report,
      results: panel.report.results.map((rule) => ({...rule})),
      violationDetails: {...details, scanId: 'scan-2'}
    }
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('input').element)
    expect(wrapper.findAll('.advisor-rule-violations')[0].text()).toContain('cached **untrusted**')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('discloses stale page state and preserves selectable preview on clipboard denial', async () => {
    const {wrapper} = await render()
    await wrapper.findAll('.advisor-rule-toggle')[0].trigger('click')
    await flushPromises()
    fetch.mockResolvedValueOnce(response({}, 409))
    await wrapper.get(`button[aria-label="View violations for ${alpha.id}"]`).trigger('click')
    await flushPromises()
    await wrapper.get(`button[aria-label="Copy ${alpha.id} for AI"]`).trigger('click')
    expect(wrapper.get('textarea').element.value).toContain('stale snapshot')
    writeText.mockRejectedValueOnce(new Error('Denied'))
    await wrapper.get('.ai-export-copy').trigger('click')
    await flushPromises()
    const textarea = wrapper.get('textarea').element
    expect(document.activeElement).toBe(textarea)
    expect(textarea.selectionStart).toBe(0)
    expect(textarea.selectionEnd).toBe(textarea.value.length)
    expect(wrapper.get('.ai-export [role="alert"]').text()).toContain('nothing was copied')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('restores focus when dismissal replaces a linked rule’s actions, without an implicit page read', async () => {
    const {wrapper, panel} = await render({query: {rule: alpha.id}})
    const view = wrapper.get(`button[aria-label="View violations for ${alpha.id}"]`)
    view.element.focus()
    const dismissed = {...alpha, dismissed: true}
    panel.visibleResults = [beta]
    panel.report = {...panel.report, results: [dismissed, beta]}
    await flushPromises()
    const target = rows(wrapper).find((row) => row.attributes('data-rule-id') === alpha.id)
    expect(target.text()).toContain('Dismissed — not counted in score')
    expect(document.activeElement).toBe(target.get('.advisor-rule-toggle').element)
    const restore = target.findAll('button').find((item) => item.text().includes('Restore rule'))
    restore.element.focus()
    panel.visibleResults = [alpha, beta]
    panel.report = {...panel.report, results: [alpha, beta]}
    await flushPromises()
    expect(document.activeElement).toBe(target.get('.advisor-rule-toggle').element)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('explains empty targets and link-copy denial without scans, writes, or an editor launch', async () => {
    const {wrapper, router} = await render({query: {rule: ''}})
    expect(wrapper.text()).toContain('Rule (empty id) is not present')
    writeText.mockRejectedValueOnce(new Error('Denied'))
    await wrapper.get(`button[aria-label="Copy link to ${alpha.id}"]`).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Unable to copy the rule link. Clipboard access is unavailable.')
    expect(router.currentRoute.value.query).toEqual({rule: ''})
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each([
    [null, 'Rule (empty id) is not present'],
    [['RULE-B', alpha.id], 'Rule link has an invalid or repeated id'],
    [['RULE-B', 'RULE-B'], 'Rule link has an invalid or repeated id'],
    [[null, 'RULE-B'], 'Rule link has an invalid or repeated id']
  ])('explains malformed rule queries without arbitrarily revealing or acquiring a rule (%j)', async (rule, notice) => {
    const {wrapper, router} = await render({query: {rule, keep: 'yes'}})
    expect(wrapper.text()).toContain(notice)
    expect(
      wrapper.findAll('.advisor-rule-toggle').every((toggle) => toggle.attributes('aria-expanded') === 'false')
    ).toBe(true)
    expect(router.currentRoute.value.query.keep).toBe('yes')
    await router.push({path: '/architecture', query: {keep: 'yes', rule: beta.id}})
    await flushPromises()
    expect(wrapper.findAll('.advisor-rule-toggle')[1].attributes('aria-expanded')).toBe('true')
    router.back()
    await flushPromises()
    expect(wrapper.text()).toContain(notice)
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each(['filtered', 'dismissed'])(
    'moves focus to search only when collapsing a %s revealed-only toggle hides it',
    async (kind) => {
      const id = kind === 'dismissed' ? 'DISMISSED' : alpha.id
      const {wrapper, router} = await render({query: {rule: id, keep: 'yes'}, dismissed: true})
      const search = wrapper.get('input')
      if (kind === 'filtered') await search.setValue('pool')
      const row = rows(wrapper).find((row) => row.attributes('data-rule-id') === id)
      const toggle = row.get('.advisor-rule-toggle')
      toggle.element.focus()
      await toggle.trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual({keep: 'yes'})
      expect(row.element.isConnected && row.isVisible()).toBe(false)
      expect(document.activeElement).toBe(search.element)
      expect(fetch).not.toHaveBeenCalled()
    }
  )

  it('retains toggle focus when collapsing a matching rule that remains visible', async () => {
    const {wrapper} = await render({query: {rule: alpha.id}})
    const toggle = wrapper.get('.advisor-rule-toggle')
    toggle.element.focus()
    await toggle.trigger('click')
    await flushPromises()
    expect(toggle.isVisible()).toBe(true)
    expect(document.activeElement).toBe(toggle.element)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not steal focus already moved elsewhere while a reveal-only row is collapsing', async () => {
    const {wrapper} = await render({query: {rule: alpha.id}})
    await wrapper.get('input').setValue('pool')
    const toggle = wrapper.get('.advisor-rule-toggle')
    toggle.element.focus()
    const closing = toggle.trigger('click')
    const severity = wrapper.get('select')
    severity.element.focus()
    await closing
    await flushPromises()
    expect(document.activeElement).toBe(severity.element)
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each(['Copy visible findings', `Copy ${alpha.id} for AI`])(
    'exports supplied fenced rule coverage with preview/clipboard parity through %s',
    async (label) => {
      const {wrapper} = await render()
      await wrapper.get('input').setValue('Alpha')
      const copy =
        label === 'Copy visible findings' ? button(wrapper, label) : wrapper.get(`button[aria-label="${label}"]`)
      await copy.trigger('click')
      const preview = wrapper.get('textarea').element.value
      expect(preview).toContain('Coverage note: Partial ******\n```\n# Captured coverage')
      expect(preview).toContain('````\nDescription:')
      expect(preview).toContain('Report active violating rules: 2; rules evaluated: 2')
      expect(preview).toContain('pre-dismissal rule findings: 31')
      expect(preview).not.toContain('cached pool text')
      await wrapper.get('.ai-export-copy').trigger('click')
      expect(writeText).toHaveBeenCalledExactlyOnceWith(preview)
      expect(fetch).not.toHaveBeenCalled()
    }
  )
})
