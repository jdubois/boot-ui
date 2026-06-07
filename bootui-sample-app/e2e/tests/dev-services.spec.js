// @ts-check
import {expect, test} from './fixtures.js'

const devServicesReport = {
  dockerComposePresent: true,
  testcontainersPresent: true,
  snapshotTimestamp: Date.now(),
  total: 2,
  warnings: [],
  services: [
    {
      id: 'compose:postgres',
      name: 'postgres',
      type: 'PostgreSQL',
      source: 'Docker Compose',
      image: 'postgres:18',
      status: 'READY_AT_STARTUP',
      host: 'localhost',
      ports: [{containerPort: 5432, hostPort: 15432, protocol: 'tcp'}],
      connectionDetails: {snapshot: 'Captured when Spring Boot reported Docker Compose services ready'},
      restartable: false,
      logsAvailable: false,
      note: 'Docker Compose status is a startup snapshot; Spring Boot does not expose live per-service restart.'
    },
    {
      id: 'bean:redisContainer',
      name: 'redis',
      type: 'Redis',
      source: 'Testcontainers',
      image: 'redis:7',
      status: 'RUNNING',
      host: 'localhost',
      ports: [{containerPort: 6379, hostPort: 16379, protocol: 'tcp'}],
      connectionDetails: {containerId: 'abc123', reuse: false},
      restartable: false,
      logsAvailable: true,
      note: 'Restart disabled by default; set bootui.dev-services.restart-enabled=true to allow it.'
    }
  ]
}

const restartableDevServicesReport = {
  ...devServicesReport,
  services: devServicesReport.services.map((service) =>
    service.id === 'bean:redisContainer'
      ? {
          ...service,
          restartable: true,
          note: 'Restart may require application clients to reconnect.'
        }
      : service
  )
}

test.describe('Dev Services view', () => {
  test('renders the live Dev Services report or the empty state', async ({openView, page}) => {
    await openView('dev-services', 'Dev Services')

    await expect(page.locator('.alert-info')).toContainText('Restart controls appear only')

    const emptyState = page.locator('.alert-secondary', {
      hasText: 'No Docker Compose, Testcontainers, or Spring Boot service connection beans were detected.'
    })
    const tableRows = page.locator('tbody tr')
    await expect.poll(async () => (await emptyState.count()) + (await tableRows.count())).toBeGreaterThan(0)
  })

  test('filters services, opens details, loads logs, and hides unavailable restart', async ({openView, page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/dev-services',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify(devServicesReport)
        })
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/dev-services/bean%3AredisContainer/logs',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            id: 'bean:redisContainer',
            logs: 'Redis container started\nReady to accept connections',
            truncated: false,
            maxBytes: 65536
          })
        })
      }
    )

    await openView('dev-services', /^Dev Services/)
    await expect(page.getByText('2 / 2 services')).toBeVisible()
    await expect(page.getByRole('columnheader', {name: 'Source'})).toHaveCount(0)

    await page.getByPlaceholder('Filter by name, type, status, or image').fill('redis')
    await expect(page.getByText('1 / 2 services')).toBeVisible()

    const redisRow = page.locator('tbody tr', {hasText: 'redis'})
    await expect(redisRow).toBeVisible()
    await expect(redisRow.getByRole('button', {name: 'Restart'})).toHaveCount(0)

    await redisRow.getByRole('button', {name: 'View details'}).click()
    const details = page.locator('.card', {hasText: 'redis'}).last()
    await expect(details).toContainText('Connection details')
    await expect(details).toContainText('containerId')
    await expect(details).toContainText('Testcontainers')

    await redisRow.getByRole('button', {name: 'View logs'}).click()
    await expect(details.locator('pre')).toContainText('Ready to accept connections')
  })

  test('posts restart for restartable services', async ({openView, page}) => {
    let restartCalled = false
    await page.route(
      (url) => url.pathname === '/bootui/api/dev-services',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify(restartableDevServicesReport)
        })
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/dev-services/bean%3AredisContainer/restart',
      async (route) => {
        expect(route.request().method()).toBe('POST')
        restartCalled = true
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            id: 'bean:redisContainer',
            status: 'restarted',
            message: 'Service restarted. Already-created client beans may need an application restart to reconnect.'
          })
        })
      }
    )

    await openView('dev-services', /^Dev Services/)
    const redisRow = page.locator('tbody tr', {hasText: 'redis'})
    await expect(redisRow.getByRole('button', {name: 'Restart'})).toBeVisible()

    await redisRow.getByRole('button', {name: 'Restart'}).click()
    await expect(page.locator('.alert-success')).toContainText('Service restarted')
    await expect.poll(async () => restartCalled).toBe(true)
  })

  test('shows safe-inspection warnings from the report', async ({openView, page}) => {
    await page.route(
      (url) => url.pathname === '/bootui/api/dev-services',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            ...devServicesReport,
            warnings: ["Skipped Testcontainers bean 'lazyRedis' because inspecting it would initialize a lazy bean."]
          })
        })
      }
    )

    await openView('dev-services', /^Dev Services/)
    await expect(page.locator('.alert-warning')).toContainText('Some services were skipped')
    await expect(page.locator('.alert-warning')).toContainText('lazyRedis')
  })
})
