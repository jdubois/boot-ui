import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import CodePaths from './CodePaths.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

const QUOTE = 'shop.SlowPricingService#quote(Ljava/lang/String;)I'
const CONTROLLER = 'shop.QuoteController#quote(Ljava/lang/String;)I'
const STREAM = 'shop.StreamService#prices()Lreactor/core/publisher/Flux;'

const summary = {
  available: true,
  unavailableReason: null,
  status: {
    generation: 4,
    fragments: 40,
    staleFragments: 0,
    malformedFragments: 0,
    settledTrees: 12,
    routedTrees: 11,
    unroutedTrees: 1,
    routes: 2,
    routeNodes: 9,
    routeNodeBudget: 100000,
    foldedCalls: 0
  },
  routes: [
    {
      route: 'GET /api/quote',
      warmRequests: 5,
      firstRequestMillis: 180.2,
      p50Millis: 52.4,
      p95Millis: 58.1,
      meanMillis: 53,
      asyncMillis: 0,
      assemblyOnly: false,
      nodes: 4,
      topMethods: [
        {method: QUOTE, className: 'shop.SlowPricingService', methodName: 'quote', selfMillis: 50.1, share: 94.5}
      ]
    },
    {
      route: 'GET /api/stream',
      warmRequests: 3,
      firstRequestMillis: 20,
      p50Millis: 1.2,
      p95Millis: 1.6,
      meanMillis: 1.3,
      asyncMillis: 0,
      assemblyOnly: true,
      nodes: 2,
      topMethods: []
    }
  ],
  excludedMethods: [
    {method: 'shop.Money#amount()J', reason: 'Called more than 50,000 times a second under 2 µs each.'}
  ],
  limitations: ['A method’s self time includes the SQL, REST client, cache, and AI calls it waited on.']
}

function node(id, parent, depth, kind, method, extra = {}) {
  return {
    id,
    parent,
    depth,
    kind,
    method,
    className: null,
    methodName: null,
    phase: kind === 'METHOD' ? 'HANDLER' : null,
    async: false,
    requests: 5,
    callsPerRequest: 1,
    totalMillis: 50,
    selfMillis: 1,
    share: kind === 'REQUEST' ? null : 100,
    p50Millis: 50,
    p95Millis: 55,
    children: 1,
    ...extra
  }
}

const quoteTree = {
  available: true,
  unavailableReason: null,
  route: 'GET /api/quote',
  found: true,
  assemblyOnly: false,
  warmRequests: 5,
  firstRequestMillis: 180.2,
  firstRequestId: '0000000000000001',
  ownMillis: 53,
  handlerMillis: 51.2,
  shareOf: 'handler',
  depth: 33,
  nodes: [
    node(0, null, 0, 'REQUEST', null, {totalMillis: 53, selfMillis: 1.8}),
    node(1, 0, 1, 'METHOD', CONTROLLER, {totalMillis: 51.2, selfMillis: 1.1, share: 100}),
    node(2, 1, 2, 'METHOD', QUOTE, {totalMillis: 50.1, selfMillis: 50.1, share: 97.9}),
    node(3, 0, 1, 'ASYNC', null, {async: true, share: null, phase: null, totalMillis: 3, selfMillis: 3})
  ],
  methods: [
    {method: CONTROLLER, callers: ['REQUEST'], routes: ['GET /api/quote']},
    {method: QUOTE, callers: [CONTROLLER], routes: ['GET /api/quote', 'GET /api/stream']}
  ],
  exemplarRequestIds: ['00000000000000ab'],
  page: {total: 4, matched: 4, offset: 0, limit: 500, returned: 4, hasMore: false},
  limitations: []
}

