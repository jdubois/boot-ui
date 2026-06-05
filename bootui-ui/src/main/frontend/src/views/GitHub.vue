<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatNumber, formatRelative} from '../utils/format.js'
import {formatBytes} from '../utils/memoryReport.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const props = defineProps(panelProps)
const {readOnly} = usePanelState(props)
const AUTO_REFRESH_INTERVAL_MS = 60_000
const QUOTA_COLOR_SCALE = [
  {threshold: 0, color: '#D73027', textColor: '#fff'},
  {threshold: 20, color: '#F46D43', textColor: '#212529'},
  {threshold: 40, color: '#FDAE61', textColor: '#212529'},
  {threshold: 50, color: '#FFFFBF', textColor: '#212529'},
  {threshold: 60, color: '#A6D96A', textColor: '#212529'},
  {threshold: 80, color: '#66BD63', textColor: '#212529'},
  {threshold: 100, color: '#1A9850', textColor: '#fff'}
]

const data = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const activeDrawer = ref(null)

const metrics = computed(() => data.value?.metrics ?? [])
const quotas = computed(() => data.value?.quotas ?? [])
const pullRequests = computed(() => data.value?.pullRequests ?? [])
const workflowRuns = computed(() => data.value?.workflowRuns ?? [])
const workflows = computed(() => data.value?.workflows ?? [])
const issueBuckets = computed(() => data.value?.issueBuckets ?? [])
const issues = computed(() => data.value?.issues ?? [])
const securitySignals = computed(() => data.value?.securitySignals ?? [])
const warnings = computed(() => data.value?.warnings ?? [])
const repository = computed(() => data.value?.repository)
const credential = computed(() => data.value?.credential)
const copilotUsage = computed(() => data.value?.copilotUsage ?? null)
const connected = computed(() => data.value?.connected === true)
const unavailable = computed(() => data.value?.available === false)

async function fetchDashboard() {
  const liveRefresh = !readOnly.value
  try {
    const res = await apiFetch(liveRefresh ? 'api/github/refresh' : 'api/github', liveRefresh ? {method: 'POST'} : {})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    lastFetched.value = data.value?.refreshedAt ?? Date.now()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to refresh GitHub')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchDashboard, {
  intervalMs: AUTO_REFRESH_INTERVAL_MS
})

function statusBadgeClass(status) {
  return (
    {
      CONNECTED: 'text-bg-success',
      PARTIAL: 'text-bg-warning',
      QUOTA_PROTECTED: 'text-bg-warning',
      READY: 'text-bg-secondary',
      DISABLED: 'text-bg-secondary',
      BLOCKED: 'text-bg-danger',
      ERROR: 'text-bg-danger',
      UNAVAILABLE: 'text-bg-secondary'
    }[status] || 'text-bg-secondary'
  )
}

function metricClass(tone) {
  return (
    {
      primary: 'border-primary-subtle text-primary',
      info: 'border-info-subtle text-info',
      success: 'border-success-subtle text-success',
      warning: 'border-warning-subtle text-warning',
      danger: 'border-danger-subtle text-danger'
    }[tone] || 'border-secondary-subtle text-secondary'
  )
}

function quotaRemainingPercent(quota) {
  if (!quota || quota.status === 'UNAVAILABLE') return null
  if (quota.limit > 0 && quota.remaining != null) {
    return Math.min(100, Math.max(0, Math.round((quota.remaining * 100) / quota.limit)))
  }
  if (quota.percentUsed != null) {
    return Math.min(100, Math.max(0, 100 - quota.percentUsed))
  }
  if (quota.status === 'EXHAUSTED') return 0
  return null
}

function quotaSeverity(quota) {
  if (quota.status === 'UNAVAILABLE') return 'secondary'
  if (quota.status === 'EXHAUSTED') return 'danger'
  const remainingPercent = quotaRemainingPercent(quota)
  if (remainingPercent != null) {
    if (remainingPercent <= 0) return 'danger'
    if (remainingPercent <= 10) return 'warning'
    return 'success'
  }
  if (quota.status === 'LOW') return 'warning'
  return 'success'
}

