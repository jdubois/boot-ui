<script setup>
import {getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {describeLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const report = ref(null)
const error = ref(null)
const filter = ref('')
const lastFetched = ref(null)

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/http-clients')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load HTTP clients')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport)

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const clients = computed(() => report.value?.clients ?? [])

const filtered = computed(() => {
  const value = filter.value.trim().toLowerCase()
  if (!value) return clients.value
  return clients.value.filter((client) =>
    [
      client.name,
      client.kindLabel,
      client.framework,
      client.declaredInterface,
      client.resolvedBaseUrl,
      client.configuredBaseUrl
    ]
      .filter(Boolean)
      .some((field) => field.toLowerCase().includes(value))
  )
})

const provenanceLabels = {
  CLIENT: 'Client override',
  ANNOTATION: 'Annotation',
  APPLICATION: 'Application default',
  FRAMEWORK: 'Framework default',
  UNAVAILABLE: 'Not exposed'
}

const provenanceClasses = {
  CLIENT: 'bg-primary',
  ANNOTATION: 'bg-info text-dark',
  APPLICATION: 'bg-secondary',
  FRAMEWORK: 'bg-light text-dark border',
  UNAVAILABLE: 'bg-light text-muted border'
}

const categoryLabels = {
  TIMEOUT: 'Timeouts',
  CONNECTION_POOL: 'Connection pool',
  RETRY: 'Retry',
  REDIRECT: 'Redirects',
  PROXY: 'Proxy',
  TLS: 'TLS',
  TRANSPORT: 'Transport'
}

const categoryOrder = ['TIMEOUT', 'CONNECTION_POOL', 'RETRY', 'REDIRECT', 'PROXY', 'TLS', 'TRANSPORT']

const baseUrlLabels = {
  RESOLVED: 'Resolved',
  UNRESOLVED: 'Unresolved',
  NOT_DECLARED: 'Not declared'
}

const observedLabels = {
  LINKED: 'Linked',
  NO_CALLS: 'None in top calls',
  NOT_ATTRIBUTABLE: 'Not attributable',
  UNAVAILABLE: 'Unavailable'
}

const expanded = ref({})

function toggle(id) {
  expanded.value = {...expanded.value, [id]: !expanded.value[id]}
}

function groupedSettings(client) {
  const groups = new Map()
  for (const setting of client.settings ?? []) {
    if (!groups.has(setting.category)) groups.set(setting.category, [])
    groups.get(setting.category).push(setting)
  }
  return categoryOrder
    .filter((category) => groups.has(category))
    .map((category) => ({category, label: categoryLabels[category] ?? category, settings: groups.get(category)}))
}

function provenanceLabel(provenance) {
  return provenanceLabels[provenance] ?? provenance
}

function provenanceClass(provenance) {
  return provenanceClasses[provenance] ?? 'bg-secondary'
}

