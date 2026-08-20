<script setup>
import {getJson} from '../api.js'
import {computed, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const filter = ref('')
const lastFetched = ref(null)

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/grpc')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load gRPC registry')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport, {
  enabled: manifestAvailable,
  initialLoading: false
})

const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'gRPC is not available in this application.'
})

const term = computed(() => filter.value.trim().toLowerCase())

function matches(...values) {
  if (!term.value) return true
  return values.some((value) => (value || '').toLowerCase().includes(term.value))
}

function decorateService(service, methods) {
  return {...service, methods, retainedMethodCount: (service.methods || []).length}
}

function filterService(service) {
  const methods = service.methods || []
  if (!term.value) return decorateService(service, methods)
  if (matches(service.name, service.implementationClass)) return decorateService(service, methods)
  const matched = methods.filter((method) => matches(method.name, method.fullName))
  return matched.length ? decorateService(service, matched) : null
}

function decorateServer(server, services) {
  return {...server, services, retainedServiceCount: (server.services || []).length}
}

const servers = computed(() =>
  (report.value?.servers || [])
    .map((server) => {
      const services = server.services || []
      if (!term.value)
        return decorateServer(
          server,
          services.map((service) => filterService(service))
        )
      if (matches(server.name, server.address, String(server.port ?? ''))) {
        return decorateServer(
          server,
          services.map((service) => filterService(service) || decorateService(service, service.methods || []))
        )
      }
      const matched = services.map(filterService).filter(Boolean)
      return matched.length ? decorateServer(server, matched) : null
    })
    .filter(Boolean)
)

const channels = computed(() => {
  const list = report.value?.channels || []
  if (!term.value) return list
  return list.filter((channel) => matches(channel.name, channel.target, channel.authority))
})

const clientServices = computed(() => {
  const list = report.value?.clientServices || []
  if (!term.value) return list.map((service) => decorateService(service, service.methods || []))
  return list.map(filterService).filter(Boolean)
})

const hasMatches = computed(
  () => servers.value.length > 0 || channels.value.length > 0 || clientServices.value.length > 0
)

const securityBadgeClass = (value) =>
  ({
    TLS: 'text-bg-success',
    PLAINTEXT: 'text-bg-secondary',
    UNKNOWN: 'text-bg-light border text-body-secondary'
  })[value] || 'text-bg-light border text-body-secondary'

const methodBadgeClass = (value) =>
  ({
    UNARY: 'text-bg-primary',
    SERVER_STREAMING: 'text-bg-info',
    CLIENT_STREAMING: 'text-bg-info',
    BIDI_STREAMING: 'text-bg-info',
    UNKNOWN: 'text-bg-secondary'
  })[value] || 'text-bg-secondary'

// gRPC statuses that normally describe a rejected or cancelled call rather than a server-side fault.
const EXPECTED_STATUSES = new Set([
  'CANCELLED',
  'INVALID_ARGUMENT',
  'NOT_FOUND',
  'ALREADY_EXISTS',
  'PERMISSION_DENIED',
  'UNAUTHENTICATED',
  'RESOURCE_EXHAUSTED',
  'FAILED_PRECONDITION',
  'ABORTED',
  'OUT_OF_RANGE'
])

function statusBadgeClass(status) {
  if (status === 'OK') return 'text-bg-success'
  if (EXPECTED_STATUSES.has(status)) return 'text-bg-warning'
  return 'text-bg-danger'
}

const METHOD_TYPE_LABELS = {
  UNARY: 'Unary',
  SERVER_STREAMING: 'Server streaming',
  CLIENT_STREAMING: 'Client streaming',
  BIDI_STREAMING: 'Bidirectional streaming',
  UNKNOWN: 'Unknown'
}

function formatMethodType(value) {
  return METHOD_TYPE_LABELS[value] || 'Unknown'
}

