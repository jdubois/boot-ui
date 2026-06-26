<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const route = useRoute()
const panelConfigs = {
  copilot: {
    apiBase: 'api/copilot',
    title: 'Copilot',
    icon: 'bi-robot',
    description: 'Sanitized command activity, tool usage, failures, and recent sessions.',
    propertyPrefix: 'bootui.copilot',
    emptyTitle: 'No Copilot session state found',
    emptyHint: 'Run the local Copilot CLI at least once to create it',
    eventLabel: 'Copilot events',
    inspiration: {
      label: 'copilot-mission-control',
      href: 'https://github.com/DanWahlin/copilot-mission-control'
    }
  },
  'claude-code': {
    apiBase: 'api/claude-code',
    title: 'Claude Code',
    icon: 'bi-claude',
    description: 'Sanitized Claude Code tool use, failures, project activity, and recent sessions.',
    propertyPrefix: 'bootui.claude-code',
    emptyTitle: 'No Claude Code project logs found',
    emptyHint: 'Run Claude Code at least once to create project logs',
    eventLabel: 'Claude Code events'
  }
}
const panelConfig = computed(() => panelConfigs[route.name] ?? panelConfigs.copilot)

const sessionList = ref(null)
const dashboard = ref(null)
const selectedSessionId = ref(null)
const detail = ref(null)
const categoryFilter = ref('ALL')
const textFilter = ref('')
const activeDetailTab = ref('activity')
const activityMode = ref('tokens')
const activeSessionWindow = ref(null)
const detailLoading = ref(false)
const error = ref(null)
const lastFetched = ref(null)
const rawById = ref({})
const rawLoadingId = ref(null)

const categories = [
  'ALL',
  'FILE_EDIT',
  'FILE_READ',
  'SEARCH',
  'SHELL',
  'WEB',
  'DOCS',
  'MCP',
  'HOOK',
  'SKILL',
  'SUB_AGENT',
  'ASK',
  'FALLBACK',
  'OTHER'
]

const sessions = computed(() => sessionList.value?.sessions ?? [])
const available = computed(() => sessionList.value?.available !== false)
const sessionStateDir = computed(() => sessionList.value?.sessionStateDir)
const explorerReturned = computed(() => sessionList.value?.returned ?? sessions.value.length)
const explorerTotal = computed(() => sessionList.value?.total ?? sessions.value.length)
const explorerMaxSessions = computed(() => sessionList.value?.maxSessions)
const explorerLimitMessage = computed(() => {
  if (explorerTotal.value <= explorerReturned.value) return null
  return `Showing ${formatNumber(explorerReturned.value)} most recent of ${formatNumber(explorerTotal.value)} sessions`
})
const sessionWarnings = computed(() =>
  (sessionList.value?.warnings ?? []).filter((warning) => !warning.startsWith('Showing the '))
)
const totalEvents = computed(
  () => dashboard.value?.eventCount ?? sessions.value.reduce((sum, item) => sum + item.eventCount, 0)
)
const totalInputTokens = computed(() => dashboard.value?.totalInputTokens)
const totalOutputTokens = computed(() => dashboard.value?.totalOutputTokens)
const maxBucketEvents = computed(() =>
  Math.max(0, ...(dashboard.value?.activityBuckets ?? []).map((bucket) => bucket.eventCount))
)
const maxDailyBucketEvents = computed(() =>
  Math.max(0, ...(dashboard.value?.dailyActivityBuckets ?? []).map((bucket) => bucket.eventCount))
)
const maxBucketTokens = computed(() =>
  Math.max(0, ...(dashboard.value?.activityBuckets ?? []).map((bucket) => bucketTokenTotal(bucket)))
)
const maxDailyBucketTokens = computed(() =>
  Math.max(0, ...(dashboard.value?.dailyActivityBuckets ?? []).map((bucket) => bucketTokenTotal(bucket)))
)
const activitySummary = computed(() => {
  if (activityMode.value === 'tokens') {
    return `${formatKnownTokenTotal(totalInputTokens.value, totalOutputTokens.value)} total tokens`
  }
  return `${formatNumber(totalEvents.value)} total events`
})
const activityChartAriaLabel = computed(() => {
  if (activityMode.value === 'tokens') {
    return `${formatKnownTokenTotal(totalInputTokens.value, totalOutputTokens.value)} ${panelConfig.value.title} tokens across ${
      dashboard.value?.sessionCount ?? 0
    } sessions`
  }
  return `${formatNumber(totalEvents.value)} sanitized ${panelConfig.value.eventLabel} across ${
    dashboard.value?.sessionCount ?? 0
  } sessions`
})
const dailyActivityChartAriaLabel = computed(() => {
  if (activityMode.value === 'tokens') {
    return `${formatKnownTokenTotal(totalInputTokens.value, totalOutputTokens.value)} ${panelConfig.value.title} tokens summarized across the last 7 days`
  }
  return `${formatNumber(totalEvents.value)} sanitized ${panelConfig.value.eventLabel} summarized across the last 7 days`
})

