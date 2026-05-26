<script setup>
import { computed, onMounted, ref } from 'vue'

const report = ref(null)
const loading = ref(true)
const error = ref(null)
const filter = ref('')
const selected = ref(null)
const logs = ref(null)
const actionMessage = ref(null)
const busyService = ref(null)

async function load() {
  loading.value = true
  error.value = null
  actionMessage.value = null
  try {
    const res = await fetch('api/dev-services')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  if (!report.value) return []
  const value = filter.value.trim().toLowerCase()
  if (!value) return report.value.services
  return report.value.services.filter(service =>
    (service.name || '').toLowerCase().includes(value)
    || (service.type || '').toLowerCase().includes(value)
    || (service.source || '').toLowerCase().includes(value)
    || (service.image || '').toLowerCase().includes(value)
  )
})

function sourceClass(source) {
  return {
    'Docker Compose': 'bg-primary',
    Testcontainers: 'bg-success',
    'Connection details': 'bg-info text-dark'
  }[source] || 'bg-secondary'
}

function statusClass(status) {
  return {
    RUNNING: 'text-bg-success',
    STOPPED: 'text-bg-secondary',
    AVAILABLE: 'text-bg-info',
    READY_AT_STARTUP: 'text-bg-primary'
  }[status] || 'text-bg-secondary'
}

function formatSnapshot(timestamp) {
  if (!timestamp) return 'unknown'
  return new Date(timestamp).toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

function formatPorts(service) {
  if (!service.ports || service.ports.length === 0) return '—'
  return service.ports
    .map(port => {
      if (port.containerPort && port.hostPort) return `${port.containerPort} → ${port.hostPort}/${port.protocol || 'tcp'}`
      if (port.hostPort) return `${port.hostPort}/${port.protocol || 'tcp'}`
      return port.containerPort || '—'
    })
    .join(', ')
}

function detailEntries(service) {
  return Object.entries(service.connectionDetails || {})
}

async function openLogs(service) {
  selected.value = service
  logs.value = null
  actionMessage.value = null
  busyService.value = service.id
  try {
    const res = await fetch(`api/dev-services/${encodeURIComponent(service.id)}/logs`)
    if (!res.ok) throw new Error(await responseMessage(res))
    logs.value = await res.json()
  } catch (e) {
    actionMessage.value = { type: 'danger', text: e.message }
  } finally {
    busyService.value = null
  }
}

async function restart(service) {
  selected.value = service
  actionMessage.value = null
  busyService.value = service.id
  try {
    const res = await fetch(`api/dev-services/${encodeURIComponent(service.id)}/restart`, { method: 'POST' })
    if (!res.ok) throw new Error(await responseMessage(res))
    const result = await res.json()
    actionMessage.value = { type: 'success', text: result.message }
    await load()
  } catch (e) {
    actionMessage.value = { type: 'danger', text: e.message }
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

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-box-seam me-2"></i>Dev Services</h2>
        <div v-if="report" class="text-muted small">
          Snapshot {{ formatSnapshot(report.snapshotTimestamp) }} ·
          Docker Compose {{ report.dockerComposePresent ? 'available' : 'not detected' }} ·
          Testcontainers {{ report.testcontainersPresent ? 'available' : 'not detected' }}
        </div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" @click="load">
        <i class="bi bi-arrow-clockwise"></i> Refresh
      </button>
    </div>

    <div class="alert alert-info">
      Docker Compose services are shown from Spring Boot's startup snapshot. Restart is intentionally disabled by default
      because service ports can change and already-created client beans may not reconnect.
    </div>

    <div v-if="loading" class="text-muted">Loading…</div>
    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>
    <div v-else-if="report && report.total === 0" class="alert alert-secondary">
      No Docker Compose, Testcontainers, or Spring Boot service connection beans were detected.
    </div>

    <template v-else-if="report">
      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input
            v-model="filter"
            class="form-control"
            placeholder="Filter by name, type, source, or image…" />
        </div>
        <div class="col-md-4 text-end small text-muted align-self-center">
          {{ filtered.length }} / {{ report.total }} services
        </div>
      </div>

      <div v-if="actionMessage" class="alert" :class="`alert-${actionMessage.type}`">
        {{ actionMessage.text }}
      </div>

      <div class="row g-3">
        <div class="col-xl-7">
          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Service</th>
                  <th>Source</th>
                  <th>Status</th>
                  <th>Host / ports</th>
                  <th class="text-end">Actions</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="service in filtered" :key="service.id">
                  <td>
                    <div class="fw-semibold">{{ service.name }}</div>
                    <div class="small text-muted">
                      {{ service.type }}
                      <span v-if="service.image"> · <code>{{ service.image }}</code></span>
                    </div>
                  </td>
                  <td><span class="badge" :class="sourceClass(service.source)">{{ service.source }}</span></td>
                  <td><span class="badge" :class="statusClass(service.status)">{{ service.status }}</span></td>
                  <td class="small">
                    <div>{{ service.host || '—' }}</div>
                    <code>{{ formatPorts(service) }}</code>
                  </td>
                  <td class="text-end">
                    <div class="btn-group btn-group-sm">
                      <button class="btn btn-outline-secondary" @click="selected = service">
                        Details
                      </button>
                      <button
                        class="btn btn-outline-secondary"
                        :disabled="!service.logsAvailable || busyService === service.id"
                        @click="openLogs(service)">
                        Logs
                      </button>
                      <button
                        class="btn btn-outline-danger"
                        :disabled="!service.restartable || busyService === service.id"
                        @click="restart(service)">
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
          <div v-if="!selected" class="text-muted small">
            Select a service to inspect connection details.
          </div>
          <div v-else class="card">
            <div class="card-header d-flex justify-content-between align-items-start gap-2">
              <div>
                <strong>{{ selected.name }}</strong>
                <div class="small text-muted">{{ selected.note }}</div>
              </div>
              <span class="badge" :class="sourceClass(selected.source)">{{ selected.source }}</span>
            </div>
            <div class="card-body">
              <dl class="row small mb-3">
                <dt class="col-sm-4">Type</dt>
                <dd class="col-sm-8">{{ selected.type }}</dd>
                <dt class="col-sm-4">Image</dt>
                <dd class="col-sm-8"><code>{{ selected.image || '—' }}</code></dd>
                <dt class="col-sm-4">Host</dt>
                <dd class="col-sm-8"><code>{{ selected.host || '—' }}</code></dd>
                <dt class="col-sm-4">Ports</dt>
                <dd class="col-sm-8"><code>{{ formatPorts(selected) }}</code></dd>
              </dl>

              <h6>Connection details</h6>
              <div v-if="detailEntries(selected).length === 0" class="text-muted small">
                No connection detail values exposed.
              </div>
              <table v-else class="table table-sm">
                <tbody>
                  <tr v-for="[key, value] in detailEntries(selected)" :key="key">
                    <th class="text-nowrap small">{{ key }}</th>
                    <td><code>{{ value ?? '—' }}</code></td>
                  </tr>
                </tbody>
              </table>

              <template v-if="logs">
                <div class="d-flex justify-content-between align-items-center mt-3">
                  <h6 class="mb-0">Logs</h6>
                  <span v-if="logs.truncated" class="badge text-bg-warning">Tail {{ logs.maxBytes }} bytes</span>
                </div>
                <pre class="logs rounded border p-2 mt-2 mb-0"><code>{{ logs.logs || 'No logs returned.' }}</code></pre>
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
</style>
