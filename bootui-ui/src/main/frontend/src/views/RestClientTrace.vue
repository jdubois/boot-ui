<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, clear: clearBanner} = useFlashMessage()
const filter = ref('')
const methodFilter = ref('')
const slowOnly = ref(false)
const busy = ref(null)
const lastFetched = ref(null)
const expanded = ref(new Set())

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/rest-client-trace')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load REST client trace')
  }
}

const {autoRefresh, loading, initialLoading, load} = useEventStreamRefresh('api/rest-client-trace/stream', fetchReport)

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const stats = computed(() => report.value?.stats ?? null)
const entries = computed(() => report.value?.entries ?? [])

const methods = computed(() => {
  const seen = new Set()
  for (const entry of entries.value) {
    if (entry.method) seen.add(entry.method)
  }
  return Array.from(seen).sort()
})

const filteredEntries = computed(() => {
  const value = filter.value.trim().toLowerCase()
  const method = methodFilter.value
  return entries.value.filter((entry) => {
    if (method && entry.method !== method) return false
    if (slowOnly.value && !entry.slow) return false
    if (!value) return true
    return [entry.uri, entry.host, entry.path, entry.method, entry.clientType, entry.thread, entry.errorMessage]
      .join(' ')
      .toLowerCase()
      .includes(value)
  })
})

function methodClass(method) {
  return (
    {
      GET: 'text-bg-primary',
      POST: 'text-bg-success',
      PUT: 'text-bg-warning',
      PATCH: 'text-bg-warning',
      DELETE: 'text-bg-danger'
    }[method] || 'text-bg-secondary'
  )
}

function statusClass(entry) {
  if (!entry.success) return 'text-bg-danger'
  const status = entry.status
  if (status == null) return 'text-bg-secondary'
  if (status >= 500) return 'text-bg-danger'
  if (status >= 400) return 'text-bg-warning'
  return 'text-bg-success'
}

function toggleRow(entry) {
  const next = new Set(expanded.value)
  if (next.has(entry.id)) next.delete(entry.id)
  else next.add(entry.id)
  expanded.value = next
}

function isExpanded(entry) {
  return expanded.value.has(entry.id)
}

const subtitle = computed(() => {
  if (!report.value || !report.value.available) return null
  const s = stats.value
  const parts = [
    `${formatNumber(s.totalCalls)} retained`,
    `${formatNumber(report.value.totalCaptured)} captured since startup`
  ]
  if (s.slowCalls) parts.push(`${formatNumber(s.slowCalls)} slow`)
  if (s.failedCalls) parts.push(`${formatNumber(s.failedCalls)} failed`)
  if (s.errorStatusCalls) parts.push(`${formatNumber(s.errorStatusCalls)} error responses`)
  parts.push(report.value.capturing ? 'recording' : 'paused')
  return parts.join(' · ')
})

async function applyAction(action, options) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (options.confirm && !(await confirm(options.confirm))) return
  busy.value = action
  clearBanner()
  try {
    const res = await apiFetch(options.url, options.init)
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    report.value = result
    lastFetched.value = Date.now()
    if (options.onSuccess) options.onSuccess(result)
    flash(options.success(result), 'success')
  } catch (e) {
    flash(formatLoadError(e, options.failure), 'danger')
  } finally {
    busy.value = null
  }
}

function toggleRecording() {
  const next = !report.value?.capturing
  applyAction('recording', {
    url: 'api/rest-client-trace/recording',
    init: {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({enabled: next})},
    success: () => (next ? 'Recording resumed.' : 'Recording paused; existing calls are kept.'),
    failure: 'Could not change recording state'
  })
}

function clearTrace() {
  applyAction('clear', {
    url: 'api/rest-client-trace/clear',
    init: {method: 'POST'},
    confirm: {
      title: 'Clear REST client trace?',
      message: 'Clear all captured outbound HTTP calls from the in-memory trace buffer.',
      confirmLabel: 'Clear',
      danger: true
    },
    onSuccess: () => (expanded.value = new Set()),
    success: () => 'REST client trace cleared.',
    failure: 'Could not clear REST client trace'
  })
}

