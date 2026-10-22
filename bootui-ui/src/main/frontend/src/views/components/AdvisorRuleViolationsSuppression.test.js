import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import AdvisorRuleViolations from './AdvisorRuleViolations.vue'
import {confirmState, settleConfirm} from '../../utils/useConfirm.js'

const ruleId = 'HIB-ID-006'
const details = {scanId: 'scan-1', total: 3, retained: 3, retentionLimit: 10000, truncated: false}
const rule = {
  id: ruleId,
  violationCount: 3,
  suppressedCount: 0,
  sampleViolations: ['[app] Item.id', '[app] Order.id', '[app] Line.id']
}

function page({suppressed = [false, false, false], identities = ['v1:i=1', 'v1:i=2', null]} = {}) {
  return {
    scanId: details.scanId,
    ruleId,
    violationCount: 3,
    retainedCount: 3,
    truncated: false,
    violations: ['[app] Item.id', '[app] Order.id', '[app] Line.id'],
    identities,
    suppressed: suppressed.some(Boolean) ? suppressed : [],
    suppressedCount: suppressed.filter(Boolean).length,
    page: {total: 3, matched: 3, offset: 0, limit: 100, returned: 3, hasMore: false}
  }
}

const response = (body) => new Response(JSON.stringify(body), {headers: {'content-type': 'application/json'}})

const wrappers = []
function render(props = {}) {
  const wrapper = mount(AdvisorRuleViolations, {
    attachTo: document.body,
    props: {
      apiPath: 'api/hibernate',
      rule,
      details,
      refreshReport: vi.fn(),
      suppressible: true,
      dismissFinding: vi.fn().mockResolvedValue(true),
      restoreFinding: vi.fn().mockResolvedValue(true),
      ...props
    }
  })
  wrappers.push(wrapper)
  return wrapper
}

async function openPage(wrapper) {
  await wrapper.get(`button[aria-label="View violations for ${ruleId}"]`).trigger('click')
  await flushPromises()
}

beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
afterEach(() => {
  settleConfirm(false)
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('AdvisorRuleViolations per-finding dismissal', () => {
  it('offers the paged view for a rule that fits in its samples, and no action before it is open', async () => {
    const wrapper = render()
    expect(wrapper.find(`button[aria-label="View violations for ${ruleId}"]`).exists()).toBe(true)
    expect(wrapper.findAll('.advisor-finding-action')).toHaveLength(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('offers Dismiss only for a finding with a canonical identity', async () => {
    fetch.mockResolvedValue(response(page()))
    const wrapper = render()
    await openPage(wrapper)

    const actions = wrapper.findAll('.advisor-finding-action')
    expect(actions).toHaveLength(2)
    expect(actions.map((action) => action.attributes('aria-label'))).toEqual([
      `Dismiss finding 1 of ${ruleId}`,
      `Dismiss finding 2 of ${ruleId}`
    ])
    expect(wrapper.findAll('li')[2].find('.advisor-finding-action').exists()).toBe(false)
  })

  it('does not offer any action when the advisor is not suppressible', async () => {
    fetch.mockResolvedValue(response(page()))
    const wrapper = render({suppressible: false, rule: {...rule, violationCount: 29}})
    await openPage(wrapper)
    expect(wrapper.findAll('.advisor-finding-action')).toHaveLength(0)
  })

  it('confirms, says what is hidden and how to undo it, then dismisses and rereads the page', async () => {
    fetch
      .mockResolvedValueOnce(response(page()))
      .mockResolvedValueOnce(response(page({suppressed: [true, false, false]})))
    const dismissFinding = vi.fn().mockResolvedValue(true)
    const wrapper = render({dismissFinding})
    await openPage(wrapper)

    wrapper.get(`button[aria-label="Dismiss finding 1 of ${ruleId}"]`).trigger('click')
    await flushPromises()
    expect(confirmState.open).toBe(true)
    expect(confirmState.options.message).toContain('not application safety')
    expect(confirmState.options.message).toContain('restore it')
    expect(confirmState.options.resource).toBe('[app] Item.id')
    expect(dismissFinding).not.toHaveBeenCalled()

    settleConfirm(true)
    await flushPromises()
    expect(dismissFinding).toHaveBeenCalledWith(expect.objectContaining({id: ruleId}), 'v1:i=1')

    await wrapper.setProps({revision: 1})
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[data-testid="suppressed-finding"]')).toHaveLength(1)
    expect(wrapper.findAll('li')[0].text()).toContain('Dismissed')
    expect(wrapper.text()).toContain('3 found, 1 dismissed')
    expect(document.activeElement.getAttribute('aria-label')).toBe(`Restore finding 1 of ${ruleId}`)
  })

  it('does nothing when the confirmation is cancelled', async () => {
    fetch.mockResolvedValue(response(page()))
    const dismissFinding = vi.fn()
    const wrapper = render({dismissFinding})
    await openPage(wrapper)

    wrapper.get(`button[aria-label="Dismiss finding 1 of ${ruleId}"]`).trigger('click')
    await flushPromises()
    settleConfirm(false)
    await flushPromises()

    expect(dismissFinding).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('marks a dismissed finding with a text badge and restores it without a confirmation', async () => {
    fetch.mockResolvedValue(response(page({suppressed: [true, false, false]})))
    const restoreFinding = vi.fn().mockResolvedValue(true)
    const wrapper = render({restoreFinding})
    await openPage(wrapper)

    expect(wrapper.get('[data-testid="suppressed-finding"]').text()).toBe('Dismissed')
    await wrapper.get(`button[aria-label="Restore finding 1 of ${ruleId}"]`).trigger('click')
    expect(confirmState.open).toBe(false)
    expect(restoreFinding).toHaveBeenCalledWith(expect.objectContaining({id: ruleId}), 'v1:i=1')
  })

  it('disables the actions while the panel is busy or read-only', async () => {
    fetch.mockResolvedValue(response(page()))
    const wrapper = render({disabled: true})
    await openPage(wrapper)
    expect(
      wrapper.findAll('.advisor-finding-action').every((action) => action.attributes('disabled') !== undefined)
    ).toBe(true)
  })

  it('drops a page still in flight when the dismissals change, so no stale flags are shown', async () => {
    let finishOld
    fetch
      .mockResolvedValueOnce(response(page()))
      .mockReturnValueOnce(new Promise((resolve) => (finishOld = resolve)))
      .mockResolvedValueOnce(response(page({suppressed: [false, true, false]})))
    const wrapper = render()
    await openPage(wrapper)

    await wrapper.setProps({revision: 1})
    await flushPromises()
    await wrapper.setProps({revision: 2})
    await flushPromises()
    finishOld(response(page()))
    await flushPromises()

    expect(wrapper.findAll('[data-testid="suppressed-finding"]')).toHaveLength(1)
    expect(wrapper.findAll('li')[1].text()).toContain('Dismissed')
  })

  it('says when every finding of a rule is dismissed', async () => {
    const wrapper = render({
      rule: {id: ruleId, violationCount: 0, suppressedCount: 3, sampleViolations: []}
    })
    expect(wrapper.text()).toContain('All 3 findings are dismissed')
    expect(wrapper.text()).not.toContain('showing 0 of 0')
    expect(wrapper.find(`button[aria-label="View violations for ${ruleId}"]`).exists()).toBe(true)
  })
})
