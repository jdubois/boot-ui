<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatNumber, shortName} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const {message: banner, flash, clear} = useFlashMessage()
const busy = ref(null)
const expanded = ref(new Set())
const lastFetched = ref(null)

async function fetchSessions() {
  error.value = null
  try {
    const res = await apiFetch('api/http-sessions')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load HTTP sessions')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchSessions)

const sessions = computed(() => report.value?.sessions || [])
const available = computed(() => report.value?.available !== false)
const unavailableReason = computed(() => report.value?.unavailableReason || 'HTTP Sessions are unavailable.')
const actionEnabled = computed(() => report.value?.actionEnabled !== false)
const actionsDisabled = computed(() => readOnly.value || !actionEnabled.value || Boolean(busy.value))
const valueExposure = computed(() => report.value?.valueExposure || 'MASKED')
const subtitle = computed(() => {
  if (!report.value) return 'Inspect active Tomcat HTTP sessions'
  if (!available.value) return unavailableReason.value
  return `${formatNumber(report.value.returnedSessions)} of ${formatNumber(report.value.totalSessions)} sessions · limit ${formatNumber(
    report.value.limit
  )}`
})

function toggleDetails(sessionKey) {
  const next = new Set(expanded.value)
  if (next.has(sessionKey)) {
    next.delete(sessionKey)
  } else {
    next.add(sessionKey)
  }
  expanded.value = next
}

function isExpanded(sessionKey) {
  return expanded.value.has(sessionKey)
}

function formatTimestamp(timestamp) {
  if (!timestamp) return '—'
  return new Date(timestamp).toLocaleString()
}

function formatSeconds(seconds) {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

function maxInactiveText(seconds) {
  if (seconds < 0) return 'Never expires'
  return formatSeconds(seconds)
}

function attributeValue(attribute) {
  if (attribute.value !== null && attribute.value !== undefined) return attribute.value
  if (attribute.masked) return '******'
  return 'Hidden'
}

async function clearSession(session) {
  if (actionsDisabled.value) {
    showReadOnlyMessage()
    return
  }
  if (
    !confirm(
      `Clear all attributes from HTTP session "${session.id}"? The session remains active, but stored user state may be lost.`
    )
  )
    return
  await mutateSession(session, 'clear', 'Clear attributes')
}

async function destroySession(session) {
  if (actionsDisabled.value) {
    showReadOnlyMessage()
    return
  }
  if (
    !confirm(
      `Destroy HTTP session "${session.id}"? This invalidates the session and may sign out the associated browser.`
    )
  )
    return
  await mutateSession(session, 'invalidate', 'Destroy session')
}

async function mutateSession(session, action, label) {
  busy.value = `${session.sessionKey}:${action}`
  clear()
  try {
    const res = await apiFetch(`api/http-sessions/${encodeURIComponent(session.sessionKey)}/${action}`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({confirm: true})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `Could not ${label.toLowerCase()} (HTTP ${res.status})`, 'warning')
      return
    }
    flash(result.message || `${label} complete.`, 'success')
    await load()
  } catch (e) {
    flash(formatLoadError(e, `Could not ${label.toLowerCase()}`), 'danger')
  } finally {
    busy.value = null
  }
}

function actionBusy(session, action) {
  return busy.value === `${session.sessionKey}:${action}`
}

function showReadOnlyMessage() {
  flash(readOnly.value ? readOnlyReason.value : 'HTTP session actions are disabled.', 'warning')
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-cookie"
      title="HTTP Sessions"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="initialLoading" />

    <template v-else-if="report">
      <div v-if="!available" class="alert alert-secondary" role="alert">
        <i class="bi bi-info-circle me-2"></i>{{ unavailableReason }}
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">HTTP session actions are read-only.</ReadOnlyNotice>

        <div v-if="report.limited" class="alert alert-info small">
          Showing the first {{ formatNumber(report.limit) }} active sessions. Increase
          <code>bootui.http-sessions.max-sessions</code> in a trusted local profile to return more.
        </div>

        <div v-if="sessions.length && valueExposure === 'FULL'" class="alert alert-warning small">
          <i class="bi bi-unlock me-1"></i>
          Full value exposure is enabled. Session IDs and attribute values are visible in this local browser.
        </div>

        <div v-else-if="sessions.length" class="alert alert-info small">
          Session IDs and attribute values are masked by default because they can authenticate users or contain
          principals, CSRF tokens, and application state. Set <code>bootui.expose-values=FULL</code> only in a trusted
          local profile to reveal them.
        </div>

        <div v-if="!sessions.length" class="alert alert-secondary">
          No active Tomcat HTTP sessions were found. Visit an endpoint that creates a session and refresh this panel.
        </div>

        <div v-else class="table-responsive">
          <table class="table table-sm align-middle http-sessions-table">
            <thead>
              <tr>
                <th>Session</th>
                <th>Created</th>
                <th>Last accessed</th>
                <th>Idle</th>
                <th>Max inactive</th>
                <th>Attributes</th>
                <th class="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="session in sessions" :key="session.sessionKey">
                <tr>
                  <td>
                    <code>{{ session.id || 'Hidden' }}</code>
                    <span v-if="session.idMasked" class="badge text-bg-secondary ms-1">masked</span>
                    <span v-if="session.current" class="badge text-bg-info ms-1">current</span>
                  </td>
                  <td class="text-nowrap small">{{ formatTimestamp(session.creationTime) }}</td>
                  <td class="text-nowrap small">{{ formatTimestamp(session.lastAccessedTime) }}</td>
                  <td>{{ formatSeconds(session.idleSeconds) }}</td>
                  <td>{{ maxInactiveText(session.maxInactiveIntervalSeconds) }}</td>
                  <td>
                    <span class="badge text-bg-light border text-dark">{{ session.attributeCount }}</span>
                  </td>
                  <td class="text-end">
                    <div class="btn-group btn-group-sm">
                      <button
                        :aria-expanded="isExpanded(session.sessionKey)"
                        class="btn btn-outline-secondary http-sessions-detail-toggle"
                        type="button"
                        @click="toggleDetails(session.sessionKey)"
                      >
                        <i
                          :class="['bi', isExpanded(session.sessionKey) ? 'bi-chevron-up' : 'bi-card-text', 'me-1']"
                        ></i>
                        {{ isExpanded(session.sessionKey) ? 'Hide' : 'Details' }}
                      </button>
                      <SpinnerButton
                        :loading="actionBusy(session, 'clear')"
                        :disabled="actionsDisabled"
                        class="btn btn-outline-warning"
                        label="Clear"
                        @click="clearSession(session)"
                      />
                      <SpinnerButton
                        :loading="actionBusy(session, 'invalidate')"
                        :disabled="actionsDisabled"
                        class="btn btn-outline-danger"
                        label="Destroy"
                        @click="destroySession(session)"
                      />
                    </div>
                  </td>
                </tr>
                <tr v-if="isExpanded(session.sessionKey)" :key="`${session.sessionKey}-attributes`">
                  <td colspan="7">
                    <div class="http-sessions-detail">
                      <h3 class="h6">Session attributes</h3>
                      <div v-if="!session.attributes.length" class="text-muted small">
                        This session has no attributes.
                      </div>
                      <div v-else class="table-responsive">
                        <table class="table table-sm mb-0">
                          <thead>
                            <tr>
                              <th>Name</th>
                              <th>Type</th>
                              <th>Value</th>
                            </tr>
                          </thead>
                          <tbody>
                            <tr
                              v-for="attribute in session.attributes"
                              :key="`${session.sessionKey}-${attribute.name}`"
                            >
                              <td>
                                <code>{{ attribute.name }}</code>
                              </td>
                              <td>
                                <code>{{ shortName(attribute.type) }}</code>
                              </td>
                              <td>
                                <code :class="{'text-muted': attribute.value == null}">
                                  {{ attributeValue(attribute) }}
                                </code>
                                <span v-if="attribute.masked" class="badge text-bg-secondary ms-1">masked</span>
                                <span v-if="attribute.truncated" class="badge text-bg-warning ms-1">truncated</span>
                              </td>
                            </tr>
                          </tbody>
                        </table>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.http-sessions-table {
  min-width: 980px;
}

.http-sessions-detail {
  background: var(--bs-tertiary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: 0.5rem;
  padding: 1rem;
}

.http-sessions-detail-toggle {
  white-space: nowrap;
}
</style>
