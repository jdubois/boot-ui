// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP Probe view', () => {
  test('sends a GET request to the sample API and shows the response body', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await page.getByLabel('Method').selectOption('GET')
    await page.getByLabel('Path', {exact: true}).fill('/api/hello')
    await page.locator('button.btn-primary', {hasText: 'Send'}).click()

    const responseCard = page.locator('.card', {hasText: /^Response/})
    await expect(responseCard).toBeVisible()
    await expect(responseCard.locator('.badge', {hasText: /200 OK/})).toBeVisible()
    await expect(responseCard.locator('pre')).toContainText('Hello, world')
  })

  test('switching to POST reveals the request body editor', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await expect(page.locator('textarea')).toHaveCount(0)
    await page.getByLabel('Method').selectOption('POST')
    await expect(page.getByLabel('Request body')).toBeVisible()
    await page.getByLabel('Request body').fill('{"message":"hello"}')
    await expect(page.locator('.form-text', {hasText: 'Content-Type:'})).toBeVisible()
  })

  test('Enter requires confirmation before sending an unsafe request', async ({openView, page}) => {
    const probeRequests = []
    page.on('request', (request) => {
      if (request.url().endsWith('/bootui/api/http-probe')) probeRequests.push(request)
    })
    await openView('http-probe', 'HTTP Probe')
    await page.getByLabel('Method').selectOption('DELETE')
    const path = page.getByLabel('Path', {exact: true})
    await path.fill('/api/hello')

    await path.press('Enter')

    await expect(page.getByRole('heading', {name: 'Send DELETE request?'})).toBeVisible()
    expect(probeRequests).toHaveLength(0)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('heading', {name: 'Send DELETE request?'})).toBeHidden()
    expect(probeRequests).toHaveLength(0)
  })

  test('clear resets the form back to defaults', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await page.getByLabel('Method').selectOption('POST')
    await page.getByLabel('Path', {exact: true}).fill('/something')
    await page.getByLabel('Request body').fill('{"k":1}')

    await page.getByRole('button', {name: /Clear/}).click()

    await expect(page.getByLabel('Method')).toHaveValue('GET')
    await expect(page.getByLabel('Path', {exact: true})).toHaveValue('')
    await expect(page.getByLabel('Request body')).toHaveCount(0)
  })

  test('keeps form controls programmatically named in dark mode', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')
    await page.evaluate(() => {
      document.documentElement.dataset.bootuiTheme = 'dark'
    })

    await expect(page.getByLabel('Method')).toBeVisible()
    await expect(page.getByLabel('Path', {exact: true})).toBeVisible()

    await page.getByLabel('Method').selectOption('POST')
    await expect(page.getByLabel('Request body')).toBeVisible()
  })
})
