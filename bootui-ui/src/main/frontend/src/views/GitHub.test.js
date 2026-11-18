import {enableAutoUnmount, flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import GitHub from './GitHub.vue'
import PanelHeader from './components/PanelHeader.vue'

enableAutoUnmount(afterEach)

function githubReport(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    connected: false,
    status: 'READY',
    message: 'Click Connect to load live GitHub metrics and quota state.',
    refreshedAt: null,
    repository: {
      owner: 'jdubois',
      name: 'boot-ui',
      fullName: 'jdubois/boot-ui',
      host: 'github.com',
      apiBaseUrl: 'https://api.github.com/',
      htmlUrl: 'https://github.com/jdubois/boot-ui',
      defaultBranch: null,
      localBranch: 'main',
      upstreamBranch: 'main',
      visibility: null,
      privateRepository: null,
      fork: null,
      archived: null,
      pushedAt: null,
      stars: null,
      forks: null,
      watchers: null,
      openIssues: null,
      latestRelease: null
    },
    credential: {source: 'not connected', authenticated: false, login: null, scopes: null},
    metrics: [],
    quotas: [],
    pullRequests: [],
    workflowRuns: [],
    workflows: [],
    issueBuckets: [],
    securitySignals: [],
    copilotUsage: {
      status: 'UNAVAILABLE',
      scope: null,
      summary: 'Copilot usage report unavailable',
      reportStartDay: null,
      reportEndDay: null,
      downloadLinkCount: null,
      documentationUrl: 'https://docs.github.com/en/rest/copilot/copilot-usage-metrics',
      unavailableReason: 'Connect to GitHub to probe Copilot usage reports'
    },
    warnings: [],
    ...overrides
  }
}

