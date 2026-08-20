// @ts-check
import {expect, test} from './fixtures.js'

// The sample app deliberately has no gRPC on its classpath, so the live panel proves the unavailable
// path. A routed report proves the rendering path without ever starting a server or opening a channel.
const grpcReport = {
  available: true,
  unavailableReason: null,
  integration: 'Spring Boot gRPC',
  serverCount: 1,
  serviceCount: 1,
  methodCount: 2,
  channelCount: 1,
  metricsAvailable: true,
  metricsUnavailableReason: null,
  warnings: [],
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
      serviceCount: 1,
      methodCount: 2,
      servicesTruncated: false,
      services: [
        {
          name: 'shop.Inventory',
          implementationClass: 'com.example.InventoryService',
          interceptors: [],
          methodCount: 2,
          methodsTruncated: false,
          metrics: {
            available: true,
            callCount: 12,
            activeCalls: 1,
            totalDurationMs: 180,
            maxDurationMs: 21,
            averageDurationMs: 15,
            statusCounts: [
              {status: 'OK', count: 11},
              {status: 'UNAVAILABLE', count: 1}
            ]
          },
          methods: [
            {
              name: 'Get',
              fullName: 'shop.Inventory/Get',
              type: 'UNARY',
              metrics: {
                available: true,
                callCount: 12,
                activeCalls: 1,
                totalDurationMs: 180,
                maxDurationMs: 21,
                averageDurationMs: 15,
                statusCounts: [
                  {status: 'OK', count: 11},
                  {status: 'UNAVAILABLE', count: 1}
                ]
              }
            },
            {
              name: 'Watch',
              fullName: 'shop.Inventory/Watch',
              type: 'SERVER_STREAMING',
              metrics: {
                available: false,
                callCount: 0,
                activeCalls: null,
                totalDurationMs: null,
                maxDurationMs: null,
                averageDurationMs: null,
                statusCounts: []
              }
            }
          ]
        }
      ]
    }
  ],
  channels: [
    {
      name: 'payments',
      target: 'dns:///payments.internal:443',
      authority: 'payments.internal:443',
      loadBalancingPolicy: 'round_robin',
      transportSecurity: 'TLS',
      retryEnabled: true,
      maxInboundMessageSize: 2097152,
      maxInboundMetadataSize: null,
      keepAlive: [],
      settings: [],
      interceptors: []
    }
  ],
  clientServices: []
}

test.describe('gRPC view', () => {
  test('explains the unavailable state instead of failing when gRPC is absent', async ({openView, page}) => {
    await openView('grpc', 'gRPC')

    await expect(page.locator('.alert-info')).toContainText('gRPC')
    await expect(page.locator('.alert-danger')).toHaveCount(0)
  })

  test('renders servers, methods, call metrics and client channels', async ({page}) => {
    // The panel manifest gates the fetch, so the routed report needs a manifest that reports gRPC as present.
    await page.route(
      (url) => url.pathname === '/bootui/api/panels',
      async (route) => {
        const response = await route.fetch()
        const manifest = await response.json()
        manifest.panels = manifest.panels.map((panel) =>
          panel.id === 'grpc' ? {...panel, available: true, unavailableReason: null} : panel
        )
        await route.fulfill({contentType: 'application/json', body: JSON.stringify(manifest)})
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/grpc',
      async (route) => {
        await route.fulfill({contentType: 'application/json', body: JSON.stringify(grpcReport)})
      }
    )

    await page.goto('/bootui/#/grpc')
    await expect(page.getByText('shop.Inventory')).toBeVisible()
    await expect(page.getByText('Server streaming')).toBeVisible()
    await expect(page.getByText('OK 11')).toBeVisible()
    await expect(page.getByText('dns:///payments.internal:443')).toBeVisible()
    await expect(page.getByText('payments.internal:443', {exact: true})).toBeVisible()

    await page.getByPlaceholder('Filter by service, method or channel…').fill('payments')
    await expect(page.getByText('shop.Inventory')).toHaveCount(0)
    await expect(page.getByText('dns:///payments.internal:443')).toBeVisible()
  })
})
