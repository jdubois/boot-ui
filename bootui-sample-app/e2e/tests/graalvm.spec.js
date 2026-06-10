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

    // The metadata, Dockerfile and combined artifacts live in a three-drawer accordion. The combined
    // "Both files" drawer is open by default and offers a single action that writes both artifacts into
    // the source tree. Drawers are located by their header button so the shared "Write into project" label
    // and the filenames referenced in the combined summary don't make the lookups ambiguous. None of the
    // write buttons are clicked here, to avoid writing files into the working tree.
    const bothDrawer = page
      .locator('.accordion-item')
      .filter({has: page.getByRole('button', {name: 'Both files', exact: true})})
    await expect(bothDrawer.getByText("writes both directly into the project's source tree")).toBeVisible()
    await expect(bothDrawer.getByRole('button', {name: 'Write into project'})).toBeVisible()

    // The sample app runs from an exploded build (spring-boot:run), so the reachability-metadata.json drawer
    // offers to write the generated scaffold into the source tree and surfaces the resolved target path.
    // Expand it first since the combined drawer is open by default.
    const metadataDrawer = page
      .locator('.accordion-item')
      .filter({has: page.getByRole('button', {name: 'reachability-metadata.json', exact: true})})
    await metadataDrawer.getByRole('button', {name: 'reachability-metadata.json'}).click()
    await expect(metadataDrawer.getByRole('button', {name: 'Write into project'})).toBeVisible()
    await expect(metadataDrawer.getByText('Detected source tree:')).toBeVisible()

    // The scaffold-placement hint substitutes the resolved Maven coordinates (groupId/artifactId)
    // rather than leaving the <groupId>/<artifactId> placeholders.
    const placement = metadataDrawer.locator('p', {hasText: 'then place it under'})
    await expect(placement).toContainText(
      'src/main/resources/META-INF/native-image/com.julien-dubois.bootui/bootui-sample-app/'
    )
    await expect(placement).not.toContainText('<groupId>')

    // A tailored native-image Dockerfile-native is generated alongside the metadata scaffold. Its drawer
    // is collapsed by default, so expand it first. The preview embeds the resolved artifactId so the
    // COPY/cp paths point at the real executable name.
    const dockerDrawer = page
      .locator('.accordion-item')
      .filter({has: page.getByRole('button', {name: 'Dockerfile-native', exact: true})})
    await dockerDrawer.getByRole('button', {name: 'Dockerfile-native'}).click()
    await expect(dockerDrawer.getByRole('link', {name: 'Download'})).toBeVisible()
    await expect(dockerDrawer.getByRole('button', {name: 'Write into project'})).toBeVisible()
    await expect(dockerDrawer.locator('pre')).toContainText('FROM ghcr.io/graalvm/graalvm-community')
    await expect(dockerDrawer.locator('pre')).toContainText('target/bootui-sample-app')
  })
})
