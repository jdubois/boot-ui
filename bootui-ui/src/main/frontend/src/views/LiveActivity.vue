<script setup>
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import UnavailableState from './components/UnavailableState.vue'
import {formatBytes, formatClockTime, formatNumber} from '../utils/format.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import {bucketEntries, deepLink, filterEntries, groupEntries} from '../utils/activityStream.js'

const TYPES = ['REQUEST', 'SQL', 'EXCEPTION', 'SECURITY']
const SEVERITIES = ['OK', 'SLOW', 'WARN', 'ERROR']
const FILTERS_STORAGE_KEY = 'bootui.activity.filters'

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const typeFilter = ref('')
const severityFilter = ref('')
const textFilter = ref('')
const errorsOnly = ref(false)

const profile = ref(null)
const profileLoading = ref(false)
const profileError = ref(null)
const profileRequestId = ref(null)
const drawerEl = ref(null)

const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

restoreFilters()

async function loadActivity() {
  try {
    const response = await fetch('api/activity')
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    report.value = await response.json()
    error.value = null
    lastFetched.value = Date.now()
  } catch (err) {
    error.value = err.message || 'Could not load activity'
    throw err
  }
}

const {autoRefresh, loading, load: refreshNow} = useEventStreamRefresh('api/activity/stream', loadActivity)

const available = computed(() => report.value?.available ?? false)
const kpis = computed(() => report.value?.kpis ?? null)
const sources = computed(() => report.value?.sources ?? [])
const warnings = computed(() => report.value?.warnings ?? [])

const visibleEntries = computed(() => {
  const filtered = filterEntries(report.value?.entries ?? [], {
    type: typeFilter.value,
    severity: severityFilter.value,
    text: textFilter.value,
    errorsOnly: errorsOnly.value
  })
  return groupEntries(filtered)
})

const hasActiveFilters = computed(
  () => !!typeFilter.value || !!severityFilter.value || !!textFilter.value.trim() || errorsOnly.value
)

// Requests-over-time mini timeline: bucket the unfiltered stream so spikes and error bursts stay
// visible even while a filter narrows the table below.
const SPARKLINE_BUCKETS = 32
const SPARKLINE_HEIGHT = 36
const sparkline = computed(() => bucketEntries(report.value?.entries ?? [], SPARKLINE_BUCKETS))
const sparklineMax = computed(() => sparkline.value.reduce((max, bucket) => Math.max(max, bucket.count), 0))
const sparkBars = computed(() => {
  const data = sparkline.value
  const max = sparklineMax.value
  if (!data.length || max <= 0) return []
  const slot = 100 / data.length
  return data.map((bucket, index) => {
    const height = (bucket.count / max) * SPARKLINE_HEIGHT
    return {
      key: index,
      x: index * slot,
      width: slot,
      height,
      y: SPARKLINE_HEIGHT - height,
      errorHeight: max > 0 ? (bucket.errors / max) * SPARKLINE_HEIGHT : 0,
      count: bucket.count,
      errors: bucket.errors
    }
  })
})

const subtitle = computed(() => {
  const counts = report.value?.typeCounts ?? {}
  const total = Object.values(counts).reduce((sum, value) => sum + value, 0)
  return `${formatNumber(total)} recent events · ${sources.value.length} source${sources.value.length === 1 ? '' : 's'}`
})

const paused = computed(() => !autoRefresh.value)

const timingSummary = computed(() => {
  const timing = profile.value?.timing
  if (!timing) return ''
  let text = `${timing.sqlCount} SQL statement(s), ${timing.sqlMs} ms in SQL`
  if (timing.sqlPercent != null) {
    text += ` (${timing.sqlPercent}% of request)`
  }
  return text
})

function togglePause() {
  autoRefresh.value = !autoRefresh.value
}

function typeIcon(type) {
  return (
    {
      REQUEST: 'bi-arrow-left-right',
      SQL: 'bi-database',
      EXCEPTION: 'bi-exclamation-octagon',
      SECURITY: 'bi-shield-lock'
    }[type] || 'bi-dot'
  )
}

function severityBadgeClass(severity) {
  return (
    {
      OK: 'text-bg-success',
      SLOW: 'text-bg-warning',
      WARN: 'text-bg-warning',
      ERROR: 'text-bg-danger'
    }[severity] || 'text-bg-secondary'
  )
}

function rowClass(entry) {
  if (entry.severity === 'ERROR') return 'table-danger'
  if (entry.severity === 'SLOW' || entry.severity === 'WARN') return 'table-warning'
  return ''
}

