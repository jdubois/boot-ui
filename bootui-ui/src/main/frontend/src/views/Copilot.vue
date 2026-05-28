<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'

const sessionList = ref(null)
const dashboard = ref(null)
const selectedSessionId = ref(null)
const detail = ref(null)
const categoryFilter = ref('ALL')
const textFilter = ref('')
const loading = ref(true)
const detailLoading = ref(false)
const error = ref(null)
const status = ref('Loading')
const rawById = ref({})
const rawLoadingId = ref(null)
let eventSource = null

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
const unavailableReason = computed(() => sessionList.value?.unavailableReason)
const sessionStateDir = computed(() => sessionList.value?.sessionStateDir)
const totalEvents = computed(
  () => dashboard.value?.eventCount ?? sessions.value.reduce((sum, item) => sum + item.eventCount, 0)
)
const maxBucketEvents = computed(() =>
  Math.max(0, ...(dashboard.value?.activityBuckets ?? []).map((bucket) => bucket.eventCount))
)

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

const statusClass = computed(
  () =>
    ({
      Live: 'text-bg-success',
      Loading: 'text-bg-secondary',
      Disconnected: 'text-bg-danger',
      Unavailable: 'text-bg-warning'
    })[status.value] || 'text-bg-secondary'
)

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

const failureEvents = computed(() => (detail.value?.recentEvents ?? []).filter((event) => event.success === false))

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
  const date = new Date(timestamp)
  return date.toLocaleTimeString([], {hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'})
}

function formatBucketTime(timestamp) {
  if (timestamp === null || timestamp === undefined) return '—'
  const date = new Date(timestamp)
  return date.toLocaleTimeString([], {hour12: false, hour: '2-digit'})
}

function formatNumber(value) {
  return new Intl.NumberFormat().format(value ?? 0)
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

function bucketHeight(bucket) {
  if (!bucket.eventCount || !maxBucketEvents.value) return '4%'
  return `${Math.max(8, Math.round((bucket.eventCount / maxBucketEvents.value) * 100))}%`
}

function bucketTitle(bucket) {
  return `${formatBucketTime(bucket.startEpochMillis)}: ${formatNumber(bucket.eventCount)} events, ${formatNumber(
    bucket.errorCount
  )} failures`
}

async function loadDashboard() {
  try {
    const res = await fetch('api/copilot/dashboard')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    dashboard.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = e.message
  }
}

async function loadSessions() {
  try {
    const res = await fetch('api/copilot/sessions')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    sessionList.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadDetail(sessionId) {
  if (!sessionId) {
    detail.value = null
    return
  }
  detailLoading.value = true
  try {
    const res = await fetch(`api/copilot/sessions/${encodeURIComponent(sessionId)}`)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    detail.value = await res.json()
    rawById.value = {}
  } catch (e) {
    error.value = e.message
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function revealRaw(event) {
  if (rawById.value[event.id] !== undefined) {
    rawById.value = {...rawById.value, [event.id]: undefined}
    return
  }
  rawLoadingId.value = event.id
  try {
    const res = await fetch(
      `api/copilot/sessions/${encodeURIComponent(selectedSessionId.value)}/events/${encodeURIComponent(event.id)}/raw`
    )
    if (res.status === 404) {
      rawById.value = {...rawById.value, [event.id]: 'Raw reveal is disabled.'}
      return
    }
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const payload = await res.json()
    rawById.value = {...rawById.value, [event.id]: payload.json}
  } catch (e) {
    rawById.value = {...rawById.value, [event.id]: 'Unable to load raw event: ' + e.message}
  } finally {
    rawLoadingId.value = null
  }
}

function pickSession(sessionId) {
  selectedSessionId.value = sessionId
  loadDetail(sessionId)
}

function connectStream() {
  disconnect()
  eventSource = new EventSource('api/copilot/stream')
  eventSource.addEventListener('sessions', async (event) => {
    try {
      sessionList.value = JSON.parse(event.data)
      status.value = sessionList.value.available === false ? 'Unavailable' : 'Live'
      // refresh detail if the selected session changed
      if (selectedSessionId.value) {
        await loadDetail(selectedSessionId.value)
      }
    } catch (e) {
      // ignore parse errors
    }
  })
  eventSource.addEventListener('dashboard', (event) => {
    try {
      dashboard.value = JSON.parse(event.data)
    } catch (e) {
      // ignore parse errors
    }
  })
  eventSource.onerror = () => {
    status.value = 'Disconnected'
  }
}

function disconnect() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

onMounted(async () => {
  await Promise.all([loadSessions(), loadDashboard()])
  if (sessionList.value?.available !== false) {
    status.value = 'Live'
    connectStream()
  } else {
    status.value = 'Unavailable'
  }
})

onBeforeUnmount(disconnect)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-3 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-robot me-2"></i>Copilot</h2>
        <div class="text-muted">Sanitized command activity, tool usage, failures, and recent sessions.</div>
      </div>
      <span :class="statusClass" class="badge">{{ status }}</span>
    </div>

    <div v-if="loading" class="text-muted">Loading…</div>
    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>
    <div v-else-if="!available" class="card border-info">
      <div class="card-body">
        <h5 class="card-title"><i class="bi bi-info-circle me-2"></i>No Copilot session state found</h5>
        <p class="card-text mb-1">
          BootUI looked for sessions under <code>{{ sessionStateDir }}</code
          >.
        </p>
        <p class="card-text small text-muted mb-0">
          Run the local Copilot CLI at least once to create it, or set
          <code>bootui.copilot.session-state-dir</code>.
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
              <h3 class="mb-1">Copilot activity overview</h3>
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
            <div v-for="card in dashboardCards" :key="card.label" class="col-sm-6 col-xl-3">
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
                  <div class="d-flex justify-content-between align-items-center mb-3">
                    <h5 class="card-title mb-0">Activity, last 24 hours</h5>
                    <span class="small text-muted">{{ formatNumber(totalEvents) }} total events</span>
                  </div>
                  <div
                    class="activity-chart"
                    role="img"
                    :aria-label="`${formatNumber(totalEvents)} sanitized Copilot events across ${
                      dashboard?.sessionCount ?? 0
                    } sessions`"
                  >
                    <div
                      v-for="bucket in dashboard?.activityBuckets ?? []"
                      :key="bucket.startEpochMillis"
                      class="activity-column"
                      :title="bucketTitle(bucket)"
                    >
                      <div class="activity-bars">
                        <div class="activity-bar bg-primary" :style="{height: bucketHeight(bucket)}"></div>
                        <div
                          v-if="bucket.errorCount"
                          class="activity-bar-error bg-danger"
                          :style="{height: bucketHeight({...bucket, eventCount: bucket.errorCount})}"
                        ></div>
                      </div>
                      <div class="activity-label">{{ formatBucketTime(bucket.startEpochMillis) }}</div>
                    </div>
                  </div>
                  <div class="small text-muted mt-2">
                    Blue bars show sanitized activity; red overlays show failures in the same hour.
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
            <div class="small text-muted">
              Drill into sanitized events and reveal one raw event at a time when needed.
            </div>
          </div>
          <span class="badge text-bg-secondary">{{ sessions.length }} sessions</span>
        </div>
        <div class="card-body">
          <div class="row g-3">
            <div class="col-lg-4">
              <div v-if="sessions.length === 0" class="alert alert-secondary">No Copilot sessions recorded yet.</div>
              <div v-else class="list-group session-list">
                <button
                  v-for="session in sessions"
                  :key="session.id"
                  :class="{active: session.id === selectedSessionId}"
                  class="list-group-item list-group-item-action"
                  type="button"
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
                        <span class="badge text-bg-secondary me-1">{{ formatNumber(session.eventCount) }} events</span>
                        <span v-if="session.errorCount > 0" class="badge text-bg-danger"
                          >{{ formatNumber(session.errorCount) }} errors</span
                        >
                      </div>
                    </div>
                  </div>
                  <div v-if="session.lastActivitySummary" class="small text-muted mt-1 text-truncate">
                    {{ session.lastActivitySummary }}
                  </div>
                  <div v-if="session.schemaDrift" class="small text-warning mt-1">
                    <i class="bi bi-exclamation-triangle me-1"></i>schema drift
                  </div>
                </button>
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
                      <span class="badge text-bg-secondary">{{ formatNumber(detail.counts.total) }} events</span>
                      <span class="badge text-bg-secondary">{{ formatNumber(detail.summary.turnCount) }} turns</span>
                      <span v-if="detail.counts.errors > 0" class="badge text-bg-danger"
                        >{{ formatNumber(detail.counts.errors) }} errors</span
                      >
                      <span
                        v-for="entry in breakdown"
                        :key="entry.category"
                        :class="categoryBadgeClass(entry.category)"
                        class="badge"
                        >{{ entry.category }} · {{ formatNumber(entry.count) }}</span
                      >
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
                    <button class="nav-link active" data-bs-toggle="tab" data-bs-target="#tab-activity">
                      Activity feed
                    </button>
                  </li>
                  <li class="nav-item">
                    <button class="nav-link" data-bs-toggle="tab" data-bs-target="#tab-turns">Turn story</button>
                  </li>
                  <li class="nav-item">
                    <button class="nav-link" data-bs-toggle="tab" data-bs-target="#tab-failures">
                      Failures
                      <span v-if="failureEvents.length" class="badge text-bg-danger ms-1">{{
                        failureEvents.length
                      }}</span>
                    </button>
                  </li>
                </ul>

                <div class="tab-content">
                  <div id="tab-activity" class="tab-pane fade show active">
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

                  <div id="tab-turns" class="tab-pane fade">
                    <div v-if="!detail.turns || detail.turns.length === 0" class="text-muted small">
                      No turn information available.
                    </div>
                    <ol v-else class="list-group list-group-numbered">
                      <li v-for="turn in detail.turns" :key="turn.index" class="list-group-item">
                        <div class="d-flex justify-content-between align-items-start">
                          <div>
                            <div class="fw-semibold">{{ turn.summary || 'Turn ' + (turn.index + 1) }}</div>
                            <div class="small text-muted">{{ formatNumber(turn.eventCount) }} events</div>
                          </div>
                          <div class="small text-muted">
                            <span v-if="turn.startedAtEpochMillis">{{ formatTime(turn.startedAtEpochMillis) }}</span>
                            <span v-if="turn.durationMillis" class="ms-2">· {{ turn.durationMillis }} ms</span>
                          </div>
                        </div>
                      </li>
                    </ol>
                  </div>

                  <div id="tab-failures" class="tab-pane fade">
                    <div v-if="failureEvents.length === 0" class="text-muted small">No failures recorded.</div>
                    <ul v-else class="list-group">
                      <li v-for="event in failureEvents" :key="event.id" class="list-group-item">
                        <span :class="categoryBadgeClass(event.category)" class="badge me-2">{{ event.category }}</span>
                        <code v-if="event.toolName">{{ event.toolName }}</code>
                        <span class="badge text-bg-danger ms-2">error</span>
                        <div class="small text-muted mt-1">{{ event.summary }}</div>
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
  display: flex;
  flex: 1 0 1.75rem;
  flex-direction: column;
  justify-content: flex-end;
  min-width: 1.75rem;
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
  border-radius: 0.35rem 0.35rem 0 0;
  min-height: 2px;
  width: 70%;
}

.activity-bar-error {
  bottom: 0;
  opacity: 0.8;
  position: absolute;
  width: 34%;
}

.activity-label {
  color: var(--bs-secondary-color);
  font-size: 0.7rem;
  margin-top: 0.35rem;
}

.tool-label {
  max-width: 11rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-list {
  max-height: 44rem;
  overflow-y: auto;
}
</style>
