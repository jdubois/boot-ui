import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

const routeQuery = {}

vi.mock('vue-router', () => ({useRoute: () => ({query: routeQuery})}))

import HttpClients from './HttpClients.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function setting(overrides = {}) {
  return {
    category: 'TIMEOUT',
    name: 'Connect timeout',
    value: '2s',
    provenance: 'CLIENT',
    source: 'spring.http.serviceclient.orders.connect-timeout',
    ...overrides
  }
}

function client(overrides = {}) {
  return {
    id: 'http_interface:orders',
    name: 'orders',
    kind: 'HTTP_INTERFACE',
    kindLabel: 'HTTP Interface',
    framework: 'Spring HTTP Interface',
    declaredInterface: 'com.example.OrdersClient',
    configKey: 'orders',
    configuredBaseUrl: 'https://orders.example.com',
    resolvedBaseUrl: 'https://orders.example.com',
    baseUrlStatus: 'RESOLVED',
    baseUrlProvenance: 'CLIENT',
    baseUrlSource: 'spring.http.serviceclient.orders.base-url',
    settings: [setting()],
    observedCalls: [],
    observedCallsStatus: 'NO_CALLS',
    ...overrides
  }
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    total: 1,
    valueExposure: 'MASKED',
    observedCallsAvailable: true,
    observedCallsUnavailableReason: null,
    clients: [client()],
    warnings: [],
    ...overrides
  }
}

function stubFetch(body) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(jsonResponse(body)))
  )
}

async function render(body) {
  stubFetch(body)
  const wrapper = mount(HttpClients)
  await flushPromises()
  return wrapper
}

