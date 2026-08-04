// @ts-check
import {expect, test} from '@playwright/test'

const UI_PATH = '/host/dev-console'
const API_PATH = '/host/internal/bootui-api'

test('loads the SPA and assets only from the configured path', async ({page, request}) => {
  const response = await page.goto(`${UI_PATH}/`)

  expect(response?.ok()).toBeTruthy()
  await expect(page).toHaveURL(new RegExp(`${UI_PATH}/#/overview$`))
  await expect(page.locator('.brand-name')).toHaveText('BootUI')
  await expect(page.locator('base')).toHaveAttribute('href', `${UI_PATH}/`)
  await expect(page.locator('meta[name="bootui-api-path"]')).toHaveAttribute('content', API_PATH)

  const resourcePaths = await page.evaluate(() =>
    performance
      .getEntriesByType('resource')
      .map((entry) => new URL(entry.name).pathname)
      .filter((path) => path.includes('/assets/'))
  )
  expect(resourcePaths.length).toBeGreaterThan(0)
  expect(resourcePaths.every((path) => path.startsWith(`${UI_PATH}/assets/`))).toBeTruthy()

  expect((await request.get('/host/bootui/')).status()).toBe(404)
  expect((await request.get('/host/bootui/api/overview')).status()).toBe(404)
})

test('uses the configured API path for queries, SSE, and security', async ({page, request}) => {
  const threads = await request.get(`${API_PATH}/threads?offset=0&limit=1`)
  expect(threads.ok()).toBeTruthy()
  const report = await threads.json()
  expect(report.page.limit).toBe(1)
  expect(report.threads.length).toBeLessThanOrEqual(1)

  await page.goto(`${UI_PATH}/`)
  const streamStatus = await page.evaluate(
    (apiPath) =>
      new Promise((resolve, reject) => {
        const timeout = window.setTimeout(() => reject(new Error('SSE connection timed out')), 10_000)
        const source = new EventSource(`${apiPath}/log-tail/stream`)
        source.onopen = () => {
          window.clearTimeout(timeout)
          source.close()
          resolve('open')
        }
        source.onerror = () => {
          window.clearTimeout(timeout)
          source.close()
          reject(new Error('SSE connection failed'))
        }
      }),
    API_PATH
  )
  expect(streamStatus).toBe('open')

  const rejected = await request.post(`${API_PATH}/overview`, {
    headers: {Origin: 'http://evil.example.com', 'Sec-Fetch-Site': 'cross-site'}
  })
  expect(rejected.status()).toBe(403)
  expect((await rejected.json()).error).toContain('cross-site request')
})

test('rewrites panel data loads when the API path is independent', async ({page}) => {
  for (const [route, endpoint] of [
    ['live-memory', 'live-memory'],
    ['jvm-tuning', 'jvm-tuning'],
    ['activity', 'activity']
  ]) {
    const responsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === `${API_PATH}/${endpoint}` && response.request().method() === 'GET'
    })
    await page.goto(`${UI_PATH}/#/${route}`)
    expect((await responsePromise).ok()).toBeTruthy()
  }
})

test('uses the configured API path for browser download links', async ({page}) => {
  await page.goto(`${UI_PATH}/#/crac`)
  await expect(page.locator(`a[href="${API_PATH}/crac/dockerfile"]`)).toHaveCount(1)
  await expect(page.locator(`a[href="${API_PATH}/crac/entrypoint"]`)).toHaveCount(1)
})

test('uses the configured endpoint for MCP reads and writes', async ({page, baseURL}) => {
  await page.goto(`${UI_PATH}/#/mcp-server`)
  await expect(
    page
      .locator('main h2')
      .filter({hasText: /^MCP Server$/})
      .first()
  ).toBeVisible()
  await expect(page.locator('dd code').filter({hasText: `${baseURL}${API_PATH}/mcp`})).toBeVisible()

  const toggle = page.locator('#mcp-enabled-toggle')
  const initiallyChecked = await toggle.isChecked()
  await toggle.click()
  await expect(toggle).toBeChecked({checked: !initiallyChecked})
  await expect(page.locator('.alert')).toContainText(initiallyChecked ? 'disabled' : 'enabled')

  await toggle.click()
  await expect(toggle).toBeChecked({checked: initiallyChecked})
})

test('serves the MVC OTLP receiver at the configured API path', async ({page}, testInfo) => {
  test.skip(testInfo.project.name !== 'spring-mvc', 'The embedded OTLP/HTTP receiver is servlet-only')
  await page.goto(`${UI_PATH}/`)

  const status = await page.evaluate(async (apiPath) => {
    const csrfCookie = document.cookie
      .split(';')
      .map((part) => part.trim())
      .find((part) => part.startsWith('XSRF-TOKEN='))
    /** @type {Record<string, string>} */
    const headers = {'Content-Type': 'application/x-protobuf'}
    if (csrfCookie) {
      headers['X-XSRF-TOKEN'] = decodeURIComponent(csrfCookie.substring('XSRF-TOKEN='.length))
    }
    const response = await fetch(`${apiPath}/otlp/v1/traces`, {
      method: 'POST',
      headers,
      body: new Uint8Array([10])
    })
    return response.status
  }, API_PATH)

  expect(status).toBe(400)
})
