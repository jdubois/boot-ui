// @ts-check
import {expect, test} from './fixtures.js'

test.describe('CRaC view', () => {
  test('shows runtime status and runs the readiness scan', async ({openView, page}) => {
    // The readiness scan surveys the host application's classes and can be slow on CI hardware.
    test.setTimeout(120_000)

    await openView('crac', 'CRaC')

    // The runtime-status card renders without a scan (always read-only).
    await expect(page.locator('main')).toContainText('org.crac API')

    // The heuristic disclaimer is always present.
    await expect(page.locator('main')).toContainText('Heuristic readiness checks.')

    await page.getByRole('button', {name: 'Run readiness checks'}).click()

    // The pre-scan empty state clears once the scan completes.
    await expect(page.getByText('No readiness data yet')).toHaveCount(0, {timeout: 45_000})

    const checksRun = page.locator('.card', {hasText: 'Checks run'}).locator('.display-6')
    await expect
      .poll(async () => Number.parseInt((await checksRun.innerText()).trim(), 10) || 0, {timeout: 15_000})
      .toBeGreaterThan(0)

    // The scan-status card surfaces a status badge once the scan completes.
    const scanStatusCard = page.locator('.card', {hasText: 'Scan status'}).first()
    await expect(scanStatusCard.locator('.badge').first()).toBeVisible()
  })

  test('offers the generated CRaC container assets', async ({openView, page}) => {
    await openView('crac', 'CRaC')

    const assets = page.locator('.card', {hasText: 'Container assets'})
    await expect(assets).toContainText('Container assets')

    // Both download links are present (no scan required).
    await expect(page.locator('a[href="api/crac/dockerfile"]')).toBeVisible()
    await expect(page.locator('a[href="api/crac/entrypoint"]')).toHaveCount(1)

    // The Dockerfile preview shows the CRaC-enabled runtime image.
    await expect(assets).toContainText('bellsoft/liberica-runtime-container')

    // The checkpoint-and-run.sh accordion entry opens its entrypoint preview.
    await page.getByRole('button', {name: 'checkpoint-and-run.sh'}).click()
    await expect(assets).toContainText('CRaCCheckpointTo')
  })
})
