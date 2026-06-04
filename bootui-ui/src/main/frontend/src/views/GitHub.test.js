import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import GitHub from './GitHub.vue'

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
      {label: 'Workflow failures', value: '1', detail: 'Latest run per workflow', tone: 'danger'},
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
    securitySignals: [
      {label: 'Dependabot alerts', status: 'AVAILABLE', count: 0, unavailableReason: null},
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

function metricButton(wrapper, label) {
  return wrapper.findAll('button.metric-card-button').find((button) => button.text().includes(label))
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('GitHub', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    document.cookie = 'XSRF-TOKEN=test-token'
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  })

  it('refreshes GitHub metrics automatically every minute', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(connectedReport()))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(GitHub)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(wrapper.text()).toContain('jdubois/boot-ui')
    expect(wrapper.text()).toContain('Auto-refresh')
    expect(wrapper.text()).not.toContain('Refresh now')
    expect(wrapper.find('button[title="Refresh"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('GitHub dashboard refreshed.')
    expect(wrapper.find('a.github-link-chip--primary').text()).toContain('jdubois/boot-ui')
    expect(wrapper.find('a.github-link-chip--primary .bi-box-arrow-up-right').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(59_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
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

    await metricButton(wrapper, 'Open pull requests').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').exists()).toBe(false)

    await metricButton(wrapper, 'Workflow failures').trigger('click')
    await flushPromises()

    expect(wrapper.find('.details-drawer').text()).toContain('1 workflow needs attention')
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
    expect(wrapper.find('.details-drawer a.github-link-chip').attributes('href')).toBe(
      'https://github.com/jdubois/boot-ui/security/code-scanning'
    )

    await metricButton(wrapper, 'Dependabot alerts').trigger('click')
    await flushPromises()
    expect(wrapper.find('.details-drawer a.github-link-chip').attributes('href')).toBe(
      'https://github.com/jdubois/boot-ui/security/dependabot'
    )

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

    expect(wrapper.text()).toContain('1 workflow needs attention')
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

    expect(fetchMock).toHaveBeenCalledWith('api/github/refresh', expect.objectContaining({method: 'POST'}))
    expect(wrapper.text()).toContain('GitHub repository unavailable')
    expect(wrapper.text()).toContain('No local git repository was detected')
  })
})
