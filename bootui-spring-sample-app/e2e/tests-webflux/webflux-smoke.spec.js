// @ts-check
import {expect, test} from '@playwright/test'

/**
 * Small smoke suite for the WebFlux (reactive) BootUI adapter.
 *
 * This deliberately does not re-verify individual panel behavior already covered by the shared
 * bootui-conformance suite (WebFluxApiConformanceTest) and the servlet e2e spec-per-panel coverage - the
 * same Vue bundle is served either way, so once one adapter's UI is proven, the remaining risk specific
 * to WebFlux is (a) the shell actually boots and reports the right platform, (b) a representative sample
 * of panels that ARE ported render correctly, and (c) the panel that stays unavailable on this adapter
 * (HTTP Sessions) surfaces its WebFlux-specific explanation through the real
 * sidebar/alert UI rather than just the JSON contract.
 */
test.describe('BootUI on Spring WebFlux', () => {
  test('panels manifest reports the reactive platform', async ({request, baseURL}) => {
    const response = await request.get(`${baseURL}/bootui/api/panels`)
    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.platform).toBe('spring-boot-reactive')
  })

  test('navbar shows the reactive sample app name and Spring Boot / Java versions', async ({page}) => {
    await page.goto('/bootui/')

    await expect(page.locator('.brand-name')).toHaveText('BootUI')
    await expect(page.locator('.topbar-title')).toContainText('bootui-webflux-sample')
    const subtitle = page.locator('.topbar-subtitle')
    await expect(subtitle).toContainText(/Spring Boot \d+\.\d+/)
    await expect(subtitle).toContainText(/Java /)
  })

  test('redirects the root path to /overview', async ({page}) => {
    await page.goto('/bootui/')
    await expect(page).toHaveURL(/\/bootui\/#\/overview$/)
  })

  test('a representative sample of ported panels render', async ({page}) => {
    const panels = [
      {id: 'health', heading: /^Health/},
      {id: 'config', heading: /^Configuration/},
      {id: 'beans', heading: /^Beans/},
      {id: 'cache', heading: /^Cache$/},
      {id: 'flyway', heading: /Flyway migrations/},
      {id: 'liquibase', heading: /Liquibase change sets/},
      {id: 'scheduled', heading: /Scheduled Tasks/},
      {id: 'pentesting', heading: /^Pentesting/},
      {id: 'security', heading: /^Security/},
      {id: 'activity', heading: /Live Activity/},
      {id: 'mcp-server', heading: /^MCP Server/},
      {id: 'rest-client-trace', heading: /REST Client/}
    ]

    for (const panel of panels) {
      await page.goto(`/bootui/#/${panel.id}`)
      await expect(page.locator('main h2').filter({hasText: panel.heading}).first()).toBeVisible({timeout: 15_000})
      // None of these panels should fall back to the generic "unavailable" banner.
      await expect(page.locator('.panel-availability-alert')).toHaveCount(0)
    }
  })

  test("Live Activity's Live flow map renders the same contract on the reactive stack", async ({
    page,
    request,
    baseURL
  }) => {
    // Give the map something real to derive: a request the reactive stack has actually served.
    const warmup = await request.get(`${baseURL}/api/greetings/Ada`)
    expect(warmup.ok()).toBeTruthy()

    const map = await (await request.get(`${baseURL}/bootui/api/activity/service-map`)).json()
    expect(typeof map.available).toBe('boolean')
    expect(Array.isArray(map.nodes)).toBe(true)
    expect(map.truncation.dependencyLimit).toBeGreaterThan(0)

    await page.goto('/bootui/#/activity')
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /Live Activity/})
        .first()
    ).toBeVisible({timeout: 15_000})
    await page.getByRole('button', {name: 'Live flow view'}).click()

    const flow = page.locator('.flow-map')
    await expect(flow).toBeVisible({timeout: 15_000})
    await expect(flow).toContainText('contacts nothing and probes nothing')
    await expect(page.locator('.activity-table')).toHaveCount(0)
  })

  test('raw Spring Security panel exposes reactive chains and mappings without blocking', async ({
    page,
    request,
    baseURL
  }) => {
    const rounds = await Promise.all(
      Array.from({length: 3}, async () => {
        const [reportResponse, explainResponse, endpointsResponse] = await Promise.all([
          request.get(`${baseURL}/bootui/api/spring-security`),
          request.get(
            `${baseURL}/bootui/api/spring-security/explain?method=GET&path=${encodeURIComponent('/api/greetings/Ada')}`
          ),
          request.get(`${baseURL}/bootui/api/spring-security/endpoints`)
        ])
        expect(reportResponse.ok()).toBeTruthy()
        expect(explainResponse.ok()).toBeTruthy()
        expect(endpointsResponse.ok()).toBeTruthy()
        return {
          report: await reportResponse.json(),
          explain: await explainResponse.json(),
          endpoints: await endpointsResponse.json()
        }
      })
    )

    const {report, explain, endpoints} = rounds[0]
    expect(report.springSecurityPresent).toBe(true)
    expect(report.chains.length).toBeGreaterThan(0)
    expect(report.chains.every((chain) => !chain.requestMatcher.includes('/bootui'))).toBe(true)
    expect(report.chains.some((chain) => chain.filters.includes('AuthorizationWebFilter'))).toBe(true)
    expect(explain).toMatchObject({matched: true, bestEffort: true})
    expect(explain.filters).toContain('AuthorizationWebFilter')
    expect(endpoints.handlerMappingAvailable).toBe(true)
    expect(endpoints.endpoints).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          method: 'GET',
          pattern: '/api/greetings/{name}',
          secured: true,
          rule: 'permitAll',
          bestEffort: true
        })
      ])
    )
    expect(endpoints.endpoints.every((endpoint) => !endpoint.pattern.startsWith('/bootui'))).toBe(true)

    await page.goto('/bootui/#/spring-security')
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /^Spring Security/})
        .first()
    ).toBeVisible()
    await expect(page.getByTestId('reactive-fidelity-note')).toContainText('SecurityWebFilterChain')
    await expect(page.getByRole('heading', {name: /WebFilter chains/})).toBeVisible()
    await expect(page.getByText('Annotation-based Spring WebFlux mappings')).toBeVisible()
    await expect(page.getByText('/api/greetings/{name}', {exact: true})).toBeVisible()
    await expect(page.getByText(/Spring MVC mapping/)).toHaveCount(0)
  })

  test('Security advisor runs the 25-rule reactive catalogue', async ({page}) => {
    await page.goto('/bootui/#/security')
    await expect(page.locator('.panel-availability-alert')).toHaveCount(0)
    await page.getByRole('button', {name: 'Run security checks'}).click()
    await expect(page.getByText('Scan complete', {exact: true})).toBeVisible({timeout: 15_000})
    await expect(page.getByText('Rules evaluated').locator('..')).toContainText('25')
  })

  test('REST Client records WebClient calls, streams updates, and protects actions with CSRF', async ({
    page,
    request
  }) => {
    await request.get('/bootui/api/overview')
    const {cookies} = await request.storageState()
    const xsrf = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')
    expect(xsrf).toBeTruthy()

    const rejectedClear = await request.post('/bootui/api/rest-client-trace/clear')
    expect(rejectedClear.status()).toBe(403)

    const csrfHeaders = {'X-XSRF-TOKEN': xsrf.value}
    const cleared = await request.post('/bootui/api/rest-client-trace/clear', {headers: csrfHeaders})
    expect(cleared.ok()).toBeTruthy()

    const streamRequested = page.waitForRequest((request) =>
      request.url().endsWith('/bootui/api/rest-client-trace/stream')
    )
    const streamReady = page.waitForResponse(
      (response) => response.url().endsWith('/bootui/api/rest-client-trace/stream') && response.status() === 200
    )
    await page.goto('/bootui/#/rest-client-trace')
    await expect(page.getByText('No REST client calls have been captured yet')).toBeVisible()
    await streamRequested
    for (let attempt = 0; attempt < 10; attempt++) {
      await request.post('/bootui/api/rest-client-trace/clear', {headers: csrfHeaders})
      const connected = await Promise.race([streamReady.then(() => true), page.waitForTimeout(100).then(() => false)])
      if (connected) break
    }
    await streamReady

    const outbound = await request.get('/api/sample/rest-client?name=WebFluxRestClient')
    expect(outbound.ok()).toBeTruthy()
    await expect(page.getByText('127.0.0.1/api/greetings/WebFluxRestClient', {exact: true}).first()).toBeVisible({
      timeout: 15_000
    })

    const report = await request.get('/bootui/api/rest-client-trace')
    const body = await report.json()
    expect(body.available).toBe(true)
    expect(body.entries).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          method: 'GET',
          path: '/api/greetings/WebFluxRestClient',
          clientType: 'WebClient'
        })
      ])
    )

    await page.getByRole('button', {name: 'Pause'}).click()
    await expect(page.getByRole('button', {name: 'Resume'})).toBeVisible()
    await page.getByRole('button', {name: 'Resume'}).click()
    await expect(page.getByRole('button', {name: 'Pause'})).toBeVisible()
  })

  test('panels with no reactive equivalent yet explain why in the sidebar and panel alert', async ({page}) => {
    await page.goto('/bootui/')

    const httpSessionsLink = page.locator('aside .nav-link', {hasText: 'HTTP Sessions'})
    await expect(httpSessionsLink).toHaveClass(/bootui-nav-link--unavailable/)
    await expect(httpSessionsLink).toHaveAttribute('title', /Not applicable on Spring WebFlux/)

    await page.goto('/bootui/#/http-sessions')
    await expect(page.locator('.panel-availability-alert')).toContainText('Not applicable on Spring WebFlux')
  })
})
