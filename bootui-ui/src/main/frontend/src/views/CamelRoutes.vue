<script setup>
import {computed, onBeforeUnmount, onMounted, reactive, ref} from 'vue'
import {apiFetch} from '../api.js'

const report = ref(null)
const diagramImage = ref(null)
const error = ref(null)
const banner = ref(null)
const selectedRoute = ref(null)
const lastUpdated = ref(null)
let polling = true
let routesController = null
let diagramController = null
let bannerTimeout = null

const selectedRouteData = computed(() =>
  report.value?.routes?.find((r) => r.routeId === selectedRoute.value) || null
)

function statusBadge(status) {
  if (status === 'Started') return 'text-bg-success'
  if (status === 'Stopped') return 'text-bg-danger'
  if (status === 'Suspended') return 'text-bg-warning'
  if (status === 'Stopping') return 'text-bg-info'
  return 'text-bg-secondary'
}

function lastUpdatedText() {
  if (!lastUpdated.value) return ''
  return lastUpdated.value.toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

function showBanner(obj, autoDismiss) {
  banner.value = obj
  clearTimeout(bannerTimeout)
  if (autoDismiss) {
    bannerTimeout = setTimeout(() => {
      banner.value = null
    }, 4000)
  }
}

async function loadRoutes() {
  routesController?.abort()
  routesController = new AbortController()
  try {
    const res = await apiFetch('api/camel/routes', {signal: routesController.signal})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const data = await res.json()
    for (const route of data.routes || []) {
      if (stoppingRoutes.has(route.routeId)) {
        if (route.status === 'Stopped') {
          stoppingRoutes.delete(route.routeId)
        } else {
          route.status = 'Stopping'
        }
      }
    }
    report.value = data
    error.value = null
  } catch (e) {
    if (e.name === 'AbortError') return
    error.value = e.message
  }
}

async function loadDiagram() {
  if (!report.value?.diagramAvailable) {
    diagramImage.value = null
    return
  }
  diagramController?.abort()
  diagramController = new AbortController()
  try {
    const params = new URLSearchParams({theme: 'light', metric: 'true'})
    if (selectedRoute.value) params.set('filter', selectedRoute.value)
    const res = await apiFetch(`api/camel/routes/diagram?${params}`, {signal: diagramController.signal})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const data = await res.json()
    diagramImage.value = data.image || null
  } catch (e) {
    if (e.name === 'AbortError') return
    diagramImage.value = null
  }
}

async function refresh() {
  await loadRoutes()
  await loadDiagram()
  lastUpdated.value = new Date()
}

function selectRoute(routeId) {
  selectedRoute.value = selectedRoute.value === routeId ? null : routeId
  zoom.value = 1
  loadDiagram()
}

const zoom = ref(1)

function zoomIn() {
  zoom.value = Math.min(zoom.value + 0.25, 4)
}

function zoomOut() {
  zoom.value = Math.max(zoom.value - 0.25, 0.25)
}

function zoomReset() {
  zoom.value = 1
}

const pendingActions = reactive(new Set())
const stoppingRoutes = reactive(new Set())

async function doLifecycle(routeId, action) {
  const key = `${routeId}:${action}`
  if (pendingActions.has(key)) return
  pendingActions.add(key)
  try {
    const lc = new AbortController()
    const timeout = setTimeout(() => lc.abort(), 15000)
    const res = await apiFetch(`api/camel/routes/${routeId}/${action}`, {method: 'POST', signal: lc.signal})
    clearTimeout(timeout)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const result = await res.json()
    if (result.status === 'error') {
      showBanner({type: 'danger', text: `${action} failed on ${routeId}: ${result.message}`}, true)
    } else if (result.routeStatus === 'Stopping') {
      stoppingRoutes.add(routeId)
      if (report.value) {
        const route = report.value.routes.find((r) => r.routeId === routeId)
        if (route) route.status = 'Stopping'
      }
      showBanner({type: 'info', text: `Stopping route ${routeId}…`}, false)
    } else {
      showBanner({type: 'success', text: `Route ${routeId} ${action} successful`}, true)
      await refresh()
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      showBanner({type: 'warning', text: `${action} request timed out for ${routeId}`}, true)
    } else {
      showBanner({type: 'danger', text: `${action} failed on ${routeId}: ${e.message}`}, true)
    }
  } finally {
    pendingActions.delete(key)
  }
}

async function pollLoop() {
  while (polling) {
    await new Promise((r) => setTimeout(r, 5000))
    if (!polling) break
    if (document.visibilityState !== 'hidden') {
      await refresh()
    }
  }
}

onMounted(async () => {
  await refresh()
  pollLoop()
})

onBeforeUnmount(() => {
  polling = false
  routesController?.abort()
  diagramController?.abort()
  clearTimeout(bannerTimeout)
})
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
      <div>
        <h3 class="h4 mb-1"><i class="bi bi-signpost-split me-2"></i>Camel Routes</h3>
        <p v-if="report" class="text-muted mb-0">
          Context: {{ report.contextStatus }} · v{{ report.camelVersion }} · {{ report.total }} route{{
            report.total === 1 ? '' : 's'
          }}
        </p>
      </div>
      <div class="d-flex align-items-center gap-2">
        <span v-if="lastUpdated" class="text-muted small">
          <i class="bi bi-arrow-repeat me-1"></i>Updated {{ lastUpdatedText() }}
        </span>
        <button class="btn btn-sm btn-outline-secondary" @click="refresh">
          <i class="bi bi-arrow-clockwise"></i> Refresh
        </button>
      </div>
    </div>

    <div v-if="banner" :class="'alert-' + banner.type" class="alert d-flex justify-content-between align-items-center">
      <div>{{ banner.text }}</div>
      <button class="btn-close" @click="banner = null"></button>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-if="report">
      <div v-if="report.stats" class="row g-2 mb-3">
        <div class="col">
          <div class="card text-center p-2">
            <div class="fs-5 fw-semibold">{{ report.stats.exchangesTotal.toLocaleString() }}</div>
            <div class="text-muted small">Exchanges</div>
          </div>
        </div>
        <div class="col">
          <div class="card text-center p-2">
            <div :class="report.stats.exchangesFailed > 0 ? 'text-danger' : ''" class="fs-5 fw-semibold">
              {{ report.stats.exchangesFailed.toLocaleString() }}
            </div>
            <div class="text-muted small">Failed</div>
          </div>
        </div>
        <div class="col">
          <div class="card text-center p-2">
            <div class="fs-5 fw-semibold">{{ report.stats.exchangesInflight }}</div>
            <div class="text-muted small">Inflight</div>
          </div>
        </div>
        <div class="col">
          <div class="card text-center p-2">
            <div class="fs-5 fw-semibold">{{ report.stats.meanProcessingTime }} ms</div>
            <div class="text-muted small">Mean time</div>
          </div>
        </div>
        <div class="col">
          <div class="card text-center p-2">
            <div class="fs-5 fw-semibold">{{ report.stats.maxProcessingTime }} ms</div>
            <div class="text-muted small">Max time</div>
          </div>
        </div>
      </div>

      <div v-if="!report.stats" class="alert alert-info small mb-3">
        <i class="bi bi-info-circle me-1"></i>Add <code>camel-management-starter</code> and set
        <code>spring.jmx.enabled=true</code> to see exchange metrics.
      </div>

      <div v-if="!report.diagramAvailable" class="alert alert-info small mb-3">
        <i class="bi bi-info-circle me-1"></i>Add <code>camel-diagram</code>,
        <code>camel-console-starter</code> and set <code>camel.main.dev-console-enabled=true</code>
        to see route diagrams.
      </div>

      <div class="row g-3">
        <div class="col-lg-3">
          <div class="card">
            <div class="card-header fw-semibold">Routes</div>
            <div class="list-group list-group-flush route-list">
              <button
                v-for="r in report.routes"
                :key="r.routeId"
                :class="{active: r.routeId === selectedRoute}"
                class="list-group-item list-group-item-action d-flex justify-content-between align-items-center"
                type="button"
                @click="selectRoute(r.routeId)"
              >
                <div class="text-truncate me-2">
                  <div class="fw-semibold">{{ r.routeId }}</div>
                  <div v-if="r.from" class="small text-muted text-truncate">{{ r.from }}</div>
                </div>
                <span :class="statusBadge(r.status)" class="badge">{{ r.status }}</span>
              </button>
            </div>
          </div>
        </div>

        <div class="col-lg-9">
          <div v-if="selectedRoute" class="mb-2 d-flex gap-2">
            <button class="btn btn-sm btn-outline-success" :disabled="pendingActions.size > 0"
                    @click="doLifecycle(selectedRoute, 'start')">
              <i class="bi bi-play-fill"></i> Start
            </button>
            <button class="btn btn-sm btn-outline-danger" :disabled="pendingActions.size > 0"
                    @click="doLifecycle(selectedRoute, 'stop')">
              <i class="bi bi-stop-fill"></i> Stop
            </button>
            <button class="btn btn-sm btn-outline-warning" :disabled="pendingActions.size > 0"
                    @click="doLifecycle(selectedRoute, 'suspend')">
              <i class="bi bi-pause-fill"></i> Suspend
            </button>
            <button class="btn btn-sm btn-outline-primary" :disabled="pendingActions.size > 0"
                    @click="doLifecycle(selectedRoute, 'resume')">
              <i class="bi bi-skip-forward-fill"></i> Resume
            </button>
          </div>

          <template v-if="selectedRouteData">
            <div class="card mb-3">
              <div class="card-header d-flex justify-content-between align-items-center">
                <span>Route Details</span>
                <span class="badge text-bg-primary">{{ selectedRouteData.routeId }}</span>
              </div>
              <div class="card-body">
                <table class="table table-sm mb-0">
                  <tbody>
                    <tr>
                      <th class="text-muted" style="width: 120px">Route ID</th>
                      <td>{{ selectedRouteData.routeId }}</td>
                    </tr>
                    <tr>
                      <th class="text-muted">From</th>
                      <td><code>{{ selectedRouteData.from }}</code></td>
                    </tr>
                    <tr>
                      <th class="text-muted">Status</th>
                      <td><span :class="statusBadge(selectedRouteData.status)" class="badge">{{ selectedRouteData.status }}</span></td>
                    </tr>
                    <tr>
                      <th class="text-muted">Uptime</th>
                      <td>{{ selectedRouteData.uptime }}</td>
                    </tr>
                    <tr v-if="selectedRouteData.description">
                      <th class="text-muted">Description</th>
                      <td>{{ selectedRouteData.description }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div v-if="diagramImage" class="card">
              <div class="card-header d-flex justify-content-between align-items-center">
                <span>Route Diagram</span>
                <div class="d-flex align-items-center gap-2">
                  <button class="btn btn-sm btn-outline-secondary" @click="zoomIn">
                    <i class="bi bi-zoom-in"></i>
                  </button>
                  <span class="text-muted small">{{ Math.round(zoom * 100) }}%</span>
                  <button class="btn btn-sm btn-outline-secondary" @click="zoomOut">
                    <i class="bi bi-zoom-out"></i>
                  </button>
                  <button class="btn btn-sm btn-outline-secondary" @click="zoomReset">
                    <i class="bi bi-arrows-fullscreen"></i>
                  </button>
                  <span class="badge text-bg-primary">{{ selectedRouteData.routeId }}</span>
                </div>
              </div>
              <div class="card-body diagram-container">
                <img :src="'data:image/png;base64,' + diagramImage" alt="Route diagram" class="diagram-img"
                     :style="{transform: `scale(${zoom})`, transformOrigin: 'top left'}"/>
              </div>
            </div>
          </template>

          <div v-else-if="!selectedRoute" class="alert alert-info small">
            <i class="bi bi-info-circle me-1"></i>Select a route from the list to view its details.
          </div>
        </div>
      </div>
    </template>

    <div v-else-if="!error" class="text-muted">Loading Camel routes…</div>
  </div>
</template>

<style scoped>
.route-list {
  max-height: 36rem;
  overflow: auto;
}

.diagram-container {
  overflow: scroll;
  max-height: 70vh;
  scrollbar-gutter: stable both-edges;
}

.diagram-container::-webkit-scrollbar {
  width: 12px;
  height: 12px;
}

.diagram-container::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 6px;
}

.diagram-container::-webkit-scrollbar-thumb {
  background: #adb5bd;
  border-radius: 6px;
}

.diagram-container::-webkit-scrollbar-thumb:hover {
  background: #6c757d;
}

.diagram-img {
  max-width: 100%;
  height: auto;
}
</style>
