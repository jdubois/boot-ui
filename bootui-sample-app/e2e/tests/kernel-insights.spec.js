// @ts-check
import {expect, test} from './fixtures.js'

const STATUS_PATH = '/bootui/api/kernel-insights'
const SCAN_PATH = '/bootui/api/kernel-insights/scan'

const unavailableReport = {
  available: false,
  status: 'UNAVAILABLE',
  message: "The Inspektor Gadget 'ig' binary (ig) was not found on PATH. Install it from https://inspektor-gadget.io.",
  os: 'Linux',
  igPath: 'ig',
  igVersion: null,
  currentPid: 4242,
  scannedAt: null,
  captureSeconds: 3,
  gadgets: []
}

const readyReport = {
  ...unavailableReport,
  available: true,
  status: 'NOT_SCANNED',
  message: 'Ready. Click Capture to run Inspektor Gadget for a few seconds and snapshot kernel activity.'
}

const scannedReport = {
  available: true,
  status: 'SCANNED',
  message: 'Captured 2 kernel events across 2 gadgets.',
  os: 'Linux',
  igPath: 'ig',
  igVersion: 'ig version v0.40.0',
  currentPid: 4242,
  scannedAt: Date.now(),
  captureSeconds: 3,
  gadgets: [
    {
      gadget: 'trace_tcp',
      title: 'TCP connections',
      category: 'NETWORK',
      status: 'OK',
      message: 'Captured 1 event.',
      eventCount: 1,
      events: [
        {
          timestamp: null,
          comm: 'curl',
          pid: 1234,
          container: null,
          summary: 'curl 10.0.0.2:40000 → 93.184.216.34:443',
          fields: {comm: 'curl', pid: '1234', 'dst.addr': '93.184.216.34', 'dst.port': '443'}
        }
      ]
    },
    {
      gadget: 'trace_exec',
      title: 'Process executions',
      category: 'PROCESS',
      status: 'OK',
      message: 'Captured 1 event.',
      eventCount: 1,
      events: [
        {
          timestamp: null,
          comm: 'sh',
          pid: 2001,
          container: null,
          summary: 'sh -c env',
          fields: {comm: 'sh', pid: '2001', args: '-c env'}
        }
      ]
    }
  ]
}

async function mockStatus(page, body) {
  await page.route(
    (url) => url.pathname === STATUS_PATH,
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(body)})
    }
  )
}

test.describe('Kernel Insights view', () => {
  test('degrades gracefully when Inspektor Gadget is unavailable', async ({openView, page}) => {
    await mockStatus(page, unavailableReport)
    await openView('kernel-insights', 'Kernel Insights')

    await expect(page.locator('.alert-secondary')).toContainText('Inspektor Gadget')
    await expect(page.getByRole('button', {name: /Capture/})).toBeDisabled()
  })

  test('captures kernel activity and renders normalized events by gadget', async ({openView, page}) => {
    await mockStatus(page, readyReport)
    await page.route(
      (url) => url.pathname === SCAN_PATH,
      async (route) => {
        await route.fulfill({contentType: 'application/json', body: JSON.stringify(scannedReport)})
      }
    )

    await openView('kernel-insights', 'Kernel Insights')

    const captureButton = page.getByRole('button', {name: /Capture/})
    await expect(captureButton).toBeEnabled()
    await captureButton.click()

    await expect(page.locator('.badge', {hasText: 'SCANNED'})).toBeVisible()

    const tcpCard = page.locator('.card', {hasText: 'TCP connections'})
    await expect(tcpCard.locator('.badge', {hasText: 'NETWORK'})).toBeVisible()
    await expect(tcpCard.locator('tbody tr')).toContainText('curl')
    await expect(tcpCard.locator('tbody tr')).toContainText('93.184.216.34:443')

    const execCard = page.locator('.card', {hasText: 'Process executions'})
    await expect(execCard.locator('.badge', {hasText: 'PROCESS'})).toBeVisible()
    await expect(execCard.locator('tbody tr')).toContainText('sh')
  })
})
