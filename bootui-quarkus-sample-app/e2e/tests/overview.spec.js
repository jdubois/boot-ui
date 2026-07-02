// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Overview view (Quarkus)', () => {
  test('renders the panel header and the scanner dashboard', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    await expect(page.locator('.topbar-title')).toContainText('bootui-quarkus-sample')
    await expect(page.locator('.topbar-subtitle')).toContainText(/Quarkus/)
    await expect(page.locator('.topbar-subtitle')).toContainText(/Java/)

    // Panel header introduces the advisor dashboard.
    await expect(page.locator('.panel-header')).toContainText('Run the advisors to score')

    // Overall score box and the on-demand "Run all scanners" action.
    const overall = page.locator('.overall-card').first()
    await expect(overall).toContainText('Overall score')
    await expect(overall.getByRole('button', {name: /Run all scanners/})).toBeVisible()

    // At least the Architecture scanner card is shown for the sample app.
    const architectureCard = page.locator('.scanner-card', {hasText: 'Architecture'})
    await expect(architectureCard).toBeVisible()
    await expect(architectureCard.getByRole('button', {name: /Run scan/})).toBeVisible()
  })

  test('renders the shared Spring advisor card under its platform-aware "Quarkus" label', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    // The `spring` scanner id/endpoint is reused on Quarkus (see routes.js meta.titleByPlatform),
    // but the card title itself must render the Quarkus-specific label, not "Spring".
    const quarkusCard = page.locator('.scanner-card', {hasText: 'Quarkus'})
    await expect(quarkusCard).toBeVisible()
    await expect(quarkusCard.getByRole('button', {name: /Run scan/})).toBeVisible()
    await expect(page.locator('.scanner-card', {hasText: /^Spring$/})).toHaveCount(0)
  })

  test('does not run scanners until requested, then scores on demand', async ({openView, page}) => {
    await openView('overview', 'Overview')

    // Nothing is scored on load.
    await expect(page.locator('.overall-card').first()).toContainText('0 of')

    const architectureCard = page.locator('.scanner-card', {hasText: 'Architecture'})
    const scanResponse = page.waitForResponse(
      (res) => res.url().endsWith('/bootui/api/architecture/scan') && res.request().method() === 'POST'
    )
    await architectureCard.getByRole('button', {name: /Run scan/}).click()
    expect((await scanResponse).ok()).toBeTruthy()

    // The card resolves to a numeric score out of 100.
    await expect(architectureCard).toContainText('/ 100')
  })

  test('GitHub card exposes a connect button since a repository is detected', async ({openView, page}) => {
    await openView('overview', 'Overview')

    // The sample app is checked out from a real git repository, so the GitHub panel is
    // dynamically available (unlike the Spring spec, this is asserted unconditionally here).
    const githubCard = page.locator('.scanner-card', {hasText: 'GitHub'})
    await expect(githubCard).toBeVisible()
    await expect(githubCard.getByRole('button', {name: /Connect to GitHub/})).toBeVisible()
  })

  test('reveals an MCP Server tip after running all scanners', async ({openView, page}) => {
    await openView('overview', 'Overview')

    const overall = page.locator('.overall-card').first()
    await expect(page.locator('.mcp-tip')).toHaveCount(0)

    await overall.getByRole('button', {name: /Run all scanners/}).click()

    const tip = page.locator('.mcp-tip')
    await expect(tip).toBeVisible()
    await expect(tip).toContainText('BootUI MCP Server')
    await expect(tip.getByRole('link', {name: 'BootUI MCP Server'})).toHaveAttribute('href', /#\/mcp-server$/)

    await tip.getByRole('button', {name: 'Dismiss tip'}).click()
    await expect(tip).toHaveCount(0)
  })

  test('links to the BootUI GitHub project with the Quarkus-flavored tagline', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    await expect(page.getByRole('link', {name: /The missing developer UI for Quarkus/})).toHaveAttribute(
      'href',
      'https://github.com/jdubois/boot-ui'
    )
  })
})