function quotaClass(quota) {
  return (
    {
      danger: 'text-bg-danger',
      warning: 'text-bg-warning',
      success: 'text-bg-success',
      secondary: 'text-bg-secondary'
    }[quotaSeverity(quota)] || 'text-bg-secondary'
  )
}

function quotaProgressClass(quota) {
  return (
    {
      danger: 'bg-danger',
      warning: 'bg-warning',
      success: 'bg-success',
      secondary: 'bg-secondary'
    }[quotaSeverity(quota)] || 'bg-secondary'
  )
}

function quotaRowClass(quota) {
  return (
    {
      danger: 'table-danger',
      warning: 'table-warning'
    }[quotaSeverity(quota)] || ''
  )
}

function drawerForMetric(metric) {
  if (metric?.drawer) return metric.drawer
  const label = (metric.label || '').toLowerCase()
  if (label.includes('pull request')) return 'pullRequests'
  if (label.includes('issue')) return 'issues'
  if (label.includes('workflow')) return 'workflows'
  if (label.includes('quota')) return 'quotas'
  if (label.includes('copilot')) return 'copilot'
  return null
}

function metricIcon(metric) {
  const drawer = drawerForMetric(metric)
  if (drawer?.startsWith('security:')) return 'bi-shield-check'
  return (
    {
      pullRequests: 'bi-diagram-3',
      issues: 'bi-record-circle',
      workflows: 'bi-exclamation-octagon',
      quotas: 'bi-speedometer2',
      copilot: 'bi-stars'
    }[drawer] || 'bi-graph-up'
  )
}

function openMetricDrawer(metric) {
  const drawer = drawerForMetric(metric)
  activeDrawer.value = activeDrawer.value === drawer ? null : drawer
}

function isActiveMetric(metric) {
  return drawerForMetric(metric) === activeDrawer.value
}

const quotaIssues = computed(() => quotas.value.filter((quota) => ['danger', 'warning'].includes(quotaSeverity(quota))))

const quotaIssueSummary = computed(() => {
  if (!quotaIssues.value.length) return 'All reported quotas have more than 10% remaining.'
  const atLimit = quotaIssues.value.filter((quota) => quotaSeverity(quota) === 'danger')
  const nearLimit = quotaIssues.value.length - atLimit.length
  if (atLimit.length) return `${atLimit.length} resource${atLimit.length === 1 ? '' : 's'} at quota`
  return `${nearLimit} resource${nearLimit === 1 ? '' : 's'} with 10% or less remaining`
})

const quotaMetricTone = computed(() => {
  if (quotaIssues.value.some((quota) => quotaSeverity(quota) === 'danger')) return 'danger'
  if (quotaIssues.value.length) return 'warning'
  return null
})

const quotaMetricRemainingPercent = computed(() => {
  const percentages = quotas.value.map(quotaRemainingPercent).filter((percent) => percent != null)
  return percentages.length ? Math.min(...percentages) : null
})

function quotaMetricColor(percent) {
  if (percent == null) return null
  for (let index = QUOTA_COLOR_SCALE.length - 1; index >= 0; index -= 1) {
    if (percent >= QUOTA_COLOR_SCALE[index].threshold) return QUOTA_COLOR_SCALE[index]
  }
  return QUOTA_COLOR_SCALE[0]
}

function metricStyle(metric) {
  if (drawerForMetric(metric) !== 'quotas') return null
  const color = quotaMetricColor(quotaMetricRemainingPercent.value)
  if (!color) return null
  return {
    '--github-quota-card-bg': color.color,
    '--github-quota-card-color': color.textColor
  }
}

function securitySignalTone(signal) {
  if (signal.status !== 'AVAILABLE') return 'secondary'
  return signal.count > 0 ? 'danger' : 'success'
}

function securitySignalValue(signal) {
  if (signal.status !== 'AVAILABLE') return 'Unavailable'
  return formatNumber(signal.count ?? 0)
}

function securitySignalDetail(signal) {
  if (signal.unavailableReason) return signal.unavailableReason
  if (signal.status !== 'AVAILABLE') return 'Requires repository security permissions'
  return signal.count > 0 ? 'At least one alert returned' : 'No alerts returned'
}

