// @ts-check
import {expect} from '@playwright/test'

/**
 * @param {import('@playwright/test').Page} page
 * @param {import('@playwright/test').APIRequestContext} request
 */
export async function assertSpringAdvisorFlow(page, request) {
  const apiPath = '/bootui/api/spring'
  const before = await request.get(apiPath)
  expect(before.ok()).toBe(true)
  const previous = await before.json()

  const loaded = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPath && response.request().method() === 'GET'
  )
  await page.goto('/bootui/#/spring')
  expect((await (await loaded).json()).scan.scannedAt).toBe(previous.scan.scannedAt)
  await expect(page.locator('main h2').filter({hasText: /^Spring$/})).toBeVisible()

  const scanned = page.waitForResponse(
    (response) => new URL(response.url()).pathname === `${apiPath}/scan` && response.request().method() === 'POST'
  )
  await page.getByRole('button', {name: 'Run Spring checks'}).click()
  const response = await scanned
  expect(response.ok()).toBe(true)
  const report = await response.json()
  expect(report.localOnly).toBe(true)
  expect(report.rulesEvaluated).toBe(38)
  expect(report.scan.scannedAt).toBeGreaterThan(0)
  expect(report.analysisErrors).toEqual([])
  expect(report.inspected.length).toBeGreaterThan(0)

  const retired = ['SPRING-PROFILE-001', 'SPRING-PERF-004', 'SPRING-WEB-006', 'SPRING-REACTIVE-002']
  for (const result of report.results) {
    expect(retired).not.toContain(result.id)
  }
  await expect(page.locator('.advisor-summary__metric', {hasText: 'Rules evaluated'}).locator('dd')).toHaveText('38')
  const cached = await request.get(apiPath)
  expect(cached.ok()).toBe(true)
  expect(await cached.json()).toEqual(report)
}
