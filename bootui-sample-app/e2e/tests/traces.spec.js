// @ts-check
import {expect, test} from './fixtures.js'

const traceId = '0123456789abcdef0123456789abcdef'

const traceReport = {
  enabled: true,
  retained: 1,
  capacity: 500,
  traces: [
    {
      traceId,
      rootSpanName: 'GET /api/sample/hello',
      services: ['sample-app', 'inventory'],
      startEpochNanos: Date.now() * 1_000_000,
      endEpochNanos: Date.now() * 1_000_000 + 125_000_000,
      durationNanos: 125_000_000,
      spanCount: 2,
      hasError: false,
      hasAi: true
    }
  ]
}

const traceDetail = {
  traceId,
  spans: [
    {
      traceId,
      spanId: '1111111111111111',
      parentSpanId: null,
      name: 'GET /api/sample/hello',
      kind: 'SERVER',
      serviceName: 'sample-app',
      scope: 'io.micrometer',
      startEpochNanos: traceReport.traces[0].startEpochNanos,
      endEpochNanos: traceReport.traces[0].startEpochNanos + 125_000_000,
      durationNanos: 125_000_000,
      statusCode: 'OK',
      statusMessage: null,
      attributes: [],
      events: []
    },
    {
      traceId,
      spanId: '2222222222222222',
      parentSpanId: '1111111111111111',
      name: 'SELECT sample_products',
      kind: 'CLIENT',
      serviceName: 'inventory',
      scope: 'jdbc',
      startEpochNanos: traceReport.traces[0].startEpochNanos + 10_000_000,
      endEpochNanos: traceReport.traces[0].startEpochNanos + 80_000_000,
      durationNanos: 70_000_000,
      statusCode: 'OK',
      statusMessage: null,
      attributes: [],
      events: []
    }
  ]
}

test.describe('Traces view', () => {
  test('renders trace summaries and waterfall details', async ({openView, page}) => {
    await stubShell(page)
    await page.route(
      (url) => url.pathname === '/bootui/api/traces',
      async (route) => {
        await route.fulfill({contentType: 'application/json', body: JSON.stringify(traceReport)})
      }
    )
    await page.route(
      (url) => url.pathname === `/bootui/api/traces/${traceId}`,
      async (route) => {
        await route.fulfill({contentType: 'application/json', body: JSON.stringify(traceDetail)})
      }
    )

    await openView('traces', /^Traces/)
    await expect(page.getByText('1 / 500 retained trace')).toBeVisible()
    const traceRow = page.locator('tbody tr', {hasText: 'GET /api/sample/hello'})
    await expect(traceRow).toBeVisible()
    await expect(page.getByText('sample-app')).toBeVisible()
    await expect(traceRow.getByText('AI', {exact: true})).toBeVisible()

    await page.getByRole('button', {name: 'Open'}).click()
    await expect(page.locator('.trace-drawer')).toContainText(traceId)
    await expect(page.locator('.waterfall-row')).toHaveCount(2)
    await expect(page.locator('.trace-drawer')).toContainText('SELECT sample_products')
  })

  test('shows disabled mode when telemetry is unavailable', async ({page}) => {
    await stubShell(page)
    await page.route(
      (url) => url.pathname === '/bootui/api/traces',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({enabled: false, retained: 0, capacity: 500, traces: []})
        })
      }
    )

    await page.goto('/bootui/#/traces')
    await expect(page.getByText('Telemetry capture is disabled')).toBeVisible()
    await expect(page.getByText('No traces received yet')).toHaveCount(0)
  })
})

async function stubShell(page) {
  await page.route(
    (url) => url.pathname === '/bootui/api/overview',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          bootUiVersion: 'test',
          applicationName: 'bootui-sample',
          springBootVersion: '4.0.6',
          javaVersion: '25',
          javaVendor: 'test',
          activeProfiles: ['dev'],
          defaultProfiles: ['default'],
          webApplicationType: 'SERVLET',
          serverPort: 8080,
          managementPort: null,
          contextPath: '',
          startupTimeMillis: 1000,
          activation: {enabled: true, localhostOnly: true, reason: 'test', warnings: []},
          openApiUrl: null
        })
      })
    }
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/panels',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({panels: [{id: 'traces', title: 'Traces', available: true, unavailableReason: null}]})
      })
    }
  )
}
