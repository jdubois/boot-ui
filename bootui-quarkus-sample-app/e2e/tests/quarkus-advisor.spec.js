// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Spring advisor is replaced by a framework-aware Quarkus advisor. There is no separate
 * Quarkus.vue — the single shared Spring.vue view reads the panels `platform` and renders Quarkus copy,
 * so the two frameworks keep one UI. These tests pin that platform-aware behavior against the real
 * Quarkus backend (heading, action label, idiom-rule copy, and a successful scan round-trip).
 */
test.describe('Quarkus advisor', () => {
  test('renders the framework-aware Quarkus advisor', async ({openView, page}) => {
    await openView('spring', /^Quarkus/)

    await expect(page.getByRole('button', {name: /Run Quarkus checks/})).toBeVisible()
    await expect(page.locator('main')).toContainText('Heuristic Quarkus idiom rules')
  })

  test('runs Quarkus checks and renders the advisor report', async ({openView, page}) => {
    await openView('spring', /^Quarkus/)

    const scanResponse = page.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/spring\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: /Run Quarkus checks/}).click()
    const response = await scanResponse
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.scan.status).toBe('PARTIAL')
    expect(report.scan.message).toBeTruthy()

    await expect(page.locator('.advisor-summary__metric--status .badge')).toHaveText('Incomplete')
    await expect(page.locator('.advisor-summary__gauge')).toHaveCount(0)
    await expect(page.locator('main')).toContainText('Idioms inspected')
  })
})
