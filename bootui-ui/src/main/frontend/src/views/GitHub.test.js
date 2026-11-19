import {enableAutoUnmount, flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {defineComponent, h, KeepAlive, nextTick, ref} from 'vue'

let GitHub
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

function control(wrapper, name) {
  return wrapper.findAll('button').find((button) => button.text() === name)
}

function deferredResponse() {
  let resolve
  const promise = new Promise((complete) => (resolve = complete))
  return {promise, resolve}
}

describe('GitHub', () => {
  beforeEach(async () => {
    // A fresh module models a browser page reload; route remounts within a test reuse it.
    vi.resetModules()
    GitHub = (await import('./GitHub.vue')).default
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

  it('reads only cache until Connect, regardless of cached connected state', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith('api/github', {})
    expect(wrapper.text()).toContain('jdubois/boot-ui')
    expect(wrapper.text()).toContain('Cached data only.')
    expect(wrapper.text()).toContain('every minute while this panel is active and visible')
    expect(wrapper.text()).not.toContain('Auto-refresh')
    expect(wrapper.text()).not.toContain('Refresh now')
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('GitHub dashboard refreshed.')
    const repositoryLink = wrapper.find('a[href="https://github.com/jdubois/boot-ui"]')
    expect(repositoryLink.text()).toContain('jdubois/boot-ui')
    expect(repositoryLink.classes()).toEqual(expect.arrayContaining(['btn', 'btn-outline-secondary', 'btn-sm']))
    expect(repositoryLink.find('.bi-box-arrow-up-right').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(control(wrapper, 'Stop')).toBeDefined()
    expect(control(wrapper, 'Connect')).toBeUndefined()

    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)

    await control(wrapper, 'Stop').trigger('click')
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(control(wrapper, 'Connect')).toBeDefined()
    expect(fetchMock.mock.calls.map(([url, options]) => [url, options.method || 'GET'])).toEqual([
      ['api/github', 'GET'],
      ['api/github/refresh', 'POST'],
      ['api/github/refresh', 'POST']
    ])
  })

  it('neither reads nor writes browser storage to grant or retain consent', async () => {
    const readStorage = vi.spyOn(Storage.prototype, 'getItem')
    const writeStorage = vi.spyOn(Storage.prototype, 'setItem')
    const removeStorage = vi.spyOn(Storage.prototype, 'removeItem')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    await control(wrapper, 'Stop').trigger('click')
    expect(readStorage).not.toHaveBeenCalled()
    expect(writeStorage).not.toHaveBeenCalled()
    expect(removeStorage).not.toHaveBeenCalled()
  })

  it('retains the focused connection control when Connect changes to Stop and back', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub, {attachTo: document.body})
    await flushPromises()
    const button = control(wrapper, 'Connect').element
    button.focus()
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(button)
    expect(control(wrapper, 'Stop').element).toBe(button)
    await control(wrapper, 'Stop').trigger('click')
    expect(document.activeElement).toBe(button)
    expect(control(wrapper, 'Connect').element).toBe(button)
  })

  it('keeps the header refresh cache-only without consent', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['api/github', 'api/github'])
  })

  it('retains consent across route unmounts, returns cache-first, and clears consent on reload', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const returned = mount(GitHub)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
    expect(control(returned, 'Stop')).toBeDefined()
    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
    returned.unmount()

    vi.resetModules()
    const ReloadedGitHub = (await import('./GitHub.vue')).default
    const reloaded = mount(ReloadedGitHub)
    await flushPromises()
    expect(control(reloaded, 'Connect')).toBeDefined()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
  })

  it('pauses while hidden and resumes on the next full interval without a visibility POST', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()

    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('pauses a kept-alive inactive panel and reactivates cache-first', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const show = ref(true)
    const wrapper = mount(
      defineComponent({
        setup: () => () => h(KeepAlive, null, {default: () => (show.value ? h(GitHub) : null)})
      })
    )
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    show.value = false
    await nextTick()
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    show.value = true
    await flushPromises()
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
    expect(fetchMock).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('never overlaps refreshes or queues missed ticks, including across route remounts', async () => {
    let finish
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)))
    await control(wrapper, 'Connect').trigger('click')
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('button[title="Refresh"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()

    const returned = mount(GitHub)
    await flushPromises()
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    finish(jsonResponse(connectedReport()))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetchMock).toHaveBeenCalledTimes(5)
    await control(returned, 'Stop').trigger('click')
  })

  it.each(['BLOCKED', 'DISABLED', 'HTTP 403'])(
    'revokes page consent when an unmounted refresh finishes with %s and no replacement is mounted yet',
    async (status) => {
      const pending = deferredResponse()
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(GitHub)
      await flushPromises()
      fetchMock.mockReturnValueOnce(pending.promise)
      await control(wrapper, 'Connect').trigger('click')
      wrapper.unmount()

      pending.resolve(
        status === 'HTTP 403'
          ? jsonResponse({}, false, 403)
          : jsonResponse(githubReport({status, message: 'GitHub refresh is prohibited'}))
      )
      await flushPromises()
      const returned = mount(GitHub)
      await flushPromises()
      expect(control(returned, 'Stop')).toBeUndefined()
      expect(control(returned, 'Connect')).toBeDefined()
      await vi.advanceTimersByTimeAsync(180_000)
      // Even a still-CONNECTED cache cannot restore permission after the late denial.
      expect(fetchMock.mock.calls.map(([url, options]) => [url, options.method || 'GET'])).toEqual([
        ['api/github', 'GET'],
        ['api/github/refresh', 'POST'],
        ['api/github', 'GET']
      ])
    }
  )

  it.each(['BLOCKED', 'DISABLED', 'HTTP 403'])(
    'revokes page consent before a mounted replacement can finish its cached GET after late %s',
    async (status) => {
      const pendingPost = deferredResponse()
      const pendingGet = deferredResponse()
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(GitHub)
      await flushPromises()
      fetchMock.mockReturnValueOnce(pendingPost.promise)
      await control(wrapper, 'Connect').trigger('click')
      wrapper.unmount()
      fetchMock.mockReturnValueOnce(pendingGet.promise)
      const returned = mount(GitHub)
      await flushPromises()
      expect(control(returned, 'Stop')).toBeDefined()

      pendingPost.resolve(
        status === 'HTTP 403'
          ? jsonResponse({}, false, 403)
          : jsonResponse(githubReport({status, message: 'GitHub refresh is prohibited'}))
      )
      await flushPromises()
      expect(control(returned, 'Stop')).toBeUndefined()
      expect(control(returned, 'Connect')).toBeDefined()
      expect(fetchMock).toHaveBeenCalledTimes(3)
      pendingGet.resolve(jsonResponse(connectedReport()))
      await flushPromises()
      await vi.advanceTimersByTimeAsync(180_000)
      expect(control(returned, 'Stop')).toBeUndefined()
      expect(fetchMock.mock.calls.filter(([url]) => url === 'api/github/refresh')).toHaveLength(1)
    }
  )

  it('updates the active replacement from cache when the previous instance finishes its POST', async () => {
    const pending = deferredResponse()
    let cached = connectedReport()
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(cached)))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    fetchMock.mockReturnValueOnce(pending.promise)
    await control(wrapper, 'Connect').trigger('click')
    wrapper.unmount()
    const returned = mount(GitHub)
    await flushPromises()
    expect(metricButton(returned, 'Open pull requests').text()).toContain('1')
    cached = {
      ...connectedReport(),
      refreshedAt: connectedReport().refreshedAt + 60_000,
      metrics: [{label: 'Open pull requests', value: '99', tone: 'primary'}]
    }
    pending.resolve(jsonResponse(cached))
    await flushPromises()

    expect(metricButton(returned, 'Open pull requests').text()).toContain('99')
    expect(returned.get('time').attributes('datetime')).toBe(new Date(cached.refreshedAt).toISOString())
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      'api/github',
      'api/github/refresh',
      'api/github',
      'api/github'
    ])
    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('coalesces the completion read behind a replacement cached GET already in flight', async () => {
    const pendingPost = deferredResponse()
    const pendingGet = deferredResponse()
    const oldCache = connectedReport()
    const newCache = {...connectedReport(), metrics: [{label: 'Open pull requests', value: '99', tone: 'primary'}]}
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(oldCache))
      .mockReturnValueOnce(pendingPost.promise)
      .mockReturnValueOnce(pendingGet.promise)
      .mockResolvedValue(jsonResponse(newCache))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await control(wrapper, 'Connect').trigger('click')
    wrapper.unmount()
    const returned = mount(GitHub)
    await flushPromises()

    pendingPost.resolve(jsonResponse(newCache))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    pendingGet.resolve(jsonResponse(oldCache))
    await flushPromises()
    expect(metricButton(returned, 'Open pull requests').text()).toContain('99')
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      'api/github',
      'api/github/refresh',
      'api/github',
      'api/github'
    ])
    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('uses its own completed POST report without adding a redundant cached GET', async () => {
    const pending = deferredResponse()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(connectedReport()))
      .mockReturnValueOnce(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    await control(wrapper, 'Connect').trigger('click')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    pending.resolve(
      jsonResponse({...connectedReport(), metrics: [{label: 'Open pull requests', value: '99', tone: 'primary'}]})
    )
    await flushPromises()
    expect(metricButton(wrapper, 'Open pull requests').text()).toContain('99')
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['api/github', 'api/github/refresh'])
    await control(wrapper, 'Stop').trigger('click')
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('defers a replacement completion read while hidden and resumes with GET only', async () => {
    const pending = deferredResponse()
    let cached = connectedReport()
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(cached)))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    fetchMock.mockReturnValueOnce(pending.promise)
    await control(wrapper, 'Connect').trigger('click')
    wrapper.unmount()
    const returned = mount(GitHub)
    await flushPromises()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    cached = {...connectedReport(), metrics: [{label: 'Open pull requests', value: '99', tone: 'primary'}]}
    pending.resolve(jsonResponse(cached))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(metricButton(returned, 'Open pull requests').text()).toContain('99')
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})
  })

  it('lets Stop revoke consent while a request is in progress', async () => {
    let finish
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)))
    await control(wrapper, 'Connect').trigger('click')
    await control(wrapper, 'Stop').trigger('click')
    finish(jsonResponse(connectedReport()))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(control(wrapper, 'Connect')).toBeDefined()
  })

  it('revokes consent when read-only or panel disablement arrives, with no silent reconnection', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub, {props: {panel: {id: 'github', readOnly: false}}})
    await flushPromises()
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()

    await wrapper.setProps({panel: {id: 'github', readOnly: true, readOnlyReason: 'GitHub actions are read-only'}})
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(control(wrapper, 'Stop')).toBeUndefined()
    expect(control(wrapper, 'Connect')).toBeUndefined()
    expect(wrapper.text()).toContain('GitHub actions are read-only')
    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenLastCalledWith('api/github', {})

    await wrapper.setProps({panel: {id: 'github', readOnly: false}})
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    await wrapper.setProps({panel: {id: 'github', enabled: false}})
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(wrapper.text()).toContain('Panel is disabled')
    await wrapper.setProps({panel: {id: 'github', enabled: true}})
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(control(wrapper, 'Connect')).toBeDefined()
  })

  it.each([{readOnly: true}, {enabled: false}, {available: false}])(
    'does not authorize external calls for restrictive manifest %o',
    async (panel) => {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(GitHub, {props: {panel}})
      await flushPromises()
      await vi.advanceTimersByTimeAsync(120_000)
      expect(fetchMock).toHaveBeenCalledTimes(panel.readOnly ? 1 : 0)
      expect(control(wrapper, 'Connect')).toBeUndefined()
      expect(control(wrapper, 'Stop')).toBeUndefined()
    }
  )

  it.each(['ERROR', 'BLOCKED', 'DISABLED', 'HTTP', 'NETWORK'])(
    'preserves the cached snapshot and its timestamp when refresh fails with %s',
    async (status) => {
      const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(connectedReport()))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(GitHub)
      await flushPromises()
      const timestamp = wrapper.get('time').attributes('datetime')
      if (status === 'HTTP') {
        fetchMock.mockResolvedValueOnce(jsonResponse({}, false, 403))
      } else if (status === 'NETWORK') {
        fetchMock.mockRejectedValueOnce(new Error('Offline'))
      } else {
        fetchMock.mockResolvedValueOnce(
          jsonResponse(githubReport({status, message: 'GitHub refresh was denied', refreshedAt: Date.now()}))
        )
      }

      await control(wrapper, 'Connect').trigger('click')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').exists()).toBe(true)
      expect(wrapper.get('time').attributes('datetime')).toBe(timestamp)
      expect(wrapper.text()).toContain('The previous snapshot is still shown')
      expect(metricButton(wrapper, 'Open pull requests')).toBeDefined()
      if (['BLOCKED', 'DISABLED', 'HTTP'].includes(status)) {
        await vi.advanceTimersByTimeAsync(120_000)
        expect(fetchMock).toHaveBeenCalledTimes(2)
        expect(control(wrapper, 'Stop')).toBeUndefined()
      }
      if (status === 'DISABLED') expect(control(wrapper, 'Connect').attributes('disabled')).toBeDefined()
    }
  )

  it('does not invent a successful timestamp for an unconnected report', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(githubReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    expect(wrapper.find('time').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Cached snapshot:')
    expect(control(wrapper, 'Connect')).toBeDefined()
  })

  it('retains quota protection, partial results, and missing-credential warnings from the server', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(githubReport()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(GitHub)
    await flushPromises()
    const quotaReport = {
      ...connectedReport(),
      status: 'QUOTA_PROTECTED',
      message: 'Live repository calls were skipped to preserve remaining quota.',
      warnings: ['Optional GitHub calls were skipped because the core API quota is near its safety threshold.']
    }
    fetchMock.mockResolvedValueOnce(jsonResponse(quotaReport))
    await control(wrapper, 'Connect').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('QUOTA_PROTECTED')
    expect(wrapper.text()).toContain('Optional GitHub calls were skipped')

    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        ...connectedReport(),
        status: 'PARTIAL',
        credential: {source: 'none', authenticated: false},
        warnings: ['No GitHub token found; private repository data is unavailable.']
      })
    )
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.text()).toContain('PARTIAL')
    expect(wrapper.text()).toContain('Unauthenticated')
    expect(wrapper.text()).toContain('No GitHub token found')
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
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith('api/github', {})
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
    expect(control(wrapper, 'Connect').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
