// @ts-check
import {expect, test} from './fixtures.js'

test.skip(process.env.BOOTUI_OSV_LIVE !== '1', 'Runs only in the scheduled live OSV smoke workflow')

test('live OSV protocol smoke completes from the user-initiated Quarkus scan', async ({openView, page}) => {
  await openView('vulnerabilities', 'Vulnerabilities')

  const dependenciesMetric = page.locator('.advisor-summary__metric', {hasText: 'Dependencies'})
  expect(Number((await dependenciesMetric.locator('dd').textContent())?.trim())).toBeGreaterThan(0)

  await page.getByRole('button', {name: 'Scan with OSV.dev'}).click()

  await expect(
    page.locator('.advisor-summary__metric--status .badge', {hasText: /^(Scan complete|Incomplete)$/})
  ).toBeVisible({
    timeout: 45_000
  })
  await expect(page.locator('.advisor-summary__metric', {hasText: 'Scanner'}).locator('dd')).toHaveText('OSV.dev')
})
