import {flushPromises, mount} from '@vue/test-utils'
import {h} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import AdvisorRuleViolations from './AdvisorRuleViolations.vue'
import {OPEN_IN_STORAGE_KEY, resetOpenInPreference} from '../../utils/sourceLocation.js'

const ruleId = 'ARCH-SPRING-004'
const details = {scanId: 'scan one', total: 29, retained: 29, retentionLimit: 10000, truncated: false}
const violations = (count, start = 0) =>
  Array.from({length: count}, (_, index) => `example.Service${index + start} violates ${ruleId}`)
const rule = {id: ruleId, violationCount: 29, sampleViolations: violations(10)}

function result({count = 29, retained = count, offset = 0, limit = 100, scanId = details.scanId} = {}) {
  const returned = Math.max(0, Math.min(limit, retained - offset))
  return {
    scanId,
    ruleId,
    violationCount: count,
    retainedCount: retained,
    truncated: retained < count,
    violations: violations(returned, offset),
    page: {total: retained, matched: retained, offset, limit, returned, hasMore: offset + returned < retained}
  }
}

function response(body, status = 200) {
  return new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}})
}

function deferred() {
  let resolve
  const promise = new Promise((done) => (resolve = done))
  return {promise, resolve}
}

const wrappers = []
function render(props = {}) {
  const wrapper = mount(AdvisorRuleViolations, {
    attachTo: document.body,
    props: {apiPath: 'api/architecture', rule, details, refreshReport: vi.fn(), ...props}
  })
  wrappers.push(wrapper)
  return wrapper
}

function button(wrapper, action) {
  return wrapper.get(`button[aria-label="${action} for ${wrapper.props('rule').id}"]`)
}

beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('AdvisorRuleViolations', () => {
  it('keeps the compact samples without fetching on mount or report arrival', async () => {
    const wrapper = render()
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(violations(10))
    expect(wrapper.text()).toContain('Sample details (showing 10 of 29)')
    await wrapper.setProps({details: {...details}, rule: {...rule}})
    expect(fetch).not.toHaveBeenCalled()
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
    expect(wrapper.get('[role="status"]').text()).toBe('')
  })

  it.each([29, 22, 16])('replaces samples with every detail of a %i-finding rule on demand', async (count) => {
    vi.mocked(fetch).mockResolvedValue(response(result({count})))
    const wrapper = render({rule: {...rule, violationCount: count}})
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0][0]).toBe(
      'api/architecture/rules/ARCH-SPRING-004/violations?scanId=scan+one&offset=0&limit=100'
    )
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(violations(count))
    expect(wrapper.text()).toContain(`Showing 1–${count} of ${count} retained violations (${count} found)`)
    expect(document.activeElement).toBe(wrapper.get('[tabindex="-1"]').element)
    expect(wrapper.findAll('[role="status"], [role="alert"], [aria-live]')).toHaveLength(1)
    await button(wrapper, 'Back to samples').trigger('click')
    expect(wrapper.findAll('li')).toHaveLength(10)
    expect(document.activeElement).toBe(button(wrapper, 'View violations').element)
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('encodes the rule ID and renders detail strings as text, not markup', async () => {
    const id = 'RULE / ?'
    vi.mocked(fetch).mockResolvedValue(response({...result(), ruleId: id, violations: ['<script>unsafe()</script>']}))
    const wrapper = render({rule: {...rule, id}})
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[0][0]).toContain('/rules/RULE%20%2F%20%3F/violations?')
    expect(wrapper.findAll('script')).toHaveLength(0)
    expect(wrapper.get('li').text()).toBe('<script>unsafe()</script>')
  })

  it('loads one bounded page at a time without appending, and supports Previous', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(response(result({count: 201})))
      .mockResolvedValueOnce(response(result({count: 201, offset: 100})))
      .mockResolvedValueOnce(response(result({count: 201, offset: 200})))
      .mockResolvedValueOnce(response(result({count: 201, offset: 100})))
    const wrapper = render({rule: {...rule, violationCount: 201}})
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(button(wrapper, 'Previous violations').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Next violations').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(violations(100, 100))
    await button(wrapper, 'Next violations').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(violations(1, 200))
    expect(button(wrapper, 'Next violations').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Previous violations').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Showing 101–200 of 201 retained violations')
    expect(fetch.mock.calls.map(([url]) => new URL(url, 'http://localhost/').searchParams.get('offset'))).toEqual([
      '0',
      '100',
      '200',
      '100'
    ])
  })

  it('preserves samples while loading and failing, with a local retry', async () => {
    const pending = deferred()
    vi.mocked(fetch).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(response(result()))
    const wrapper = render()
    await button(wrapper, 'View violations').trigger('click')
    expect(wrapper.findAll('li')).toHaveLength(10)
    expect(button(wrapper, 'View violations').attributes('disabled')).toBeDefined()
    pending.resolve(response({error: 'Unavailable'}, 503))
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(10)
    expect(wrapper.text()).toContain('Unable to load violations: HTTP 503')
    await button(wrapper, 'Retry violations').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(29)
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('keeps the last accepted page during a failed next-page request and retries its offset', async () => {
    const pending = deferred()
    vi.mocked(fetch)
      .mockResolvedValueOnce(response(result({count: 120})))
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(response(result({count: 120, offset: 100})))
    const wrapper = render()
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    await button(wrapper, 'Next violations').trigger('click')
    expect(wrapper.findAll('li')).toHaveLength(100)
    pending.resolve(response({}, 500))
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(100)
    await button(wrapper, 'Retry violations').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[2][0]).toContain('offset=100')
    expect(wrapper.findAll('li')).toHaveLength(20)
  })

  it.each([12, 0])('discloses retention overflow even with %i retained entries and no next page', async (retained) => {
    vi.mocked(fetch).mockResolvedValue(response(result({retained})))
    const wrapper = render({details: {...details, retained, truncated: true}})
    expect(wrapper.text()).toContain(`This scan retained ${retained} of 29 violation details across all rules`)
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(`Incomplete details: ${retained} of 29 violations retained for this rule`)
    expect(wrapper.text()).not.toContain('all 29')
    expect(wrapper.findAll('button[aria-label^="Next"]')).toHaveLength(0)
    if (retained === 0) expect(wrapper.text()).toContain('Showing 0 of 0 retained violations (29 found)')
  })

  it('offers only an explicit cached-report refresh after 409, never a scan', async () => {
    const refreshReport = vi.fn()
    vi.mocked(fetch).mockResolvedValue(response({}, 409))
    const wrapper = render({refreshReport})
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(10)
    expect(wrapper.text()).toContain('No scan will run')
    expect(refreshReport).not.toHaveBeenCalled()
    await button(wrapper, 'Refresh cached report').trigger('click')
    expect(refreshReport).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledOnce()
  })

  it.each([{scanId: 'another-scan'}, {ruleId: 'ANOTHER-RULE'}])(
    'rejects a mismatched page identity: %j',
    async (identity) => {
      vi.mocked(fetch).mockResolvedValue(response({...result(), ...identity}))
      const wrapper = render()
      await button(wrapper, 'View violations').trigger('click')
      await flushPromises()
      expect(wrapper.findAll('li')).toHaveLength(10)
      expect(button(wrapper, 'Refresh cached report').exists()).toBe(true)
    }
  )

  it('retains an accepted page after a same-scan dismissal refresh but clears it for a new scan', async () => {
    vi.mocked(fetch).mockResolvedValue(response(result()))
    const wrapper = render()
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    await wrapper.setProps({rule: {...rule}, details: {...details}})
    expect(wrapper.findAll('li')).toHaveLength(29)
    await wrapper.setProps({details: {...details, scanId: 'new-scan'}})
    expect(wrapper.findAll('li')).toHaveLength(10)
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('ignores late responses from a replaced scan even when the transport ignores abort', async () => {
    const old = deferred()
    vi.mocked(fetch)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(response(result({scanId: 'new-scan', count: 16})))
    const wrapper = render()
    await button(wrapper, 'View violations').trigger('click')
    const oldSignal = fetch.mock.calls[0][1].signal
    await wrapper.setProps({details: {...details, scanId: 'new-scan'}})
    expect(oldSignal.aborted).toBe(true)
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    old.resolve(response(result()))
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(16)
    expect(wrapper.get('[role="status"]').text()).toContain('16 retained violations')
  })

  it('aborts on unmount and does not announce a late response', async () => {
    const pending = deferred()
    vi.mocked(fetch).mockReturnValue(pending.promise)
    const wrapper = render()
    await button(wrapper, 'View violations').trigger('click')
    const status = wrapper.get('[role="status"]').element
    wrapper.unmount()
    expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
    pending.resolve(response(result()))
    await flushPromises()
    expect(status.textContent).toBe(`Loading violations for ${ruleId}.`)
  })

  it('lets Back to samples cancel a pending page without accepting it later', async () => {
    const pending = deferred()
    vi.mocked(fetch)
      .mockResolvedValueOnce(response(result({count: 120})))
      .mockReturnValueOnce(pending.promise)
    const wrapper = render()
    await button(wrapper, 'View violations').trigger('click')
    await flushPromises()
    await button(wrapper, 'Next violations').trigger('click')
    await button(wrapper, 'Back to samples').trigger('click')
    pending.resolve(response(result({count: 120, offset: 100})))
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(10)
  })

  it.each([null, {}, {...details, scanId: null}, {...details, scanId: ''}])(
    'preserves legacy previews without a broken control: %j',
    (metadata) => {
      const wrapper = render({details: metadata})
      expect(wrapper.findAll('li')).toHaveLength(10)
      expect(wrapper.findAll('button')).toHaveLength(0)
      expect(fetch).not.toHaveBeenCalled()
    }
  )

  it('keeps twenty-sample previews and hides detail reads when every finding is already shown', () => {
    const wrapper = render({rule: {...rule, violationCount: 20, sampleViolations: violations(20)}})
    expect(wrapper.findAll('li')).toHaveLength(20)
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('gives multiple rule controls unique targets and accessible names', () => {
    const wrapper = mount(
      {
        render: () =>
          h(
            'div',
            [rule, {...rule, id: 'ARCH-CODE-002'}].map((entry) =>
              h(AdvisorRuleViolations, {apiPath: 'api/architecture', rule: entry, details, refreshReport: vi.fn()})
            )
          )
      },
      {attachTo: document.body}
    )
    wrappers.push(wrapper)
    const [first, second] = wrapper.findAllComponents(AdvisorRuleViolations)
    const a = button(first, 'View violations')
    const b = button(second, 'View violations')
    expect(a.attributes('aria-controls')).not.toBe(b.attributes('aria-controls'))
    expect(a.attributes('aria-label')).not.toBe(b.attributes('aria-label'))
    expect(document.getElementById(a.attributes('aria-controls'))).not.toBeNull()
  })

  describe('locations', () => {
    const located = (index) => ({
      className: `example.Service${index}`,
      memberName: 'handle',
      kind: 'METHOD',
      sourceFile: `Service${index}.java`,
      line: index + 1,
      sourcePath: `/work/app/src/main/java/example/Service${index}.java`,
      precision: 'LINE'
    })
    const locations = (count, start = 0) =>
      Array.from({length: count}, (_, index) => ((index + start) % 3 === 2 ? null : located(index + start)))

    beforeEach(() => {
      window.localStorage.clear()
      resetOpenInPreference()
    })
    afterEach(() => {
      window.localStorage.clear()
      resetOpenInPreference()
    })

    it('shows each sample location beside its text, and nothing for a null entry', () => {
      const wrapper = render({rule: {...rule, sampleLocations: locations(10)}})
      const items = wrapper.findAll('li')
      expect(items).toHaveLength(10)
      expect(items[0].text()).toContain(violations(1)[0])
      expect(items[0].text()).toContain('example.Service0#handle (Service0.java:1)')
      expect(items[2].text()).toBe(violations(3)[2])
      expect(items[2].find('.advisor-violation-location').exists()).toBe(false)
      expect(wrapper.findAll('a')).toHaveLength(0)
    })

    it('ignores a location list that is not aligned with its texts', () => {
      const wrapper = render({rule: {...rule, sampleLocations: locations(3)}})
      expect(wrapper.findAll('.advisor-violation-location')).toHaveLength(0)
      expect(wrapper.findAll('li').map((item) => item.text())).toEqual(violations(10))
    })

    it('shows the page locations aligned with every detail page', async () => {
      vi.mocked(fetch).mockResolvedValueOnce(
        response({...result({count: 29, offset: 0, limit: 100}), locations: locations(29)})
      )
      const wrapper = render({rule: {...rule, sampleLocations: locations(10)}})
      await button(wrapper, 'View violations').trigger('click')
      await flushPromises()
      const items = wrapper.findAll('li')
      expect(items).toHaveLength(29)
      expect(items[28].text()).toContain('example.Service28#handle (Service28.java:29)')
      expect(items[26].find('.advisor-violation-location').exists()).toBe(false)
    })

    it('copies a location by keyboard-reachable button and announces it once', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      vi.stubGlobal('navigator', {clipboard: {writeText}})
      const wrapper = render({rule: {...rule, sampleLocations: locations(10)}})
      const copy = wrapper.get('button[aria-label="Copy location example.Service0#handle (Service0.java:1)"]')
      expect(copy.attributes('type')).toBe('button')
      await copy.trigger('click')
      await flushPromises()
      expect(writeText).toHaveBeenCalledWith('example.Service0#handle (Service0.java:1)')
      expect(copy.text()).toBe('Copied')
      expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
      expect(wrapper.get('[role="status"]').text()).toBe('Copied location example.Service0#handle (Service0.java:1).')
    })

    it('links a location only when an Open in preset is chosen and a local path exists', async () => {
      window.localStorage.setItem(OPEN_IN_STORAGE_KEY, 'idea')
      resetOpenInPreference()
      const withoutPath = {...located(1), sourcePath: null}
      const wrapper = render({rule: {...rule, sampleLocations: [located(0), withoutPath, ...locations(8, 2)]}})
      const links = wrapper.findAll('a')
      const first = links[0]
      expect(first.attributes('href')).toBe(
        'idea://open?file=%2Fwork%2Fapp%2Fsrc%2Fmain%2Fjava%2Fexample%2FService0.java&line=1'
      )
      expect(first.attributes('aria-label')).toBe('Open example.Service0#handle (Service0.java:1) in IntelliJ IDEA')
      expect(wrapper.findAll('li')[1].find('a').exists()).toBe(false)
      expect(wrapper.findAll('li')[1].text()).toContain('example.Service1#handle (Service1.java:2)')
      expect(links.every((link) => link.attributes('href').startsWith('idea://open?file='))).toBe(true)
    })
  })
})
