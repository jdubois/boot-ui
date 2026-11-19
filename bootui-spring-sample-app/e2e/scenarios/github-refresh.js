// @ts-check

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
    issues: [
      {
        number: 188,
        title: 'Flaky Playwright run on slow CI agents',
        author: 'julien',
        htmlUrl: 'https://github.com/jdubois/boot-ui/issues/188',
        createdAt: now - 3 * 24 * 60 * 60 * 1000,
        updatedAt: now - 35 * 60 * 1000,
        comments: 4,
        labels: ['bug', 'ci']
      },
      {
        number: 184,
        title: 'Document the GitHub issues drawer',
        author: 'octocat',
        htmlUrl: 'https://github.com/jdubois/boot-ui/issues/184',
        createdAt: now - 5 * 24 * 60 * 60 * 1000,
        updatedAt: now - 4 * 60 * 60 * 1000,
        comments: 1,
        labels: ['docs']
      }
    ],
    securitySignals: [
      {
        label: 'Dependabot alerts',
        status: 'AVAILABLE',
        count: 2,
        unavailableReason: null,
        alerts: [
          {
            number: 7,
            state: 'open',
            packageName: 'org.example:lib',
            ecosystem: 'maven',
            manifestPath: 'pom.xml',
            severity: 'critical',
            ghsaId: 'GHSA-aaaa-bbbb-cccc',
            cveId: 'CVE-2026-1234',
            summary: 'Remote code execution in org.example:lib',
            vulnerableVersionRange: '< 1.2.3',
            firstPatchedVersion: '1.2.3',
            htmlUrl: 'https://github.com/jdubois/boot-ui/security/dependabot/7',
            createdAt: now - 3 * 24 * 60 * 60 * 1000,
            updatedAt: now - 12 * 60 * 60 * 1000
          },
          {
            number: 6,
            state: 'open',
            packageName: 'com.sample:utils',
            ecosystem: 'maven',
            manifestPath: 'bootui-engine/pom.xml',
            severity: 'medium',
            ghsaId: 'GHSA-dddd-eeee-ffff',
            cveId: null,
            summary: 'Denial of service in com.sample:utils',
            vulnerableVersionRange: '>= 2.0.0, < 2.4.1',
            firstPatchedVersion: '2.4.1',
            htmlUrl: 'https://github.com/jdubois/boot-ui/security/dependabot/6',
            createdAt: now - 6 * 24 * 60 * 60 * 1000,
            updatedAt: now - 2 * 24 * 60 * 60 * 1000
          }
        ]
      },
      {label: 'Code scanning alerts', status: 'AVAILABLE', count: 1, unavailableReason: null, alerts: []},
      {label: 'Secret scanning alerts', status: 'AVAILABLE', count: 0, unavailableReason: null, alerts: []}
    ],
    copilotUsage: null,
    warnings: []
  }
}

/**
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} [options]
 */