function formatDuration(millis) {
  if (millis === null || millis === undefined) return '—'
  if (millis < 1000) return `${millis} ms`
  return `${(millis / 1000).toFixed(2)} s`
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-broadcast-pin"
      title="HTTP Clients"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading && !report" />
    <div v-else-if="report && !report.available" class="alert alert-info">
      {{ report.unavailableReason || 'No declarative HTTP client is registered.' }}
    </div>
    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning py-2 small">
        <i class="bi bi-exclamation-triangle me-1" aria-hidden="true"></i>{{ warning }}
      </div>

      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input
            v-model="filter"
            aria-label="Filter HTTP clients"
            class="form-control"
            placeholder="Filter by name, type, interface, or base URL…"
          />
        </div>
        <div class="col-md-4 text-end small text-muted align-self-center">
          {{ filtered.length }} / {{ report.total }} clients
        </div>
      </div>

      <div v-if="!report.observedCallsAvailable" class="small text-muted mb-3">
        <i class="bi bi-info-circle me-1" aria-hidden="true"></i>{{ report.observedCallsUnavailableReason }}
      </div>

      <div v-if="filtered.length === 0" class="alert alert-secondary">No HTTP client matches this filter.</div>

      <div v-for="client in filtered" :key="client.id" class="card mb-3">
        <div class="card-body">
          <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
            <h3 class="h6 mb-0">{{ client.name }}</h3>
            <span class="badge bg-primary">{{ client.kindLabel }}</span>
            <span class="badge bg-light text-dark border">{{ client.framework }}</span>
            <span
              v-if="client.observedCallsStatus === 'LINKED'"
              class="badge bg-success"
              :title="`${client.observedCalls.length} retained call(s) observed to this client's host`"
            >
              {{ observedLabels[client.observedCallsStatus] }}
            </span>
          </div>

          <dl class="row mb-2 small">
            <dt class="col-sm-3 text-muted fw-normal">Declared interface</dt>
            <dd class="col-sm-9">
              <code v-if="client.declaredInterface">{{ client.declaredInterface }}</code>
              <span v-else class="text-muted">Not applicable</span>
            </dd>

            <dt class="col-sm-3 text-muted fw-normal">Configuration key</dt>
            <dd class="col-sm-9">
              <code v-if="client.configKey">{{ client.configKey }}</code>
              <span v-else class="text-muted">Not applicable</span>
            </dd>

            <dt class="col-sm-3 text-muted fw-normal">Base URL</dt>
            <dd class="col-sm-9">
              <template v-if="client.baseUrlStatus === 'RESOLVED'">
                <code>{{ client.resolvedBaseUrl }}</code>
                <span
                  v-if="client.configuredBaseUrl && client.configuredBaseUrl !== client.resolvedBaseUrl"
                  class="text-muted ms-2"
                >
                  configured as <code>{{ client.configuredBaseUrl }}</code>
                </span>
              </template>
              <template v-else-if="client.baseUrlStatus === 'UNRESOLVED'">
                <code>{{ client.configuredBaseUrl }}</code>
                <span class="badge bg-warning text-dark ms-2">{{ baseUrlLabels[client.baseUrlStatus] }}</span>
              </template>
              <span v-else class="text-muted">{{ baseUrlLabels[client.baseUrlStatus] }}</span>
              <span :class="provenanceClass(client.baseUrlProvenance)" class="badge ms-2">
                {{ provenanceLabel(client.baseUrlProvenance) }}
              </span>
              <div v-if="client.baseUrlSource" class="text-muted mt-1">
                from <code class="small">{{ client.baseUrlSource }}</code>
              </div>
            </dd>
          </dl>

          <button
            class="btn btn-sm btn-outline-secondary"
            type="button"
            :aria-expanded="expanded[client.id] ? 'true' : 'false'"
            :aria-controls="`http-client-settings-${client.id}`"
            @click="toggle(client.id)"
          >
            <i
              :class="expanded[client.id] ? 'bi-chevron-up' : 'bi-chevron-down'"
              class="bi me-1"
              aria-hidden="true"
            ></i>
            {{ expanded[client.id] ? 'Hide' : 'Show' }} effective settings
          </button>

          <div v-if="expanded[client.id]" :id="`http-client-settings-${client.id}`" class="mt-3">
            <div v-for="group in groupedSettings(client)" :key="group.category" class="mb-3">
              <h4 class="text-muted small fw-semibold mb-1">{{ group.label }}</h4>
              <div class="table-responsive">
                <table class="table table-sm table-hover align-middle mb-0">
                  <thead>
                    <tr>
                      <th style="width: 30%">Setting</th>
                      <th style="width: 30%">Value</th>
                      <th style="width: 20%">Provenance</th>
                      <th>Source</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="setting in group.settings" :key="`${group.category}-${setting.name}`">
                      <td>{{ setting.name }}</td>
                      <td>
                        <code v-if="setting.value">{{ setting.value }}</code>
                        <span v-else class="text-muted">Not exposed</span>
                      </td>
                      <td>
                        <span :class="provenanceClass(setting.provenance)" class="badge">
                          {{ provenanceLabel(setting.provenance) }}
                        </span>
                      </td>
                      <td>
                        <code v-if="setting.source" class="small">{{ setting.source }}</code>
                        <span v-else class="text-muted">—</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <h4 class="text-muted small fw-semibold mb-1">Observed calls to this host</h4>
            <div v-if="client.observedCallsStatus !== 'LINKED'" class="small text-muted">
              {{ observedLabels[client.observedCallsStatus] }}
              <template v-if="client.observedCallsStatus === 'NOT_ATTRIBUTABLE'">
                — BootUI only links calls it can attribute to exactly one client.
              </template>
            </div>
            <div v-else class="table-responsive">
              <table class="table table-sm table-hover align-middle mb-0">
                <thead>
                  <tr>
                    <th style="width: 90px">Method</th>
                    <th>Path</th>
                    <th style="width: 120px">Executions</th>
                    <th style="width: 120px">Slowest</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="call in client.observedCalls" :key="`${call.method}-${call.path}`">
                    <td>
                      <span class="badge bg-secondary">{{ call.method }}</span>
                    </td>
                    <td>
                      <code>{{ call.path }}</code>
                    </td>
                    <td>{{ call.executions }}</td>
                    <td>{{ formatDuration(call.maxDurationMillis) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