const displayMetrics = computed(() =>
  metrics.value.map((metric) => {
    if (drawerForMetric(metric) !== 'quotas') return metric
    const remainingPercent = quotaMetricRemainingPercent.value
    return {
      ...metric,
      tone: quotaMetricTone.value || 'success',
      value: remainingPercent == null ? metric.value : `${remainingPercent}%`,
      detail: null
    }
  })
)

const securityMetricCards = computed(() =>
  securitySignals.value.map((signal) => ({
    label: signal.label,
    value: securitySignalValue(signal),
    detail: securitySignalDetail(signal),
    tone: securitySignalTone(signal),
    drawer: `security:${signal.label}`
  }))
)

const summaryCards = computed(() => [...displayMetrics.value, ...securityMetricCards.value])

function quotaValue(quota, value) {
  if (value == null) return '—'
  return quota.category === 'Storage' ? formatBytes(value) : formatNumber(value)
}

function percentWidth(quota) {
  if (quota.percentUsed == null) return '0%'
  return `${Math.min(100, Math.max(0, quota.percentUsed))}%`
}

function formatDate(timestamp) {
  if (timestamp == null) return '—'
  return new Date(timestamp).toLocaleString()
}

function formatDateTime(timestamp) {
  if (timestamp == null) return '—'
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(timestamp))
}

function duration(ms) {
  if (ms == null) return '—'
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`
  return `${Math.round(ms / 60_000)}m`
}

function workflowProblem(run) {
  if (!run) return false
  const conclusion = (run.conclusion || '').toLowerCase()
  return Boolean(conclusion) && !['success', 'neutral', 'skipped'].includes(conclusion)
}

function workflowBadgeClass(run) {
  if (!run) return 'text-bg-secondary'
  const conclusion = (run.conclusion || '').toLowerCase()
  const status = (run.status || '').toLowerCase()
  if (workflowProblem(run)) return 'text-bg-danger'
  if (conclusion === 'success') return 'text-bg-success'
  if (status === 'in_progress' || status === 'queued') return 'text-bg-info'
  return 'text-bg-secondary'
}

function workflowStatus(run) {
  if (!run) return 'unknown'
  return run.conclusion || run.status || 'unknown'
}

function workflowEventClass(event) {
  const normalized = (event || '').toLowerCase()
  if (normalized === 'pull_request' || normalized === 'pull_request_target') return 'workflow-event-badge--pull-request'
  if (normalized === 'push') return 'workflow-event-badge--push'
  if (normalized === 'workflow_dispatch') return 'workflow-event-badge--manual'
  if (normalized === 'schedule') return 'workflow-event-badge--schedule'
  return 'workflow-event-badge--default'
}

function workflowRunTime(run) {
  return run?.createdAt ?? run?.updatedAt ?? 0
}

const visibleWorkflowRuns = computed(() =>
  [...workflowRuns.value].sort((a, b) => workflowRunTime(b) - workflowRunTime(a))
)

const latestWorkflowRuns = computed(() => {
  const latestRuns = new Map()
  for (const run of workflowRuns.value) {
    const key = workflowRunScopeKey(run)
    const current = latestRuns.get(key)
    if (!current || workflowRunTime(run) > workflowRunTime(current)) {
      latestRuns.set(key, run)
    }
  }
  return [...latestRuns.values()]
})

function workflowRunScopeKey(run) {
  if (run?.workflowId == null) return `run:${run?.id}`
  return `workflow:${run.workflowId}:branch:${String(run.branch ?? '').trim()}`
}

const workflowFailures = computed(() => latestWorkflowRuns.value.filter(workflowProblem))

const workflowsById = computed(() => new Map(workflows.value.map((workflow) => [workflow.id, workflow])))

function workflowRunTitle(run) {
  return run.displayTitle || run.name || 'Workflow run'
}

function workflowRunSubtitle(run) {
  const parts = []
  if (run.runNumber != null) parts.push(`#${run.runNumber}`)
  if (run.actor) parts.push(`by ${run.actor}`)
  return parts.join(' · ')
}

function workflowForRun(run) {
  return workflowsById.value.get(run.workflowId) ?? null
}

const activeSecuritySignal = computed(() => {
  if (!activeDrawer.value?.startsWith('security:')) return null
  const label = activeDrawer.value.slice('security:'.length)
  return securitySignals.value.find((signal) => signal.label === label) ?? null
})

