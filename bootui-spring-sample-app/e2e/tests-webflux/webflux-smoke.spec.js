// @ts-check
import {expect, test} from '@playwright/test'

async function expandAllSidebarGroups(page) {
  const toggles = page.locator('aside .bootui-nav-group__toggle')
  for (let index = 0; index < (await toggles.count()); index += 1) {
    const toggle = toggles.nth(index)
    if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click()
  }
}

/**
 * Small smoke suite for the WebFlux (reactive) BootUI adapter.
 *
 * This deliberately does not re-verify individual panel behavior already covered by the shared
 * bootui-conformance suite (WebFluxApiConformanceTest) and the servlet e2e spec-per-panel coverage - the
 * same Vue bundle is served either way, so once one adapter's UI is proven, the remaining risk specific
 * to WebFlux is (a) the shell actually boots and reports the right platform, (b) a representative sample
 * of panels that ARE ported render correctly, (c) the panel that stays unavailable on this adapter
 * (HTTP Sessions) surfaces its WebFlux-specific explanation through the real
 * sidebar/alert UI rather than just the JSON contract, and (d) client-side actions that read a shared DTO
 * - such as HTTP Exchanges' "Copy as cURL" - produce the same text from reactive-captured evidence.
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

  test('expanded sidebar groups scroll without moving the footer actions', async ({page}) => {
    await page.setViewportSize({width: 1280, height: 400})
    await page.goto('/bootui/')
    await expandAllSidebarGroups(page)

    const layout = await page.locator('aside.bootui-sidebar').evaluate((sidebar) => {
      const nav = sidebar.querySelector('.sidebar-nav')
      const navRect = nav.getBoundingClientRect()
      const bottom = sidebar.querySelector('.sidebar-bottom').getBoundingClientRect()
      return {
        navScrollable: nav.scrollHeight > nav.clientHeight,
        navDoesNotWrap: getComputedStyle(nav).flexWrap === 'nowrap',
        noHorizontalOverflow: nav.scrollWidth <= nav.clientWidth + 1,
        itemsStayInsideNav: [...nav.querySelectorAll('.bootui-nav-section, .bootui-nav-link')].every((item) => {
          const itemRect = item.getBoundingClientRect()
          return itemRect.left >= navRect.left - 1 && itemRect.right <= navRect.right + 1
        }),
        sectionsDoNotShrink: [...nav.querySelectorAll('.bootui-nav-section')].every(
          (section) => getComputedStyle(section).flexShrink === '0'
        ),
        bottomVisible: bottom.top >= 0 && bottom.bottom <= innerHeight
      }
    })

    expect(layout).toEqual({
      navScrollable: true,
      navDoesNotWrap: true,
      noHorizontalOverflow: true,
      itemsStayInsideNav: true,
      sectionsDoNotShrink: true,
      bottomVisible: true
    })
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
      {id: 'cli', heading: /^Command Line/},
      {id: 'rest-client-trace', heading: /REST Client/}
    ]

    for (const panel of panels) {
      await page.goto(`/bootui/#/${panel.id}`)
      await expect(page.locator('main h2').filter({hasText: panel.heading}).first()).toBeVisible({timeout: 15_000})
      // None of these panels should fall back to the generic "unavailable" banner.
      await expect(page.locator('.panel-availability-alert')).toHaveCount(0)
    }
  })

  test('MCP Server offers the per-client configuration snippets and the bearer-header switch', async ({page}) => {
    await page.goto('/bootui/#/mcp-server')
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /^MCP Server/})
        .first()
    ).toBeVisible({timeout: 15_000})

    await expect(page.getByRole('tablist', {name: 'MCP client'})).toHaveCount(1)
    await expect(page.locator('#mcp-client-vscode-panel .config-block')).toContainText('"servers"')

    await page.getByRole('tab', {name: 'Claude Code'}).click()
    const claudeSnippet = page.locator('#mcp-client-claude-panel .config-block')
    await expect(claudeSnippet).toContainText('claude mcp add --transport http bootui')
    await expect(claudeSnippet).not.toContainText('--header')

    await page.locator('#mcp-remote-agent').check()
    await expect(claudeSnippet).toContainText('--header "Authorization:')
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

    const flow = page.locator('.flow-map')
    await expect(flow).toBeVisible({timeout: 15_000})
    await expect(flow).toContainText('contacts nothing and probes nothing')
    await expect(page.locator('.activity-table')).toBeVisible()
  })

  test('profiles a traced request with exact tiers only on the reactive stack', async ({page, request, baseURL}) => {
    // The reactive sample runs no tracer, so an inbound W3C traceparent is what gives the exchange the
    // trace id the reactive profiler correlates on. The serving-thread and time-window tiers stay
    // unavailable on an event loop, and the drawer says so instead of guessing.
    const traceId = `4bf92f3577b34da6a3ce929d${Date.now().toString(16).slice(-8).padStart(8, '0')}`
    const traced = await request.get(`${baseURL}/api/greetings/Grace`, {
      headers: {traceparent: `00-${traceId}-00f067aa0ba902b7-01`}
    })
    expect(traced.ok()).toBeTruthy()

    const activity = await request.get(`${baseURL}/bootui/api/activity`)
    const entry = (await activity.json()).entries.find(
      (candidate) => candidate.type === 'REQUEST' && candidate.correlationId === traceId
    )
    expect(entry?.profileable).toBe(true)

    await page.goto('/bootui/#/activity')
    const row = page.locator('.activity-table tbody tr', {hasText: '/api/greetings/Grace'}).first()
    await expect(row).toBeVisible({timeout: 15_000})
    await row.getByRole('button', {name: /Profile/}).click()

    const drawer = page.locator('.activity-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('This is a reduced profile')
    await expect(drawer.getByRole('heading', {name: /^REST client calls/})).toBeVisible()
    await expect(drawer.getByRole('heading', {name: /^Cache accesses/})).toBeVisible()
    await expect(drawer).toContainText('Serving thread and time window correlation are unavailable on this adapter')
    await expect(drawer.getByRole('button', {name: /Copy profile/})).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(drawer).toHaveCount(0)
  })

  test('opens the runtime journal status, which records the reactive requests', async ({page, request, baseURL}) => {
    const notes = await request.get(`${baseURL}/api/notes`)
    expect(notes.ok()).toBeTruthy()

    await page.goto('/bootui/#/activity')
    const toggle = page.getByRole('button', {name: 'Recording', exact: true})
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const journal = page.locator('#activity-runtime-journal')
    await expect(page.getByRole('heading', {name: 'Runtime journal'})).toHaveCount(1)
    await expect(journal.locator('code', {hasText: /^http$/})).toBeVisible()
    await expect(journal.locator('code', {hasText: /^sql$/})).toBeVisible()
  })

  test("profiles a request's SQL exactly by its BootUI request id", async ({request, baseURL}) => {
    // BootUI's own request id follows the request across Reactor hops to its blocking SQL, and it decides
    // before the trace id, so the SQL is correlated by it whether or not the request is traced.
    const notes = await request.get(`${baseURL}/api/notes`)
    expect(notes.ok()).toBeTruthy()

    const activity = await request.get(`${baseURL}/bootui/api/activity`)
    const entry = (await activity.json()).entries.find(
      (candidate) => candidate.type === 'REQUEST' && candidate.path === '/api/notes'
    )
    expect(entry?.profileable).toBe(true)

    const profile = await (await request.get(`${baseURL}/bootui/api/activity/request/${entry.id}`)).json()
    expect(profile.available).toBe(true)
    expect(profile.approximate).toBe(false)
    expect(profile.sql.length).toBeGreaterThan(0)
    expect(profile.sections.find((section) => section.type === 'SQL').tier).toBe('REQUEST_ID')
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
    await expect(
      page.locator('.advisor-summary__metric--status').getByText('Results available', {exact: true})
    ).toBeVisible({timeout: 15_000})
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

  test('HTTP Exchanges copies the same safe cURL template on the reactive stack', async ({
    browserName,
    context,
    page,
    request
  }) => {
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }

    const probe = await request.get('/api/greetings/Ada?curlProbe=alpha&curlProbe=beta', {
      headers: {accept: 'application/json', 'x-api-key': 'e2e-must-not-be-copied'}
    })
    expect(probe.ok()).toBeTruthy()

    await page.goto('/bootui/#/http-exchanges')
    await page.locator('#http-exchanges-filter').fill('curlProbe')

    const probeRow = page.locator('.http-exchanges-table tbody tr', {hasText: 'curlProbe'}).first()
    await expect(probeRow).toBeVisible({timeout: 15_000})
    await probeRow.locator('.http-exchanges-detail-toggle').click()
    // Precondition: the header really was recorded, so the absence assertions below cannot pass vacuously.
    await expect(page.locator('.http-exchanges-detail').first()).toContainText(/x-api-key/i)

    const curlAction = page.locator('.http-exchanges-curl').first()
    const copyButton = curlAction.locator('.http-exchanges-curl-copy')
    await copyButton.click()
    // Wait for the confirmed copy before reading, so the clipboard cannot still hold older content.
    await expect(copyButton).toContainText('Copied')

    const copied = await page.evaluate(() => navigator.clipboard.readText())
    // textContent, not toHaveText: the rendered command must match the clipboard byte for byte.
    expect(await curlAction.locator('.http-exchanges-curl-command').textContent()).toBe(copied)

    const lines = copied.split(' \\\n')
    expect(lines[0]).toMatch(/^curl --globoff 'http:\/\/[^']+\/api\/greetings\/Ada\?curlProbe=VALUE&curlProbe=VALUE'$/)
    for (const line of lines.slice(1)) {
      expect(line).toMatch(/^ {2}-H '(Accept|Accept-Language|Cache-Control|Content-Type|User-Agent): [^']*'$/)
    }
    expect(lines).toContain("  -H 'Accept: application/json'")
    expect(copied).not.toContain('alpha')
    expect(copied).not.toContain('beta')
    expect(copied.toLowerCase()).not.toContain('x-api-key')
    expect(copied).not.toContain('e2e-must-not-be-copied')
  })

  test('HTTP Exchanges ranks routes by their WebFlux template, never by a path value', async ({page, request}) => {
    for (const name of ['Ada', 'Grace', 'Barbara']) {
      expect((await request.get(`/api/greetings/${name}`)).ok()).toBeTruthy()
    }

    await page.goto('/bootui/#/http-exchanges')

    // A word-shaped path value such as a name is indistinguishable from a route word, so only the matched
    // handler pattern can group these three requests into one row.
    const routeRow = page.locator('.http-routes-table tbody tr[data-route-id="GET /api/greetings/{name}"]')
    await expect(routeRow).toBeVisible({timeout: 15_000})
    await expect(routeRow.locator('.http-routes-source')).toHaveText(/template/)
    await expect(page.locator('.http-routes-table')).not.toContainText('Grace')
    await expect(page.locator('.http-routes-table')).not.toContainText('/bootui')

    await routeRow.locator('.http-routes-exchanges-link').click()
    await expect(page).toHaveURL(/route=GET/)
    await expect(page.locator('.http-exchanges-route-filter')).toContainText('GET /api/greetings/{name}')
    await expect(page.locator('.http-exchanges-table tbody tr').first()).toContainText('/api/greetings/')
    await expect(page.locator('.http-exchanges-table')).not.toContainText('/api/notes')
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
