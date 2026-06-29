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

    await page.getByRole('button', {name: /Run Quarkus checks/}).click()

    // A successful scan renders the shared advisor summary score and the platform-aware "Idioms
    // inspected" card. The state-changing POST also proves the Quarkus LocalhostOnlyFilter accepts a
    // same-origin browser request without Spring Security's CSRF token.
    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})
    await expect(page.locator('main')).toContainText('Idioms inspected')
  })
})