function connectedReport() {
  return githubReport({
    connected: true,
    status: 'CONNECTED',
    message: null,
    refreshedAt: 1780563600000,
    repository: {
      ...githubReport().repository,
      defaultBranch: 'main',
      visibility: 'public',
      stars: 12,
      forks: 3,
      watchers: 4,
      pushedAt: 1780560000000
    },
    credential: {source: 'GITHUB_TOKEN', authenticated: true, login: null, scopes: 'repo, workflow'},
    metrics: [
      {label: 'Open pull requests', value: '1', detail: 'Bounded live queue', tone: 'primary'},
      {label: 'Open issues', value: '2', detail: 'Issues returned by this refresh', tone: 'info'},
      {label: 'Workflow failures', value: '1', detail: 'Latest run per workflow/branch', tone: 'danger'},
      {label: 'Core quota remaining', value: '500', detail: 'GitHub REST core resource', tone: 'success'},
      {label: 'Copilot usage', value: 'Available', detail: '2026-05-07 to 2026-06-03', tone: 'info'}
    ],
    quotas: [
      {
        key: 'core',
        label: 'Core',
        category: 'Rate limit',
        scope: 'credential',
        limit: 5000,
        used: 4500,
        remaining: 500,
        resetAt: 1893456000000,
        percentUsed: 90,
        status: 'OK',
        unavailableReason: null
      },
      {
        key: 'code_scanning_autofix',
        label: 'Code Scanning Autofix',
        category: 'Rate limit',
        scope: 'credential',
        limit: 10,
        used: 0,
        remaining: 10,
        resetAt: 1893456000000,
        percentUsed: 0,
        status: 'OK',
        unavailableReason: null
      },
      {
        key: 'search',
        label: 'Search',
        category: 'Rate limit',
        scope: 'credential',
        limit: 30,
        used: 30,
        remaining: 0,
        resetAt: 1893456000000,
        percentUsed: 100,
        status: 'OK',
        unavailableReason: null
      }
    ],
    pullRequests: [
      {
        number: 42,
        title: 'Add dashboard',
        author: 'alice',
        draft: false,
        htmlUrl: 'https://github.com/jdubois/boot-ui/pull/42',
        updatedAt: 1780561800000,
        reviewDecision: null,
        checksConclusion: null,
        labels: ['feature']
      }
    ],
    workflowRuns: [
      {
        id: 10,
        workflowId: 100,
        name: 'Build',
        displayTitle: 'Run tests on pull request',
        runNumber: 42,
        event: 'pull_request',
        status: 'completed',
        conclusion: 'timed_out',
        branch: 'main',
        actor: 'alice',
        htmlUrl: 'https://github.com/jdubois/boot-ui/actions/runs/10',
        createdAt: 1780560000000,
        updatedAt: 1780560300000,
        durationMillis: 300000
      },
      {
        id: 11,
        workflowId: 200,
        name: 'Release',
        displayTitle: 'Release v0.1.0',
        runNumber: 17,
        event: 'workflow_dispatch',
        status: 'completed',
        conclusion: 'success',
        branch: 'main',
        actor: 'bob',
        htmlUrl: 'https://github.com/jdubois/boot-ui/actions/runs/11',
        createdAt: 1780560600000,
        updatedAt: 1780560900000,
        durationMillis: 300000
      }
    ],
    workflows: [
      {
        id: 100,
        name: 'Build',
        path: '.github/workflows/build.yml',
        state: 'active',
        htmlUrl: 'https://github.com/jdubois/boot-ui/actions/workflows/build.yml',
        latestRun: {
          id: 10,
          workflowId: 100,
          name: 'Build',
          displayTitle: 'Run tests on pull request',
          runNumber: 42,
          event: 'pull_request',
          status: 'completed',
          conclusion: 'timed_out',
          branch: 'main',
          actor: 'alice',
          htmlUrl: 'https://github.com/jdubois/boot-ui/actions/runs/10',
          createdAt: 1780560000000,
          updatedAt: 1780560300000,
          durationMillis: 300000
        }
      },
      {
        id: 200,
        name: 'Release',
        path: '.github/workflows/release.yml',
        state: 'active',
        htmlUrl: 'https://github.com/jdubois/boot-ui/actions/workflows/release.yml',
        latestRun: {
          id: 11,
          workflowId: 200,
          name: 'Release',
          displayTitle: 'Release v0.1.0',
          runNumber: 17,
          event: 'workflow_dispatch',
          status: 'completed',
          conclusion: 'success',
          branch: 'main',
          actor: 'bob',
          htmlUrl: 'https://github.com/jdubois/boot-ui/actions/runs/11',
          createdAt: 1780560600000,
          updatedAt: 1780560900000,
          durationMillis: 300000
        }
      },
      {
        id: 300,
        name: 'Native image',
        path: '.github/workflows/native.yml',
        state: 'active',
        htmlUrl: 'https://github.com/jdubois/boot-ui/actions/workflows/native.yml',
        latestRun: null
      }
    ],
    issueBuckets: [{label: 'Open issues', count: 2, tone: 'primary'}],
    issues: [
      {
        number: 7,
        title: 'Investigate flaky test',
        author: 'carol',
        htmlUrl: 'https://github.com/jdubois/boot-ui/issues/7',
        createdAt: 1780300000000,
        updatedAt: 1780561800000,
        comments: 3,
        labels: ['bug']
      }
    ],
    securitySignals: [
      {
        label: 'Dependabot alerts',
        status: 'AVAILABLE',
        count: 1,
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
            htmlUrl: 'https://github.com/jdubois/boot-ui/security/dependabot/7'
          }
        ]
      },
      {label: 'Code scanning alerts', status: 'AVAILABLE', count: 1, unavailableReason: null},
      {label: 'Secret scanning alerts', status: 'UNAVAILABLE', count: null, unavailableReason: 'Requires admin access'}
    ],
    copilotUsage: {
      status: 'AVAILABLE',
      scope: 'organization',
      summary: 'Latest 28-day Copilot usage report is available.',
      reportStartDay: '2026-05-07',
      reportEndDay: '2026-06-03',
      downloadLinkCount: 1,
      documentationUrl: 'https://docs.github.com/en/rest/copilot/copilot-usage-metrics',
      unavailableReason: null
    }
  })
}

