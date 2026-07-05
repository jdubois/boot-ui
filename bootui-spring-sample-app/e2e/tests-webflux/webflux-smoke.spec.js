// @ts-check
import {expect, test} from '@playwright/test'

/**
 * Small smoke suite for the WebFlux (reactive) BootUI adapter.
 *
 * This deliberately does not re-verify individual panel behavior already covered by the shared
 * bootui-conformance suite (WebFluxApiConformanceTest) and the servlet e2e spec-per-panel coverage - the
 * same Vue bundle is served either way, so once one adapter's UI is proven, the remaining risk specific
 * to WebFlux is (a) the shell actually boots and reports the right platform, (b) a representative sample
 * of panels that ARE ported render correctly, and (c) the panels that have no reactive equivalent yet
 * (HTTP Sessions, Spring Security, MCP Server, Live Activity) surface their WebFlux-specific explanation
 * through the real sidebar/alert UI rather than just the JSON contract.
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
      {id: 'pentesting', heading: /^Pentesting/}
    ]

    for (const panel of panels) {
      await page.goto(`/bootui/#/${panel.id}`)
      await expect(page.locator('main h2').filter({hasText: panel.heading}).first()).toBeVisible({timeout: 15_000})
      // None of these panels should fall back to the generic "unavailable" banner.
      await expect(page.locator('.panel-availability-alert')).toHaveCount(0)
    }
  })

  test('panels with no reactive equivalent yet explain why in the sidebar and panel alert', async ({page}) => {
    await page.goto('/bootui/')

    const httpSessionsLink = page.locator('aside .nav-link', {hasText: 'HTTP Sessions'})
    await expect(httpSessionsLink).toHaveClass(/bootui-nav-link--unavailable/)
    await expect(httpSessionsLink).toHaveAttribute('title', /Not applicable on Spring WebFlux/)

    await page.goto('/bootui/#/http-sessions')
    await expect(page.locator('.panel-availability-alert')).toContainText('Not applicable on Spring WebFlux')

    await page.goto('/bootui/#/spring-security')
    await expect(page.locator('.panel-availability-alert')).toContainText('Not yet ported for Spring WebFlux')

    await page.goto('/bootui/#/mcp-server')
    await expect(page.locator('.panel-availability-alert')).toContainText('Not yet ported for Spring WebFlux')

    await page.goto('/bootui/#/activity')
    await expect(page.locator('.panel-availability-alert')).toContainText('Not yet ported for Spring WebFlux')
  })
})
