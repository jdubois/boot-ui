<script setup>
import LowFootprintBanner from './components/LowFootprintBanner.vue'
import {getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const filter = ref('')
const typeFilter = ref('')
const lastFetched = ref(null)

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/fault-tolerance')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load fault tolerance policies')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport, {
  enabled: manifestAvailable,
  initialLoading: false
})

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const available = computed(() => manifestAvailable.value && report.value?.faultTolerancePresent === true)

const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'No supported fault tolerance library is present.'
})

const policies = computed(() => report.value?.policies ?? [])
const events = computed(() => report.value?.events ?? [])

const filteredPolicies = computed(() => {
  const type = typeFilter.value
  const value = filter.value.trim().toLowerCase()
  return policies.value.filter((policy) => {
    if (type && policy.type !== type) return false
    if (!value) return true
    return [policy.name, policy.target, policy.provider].join(' ').toLowerCase().includes(value)
  })
})

const typeCounts = computed(() => Object.entries(report.value?.policyCountsByType ?? {}))

const typeBadgeClass = (type) =>
  ({
    CIRCUIT_BREAKER: 'bg-primary',
    RETRY: 'bg-info text-dark',
    RATE_LIMITER: 'bg-warning text-dark',
    BULKHEAD: 'bg-secondary',
    TIME_LIMITER: 'bg-success',
    FALLBACK: 'bg-dark'
  })[type] || 'bg-secondary'

const stateBadgeClass = (state) =>
  ({
    CLOSED: 'bg-success',
    OPEN: 'bg-danger',
    HALF_OPEN: 'bg-warning text-dark',
    FORCED_OPEN: 'bg-danger',
    DISABLED: 'bg-secondary',
    UNKNOWN: 'bg-light text-dark border'
  })[state] || 'bg-secondary'

const outcomeBadgeClass = (outcome) =>
  ({
    SUCCESS: 'bg-success',
    ERROR: 'bg-danger',
    RETRY: 'bg-warning text-dark',
    RETRY_EXHAUSTED: 'bg-danger',
    REJECTED: 'bg-warning text-dark',
    TIMEOUT: 'bg-danger',
    SHORT_CIRCUITED: 'bg-warning text-dark',
    STATE_TRANSITION: 'bg-primary',
    FALLBACK: 'bg-info text-dark'
  })[outcome] || 'bg-secondary'

const readableType = (type) => (type ? type.toLowerCase().replace(/_/g, ' ') : '—')

function formatTimestamp(timestamp) {
  if (!timestamp) return '—'
  return formatClockTime(timestamp)
}

function formatMetric(value) {
  if (value === null || value === undefined) return '—'
  return formatNumber(value)
}

function formatRate(value) {
  if (value === null || value === undefined) return '—'
  return `${value.toFixed(1)}%`
}

function formatDuration(value) {
  if (value === null || value === undefined) return '—'
  return `${formatNumber(value)} ms`
}

function present(value) {
  return value !== null && value !== undefined
}

