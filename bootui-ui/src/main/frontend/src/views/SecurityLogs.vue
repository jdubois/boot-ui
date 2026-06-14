<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const report = ref(null)
const error = ref(null)
const principal = ref('')
const type = ref('')
const timeWindow = ref('24h')
const offset = ref(0)
const limit = ref(50)
const lastFetched = ref(null)

const timeWindows = [
  {value: '15m', label: 'Last 15 minutes', millis: 15 * 60 * 1000},
  {value: '1h', label: 'Last hour', millis: 60 * 60 * 1000},
  {value: '6h', label: 'Last 6 hours', millis: 6 * 60 * 60 * 1000},
  {value: '24h', label: 'Last 24 hours', millis: 24 * 60 * 60 * 1000},
  {value: 'all', label: 'All retained events', millis: null}
]

const events = computed(() => report.value?.events ?? [])
const page = computed(() => report.value?.page ?? null)

function selectedAfter() {
  const selected = timeWindows.find((window) => window.value === timeWindow.value)
  if (!selected || selected.millis === null) return null
  return new Date(Date.now() - selected.millis).toISOString()
}

async function fetchLogs(reset = false) {
  if (reset) offset.value = 0
  error.value = null
  try {
    const params = new URLSearchParams({offset: String(offset.value), limit: String(limit.value)})
    if (principal.value.trim()) params.set('principal', principal.value.trim())
    if (type.value.trim()) params.set('type', type.value.trim())
    const after = selectedAfter()
    if (after) params.set('after', after)
    const res = await apiFetch('api/security-logs?' + params)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Security Logs')
  }
}

const {autoRefresh, loading, initialLoading, load} = useEventStreamRefresh('api/security-logs/stream', fetchLogs)

function previousPage() {
  if (!page.value) return
  offset.value = Math.max(0, page.value.offset - page.value.limit)
  load()
}

function nextPage() {
  if (!page.value || !page.value.hasMore) return
  offset.value = page.value.offset + page.value.limit
  load()
}

function formatTime(timestamp) {
  if (!timestamp) return '—'
  return new Date(timestamp).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

function formatValue(entry) {
  if (entry.value === null || entry.value === undefined) return 'hidden'
  return entry.value
}

function typeBadgeClass(typeName) {
  if (!typeName) return 'bg-secondary'
  if (typeName.includes('FAILURE') || typeName.includes('DENIED')) return 'bg-danger'
  if (typeName.includes('SUCCESS')) return 'bg-success'
  if (typeName.includes('AUTHORIZATION')) return 'bg-warning text-dark'
  return 'bg-primary'
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-shield-lock"
      title="Security Logs"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <p class="text-muted small">
      Read-only Spring Boot audit events from the application <code>AuditEventRepository</code>.
      <template v-if="report">
        This application returns up to <strong>{{ report.maxLogs }}</strong> recent audit events per response; change
        <code>bootui.security-logs.max-logs</code> in Spring Boot configuration to adjust that cap.
      </template>
      <template v-else>
        The response cap comes from <code>bootui.security-logs.max-logs</code> and will appear after the first refresh.
      </template>
      Sensitive event data is masked before it reaches the browser.
    </p>

    <PanelSkeleton v-if="initialLoading" />

    <template v-else-if="report && !report.auditEventsPresent">
      <div class="alert alert-secondary">
        {{ report.unavailableReason }}. Define an <code>AuditEventRepository</code> bean to record Spring Security audit
        events for this panel.
      </div>
    </template>

    <template v-else-if="report">
      <div class="row g-2 align-items-end mb-3">
        <div class="col-md-3">
          <label class="form-label small text-muted" for="security-log-principal">Principal</label>
          <input
            id="security-log-principal"
            v-model="principal"
            class="form-control form-control-sm"
            placeholder="Any principal"
            @keyup.enter="load(true)"
          />
        </div>
        <div class="col-md-3">
          <label class="form-label small text-muted" for="security-log-type">Event type</label>
          <input
            id="security-log-type"
            v-model="type"
            class="form-control form-control-sm"
            placeholder="AUTHENTICATION_SUCCESS"
            @keyup.enter="load(true)"
          />
        </div>
        <div class="col-md-3">
          <label class="form-label small text-muted" for="security-log-window">Time window</label>
          <select id="security-log-window" v-model="timeWindow" class="form-select form-select-sm">
            <option v-for="window in timeWindows" :key="window.value" :value="window.value">{{ window.label }}</option>
          </select>
        </div>
        <div class="col-md-auto">
          <SpinnerButton
            :loading="loading"
            :disabled="loading"
            class="btn btn-sm btn-primary"
            label="Apply"
            @click="load(true)"
          />
        </div>
      </div>

      <div class="d-flex flex-wrap gap-2 mb-3">
        <span class="badge bg-light text-dark border">Maximum retained: {{ report.maxLogs }}</span>
        <span
          v-for="summary in report.typeSummaries"
          :key="summary.type"
          :class="typeBadgeClass(summary.type)"
          class="badge"
        >
          {{ summary.type }} {{ summary.count }}
        </span>
      </div>

      <div v-if="events.length === 0" class="alert alert-secondary small mb-0">
        No audit events match the current filters.
      </div>

      <template v-else>
        <div class="table-responsive">
          <table class="table table-sm table-hover small align-middle">
            <thead>
              <tr>
                <th style="width: 13rem">Time</th>
                <th style="width: 10rem">Principal</th>
                <th style="width: 14rem">Type</th>
                <th>Event data</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in events" :key="`${event.timestamp}-${event.type}-${event.principal}`">
                <td class="text-muted">{{ formatTime(event.timestamp) }}</td>
                <td>
                  <code v-if="event.principal">{{ event.principal }}</code>
                  <span v-else class="text-muted">hidden</span>
                </td>
                <td>
                  <span :class="typeBadgeClass(event.type)" class="badge">{{ event.type }}</span>
                </td>
                <td>
                  <span v-if="event.data.length === 0" class="text-muted">No event data</span>
                  <span
                    v-for="entry in event.data"
                    :key="entry.name"
                    class="badge bg-light text-dark border me-1 mb-1 text-wrap"
                    :title="entry.truncated ? 'Value was truncated' : undefined"
                  >
                    {{ entry.name }}={{ formatValue(entry) }}
                    <i v-if="entry.masked" class="bi bi-shield-lock ms-1 text-muted" title="Masked"></i>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="page" class="d-flex justify-content-between align-items-center small text-muted">
          <span>
            Showing {{ page.returned }} of {{ page.matched }} matching events
            <span v-if="page.total !== page.matched">from {{ page.total }} retained</span>
          </span>
          <div class="btn-group btn-group-sm">
            <button class="btn btn-outline-secondary" :disabled="page.offset === 0 || loading" @click="previousPage">
              Previous
            </button>
            <button class="btn btn-outline-secondary" :disabled="!page.hasMore || loading" @click="nextPage">
              Next
            </button>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
