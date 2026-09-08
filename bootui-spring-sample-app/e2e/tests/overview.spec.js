// @ts-check
import {expect, test} from './fixtures.js'
import {registerAdvisorScoringTests, stubUnscannedAdvisorReports} from '../scenarios/advisor-scoring.js'

registerAdvisorScoringTests(test, expect)

test.describe('Overview view', () => {
  test.beforeEach(async ({page}) => stubUnscannedAdvisorReports(page))

  test('renders the panel header and the scanner dashboard', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    await expect(page.locator('.topbar-title')).toContainText('bootui-sample')
    await expect(page.locator('.topbar-subtitle')).toContainText(/Spring Boot/)
    await expect(page.locator('.topbar-subtitle')).toContainText(/Java/)

    // Panel header introduces the advisor dashboard.
    await expect(page.locator('.panel-header')).toContainText('Inspect retained findings')

    // Overall score starts unscored alongside the on-demand "Run all scanners" action.
    const overall = page.locator('.overall-card').first()
    await expect(overall).not.toContainText('Known-findings score')
    await expect(overall).toContainText('Overall score')
    await expect(overall).toContainText('Not scored')
    await expect(overall.getByRole('img')).toHaveCount(0)
    await expect(overall.getByRole('button', {name: /Run all scanners/})).toBeVisible()

    // At least the Architecture scanner card is shown for the sample app.
    const architectureCard = page.locator('.scanner-card', {hasText: 'Architecture'})
    await expect(architectureCard).toBeVisible()
    await expect(architectureCard.getByRole('button', {name: /Run scan/})).toBeVisible()
  })

  test('does not run scanners until requested, then scores on demand', async ({openView, page}) => {
    await openView('overview', 'Overview')

    // The cached reports are explicitly unscanned, independently of earlier tests' scans.
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

  test('GitHub card exposes a connect button when the repository is detected', async ({openView, page}) => {
    await openView('overview', 'Overview')

    const githubCard = page.locator('.scanner-card', {hasText: 'GitHub'})
    if (await githubCard.count()) {
      await expect(githubCard.getByRole('button', {name: /Connect to GitHub/})).toBeVisible()
    }
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

  test('links to the BootUI GitHub project', async ({openView}) => {
    const page = await openView('overview', 'Overview')

    await expect(page.getByRole('link', {name: /View BootUI on GitHub/})).toHaveAttribute(
      'href',
      'https://github.com/jdubois/boot-ui'
    )
  })

  test('returns to the host application homepage', async ({openView, page}) => {
    await openView('overview', 'Overview')

    await expect(page.locator('meta[name="bootui-application-path"]')).toHaveAttribute('content', '/')
    const home = page.getByRole('link', {name: 'Application homepage'})
    await expect(home).toHaveAttribute('href', '/')
    await home.click()

    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByRole('heading', {name: 'Welcome to the BootUI sample app'})).toBeVisible()
  })
})