describe('HttpClients', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
    routeQuery.q = undefined
  })

  it('renders each registered client with its kind, interface and resolved base URL', async () => {
    wrapper = await render(report())

    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).toContain('HTTP Interface')
    expect(wrapper.text()).toContain('com.example.OrdersClient')
    expect(wrapper.text()).toContain('https://orders.example.com')
    expect(wrapper.text()).toContain('spring.http.serviceclient.orders.base-url')
  })

  it('shows the unavailable reason instead of an empty table when no client is registered', async () => {
    wrapper = await render(
      report({
        available: false,
        unavailableReason: 'No declarative HTTP client found.',
        total: 0,
        clients: []
      })
    )

    expect(wrapper.text()).toContain('No declarative HTTP client found.')
    expect(wrapper.find('input[aria-label="Filter HTTP clients"]').exists()).toBe(false)
  })

  it('flags an unresolved base URL and surfaces the report warning', async () => {
    wrapper = await render(
      report({
        warnings: ['1 client has a base URL that could not be resolved. Check for unresolved property placeholders.'],
        clients: [
          client({
            configuredBaseUrl: 'https://${orders.host}/v1',
            resolvedBaseUrl: null,
            baseUrlStatus: 'UNRESOLVED'
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('Unresolved')
    expect(wrapper.text()).toContain('https://${orders.host}/v1')
    expect(wrapper.text()).toContain('could not be resolved')
  })

  it('renders a client with no declared base URL without inventing one', async () => {
    wrapper = await render(
      report({
        clients: [
          client({
            id: 'rest_client_builder:restClientBuilder',
            name: 'restClientBuilder',
            kind: 'REST_CLIENT_BUILDER',
            kindLabel: 'RestClient builder',
            declaredInterface: null,
            configKey: null,
            configuredBaseUrl: null,
            resolvedBaseUrl: null,
            baseUrlStatus: 'NOT_DECLARED',
            baseUrlProvenance: 'UNAVAILABLE',
            baseUrlSource: null
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('Not declared')
    expect(wrapper.text()).toContain('Not applicable')
    expect(wrapper.text()).not.toContain('undefined')
    expect(wrapper.text()).not.toContain('null')
  })

  it('groups effective settings by category and shows their provenance and source once expanded', async () => {
    wrapper = await render(
      report({
        clients: [
          client({
            settings: [
              setting(),
              setting({
                name: 'Read timeout',
                value: '30s',
                provenance: 'APPLICATION',
                source: 'spring.http.clients.read-timeout'
              }),
              setting({category: 'TLS', name: 'SSL bundle', value: null, provenance: 'UNAVAILABLE', source: null})
            ]
          })
        ]
      })
    )

    expect(wrapper.text()).not.toContain('Connect timeout')

    await wrapper.find('button[aria-expanded="false"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Timeouts')
    expect(wrapper.text()).toContain('TLS')
    expect(wrapper.text()).toContain('Client override')
    expect(wrapper.text()).toContain('Application default')
    expect(wrapper.text()).toContain('Not exposed')
    expect(wrapper.text()).toContain('spring.http.clients.read-timeout')
  })

  it('explains why observed calls are not attributed rather than showing an empty table', async () => {
    wrapper = await render(
      report({
        clients: [client({observedCallsStatus: 'NOT_ATTRIBUTABLE'})]
      })
    )

    await wrapper.find('button[aria-expanded="false"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Not attributable')
    expect(wrapper.text()).toContain('exactly one client')
  })

  it('lists the retained calls linked to a client', async () => {
    wrapper = await render(
      report({
        clients: [
          client({
            observedCallsStatus: 'LINKED',
            observedCalls: [{method: 'GET', path: '/v1/orders', executions: 4, maxDurationMillis: 1500}]
          })
        ]
      })
    )

    await wrapper.find('button[aria-expanded="false"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('/v1/orders')
    expect(wrapper.text()).toContain('1.50 s')
  })

  it('says the retained window found nothing rather than claiming the client is idle', async () => {
    wrapper = await render(
      report({
        clients: [client({observedCallsStatus: 'NO_CALLS'})]
      })
    )

    await wrapper.find('button[aria-expanded="false"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('None in top calls')
  })

  it('reports why cross-linking is unavailable when the REST Client trace has no instrumented client', async () => {
    wrapper = await render(
      report({
        observedCallsAvailable: false,
        observedCallsUnavailableReason: 'REST Client trace is not available on this runtime.',
        clients: [client({observedCallsStatus: 'UNAVAILABLE'})]
      })
    )

    expect(wrapper.text()).toContain('REST Client trace is not available on this runtime.')
  })

  it('filters clients by name, interface and base URL', async () => {
    wrapper = await render(
      report({
        total: 2,
        clients: [
          client(),
          client({id: 'http_interface:billing', name: 'billing', declaredInterface: 'com.example.BillingClient'})
        ]
      })
    )

    await wrapper.find('input[aria-label="Filter HTTP clients"]').setValue('billing')
    await flushPromises()

    expect(wrapper.text()).toContain('billing')
    expect(wrapper.text()).not.toContain('com.example.OrdersClient')
    expect(wrapper.text()).toContain('1 / 2 clients')
  })

  it('prefills the filter box from the ?q= deep-link query parameter', async () => {
    routeQuery.q = 'billing'

    wrapper = await render(
      report({
        total: 2,
        clients: [client(), client({id: 'http_interface:billing', name: 'billing'})]
      })
    )

    expect(wrapper.find('input[aria-label="Filter HTTP clients"]').element.value).toBe('billing')
    expect(wrapper.text()).toContain('1 / 2 clients')
  })

  it('says so when the filter matches nothing', async () => {
    wrapper = await render(report())

    await wrapper.find('input[aria-label="Filter HTTP clients"]').setValue('nothing-matches-this')
    await flushPromises()

    expect(wrapper.text()).toContain('No HTTP client matches this filter.')
  })

  it('shows a load error instead of a blank panel', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('boom')))
    )

    wrapper = mount(HttpClients)
    await flushPromises()

    expect(wrapper.text()).toContain('Unable to load HTTP clients')
  })
})
