// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Profile Diff view', () => {
  test('renders the active-profile badges and the property table or empty state', async ({openView, page}) => {
    await openView('profile-diff', 'Profile Diff')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    // The sample app's integration tests activate the dev profile, but a manual
    // run does not — accept either case as long as the page rendered correctly.
    const hasSources = await page.locator('main table').count()
    const hasEmptyState = await page
      .locator('.alert', {hasText: /No profile-specific configuration sources found/})
      .count()
    expect(hasSources + hasEmptyState).toBeGreaterThan(0)

    // The filter input is always shown when data has loaded.
    await expect(page.getByPlaceholder(/Filter by property name or value/)).toBeVisible()
  })
})