export function registerGitHubRefreshTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test('GitHub fork context requires an explicit upstream connection and retains data on failure', async ({page}) => {
    await page.clock.install()
    const origin = connectedDashboard()
    origin.repository.fork = true
    const context = {
      selectedRemote: 'origin',
      origin: {name: 'origin', host: 'github.com', fullName: 'jdubois/boot-ui'},
      upstream: {name: 'upstream', host: 'ghe.example.com', fullName: 'team/service'},
      upstreamUnavailableReason: null
    }
    let fail = true
    let upstreamAvailable = true
    /** @type {string[]} */
    const requests = []
    await page.route(
      (url) => url.pathname === `${apiPath}/github`,
      async (route) => {
        requests.push('GET origin')
        await route.fulfill({json: {...origin, repositoryContext: context}})
      }
    )
    await page.route(
      (url) => url.pathname === `${apiPath}/github/refresh`,
      async (route) => {
        const remote = new URL(route.request().url()).searchParams.get('remote') || 'origin'
        requests.push(`${route.request().method()} ${remote}`)
        await route.fulfill({
          json: fail
            ? {...origin, status: 'ERROR', message: 'Upstream refresh failed'}
            : remote === 'origin'
              ? {...origin, repositoryContext: context}
              : !upstreamAvailable
                ? {
                    available: false,
                    connected: false,
                    status: 'UNAVAILABLE',
                    unavailableReason: 'Configured upstream was removed',
                    repository: null,
                    refreshedAt: null,
                    securitySignals: [],
                    repositoryContext: {...context, selectedRemote: 'upstream', upstream: null}
                  }
                : {
                    ...origin,
                    repository: {...origin.repository, host: 'ghe.example.com', fullName: 'team/service', fork: false},
                    repositoryContext: {...context, selectedRemote: 'upstream'}
                  }
        })
      }
    )
    await page.goto(`${uiPath}/#/github`)
    const upstream = page.getByRole('button', {name: 'Connect to configured upstream', exact: true})
    await expect(upstream).toBeVisible()
    await expect(page.getByRole('button', {name: /Dependabot alerts/})).toContainText('Fork data')
    await expect(page.getByText('ghe.example.com/team/service', {exact: true})).toBeVisible()
    await page.clock.runFor(120_000)
    expect(requests).toEqual(['GET origin'])
    await upstream.click()
    await expect(page.getByText('Upstream refresh failed', {exact: false})).toBeVisible()
    await expect(page.getByRole('button', {name: /Dependabot alerts/})).toContainText('Fork data')
    await expect(page.getByRole('button', {name: 'Connect', exact: true})).toBeVisible()
    expect(requests).toEqual(['GET origin', 'POST upstream'])
    await page.clock.runFor(120_000)
    expect(requests).toEqual(['GET origin', 'POST upstream'])
    fail = false
    await upstream.click()
    await expect(page.getByRole('button', {name: 'Connect to origin', exact: true})).toBeVisible()
    await expect(page.getByRole('button', {name: /Dependabot alerts/})).not.toContainText('Fork data')
    await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeVisible()
    await page.clock.runFor(120_000)
    expect(requests).toEqual(['GET origin', 'POST upstream', 'POST upstream', 'POST upstream'])
    upstreamAvailable = false
    await page.getByRole('button', {name: 'Refresh panel', exact: true}).click()
    await expect(page.getByText('Configured upstream was removed', {exact: false})).toBeVisible()
    await page.getByRole('button', {name: 'Connect to origin', exact: true}).click()
    await expect(page.getByRole('button', {name: /Dependabot alerts/})).toContainText('Fork data')
    await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeVisible()
    await page.clock.runFor(120_000)
    expect(requests).toEqual([
      'GET origin',
      'POST upstream',
      'POST upstream',
      'POST upstream',
      'POST upstream',
      'POST origin',
      'POST origin'
    ])
  })

  test('GitHub explicit refresh opens cached data and polls only after opt-in', async ({page}) => {
    await page.clock.install()
    const report = connectedDashboard()
    const payload = JSON.stringify(report)
    const emptyCache = {
      ...report,
      connected: false,
      status: 'READY',
      refreshedAt: null,
      metrics: [],
      pullRequests: [],
      issues: [],
      issueBuckets: [],
      securitySignals: []
    }
    const nativeError = {
      ...emptyCache,
      status: 'ERROR',
      message: 'GitHub core request failed (HTTP 503)',
      refreshedAt: report.refreshedAt + 60_000
    }
    let refreshFails = false
    let readOnly = false
    let cachedReads = 0
    let releaseCacheRead = () => {
      throw new Error('No cached retry is in flight')
    }
    /** @type {string[]} */
    const requests = []
    await page.route(
      (url) => url.pathname === `${apiPath}/github/refresh`,
      async (route) => {
        requests.push(`${route.request().method()} refresh`)
        await route.fulfill({json: refreshFails ? nativeError : report})
      }
    )
    await page.route(
      (url) => url.pathname === `${apiPath}/github`,
      async (route) => {
        requests.push(`${route.request().method()} cache`)
        if (readOnly) {
          cachedReads++
          if (cachedReads === 2) {
            await new Promise((resolve) => {
              releaseCacheRead = () => resolve(null)
            })
          }
          await route.fulfill({json: cachedReads === 1 ? nativeError : emptyCache})
        } else {
          await route.fulfill({contentType: 'application/json', body: payload})
        }
      }
    )

    await page.goto(`${uiPath}/#/github`)
    await expect(page.getByRole('heading', {name: 'GitHub', exact: true})).toBeVisible()
    const refresh = page.getByRole('button', {name: 'Refresh panel', exact: true})
    const connect = page.getByRole('button', {name: 'Connect', exact: true})
    await expect(refresh).toBeEnabled()
    await expect(connect).toBeVisible()
    expect(requests).toEqual(['GET cache'])

    /** @param {'visible' | 'hidden'} value */
    async function visibility(value) {
      await page.evaluate((value) => {
        Object.defineProperty(document, 'visibilityState', {configurable: true, value})
        document.dispatchEvent(new Event('visibilitychange'))
      }, value)
    }

    await page.clock.runFor(120_000)
    await visibility('hidden')
    await visibility('visible')
    await expect(refresh).toBeEnabled()
    expect(requests).toEqual(['GET cache'])

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

    // The issues card opens a drawer that lists the actual open issues.
    await page.getByRole('button', {name: /Open issues/}).click()
    await expect(drawer).toContainText('Open issues')
    await expect(drawer).toContainText('#188 Flaky Playwright run on slow CI agents')
    await expect(drawer).toContainText('#184 Document the GitHub issues drawer')

    // Dependabot card opens a drawer that lists the non-secret alert details.
    await page.getByRole('button', {name: /Dependabot alerts/}).click()
    await expect(drawer).toContainText('org.example:lib')
    await expect(drawer).toContainText('Remote code execution in org.example:lib')
    await expect(drawer).toContainText('GHSA-aaaa-bbbb-cccc')
    await expect(drawer).toContainText('Fixed in: 1.2.3')
    await expect(drawer).toContainText('com.sample:utils')

    // Secret scanning stays count-only with no inline alert details.
    await page.getByRole('button', {name: /Secret scanning alerts/}).click()
    await expect(drawer).toContainText('does not expose secret values')

    await refresh.click()
    await expect(refresh).toBeEnabled()
    expect(requests).toEqual(['GET cache', 'GET cache'])
    await expect(connect).toBeVisible()

    await connect.click()
    await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeVisible()
    expect(requests).toEqual(['GET cache', 'GET cache', 'POST refresh'])
    await page.clock.runFor(60_000)
    await expect(refresh).toBeEnabled()
    expect(requests).toEqual(['GET cache', 'GET cache', 'POST refresh', 'POST refresh'])

    await visibility('hidden')
    await page.clock.runFor(120_000)
    expect(requests).toHaveLength(4)
    await visibility('visible')
    await expect(refresh).toBeEnabled()
    await page.clock.runFor(59_000)
    expect(requests).toHaveLength(4)
    await page.clock.runFor(1_000)
    expect(requests).toEqual(['GET cache', 'GET cache', 'POST refresh', 'POST refresh', 'POST refresh'])

    await page.getByRole('button', {name: 'Stop', exact: true}).click()
    await page.clock.runFor(120_000)
    await visibility('hidden')
    await visibility('visible')
    expect(requests).toHaveLength(5)

    await page.evaluate(() => (window.location.hash = '#/scorecard'))
    await expect(page.getByRole('heading', {name: 'Scorecard', exact: true})).toBeVisible()
    await page.evaluate(() => (window.location.hash = '#/github'))
    await expect(page.getByRole('heading', {name: 'GitHub', exact: true})).toBeVisible()
    await expect(refresh).toBeEnabled()
    await expect(connect).toBeVisible()
    expect(requests).toEqual(['GET cache', 'GET cache', 'POST refresh', 'POST refresh', 'POST refresh', 'GET cache'])

    await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now() + 60_000)))
    const cachedTimestamp = await page.locator('.last-fetched-text').innerText()
    await connect.click()
    await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeVisible()
    refreshFails = true
    await refresh.click()
    const error = page.getByRole('alert')
    await expect(error).toHaveCount(1)
    await expect(error).toContainText(nativeError.message)
    await expect(openPrsCard.locator('.display-6')).toHaveText('2')
    await expect(page.locator('.last-fetched-text')).toHaveText(cachedTimestamp)
    const retry = error.getByRole('button', {name: /Retry$/})
    await expect(retry).toHaveCount(1)
    refreshFails = false
    await retry.click()
    await expect(error).toHaveCount(0)
    await expect(page.getByRole('button', {name: 'Stop', exact: true})).toBeVisible()
    expect(requests.slice(-2)).toEqual(['POST refresh', 'POST refresh'])
    await page.clock.resume()

    const reason = 'GitHub is read-only by configuration.'
    let manifestReads = 0
    await page.route(
      (url) => url.pathname === `${apiPath}/panels`,
      async (route) => {
        const response = await route.fetch()
        expect(response.ok()).toBe(true)
        const body = await response.json()
        expect(body.panels.some((panel) => panel.id === 'github')).toBe(true)
        body.panels = body.panels.map((panel) =>
          panel.id === 'github' ? {...panel, readOnly: true, readOnlyReason: reason} : panel
        )
        manifestReads++
        await route.fulfill({response, json: body})
      }
    )
    readOnly = true
    const previousPosts = requests.filter((request) => request === 'POST refresh').length
    await page.reload()
    await expect.poll(() => manifestReads).toBe(1)
    await expect(error).toHaveCount(1)
    await expect(error).toContainText(nativeError.message)
    await expect(page.locator('.last-fetched-text')).toHaveCount(0)
    await expect(refresh).toBeEnabled()
    const cacheRetry = error.getByRole('button', {name: 'Retry', exact: true})
    await expect(cacheRetry).toHaveCount(1)
    await cacheRetry.focus()
    await expect(cacheRetry).toBeFocused()
    await page.keyboard.press('Enter')
    await expect.poll(() => cachedReads).toBe(2)
    await expect(cacheRetry).toBeDisabled()
    releaseCacheRead()
    await expect(error).toHaveCount(0)
    await expect(cacheRetry).toHaveCount(0)
    await expect(page.getByText(reason, {exact: true})).toBeVisible()
    await expect(page.getByRole('button', {name: 'Connect', exact: true})).toHaveCount(0)
    await page.clock.runFor(120_000)
    await visibility('hidden')
    await visibility('visible')
    expect(cachedReads).toBe(2)
    expect(requests.slice(-2)).toEqual(['GET cache', 'GET cache'])
    expect(requests.filter((request) => request === 'POST refresh')).toHaveLength(previousPosts)
  })
}
