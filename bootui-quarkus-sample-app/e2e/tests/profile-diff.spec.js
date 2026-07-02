// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Profile Diff view (Quarkus)', () => {
  test('renders the active dev profile badge and its real override table', async ({openView, page}) => {
    await openView('profile-diff', 'Profile Diff')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    // Quarkus has no separate application-dev.properties file; %profile.-prefixed keys in the
    // single application.properties serve the same purpose (QuarkusConfigProvider.profileSources()).
    // The e2e suite always runs the sample app under `quarkus:dev`, so the active profile is
    // deterministically "dev" -- unlike Spring's spec (which accepts either state depending on how
    // it was launched), this can assert the real content directly.
    await expect(page.locator('.badge', {hasText: 'dev'}).first()).toBeVisible()

    const sourceHeading = page.locator('small.text-muted.font-monospace', {hasText: 'application.properties (%dev)'})
    await expect(sourceHeading).toBeVisible()

    const table = page.locator('table').first()
    await expect(table).toContainText('sample.retries')
    await expect(table).toContainText('1')

    await expect(page.locator('.alert', {hasText: /No profile-specific configuration sources found/})).toHaveCount(0)
    await expect(page.getByPlaceholder(/Filter by property name or value/)).toBeVisible()
  })

  test('filtering narrows the profile-specific property table', async ({openView, page}) => {
    await openView('profile-diff', 'Profile Diff')

    const filter = page.getByPlaceholder(/Filter by property name or value/)
    const table = page.locator('table').first()
    await expect(table).toContainText('sample.retries')

    await filter.fill('sample.retries')
    await expect(table).toContainText('sample.retries')

    await filter.fill('no-such-property-xyz')
    await expect(page.locator('.alert', {hasText: /No profile-specific configuration sources found/})).toBeVisible()
  })
})
