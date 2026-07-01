// @ts-check
import {expect, test} from './fixtures.js'

/**
 * On Quarkus the Dev Services panel renders the build-time DevServicesResultBuildItem snapshot — the
 * throwaway PostgreSQL container Quarkus Dev Services starts for the sample. Live logs and per-service
 * restart are owned by Quarkus, not BootUI, and the shared (platform-aware) DevServices.vue says so.
 */
test.describe('Dev Services view (Quarkus)', () => {
  test.beforeEach(async ({page}) => {
    // Dev Services need Docker/Podman; skip cleanly where they could not start rather than flake.
    const response = await page.request.get('/bootui/api/panels')
    const report = await response.json()
    const devServices = report.panels.find((panel) => panel.id === 'dev-services')
    test.skip(!devServices?.available, 'Dev Services unavailable (no Docker/Podman in this environment)')
  })

  test('renders the Quarkus Dev Services snapshot and platform-aware copy', async ({openView, page}) => {
    await openView('dev-services', /^Dev Services/)

    await expect(page.locator('.alert-info')).toContainText('build-time snapshot')
    await expect(page.locator('.alert-info')).toContainText('managed by Quarkus')

    // The panel is only available when a Dev Services snapshot exists, so at least one service row
    // (the PostgreSQL Dev Service) is present rather than the empty state.
    await expect(page.locator('tbody tr').first()).toBeVisible({timeout: 15_000})
  })
})