function formatDurationMs(durationMs) {
  if (durationMs == null) return ''
  if (durationMs < 1) return '<1 ms'
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(2)} s`
}

function entryLink(entry) {
  return deepLink(entry)
}

function onRowClick(entry) {
  if (entry.profileable) {
    openProfile(entry)
  }
}

async function openProfile(entry) {
  if (!entry.profileable) return
  profileRequestId.value = entry.id
  profileLoading.value = true
  profileError.value = null
  profile.value = null
  try {
    const response = await fetch(`api/activity/request/${encodeURIComponent(entry.id)}`)
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    profile.value = await response.json()
  } catch (err) {
    profileError.value = err.message || 'Could not load request profile'
  } finally {
    profileLoading.value = false
    focusDrawer()
  }
}

function closeProfile() {
  profileRequestId.value = null
  profile.value = null
  profileError.value = null
}

function focusDrawer() {
  requestAnimationFrame(() => {
    drawerEl.value?.focus?.()
  })
}

function onKeydown(event) {
  if (!profileRequestId.value) return
  if (event.key === 'Escape') {
    closeProfile()
    return
  }
  if (event.key === 'Tab') {
    trapFocus(event)
  }
}

// Minimal focus trap so keyboard users cannot tab out of the open drawer.
function trapFocus(event) {
  const root = drawerEl.value
  if (!root) return
  const focusable = root.querySelectorAll(
    'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])'
  )
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === root)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

function copyProfile() {
  const text = renderProfileReport()
  if (text) {
    copyToClipboard(text, 'profile')
  }
}

// Build a plain-text, already-masked timeline (request + SQL + exceptions) a developer can paste
// straight into a bug report. All values come from the masked profile payload; nothing new is read.
function renderProfileReport() {
  const p = profile.value
  if (!p || !p.available) return ''
  const lines = []
  lines.push('# BootUI request profile')
  const req = p.request
  lines.push(`Request: ${req.method} ${req.path} → ${req.status}`)
  if (req.durationMs != null) lines.push(`Duration: ${formatDurationMs(req.durationMs)}`)
  if (req.principal) lines.push(`Principal: ${req.principal}`)
  if (req.traceId) lines.push(`Trace id: ${req.traceId}`)
  if (p.timing) lines.push(`Timing: ${timingSummary.value}`)
  lines.push('')
  lines.push(`SQL (${p.sqlCorrelationApproximate ? 'approximate, time-window' : 'exact'}):`)
  if (p.sqlGroups && p.sqlGroups.length) {
    for (const group of p.sqlGroups) {
      const flag = group.potentialNPlusOne ? ' [N+1]' : ''
      lines.push(`  ×${group.executions}${flag} ${group.sql}`)
    }
  } else {
    lines.push('  (none correlated)')
  }
  if (p.exceptions && p.exceptions.length) {
    lines.push('')
    lines.push('Exceptions:')
    for (const ex of p.exceptions) {
      const message = ex.message ? `: ${ex.message}` : ''
      lines.push(`  ${ex.exceptionClassName}${message}`)
      if (ex.location) lines.push(`    at ${ex.location}`)
    }
  }
  if (p.security && p.security.length) {
    lines.push('')
    lines.push('Security events:')
    for (const event of p.security) {
      const principal = event.principal ? ` · ${event.principal}` : ''
      lines.push(`  ${event.type}${principal}`)
    }
  }
  if (p.notes && p.notes.length) {
    lines.push('')
    lines.push('Notes:')
    for (const note of p.notes) lines.push(`  - ${note}`)
  }
  return lines.join('\n')
}

function restoreFilters() {
  try {
    const raw = localStorage.getItem(FILTERS_STORAGE_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    if (typeof saved.type === 'string') typeFilter.value = saved.type
    if (typeof saved.severity === 'string') severityFilter.value = saved.severity
    if (typeof saved.text === 'string') textFilter.value = saved.text
    if (typeof saved.errorsOnly === 'boolean') errorsOnly.value = saved.errorsOnly
  } catch (_) {
    // Corrupt or unavailable storage: fall back to defaults silently.
  }
}

function persistFilters() {
  try {
    localStorage.setItem(
      FILTERS_STORAGE_KEY,
      JSON.stringify({
        type: typeFilter.value,
        severity: severityFilter.value,
        text: textFilter.value,
        errorsOnly: errorsOnly.value
      })
    )
  } catch (_) {
    // Ignore storage write failures (private mode, quota); filters still work in-session.
  }
}

watch([typeFilter, severityFilter, textFilter, errorsOnly], persistFilters)

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function clearFilters() {
  typeFilter.value = ''
  severityFilter.value = ''
  textFilter.value = ''
  errorsOnly.value = false
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-broadcast"
      title="Live Activity"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      auto-refresh-title="Stream new activity live over Server-Sent Events while this tab is visible"
      @refresh="refreshNow"
    />

    <UnavailableState
      v-if="report && !available"
      icon="bi-broadcast"
      message="No live activity sources are available yet. Enable HTTP exchange recording, SQL tracing, exception capture, or security logs to populate this stream."
    />

    <template v-else-if="report">
      <div v-if="kpis" class="row g-2 mb-3 activity-kpis">
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Requests/min</div>
              <div class="fs-5">{{ formatNumber(kpis.requestsPerMinute) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Error rate</div>
              <div class="fs-5">{{ kpis.errorRatePercent }}%</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Latency p50 / p95</div>
              <div class="fs-5">{{ kpis.p50LatencyMs ?? '—' }} / {{ kpis.p95LatencyMs ?? '—' }} ms</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">SQL/min</div>
              <div class="fs-5">{{ formatNumber(kpis.sqlPerMinute) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <component
            :is="kpis.slowestEndpoint ? 'router-link' : 'div'"
            class="card h-100 text-reset text-decoration-none"
            :class="{'activity-kpi-link': kpis.slowestEndpoint}"
            :to="kpis.slowestEndpoint ? {path: '/http-exchanges', query: {q: kpis.slowestEndpoint}} : undefined"
            :title="kpis.slowestEndpoint ? `Open ${kpis.slowestEndpoint} in HTTP Exchanges` : null"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Slowest endpoint
                <i v-if="kpis.slowestEndpoint" class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5 text-truncate">
                <template v-if="kpis.slowestEndpoint">
                  {{ kpis.slowestEndpointMs ?? '—' }} ms
                  <span class="text-muted small d-block text-truncate">{{ kpis.slowestEndpoint }}</span>
                </template>
                <template v-else>—</template>
              </div>
            </div>
          </component>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/exceptions'}"
            title="Open the Exceptions panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Active exceptions
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ formatNumber(kpis.activeExceptionCount) }}</div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/health'}"
            title="Open the Health panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Health
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ kpis.healthStatus ?? '—' }}</div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/heap-dump'}"
            title="Open the Heap Dump panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Heap used
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ formatBytes(kpis.heapUsedBytes) }}</div>
            </div>
          </router-link>
        </div>
      </div>

      <div class="d-flex flex-wrap align-items-end gap-2 mb-3">
        <div class="activity-text-filter">
          <label class="form-label small mb-1">Filter</label>
          <input
            v-model="textFilter"
            type="search"
            class="form-control form-control-sm"
            placeholder="Path, status, SQL, exception…"
            aria-label="Free-text activity filter"
          />
        </div>
        <div>
          <label class="form-label small mb-1">Type</label>
          <select v-model="typeFilter" class="form-select form-select-sm">
            <option value="">All types</option>
            <option v-for="type in TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
        </div>
        <div>
          <label class="form-label small mb-1">Severity</label>
          <select v-model="severityFilter" class="form-select form-select-sm">
            <option value="">All severities</option>
            <option v-for="severity in SEVERITIES" :key="severity" :value="severity">{{ severity }}</option>
          </select>
        </div>
        <div class="form-check mb-1">
          <input id="activity-errors-only" v-model="errorsOnly" class="form-check-input" type="checkbox" />
          <label class="form-check-label small" for="activity-errors-only">Errors only</label>
        </div>
        <button v-if="hasActiveFilters" class="btn btn-sm btn-outline-secondary" type="button" @click="clearFilters">
          Clear
        </button>
        <div class="ms-auto">
          <button
            class="btn btn-sm"
            :class="paused ? 'btn-success' : 'btn-outline-secondary'"
            type="button"
            @click="togglePause"
          >
            <i :class="['bi', paused ? 'bi-play-fill' : 'bi-pause-fill', 'me-1']"></i>
            {{ paused ? 'Resume' : 'Pause' }}
          </button>
        </div>
      </div>

      <div v-for="warning in warnings" :key="warning" class="alert alert-warning py-2 small" role="alert">
        {{ warning }}
      </div>

      <figure v-if="sparkBars.length" class="activity-sparkline mb-3" aria-hidden="true">
        <figcaption class="text-muted small mb-1">Events over time (red = errors)</figcaption>
        <svg viewBox="0 0 100 36" preserveAspectRatio="none" class="w-100 activity-sparkline-svg">
          <g v-for="bar in sparkBars" :key="bar.key">
            <rect :x="bar.x" :y="bar.y" :width="bar.width" :height="bar.height" class="activity-spark-bar">
              <title>{{ bar.count }} events, {{ bar.errors }} errors</title>
            </rect>
            <rect
              v-if="bar.errors"
              :x="bar.x"
              :y="36 - bar.errorHeight"
              :width="bar.width"
              :height="bar.errorHeight"
              class="activity-spark-error"
            />
          </g>
        </svg>
      </figure>

      <div class="table-responsive">
        <table class="table table-sm align-middle activity-table">
          <colgroup>
            <col class="activity-col-time" />
            <col class="activity-col-type" />
            <col class="activity-col-severity" />
            <col class="activity-col-summary" />
            <col class="activity-col-duration" />
            <col class="activity-col-actions" />
          </colgroup>
          <thead>
            <tr>
              <th class="text-nowrap">Time</th>
              <th>Type</th>
              <th>Severity</th>
              <th>Activity</th>
              <th class="text-end text-nowrap">Duration</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="entry in visibleEntries"
              :key="entry.id"
              :class="[rowClass(entry), entry.profileable ? 'activity-row-clickable' : '']"
              :tabindex="entry.profileable ? 0 : undefined"
              @click="onRowClick(entry)"
              @keydown.enter="onRowClick(entry)"
            >
              <td class="text-nowrap small">{{ formatClockTime(entry.timestamp) }}</td>
              <td class="text-nowrap"><i :class="['bi', typeIcon(entry.type), 'me-1']"></i>{{ entry.type }}</td>
              <td>
                <span :class="['badge', severityBadgeClass(entry.severity)]">{{ entry.severity }}</span>
              </td>
              <td class="activity-summary-cell">
                <span>{{ entry.summary }}</span>
                <span v-if="entry.count > 1" class="badge rounded-pill text-bg-light ms-2">×{{ entry.count }}</span>
                <span v-if="entry.detail" class="d-block text-muted small">{{ entry.detail }}</span>
              </td>
              <td class="text-end text-nowrap small">{{ formatDurationMs(entry.durationMs) }}</td>
              <td class="text-end text-nowrap" @click.stop>
                <router-link
                  v-if="entryLink(entry)"
                  :to="{path: entryLink(entry).path, query: entryLink(entry).query}"
                  class="btn btn-outline-secondary btn-sm rounded-pill me-1"
                  :title="entryLink(entry).label"
                >
                  <i class="bi bi-box-arrow-up-right"></i>
                </router-link>
                <button
                  v-if="entry.profileable"
                  class="btn btn-outline-primary btn-sm rounded-pill"
                  type="button"
                  @click="openProfile(entry)"
                >
                  <i class="bi bi-search me-1"></i>Profile
                </button>
              </td>
            </tr>
            <tr v-if="!visibleEntries.length">
              <td colspan="6" class="text-center text-muted py-4">No activity matches the current filters.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Per-request profiler drawer -->
    <div v-if="profileRequestId" class="activity-drawer-backdrop" @click.self="closeProfile">
      <aside
        ref="drawerEl"
        class="activity-drawer card shadow"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        aria-label="Request profile"
      >
        <div class="card-header d-flex align-items-center justify-content-between">
          <h2 class="h6 mb-0">Request profile</h2>
          <div class="d-flex align-items-center gap-2">
            <button
              v-if="profile && profile.available"
              class="btn btn-sm btn-outline-secondary"
              type="button"
              @click="copyProfile"
            >
              <i class="bi bi-clipboard me-1"></i>{{ copiedKey === 'profile' ? 'Copied' : 'Copy profile' }}
            </button>
            <button class="btn-close" type="button" aria-label="Close" @click="closeProfile"></button>
          </div>
        </div>
        <div class="card-body activity-drawer-body">
          <div v-if="profileLoading" class="text-muted">Loading…</div>
          <div v-else-if="profileError" class="alert alert-danger">{{ profileError }}</div>
          <div v-else-if="profile && !profile.available" class="alert alert-warning">
            {{ profile.unavailableReason }}
          </div>
          <div v-else-if="profile">
            <section class="mb-3">
              <h3 class="h6">Request</h3>
              <dl class="row small mb-0">
                <dt class="col-4">Method · Path</dt>
                <dd class="col-8">
                  <code>{{ profile.request.method }} {{ profile.request.path }}</code>
                </dd>
                <dt class="col-4">Status</dt>
                <dd class="col-8">{{ profile.request.status }}</dd>
                <dt class="col-4">Duration</dt>
                <dd class="col-8">{{ formatDurationMs(profile.request.durationMs) }}</dd>
                <template v-if="profile.request.principal">
                  <dt class="col-4">Principal</dt>
                  <dd class="col-8">{{ profile.request.principal }}</dd>
                </template>
                <template v-if="profile.request.traceId">
                  <dt class="col-4">Trace id</dt>
                  <dd class="col-8">
                    <code>{{ profile.request.traceId }}</code>
                  </dd>
                </template>
              </dl>
            </section>

            <section v-if="profile.timing" class="mb-3">
              <h3 class="h6">Timing</h3>
              <p class="small mb-1">{{ timingSummary }}</p>
            </section>

            <section class="mb-3">
              <h3 class="h6">
                SQL
                <span
                  v-if="profile.sqlCorrelationApproximate"
                  class="badge text-bg-secondary ms-1"
                  title="Correlated by time window only (no trace id or uniquely identifiable serving thread)"
                >
                  approximate
                </span>
                <span
                  v-else-if="profile.sql && profile.sql.length"
                  class="badge text-bg-success ms-1"
                  title="Correlated exactly by trace id or the request's serving thread"
                >
                  exact
                </span>
              </h3>
              <div v-for="group in profile.sqlGroups" :key="group.sql" class="small mb-1">
                <span v-if="group.potentialNPlusOne" class="badge text-bg-danger me-1">
                  N+1 · {{ group.executions }} identical
                </span>
                <span v-else class="badge text-bg-light me-1">×{{ group.executions }}</span>
                <code>{{ group.sql }}</code>
              </div>
              <p v-if="!profile.sql.length" class="text-muted small mb-0">No SQL correlated to this request.</p>
            </section>

            <section v-if="profile.exceptions.length" class="mb-3">
              <h3 class="h6">Exceptions</h3>
              <div v-for="(ex, index) in profile.exceptions" :key="index" class="small mb-1">
                <code>{{ ex.exceptionClassName }}</code>
                <span v-if="ex.message" class="text-muted">: {{ ex.message }}</span>
                <span v-if="ex.location" class="d-block text-muted">{{ ex.location }}</span>
              </div>
            </section>

            <section v-if="profile.security && profile.security.length" class="mb-3">
              <h3 class="h6">Security events</h3>
              <div v-for="(event, index) in profile.security" :key="index" class="small mb-1">
                <code>{{ event.type }}</code>
                <span v-if="event.principal" class="text-muted"> · {{ event.principal }}</span>
                <span
                  v-if="event.principalMatched"
                  class="badge text-bg-light ms-1"
                  title="Principal matches the request"
                >
                  principal
                </span>
              </div>
            </section>

            <section v-if="profile.trace && profile.trace.spans.length" class="mb-3">
              <h3 class="h6">Trace waterfall</h3>
              <ul class="list-unstyled small mb-0">
                <li v-for="(span, index) in profile.trace.spans" :key="index">
                  <code>{{ span.name }}</code>
                </li>
              </ul>
            </section>

            <section v-if="profile.notes.length">
              <h3 class="h6">Notes</h3>
              <ul class="small text-muted mb-0">
                <li v-for="(note, index) in profile.notes" :key="index">{{ note }}</li>
              </ul>
            </section>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.activity-drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  justify-content: flex-end;
  z-index: 1050;
}

.activity-drawer {
  width: min(560px, 100%);
  height: 100%;
  border-radius: 0;
  overflow: hidden;
}

.activity-drawer-body {
  overflow-y: auto;
}

.activity-row-clickable {
  cursor: pointer;
}

.activity-kpi-link {
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.activity-kpi-link:hover,
.activity-kpi-link:focus-visible {
  border-color: var(--bs-primary);
  box-shadow: 0 0 0 0.15rem rgba(var(--bs-primary-rgb), 0.25);
}

.activity-table {
  table-layout: fixed;
  width: 100%;
  min-width: 40rem;
}

.activity-col-time {
  width: 5.5rem;
}

.activity-col-type {
  width: 7rem;
}

.activity-col-severity {
  width: 5rem;
}

.activity-col-summary {
  width: auto;
}

.activity-col-duration {
  width: 5.5rem;
}

.activity-col-actions {
  width: 8rem;
}

.activity-summary-cell {
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.activity-text-filter {
  min-width: 16rem;
  flex: 1 1 16rem;
}

.activity-sparkline-svg {
  height: 36px;
  display: block;
}

.activity-spark-bar {
  fill: var(--bs-primary, #0d6efd);
  opacity: 0.55;
}

.activity-spark-error {
  fill: var(--bs-danger, #dc3545);
}
</style>