const drawerTitle = computed(() => {
  const securitySignal = activeSecuritySignal.value
  if (securitySignal) return securitySignal.label
  return (
    {
      pullRequests: 'Open pull requests',
      issues: 'Open issues',
      workflows: 'GitHub Actions executions',
      quotas: 'Quotas and rate limits',
      copilot: 'Copilot usage'
    }[activeDrawer.value] || 'GitHub details'
  )
})

const drawerSubtitle = computed(() => {
  const securitySignal = activeSecuritySignal.value
  if (securitySignal) return securitySignalDetail(securitySignal)
  return (
    {
      pullRequests: `${pullRequests.value.length} pull request${pullRequests.value.length === 1 ? '' : 's'} returned by this refresh`,
      issues: issues.value.length
        ? `${issues.value.length} issue${issues.value.length === 1 ? '' : 's'} returned by this refresh`
        : 'Issue buckets from the bounded live refresh',
      workflows: visibleWorkflowRuns.value.length
        ? `Latest ${visibleWorkflowRuns.value.length} GitHub Actions execution${visibleWorkflowRuns.value.length === 1 ? '' : 's'} returned by this refresh`
        : 'No GitHub Actions executions were returned by this refresh',
      quotas: quotaIssueSummary.value,
      copilot: copilotUsage.value?.summary || 'Copilot usage report availability'
    }[activeDrawer.value] || ''
  )
})

function copilotBadgeClass(status) {
  return (
    {
      AVAILABLE: 'text-bg-info',
      NO_DATA: 'text-bg-secondary',
      UNAVAILABLE: 'text-bg-secondary'
    }[status] || 'text-bg-secondary'
  )
}

