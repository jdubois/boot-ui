import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import Hibernate from './Hibernate.vue'
import {settleConfirm} from '../utils/useConfirm.js'

const RULE = 'HIB-ID-006'
const SCAN = 'scan-1'
const TEXTS = ['[app] Item.id', '[app] Order.id']
const IDENTITIES = ['v1:unit=app|entity=Item|member=id', 'v1:unit=app|entity=Order|member=id']

/** A tiny in-memory server: the report follows what was dismissed, like the real read-time projection. */
function server({readOnly = false} = {}) {
  const state = {dismissed: new Set(), notes: new Map(), calls: []}
  const rule = () => {
    const suppressedCount = state.dismissed.size
    const active = TEXTS.filter((_, i) => !state.dismissed.has(IDENTITIES[i]))
    return {
      id: RULE,
      name: 'Identity generation',
      category: 'Identifiers',
      severity: 'MEDIUM',
      description: 'Rule description.',
      status: 'VIOLATION',
      violationCount: active.length,
      suppressedCount,
      sampleViolations: active,
      recommendation: 'Rule recommendation.'
    }
  }
  const report = () => {
    const result = rule()
    return {
      localOnly: true,
      disclaimer: 'Disclaimer.',
      evidence: {usable: true, coverageComplete: true, limitations: []},
      entityPackages: [],
      entitiesAnalyzed: 1,
      rulesEvaluated: 1,
      violationsFound: result.violationCount > 0 ? 1 : 0,
      severityCounts: [{severity: 'MEDIUM', count: result.violationCount}],
      scan: {status: 'SCANNED', message: 'ok', scannedAt: 1_700_000_000_000, violationsFound: 1},
      results: [result],
      diagnostics: [],
      violationDetails: {scanId: SCAN, total: 2, retained: 2, retentionLimit: 10000, truncated: false},
      suppression: {
        suppressedFindings: state.dismissed.size,
        incompleteRules: [],
        omitted: 0,
        findings: IDENTITIES.filter((id) => state.dismissed.has(id)).map((identity) => ({
          ruleId: RULE,
          identity,
          state: 'MATCHED'
        }))
      }
    }
  }
  const json = (body, status = 200) =>
    new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}})
  const fetchMock = vi.fn((url, options = {}) => {
    const method = (options.method || 'GET').toUpperCase()
    state.calls.push([method, String(url)])
    if (String(url) === 'api/dismissed-findings') {
      if (method === 'GET') {
        return Promise.resolve(
          json({
            dismissed: [...state.dismissed].map((identity) => ({
              ruleId: RULE,
              identity,
              note: state.notes.get(identity) || null
            })),
            ignored: 0
          })
        )
      }
      if (readOnly) return Promise.resolve(json({error: 'read-only'}, 403))
      const body = JSON.parse(options.body)
      if (method === 'POST') {
        state.dismissed.add(body.identity)
        if (body.note) state.notes.set(body.identity, body.note)
      } else {
        state.dismissed.delete(body.identity)
        state.notes.delete(body.identity)
      }
      return Promise.resolve(json({dismissed: [], ignored: 0}))
    }
    if (String(url).includes('/violations?')) {
      const flags = IDENTITIES.map((id) => state.dismissed.has(id))
      return Promise.resolve(
        json({
          scanId: SCAN,
          ruleId: RULE,
          violationCount: 2,
          retainedCount: 2,
          truncated: false,
          violations: TEXTS,
          identities: IDENTITIES,
          suppressed: flags.some(Boolean) ? flags : [],
          suppressedCount: flags.filter(Boolean).length,
          page: {total: 2, matched: 2, offset: 0, limit: 100, returned: 2, hasMore: false}
        })
      )
    }
    return Promise.resolve(json(report()))
  })
  return {state, fetchMock}
}

