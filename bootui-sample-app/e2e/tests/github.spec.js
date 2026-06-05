// @ts-check
import {expect, test} from './fixtures.js'

// A connected GitHub dashboard cannot be reproduced deterministically in CI (it depends
// on an authenticated token and live GitHub responses), so this spec mocks the bounded
// GitHub API the same way the documentation screenshot generator does and asserts that
// the panel renders the repository, credential, metrics, and an interactive drawer.
function connectedDashboard() {
  const now = Date.now()
  return {
    available: true,
    unavailableReason: null,
    connected: true,
    status: 'CONNECTED',
    message: null,
    refreshedAt: now - 45_000,
    repository: {
      owner: 'jdubois',
      name: 'boot-ui',
      fullName: 'jdubois/boot-ui',
      host: 'github.com',
      apiBaseUrl: 'https://api.github.com/',
      htmlUrl: 'https://github.com/jdubois/boot-ui',
      defaultBranch: 'main',
      localBranch: 'jdubois/panel-e2e-coverage',
      upstreamBranch: 'main',
      visibility: 'public',
      privateRepository: false,
      fork: false,
      archived: false,
      pushedAt: now - 18 * 60 * 1000,
      stars: 128,
      forks: 14,
      watchers: 11,
      openIssues: 9,
      latestRelease: 'v0.4.0'
    },
    credential: {source: 'GITHUB_TOKEN', authenticated: true, login: 'local-dev', scopes: 'repo, workflow'},
    metrics: [
      {label: 'Open pull requests', value: '2', detail: 'Bounded live queue', tone: 'primary'},
      {label: 'Open issues', value: '9', detail: 'Grouped by label and age', tone: 'info'},
      {label: 'Workflow failures', value: '1', detail: 'Latest run per workflow', tone: 'danger'}
    ],
    quotas: [],
    pullRequests: [
      {
        number: 211,
        title: 'Update implementation plan roadmap',
        author: 'julien',
        draft: false,
        htmlUrl: 'https://github.com/jdubois/boot-ui/pull/211',
        updatedAt: now - 20 * 60 * 1000,
        reviewDecision: 'APPROVED',
        checksConclusion: 'success',
        labels: ['docs']
      },
      {
        number: 210,
        title: 'Update GitHub Actions execution drawer',
        author: 'julien',
        draft: false,
        htmlUrl: 'https://github.com/jdubois/boot-ui/pull/210',
        updatedAt: now - 90 * 60 * 1000,
        reviewDecision: null,
        checksConclusion: 'success',
        labels: ['github']
      }
    ],
    workflowRuns: [],
    workflows: [],
    issueBuckets: [
      {label: 'Open issues', count: 9, tone: 'primary'},
      {label: 'Bug', count: 2, tone: 'danger'}
    ],
    securitySignals: [
      {label: 'Dependabot alerts', status: 'AVAILABLE', count: 0, unavailableReason: null},
      {label: 'Code scanning alerts', status: 'AVAILABLE', count: 1, unavailableReason: null},
      {label: 'Secret scanning alerts', status: 'AVAILABLE', count: 0, unavailableReason: null}
    ],
    copilotUsage: null,
    warnings: []
  }
}

test.describe('GitHub view', () => {
  test('renders the connected dashboard and opens the pull-request drawer', async ({openView, page}) => {
    const payload = JSON.stringify(connectedDashboard())
    let refreshMethod = null
    // Trusted localhost sessions refresh via POST on mount; the GET endpoint is the read-only
    // fallback. Both are mocked, and the refresh method is captured so the spec can confirm the
    // panel used the POST refresh path.
    await page.route(
      (url) => url.pathname === '/bootui/api/github/refresh',
      async (route) => {
        refreshMethod = route.request().method()
        await route.fulfill({contentType: 'application/json', body: payload})
      }
    )
    await page.route(
      (url) => url.pathname === '/bootui/api/github',
      async (route) => {
        await route.fulfill({contentType: 'application/json', body: payload})
      }
    )

    await openView('github', 'GitHub')

    // Repository summary card.
    await expect(page.getByRole('link', {name: 'jdubois/boot-ui'})).toBeVisible()
    await expect(page.locator('.badge', {hasText: 'CONNECTED'})).toBeVisible()
    const repositoryCard = page.locator('.card', {hasText: 'Repository'}).first()
    await expect(repositoryCard).toContainText('128')
    await expect(repositoryCard).toContainText('14')

    // Credential card confirms the token stayed server-side but reports the authenticated source.
    const credentialCard = page.locator('.card', {hasText: 'Credential'}).first()
    await expect(credentialCard).toContainText('Authenticated')
    await expect(credentialCard).toContainText('GITHUB_TOKEN')

    // Summary metric and security-signal cards render the mocked values.
    const openPrsCard = page.getByRole('button', {name: /Open pull requests/})
    await expect(openPrsCard).toBeVisible()
    await expect(openPrsCard.locator('.display-6')).toHaveText('2')
    const codeScanningCard = page.getByRole('button', {name: /Code scanning alerts/})
    await expect(codeScanningCard).toBeVisible()
    await expect(codeScanningCard.locator('.display-6')).toHaveText('1')

    // Opening the pull-request drawer is a meaningful read interaction.
    await page.getByRole('button', {name: /Open pull requests/}).click()
    const drawer = page.locator('.details-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('Open pull requests')
    await expect(drawer).toContainText('#211 Update implementation plan roadmap')
    await expect(drawer).toContainText('#210 Update GitHub Actions execution drawer')

    // The trusted panel reached the live data through the POST refresh endpoint.
    expect(refreshMethod).toBe('POST')
  })
})