function securitySignalUrl(signal) {
  if (!repository.value?.htmlUrl || !signal?.label) return null
  const baseUrl = repository.value.htmlUrl.replace(/\/$/, '')
  const label = signal.label.toLowerCase()
  if (label.includes('dependabot')) return `${baseUrl}/security/dependabot`
  if (label.includes('code scanning')) return `${baseUrl}/security/code-scanning`
  if (label.includes('secret scanning')) return `${baseUrl}/security/secret-scanning`
  return `${baseUrl}/security`
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-github"
      title="GitHub"
      subtitle="Live repository metrics, CI state, security signals, and quota usage for the local GitHub origin."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="!readOnly"
      :auto-refreshable="!readOnly"
      auto-refresh-title="Refresh every minute while this tab is visible"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading" />

    <template v-else-if="data">
      <div v-if="unavailable" class="alert alert-warning">
        <strong>GitHub repository unavailable.</strong>
        {{ data.unavailableReason }}
      </div>

      <div v-else class="alert alert-info github-refresh-note">
        <i class="bi bi-shield-lock me-2"></i>
        <span>
          BootUI refreshes this local-only panel every minute while visible. Tokens from <code>GITHUB_TOKEN</code>,
          <code>GH_TOKEN</code>, or <code>gh auth token</code> stay server-side and are never sent to the browser.
        </span>
      </div>

      <div v-if="!unavailable" class="row g-3 mb-3">
        <div class="col-lg-8">
          <div class="card h-100">
            <div class="card-body">
              <div class="d-flex justify-content-between align-items-start gap-3 flex-wrap">
                <div>
                  <div class="text-muted small text-uppercase fw-semibold">Repository</div>
                  <h3 class="h4 mb-1">
                    <a
                      v-if="repository?.htmlUrl"
                      :href="repository.htmlUrl"
                      class="github-link-chip github-link-chip--primary"
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      {{ repository.fullName }}
                    </a>
                    <span v-else>{{ repository?.fullName }}</span>
                  </h3>
                  <div class="text-muted small">
                    {{ repository?.visibility || 'visibility unknown' }}
                    <span v-if="repository?.defaultBranch">· default {{ repository.defaultBranch }}</span>
                    <span v-if="repository?.localBranch">· local {{ repository.localBranch }}</span>
                  </div>
                </div>
                <span :class="statusBadgeClass(data.status)" class="badge">{{ data.status }}</span>
              </div>
              <div class="row g-2 mt-3">
                <div class="col-sm-3">
                  <div class="text-muted small">Stars</div>
                  <div class="fw-semibold">{{ formatNumber(repository?.stars) }}</div>
                </div>
                <div class="col-sm-3">
                  <div class="text-muted small">Forks</div>
                  <div class="fw-semibold">{{ formatNumber(repository?.forks) }}</div>
                </div>
                <div class="col-sm-3">
                  <div class="text-muted small">Watchers</div>
                  <div class="fw-semibold">{{ formatNumber(repository?.watchers) }}</div>
                </div>
                <div class="col-sm-3">
                  <div class="text-muted small">Pushed</div>
                  <div class="fw-semibold">{{ repository?.pushedAt ? formatRelative(repository.pushedAt) : '—' }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="col-lg-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small text-uppercase fw-semibold">Credential</div>
              <div class="fw-semibold mt-1">
                {{ credential?.authenticated ? 'Authenticated' : 'Unauthenticated' }}
              </div>
              <div class="small text-muted">{{ credential?.source }}</div>
              <div v-if="credential?.login" class="small mt-2">Login: {{ credential.login }}</div>
              <div v-if="credential?.scopes" class="small mt-2">Scopes: {{ credential.scopes }}</div>
              <div v-else class="small text-muted mt-2">Scopes are not exposed by this response.</div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!unavailable && !connected" class="card border-dashed mb-3">
        <div class="card-body text-center text-muted py-4">
          <i class="bi bi-cloud-arrow-down fs-1 d-block mb-2"></i>
          <div class="fw-semibold text-body">Connect to GitHub to load live metrics</div>
          <div>
            Repository detection is local; quota and project metrics load from GitHub when this panel refreshes.
          </div>
        </div>
      </div>

      <div v-if="summaryCards.length" class="row g-3 mb-3">
        <div v-for="metric in summaryCards" :key="metric.label" class="col-sm-6 col-lg-3">
          <button
            :class="[metricClass(metric.tone), {'metric-card-button--quota': drawerForMetric(metric) === 'quotas'}]"
            :aria-pressed="isActiveMetric(metric)"
            :style="metricStyle(metric)"
            class="card metric-card metric-card-button h-100 w-100 border-2 text-start"
            type="button"
            @click="openMetricDrawer(metric)"
          >
            <span class="metric-card__icon"><i :class="['bi', metricIcon(metric)]"></i></span>
            <span class="card-body">
              <span class="text-muted small d-block">{{ metric.label }}</span>
              <span class="display-6 d-block">{{ metric.value }}</span>
              <span v-if="metric.detail" class="small text-muted d-block">{{ metric.detail }}</span>
              <span class="small fw-semibold d-block mt-2">
                {{ isActiveMetric(metric) ? 'Hide details' : 'Show details' }}
              </span>
            </span>
          </button>
        </div>
      </div>

      <div v-if="warnings.length" class="alert alert-warning">
        <strong>Partial data.</strong>
        <ul class="mb-0 mt-2">
          <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
        </ul>
      </div>

      <div v-if="activeDrawer" class="card mb-3 details-drawer">
        <div class="card-header d-flex justify-content-between align-items-start gap-3">
          <div>
            <div class="fw-semibold">{{ drawerTitle }}</div>
            <div class="text-muted small">{{ drawerSubtitle }}</div>
          </div>
          <button class="btn btn-sm btn-outline-secondary" type="button" @click="activeDrawer = null">
            <i class="bi bi-x-lg"></i>
          </button>
        </div>

        <template v-if="activeDrawer === 'pullRequests'">
          <div v-if="pullRequests.length" class="table-responsive">
            <table class="table table-sm align-middle mb-0">
              <thead>
                <tr>
                  <th>PR</th>
                  <th>Author</th>
                  <th>Labels</th>
                  <th class="text-end">Updated</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="pr in pullRequests" :key="pr.number">
                  <td>
                    <a :href="pr.htmlUrl" class="github-link-chip" rel="noopener noreferrer" target="_blank">
                      #{{ pr.number }} {{ pr.title }}
                      <i class="bi bi-box-arrow-up-right"></i>
                    </a>
                    <span v-if="pr.draft" class="badge text-bg-secondary ms-2">draft</span>
                  </td>
                  <td>{{ pr.author || '—' }}</td>
                  <td>
                    <span v-for="label in pr.labels" :key="label" class="badge text-bg-light me-1">{{ label }}</span>
                    <span v-if="!pr.labels?.length" class="text-muted small">—</span>
                  </td>
                  <td class="text-end">{{ pr.updatedAt ? formatRelative(pr.updatedAt) : '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="card-body text-muted">No open pull requests were returned by this refresh.</div>
        </template>

        <template v-else-if="activeDrawer === 'issues'">
          <div v-if="issueBuckets.length" class="card-body pb-0">
            <div class="row g-2">
              <div v-for="bucket in issueBuckets" :key="bucket.label" class="col-6 col-lg-3">
                <div class="border rounded px-3 py-2 h-100">
                  <div class="text-muted small">{{ bucket.label }}</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(bucket.count) }}</div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="issues.length" class="table-responsive mt-3">
            <table class="table table-sm align-middle mb-0">
              <thead>
                <tr>
                  <th>Issue</th>
                  <th>Author</th>
                  <th>Labels</th>
                  <th class="text-end">Comments</th>
                  <th class="text-end">Updated</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="issue in issues" :key="issue.number">
                  <td>
                    <a
                      v-if="issue.htmlUrl"
                      :href="issue.htmlUrl"
                      class="github-link-chip"
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      #{{ issue.number }} {{ issue.title }}
                      <i class="bi bi-box-arrow-up-right"></i>
                    </a>
                    <span v-else class="fw-semibold">#{{ issue.number }} {{ issue.title }}</span>
                  </td>
                  <td>{{ issue.author || '—' }}</td>
                  <td>
                    <span v-for="label in issue.labels" :key="label" class="badge text-bg-light me-1">{{ label }}</span>
                    <span v-if="!issue.labels?.length" class="text-muted small">—</span>
                  </td>
                  <td class="text-end">{{ formatNumber(issue.comments ?? 0) }}</td>
                  <td class="text-end">{{ issue.updatedAt ? formatRelative(issue.updatedAt) : '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else-if="!issueBuckets.length" class="card-body text-muted">
            No open issues were returned by this refresh.
          </div>
        </template>

        <template v-else-if="activeDrawer === 'workflows'">
          <div v-if="workflowFailures.length" class="alert alert-danger d-flex align-items-start gap-2 m-3">
            <i class="bi bi-exclamation-octagon-fill flex-shrink-0 mt-1"></i>
            <div>
              <strong>
                {{ workflowFailures.length }} workflow/branch pair{{ workflowFailures.length === 1 ? '' : 's' }}
                {{ workflowFailures.length === 1 ? 'needs' : 'need' }} attention.
              </strong>
              Only the latest execution for each workflow and branch is counted; older failed executions remain visible
              in the history.
            </div>
          </div>
          <div v-if="visibleWorkflowRuns.length" class="table-responsive">
            <table class="table table-sm align-middle mb-0">
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Execution</th>
                  <th>Workflow</th>
                  <th>Branch</th>
                  <th>Event</th>
                  <th>Event date</th>
                  <th class="text-end">Duration</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="run in visibleWorkflowRuns" :key="run.id">
                  <td>
                    <span :class="workflowBadgeClass(run)" class="badge">
                      <i v-if="workflowProblem(run)" class="bi bi-exclamation-octagon-fill me-1"></i>
                      {{ workflowStatus(run) }}
                    </span>
                  </td>
                  <td>
                    <a
                      v-if="run.htmlUrl"
                      :href="run.htmlUrl"
                      class="github-link-chip"
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      {{ workflowRunTitle(run) }}
                      <i class="bi bi-box-arrow-up-right"></i>
                    </a>
                    <span v-else class="fw-semibold">{{ workflowRunTitle(run) }}</span>
                    <div v-if="workflowRunSubtitle(run)" class="small text-muted mt-1">
                      {{ workflowRunSubtitle(run) }}
                    </div>
                  </td>
                  <td>
                    <a
                      v-if="workflowForRun(run)?.htmlUrl"
                      :href="workflowForRun(run).htmlUrl"
                      class="github-link-chip"
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      {{ run.name || workflowForRun(run).name || 'Workflow' }}
                      <i class="bi bi-box-arrow-up-right"></i>
                    </a>
                    <span v-else>{{ run.name || workflowForRun(run)?.name || 'Workflow' }}</span>
                  </td>
                  <td>
                    <span v-if="run.branch" class="font-monospace small">{{ run.branch }}</span>
                    <span v-else class="text-muted small">—</span>
                  </td>
                  <td>
                    <span :class="workflowEventClass(run.event)" class="workflow-event-badge">
                      {{ run.event || 'unknown' }}
                    </span>
                  </td>
                  <td>{{ formatDateTime(run.createdAt || run.updatedAt) }}</td>
                  <td class="text-end">{{ duration(run.durationMillis) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="card-body text-muted">No GitHub Actions executions were returned by this refresh.</div>
        </template>

        <template v-else-if="activeDrawer === 'quotas'">
          <div v-if="quotas.length" class="table-responsive">
            <table class="table table-sm align-middle mb-0">
              <thead>
                <tr>
                  <th>Resource</th>
                  <th>Scope</th>
                  <th class="text-end">Used</th>
                  <th class="text-end">Remaining</th>
                  <th class="text-end">Limit</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="quota in quotas" :key="quota.key" :class="quotaRowClass(quota)">
                  <td>
                    <div class="fw-semibold">{{ quota.label }}</div>
                    <div class="text-muted small">{{ quota.category }}</div>
                    <div v-if="quota.unavailableReason" class="small text-muted">{{ quota.unavailableReason }}</div>
                    <div v-if="quota.percentUsed != null" class="progress mt-1" style="height: 5px">
                      <div
                        :class="quotaProgressClass(quota)"
                        :style="{width: percentWidth(quota)}"
                        class="progress-bar"
                      ></div>
                    </div>
                  </td>
                  <td>{{ quota.scope }}</td>
                  <td class="text-end">{{ quotaValue(quota, quota.used) }}</td>
                  <td class="text-end">{{ quotaValue(quota, quota.remaining) }}</td>
                  <td class="text-end">{{ quotaValue(quota, quota.limit) }}</td>
                  <td>
                    <span :class="quotaClass(quota)" class="badge">{{ quota.status }}</span>
                    <div v-if="quota.resetAt" class="small text-muted">resets {{ formatDate(quota.resetAt) }}</div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="card-body text-muted">No quotas or rate limits were returned by this refresh.</div>
        </template>

        <template v-else-if="activeDrawer === 'copilot'">
          <div class="card-body">
            <div class="d-flex flex-wrap align-items-start justify-content-between gap-3">
              <div>
                <span :class="copilotBadgeClass(copilotUsage?.status)" class="badge mb-2">
                  {{ copilotUsage?.status || 'UNAVAILABLE' }}
                </span>
                <p class="mb-2">{{ copilotUsage?.summary || 'Copilot usage report unavailable.' }}</p>
                <div v-if="copilotUsage?.reportStartDay || copilotUsage?.reportEndDay" class="small text-muted">
                  Report window: {{ copilotUsage.reportStartDay || 'unknown' }} to
                  {{ copilotUsage.reportEndDay || 'unknown' }}
                </div>
                <div v-if="copilotUsage?.downloadLinkCount != null" class="small text-muted">
                  Report download links available: {{ formatNumber(copilotUsage.downloadLinkCount) }}
                </div>
                <div v-if="copilotUsage?.unavailableReason" class="small text-muted mt-2">
                  {{ copilotUsage.unavailableReason }}
                </div>
              </div>
              <a
                v-if="copilotUsage?.documentationUrl"
                :href="copilotUsage.documentationUrl"
                class="github-link-chip"
                rel="noopener noreferrer"
                target="_blank"
              >
                API docs
                <i class="bi bi-box-arrow-up-right"></i>
              </a>
            </div>
            <div class="alert alert-light border mt-3 mb-0 small">
              GitHub currently exposes Copilot usage through report metadata and signed NDJSON download links. BootUI
              only shows report availability and counts the links; it does not download or expose those signed URLs.
            </div>
          </div>
        </template>

        <template v-else-if="activeSecuritySignal">
          <div class="card-body">
            <div class="d-flex flex-wrap justify-content-between gap-3">
              <div>
                <span
                  :class="activeSecuritySignal.status === 'AVAILABLE' ? 'text-bg-success' : 'text-bg-secondary'"
                  class="badge mb-2"
                >
                  {{ activeSecuritySignal.status }}
                </span>
                <div class="display-6">
                  {{ activeSecuritySignal.status === 'AVAILABLE' ? formatNumber(activeSecuritySignal.count) : '—' }}
                </div>
                <div class="text-muted small">Alerts returned by this bounded refresh.</div>
                <a
                  v-if="securitySignalUrl(activeSecuritySignal)"
                  :href="securitySignalUrl(activeSecuritySignal)"
                  class="github-link-chip mt-3"
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  Open {{ activeSecuritySignal.label }}
                  <i class="bi bi-box-arrow-up-right"></i>
                </a>
              </div>
              <div v-if="activeSecuritySignal.unavailableReason" class="alert alert-light border mb-0 flex-grow-1">
                {{ activeSecuritySignal.unavailableReason }}
              </div>
              <div v-else class="alert alert-light border mb-0 flex-grow-1">
                BootUI only shows the alert count from GitHub. It does not expose alert payloads, secret values, or
                vulnerable code snippets.
              </div>
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.border-dashed {
  border-style: dashed;
}

.github-refresh-note {
  align-items: center;
  display: flex;
}

.metric-card {
  overflow: hidden;
  position: relative;
  transition:
    box-shadow 160ms ease,
    transform 160ms ease;
}

.metric-card-button {
  background: var(--bs-card-bg);
  color: inherit;
}

.metric-card-button--quota {
  background: var(--github-quota-card-bg);
  border-color: var(--github-quota-card-bg) !important;
  color: var(--github-quota-card-color) !important;
}

.metric-card-button--quota .text-muted {
  color: color-mix(in srgb, var(--github-quota-card-color) 78%, transparent) !important;
}

.metric-card-button:hover,
.metric-card-button:focus-visible,
.metric-card-button[aria-pressed='true'] {
  box-shadow: 0 0.75rem 1.5rem rgba(15, 23, 42, 0.12);
  transform: translateY(-1px);
}

.metric-card__icon {
  opacity: 0.08;
  font-size: 4rem;
  line-height: 1;
  position: absolute;
  right: 0.75rem;
  top: 0.25rem;
}

.github-link-chip {
  align-items: center;
  background: var(--bs-tertiary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: 999px;
  color: var(--bs-body-color);
  display: inline-flex;
  gap: 0.35rem;
  max-width: 100%;
  padding: 0.25rem 0.65rem;
  text-decoration: none;
  vertical-align: middle;
}

.github-link-chip:hover,
.github-link-chip:focus-visible {
  background: rgba(var(--bs-primary-rgb), 0.1);
  border-color: rgba(var(--bs-primary-rgb), 0.35);
  color: var(--bs-primary);
  text-decoration: none;
}

.github-link-chip--primary {
  background: rgba(var(--bs-primary-rgb), 0.08);
  border-color: rgba(var(--bs-primary-rgb), 0.25);
  color: var(--bs-primary);
}

.workflow-event-badge {
  border-radius: 999px;
  display: inline-flex;
  font-size: 0.78rem;
  font-weight: 700;
  padding: 0.25rem 0.65rem;
}

.workflow-event-badge--pull-request {
  background: rgba(var(--bs-info-rgb), 0.14);
  color: var(--bs-info-text-emphasis);
}

.workflow-event-badge--push {
  background: rgba(var(--bs-success-rgb), 0.14);
  color: var(--bs-success-text-emphasis);
}

.workflow-event-badge--manual {
  background: rgba(var(--bs-primary-rgb), 0.14);
  color: var(--bs-primary-text-emphasis);
}

.workflow-event-badge--schedule {
  background: rgba(var(--bs-warning-rgb), 0.2);
  color: var(--bs-warning-text-emphasis);
}

.workflow-event-badge--default {
  background: var(--bs-tertiary-bg);
  color: var(--bs-secondary-color);
}
</style>
