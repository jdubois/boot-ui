import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import {confirmState, settleConfirm} from '../../utils/useConfirm.js'
import MethodProbes from './MethodProbes.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

const QUOTE = 'shop.QuoteService#quote(I)J'

function probe(overrides = {}) {
  return {
    id: '7',
    method: QUOTE,
    className: 'shop.QuoteService',
    methodName: 'quote',
    descriptor: '(I)J',
    state: 'active',
    endReason: null,
    failure: null,
    removal: null,
    waitingForClass: false,
    async: false,
    maxInvocations: 20,
    windowSeconds: 60,
    requestedAt: '2026-10-04T10:00:00Z',
    endsAt: '2026-10-04T10:01:00Z',
    endedAt: null,
    invocations: 2,
    recorded: 2,
    dropped: 0,
    hits: [
      {
        time: '2026-10-04T10:00:01Z',
        durationMicros: 1530.4,
        threadKind: 'platform',
        requestId: '00000000000000ab',
        outcome: 'returned',
        exceptionType: null,
        caller: 'shop.QuoteController#quote:42'
      },
      {
        time: '2026-10-04T10:00:02Z',
        durationMicros: 12.2,
        threadKind: 'virtual',
        requestId: null,
        outcome: 'threw',
        exceptionType: 'java.lang.IllegalStateException',
        caller: null
      }
    ],
    ...overrides
  }
}

function report(probes, overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    maxActive: 5,
    maxInvocations: 20,
    windowSeconds: 60,
    probes,
    limitations: ['A probe records metadata only: never an argument or a return value.'],
    ...overrides
  }
}

function json(body, status = 200) {
  return {ok: status < 400, status, json: () => Promise.resolve(body)}
}

describe('Method probes', () => {
  let wrapper

  afterEach(() => {
    settleConfirm(false)
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function mountProbes(fetch, props = {}) {
    vi.stubGlobal('fetch', fetch)
    wrapper = mount(MethodProbes, {props, global: {stubs: {RouterLink: RouterLinkStub}}})
  }

  it('lists the run’s probes with their recorded invocations, metadata only', async () => {
    mountProbes(vi.fn(() => Promise.resolve(json(report([probe({state: 'ended', endReason: 'invocations'})])))))
    await flushPromises()

    const row = wrapper.get('.code-paths-probe')
    expect(row.text()).toContain('QuoteService.quote')
    expect(row.get('.code-paths-probe-state').text()).toBe('ended')
    expect(row.text()).toContain('2 of 20 invocations')
    expect(row.text()).toContain('recorded its invocations')
    expect(row.find('.code-paths-probe-stop').exists()).toBe(false)

    await row.get('button').trigger('click')
    const hits = wrapper.findAll('.code-paths-probe-hits tbody tr')
    expect(hits).toHaveLength(2)
    expect(hits[0].text()).toContain('1.5 ms')
    expect(hits[0].text()).toContain('00000000000000ab')
    expect(hits[0].text()).toContain('shop.QuoteController#quote:42')
    expect(hits[1].text()).toContain('threw IllegalStateException')
    expect(hits[1].text()).toContain('virtual')
    expect(wrapper.get('.code-paths-limitations').text()).toContain('metadata only')
  })

  it('starts a probe on the selected method only after confirmation, posting the method', async () => {
    const fetch = vi.fn((url, init) => {
      if (init?.method === 'POST') return Promise.resolve(json(probe({state: 'starting', hits: []})))
      return Promise.resolve(json(report([])))
    })
    mountProbes(fetch, {method: QUOTE})
    await flushPromises()

    await wrapper.get('.code-paths-probe-start').trigger('click')
    expect(confirmState.open).toBe(true)
    expect(confirmState.options.message).toContain('never argument or return values')
    expect(confirmState.options.resource).toBe(QUOTE)
    settleConfirm(true)
    await flushPromises()

    const post = fetch.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(String(post[0])).toContain('api/code-paths/probes')
    expect(JSON.parse(post[1].body)).toEqual({method: QUOTE})
    expect(post[1].headers.get('Content-Type')).toBe('application/json')
  })

  it('posts nothing when the confirmation is cancelled', async () => {
    const fetch = vi.fn(() => Promise.resolve(json(report([]))))
    mountProbes(fetch, {method: QUOTE})
    await flushPromises()

    await wrapper.get('.code-paths-probe-start').trigger('click')
    settleConfirm(false)
    await flushPromises()

    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('shows a refusal with its reason', async () => {
    const fetch = vi.fn((url, init) => {
      if (init?.method === 'POST') {
        return Promise.resolve(json({error: 'Five probes are running: stop one, or wait for one to end.'}, 409))
      }
      return Promise.resolve(json(report([])))
    })
    mountProbes(fetch, {method: QUOTE})
    await flushPromises()

    await wrapper.get('.code-paths-probe-start').trigger('click')
    settleConfirm(true)
    await flushPromises()

    expect(wrapper.text()).toContain('Five probes are running')
  })

  it('is disabled and explains why on a read-only panel', async () => {
    mountProbes(
      vi.fn(() => Promise.resolve(json(report([probe()])))),
      {
        method: 'shop.Other#run()V',
        readOnly: true,
        readOnlyReason: 'Panel is read-only via bootui.read-only=true'
      }
    )
    await flushPromises()

    expect(wrapper.get('.code-paths-probe-start').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.code-paths-probe-stop').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Starting and stopping probes is read-only; a running probe ends by itself within')
    expect(wrapper.text()).toContain('bootui.read-only=true')
  })

  it('does not offer a second probe on a method already probed, and stops a running one', async () => {
    const fetch = vi.fn(() => Promise.resolve(json(report([probe({waitingForClass: true, hits: []})]))))
    mountProbes(fetch, {method: QUOTE})
    await flushPromises()

    expect(wrapper.get('.code-paths-probe-start').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.code-paths-probe-state').text()).toBe('waiting for its class')
    await wrapper.get('.code-paths-probe-stop').trigger('click')
    await flushPromises()

    const stop = fetch.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(String(stop[0])).toContain('api/code-paths/probes/7/stop')
  })

  it('polls only while a probe is live', async () => {
    vi.useFakeTimers()
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json(report([probe()])))
      .mockResolvedValue(json(report([probe({state: 'ended', endReason: 'window'})])))
    mountProbes(fetch)
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('says why probes are unavailable', async () => {
    mountProbes(
      vi.fn(() =>
        Promise.resolve(
          json(report([], {available: false, unavailableReason: "Requires the BootUI agent's code-paths sensor."}))
        )
      ),
      {method: QUOTE}
    )
    await flushPromises()

    expect(wrapper.get('.code-paths-probes-unavailable').text()).toContain('code-paths sensor')
    expect(wrapper.get('.code-paths-probe-start').attributes('disabled')).toBeDefined()
  })
})