function formatBytes(value) {
  if (value === null || value === undefined) return '—'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / (1024 * 1024)).toFixed(1)} MiB`
}

function formatDuration(value) {
  if (value === null || value === undefined) return '—'
  if (value < 1) return `${value.toFixed(3)} ms`
  if (value < 1000) return `${value.toFixed(1)} ms`
  return `${(value / 1000).toFixed(2)} s`
}

function formatBoolean(value) {
  if (value === null || value === undefined) return 'Unknown'
  return value ? 'Enabled' : 'Disabled'
}

// unix: and in-process: endpoints carry their location in the address, so there is no port to append.
const PORTLESS_ADDRESS_PREFIXES = ['unix:', 'in-process:', 'inprocess:']

function formatEndpoint(server) {
  const address = server.address || ''
  const portless = PORTLESS_ADDRESS_PREFIXES.some((prefix) => address.toLowerCase().startsWith(prefix))
  if (portless || server.port === null || server.port === undefined) return address || '—'
  return `${address}:${server.port}`
}

function hasChannelDetails(channel) {
  return Boolean(
    channel.authority ||
    channel.maxInboundMetadataSize !== null ||
    (channel.keepAlive && channel.keepAlive.length) ||
    (channel.settings && channel.settings.length) ||
    (channel.interceptors && channel.interceptors.length)
  )
}

function hasCalls(metrics) {
  return Boolean(metrics && metrics.available && metrics.callCount > 0)
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-ethernet"
      title="gRPC"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      :auto-refreshable="manifestAvailable"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />
    <template v-else-if="!manifestAvailable || report">
      <UnavailableState v-if="!manifestAvailable || !report.available" variant="info">
        {{ unavailableReason }}
      </UnavailableState>
      <template v-else>
        <div class="row g-2 mb-3 stat-cards">
          <div class="col-md-4">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Servers</div>
                <div class="fs-5 fw-semibold">
                  {{ report.serverCount }}
                  <span class="text-muted fs-6"
                    >· {{ report.serviceCount }} services · {{ report.methodCount }} methods</span
                  >
                </div>
              </div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Client channels</div>
                <div class="fs-5 fw-semibold">{{ report.channelCount }}</div>
              </div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="card h-100">
              <div class="card-body py-2">
                <div class="text-muted small">Integration</div>
                <div class="fs-6 fw-semibold pt-1">{{ report.integration || 'gRPC' }}</div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="!report.metricsAvailable" class="alert alert-secondary py-2 small" role="status">
          <i class="bi bi-graph-up me-2"></i>
          {{ report.metricsUnavailableReason || 'gRPC call metrics are not available.' }}
        </div>

        <div v-if="report.warnings && report.warnings.length" class="alert alert-warning py-2" role="status">
          <div v-for="warning in report.warnings" :key="warning" class="small">{{ warning }}</div>
        </div>

        <div class="row g-2 mb-3">
          <div class="col-md-8">
            <input
              v-model="filter"
              aria-label="Filter gRPC services, methods and channels"
              class="form-control"
              placeholder="Filter by service, method or channel…"
            />
          </div>
        </div>

        <UnavailableState v-if="term && !hasMatches" icon="bi-search">
          No gRPC services, methods or channels match this filter.
        </UnavailableState>

        <section v-for="server in servers" :key="server.id" class="card mb-3">
          <div class="card-header d-flex flex-wrap gap-2 align-items-center">
            <span class="fw-semibold">{{ server.name }}</span>
            <code class="small">{{ formatEndpoint(server) }}</code>
            <span :class="['badge', securityBadgeClass(server.transportSecurity)]">
              {{ server.transportSecurity }}
            </span>
            <span class="text-muted small ms-auto">
              {{ server.serviceCount }} services · {{ server.methodCount }} methods
            </span>
          </div>
          <div class="card-body">
            <dl class="row mb-3 small">
              <dt class="col-sm-3">Reflection</dt>
              <dd class="col-sm-3">{{ formatBoolean(server.reflectionEnabled) }}</dd>
              <dt class="col-sm-3">Max inbound message</dt>
              <dd class="col-sm-3">{{ formatBytes(server.maxInboundMessageSize) }}</dd>
              <dt class="col-sm-3">Max inbound metadata</dt>
              <dd class="col-sm-3">{{ formatBytes(server.maxInboundMetadataSize) }}</dd>
              <template v-if="server.interceptors && server.interceptors.length">
                <dt class="col-sm-3">Interceptors</dt>
                <dd class="col-sm-9">
                  <code v-for="interceptor in server.interceptors" :key="interceptor" class="me-2 small">
                    {{ interceptor }}
                  </code>
                </dd>
              </template>
              <template v-for="setting in server.keepAlive" :key="`ka-${setting.name}`">
                <dt class="col-sm-3">Keepalive {{ setting.name.toLowerCase() }}</dt>
                <dd class="col-sm-3">{{ setting.value }}</dd>
              </template>
              <template v-for="setting in server.settings" :key="`s-${setting.name}`">
                <dt class="col-sm-3">{{ setting.name }}</dt>
                <dd class="col-sm-3">{{ setting.value }}</dd>
              </template>
            </dl>

            <div v-if="server.servicesTruncated" class="small text-muted mb-2">
              Only the first {{ server.retainedServiceCount }} of {{ server.serviceCount }} services are shown.
            </div>

            <div v-for="service in server.services" :key="service.name" class="mb-3">
              <div class="d-flex flex-wrap gap-2 align-items-center mb-1">
                <code class="fw-semibold">{{ service.name }}</code>
                <span class="text-muted small">{{ service.implementationClass }}</span>
                <span v-if="hasCalls(service.metrics)" class="badge text-bg-light ms-auto">
                  {{ service.metrics.callCount }} calls
                </span>
              </div>
              <div class="table-responsive">
                <table class="table table-sm table-hover align-middle mb-0">
                  <thead>
                    <tr>
                      <th>Method</th>
                      <th style="width: 150px">Type</th>
                      <th style="width: 90px" class="text-end">Calls</th>
                      <th style="width: 90px" class="text-end">In flight</th>
                      <th style="width: 110px" class="text-end">Avg</th>
                      <th style="width: 110px" class="text-end">Max</th>
                      <th style="width: 200px">Statuses</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="method in service.methods" :key="method.fullName">
                      <td>
                        <code>{{ method.name }}</code>
                      </td>
                      <td>
                        <span :class="['badge', methodBadgeClass(method.type)]">
                          {{ formatMethodType(method.type) }}
                        </span>
                      </td>
                      <td class="text-end">{{ method.metrics.available ? method.metrics.callCount : '—' }}</td>
                      <td class="text-end">
                        {{ method.metrics.activeCalls === null ? '—' : method.metrics.activeCalls }}
                      </td>
                      <td class="text-end">{{ formatDuration(method.metrics.averageDurationMs) }}</td>
                      <td class="text-end">{{ formatDuration(method.metrics.maxDurationMs) }}</td>
                      <td>
                        <span
                          v-for="status in method.metrics.statusCounts"
                          :key="status.status"
                          :class="['badge', 'me-1', statusBadgeClass(status.status)]"
                        >
                          {{ status.status }} {{ status.count }}
                        </span>
                        <span v-if="!method.metrics.statusCounts.length" class="text-muted">—</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="service.methodsTruncated" class="small text-muted mt-1">
                Only the first {{ service.retainedMethodCount }} of {{ service.methodCount }} methods are shown.
              </div>
            </div>

            <div v-if="!server.services.length" class="text-muted small">No gRPC services are registered.</div>
          </div>
        </section>

        <section v-if="channels.length" class="card mb-3">
          <div class="card-header fw-semibold">Client channels</div>
          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th style="width: 160px">Name</th>
                  <th>Target</th>
                  <th style="width: 130px">Transport</th>
                  <th style="width: 150px">Load balancing</th>
                  <th style="width: 100px">Retry</th>
                  <th style="width: 130px">Max message</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="channel in channels" :key="channel.name">
                  <tr>
                    <td>
                      <code>{{ channel.name }}</code>
                    </td>
                    <td>
                      <code class="small">{{ channel.target || '—' }}</code>
                    </td>
                    <td>
                      <span :class="['badge', securityBadgeClass(channel.transportSecurity)]">
                        {{ channel.transportSecurity }}
                      </span>
                    </td>
                    <td>{{ channel.loadBalancingPolicy || '—' }}</td>
                    <td>{{ formatBoolean(channel.retryEnabled) }}</td>
                    <td>{{ formatBytes(channel.maxInboundMessageSize) }}</td>
                  </tr>
                  <tr v-if="hasChannelDetails(channel)">
                    <td colspan="6" class="pt-0 border-top-0">
                      <dl class="row mb-0 small">
                        <template v-if="channel.authority">
                          <dt class="col-sm-3">Authority</dt>
                          <dd class="col-sm-3 mb-1">
                            <code>{{ channel.authority }}</code>
                          </dd>
                        </template>
                        <template v-if="channel.maxInboundMetadataSize !== null">
                          <dt class="col-sm-3">Max inbound metadata</dt>
                          <dd class="col-sm-3 mb-1">{{ formatBytes(channel.maxInboundMetadataSize) }}</dd>
                        </template>
                        <template v-for="setting in channel.keepAlive" :key="`cka-${channel.name}-${setting.name}`">
                          <dt class="col-sm-3">Keepalive {{ setting.name.toLowerCase() }}</dt>
                          <dd class="col-sm-3 mb-1">{{ setting.value }}</dd>
                        </template>
                        <template v-for="setting in channel.settings" :key="`cs-${channel.name}-${setting.name}`">
                          <dt class="col-sm-3">{{ setting.name }}</dt>
                          <dd class="col-sm-3 mb-1">{{ setting.value }}</dd>
                        </template>
                        <template v-if="channel.interceptors && channel.interceptors.length">
                          <dt class="col-sm-3">Interceptors</dt>
                          <dd class="col-sm-9 mb-1">
                            <code
                              v-for="interceptor in channel.interceptors"
                              :key="`ci-${channel.name}-${interceptor}`"
                              class="me-2"
                            >
                              {{ interceptor }}
                            </code>
                          </dd>
                        </template>
                      </dl>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="clientServices.length" class="card mb-3">
          <div class="card-header d-flex align-items-center">
            <span class="fw-semibold">Outgoing calls</span>
            <span class="text-muted small ms-2">observed from client-side metrics</span>
          </div>
          <div class="card-body">
            <div v-for="service in clientServices" :key="service.name" class="mb-3">
              <code class="fw-semibold">{{ service.name }}</code>
              <div class="table-responsive mt-1">
                <table class="table table-sm table-hover align-middle mb-0">
                  <thead>
                    <tr>
                      <th>Method</th>
                      <th style="width: 90px" class="text-end">Calls</th>
                      <th style="width: 90px" class="text-end">In flight</th>
                      <th style="width: 110px" class="text-end">Avg</th>
                      <th style="width: 110px" class="text-end">Max</th>
                      <th style="width: 200px">Statuses</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="method in service.methods" :key="method.fullName">
                      <td>
                        <code>{{ method.name }}</code>
                      </td>
                      <td class="text-end">{{ method.metrics.callCount }}</td>
                      <td class="text-end">
                        {{ method.metrics.activeCalls === null ? '—' : method.metrics.activeCalls }}
                      </td>
                      <td class="text-end">{{ formatDuration(method.metrics.averageDurationMs) }}</td>
                      <td class="text-end">{{ formatDuration(method.metrics.maxDurationMs) }}</td>
                      <td>
                        <span
                          v-for="status in method.metrics.statusCounts"
                          :key="status.status"
                          :class="['badge', 'me-1', statusBadgeClass(status.status)]"
                        >
                          {{ status.status }} {{ status.count }}
                        </span>
                        <span v-if="!method.metrics.statusCounts.length" class="text-muted">—</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </section>

        <UnavailableState v-if="!report.serverCount && !report.channelCount" icon="bi-ethernet">
          No gRPC servers or client channels are configured.
        </UnavailableState>
      </template>
    </template>
  </div>
</template>