function hasMetrics(metrics) {
  if (!metrics) return false
  return Object.values(metrics).some((value) => value !== null && value !== undefined)
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-shield-check"
      title="Fault Tolerance"
      subtitle="Inspect the retries, circuit breakers, rate limits, bulkheads, timeouts, and fallbacks protecting application calls."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />
    <LowFootprintBanner />

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />

    <template v-else-if="!manifestAvailable || report">
      <div v-if="!available" class="alert alert-warning">
        <strong>Fault tolerance data is unavailable.</strong>
        <span class="d-block small">{{ unavailableReason }}</span>
      </div>

      <template v-else>
        <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small py-2">
          {{ warning }}
        </div>

        <div v-if="!report.captureEnabled" class="alert alert-secondary small py-2">
          Event capture is currently disabled (<code>bootui.fault-tolerance.enabled=false</code>). Policies below are
          still read live; no retry, rejection, timeout or breaker-transition events are being recorded.
        </div>

        <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
          <span class="text-muted small">Providers:</span>
          <span v-for="provider in report.providers" :key="provider" class="badge bg-light text-dark border">
            {{ provider }}
          </span>
          <span v-for="[type, count] in typeCounts" :key="type" :class="typeBadgeClass(type)" class="badge">
            {{ readableType(type) }}: {{ count }}
          </span>
        </div>

        <div v-if="report.totalPolicies === 0" class="alert alert-secondary">
          No fault tolerance policies declared yet. Register a circuit breaker, retry, rate limiter, bulkhead or timeout
          and refresh this panel.
        </div>

        <template v-else>
          <div class="mb-3 d-flex gap-2 flex-wrap">
            <input
              v-model="filter"
              class="form-control form-control-sm fault-tolerance-filter-input"
              aria-label="Filter fault tolerance policies"
              placeholder="Filter by policy name, protected operation, or provider…"
            />
            <select
              v-model="typeFilter"
              class="form-select form-select-sm fault-tolerance-type-select"
              aria-label="Filter fault tolerance policies by type"
            >
              <option value="">All types</option>
              <option v-for="[type] in typeCounts" :key="type" :value="type">{{ readableType(type) }}</option>
            </select>
            <span class="small text-muted align-self-center">
              {{ filteredPolicies.length }} / {{ report.totalPolicies }} policies
            </span>
          </div>

          <div class="table-responsive mb-4">
            <table class="table table-sm table-hover align-middle fault-tolerance-policy-table">
              <thead>
                <tr>
                  <th>Policy</th>
                  <th style="width: 150px">Type</th>
                  <th style="width: 120px">State</th>
                  <th>Protected operation</th>
                  <th>Configuration</th>
                  <th style="width: 220px">Counters</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(policy, index) in filteredPolicies" :key="`${policy.provider}-${policy.name}-${index}`">
                  <td>
                    <code>{{ policy.name }}</code>
                    <span class="d-block text-muted small">{{ policy.provider }} · {{ policy.source }}</span>
                  </td>
                  <td>
                    <span :class="typeBadgeClass(policy.type)" class="badge">{{ readableType(policy.type) }}</span>
                  </td>
                  <td>
                    <span v-if="policy.state" :class="stateBadgeClass(policy.state)" class="badge">
                      {{ policy.state }}
                    </span>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td>
                    <code v-if="policy.target" class="small">{{ policy.target }}</code>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td>
                    <span v-if="!policy.settings?.length" class="text-muted">—</span>
                    <ul v-else class="list-unstyled mb-0 small">
                      <li v-for="setting in policy.settings" :key="setting.name">
                        <span class="text-muted">{{ setting.name }}:</span> {{ setting.value }}
                        <span
                          v-if="setting.provenance && setting.provenance !== 'CONFIGURED'"
                          class="badge bg-light text-dark border ms-1 fault-tolerance-provenance"
                          >{{ setting.provenance.toLowerCase() }}</span
                        >
                      </li>
                    </ul>
                  </td>
                  <td class="small">
                    <span v-if="!hasMetrics(policy.metrics)" class="text-muted">Not exposed by this library</span>
                    <ul v-else class="list-unstyled mb-0">
                      <li v-if="present(policy.metrics.successfulCalls)">
                        <span class="text-muted">successful:</span> {{ formatMetric(policy.metrics.successfulCalls) }}
                      </li>
                      <li v-if="present(policy.metrics.failedCalls)">
                        <span class="text-muted">failed:</span> {{ formatMetric(policy.metrics.failedCalls) }}
                      </li>
                      <li v-if="present(policy.metrics.retriedCalls)">
                        <span class="text-muted">retried:</span> {{ formatMetric(policy.metrics.retriedCalls) }}
                      </li>
                      <li v-if="present(policy.metrics.rejectedCalls)">
                        <span class="text-muted">rejected:</span> {{ formatMetric(policy.metrics.rejectedCalls) }}
                      </li>
                      <li v-if="present(policy.metrics.timeoutCalls)">
                        <span class="text-muted">timeouts:</span> {{ formatMetric(policy.metrics.timeoutCalls) }}
                      </li>
                      <li v-if="present(policy.metrics.shortCircuitedCalls)">
                        <span class="text-muted">short-circuited:</span>
                        {{ formatMetric(policy.metrics.shortCircuitedCalls) }}
                      </li>
                      <li v-if="present(policy.metrics.failureRatePercent)">
                        <span class="text-muted">failure rate:</span>
                        {{ formatRate(policy.metrics.failureRatePercent) }}
                      </li>
                      <li v-if="present(policy.metrics.bufferedCalls)">
                        <span class="text-muted">buffered:</span> {{ formatMetric(policy.metrics.bufferedCalls) }}
                      </li>
                    </ul>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <h2 class="h6">Recent events</h2>
        <div v-if="!events.length" class="alert alert-secondary small py-2">
          No fault tolerance events captured yet. Events appear here when a call is retried, rejected, timed out or
          short-circuited, or when a circuit breaker changes state.
        </div>
        <div v-else class="table-responsive">
          <table class="table table-sm table-hover align-middle fault-tolerance-event-table">
            <thead>
              <tr>
                <th style="width: 110px">Time</th>
                <th style="width: 160px">Outcome</th>
                <th>Policy</th>
                <th>Protected operation</th>
                <th style="width: 90px">Attempt</th>
                <th style="width: 110px">Duration</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in events" :key="event.id">
                <td class="text-muted small text-nowrap">{{ formatTimestamp(event.timestamp) }}</td>
                <td>
                  <span :class="outcomeBadgeClass(event.outcome)" class="badge">{{ event.outcome }}</span>
                </td>
                <td>
                  <code>{{ event.policyName }}</code>
                  <span class="d-block text-muted small">{{ readableType(event.policyType) }}</span>
                </td>
                <td>
                  <code v-if="event.target" class="small">{{ event.target }}</code>
                  <span v-else class="text-muted">—</span>
                </td>
                <td>{{ event.attempt ?? '—' }}</td>
                <td>{{ formatDuration(event.durationMillis) }}</td>
                <td class="small">
                  <span v-if="event.state" class="badge" :class="stateBadgeClass(event.state)">{{ event.state }}</span>
                  <code v-else-if="event.failureCategory">{{ event.failureCategory }}</code>
                  <span v-else class="text-muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="text-muted small mb-0">
          Showing the most recent {{ events.length }} of at most {{ report.maxEvents }} captured events. BootUI never
          records method arguments, return values, payloads or raw exception messages.
        </p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.fault-tolerance-filter-input {
  max-width: 26rem;
}

.fault-tolerance-type-select {
  max-width: 12rem;
}

.fault-tolerance-provenance {
  font-weight: 400;
}
</style>