const streamTree = {
  ...quoteTree,
  route: 'GET /api/stream',
  assemblyOnly: true,
  handlerMillis: null,
  shareOf: 'request',
  nodes: [node(0, null, 0, 'REQUEST', null), node(1, 0, 1, 'METHOD', STREAM, {phase: null, share: 90})],
  methods: [{method: STREAM, callers: ['REQUEST'], routes: ['GET /api/stream']}],
  exemplarRequestIds: []
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function routeFetch(responses) {
  return vi.fn((url) => {
    const path = decodeURIComponent(String(url))
    const key = Object.keys(responses)
      .sort((a, b) => b.length - a.length)
      .find((prefix) => path.includes(prefix))
    return Promise.resolve(jsonResponse(responses[key]))
  })
}

function mountPanel(responses, props = {}) {
  const fetch = routeFetch(responses)
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(CodePaths, {props, global: {stubs: {RouterLink: RouterLinkStub}}})
  return {wrapper, fetch}
}

describe('Code Paths panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('ranks the routes by warm median and opens the slowest route as an indented tree', async () => {
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/code-paths/route?route=GET /api/stream': streamTree,
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    expect(wrapper.get('#code-paths-headline').text()).toBe('2 routes with a call tree')
    expect(wrapper.get('.code-paths-status').text()).toContain('1 without a route')
    const rows = wrapper.findAll('.code-paths-routes tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('GET /api/quote')
    expect(rows[0].text()).toContain('SlowPricingService.quote')
    expect(rows[0].text()).toContain('52.4')
    expect(rows[1].find('.code-paths-assembly').text()).toBe('Assembly only')
    expect(rows[0].get('button').attributes('aria-pressed')).toBe('true')
    expect(fetch.mock.calls.map(([url]) => decodeURIComponent(String(url)))).toContain(
      'api/code-paths/route?route=GET /api/quote&depth=33&limit=500'
    )

    const tree = wrapper.findAll('.code-paths-tree tbody tr')
    expect(tree.map((row) => row.find('td').text())).toEqual([
      'Request',
      'QuoteController.quotehandler',
      'SlowPricingService.quotehandler',
      'Executor workAsync, shown apart'
    ])
    expect(tree[2].get('.code-paths-indent').attributes('style')).toContain('padding-inline-start: 2.2rem')
    expect(tree[2].get('.code-paths-share-value').text()).toBe('97.9 %')
    expect(tree[1].find('.code-paths-share-bar-top').exists()).toBe(true)
    expect(tree[3].classes()).toContain('code-paths-async')
    expect(tree[2].get('.code-paths-median').text()).toBe('≈ 50.0')
    expect(wrapper.get('.code-paths-tree thead').text()).toContain('Median (≈ ms)')
    expect(wrapper.get('.code-paths-tree-summary').text()).toContain('first recorded request 180 ms, kept apart')
    expect(wrapper.get('.code-paths-tree-summary').text()).toContain('approximate (≈), from log2 buckets')
    expect(wrapper.get('.code-paths-routes thead').text()).toContain('First request (ms)')
    expect(wrapper.get('.code-paths-tree thead').text()).toContain('Share of the handler')
    expect(wrapper.get('.code-paths-exemplars').text()).toContain('00000000000000ab')
    expect(wrapper.find('.code-paths-assembly-note').exists()).toBe(false)
  })

  /** Answers the first tree read at once and holds every later one until the test settles it. */
  function mountWithHeldTrees() {
    const held = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        const path = decodeURIComponent(String(url))
        if (!path.includes('api/code-paths/route')) return Promise.resolve(jsonResponse(summary))
        const body = path.includes('GET /api/stream') ? streamTree : quoteTree
        if (!held.initial) {
          held.initial = true
          return Promise.resolve(jsonResponse(body))
        }
        return new Promise((resolve) => {
          held.push({
            answer: () => resolve(jsonResponse(body)),
            fail: () => resolve({ok: false, status: 500, json: () => Promise.resolve({message: 'boom'})})
          })
        })
      })
    )
    wrapper = mount(CodePaths, {global: {stubs: {RouterLink: RouterLinkStub}}})
    return held
  }

  async function selectStreamThenQuote() {
    await wrapper.findAll('.code-paths-routes tbody tr')[1].get('button').trigger('click')
    await wrapper.findAll('.code-paths-routes tbody tr')[0].get('button').trigger('click')
    await flushPromises()
  }

  it('keeps the newest route’s tree when an older route answers last', async () => {
    const held = mountWithHeldTrees()
    await flushPromises()
    await selectStreamThenQuote()
    expect(held).toHaveLength(2)

    held[1].answer()
    await flushPromises()
    held[0].answer()
    await flushPromises()

    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/quote')
    expect(wrapper.get('.code-paths-tree').text()).toContain('SlowPricingService.quote')
    expect(wrapper.get('.code-paths-tree').text()).not.toContain('StreamService.prices')
  })

  it('does not show an older route’s failure under the newer route', async () => {
    const held = mountWithHeldTrees()
    await flushPromises()
    await selectStreamThenQuote()

    held[1].answer()
    await flushPromises()
    held[0].fail()
    await flushPromises()

    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/quote')
    expect(wrapper.find('section[aria-labelledby="code-paths-tree-heading"] .alert-danger').exists()).toBe(false)
    expect(wrapper.get('.code-paths-tree').text()).toContain('SlowPricingService.quote')
  })

  it('shows a method’s callers and the routes that reach it, and follows a route', async () => {
    ;({wrapper} = mountPanel({
      'api/code-paths/route?route=GET /api/stream': streamTree,
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    const method = wrapper.findAll('.code-paths-method').find((button) => button.text() === 'SlowPricingService.quote')
    await method.trigger('click')
    expect(method.attributes('aria-pressed')).toBe('true')
    const detail = wrapper.get('.code-paths-method-detail')
    expect(detail.get('.code-paths-callers').text()).toBe('QuoteController.quote')
    expect(detail.get('.code-paths-reach').text()).toContain('GET /api/stream')
    // The selected method is the one Probe this method offers (M5-8).
    expect(wrapper.get('#code-paths-probe-target').text()).toBe(QUOTE)
    expect(wrapper.find('.code-paths-probe-start').exists()).toBe(true)

    await detail
      .findAll('.code-paths-reach button')
      .find((button) => button.text() === 'GET /api/stream')
      .trigger('click')
    await flushPromises()

    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/stream')
    expect(wrapper.get('.code-paths-assembly-note').text()).toContain('Assembly only.')
    expect(wrapper.get('.code-paths-assembly-note').text()).toContain(
      'ran on an event loop, returned a reactive or asynchronous result, or BootUI could not tell where its work ran'
    )
    expect(wrapper.get('.code-paths-tree thead').text()).toContain('Share of the request')
    expect(wrapper.find('.code-paths-method-detail').exists()).toBe(false)
  })

  it('shows the SQL, REST, cache, and AI calls a method issued under it', async () => {
    const withCalls = {
      ...quoteTree,
      nodes: quoteTree.nodes.map((entry) =>
        entry.method === QUOTE
          ? {
              ...entry,
              calls: [
                {kind: 'SQL', callsPerRequest: 6, totalMillis: 12.5},
                {kind: 'CACHE', callsPerRequest: 1, totalMillis: null}
              ]
            }
          : {...entry, calls: []}
      )
    }
    ;({wrapper} = mountPanel({
      'api/code-paths/route?route=GET /api/quote': withCalls,
      'api/code-paths/route?route=GET /api/stream': streamTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    const rows = wrapper.findAll('.code-paths-tree tbody tr')
    expect(rows).toHaveLength(6)
    const calls = wrapper.findAll('.code-paths-call')
    expect(calls).toHaveLength(2)
    expect(rows[3].classes()).toContain('code-paths-call')
    expect(calls[0].find('td').text()).toBe('SQL statements, issued while SlowPricingService.quote was open')
    expect(calls[0].findAll('td')[1].text()).toBe('6')
    expect(calls[0].findAll('td')[2].text()).toBe('12.5')
    expect(calls[0].get('.code-paths-indent').attributes('style')).toContain('padding-inline-start: 3.3rem')
    expect(calls[0].get('.code-paths-call-label').attributes('title')).toBe(
      'Issued while SlowPricingService.quote was the innermost instrumented method open on their thread'
    )
    expect(calls[1].find('td').text()).toContain('Cache access')
    expect(calls[1].findAll('td')[2].text()).toBe('—')
    // A commit flush runs after the @Transactional method returned, under the method that called it.
    expect(wrapper.get('.code-paths-tree-summary').text()).toContain(
      'A statement Hibernate flushes at commit runs after the @Transactional method returned, so it shows under the method that called it.'
    )
  })

  it('compares the calls observed between beans with their declared dependencies in Beans at runtime', async () => {
    const beans = {
      available: true,
      unavailableReason: null,
      beansAvailable: true,
      edges: [
        {
          from: 'quoteController',
          fromType: 'shop.QuoteController',
          to: 'quoteService',
          toType: 'shop.QuoteService',
          declared: true,
          observed: true,
          calls: 12,
          observable: true,
          unobservableReason: null
        },
        {
          from: 'quoteService',
          fromType: 'shop.QuoteService',
          to: 'auditService',
          toType: 'shop.AuditService',
          declared: true,
          observed: false,
          calls: 0,
          observable: true,
          unobservableReason: null
        },
        {
          from: 'quoteService',
          fromType: 'shop.QuoteService',
          to: 'ownerRepository',
          toType: 'jdk.proxy2.$Proxy91',
          declared: true,
          observed: false,
          calls: 0,
          observable: false,
          unobservableReason: "The called bean's class is not one the code-paths sensor instruments."
        }
      ],
      observedEdges: 1,
      declaredEdges: 3,
      notCalled: 1,
      omitted: 0,
      limitations: ['Calls come from this run’s route trees, first requests included.']
    }
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/code-paths/beans': beans,
      'api/code-paths/route': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => String(url))).not.toContain('api/code-paths/beans')

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs.map((tab) => tab.text())).toEqual(['Routes', 'Beans at runtime'])
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    expect(tabs[1].attributes('tabindex')).toBe('-1')
    await tabs[0].trigger('keydown', {key: 'ArrowRight'})
    await flushPromises()

    expect(wrapper.findAll('[role="tab"]')[1].attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(1)
    expect(wrapper.get('[role="tabpanel"]').attributes('aria-labelledby')).toBe('code-paths-tab-beans')
    expect(fetch.mock.calls.map(([url]) => String(url))).toContain('api/code-paths/beans')
    expect(wrapper.get('.code-paths-beans-summary').text()).toContain('1 call pair between beans observed')
    expect(wrapper.get('.code-paths-beans-summary').text()).toContain('of which 1 not called in this run')
    const rows = wrapper.findAll('.code-paths-beans-table tbody tr')
    expect(rows.map((row) => row.findAll('td')[3].text())).toEqual(['12', 'Not called in this run', 'Not observable'])
    // A dependency a call into which would not be observed never reads as not called, and says why.
    expect(rows[2].get('.code-paths-not-observable').attributes('title')).toBe(
      "The called bean's class is not one the code-paths sensor instruments."
    )
    expect(wrapper.text()).not.toMatch(/unused/i)

    await wrapper.get('#code-paths-not-called').setValue(true)
    const filtered = wrapper.findAll('.code-paths-beans-table tbody tr')
    expect(filtered).toHaveLength(1)
    expect(filtered[0].text()).toContain('auditService')
  })

  it('says why Beans at runtime is unavailable', async () => {
    ;({wrapper} = mountPanel({
      'api/code-paths/beans': {
        available: false,
        unavailableReason: "Requires the BootUI agent's code-paths sensor.",
        beansAvailable: false,
        edges: [],
        observedEdges: 0,
        declaredEdges: 0,
        notCalled: 0,
        omitted: 0,
        limitations: []
      },
      'api/code-paths/route': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()

    expect(wrapper.get('.code-paths-beans-unavailable').text()).toContain(
      "Requires the BootUI agent's code-paths sensor."
    )
  })

  it('lists the excluded methods with why', async () => {
    ;({wrapper} = mountPanel({
      'api/code-paths/route': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    expect(wrapper.get('.code-paths-excluded').text()).toContain('Money.amount')
    expect(wrapper.get('.code-paths-excluded').text()).toContain('50,000 times a second')
  })

  it('says when no route has a tree yet', async () => {
    ;({wrapper} = mountPanel({'api/code-paths': {...summary, routes: [], excludedMethods: []}}))
    await flushPromises()

    expect(wrapper.get('.code-paths-empty').text()).toContain('No route has a call tree yet')
    expect(wrapper.get('.code-paths-excluded-heading, #code-paths-excluded-heading').text()).toBe('Excluded methods')
  })

  it('is unavailable without the agent, with the reason and a link to the Java Agent panel', async () => {
    ;({wrapper} = mountPanel({
      'api/code-paths': {
        available: false,
        unavailableReason: "Requires the BootUI agent's code-paths sensor.",
        status: null,
        routes: [],
        excludedMethods: [],
        limitations: []
      }
    }))
    await flushPromises()

    expect(wrapper.get('.code-paths-unavailable').text()).toContain("Requires the BootUI agent's code-paths sensor.")
    expect(wrapper.get('.code-paths-agent-link').text()).toBe('Open the Java Agent panel')
    expect(wrapper.find('.code-paths-routes').exists()).toBe(false)
  })

  it('shows the manifest’s reason without reading when the panel is unavailable', async () => {
    let fetch
    ;({wrapper, fetch} = mountPanel(
      {'api/code-paths': summary},
      {panel: {id: 'code-paths', available: false, unavailableReason: "Requires the BootUI agent's code-paths sensor."}}
    ))
    await flushPromises()

    expect(wrapper.text()).toContain("Requires the BootUI agent's code-paths sensor.")
    expect(wrapper.get('.code-paths-agent-link').text()).toBe('Open the Java Agent panel')
    expect(fetch).not.toHaveBeenCalled()
  })
})
