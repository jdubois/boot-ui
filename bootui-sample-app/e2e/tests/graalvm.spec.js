// @ts-check
import {expect, test} from './fixtures.js'

test.describe('GraalVM view', () => {
  test('runs the native-image readiness scan and reports analysed classes', async ({openView, page}) => {
    // The native-image readiness scan can take a while on slower CI hardware.
    test.setTimeout(120_000)

    await openView('graalvm', 'GraalVM')

    // The read-only disclaimer renders once the report loads. The pre-scan empty state is not
    // asserted because the readiness report caches the last scan, so a reused or retried
    // server may already have scan data on mount.
    await expect(page.locator('main')).toContainText('Heuristic readiness checks.')

    await page.getByRole('button', {name: 'Run readiness checks'}).click()

    // The scan surveys the host application's own classes, so the empty state clears.
    await expect(page.getByText('No readiness data yet')).toHaveCount(0, {timeout: 45_000})

    const checksRun = page.locator('.card', {hasText: 'Checks run'}).locator('.display-6')
    await expect
      .poll(async () => Number.parseInt((await checksRun.innerText()).trim(), 10) || 0, {timeout: 15_000})
      .toBeGreaterThan(0)

    const classesAnalyzed = page.locator('.card', {hasText: 'Classes analysed'}).locator('.display-6')
    await expect
      .poll(async () => Number.parseInt((await classesAnalyzed.innerText()).trim(), 10) || 0)
      .toBeGreaterThan(0)

    // The scan-status card surfaces a status badge once the scan completes.
    const scanStatusCard = page.locator('.card', {hasText: 'Scan status'}).first()
    await expect(scanStatusCard.locator('.badge').first()).toBeVisible()

    // The readiness-concerns section renders once the scan completes.
    await expect(page.getByText('Readiness concerns')).toBeVisible()

    // The sample app runs from an exploded build (spring-boot:run), so the panel offers to install
    // the generated scaffold into the source tree and surfaces the resolved target path. The button
    // is intentionally not clicked here to avoid writing a metadata file into the working tree.
    await expect(page.getByRole('button', {name: 'Install into source tree'})).toBeVisible()
    await expect(page.getByText('Detected source tree:')).toBeVisible()
  })
})