// useEventStreamRefresh automatically loads on mount unless configured otherwise
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-globe2"
      title="REST Client Trace"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy === 'recording'"
          :disabled="!report || !report.available || readOnly || busy"
          class="ms-2"
          :class="report && report.capturing ? 'btn btn-sm btn-outline-warning' : 'btn btn-sm btn-outline-success'"
          :icon="report && report.capturing ? 'bi-pause-fill' : 'bi-record-fill'"
          :label="report && report.capturing ? 'Pause' : 'Resume'"
          @click="toggleRecording"
        />
        <SpinnerButton
          :loading="busy === 'clear'"
          :disabled="!report || !report.available || readOnly || busy || !stats || stats.totalCalls === 0"
          class="btn btn-sm btn-outline-danger ms-2"
          icon="bi-trash"
          label="Clear"
          @click="clearTrace"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clearBanner" />

    <PanelSkeleton v-if="initialLoading && !report" />

    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small py-2">
        {{ warning }}
      </div>

      <div v-if="!report.available" class="alert alert-secondary">
        {{ report.unavailableReason || 'REST client tracing is not available.' }}
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Recording controls are read-only.</ReadOnlyNotice>

        <div v-if="!report.captureHeaders" class="alert alert-secondary small py-2">
          Header capture is disabled. Set <code>bootui.rest-client-trace.capture-headers=true</code> (local profiles
          only) to record request header values.
        </div>

        <section class="mb-4">
          <div class="row g-2 stat-cards">
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Retained</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(stats.totalCalls) }}</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Avg time</div>
                  <div class="fs-5 fw-semibold">{{ stats.avgDurationMillis.toFixed(1) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Slowest</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(stats.maxDurationMillis) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Slow (&ge;{{ formatNumber(report.slowCallThresholdMillis) }} ms)</div>
                  <div class="fs-5 fw-semibold" :class="{'text-warning': stats.slowCalls > 0}">
                    {{ formatNumber(stats.slowCalls) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Failed</div>
                  <div class="fs-5 fw-semibold" :class="{'text-danger': stats.failedCalls > 0}">
                    {{ formatNumber(stats.failedCalls) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Error responses</div>
                  <div class="fs-5 fw-semibold" :class="{'text-warning': stats.errorStatusCalls > 0}">
                    {{ formatNumber(stats.errorStatusCalls) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">By method</div>
                  <div class="rest-method-counts">
                    <span class="badge text-bg-primary">GET {{ formatNumber(stats.getCount) }}</span>
                    <span class="badge text-bg-success">POST {{ formatNumber(stats.postCount) }}</span>
                    <span class="badge text-bg-warning">PUT {{ formatNumber(stats.putCount) }}</span>
                    <span class="badge text-bg-danger">DEL {{ formatNumber(stats.deleteCount) }}</span>
                    <span class="badge text-bg-secondary">OTH {{ formatNumber(stats.otherCount) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section v-if="report.clientTypes.length" class="mb-3">
          <span class="text-muted small me-2">Instrumented clients:</span>
          <span
            v-for="clientType in report.clientTypes"
            :key="clientType"
            class="badge text-bg-light border text-dark me-1"
          >
            {{ clientType }}
          </span>
        </section>

        <section v-if="report.topCalls.length" class="mb-4">
          <h5 class="mb-2">
            Most frequent calls <span class="badge bg-secondary">{{ report.topCalls.length }}</span>
          </h5>
          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Method</th>
                  <th>Endpoint</th>
                  <th class="text-end">Count</th>
                  <th class="text-end">Total</th>
                  <th class="text-end">Max</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(group, index) in report.topCalls" :key="index">
                  <td>
                    <span :class="methodClass(group.method)" class="badge">{{ group.method }}</span>
                  </td>
                  <td>
                    <code class="rest-text">{{ group.host }}{{ group.path }}</code>
                    <span
                      v-if="group.chatty"
                      class="badge text-bg-danger ms-1"
                      title="This endpoint is called many times in the buffer — it may be a chatty (N+1-style) outbound-call pattern"
                    >
                      chatty
                    </span>
                    <div v-if="group.callSites && group.callSites.length" class="call-sites small text-muted">
                      <div v-for="site in group.callSites" :key="site" class="font-monospace">at {{ site }}</div>
                    </div>
                  </td>
                  <td class="text-end">{{ formatNumber(group.executions) }}</td>
                  <td class="text-end">{{ formatNumber(group.totalDurationMillis) }} ms</td>
                  <td class="text-end">{{ formatNumber(group.maxDurationMillis) }} ms</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section>
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h5 class="mb-0">
              Recent calls <span class="badge bg-secondary">{{ filteredEntries.length }}</span>
            </h5>
            <div class="d-flex flex-wrap gap-2">
              <select v-model="methodFilter" class="form-select form-select-sm rest-filter-select">
                <option value="">All methods</option>
                <option v-for="method in methods" :key="method" :value="method">{{ method }}</option>
              </select>
              <div class="form-check form-switch d-flex align-items-center">
                <input
                  id="rest-slow-only"
                  v-model="slowOnly"
                  class="form-check-input me-1"
                  type="checkbox"
                  role="switch"
                />
                <label class="form-check-label small" for="rest-slow-only">Slow only</label>
              </div>
              <input
                v-model="filter"
                class="form-control form-control-sm trace-filter"
                placeholder="Filter by URI, host, method, client, or thread…"
              />
            </div>
          </div>

          <div v-if="entries.length === 0" class="alert alert-secondary small">
            No REST client calls have been captured yet. Exercise the application's outbound HTTP calls (RestClient,
            RestTemplate, or WebClient) and refresh to see them here.
          </div>

          <div v-else-if="filteredEntries.length" class="table-responsive">
            <table class="table table-sm table-hover align-middle rest-table">
              <thead>
                <tr>
                  <th style="width: 2rem"></th>
                  <th>Time</th>
                  <th>Method</th>
                  <th class="text-end">Duration</th>
                  <th>Endpoint</th>
                  <th>Status</th>
                  <th>Client</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="entry in filteredEntries" :key="entry.id">
                  <tr class="rest-row" @click="toggleRow(entry)">
                    <td class="text-muted">
                      <i class="bi" :class="isExpanded(entry) ? 'bi-chevron-down' : 'bi-chevron-right'"></i>
                    </td>
                    <td class="text-nowrap font-monospace small">{{ formatClockTime(entry.timestamp) }}</td>
                    <td>
                      <span :class="methodClass(entry.method)" class="badge">{{ entry.method }}</span>
                    </td>
                    <td class="text-end text-nowrap" :class="{'text-warning fw-semibold': entry.slow}">
                      {{ formatNumber(entry.durationMillis) }} ms
                    </td>
                    <td>
                      <code class="rest-text">{{ entry.host }}{{ entry.path }}</code>
                    </td>
                    <td>
                      <span :class="statusClass(entry)" class="badge">{{
                        entry.success ? entry.status : 'failed'
                      }}</span>
                      <span v-if="entry.slow" class="badge text-bg-warning ms-1">slow</span>
                    </td>
                    <td class="text-nowrap small">{{ entry.clientType }}</td>
                  </tr>
                  <tr v-if="isExpanded(entry)" class="rest-detail-row">
                    <td></td>
                    <td colspan="6">
                      <dl class="row mb-0 small">
                        <dt class="col-sm-2">URI</dt>
                        <dd class="col-sm-10">
                          <pre class="rest-detail mb-1">{{ entry.uri }}</pre>
                        </dd>
                        <template v-if="entry.requestHeaders && Object.keys(entry.requestHeaders).length">
                          <dt class="col-sm-2">Headers</dt>
                          <dd class="col-sm-10">
                            <span
                              v-for="(value, name) in entry.requestHeaders"
                              :key="name"
                              class="badge text-bg-light border text-dark me-1 mb-1"
                            >
                              {{ name }}: {{ value }}
                            </span>
                          </dd>
                        </template>
                        <dt class="col-sm-2">Client</dt>
                        <dd class="col-sm-10">{{ entry.clientType }}</dd>
                        <dt class="col-sm-2">Trace id</dt>
                        <dd class="col-sm-10">
                          <code>{{ entry.traceId || '—' }}</code>
                        </dd>
                        <dt class="col-sm-2">Thread</dt>
                        <dd class="col-sm-10">
                          <code>{{ entry.thread || '—' }}</code>
                        </dd>
                        <dt class="col-sm-2">Call site</dt>
                        <dd class="col-sm-10">
                          <code>{{ entry.callSite || '—' }}</code>
                        </dd>
                        <template v-if="!entry.success">
                          <dt class="col-sm-2 text-danger">Error</dt>
                          <dd class="col-sm-10 text-danger">{{ entry.errorMessage }}</dd>
                        </template>
                      </dl>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>

          <div v-else class="text-muted small">No calls match the current filter.</div>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.trace-filter {
  max-width: 24rem;
}

.rest-filter-select {
  max-width: 12rem;
}

.rest-text {
  display: inline-block;
  max-width: 44rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.rest-method-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
}

.rest-row {
  cursor: pointer;
}

.rest-detail-row > td {
  background-color: var(--bs-tertiary-bg);
}

.rest-detail {
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 0;
}
</style>