const wrappers = []
async function mountPanel(fake, props = {}) {
  vi.stubGlobal('fetch', fake.fetchMock)
  const wrapper = mount(Hibernate, {attachTo: document.body, props})
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

async function dismiss(wrapper, index) {
  wrapper.get(`button[aria-label="Dismiss finding ${index} of ${RULE}"]`).trigger('click')
  await flushPromises()
  settleConfirm(true)
  await flushPromises()
}

afterEach(() => {
  settleConfirm(false)
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('Hibernate per-finding dismissal', () => {
  it('dismisses one finding, lowers the counts at once, and restores it without a rescan', async () => {
    const fake = server()
    const wrapper = await mountPanel(fake)
    expect(wrapper.text()).toContain('2 findings found for this rule.')

    await wrapper.get(`button[aria-label="View violations for ${RULE}"]`).trigger('click')
    await flushPromises()
    await dismiss(wrapper, 1)

    expect(wrapper.text()).toContain('1 finding found for this rule.')
    expect(wrapper.text()).toContain('1 more dismissed one by one.')
    expect(wrapper.get('[data-testid="suppressed-summary"]').text()).toContain('1 finding dismissed one by one')
    expect(wrapper.findAll('[data-testid="suppressed-finding"]')).toHaveLength(1)
    expect(wrapper.findAll('[role="status"]').map((region) => region.text())).toContain(
      'Dismissed a finding of HIB-ID-006. 1 finding active.'
    )
    expect(fake.state.calls.some(([method, url]) => method === 'POST' && url.endsWith('/scan'))).toBe(false)

    await wrapper.get(`button[aria-label="Restore finding 1 of ${RULE}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('2 findings found for this rule.')
    expect(wrapper.find('[data-testid="suppressed-summary"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="suppressed-finding"]')).toHaveLength(0)
    expect(fake.state.calls.some(([method, url]) => method === 'POST' && url.endsWith('/scan'))).toBe(false)
  })

  it('moves a rule with every finding dismissed to its own list and keeps focus on a stable heading', async () => {
    const fake = server()
    fake.state.dismissed.add(IDENTITIES[0])
    const wrapper = await mountPanel(fake)
    await wrapper.get(`button[aria-label="View violations for ${RULE}"]`).trigger('click')
    await flushPromises()

    await dismiss(wrapper, 2)

    const allDismissed = wrapper.get('[data-testid="all-dismissed"]')
    expect(allDismissed.text()).toContain('All findings dismissed (1 rule)')
    expect(allDismissed.text()).toContain('2 findings are dismissed')
    expect(wrapper.text()).toContain('Every finding is dismissed')
    expect(wrapper.text()).not.toContain('No Hibernate Advisor findings')
    expect(wrapper.find('.badge.text-bg-secondary').exists()).toBe(true)
    expect(document.activeElement.id).toBe('advisor-all-dismissed-heading')
  })

  it('lists dismissed findings collapsed, reads their notes only when opened, and removes one', async () => {
    const fake = server()
    fake.state.dismissed.add(IDENTITIES[0])
    fake.state.notes.set(IDENTITIES[0], 'Intentional')
    const wrapper = await mountPanel(fake)
    expect(fake.state.calls.some(([, url]) => url === 'api/dismissed-findings')).toBe(false)

    const details = wrapper.get('[data-testid="dismissed-findings"]')
    expect(details.element.open).toBe(false)
    details.element.open = true
    await details.trigger('toggle')
    await flushPromises()

    expect(details.get('input[type="text"]').element.value).toBe('Intentional')
    await details.get('li button[aria-label^="Restore dismissed finding"]').trigger('click')
    await flushPromises()
    expect(fake.state.dismissed.size).toBe(0)
    expect(wrapper.find('[data-testid="dismissed-findings"]').exists()).toBe(false)
  })

  it('shows the server refusal and changes nothing when the write is refused', async () => {
    const fake = server({readOnly: true})
    const wrapper = await mountPanel(fake)
    await wrapper.get(`button[aria-label="View violations for ${RULE}"]`).trigger('click')
    await flushPromises()

    await dismiss(wrapper, 1)

    expect(fake.state.dismissed.size).toBe(0)
    expect(wrapper.text()).toContain('Unable to dismiss finding')
    expect(wrapper.text()).toContain('2 findings found for this rule.')
  })
})
