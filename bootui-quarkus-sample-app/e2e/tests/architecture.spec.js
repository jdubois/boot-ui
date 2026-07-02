// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Architecture advisor runs the shared engine's curated ArchUnit rules against the sample app's own
 * classes, discovered at build time from the Jandex index (no runtime package scanning on Quarkus). The
 * sample's ArchitectureIssuesResource intentionally triggers two rules so a real scan has deterministic
 * findings to assert on. Dismiss/restore is backed by the Quarkus DismissedRulesResource, which persists
 * to .bootui/boot-ui.yml — we always restore so we don't leak state into later tests in this file/run.
 */
test.describe('Architecture advisor (Quarkus)', () => {
  test.afterEach(async ({page}) => {
    // Best-effort cleanup: make sure ARCH-CODE-001 isn't left dismissed for later assertions/runs.
    await page.request.delete('/bootui/api/dismissed-rules/ARCH-CODE-001').catch(() => {})
  })

  test('runs architecture checks and renders real ArchUnit violations', async ({openView, page}) => {
    await openView('architecture', 'Architecture')

    await page.getByRole('button', {name: 'Run architecture checks'}).click()

    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})
    await expect(page.locator('main')).toContainText('Classes analysed')

    // ArchitectureIssuesResource.errors() writes to System.out and field-injects a repository, so both
    // rules should fire for real against the sample app's own bytecode.
    const codeStreamsRow = page.locator('.list-group-item', {hasText: 'ARCH-CODE-001'})
    await expect(codeStreamsRow).toContainText('Classes should not access standard streams')
    await expect(codeStreamsRow).toContainText('ArchitectureIssuesResource')

    const fieldInjectionRow = page.locator('.list-group-item', {hasText: 'ARCH-CODE-016'})
    await expect(fieldInjectionRow).toContainText('Classes should not use standard-annotation field injection')
  })

  test('dismisses a rule violation and restores it', async ({openView, page}) => {
    await openView('architecture', 'Architecture')
    await page.getByRole('button', {name: 'Run architecture checks'}).click()

    const codeStreamsRow = page.locator('.list-group-item', {hasText: 'ARCH-CODE-001'})
    await expect(codeStreamsRow).toBeVisible({timeout: 20_000})

    await codeStreamsRow.getByRole('button', {name: 'Dismiss'}).click()

    // The dismissed rule moves out of the scored results into its own "Dismissed rules" section.
    const dismissedSection = page.locator('.card-header', {hasText: 'Dismissed rules'})
    await expect(dismissedSection).toBeVisible()
    const dismissedRow = page.locator('.list-group-item.opacity-50', {hasText: 'ARCH-CODE-001'})
    await expect(dismissedRow).toBeVisible()
    await expect(page.locator('.list-group-item:not(.opacity-50)', {hasText: 'ARCH-CODE-001'})).toHaveCount(0)

    await dismissedRow.getByRole('button', {name: 'Restore'}).click()

    // Restoring brings it back into the scored violations and the dismissed section shrinks/disappears.
    await expect(page.locator('.list-group-item:not(.opacity-50)', {hasText: 'ARCH-CODE-001'})).toBeVisible()
  })
})
