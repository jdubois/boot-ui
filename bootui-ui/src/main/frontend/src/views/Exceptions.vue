<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatClockTime, formatRelative, formatNumber, shortName} from '../utils/format.js'
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
const detail = ref(null)
const error = ref(null)
const {message: banner, flash, show, clear} = useFlashMessage(4000)
const filter = ref('')
const sourceFilter = ref('all')
const appOnly = ref(false)
const selectedId = ref(null)
const detailLoading = ref(false)
const busy = ref(false)
const lastFetched = ref(null)

async function fetchExceptions() {
  error.value = null
  try {
    const res = await apiFetch('api/exceptions')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load exceptions')
  }
}

async function openException(id) {
  selectedId.value = id
  detail.value = null
  detailLoading.value = true
  try {
    const res = await apiFetch('api/exceptions/' + encodeURIComponent(id))
    if (!res.ok) throw new Error('HTTP ' + res.status)
    detail.value = await res.json()
  } catch (e) {
    show(formatLoadError(e, 'Could not load exception detail'), 'danger')
  } finally {
    detailLoading.value = false
  }
}

function toggleException(id) {
  if (id === selectedId.value) {
    closeDrawer()
    return
  }
  openException(id)
}

function closeDrawer() {
  selectedId.value = null
  detail.value = null
}

async function clearAll() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (!confirm('Clear all captured exceptions? This cannot be undone.')) return
  busy.value = true
  try {
    const res = await apiFetch('api/exceptions', {method: 'DELETE'})
    if (!res.ok && res.status !== 204) throw new Error('HTTP ' + res.status)
    closeDrawer()
    await load()
    flash('Cleared captured exceptions.', 'success')
  } catch (e) {
    show(formatLoadError(e, 'Could not clear exceptions'), 'danger')
  } finally {
    busy.value = false
  }
}

const filteredGroups = computed(() => {
  if (!report.value || !report.value.groups) return []
  const text = filter.value.trim().toLowerCase()
  return report.value.groups.filter((g) => {
    if (appOnly.value && !g.applicationException) return false
    if (sourceFilter.value !== 'all' && g.lastSource !== sourceFilter.value) return false
    if (!text) return true
    return (
      (g.exceptionClassName || '').toLowerCase().includes(text) ||
      (g.message || '').toLowerCase().includes(text) ||
      (g.location || '').toLowerCase().includes(text)
    )
  })
})

const totalText = computed(() => {
  if (!report.value) return null
  const groupCount = report.value.groups ? report.value.groups.length : 0
  const total = report.value.totalExceptions || 0
  return `${formatNumber(groupCount)} group${groupCount === 1 ? '' : 's'} · ${formatNumber(total)} occurrence${
    total === 1 ? '' : 's'
  }`
})

function sourceBadgeClass(source) {
  return source === 'web' ? 'text-bg-primary' : 'text-bg-secondary'
}

function frameLabel(frame) {
  const file = frame.fileName
  const position = file ? (frame.lineNumber != null ? `${file}:${frame.lineNumber}` : file) : 'Unknown Source'
  return `${frame.declaringClass}.${frame.methodName}(${position})`
}

function requestLabel(item) {
  if (!item.lastRequestPath && !item.requestPath) return null
  const method = item.lastRequestMethod || item.requestMethod
  const path = item.lastRequestPath || item.requestPath
  return method ? `${method} ${path}` : path
}

const {autoRefresh, loading, load} = useAutoRefresh(fetchExceptions)
</script>

