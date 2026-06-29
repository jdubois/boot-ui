// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Spring-only panels have no meaningful Quarkus equivalent. Rather than hide them — which would make
 * the two UIs diverge — BootUI keeps them in the sidebar and explains, in-panel, why they do not apply
 * on Quarkus. These checks pin that honest-degradation behavior so a Spring-only panel never just
 * breaks on Quarkus.
 */
const NOT_APPLICABLE = [
  {id: 'devtools', label: 'DevTools'},
  {id: 'conditions', label: 'Conditions'},
  {id: 'http-sessions', label: 'HTTP Sessions'},
  {id: 'graalvm', label: 'GraalVM'},
  {id: 'spring-security', label: 'Spring Security'},
  {id: 'data', label: 'Spring Data'}
]

test.describe('Spring-only panels degrade honestly on Quarkus', () => {
  for (const panel of NOT_APPLICABLE) {
    test(`${panel.label} explains it is not applicable on Quarkus`, async ({page}) => {
      await page.goto(`/bootui/#/${panel.id}`)

      const alert = page.locator('.panel-availability-alert')
      await expect(alert).toBeVisible()
      await expect(alert).toContainText('Not applicable on Quarkus')
    })
  }

  test('the sidebar collects the Spring-only panels in the "Disabled / unavailable" group', async ({page}) => {
    await page.goto('/bootui/')

    const toggle = page.getByRole('button', {name: /Disabled \/ unavailable\s+\d+/})
    await expect(toggle).toBeVisible()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')

    // DevTools is Spring-only, so it must not be visible until the disabled group is expanded.
    await expect(page.locator('aside .nav-link', {hasText: 'DevTools'})).not.toBeVisible()

    await toggle.click()
    await expect(page.locator('aside .nav-link', {hasText: 'DevTools'})).toBeVisible()
  })
})
