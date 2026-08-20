import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Grpc from './Grpc.vue'
import PanelHeader from './components/PanelHeader.vue'

const metrics = (overrides = {}) => ({
  available: false,
  callCount: 0,
  activeCalls: null,
  totalDurationMs: null,
  maxDurationMs: null,
  averageDurationMs: null,
  statusCounts: [],
  ...overrides
})

function report() {
  return {
    available: true,
    unavailableReason: null,
    integration: 'Spring Boot gRPC',
    serverCount: 1,
    serviceCount: 2,
    methodCount: 3,
    channelCount: 2,
    metricsAvailable: true,
    metricsUnavailableReason: null,
    servers: [
      {
        id: 'spring-grpc-server',
        name: 'gRPC server',
        address: '*',
        port: 9090,
        transportSecurity: 'PLAINTEXT',
        reflectionEnabled: true,
        maxInboundMessageSize: 4194304,
        maxInboundMetadataSize: 8192,
        keepAlive: [{name: 'Time', value: '30s'}],
        settings: [{name: 'Health service', value: 'true'}],
        interceptors: ['com.example.AuditInterceptor'],
        serviceCount: 2,
        methodCount: 3,
        servicesTruncated: false,
        services: [
          {
            name: 'shop.Inventory',
            implementationClass: 'com.example.InventoryService',
            interceptors: [],
            methodCount: 2,
            methodsTruncated: false,
            metrics: metrics({available: true, callCount: 12}),
            methods: [
              {
                name: 'Get',
                fullName: 'shop.Inventory/Get',
                type: 'UNARY',
                metrics: metrics({
                  available: true,
                  callCount: 12,
                  activeCalls: 1,
                  averageDurationMs: 4.5,
                  maxDurationMs: 21,
                  statusCounts: [
                    {status: 'OK', count: 11},
                    {status: 'UNAVAILABLE', count: 1}
                  ]
                })
              },
              {
                name: 'Watch',
                fullName: 'shop.Inventory/Watch',
                type: 'SERVER_STREAMING',
                metrics: metrics()
              }
            ]
          },
          {
            name: 'shop.Pricing',
            implementationClass: 'com.example.PricingService',
            interceptors: [],
            methodCount: 1,
            methodsTruncated: false,
            metrics: metrics(),
            methods: [
              {
                name: 'Quote',
                fullName: 'shop.Pricing/Quote',
                type: 'BIDI_STREAMING',
                metrics: metrics()
              }
            ]
          }
        ]
      }
    ],
    channels: [
      {
        name: 'billing',
        target: 'static://localhost:9091',
        authority: 'localhost:9091',
        loadBalancingPolicy: 'round_robin',
        transportSecurity: 'PLAINTEXT',
        retryEnabled: null,
        maxInboundMessageSize: 2097152,
        maxInboundMetadataSize: null,
        keepAlive: [],
        settings: [],
        interceptors: []
      },
      {
        name: 'payments',
        target: 'dns:///payments.internal:443',
        authority: 'payments.internal:443',
        loadBalancingPolicy: null,
        transportSecurity: 'TLS',
        retryEnabled: true,
        maxInboundMessageSize: null,
        maxInboundMetadataSize: null,
        keepAlive: [],
        settings: [],
        interceptors: []
      }
    ],
    clientServices: [
      {
        name: 'payments.Payments',
        implementationClass: null,
        interceptors: [],
        methodCount: 1,
        methodsTruncated: false,
        metrics: metrics({available: true, callCount: 4}),
        methods: [
          {
            name: 'Charge',
            fullName: 'payments.Payments/Charge',
            type: 'UNARY',
            metrics: metrics({available: true, callCount: 4, averageDurationMs: 8.25, maxDurationMs: 30})
          }
        ]
      }
    ],
    warnings: []
  }
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('gRPC panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('does not call the API when the manifest already reports gRPC unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Grpc, {
      props: {
        panel: {
          id: 'grpc',
          enabled: true,
          available: false,
          unavailableReason: 'Not available: this application does not use gRPC.'
        }
      }
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('this application does not use gRPC')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(false)
  })

  it('explains the backend unavailable reason without inventing a registry', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          available: false,
          unavailableReason: 'No supported gRPC integration was detected.',
          integration: null,
          serverCount: 0,
          serviceCount: 0,
          methodCount: 0,
          channelCount: 0,
          metricsAvailable: false,
          metricsUnavailableReason: 'No native gRPC metrics were found.',
          servers: [],
          channels: [],
          clientServices: [],
          warnings: []
        })
      )
    )
    wrapper = mount(Grpc)
    await flushPromises()

    expect(wrapper.text()).toContain('No supported gRPC integration was detected.')
    expect(wrapper.text()).not.toContain('Client channels')
  })

  it('renders servers, services, methods, channels and observed client calls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))
    wrapper = mount(Grpc)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Spring Boot gRPC')
    expect(text).toContain('shop.Inventory')
    expect(text).toContain('Get')
    expect(text).toContain('Server streaming')
    expect(text).toContain('Bidirectional streaming')
    expect(text).toContain('4.0 MiB')
    expect(text).toContain('OK 11')
    expect(text).toContain('UNAVAILABLE 1')
    expect(text).toContain('billing')
    expect(text).toContain('static://localhost:9091')
    expect(text).toContain('payments.Payments')
    expect(text).toContain('Charge')
  })

  it('filters services, methods and channels together', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))
    wrapper = mount(Grpc)
    await flushPromises()

    await wrapper.get('input.form-control').setValue('pricing')
    expect(wrapper.text()).toContain('shop.Pricing')
    expect(wrapper.text()).not.toContain('shop.Inventory')
    expect(wrapper.text()).not.toContain('static://localhost:9091')

    await wrapper.get('input.form-control').setValue('nothing-matches')
    expect(wrapper.text()).toContain('No gRPC services, methods or channels match this filter.')
  })

  it('says so when the application publishes no gRPC metrics', async () => {
    const withoutMetrics = report()
    withoutMetrics.metricsAvailable = false
    withoutMetrics.metricsUnavailableReason =
      'No native gRPC metrics were found. BootUI never installs its own gRPC interceptor.'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(withoutMetrics)))
    wrapper = mount(Grpc)
    await flushPromises()

    expect(wrapper.text()).toContain('BootUI never installs its own gRPC interceptor')
  })

  it('surfaces truncation and registry warnings instead of hiding them', async () => {
    const truncated = report()
    truncated.warnings = ['Only the first 20 gRPC servers are shown.']
    truncated.servers[0].servicesTruncated = true
    truncated.servers[0].serviceCount = 40
    truncated.servers[0].services[0].methodsTruncated = true
    truncated.servers[0].services[0].methodCount = 30
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(truncated)))
    wrapper = mount(Grpc)
    await flushPromises()

    expect(wrapper.text()).toContain('Only the first 20 gRPC servers are shown.')
    expect(wrapper.text()).toContain('Only the first 2 of 40 services are shown.')
    expect(wrapper.text()).toContain('Only the first 2 of 30 methods are shown.')

    await wrapper.get('input.form-control').setValue('shop.Pricing')
    expect(wrapper.text()).toContain('Only the first 2 of 40 services are shown.')
  })

  it('renders channel detail, and never appends a port to a unix socket address', async () => {
    const unixSocket = report()
    unixSocket.servers[0].address = 'unix:/tmp/bootui-grpc.sock'
    unixSocket.servers[0].port = 9090
    unixSocket.channels[0].keepAlive = [{name: 'Time', value: '20s'}]
    unixSocket.channels[0].settings = [{name: 'Name resolver', value: 'static'}]
    unixSocket.channels[0].interceptors = ['com.example.TracingClientInterceptor']
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(unixSocket)))
    wrapper = mount(Grpc)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('unix:/tmp/bootui-grpc.sock')
    expect(text).not.toContain('unix:/tmp/bootui-grpc.sock:9090')
    expect(text).toContain('localhost:9091')
    expect(text).toContain('Keepalive time')
    expect(text).toContain('Name resolver')
    expect(text).toContain('com.example.TracingClientInterceptor')
  })

  it('does not claim a filter mismatch when no filter is active', async () => {
    const empty = report()
    empty.serverCount = 0
    empty.channelCount = 0
    empty.servers = []
    empty.channels = []
    empty.clientServices = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(empty)))
    wrapper = mount(Grpc)
    await flushPromises()

    expect(wrapper.text()).not.toContain('match this filter')
  })

  it('reports an empty registry rather than a blank panel', async () => {
    const empty = report()
    empty.serverCount = 0
    empty.serviceCount = 0
    empty.methodCount = 0
    empty.channelCount = 0
    empty.servers = []
    empty.channels = []
    empty.clientServices = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(empty)))
    wrapper = mount(Grpc)
    await flushPromises()

    expect(wrapper.text()).toContain('No gRPC servers or client channels are configured.')
  })
})