const dashboardCards = computed(() => [
  {
    label: 'Sessions',
    value: formatNumber(dashboard.value?.sessionCount ?? sessions.value.length),
    detail: `${formatNumber(dashboard.value?.activeLast24Hours ?? 0)} active in 24h`,
    icon: 'bi-diagram-3',
    tone: 'text-bg-primary'
  },
  {
    label: 'Sanitized events',
    value: formatNumber(dashboard.value?.eventCount ?? 0),
    detail: `${formatNumber(dashboard.value?.turnCount ?? 0)} turns observed`,
    icon: 'bi-activity',
    tone: 'text-bg-info'
  },
  {
    label: 'Tokens',
    value: formatKnownTokenTotal(dashboard.value?.totalInputTokens, dashboard.value?.totalOutputTokens),
    detail: formatTokenUsage(dashboard.value, 'No token usage recorded'),
    icon: 'bi-coin',
    tone: 'text-bg-warning'
  },
  {
    label: 'Failures',
    value: formatNumber(dashboard.value?.errorCount ?? 0),
    detail: `${metricPercent(dashboard.value?.errorCount ?? 0, dashboard.value?.eventCount ?? 0)}% of events`,
    icon: 'bi-exclamation-triangle',
    tone: (dashboard.value?.errorCount ?? 0) > 0 ? 'text-bg-danger' : 'text-bg-success'
  },
  {
    label: 'Last activity',
    value: formatRelative(dashboard.value?.lastActivityEpochMillis),
    detail: `${formatNumber(dashboard.value?.activeLast7Days ?? 0)} active in 7d`,
    icon: 'bi-clock-history',
    tone: 'text-bg-dark'
  }
])

const categoryRows = computed(() => addPercent(dashboard.value?.categoryCounts ?? [], totalEvents.value))
const topToolRows = computed(() => addPercent(dashboard.value?.topTools ?? [], dashboard.value?.eventCount ?? 0))
const modelRows = computed(() => addPercent(dashboard.value?.modelCounts ?? [], dashboard.value?.sessionCount ?? 0))

const filteredEvents = computed(() => {
  const events = detail.value?.recentEvents ?? []
  const filterText = textFilter.value.trim().toLowerCase()
  return events.filter((event) => {
    if (categoryFilter.value !== 'ALL' && event.category !== categoryFilter.value) return false
    if (!filterText) return true
    return (
      (event.summary || '').toLowerCase().includes(filterText) ||
      (event.toolName || '').toLowerCase().includes(filterText) ||
      (event.type || '').toLowerCase().includes(filterText)
    )
  })
})

const failureEvents = computed(
  () => detail.value?.failureEvents ?? (detail.value?.recentEvents ?? []).filter((event) => event.success === false)
)

const breakdown = computed(() => {
  const map = detail.value?.counts?.byCategory ?? {}
  return Object.entries(map)
    .map(([category, count]) => ({category, count}))
    .sort((a, b) => b.count - a.count)
})

function addPercent(items, total) {
  return items.map((item) => ({...item, percent: metricPercent(item.count, total)}))
}

const categoryBadgeClass = (category) =>
  ({
    FILE_EDIT: 'bg-warning text-dark',
    FILE_READ: 'bg-info text-dark',
    SEARCH: 'bg-secondary',
    SHELL: 'bg-dark',
    WEB: 'bg-primary',
    MCP: 'bg-success',
    HOOK: 'bg-success-subtle text-success-emphasis',
    SKILL: 'bg-info',
    SUB_AGENT: 'bg-danger',
    ASK: 'bg-light text-dark',
    FALLBACK: 'bg-warning',
    OTHER: 'bg-secondary'
  })[category] || 'bg-secondary'

function formatTime(timestamp) {
  if (timestamp === null || timestamp === undefined) return '—'
  return formatClockTime(timestamp)
}

function formatBucketTime(timestamp) {
  if (timestamp === null || timestamp === undefined) return '—'
  const date = new Date(timestamp)
  return date.toLocaleTimeString([], {hour12: false, hour: '2-digit'})
}

function formatBucketDay(timestamp) {
  if (timestamp === null || timestamp === undefined) return '—'
  const date = new Date(timestamp)
  return date.toLocaleDateString([], {weekday: 'short', month: 'short', day: 'numeric'})
}

function formatRelative(timestamp) {
  if (timestamp === null || timestamp === undefined) return '—'
  const diffSec = Math.round((Date.now() - timestamp) / 1000)
  if (diffSec < 60) return `${diffSec}s ago`
  if (diffSec < 3600) return `${Math.round(diffSec / 60)}m ago`
  return `${Math.round(diffSec / 3600)}h ago`
}

function metricPercent(count, total) {
  if (!total) return 0
  return Math.round((count / total) * 100)
}

function hasTokenUsage(item) {
  return (
    item?.inputTokens != null ||
    item?.outputTokens != null ||
    item?.totalInputTokens != null ||
    item?.totalOutputTokens != null
  )
}

function formatTokenUsage(item, fallback = '') {
  const input = item?.inputTokens ?? item?.totalInputTokens
  const output = item?.outputTokens ?? item?.totalOutputTokens
  const parts = []
  if (input != null) parts.push(`${formatTokenCount(input)} in`)
  if (output != null) parts.push(`${formatTokenCount(output)} out`)
  return parts.length ? parts.join(' · ') : fallback
}

function formatKnownTokenTotal(input, output) {
  if (input == null && output == null) return '—'
  return formatTokenCount((input ?? 0) + (output ?? 0))
}

function formatTokenCount(value) {
  if (value == null) return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  const absolute = Math.abs(numeric)
  if (absolute < 1000) return formatNumber(numeric)
  const unit = absolute >= 1_000_000 ? 'm' : 'k'
  const divisor = unit === 'm' ? 1_000_000 : 1000
  const truncated = Math.trunc((absolute / divisor) * 100) / 100
  const compact = truncated
    .toFixed(2)
    .replace('.', ',')
    .replace(/,?0{1,2}$/, '')
  return `${numeric < 0 ? '-' : ''}${compact}${unit}`
}

function bucketTokenTotal(bucket) {
  return (bucket?.inputTokens ?? 0) + (bucket?.outputTokens ?? 0)
}

function scaledHeight(value, max, minPercent = 8) {
  if (!value || !max) return '0%'
  return `${Math.max(minPercent, Math.round((value / max) * 100))}%`
}

