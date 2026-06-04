// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Overview view', () => {
  test('renders the welcome hero and the scanner dashboard', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    await expect(page.locator('.topbar-title')).toContainText('bootui-sample')
    await expect(page.locator('.topbar-subtitle')).toContainText(/Spring Boot/)
    await expect(page.locator('.topbar-subtitle')).toContainText(/Java/)

    // Welcome hero is preserved.
    await expect(page.locator('.overview-hero')).toContainText('Understand your Spring Boot app in minutes.')

    // Overall score box and the on-demand "Run all scanners" action.
    const overall = page.locator('.overall-card')
    await expect(overall).toContainText('Overall score')
    await expect(overall.getByRole('button', {name: /Run all scanners/})).toBeVisible()

    // At least the Architecture scanner card is shown for the sample app.
    const architectureCard = page.locator('.scanner-card', {hasText: 'Architecture'})
    await expect(architectureCard).toBeVisible()
    await expect(architectureCard.getByRole('button', {name: /Run scan/})).toBeVisible()
  })

  test('does not run scanners until requested, then scores on demand', async ({openView, page}) => {
    await openView('overview', 'Overview')

    // Nothing is scored on load.
    await expect(page.locator('.overall-card')).toContainText('0 of')

    const architectureCard = page.locator('.scanner-card', {hasText: 'Architecture'})
    const scanResponse = page.waitForResponse(
      (res) => res.url().endsWith('/bootui/api/architecture/scan') && res.request().method() === 'POST'
    )
    await architectureCard.getByRole('button', {name: /Run scan/}).click()
    expect((await scanResponse).ok()).toBeTruthy()

    // The card resolves to a numeric score out of 100.
    await expect(architectureCard).toContainText('/ 100')
  })

  test('GitHub card exposes a connect button when the repository is detected', async ({openView, page}) => {
    await openView('overview', 'Overview')

    const githubCard = page.locator('.scanner-card', {hasText: 'GitHub'})
    if (await githubCard.count()) {
      await expect(githubCard.getByRole('button', {name: /Connect to GitHub/})).toBeVisible()
    }
  })

  test('hero links to the BootUI GitHub project', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    await expect(page.getByRole('link', {name: /BootUI GitHub project/})).toHaveAttribute(
      'href',
      'https://github.com/jdubois/boot-ui'
    )
  })
})