function fixedWorkflowReport() {
  const report = connectedReport()
  const fixedRun = {
    ...report.workflowRuns[0],
    id: 12,
    displayTitle: 'Fix tests after failure',
    runNumber: 43,
    conclusion: 'success',
    actor: 'carol',
    htmlUrl: 'https://github.com/jdubois/boot-ui/actions/runs/12',
    createdAt: 1780561200000,
    updatedAt: 1780561440000,
    durationMillis: 240000
  }

  return {
    ...report,
    metrics: report.metrics.map((metric) =>
      metric.label === 'Workflow failures' ? {...metric, value: '0', tone: 'success'} : metric
    ),
    workflowRuns: [report.workflowRuns[0], fixedRun],
    workflows: [{...report.workflows[0], latestRun: fixedRun}]
  }
}

function branchWorkflowFailureReport() {
  const report = connectedReport()
  const featureRun = {
    ...report.workflowRuns[0],
    id: 12,
    displayTitle: 'Run tests on feature branch',
    runNumber: 43,
    branch: 'feature/github-dashboard',
    htmlUrl: 'https://github.com/jdubois/boot-ui/actions/runs/12',
    createdAt: 1780561200000,
    updatedAt: 1780561500000
  }

  return {
    ...report,
    metrics: report.metrics.map((metric) =>
      metric.label === 'Workflow failures' ? {...metric, value: '2', tone: 'danger'} : metric
    ),
    workflowRuns: [report.workflowRuns[0], featureRun, report.workflowRuns[1]]
  }
}