function bucketHeight(bucket, max = maxBucketEvents.value) {
  if (!bucket.eventCount || !max) return '4%'
  return scaledHeight(bucket.eventCount, max)
}

function tokenBucketHeight(bucket, tokenType, max) {
  const value = tokenType === 'input' ? bucket.inputTokens : bucket.outputTokens
  return scaledHeight(value ?? 0, max, 2)
}

function bucketTitle(bucket) {
  if (activityMode.value === 'tokens') {
    return `${formatBucketTime(bucket.startEpochMillis)}: ${formatTokenBucket(bucket)}`
  }
  return `${formatBucketTime(bucket.startEpochMillis)}: ${formatNumber(bucket.eventCount)} events, ${formatNumber(
    bucket.errorCount
  )} failures`
}

function dailyBucketTitle(bucket) {
  if (activityMode.value === 'tokens') {
    return `${formatBucketDay(bucket.startEpochMillis)}: ${formatTokenBucket(bucket)}`
  }
  return `${formatBucketDay(bucket.startEpochMillis)}: ${formatNumber(bucket.eventCount)} events, ${formatNumber(
    bucket.errorCount
  )} failures`
}

function formatTokenBucket(bucket) {
  if (!bucketTokenTotal(bucket)) return 'no token usage'
  return `${formatTokenCount(bucketTokenTotal(bucket))} tokens (${formatTokenCount(
    bucket.inputTokens ?? 0
  )} in, ${formatTokenCount(bucket.outputTokens ?? 0)} out)`
}

function bucketTooltip(bucket) {
  if (activityMode.value === 'tokens') {
    return `Input tokens: ${formatTokenCount(bucket.inputTokens ?? 0)} · Output tokens: ${formatTokenCount(
      bucket.outputTokens ?? 0
    )}`
  }
  return `Events: ${formatNumber(bucket.eventCount)} · Failures: ${formatNumber(bucket.errorCount)}`
}

function bucketWindowLabel(bucket, granularity) {
  return granularity === 'day' ? formatBucketDay(bucket.startEpochMillis) : formatBucketTime(bucket.startEpochMillis)
}

async function loadDashboard() {
  dashboard.value = await getJson(`${panelConfig.value.apiBase}/dashboard`)
}

async function loadSessions(window = activeSessionWindow.value) {
  let url = `${panelConfig.value.apiBase}/sessions`
  if (window) {
    const params = new URLSearchParams()
    params.set('since', window.since)
    params.set('until', window.until)
    url += `?${params.toString()}`
  }
  sessionList.value = await getJson(url)
}

async function refreshSessions(window = activeSessionWindow.value) {
  try {
    await loadSessions(window)
    lastFetched.value = Date.now()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, `Unable to load ${panelConfig.value.title} sessions`)
  }
}

async function refreshPanel() {
  const jobs = [
    {
      run: loadSessions,
      message: `Unable to load ${panelConfig.value.title} sessions`
    },
    {
      run: loadDashboard,
      message: `Unable to load ${panelConfig.value.title} dashboard`
    }
  ]
  const results = await Promise.all(
    jobs.map(async (job) => {
      try {
        await job.run()
        return null
      } catch (e) {
        return formatLoadError(e, job.message)
      }
    })
  )
  const firstError = results.find(Boolean)
  if (firstError) {
    error.value = firstError
    return
  }

  error.value = null
  lastFetched.value = Date.now()
  if (selectedSessionId.value) {
    await loadDetail(selectedSessionId.value)
  }
}

async function selectActivityWindow(bucket, granularity) {
  activeSessionWindow.value = {
    since: bucket.startEpochMillis,
    until: bucket.endEpochMillis,
    label: bucketWindowLabel(bucket, granularity),
    granularity
  }
  selectedSessionId.value = null
  detail.value = null
  await refreshSessions(activeSessionWindow.value)
}

async function clearActivityWindow() {
  activeSessionWindow.value = null
  await refreshSessions(null)
}

