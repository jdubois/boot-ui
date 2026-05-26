// @ts-check
import { test, expect } from './fixtures.js'

const devServicesReport = {
  dockerComposePresent: true,
  testcontainersPresent: true,
  snapshotTimestamp: Date.now(),
  total: 2,
  services: [
    {
      id: 'compose:postgres',
      name: 'postgres',
      type: 'PostgreSQL',
      source: 'Docker Compose',
      image: 'postgres:18',
      status: 'READY_AT_STARTUP',
      host: 'localhost',
      ports: [{ containerPort: 5432, hostPort: 15432, protocol: 'tcp' }],
      connectionDetails: { snapshot: 'Captured when Spring Boot reported Docker Compose services ready' },
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
      ports: [{ containerPort: 6379, hostPort: 16379, protocol: 'tcp' }],
      connectionDetails: { containerId: 'abc123', reuse: false },
      restartable: false,
      logsAvailable: true,
      note: 'Restart disabled by default; set bootui.dev-services.restart-enabled=true to allow it.'
    }
  ]
}

test.describe('Dev Services view', () => {

  test('renders the live Dev Services report or the empty state', async ({ openView, page }) => {
    await openView('dev-services', 'Dev Services')

    await expect(page.locator('.alert-info')).toContainText('Restart is intentionally disabled by default')

    const emptyState = page.locator('.alert-secondary', {
      hasText: 'No Docker Compose, Testcontainers, or Spring Boot service connection beans were detected.'
    })
    const tableRows = page.locator('tbody tr')
    await expect.poll(async () => (await emptyState.count()) + (await tableRows.count())).toBeGreaterThan(0)
  })

  test('filters services, opens details, and keeps restart disabled by default', async ({ page }) => {
    await page.route(url => url.pathname === '/bootui/api/dev-services', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(devServicesReport)
      })
    })
    await page.route(url => url.pathname === '/bootui/api/dev-services/bean%3AredisContainer/logs', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'bean:redisContainer',
          logs: 'Redis container started\nReady to accept connections',
          truncated: false,
          maxBytes: 65536
        })
      })
    })

    await page.goto('/bootui/#/dev-services')
    await expect(page.locator('main h2').filter({ hasText: /^Dev Services/ }).first()).toBeVisible()
    await expect(page.getByText('2 / 2 services')).toBeVisible()

    await page.getByPlaceholder('Filter by name, type, source, or image').fill('redis')
    await expect(page.getByText('1 / 2 services')).toBeVisible()

    const redisRow = page.locator('tbody tr', { hasText: 'redis' })
    await expect(redisRow).toBeVisible()
    await expect(redisRow.locator('.badge', { hasText: 'Testcontainers' })).toBeVisible()
    await expect(redisRow.getByRole('button', { name: 'Restart' })).toBeDisabled()

    await redisRow.getByRole('button', { name: 'Details' }).click()
    const details = page.locator('.card', { hasText: 'redis' }).last()
    await expect(details).toContainText('Connection details')
    await expect(details).toContainText('containerId')

    await redisRow.getByRole('button', { name: 'Logs' }).click()
    await expect(details.locator('pre')).toContainText('Ready to accept connections')
  })
})