<template>
  <div>
    <PanelHeader
      v-model:auto-refresh="autoRefresh"
      icon="bi-exclamation-octagon"
      title="Exceptions"
      :subtitle="totalText"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      @refresh="load"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy"
          :disabled="!report || readOnly || !report.groups || report.groups.length === 0 || busy"
          class="btn btn-sm btn-outline-danger"
          icon="bi-trash"
          label="Clear"
          @click="clearAll"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="loading && !report" />

    <template v-else-if="report">
      <div v-if="!report.available" class="alert alert-info small">
        Exception capture is disabled.
        <span v-if="report.unavailableReason">{{ report.unavailableReason }}.</span>
        Enable the Exceptions panel (<code>bootui.panels.exceptions.enabled=true</code>) to capture thrown exceptions.
      </div>

      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Clearing exceptions is read-only.</ReadOnlyNotice>

      <template v-if="report.available">
        <div v-if="!report.groups || report.groups.length === 0" class="alert alert-secondary">
          No exceptions captured yet. BootUI records exceptions thrown by web request handlers and anything logged with
          a throwable. Exercise your application to populate this panel.
        </div>

        <template v-else>
          <div class="row g-2 mb-3 align-items-end">
            <div class="col-lg-6">
              <input
                v-model="filter"
                class="form-control form-control-sm"
                placeholder="Filter by exception type, message, or location…"
              />
            </div>
            <div class="col-6 col-lg-3">
              <select v-model="sourceFilter" class="form-select form-select-sm" aria-label="Source filter">
                <option value="all">All sources</option>
                <option value="web">Web requests</option>
                <option value="log">Logged</option>
              </select>
            </div>
            <div class="col-6 col-lg-3">
              <div class="form-check">
                <input id="exceptions-app-only" v-model="appOnly" class="form-check-input" type="checkbox" />
                <label class="form-check-label small" for="exceptions-app-only">Application only</label>
              </div>
            </div>
          </div>

          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Last seen</th>
                  <th>Exception</th>
                  <th>Source</th>
                  <th class="text-end">Count</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <template v-for="g in filteredGroups" :key="g.id">
                  <tr :class="{'table-active': g.id === selectedId}">
                    <td class="text-muted small text-nowrap" :title="formatClockTime(g.lastSeen)">
                      {{ formatRelative(g.lastSeen) }}
                    </td>
                    <td>
                      <div>
                        <span class="fw-semibold">{{ shortName(g.exceptionClassName) }}</span>
                        <span
                          v-if="g.applicationException"
                          class="badge text-bg-warning ms-1"
                          title="Originated in application code"
                          >app</span
                        >
                      </div>
                      <div v-if="g.message" class="text-body-secondary small text-truncate exception-message">
                        {{ g.message }}
                      </div>
                      <div v-if="g.location" class="text-muted small">
                        <i class="bi bi-geo-alt me-1"></i><code>{{ g.location }}</code>
                      </div>
                      <div v-if="requestLabel(g)" class="text-muted small">
                        <i class="bi bi-globe me-1"></i>{{ requestLabel(g) }}
                      </div>
                    </td>
                    <td>
                      <span :class="sourceBadgeClass(g.lastSource)" class="badge">{{ g.lastSource || '—' }}</span>
                    </td>
                    <td class="text-end">
                      <span class="badge text-bg-dark">{{ formatNumber(g.count) }}</span>
                    </td>
                    <td class="text-end">
                      <button
                        :aria-expanded="g.id === selectedId"
                        class="btn btn-sm btn-outline-primary"
                        @click="toggleException(g.id)"
                      >
                        {{ g.id === selectedId ? 'Close' : 'Open' }}
                      </button>
                    </td>
                  </tr>
                  <tr v-if="g.id === selectedId" class="exception-detail-row">
                    <td class="p-0" colspan="5">
                      <div class="exception-drawer card m-2">
                        <div class="card-header d-flex justify-content-between align-items-center">
                          <div class="text-truncate">
                            <i class="bi bi-exclamation-octagon me-2"></i>
                            <span class="fw-semibold">{{ g.exceptionClassName }}</span>
                          </div>
                          <button class="btn btn-sm btn-outline-secondary" @click="closeDrawer">Close</button>
                        </div>
                        <div class="card-body">
                          <div v-if="detailLoading" class="text-muted small">Loading detail…</div>
                          <template v-else-if="detail">
                            <div v-if="detail.group && detail.group.message" class="mb-3">
                              <div class="text-muted small text-uppercase">Message</div>
                              <div class="exception-message-full">{{ detail.group.message }}</div>
                            </div>

                            <div class="mb-3">
                              <div class="text-muted small text-uppercase mb-1">Stack trace</div>
                              <pre class="stack-pane rounded border p-2 mb-0"><code><span
                                v-for="(f, i) in detail.frames"
                                :key="i"
                                class="d-block"
                                :class="{'stack-app': f.applicationFrame}"
                              >at {{ frameLabel(f) }}</span><span
                                v-for="(c, ci) in detail.causes"
                                :key="'c' + ci"
                              ><span class="d-block stack-cause">Caused by: {{ c.exceptionClassName }}<template
                                v-if="c.message">: {{ c.message }}</template></span><span
                                v-for="(f, fi) in c.frames"
                                :key="ci + '-' + fi"
                                class="d-block"
                                :class="{'stack-app': f.applicationFrame}"
                              >    at {{ frameLabel(f) }}</span><span
                                v-if="c.commonFrames > 0"
                                class="d-block text-muted"
                              >    ... {{ c.commonFrames }} more</span></span></code></pre>
                            </div>

                            <div v-if="detail.occurrences && detail.occurrences.length">
                              <div class="text-muted small text-uppercase mb-1">
                                Recent occurrences ({{ detail.occurrences.length }})
                              </div>
                              <div class="table-responsive">
                                <table class="table table-sm align-middle mb-0">
                                  <thead>
                                    <tr>
                                      <th>Time</th>
                                      <th>Source</th>
                                      <th>Thread</th>
                                      <th>Request</th>
                                      <th>Handler</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    <tr v-for="(o, oi) in detail.occurrences" :key="oi">
                                      <td class="text-nowrap small">{{ formatClockTime(o.timestamp) }}</td>
                                      <td>
                                        <span :class="sourceBadgeClass(o.source)" class="badge">{{
                                          o.source || '—'
                                        }}</span>
                                      </td>
                                      <td class="small">{{ o.thread || '—' }}</td>
                                      <td class="small">{{ requestLabel(o) || '—' }}</td>
                                      <td class="small">{{ o.handler || '—' }}</td>
                                    </tr>
                                  </tbody>
                                </table>
                              </div>
                            </div>
                          </template>
                          <div v-else class="text-muted">No detail available.</div>
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
    </template>
  </div>
</template>

<style scoped>
.exception-drawer {
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.exception-message {
  max-width: 40rem;
}

.exception-message-full {
  white-space: pre-wrap;
  word-break: break-word;
}

.stack-pane {
  background: #111827;
  color: #e2e8f0;
  font-family: var(--bs-font-monospace);
  font-size: 0.8rem;
  max-height: 22rem;
  overflow: auto;
  white-space: pre;
}

.stack-app {
  color: #fcd34d;
}

.stack-cause {
  color: #f87171;
}

code {
  overflow-wrap: anywhere;
}
</style>
