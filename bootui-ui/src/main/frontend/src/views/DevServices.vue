<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, ref} from 'vue'
import {formatClockTime} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const filter = ref('')
const selected = ref(null)
const logs = ref(null)
const actionMessage = ref(null)
const busyService = ref(null)
const lastFetched = ref(null)

async function fetchReport(options = {}) {
  error.value = null
  if (!options.preserveActionMessage) {
    actionMessage.value = null
  }
  try {
    report.value = await getJson('api/dev-services')
    lastFetched.value = Date.now()
    syncSelectedService()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Dev Services')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport)

const filtered = computed(() => {
  if (!report.value) return []
  const value = filter.value.trim().toLowerCase()
  if (!value) return report.value.services
  return report.value.services.filter(
    (service) =>
      (service.name || '').toLowerCase().includes(value) ||
      (service.type || '').toLowerCase().includes(value) ||
      (service.status || '').toLowerCase().includes(value) ||
      (service.image || '').toLowerCase().includes(value)
  )
})

function sourceClass(source) {
  return (
    {
      'Docker Compose': 'bg-primary',
      Testcontainers: 'bg-success',
      'Connection details': 'bg-info text-dark'
    }[source] || 'bg-secondary'
  )
}

function statusClass(status) {
  return (
    {
      RUNNING: 'text-bg-success',
      STOPPED: 'text-bg-secondary',
      AVAILABLE: 'text-bg-info',
      READY_AT_STARTUP: 'text-bg-primary'
    }[status] || 'text-bg-secondary'
  )
}

function formatSnapshot(timestamp) {
  if (!timestamp) return 'unknown'
  return formatClockTime(timestamp)
}

function formatPorts(service) {
  if (!service.ports || service.ports.length === 0) return '—'
  return service.ports
    .map((port) => {
      if (port.containerPort && port.hostPort)
        return `${port.containerPort} → ${port.hostPort}/${port.protocol || 'tcp'}`
      if (port.hostPort) return `${port.hostPort}/${port.protocol || 'tcp'}`
      return port.containerPort || '—'
    })
    .join(', ')
}

function detailEntries(service) {
  return Object.entries(service.connectionDetails || {})
}

function syncSelectedService() {
  const services = report.value?.services || []
  if (services.length === 0) {
    selected.value = null
    logs.value = null
    return
  }
  if (!selected.value) {
    selected.value = services[0]
    logs.value = null
    return
  }
  const updated = services.find((service) => service.id === selected.value.id)
  if (updated) {
    selected.value = updated
    return
  }
  selected.value = services[0]
  logs.value = null
}

function selectService(service) {
  if (selected.value?.id !== service.id) {
    logs.value = null
  }
  selected.value = service
  actionMessage.value = null
}

function isSelected(service) {
  return selected.value?.id === service.id
}

function serviceActionUrl(service, action) {
  return `api/dev-services/${encodeURIComponent(service.id)}/${action}`
}

async function openLogs(service) {
  if (selected.value?.id !== service.id) {
    logs.value = null
  }
  selected.value = service
  logs.value = null
  actionMessage.value = null
  busyService.value = service.id
  try {
    const res = await apiFetch(serviceActionUrl(service, 'logs'))
    if (!res.ok) throw new Error(await responseMessage(res))
    logs.value = await res.json()
  } catch (e) {
    actionMessage.value = {type: 'danger', text: formatLoadError(e, 'Unable to load service logs')}
  } finally {
    busyService.value = null
  }
}

async function restart(service) {
  if (readOnly.value) {
    actionMessage.value = {type: 'warning', text: readOnlyReason.value}
    return
  }
  if (selected.value?.id !== service.id) {
    logs.value = null
  }
  selected.value = service
  logs.value = null
  actionMessage.value = null
  busyService.value = service.id
  try {
    const res = await apiFetch(serviceActionUrl(service, 'restart'), {method: 'POST'})
    if (!res.ok) throw new Error(await responseMessage(res))
    const result = await res.json()
    await load({preserveActionMessage: true})
    actionMessage.value = {type: 'success', text: result.message}
  } catch (e) {
    actionMessage.value = {type: 'danger', text: formatLoadError(e, 'Unable to restart service')}
  } finally {
    busyService.value = null
  }
}

async function responseMessage(res) {
  try {
    const body = await res.json()
    return body.message || body.error || `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-box-seam"
      title="Dev Services"
      :subtitle="
        report
          ? `Snapshot ${formatSnapshot(report.snapshotTimestamp)} · Docker Compose ${report.dockerComposePresent ? 'available' : 'not detected'} · Testcontainers ${report.testcontainersPresent ? 'available' : 'not detected'}`
          : null
      "
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <div class="alert alert-info">
      Docker Compose services are shown from Spring Boot's startup snapshot. Restart controls appear only for
      bean-backed Testcontainers services when <code>bootui.dev-services.restart-enabled=true</code>.
      <span v-if="readOnly"> Restart actions are read-only. {{ readOnlyReason }}</span>
    </div>

    <div v-if="report?.warnings?.length" class="alert alert-warning">
      <div class="fw-semibold mb-1">Some services were skipped or adjusted for safe inspection.</div>
      <ul class="mb-0">
        <li v-for="warning in report.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </div>

    <PanelSkeleton v-if="initialLoading && !report" />
    <div v-else-if="report && report.total === 0" class="alert alert-secondary">
      No Docker Compose, Testcontainers, or Spring Boot service connection beans were detected.
    </div>

    <template v-else-if="report">
      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input v-model="filter" class="form-control" placeholder="Filter by name, type, status, or image…" />
        </div>
        <div class="col-md-4 text-end small text-muted align-self-center">
          {{ filtered.length }} / {{ report.total }} services
        </div>
      </div>

      <div v-if="actionMessage" :class="`alert-${actionMessage.type}`" class="alert">
        {{ actionMessage.text }}
      </div>

      <div class="row g-3">
        <div class="col-xl-7">
          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Service</th>
                  <th>Status</th>
                  <th>Host / ports</th>
                  <th class="text-end">Actions</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="service in filtered" :key="service.id" :class="{'selected-row': isSelected(service)}">
                  <td data-label="Service">
                    <div class="fw-semibold">{{ service.name }}</div>
                    <div class="small text-muted">
                      {{ service.type }}
                      <span v-if="service.image">
                        · <code>{{ service.image }}</code></span
                      >
                    </div>
                  </td>
                  <td data-label="Status">
                    <span :class="statusClass(service.status)" class="badge">{{ service.status }}</span>
                  </td>
                  <td class="small" data-label="Host / ports">
                    <template v-if="service.host || service.ports?.length">
                      <div v-if="service.host">{{ service.host }}</div>
                      <code v-if="service.ports?.length">{{ formatPorts(service) }}</code>
                    </template>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td class="text-end" data-label="Actions">
                    <div class="d-flex flex-wrap justify-content-end gap-1 service-actions">
                      <button
                        :aria-pressed="isSelected(service)"
                        :class="isSelected(service) ? 'btn-primary' : 'btn-outline-primary'"
                        class="btn btn-sm"
                        @click="selectService(service)"
                      >
                        <i class="bi bi-info-circle me-1"></i>
                        {{ isSelected(service) ? 'Details shown' : 'View details' }}
                      </button>
                      <button
                        v-if="service.logsAvailable"
                        :disabled="busyService === service.id"
                        class="btn btn-sm btn-outline-secondary"
                        @click="openLogs(service)"
                      >
                        <i class="bi bi-card-text me-1"></i>
                        View logs
                      </button>
                      <button
                        v-if="service.restartable"
                        :disabled="readOnly || busyService === service.id"
                        class="btn btn-sm btn-outline-danger"
                        @click="restart(service)"
                      >
                        <i class="bi bi-arrow-repeat me-1"></i>
                        Restart
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="col-xl-5">
          <div v-if="!selected" class="text-muted small">Select a service to inspect connection details.</div>
          <div v-else class="card">
            <div class="card-header d-flex justify-content-between align-items-start gap-2">
              <div>
                <strong>{{ selected.name }}</strong>
                <div class="small text-muted">{{ selected.note }}</div>
              </div>
              <span :class="sourceClass(selected.source)" class="badge">{{ selected.source }}</span>
            </div>
            <div class="card-body">
              <dl class="row small mb-3">
                <dt class="col-sm-4">Type</dt>
                <dd class="col-sm-8">{{ selected.type }}</dd>
                <dt class="col-sm-4">Image</dt>
                <dd class="col-sm-8">
                  <code>{{ selected.image || '—' }}</code>
                </dd>
                <dt class="col-sm-4">Host</dt>
                <dd class="col-sm-8">
                  <code>{{ selected.host || '—' }}</code>
                </dd>
                <dt class="col-sm-4">Ports</dt>
                <dd class="col-sm-8">
                  <code>{{ formatPorts(selected) }}</code>
                </dd>
              </dl>

              <h6>Connection details</h6>
              <div v-if="detailEntries(selected).length === 0" class="text-muted small">
                No connection detail values exposed.
              </div>
              <table v-else class="table table-sm">
                <tbody>
                  <tr v-for="[key, value] in detailEntries(selected)" :key="key">
                    <th class="text-nowrap small">{{ key }}</th>
                    <td>
                      <code>{{ value ?? '—' }}</code>
                    </td>
                  </tr>
                </tbody>
              </table>

              <template v-if="logs">
                <div class="d-flex justify-content-between align-items-center mt-3">
                  <h6 class="mb-0">Logs</h6>
                  <span v-if="logs.truncated" class="badge text-bg-warning">Tail {{ logs.maxBytes }} bytes</span>
                </div>
                <pre class="logs rounded border p-2 mt-2 mb-0"><code>{{
                    logs.logs || 'No log output yet.'
                  }}</code></pre>
              </template>
              <template v-else-if="selected.logsAvailable">
                <div class="text-muted small mt-3">
                  Use <strong>View logs</strong> to load the bounded log tail for this service.
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.logs {
  background: #111827;
  color: #f8f9fa;
  max-height: 360px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

td code {
  white-space: normal;
  word-break: break-word;
}

.selected-row > td {
  --bs-table-bg: rgba(13, 110, 253, 0.08);
}

@media (max-width: 991.98px) {
  .table-responsive {
    overflow-x: visible;
  }

  table,
  tbody,
  tr,
  td {
    display: block;
    width: 100%;
  }

  table {
    border-collapse: separate;
    border-spacing: 0 0.75rem;
  }

  thead {
    display: none;
  }

  tr {
    background: #fff;
    border: 1px solid rgba(15, 23, 42, 0.08);
    border-radius: 1rem;
    box-shadow: 0 0.75rem 1.75rem rgba(15, 23, 42, 0.06);
    padding: 0.75rem;
  }

  td {
    border: 0;
    padding: 0.35rem 0;
  }

  td::before {
    color: #6c757d;
    content: attr(data-label);
    display: block;
    font-size: 0.75rem;
    font-weight: 600;
    margin-bottom: 0.15rem;
    text-transform: uppercase;
  }

  td.text-end {
    text-align: start !important;
  }

  .service-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    justify-content: flex-start !important;
  }

  .service-actions > .btn {
    border-radius: 0.75rem !important;
  }
}
</style>