async function loadDetail(sessionId) {
  if (!sessionId) {
    detail.value = null
    return
  }
  detailLoading.value = true
  try {
    detail.value = await getJson(`${panelConfig.value.apiBase}/sessions/${encodeURIComponent(sessionId)}`)
    rawById.value = {}
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, `Unable to load ${panelConfig.value.title} session details`)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function showActivity(category = 'ALL') {
  activeDetailTab.value = 'activity'
  categoryFilter.value = category
  if (category !== 'ALL') {
    textFilter.value = ''
  }
}

function showTurns() {
  activeDetailTab.value = 'turns'
}

function showFailures() {
  activeDetailTab.value = 'failures'
  categoryFilter.value = 'ALL'
  textFilter.value = ''
}

async function revealRaw(event) {
  if (rawById.value[event.id] !== undefined) {
    rawById.value = {...rawById.value, [event.id]: undefined}
    return
  }
  rawLoadingId.value = event.id
  try {
    const res = await apiFetch(
      `${panelConfig.value.apiBase}/sessions/${encodeURIComponent(selectedSessionId.value)}/events/${encodeURIComponent(
        event.id
      )}/raw`
    )
    if (res.status === 404) {
      rawById.value = {...rawById.value, [event.id]: 'Raw reveal is disabled.'}
      return
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const payload = await res.json()
    rawById.value = {...rawById.value, [event.id]: payload.json}
  } catch (e) {
    rawById.value = {...rawById.value, [event.id]: formatLoadError(e, 'Unable to load raw event')}
  } finally {
    rawLoadingId.value = null
  }
}

function pickSession(sessionId, options = {}) {
  selectedSessionId.value = sessionId
  if (options.tab === 'failures') {
    showFailures()
  } else if (options.tab === 'turns') {
    showTurns()
  } else if (options.category) {
    showActivity(options.category)
  } else {
    showActivity()
  }
  loadDetail(sessionId)
}

function resetPanelState() {
  sessionList.value = null
  dashboard.value = null
  selectedSessionId.value = null
  detail.value = null
  categoryFilter.value = 'ALL'
  textFilter.value = ''
  activeDetailTab.value = 'activity'
  activityMode.value = 'tokens'
  activeSessionWindow.value = null
  error.value = null
  lastFetched.value = null
  rawById.value = {}
  rawLoadingId.value = null
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(refreshPanel)

watch(
  () => route.name,
  () => {
    resetPanelState()
    load()
  }
)
</script>

<template>
  <div>
    <PanelHeader
      :icon="panelConfig.icon"
      :title="panelConfig.title"
      :subtitle="panelConfig.description"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading" />
    <div v-else-if="!available" class="card border-info">
      <div class="card-body">
        <h5 class="card-title"><i class="bi bi-info-circle me-2"></i>{{ panelConfig.emptyTitle }}</h5>
        <p class="card-text mb-1">
          BootUI looked for sessions under <code>{{ sessionStateDir }}</code
          >.
        </p>
        <p class="card-text small text-muted mb-0">
          {{ panelConfig.emptyHint }}, or set <code>{{ panelConfig.propertyPrefix }}.session-state-dir</code>.
        </p>
      </div>
    </div>
    <template v-else>
      <div class="alert alert-secondary small mb-3">
        <i class="bi bi-shield-lock me-1"></i>
        BootUI shows sanitized signals only - prompts, raw arguments, command output, and diffs are excluded by default.
        Use <em>Reveal raw</em> on an event to inspect the source JSON locally.
      </div>

      <section class="card border-0 shadow-sm mb-3">
        <div class="card-body">
          <div class="d-flex flex-wrap justify-content-between gap-3 mb-3">
            <div>
              <div class="text-uppercase small text-muted fw-semibold">Dashboard</div>
              <h3 class="mb-1">{{ panelConfig.title }} activity overview</h3>
              <div v-if="panelConfig.inspiration" class="small text-muted mt-1">
                Inspired by
                <a :href="panelConfig.inspiration.href" rel="noopener noreferrer" target="_blank">{{
                  panelConfig.inspiration.label
                }}</a
                >.
              </div>
              <div class="small text-muted">
                <span v-if="dashboard?.lastActivityEpochMillis">
                  Last activity {{ formatRelative(dashboard.lastActivityEpochMillis) }}
                </span>
                <span v-else>No activity recorded yet</span>
                <span class="ms-2">·</span>
                <code class="ms-2">{{ dashboard?.sessionStateDir || sessionStateDir }}</code>
              </div>
            </div>
            <div v-if="dashboard?.sessionsWithSchemaDrift" class="alert alert-warning py-2 px-3 mb-0 small">
              <i class="bi bi-exclamation-triangle me-1"></i>
              {{ dashboard.sessionsWithSchemaDrift }} sessions have schema drift.
            </div>
          </div>

          <div class="row g-3 mb-3">
            <div v-for="card in dashboardCards" :key="card.label" class="col-sm-6 col-xl">
              <div class="card h-100 metric-card">
                <div class="card-body">
                  <div class="d-flex justify-content-between align-items-start">
                    <div>
                      <div class="small text-muted">{{ card.label }}</div>
                      <div class="display-6 fs-2 fw-semibold">{{ card.value }}</div>
                    </div>
                    <span :class="card.tone" class="badge rounded-pill p-2">
                      <i :class="card.icon"></i>
                    </span>
                  </div>
                  <div class="small text-muted mt-2">{{ card.detail }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="row g-3">
            <div class="col-xl-8">
              <div class="card h-100">
                <div class="card-body">
                  <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
                    <h5 class="card-title mb-0">Activity, last 24 hours</h5>
                    <div class="d-flex align-items-center gap-3">
                      <div class="btn-group btn-group-sm" role="group" aria-label="Activity chart mode">
                        <input
                          id="activity-mode-tokens"
                          v-model="activityMode"
                          class="btn-check"
                          type="radio"
                          value="tokens"
                          autocomplete="off"
                        />
                        <label class="btn btn-outline-primary" for="activity-mode-tokens">Tokens</label>
                        <input
                          id="activity-mode-events"
                          v-model="activityMode"
                          class="btn-check"
                          type="radio"
                          value="events"
                          autocomplete="off"
                        />
                        <label class="btn btn-outline-primary" for="activity-mode-events">Events</label>
                      </div>
                      <span class="small text-muted">{{ activitySummary }}</span>
                    </div>
                  </div>
                  <div class="activity-chart" :aria-label="activityChartAriaLabel">
                    <button
                      v-for="bucket in dashboard?.activityBuckets ?? []"
                      :key="bucket.startEpochMillis"
                      :class="{active: activeSessionWindow?.since === bucket.startEpochMillis}"
                      class="activity-column"
                      type="button"
                      :title="bucketTitle(bucket)"
                      :aria-label="`Show sessions from ${bucketTitle(bucket)}`"
                      @mousedown="selectActivityWindow(bucket, 'hour')"
                      @click.stop.prevent="selectActivityWindow(bucket, 'hour')"
                      @keydown.enter.prevent="selectActivityWindow(bucket, 'hour')"
                      @keydown.space.prevent="selectActivityWindow(bucket, 'hour')"
                    >
                      <div v-if="activityMode === 'tokens'" class="activity-bars activity-bars--tokens">
                        <div
                          v-if="bucket.inputTokens"
                          class="activity-bar activity-bar-token-input bg-primary"
                          :style="{height: tokenBucketHeight(bucket, 'input', maxBucketTokens)}"
                        ></div>
                        <div
                          v-if="bucket.outputTokens"
                          class="activity-bar activity-bar-token-output bg-danger"
                          :style="{height: tokenBucketHeight(bucket, 'output', maxBucketTokens)}"
                        ></div>
                      </div>
                      <div v-else class="activity-bars">
                        <div class="activity-bar bg-primary" :style="{height: bucketHeight(bucket)}"></div>
                        <div
                          v-if="bucket.errorCount"
                          class="activity-bar-error bg-danger"
                          :style="{height: bucketHeight({...bucket, eventCount: bucket.errorCount})}"
                        ></div>
                      </div>
                      <span class="activity-tooltip" aria-hidden="true">{{ bucketTooltip(bucket) }}</span>
                      <div class="activity-label">{{ formatBucketTime(bucket.startEpochMillis) }}</div>
                    </button>
                  </div>
                  <div class="small text-muted mt-2">
                    <template v-if="activityMode === 'tokens'">
                      Blue bars show input tokens; red bars show output tokens in the same hour.
                    </template>
                    <template v-else>
                      Blue bars show sanitized activity; red overlays show failures in the same hour.
                    </template>
                  </div>

                  <div class="border-top mt-4 pt-3">
                    <div class="d-flex justify-content-between align-items-center mb-3">
                      <h5 class="card-title mb-0">Activity, last 7 days</h5>
                      <span class="small text-muted"
                        >{{ formatNumber(dashboard?.activeLast7Days ?? 0) }} active sessions</span
                      >
                    </div>
                    <div class="activity-chart activity-chart--weekly" :aria-label="dailyActivityChartAriaLabel">
                      <button
                        v-for="bucket in dashboard?.dailyActivityBuckets ?? []"
                        :key="bucket.startEpochMillis"
                        :class="{active: activeSessionWindow?.since === bucket.startEpochMillis}"
                        class="activity-column"
                        type="button"
                        :title="dailyBucketTitle(bucket)"
                        :aria-label="`Show sessions from ${dailyBucketTitle(bucket)}`"
                        @mousedown="selectActivityWindow(bucket, 'day')"
                        @click.stop.prevent="selectActivityWindow(bucket, 'day')"
                        @keydown.enter.prevent="selectActivityWindow(bucket, 'day')"
                        @keydown.space.prevent="selectActivityWindow(bucket, 'day')"
                      >
                        <div class="activity-bars" :class="{'activity-bars--tokens': activityMode === 'tokens'}">
                          <template v-if="activityMode === 'tokens'">
                            <div
                              v-if="bucket.inputTokens"
                              class="activity-bar activity-bar-token-input bg-primary"
                              :style="{height: tokenBucketHeight(bucket, 'input', maxDailyBucketTokens)}"
                            ></div>
                            <div
                              v-if="bucket.outputTokens"
                              class="activity-bar activity-bar-token-output bg-danger"
                              :style="{height: tokenBucketHeight(bucket, 'output', maxDailyBucketTokens)}"
                            ></div>
                          </template>
                          <template v-else>
                            <div
                              class="activity-bar bg-primary"
                              :style="{height: bucketHeight(bucket, maxDailyBucketEvents)}"
                            ></div>
                            <div
                              v-if="bucket.errorCount"
                              class="activity-bar-error bg-danger"
                              :style="{
                                height: bucketHeight({...bucket, eventCount: bucket.errorCount}, maxDailyBucketEvents)
                              }"
                            ></div>
                          </template>
                        </div>
                        <span class="activity-tooltip" aria-hidden="true">{{ bucketTooltip(bucket) }}</span>
                        <div class="activity-label">{{ formatBucketDay(bucket.startEpochMillis) }}</div>
                      </button>
                    </div>
                    <div class="small text-muted mt-2">
                      Daily buckets use the same
                      {{ activityMode === 'tokens' ? 'token usage' : 'sanitized event stream' }}
                      as the 24-hour chart.
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="col-xl-4">
              <div class="card h-100">
                <div class="card-body">
                  <h5 class="card-title">Event mix</h5>
                  <div v-if="categoryRows.length === 0" class="text-muted small">No categories recorded yet.</div>
                  <div v-for="row in categoryRows" v-else :key="row.label" class="mb-3">
                    <div class="d-flex justify-content-between small mb-1">
                      <span class="fw-semibold">{{ row.label }}</span>
                      <span class="text-muted">{{ formatNumber(row.count) }} · {{ row.percent }}%</span>
                    </div>
                    <div class="progress metric-progress" role="progressbar" :aria-valuenow="row.percent">
                      <div class="progress-bar" :style="{width: row.percent + '%'}"></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="row g-3 mt-0">
            <div class="col-lg-5">
              <div class="card h-100">
                <div class="card-body">
                  <h5 class="card-title">Top tools</h5>
                  <div v-if="topToolRows.length === 0" class="text-muted small">No tool calls recorded yet.</div>
                  <div
                    v-for="tool in topToolRows"
                    v-else
                    :key="tool.label"
                    class="d-flex align-items-center gap-2 mb-2"
                  >
                    <code class="tool-label">{{ tool.label }}</code>
                    <div class="progress flex-grow-1 metric-progress" role="progressbar" :aria-valuenow="tool.percent">
                      <div class="progress-bar bg-dark" :style="{width: Math.max(4, tool.percent) + '%'}"></div>
                    </div>
                    <span class="small text-muted">{{ formatNumber(tool.count) }}</span>
                  </div>
                  <div v-if="dashboard?.otherToolEventCount" class="small text-muted mt-2">
                    + {{ formatNumber(dashboard.otherToolEventCount) }} events from other tools
                  </div>
                </div>
              </div>
            </div>

            <div class="col-lg-3">
              <div class="card h-100">
                <div class="card-body">
                  <h5 class="card-title">Models</h5>
                  <div v-if="modelRows.length === 0" class="text-muted small">No model metadata recorded.</div>
                  <div v-for="model in modelRows" v-else :key="model.label" class="mb-2">
                    <div class="d-flex justify-content-between small mb-1">
                      <span class="text-truncate">{{ model.label }}</span>
                      <span class="text-muted">{{ formatNumber(model.count) }}</span>
                    </div>
                    <div class="progress metric-progress" role="progressbar" :aria-valuenow="model.percent">
                      <div class="progress-bar bg-info" :style="{width: Math.max(4, model.percent) + '%'}"></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="col-lg-4">
              <div class="card h-100">
                <div class="card-body">
                  <h5 class="card-title">Recent sessions</h5>
                  <div v-if="(dashboard?.recentSessions ?? []).length === 0" class="text-muted small">
                    No recent sessions.
                  </div>
                  <div v-else class="list-group list-group-flush">
                    <button
                      v-for="session in dashboard.recentSessions"
                      :key="session.id"
                      class="list-group-item list-group-item-action px-0"
                      type="button"
                      @click="pickSession(session.id)"
                    >
                      <div class="d-flex justify-content-between gap-2">
                        <code class="text-truncate">{{ session.id }}</code>
                        <span class="small text-muted text-nowrap">{{
                          formatRelative(session.updatedAtEpochMillis)
                        }}</span>
                      </div>
                      <div class="small text-muted">
                        {{ formatNumber(session.eventCount) }} events
                        <span v-if="hasTokenUsage(session)">· {{ formatTokenUsage(session) }}</span>
                        <span v-if="session.errorCount">· {{ formatNumber(session.errorCount) }} failures</span>
                      </div>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="card shadow-sm">
        <div class="card-header bg-body d-flex flex-wrap align-items-center justify-content-between gap-2">
          <div>
            <h4 class="mb-0">Session explorer</h4>
            <div class="small text-muted d-flex flex-wrap gap-2">
              <span>Drill into sanitized events and reveal one raw event at a time when needed.</span>
              <span v-if="explorerMaxSessions">Limit: {{ formatNumber(explorerMaxSessions) }} sessions</span>
            </div>
          </div>
          <span class="badge text-bg-secondary">
            {{ formatNumber(explorerReturned) }} / {{ formatNumber(explorerTotal) }} sessions
          </span>
        </div>
        <div class="card-body">
          <div v-if="explorerLimitMessage" class="alert alert-info py-2 small">
            <i class="bi bi-funnel me-1"></i>{{ explorerLimitMessage }}. Increase
            <code>{{ panelConfig.propertyPrefix }}.max-sessions</code> to show more.
          </div>
          <div v-if="activeSessionWindow" class="alert alert-primary py-2 small d-flex justify-content-between gap-2">
            <span>
              <i class="bi bi-filter me-1"></i>
              Filtered to sessions active during {{ activeSessionWindow.label }}.
            </span>
            <button class="btn btn-sm btn-link p-0" type="button" @click="clearActivityWindow">Clear filter</button>
          </div>
          <div v-if="sessionWarnings.length" class="mb-3">
            <div v-for="warning in sessionWarnings" :key="warning" class="small text-warning">
              <i class="bi bi-exclamation-triangle me-1"></i>{{ warning }}
            </div>
          </div>
          <div class="row g-3">
            <div class="col-lg-4">
              <div v-if="sessions.length === 0" class="alert alert-secondary">
                No {{ panelConfig.title }} sessions recorded yet.
              </div>
              <div v-else class="list-group session-list">
                <div
                  v-for="session in sessions"
                  :key="session.id"
                  :class="{active: session.id === selectedSessionId}"
                  class="list-group-item list-group-item-action session-row"
                  @click="pickSession(session.id)"
                >
                  <div class="d-flex justify-content-between align-items-start">
                    <div class="me-2 text-truncate">
                      <div class="fw-semibold text-truncate">
                        <code>{{ session.id }}</code>
                      </div>
                      <div class="small text-muted text-truncate">
                        <span v-if="session.model"><i class="bi bi-cpu me-1"></i>{{ session.model }}</span>
                        <span v-if="session.workingDirectory" class="ms-2"
                          ><i class="bi bi-folder me-1"></i>{{ session.workingDirectory }}</span
                        >
                      </div>
                    </div>
                    <div class="text-end small">
                      <div>{{ formatRelative(session.updatedAtEpochMillis) }}</div>
                      <div>
                        <button
                          :aria-label="`Show activity for ${session.id}`"
                          class="badge text-bg-secondary border-0 me-1"
                          type="button"
                          @click.stop="pickSession(session.id, {tab: 'activity'})"
                        >
                          {{ formatNumber(session.eventCount) }} events
                        </button>
                        <button
                          v-if="session.errorCount > 0"
                          :aria-label="`Show failures for ${session.id}`"
                          class="badge text-bg-danger border-0"
                          type="button"
                          @click.stop="pickSession(session.id, {tab: 'failures'})"
                        >
                          {{ formatNumber(session.errorCount) }} errors
                        </button>
                        <span v-if="hasTokenUsage(session)" class="badge text-bg-warning ms-1">
                          {{ formatTokenUsage(session) }}
                        </span>
                      </div>
                    </div>
                  </div>
                  <div v-if="session.lastActivitySummary" class="small text-muted mt-1 text-truncate">
                    {{ session.lastActivitySummary }}
                  </div>
                  <div v-if="session.schemaDrift" class="small text-warning mt-1">
                    <i class="bi bi-exclamation-triangle me-1"></i>schema drift
                  </div>
                </div>
              </div>
            </div>

            <div class="col-lg-8">
              <div v-if="!selectedSessionId" class="alert alert-secondary">Select a session to see its activity.</div>
              <div v-else-if="detailLoading" class="text-muted">Loading session…</div>
              <div v-else-if="!detail" class="alert alert-warning">Session not found.</div>
              <template v-else>
                <div class="card mb-3">
                  <div class="card-body">
                    <h5 class="card-title mb-2">
                      <code>{{ detail.summary.id }}</code>
                      <span v-if="detail.summary.model" class="badge text-bg-primary ms-2">{{
                        detail.summary.model
                      }}</span>
                      <span v-if="detail.summary.status" class="badge text-bg-info ms-2">{{
                        detail.summary.status
                      }}</span>
                    </h5>
                    <div class="small text-muted mb-2">
                      <span v-if="detail.summary.workingDirectory"
                        ><i class="bi bi-folder me-1"></i>{{ detail.summary.workingDirectory }}</span
                      >
                      <span class="ms-3"
                        ><i class="bi bi-clock me-1"></i>updated
                        {{ formatRelative(detail.summary.updatedAtEpochMillis) }}</span
                      >
                    </div>
                    <div class="d-flex flex-wrap gap-2">
                      <button
                        class="badge text-bg-secondary border-0"
                        type="button"
                        aria-label="Show activity feed"
                        @click="showActivity()"
                      >
                        {{ formatNumber(detail.counts.total) }} events
                      </button>
                      <button
                        class="badge text-bg-secondary border-0"
                        type="button"
                        aria-label="Show turn story"
                        @click="showTurns()"
                      >
                        {{ formatNumber(detail.summary.turnCount) }} turns
                      </button>
                      <span v-if="hasTokenUsage(detail.summary)" class="badge text-bg-warning">
                        tokens · {{ formatTokenUsage(detail.summary) }}
                      </span>
                      <button
                        v-if="detail.counts.errors > 0"
                        class="badge text-bg-danger border-0"
                        type="button"
                        aria-label="Show failures"
                        @click="showFailures()"
                      >
                        {{ formatNumber(detail.counts.errors) }} errors
                      </button>
                      <button
                        v-for="entry in breakdown"
                        :key="entry.category"
                        :class="categoryBadgeClass(entry.category)"
                        class="badge border-0"
                        type="button"
                        :aria-label="`Filter activity by ${entry.category}`"
                        @click="showActivity(entry.category)"
                      >
                        {{ entry.category }} · {{ formatNumber(entry.count) }}
                      </button>
                    </div>
                    <div v-if="detail.warnings && detail.warnings.length" class="mt-2">
                      <div v-for="warning in detail.warnings" :key="warning" class="small text-warning">
                        <i class="bi bi-exclamation-triangle me-1"></i>{{ warning }}
                      </div>
                    </div>
                  </div>
                </div>

                <div class="row g-2 mb-3 align-items-end">
                  <div class="col-md-4">
                    <label class="form-label">Category</label>
                    <select v-model="categoryFilter" class="form-select">
                      <option v-for="cat in categories" :key="cat" :value="cat">{{ cat }}</option>
                    </select>
                  </div>
                  <div class="col-md-6">
                    <label class="form-label">Filter</label>
                    <input v-model="textFilter" class="form-control" placeholder="Tool name, summary, or type…" />
                  </div>
                  <div class="col-md-2 text-end small text-muted">
                    {{ filteredEvents.length }} / {{ detail.recentEvents.length }}
                  </div>
                </div>

                <ul class="nav nav-tabs mb-3" role="tablist">
                  <li class="nav-item">
                    <button
                      :class="{active: activeDetailTab === 'activity'}"
                      class="nav-link"
                      role="tab"
                      type="button"
                      @click="showActivity(categoryFilter)"
                    >
                      Activity feed
                    </button>
                  </li>
                  <li class="nav-item">
                    <button
                      :class="{active: activeDetailTab === 'turns'}"
                      class="nav-link"
                      role="tab"
                      type="button"
                      @click="showTurns()"
                    >
                      Turn story
                    </button>
                  </li>
                  <li class="nav-item">
                    <button
                      :class="{active: activeDetailTab === 'failures'}"
                      class="nav-link"
                      role="tab"
                      type="button"
                      @click="showFailures()"
                    >
                      Failures
                      <span v-if="detail.counts.errors" class="badge text-bg-danger ms-1">{{
                        detail.counts.errors
                      }}</span>
                    </button>
                  </li>
                </ul>

                <div class="tab-content">
                  <div v-if="activeDetailTab === 'activity'" class="tab-pane active" role="tabpanel">
                    <div v-if="filteredEvents.length === 0" class="text-muted small">No events match this filter.</div>
                    <ul v-else class="list-group">
                      <li v-for="event in filteredEvents" :key="event.id" class="list-group-item">
                        <div class="d-flex justify-content-between align-items-start gap-2">
                          <div class="me-2">
                            <span :class="categoryBadgeClass(event.category)" class="badge me-2">{{
                              event.category
                            }}</span>
                            <code v-if="event.toolName">{{ event.toolName }}</code>
                            <span v-else-if="event.type" class="text-muted">{{ event.type }}</span>
                            <span v-if="event.success === false" class="badge text-bg-danger ms-2">error</span>
                            <span v-if="event.success === true" class="badge text-bg-success ms-2">ok</span>
                            <div class="small text-muted mt-1">{{ event.summary }}</div>
                          </div>
                          <div class="text-end small text-muted">
                            <div>{{ formatTime(event.timestampEpochMillis) }}</div>
                            <button
                              class="btn btn-sm btn-link p-0"
                              type="button"
                              :disabled="rawLoadingId === event.id"
                              @click="revealRaw(event)"
                            >
                              {{ rawById[event.id] !== undefined ? 'Hide raw' : 'Reveal raw' }}
                            </button>
                          </div>
                        </div>
                        <pre
                          v-if="rawById[event.id] !== undefined"
                          class="mt-2 mb-0 small bg-light border rounded p-2"
                          >{{ rawById[event.id] }}</pre
                        >
                      </li>
                    </ul>
                  </div>

                  <div v-else-if="activeDetailTab === 'turns'" class="tab-pane active" role="tabpanel">
                    <div v-if="!detail.turns || detail.turns.length === 0" class="text-muted small">
                      No turn information available.
                    </div>
                    <ol v-else class="list-group list-group-numbered">
                      <li v-for="turn in detail.turns" :key="turn.index" class="list-group-item">
                        <div class="d-flex justify-content-between align-items-start">
                          <div>
                            <div class="fw-semibold">{{ turn.summary || 'Turn ' + (turn.index + 1) }}</div>
                            <div class="small text-muted">
                              {{ formatNumber(turn.eventCount) }} events
                              <span v-if="hasTokenUsage(turn)">· {{ formatTokenUsage(turn) }}</span>
                            </div>
                          </div>
                          <div class="small text-muted">
                            <span v-if="turn.startedAtEpochMillis">{{ formatTime(turn.startedAtEpochMillis) }}</span>
                            <span v-if="turn.durationMillis" class="ms-2">· {{ turn.durationMillis }} ms</span>
                          </div>
                        </div>
                      </li>
                    </ol>
                  </div>

                  <div v-else-if="activeDetailTab === 'failures'" class="tab-pane active" role="tabpanel">
                    <div v-if="failureEvents.length === 0" class="text-muted small">No failures recorded.</div>
                    <ul v-else class="list-group">
                      <li v-for="event in failureEvents" :key="event.id" class="list-group-item">
                        <div class="d-flex justify-content-between align-items-start gap-2">
                          <div>
                            <span :class="categoryBadgeClass(event.category)" class="badge me-2">{{
                              event.category
                            }}</span>
                            <code v-if="event.toolName">{{ event.toolName }}</code>
                            <span v-else class="text-muted">{{ event.type || 'event' }}</span>
                            <span class="badge text-bg-danger ms-2">failed</span>
                            <div class="small text-muted mt-1">{{ event.summary }}</div>
                            <div class="small text-muted">type: {{ event.type || 'unknown' }}</div>
                          </div>
                          <div class="small text-muted text-end">{{ formatTime(event.timestampEpochMillis) }}</div>
                        </div>
                      </li>
                    </ul>
                  </div>
                </div>
              </template>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.metric-card {
  border-color: var(--bs-border-color-translucent);
}

.metric-progress {
  height: 0.45rem;
}

.activity-chart {
  align-items: stretch;
  border-bottom: 1px solid var(--bs-border-color);
  display: flex;
  gap: 0.35rem;
  min-height: 220px;
  overflow-x: auto;
  padding-top: 0.75rem;
}

.activity-column {
  align-items: center;
  background: transparent;
  border: 0;
  color: inherit;
  display: flex;
  flex: 1 0 1.75rem;
  flex-direction: column;
  justify-content: flex-end;
  min-width: 1.75rem;
  padding: 0;
  position: relative;
}

.activity-column:hover .activity-bar,
.activity-column:focus-visible .activity-bar {
  filter: brightness(0.9);
}

.activity-column.active .activity-bar {
  outline: 2px solid var(--bs-primary);
  outline-offset: 2px;
}

.activity-column > * {
  pointer-events: none;
}

.activity-tooltip {
  background: var(--bs-body-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bootui-radius-xs);
  box-shadow: var(--bs-box-shadow-sm);
  color: var(--bs-body-color);
  font-size: 0.75rem;
  left: 50%;
  opacity: 0;
  padding: 0.25rem 0.5rem;
  position: absolute;
  top: 0;
  transform: translateX(-50%) translateY(-0.25rem);
  transition:
    opacity 0.12s ease,
    transform 0.12s ease;
  white-space: nowrap;
  z-index: 2;
}

.activity-column:hover .activity-tooltip,
.activity-column:focus-visible .activity-tooltip {
  opacity: 1;
  transform: translateX(-50%);
}

.activity-bars {
  align-items: flex-end;
  display: flex;
  height: 170px;
  justify-content: center;
  position: relative;
  width: 100%;
}

.activity-bar,
.activity-bar-error {
  border-radius: var(--bootui-radius-xs) var(--bootui-radius-xs) 0 0;
  min-height: 2px;
  width: 70%;
}

.activity-bar-error {
  bottom: 0;
  opacity: 0.8;
  position: absolute;
  width: 34%;
}

.activity-bars--tokens {
  align-items: center;
  flex-direction: column-reverse;
  justify-content: flex-start;
}

.activity-bars--tokens .activity-bar {
  border-radius: 0;
  min-height: 0;
  width: 70%;
}

.activity-bars--tokens .activity-bar-token-input {
  border-radius: 0 0 var(--bootui-radius-xs) var(--bootui-radius-xs);
}

.activity-bars--tokens .activity-bar-token-output {
  border-radius: var(--bootui-radius-xs) var(--bootui-radius-xs) 0 0;
}

.activity-label {
  color: var(--bs-secondary-color);
  font-size: 0.7rem;
  margin-top: 0.35rem;
  text-align: center;
}

.activity-chart--weekly {
  min-height: 190px;
}

.activity-chart--weekly .activity-bars {
  height: 135px;
}

.tool-label {
  max-width: 11rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-row {
  cursor: pointer;
}

.session-list {
  max-height: 44rem;
  overflow-y: auto;
}
</style>