function metricButton(wrapper, label) {
  return wrapper.findAll('button.metric-card-button').find((button) => button.text().includes(label))
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function forkReport() {
  const report = connectedReport()
  return {
    ...report,
    repository: {...report.repository, fork: true},
    repositoryContext: {
      selectedRemote: 'origin',
      origin: {name: 'origin', host: 'github.com', fullName: 'jdubois/boot-ui'},
      upstream: {name: 'upstream', host: 'ghe.example.com', fullName: 'team/service'},
      upstreamUnavailableReason: null
    }
  }
}

describe('GitHub', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.cookie = 'XSRF-TOKEN=test-token'
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  })

  it('labels fork security cards and contacts a configured upstream only after explicit selection', async () => {
    const origin = forkReport()
    const upstream = {
      ...origin,
      repository: {
        ...origin.repository,
        host: 'ghe.example.com',
        fullName: 'team/service',
        htmlUrl: 'https://ghe.example.com/team/service',
        fork: false
      },
      repositoryContext: {...origin.repositoryContext, selectedRemote: 'upstream'}
    }
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(origin)).mockResolvedValue(jsonResponse(upstream))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()

    expect(metricButton(wrapper, 'Dependabot alerts').text()).toContain('Fork data')
    expect(wrapper.text()).toContain('not its upstream')
    expect(wrapper.text()).toContain('ghe.example.com/team/service')
    expect(wrapper.text()).toContain('has not been verified')
    await vi.advanceTimersByTimeAsync(120_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledOnce()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Connect to configured upstream')
      .trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenLastCalledWith(
      'api/github/refresh?remote=upstream',
      expect.objectContaining({method: 'POST'})
    )
    expect(metricButton(wrapper, 'Dependabot alerts').text()).not.toContain('Fork data')
    expect(wrapper.text()).toContain('explicitly selected configured upstream')
    expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)[0]).toBe('api/github/refresh?remote=upstream')

    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(true)
    await wrapper.setProps({panel: {readOnly: true, readOnlyReason: 'Locked by configuration'}})
    const originButton = wrapper.findAll('button').find((button) => button.text() === 'Read cached origin')
    expect(originButton.attributes('disabled')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(120_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('team/service')
    await wrapper.setProps({panel: {readOnly: false}})
    expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)

    fetchMock.mockResolvedValue(jsonResponse(origin))
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Connect to origin')
      .trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)[0]).toBe('api/github/refresh')
    expect(metricButton(wrapper, 'Dependabot alerts').text()).toContain('Fork data')
  })

  it('retains the fork report, timestamp and selected origin after an upstream failure', async () => {
    const report = forkReport()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(report))
      .mockResolvedValueOnce(jsonResponse({...report, status: 'ERROR', message: 'Upstream refresh failed'}))
      .mockResolvedValue(jsonResponse(report))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(true)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Connect to configured upstream')
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Upstream refresh failed')
    expect(metricButton(wrapper, 'Dependabot alerts').text()).toContain('Fork data')
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(report.refreshedAt)
    expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)[0]).toBe('api/github/refresh')
  })

  it.each(['removed', 'invalid'])(
    'recovers cached origin in read-only mode after upstream becomes %s',
    async (reason) => {
      const origin = forkReport()
      const upstream = {
        ...origin,
        repository: {...origin.repository, host: 'ghe.example.com', fullName: 'team/service', fork: false},
        repositoryContext: {...origin.repositoryContext, selectedRemote: 'upstream'}
      }
      const missing = githubReport({
        available: false,
        connected: false,
        status: 'UNAVAILABLE',
        unavailableReason: `Configured upstream is ${reason}`,
        repository: null,
        repositoryContext: {...upstream.repositoryContext, upstream: null}
      })
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(origin))
        .mockResolvedValueOnce(jsonResponse(upstream))
        .mockResolvedValueOnce(jsonResponse(missing))
        .mockResolvedValue(jsonResponse(origin))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(GitHub)
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Connect to configured upstream')
        .trigger('click')
      await flushPromises()
      await wrapper.get('button[title="Refresh"]').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain(`Configured upstream is ${reason}`)
      expect(wrapper.findAll('button').some((button) => button.text() === 'Connect to origin')).toBe(true)

      await wrapper.setProps({panel: {readOnly: true, readOnlyReason: 'Locked by configuration'}})
      const recover = wrapper.findAll('button').find((button) => button.text() === 'Read cached origin')
      expect(recover.attributes('disabled')).toBeUndefined()
      await recover.trigger('click')
      await flushPromises()
      expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
      expect(wrapper.text()).toContain('jdubois/boot-ui')
      expect(metricButton(wrapper, 'Dependabot alerts').text()).toContain('Fork data')
      expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(origin.refreshedAt)
      await wrapper.setProps({panel: {readOnly: false}})
      expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
      await vi.advanceTimersByTimeAsync(120_000)
      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()
      expect(fetchMock).toHaveBeenCalledTimes(4)
    }
  )

  it.each([null, false])('does not infer a fork or offer upstream when fork=%s', async (fork) => {
    const report = forkReport()
    report.repository.fork = fork
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report)))
    const wrapper = mount(GitHub)
    await flushPromises()
    expect(wrapper.text()).not.toContain('Connect to configured upstream')
    expect(metricButton(wrapper, 'Dependabot alerts').text()).not.toContain('Fork data')
    expect(wrapper.text()).toContain(fork === null ? 'Fork status unknown' : 'Not a fork')
  })

  it('explains unknown upstream context and disables explicit selection in read-only mode', async () => {
    const report = forkReport()
    report.repositoryContext.upstream = null
    report.repositoryContext.upstreamUnavailableReason = 'No valid upstream; upstream context is unknown.'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(report))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    expect(wrapper.text()).toContain('upstream context is unknown')
    expect(wrapper.text()).not.toContain('Connect to configured upstream')
    wrapper.unmount()

    fetchMock.mockResolvedValue(jsonResponse(forkReport()))
    const locked = mount(GitHub, {props: {panel: {readOnly: true, readOnlyReason: 'Locked by configuration'}}})
    await flushPromises()
    const button = locked.findAll('button').find((button) => button.text() === 'Connect to configured upstream')
    expect(button.attributes('disabled')).toBeDefined()
    expect(locked.text()).toContain('Locked by configuration')
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['api/github', 'api/github'])
  })

  it('opens and reopens cached data without refreshing on timers or visibility return', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(githubReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith('api/github', {})
    expect(wrapper.find('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBeNull()
    expect(wrapper.find('.last-fetched-text').exists()).toBe(false)
    expect(wrapper.text()).toContain('jdubois/boot-ui')
    expect(wrapper.text()).toContain('Auto-refresh')
    expect(wrapper.text()).not.toContain('Refresh now')
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('GitHub dashboard refreshed.')
    const repositoryLink = wrapper.find('a[href="https://github.com/jdubois/boot-ui"]')
    expect(repositoryLink.text()).toContain('jdubois/boot-ui')
    expect(repositoryLink.classes()).toEqual(expect.arrayContaining(['btn', 'btn-outline-secondary', 'btn-sm']))
    expect(repositoryLink.find('.bi-box-arrow-up-right').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(120_000)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    const reopened = mount(GitHub)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(([url]) => url === 'api/github')).toBe(true)
    expect(reopened.find('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
  })

  it('connects once and keeps manual refresh independent of polling', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(githubReport()))
      .mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Connect')
      .trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(connectedReport().refreshedAt)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Connect')).toBe(false)

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(wrapper.find('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('polls only after opt-in and only while visible', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()

    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(true)
    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))

    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))

    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(false)
    await vi.advanceTimersByTimeAsync(120_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('single-flights manual, interval, and visibility refreshes while keeping cached data visible', async () => {
    let finishRefresh
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(connectedReport()))
      .mockImplementationOnce(() => new Promise((resolve) => (finishRefresh = resolve)))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()

    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(true)
    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('button[title="Refresh"]').element.disabled).toBe(true)
    expect(wrapper.findComponent(PanelHeader).props('loading')).toBe(true)
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(connectedReport().refreshedAt)
    expect(metricButton(wrapper, 'Open pull requests').exists()).toBe(true)
    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(60_000)
    document.dispatchEvent(new Event('visibilitychange'))
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    finishRefresh(jsonResponse(connectedReport()))
    await flushPromises()
    expect(wrapper.get('button[title="Refresh"]').element.disabled).toBe(false)
  })

  it('reads only cached data in read-only mode and does not re-arm polling after a policy transition', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub, {props: {panel: {readOnly: true}}})
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledWith('api/github', {})
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(false)
    expect(wrapper.find('input[aria-label="Toggle auto-refresh"]').exists()).toBe(false)

    await wrapper.setProps({panel: {readOnly: false}})
    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(true)
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))

    await wrapper.setProps({panel: {readOnly: true}})
    wrapper.findComponent(PanelHeader).vm.$emit('update:autoRefresh', true)
    await vi.advanceTimersByTimeAsync(120_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})

    await wrapper.setProps({panel: {readOnly: false}})
    expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    await vi.advanceTimersByTimeAsync(120_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)

    await wrapper.get('input[aria-label="Toggle auto-refresh"]').setValue(true)
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
  })

  it('preserves the cached timestamp and data on refresh failure and exposes an explicit retry', async () => {
    const report = connectedReport()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(report))
      .mockResolvedValueOnce(jsonResponse({}, false, 503))
      .mockResolvedValueOnce(jsonResponse({...report, refreshedAt: report.refreshedAt + 60_000}))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(report.refreshedAt)

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('HTTP 503')
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(report.refreshedAt)
    expect(metricButton(wrapper, 'Open pull requests').exists()).toBe(true)

    const retry = wrapper.findAll('button').find((button) => button.text() === 'Retry')
    expect(retry).toBeDefined()
    await retry.trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(report.refreshedAt + 60_000)
  })

  it('retains last-good cache for HTTP-200 ERROR reports and retries explicitly', async () => {
    const report = connectedReport()
    const message = 'GitHub core request failed (HTTP 503)'
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(report))
      .mockResolvedValueOnce(
        jsonResponse(githubReport({status: 'ERROR', message, refreshedAt: report.refreshedAt + 60_000}))
      )
      .mockResolvedValueOnce(jsonResponse({...report, refreshedAt: report.refreshedAt + 120_000}))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await metricButton(wrapper, 'Open pull requests').trigger('click')
    expect(wrapper.get('.details-drawer').text()).toContain('#42 Add dashboard')

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(report.refreshedAt)
    expect(wrapper.get('[role="alert"]').text()).toContain(message)
    expect(wrapper.get('.details-drawer').text()).toContain('#42 Add dashboard')
    const retry = wrapper.findAll('button').find((button) => button.text() === 'Retry')
    expect(retry).toBeDefined()
    await retry.trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(report.refreshedAt + 120_000)
    expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
  })

  it.each([
    ['GitHub core request failed (HTTP 503)', 'GitHub core request failed (HTTP 503)'],
    [null, 'GitHub refresh failed']
  ])('does not accept an initial ERROR report as fresh data (%s)', async (message, expectedMessage) => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(githubReport({status: 'ERROR', message, refreshedAt: Date.now()})))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(expectedMessage)
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBeNull()
    expect(wrapper.find('.last-fetched-text').exists()).toBe(false)
    expect(wrapper.find('.metric-card-button').exists()).toBe(false)
    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)
    const retry = wrapper.findAll('button').find((button) => button.text() === 'Retry')
    expect(retry).toBeDefined()
  })

  it.each([
    {status: 'PARTIAL', connected: true, available: true, refreshedAt: 1780563660000},
    {
      status: 'UNAVAILABLE',
      available: false,
      unavailableReason: 'Repository is no longer available',
      refreshedAt: null
    },
    {status: 'DISABLED', available: true, refreshedAt: null}
  ])('accepts authoritative $status reports instead of retaining old metrics', async (overrides) => {
    const next = githubReport(overrides)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(connectedReport()))
      .mockResolvedValueOnce(jsonResponse(next))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    expect(metricButton(wrapper, 'Open pull requests').exists()).toBe(true)

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findComponent(PanelHeader).props('lastFetched')).toBe(next.refreshedAt)
    expect(wrapper.find('.metric-card-button').exists()).toBe(false)
    expect(wrapper.text()).toContain(next.available ? next.status : next.unavailableReason)
  })

  it('retries a failed read-only cache GET without live refresh or polling and explains an empty cache', async () => {
    let finishRead
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({}, false, 503))
      .mockImplementationOnce(() => new Promise((resolve) => (finishRead = resolve)))
    vi.stubGlobal('fetch', fetchMock)
    const reason = 'GitHub is read-only by configuration.'
    const wrapper = mount(GitHub, {props: {panel: {readOnly: true, readOnlyReason: reason}}})
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('HTTP 503')
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(false)
    expect(wrapper.find('input[aria-label="Toggle auto-refresh"]').exists()).toBe(false)
    const retry = wrapper.findAll('button').find((button) => button.text() === 'Retry cached data')
    expect(retry).toBeDefined()
    await retry.trigger('click')
    await flushPromises()
    expect(retry.element.disabled).toBe(true)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
    finishRead(jsonResponse(githubReport()))
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('No cached GitHub metrics')
    expect(wrapper.text()).toContain(reason)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Connect')).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Retry cached data')).toBe(false)

    await wrapper.setProps({panel: {readOnly: false}})
    expect(wrapper.get('input[aria-label="Toggle auto-refresh"]').element.checked).toBe(false)
    await vi.advanceTimersByTimeAsync(120_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(([url, init]) => url === 'api/github' && init.method !== 'POST')).toBe(true)
  })

  it('opens one replacing details drawer from each metric card', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(wrapper.find('.details-drawer').exists()).toBe(false)
    expect(wrapper.findAll('button.metric-card-button')).toHaveLength(8)
    expect(wrapper.findAll('.col-lg-3')).toHaveLength(8)

    await metricButton(wrapper, 'Open pull requests').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('#42 Add dashboard')
    expect(wrapper.find('.details-drawer').text()).not.toContain('Build')
    expect(wrapper.find('.details-drawer a.github-link-chip').text()).toContain('#42 Add dashboard')
    const closeButton = wrapper.get('.details-drawer button[aria-label="Close details"]')
    expect(closeButton.get('i').attributes('aria-hidden')).toBe('true')
    await closeButton.trigger('click')
    expect(wrapper.find('.details-drawer').exists()).toBe(false)

    await metricButton(wrapper, 'Open pull requests').trigger('click')
    await metricButton(wrapper, 'Open pull requests').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').exists()).toBe(false)

    await metricButton(wrapper, 'Open issues').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('1 issue returned by this refresh')
    expect(wrapper.find('.details-drawer').text()).toContain('#7 Investigate flaky test')
    expect(wrapper.find('.details-drawer a.github-link-chip').attributes('href')).toBe(
      'https://github.com/jdubois/boot-ui/issues/7'
    )
    expect(wrapper.find('.details-drawer').text()).not.toContain('#42 Add dashboard')

    await metricButton(wrapper, 'Workflow failures').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('1 workflow/branch pair needs attention')
    expect(wrapper.find('.details-drawer').text()).toContain('Latest 2 GitHub Actions executions')
    expect(wrapper.find('.details-drawer').text()).toContain('Build')
    expect(wrapper.find('.details-drawer').text()).not.toContain('#42 Add dashboard')

    await metricButton(wrapper, 'Core quota remaining').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer tbody').exists()).toBe(true)
    expect(wrapper.text()).toContain('Search')

    await metricButton(wrapper, 'Code scanning alerts').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('Code scanning alerts')
    expect(wrapper.find('.details-drawer').text()).toContain('At least one alert returned')
    expect(wrapper.find('.details-drawer').text()).toContain('does not expose secret values')
    expect(wrapper.find('.details-drawer a.github-link-chip').attributes('href')).toBe(
      'https://github.com/jdubois/boot-ui/security/code-scanning'
    )

    await metricButton(wrapper, 'Dependabot alerts').trigger('click')
    await flushPromises()
    expect(wrapper.find('.details-drawer a.github-link-chip').attributes('href')).toBe(
      'https://github.com/jdubois/boot-ui/security/dependabot'
    )
    const dependabotDrawer = wrapper.find('.details-drawer').text()
    expect(dependabotDrawer).toContain('org.example:lib')
    expect(dependabotDrawer).toContain('Remote code execution in org.example:lib')
    expect(dependabotDrawer).toContain('GHSA-aaaa-bbbb-cccc')
    expect(dependabotDrawer).toContain('Fixed in: 1.2.3')

    await metricButton(wrapper, 'Secret scanning alerts').trigger('click')
    await flushPromises()
    expect(wrapper.find('.details-drawer a.github-link-chip').attributes('href')).toBe(
      'https://github.com/jdubois/boot-ui/security/secret-scanning'
    )
  })

  it('does not warn when a quota has all of its allowance remaining', async () => {
    const report = connectedReport()
    report.quotas = [
      {
        key: 'core',
        label: 'Core',
        category: 'Rate limit',
        scope: 'credential',
        limit: 5000,
        used: 50,
        remaining: 4950,
        resetAt: 1893456000000,
        percentUsed: 1,
        status: 'OK',
        unavailableReason: null
      },
      {
        key: 'code_scanning_autofix',
        label: 'Code Scanning Autofix',
        category: 'Rate limit',
        scope: 'credential',
        limit: 10,
        used: 0,
        remaining: 10,
        resetAt: 1893456000000,
        percentUsed: 0,
        status: 'OK',
        unavailableReason: null
      }
    ]
    report.metrics = report.metrics.map((metric) =>
      metric.label === 'Core quota remaining' ? {...metric, tone: 'warning'} : metric
    )
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(report))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(metricButton(wrapper, 'Core quota remaining').text()).not.toContain(
      'All reported quotas have more than 10% remaining.'
    )
    expect(metricButton(wrapper, 'Core quota remaining').text()).toContain('99%')

    await metricButton(wrapper, 'Core quota remaining').trigger('click')
    await flushPromises()

    const autofixRow = wrapper.findAll('tbody tr').find((row) => row.text().includes('Code Scanning Autofix'))
    expect(autofixRow.classes()).not.toContain('table-warning')
    expect(autofixRow.classes()).not.toContain('table-danger')
  })

  it('shows the worst quota remaining percentage with the threshold palette', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    const quotaCard = metricButton(wrapper, 'Core quota remaining')
    expect(quotaCard.text()).toContain('0%')
    expect(quotaCard.attributes('style')).toContain('--github-quota-card-bg: #D73027')
  })

  it('surfaces unsuccessful workflow runs prominently', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(wrapper.text()).toContain('Open pull requests')
    expect(wrapper.text()).toContain('Workflow failures')
    expect(wrapper.text()).not.toContain('#42 Add dashboard')

    await metricButton(wrapper, 'Workflow failures').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('1 workflow/branch pair needs attention')
    expect(wrapper.text()).toContain('Latest 2 GitHub Actions executions')
    expect(wrapper.text()).toContain('Build')
    expect(wrapper.text()).toContain('Run tests on pull request')
    expect(wrapper.text()).toContain('#42')
    expect(wrapper.text()).toContain('by alice')
    expect(wrapper.text()).not.toContain('Native image')
    expect(wrapper.text()).toContain('pull_request')
    expect(wrapper.text()).toContain('timed_out')
    expect(wrapper.find('.details-drawer tbody tr').classes()).not.toContain('table-danger')
    expect(wrapper.find('.workflow-event-badge--pull-request').exists()).toBe(true)
    const headerLabels = wrapper.findAll('.details-drawer thead th').map((cell) => cell.text())
    expect(headerLabels).toEqual(['Status', 'Execution', 'Workflow', 'Branch', 'Event', 'Event date', 'Duration'])
    const rows = wrapper.findAll('.details-drawer tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Release v0.1.0')
    expect(rows[0].text()).toContain('Release')
    expect(rows[1].text()).toContain('Run tests on pull request')
    expect(rows[1].text()).toContain('Build')
    expect(rows[1].text()).toContain('main')
    expect(
      wrapper
        .findAll('.details-drawer tbody a.github-link-chip')
        .some((link) => link.attributes('href')?.endsWith('/actions/runs/10'))
    ).toBe(true)
    expect(wrapper.text()).toContain('Dependabot alerts')
  })

  it('does not count an older failed execution after a later run fixes the workflow', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(fixedWorkflowReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    const workflowCard = metricButton(wrapper, 'Workflow failures')
    expect(workflowCard.text()).toContain('0')

    await workflowCard.trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('Latest 2 GitHub Actions executions')
    expect(wrapper.find('.details-drawer').text()).toContain('Fix tests after failure')
    expect(wrapper.find('.details-drawer').text()).toContain('Run tests on pull request')
    expect(wrapper.find('.details-drawer').text()).toContain('timed_out')
    expect(wrapper.find('.details-drawer .alert-danger').exists()).toBe(false)
    expect(wrapper.find('.details-drawer').text()).not.toContain('needs attention')
  })

  it('counts failures separately for each workflow branch', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(branchWorkflowFailureReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    await metricButton(wrapper, 'Workflow failures').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('2 workflow/branch pairs need attention')
    expect(wrapper.find('.details-drawer').text()).toContain(
      'Only the latest execution for each workflow and branch is counted'
    )
    expect(wrapper.find('.details-drawer').text()).toContain('feature/github-dashboard')
  })

  it('shows Copilot usage report metadata without exposing signed download URLs', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    await metricButton(wrapper, 'Copilot usage').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('Latest 28-day Copilot usage report is available.')
    expect(wrapper.find('.details-drawer').text()).toContain('Report download links available: 1')
    expect(wrapper.find('.details-drawer').text()).not.toContain('signed.example')
  })

  it('renders unavailable state from the backend', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
      jsonResponse(
        githubReport({
          available: false,
          unavailableReason: 'No local git repository was detected',
          repository: null
        })
      )
    )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/github', {})
    expect(wrapper.text()).toContain('GitHub repository unavailable')
    expect(wrapper.text()).toContain('No local git repository was detected')
  })
})
