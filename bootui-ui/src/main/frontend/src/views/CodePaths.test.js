import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import CodePaths from './CodePaths.vue'

const router = vi.hoisted(() => ({state: null, replace: null}))

vi.mock('vue-router', async (original) => {
  const {reactive: makeReactive} = await import('vue')
  router.state = makeReactive({query: {}})
  router.replace = vi.fn(({query}) => {
    router.state.query = query
    return Promise.resolve()
  })
  return {...(await original()), useRoute: () => router.state, useRouter: () => ({replace: router.replace})}
})

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
    router.state.query = {}
    router.replace.mockClear()
  })

  function routeRows() {
    return wrapper.findAll('.code-paths-route-row')
  }

  async function openRoute(name) {
    await routeRows()
      .find((row) => row.get('.code-paths-route-name').text() === name)
      .get('.code-paths-route')
      .trigger('click')
    await flushPromises()
  }

  function treeRows() {
    return wrapper.findAll('.code-paths-tree tbody tr')
  }

  it('lists the routes slowest warm median first, closed until one is opened', async () => {
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/code-paths/route?route=GET /api/stream': streamTree,
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    expect(wrapper.get('#code-paths-headline').text()).toBe('2 routes with a call tree')
    expect(wrapper.get('.code-paths-status').text()).toContain('1 without a route')
    expect(wrapper.get('#code-paths-routes-heading').text()).toContain('2 routes, slowest warm median first.')
    const rows = routeRows()
    expect(rows).toHaveLength(2)
    expect(rows[0].get('.code-paths-route-name').text()).toBe('GET /api/quote')
    expect(rows[0].get('.code-paths-verb').text()).toBe('GET')
    expect(rows[0].text()).toContain('SlowPricingService.quote')
    expect(rows[0].get('.code-paths-route-median').text()).toContain('52.4 ms')
    expect(rows[0].get('.code-paths-route-counts').text()).toContain('first request 180 ms')
    expect(rows[0].find('.code-paths-route-bar-top').exists()).toBe(true)
    expect(rows[1].find('.code-paths-assembly').text()).toBe('Assembly only')
    expect(rows.map((row) => row.get('.code-paths-route').attributes('aria-expanded'))).toEqual(['false', 'false'])
    // Nothing is read for a route until it is opened.
    expect(fetch.mock.calls.some(([url]) => String(url).includes('api/code-paths/route'))).toBe(false)
    expect(wrapper.find('.code-paths-tree').exists()).toBe(false)
  })

  it('opens a route’s call tree under its row as an indented treegrid', async () => {
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/code-paths/route?route=GET /api/stream': streamTree,
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()
    await openRoute('GET /api/quote')

    expect(fetch.mock.calls.map(([url]) => decodeURIComponent(String(url)))).toContain(
      'api/code-paths/route?route=GET /api/quote&depth=33&limit=500'
    )
    const row = routeRows()[0]
    expect(row.classes()).toContain('open')
    expect(row.get('.code-paths-route').attributes('aria-expanded')).toBe('true')
    expect(row.get('.code-paths-route').attributes('aria-controls')).toBe(
      row.get('.code-paths-route-detail').attributes('id')
    )
    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/quote')
    expect(router.replace).toHaveBeenLastCalledWith({query: {route: 'GET /api/quote'}})

    const grid = wrapper.get('.code-paths-tree')
    expect(grid.attributes('role')).toBe('treegrid')
    const tree = treeRows()
    expect(tree.map((entry) => entry.find('td').text())).toEqual([
      'Request',
      'QuoteController.quote, on the hot pathhandler',
      'SlowPricingService.quote, on the hot pathhandler',
      'Executor workAsync, shown apart'
    ])
    expect(tree.map((entry) => entry.attributes('aria-level'))).toEqual(['1', '2', '3', '2'])
    expect(tree[0].attributes('aria-expanded')).toBe('true')
    expect(tree[2].attributes('aria-expanded')).toBeUndefined()
    expect(tree[2].get('.code-paths-guides').attributes('style')).toContain('--code-paths-guides: 2')
    expect(tree[2].get('.code-paths-share-value').text()).toBe('97.9 %')
    expect(tree[1].find('.code-paths-share-bar-top').exists()).toBe(true)
    expect(tree[2].classes()).toContain('code-paths-node-hot')
    expect(tree[3].classes()).toContain('code-paths-async')
    expect(tree[3].classes()).not.toContain('code-paths-node-hot')
    expect(tree[2].get('.code-paths-median').text()).toBe('≈ 50.0')
    expect(grid.get('thead').text()).toContain('Median (≈ ms)')
    expect(grid.get('thead').text()).toContain('Share of the handler')
    expect(wrapper.get('.code-paths-tree-summary').text()).toContain('first recorded request 180 ms, kept apart')
    expect(wrapper.get('.code-paths-tree-summary').text()).toContain('own time, 51.2 ms of it in the handler')
    expect(wrapper.get('.code-paths-tree-summary').text()).toContain('approximate (≈), from log2 buckets')
    expect(wrapper.get('.code-paths-exemplars').text()).toContain('00000000000000ab')
    expect(wrapper.find('.code-paths-assembly-note').exists()).toBe(false)

    await routeRows()[0].get('.code-paths-route').trigger('click')
    expect(wrapper.find('.code-paths-tree').exists()).toBe(false)
    expect(router.replace).toHaveBeenLastCalledWith({query: {}})
  })

  it('filters the routes by path, HTTP method, or Java method, and sorts them', async () => {
    ;({wrapper} = mountPanel({
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    await wrapper.get('.code-paths-route-search').setValue('stream')
    expect(routeRows().map((row) => row.get('.code-paths-route-name').text())).toEqual(['GET /api/stream'])
    expect(wrapper.get('#code-paths-routes-heading').text()).toContain('1 of 2 routes')

    await wrapper.get('.code-paths-route-search').setValue('pricingservice')
    expect(routeRows().map((row) => row.get('.code-paths-route-name').text())).toEqual(['GET /api/quote'])

    await wrapper.get('.code-paths-route-search').setValue('nothing-like-it')
    expect(wrapper.get('.code-paths-route-none').text()).toContain('No route matches “nothing-like-it”.')
    await wrapper.get('.code-paths-route-none button').trigger('click')
    expect(routeRows()).toHaveLength(2)

    await wrapper.get('#code-paths-route-sort').setValue('route')
    expect(wrapper.get('#code-paths-routes-heading').text()).toContain('by path')
    expect(routeRows().map((row) => row.get('.code-paths-route-name').text())).toEqual([
      'GET /api/quote',
      'GET /api/stream'
    ])
    await wrapper.get('#code-paths-route-sort').setValue('requests')
    expect(routeRows()[0].get('.code-paths-route-name').text()).toBe('GET /api/quote')
  })

  it('moves between the routes with the arrow keys', async () => {
    ;({wrapper} = mountPanel({'api/code-paths': summary}))
    await flushPromises()
    const toggles = wrapper.findAll('.code-paths-route')
    toggles[0].element.focus = vi.fn()
    toggles[1].element.focus = vi.fn()

    await toggles[0].trigger('keydown', {key: 'ArrowDown'})
    expect(toggles[1].element.focus).toHaveBeenCalled()
    await toggles[1].trigger('keydown', {key: 'Home'})
    expect(toggles[0].element.focus).toHaveBeenCalled()
  })

  it('opens the route and method a link names, and keeps them across a refresh', async () => {
    router.state.query = {route: 'GET /api/quote', method: QUOTE}
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/quote')
    expect(wrapper.get('.code-paths-node-selected').text()).toContain('SlowPricingService.quote')
    expect(wrapper.get('.code-paths-method-detail').text()).toContain(QUOTE)
    const reads = fetch.mock.calls.length

    await wrapper.get('button[aria-label="Refresh panel"]').trigger('click')
    await flushPromises()

    expect(fetch.mock.calls.length).toBeGreaterThan(reads)
    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/quote')
    expect(wrapper.get('.code-paths-node-selected').text()).toContain('SlowPricingService.quote')
    expect(wrapper.find('.code-paths-method-detail').exists()).toBe(true)
  })

  it('opens the route a probed method runs in, from another panel’s Probe link', async () => {
    router.state.query = {probe: QUOTE}
    ;({wrapper} = mountPanel({
      'api/code-paths/probes': {available: true, probes: [], limitations: [], maxActive: 5, shapesAvailable: true},
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()

    expect(wrapper.get('#code-paths-tree-heading').text()).toBe('GET /api/quote')
    expect(wrapper.get('.code-paths-node-selected').text()).toContain('SlowPricingService.quote')
    expect(wrapper.get('#code-paths-probe-target').text()).toContain(QUOTE)
  })

  it('offers a probe on a linked method no route reaches, in the Method probes card', async () => {
    const other = 'shop.Unrouted#run()V'
    router.state.query = {probe: other}
    ;({wrapper} = mountPanel({
      'api/code-paths/probes': {available: true, probes: [], limitations: [], maxActive: 5, shapesAvailable: true},
      'api/code-paths': summary
    }))
    await flushPromises()

    expect(wrapper.find('.code-paths-tree').exists()).toBe(false)
    const card = wrapper.get('.code-paths-probes')
    expect(card.get('#code-paths-probe-target').text()).toContain(other)
    expect(card.find('.code-paths-probe-start').exists()).toBe(true)
  })

  /** Holds every tree read until the test settles it. */
  function mountWithHeldTrees() {
    const held = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        const path = decodeURIComponent(String(url))
        if (!path.includes('api/code-paths/route')) return Promise.resolve(jsonResponse(summary))
        const body = path.includes('GET /api/stream') ? streamTree : quoteTree
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
    await routeRows()[1].get('.code-paths-route').trigger('click')
    await routeRows()[0].get('.code-paths-route').trigger('click')
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
    expect(wrapper.find('.code-paths-route-detail .alert-danger').exists()).toBe(false)
    expect(wrapper.get('.code-paths-tree').text()).toContain('SlowPricingService.quote')
  })

  it('shows a method’s detail and Probe this method under its row, and follows a route', async () => {
    ;({wrapper} = mountPanel({
      'api/code-paths/probes': {available: true, probes: [], limitations: [], maxActive: 5, shapesAvailable: true},
      'api/code-paths/route?route=GET /api/stream': streamTree,
      'api/code-paths/route?route=GET /api/quote': quoteTree,
      'api/code-paths': summary
    }))
    await flushPromises()
    await openRoute('GET /api/quote')

    const methodRow = treeRows().find((row) => row.text().includes('SlowPricingService.quote'))
    await methodRow.trigger('click')
    await flushPromises()
    expect(methodRow.attributes('aria-selected')).toBe('true')
    expect(router.replace).toHaveBeenLastCalledWith({query: {route: 'GET /api/quote', method: QUOTE}})
    const rows = treeRows()
    const detailRow = rows[rows.findIndex((row) => row.element === methodRow.element) + 1]
    expect(detailRow.classes()).toContain('code-paths-detail-row')
    const detail = detailRow.get('.code-paths-method-detail')
    expect(detail.get('.code-paths-callers').text()).toBe('QuoteController.quote')
    expect(detail.get('.code-paths-reach').text()).toContain('GET /api/stream')
    expect(detail.get('.code-paths-method-stats').text()).toContain('Self50.1 ms')
    // The probe action sits in the method's detail, not in the card below the routes.
    expect(detail.get('#code-paths-probe-target').text()).toContain(QUOTE)
    expect(detail.find('.code-paths-probe-start').exists()).toBe(true)
    expect(wrapper.get('.code-paths-probes').find('.code-paths-probe-start').exists()).toBe(false)

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
    expect(wrapper.find('.code-paths-probe-start').exists()).toBe(false)
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
    await openRoute('GET /api/quote')

    const rows = treeRows()
    expect(rows).toHaveLength(6)
    const calls = wrapper.findAll('.code-paths-call')
    expect(calls).toHaveLength(2)
    expect(rows[3].classes()).toContain('code-paths-call')
    expect(calls[0].attributes('aria-level')).toBe('4')
    expect(calls[0].find('td').text()).toContain('SQL statements')
    expect(calls[0].find('td').text()).toContain(', issued while SlowPricingService.quote was open')
    expect(calls[0].findAll('td')[1].text()).toBe('6')
    expect(calls[0].findAll('td')[2].text()).toBe('12.5')
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
