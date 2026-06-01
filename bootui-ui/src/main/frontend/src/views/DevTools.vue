<script setup>
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {apiFetch} from '../api.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const status = ref(null)
const loading = ref(true)
const actionLoading = ref(null)
const banner = ref(null)
const restarting = ref(false)
const lastFetched = ref(null)
let reconnectTimer = null

const restartReady = computed(() => status.value?.restartAvailable && !status.value?.restartPending)
const liveReloadReady = computed(() => status.value?.liveReloadAvailable)

async function load() {
  loading.value = true
  try {
    const res = await fetch('api/devtools')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    status.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    flash('Could not load DevTools status: ' + e.message, 'danger')
  } finally {
    loading.value = false
  }
}

async function triggerLiveReload() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  actionLoading.value = 'livereload'
  try {
    const res = await apiFetch('api/devtools/livereload', {method: 'POST'})
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || 'HTTP ' + res.status, 'warning')
      await load()
      return
    }
    flash(result.message || 'LiveReload triggered.', 'success')
    await load()
  } catch (e) {
    flash('Could not trigger LiveReload: ' + e.message, 'danger')
  } finally {
    actionLoading.value = null
  }
}

async function restart() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (!confirm('Restart this Spring Boot application now? In-memory state and in-flight requests will be interrupted.'))
    return

  actionLoading.value = 'restart'
  try {
    const res = await apiFetch('api/devtools/restart', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({confirm: true})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || 'HTTP ' + res.status, 'warning')
      await load()
      return
    }
    restarting.value = true
    flash(result.message || 'Restart scheduled.', 'success')
    pollUntilOnline()
  } catch (e) {
    flash('Could not schedule restart: ' + e.message, 'danger')
  } finally {
    actionLoading.value = null
  }
}

function pollUntilOnline() {
  clearReconnectTimer()
  reconnectTimer = setTimeout(async () => {
    try {
      const res = await fetch('api/devtools', {cache: 'no-store'})
      if (res.ok) {
        status.value = await res.json()
        restarting.value = false
        flash('Application is available again.', 'success')
        return
      }
    } catch {
      // Expected while DevTools is restarting the application.
    }
    pollUntilOnline()
  }, 1500)
}

function clearReconnectTimer() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function flash(text, type) {
  banner.value = {text, type}
  setTimeout(() => {
    banner.value = null
  }, 8000)
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

onMounted(load)
onUnmounted(clearReconnectTimer)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-lightning-charge"
      title="DevTools"
      subtitle="Trigger Spring Boot DevTools LiveReload notifications or restart the local application."
      :loading="loading || restarting"
      :last-fetched="lastFetched"
      @refresh="load"
    />

    <div v-if="banner" :class="'alert-' + banner.type" class="alert d-flex justify-content-between align-items-center">
      <div>
        <i :class="banner.type === 'danger' ? 'bi-exclamation-triangle-fill' : 'bi-info-circle-fill'" class="bi"></i>
        <span class="ms-2">{{ banner.text }}</span>
      </div>
      <button class="btn-close" @click="banner = null"></button>
    </div>

    <div v-if="readOnly" class="alert alert-warning small">
      <i class="bi bi-lock me-1"></i>
      DevTools actions are read-only. {{ readOnlyReason }}
    </div>

    <div v-if="restarting" class="alert alert-primary d-flex align-items-start gap-3">
      <div class="spinner-border spinner-border-sm mt-1" role="status"></div>
      <div>
        <strong>Restarting application…</strong>
        <div class="small">BootUI is polling until the local server responds again.</div>
      </div>
    </div>

    <div v-if="loading" class="card">
      <div class="card-body text-muted">Loading DevTools status…</div>
    </div>

    <div v-else-if="status" class="row g-4">
      <div class="col-lg-6">
        <div class="card h-100 action-card">
          <div class="card-body p-4">
            <div class="d-flex align-items-start justify-content-between gap-3 mb-3">
              <div>
                <div class="action-icon bg-primary-subtle text-primary">
                  <i class="bi bi-broadcast"></i>
                </div>
                <h3 class="h5 fw-bold mt-3 mb-1">Trigger LiveReload</h3>
                <p class="text-muted small mb-0">
                  Sends a LiveReload event to browsers connected to Spring Boot DevTools.
                </p>
              </div>
              <span :class="liveReloadReady ? 'text-bg-success' : 'text-bg-secondary'" class="badge">
                {{ liveReloadReady ? 'Available' : 'Unavailable' }}
              </span>
            </div>

            <div class="small text-muted mb-3">
              <span v-if="status.liveReloadPort"
                >LiveReload port: <code>{{ status.liveReloadPort }}</code></span
              >
              <span v-else>{{ status.liveReloadUnavailableReason || 'No LiveReload port reported.' }}</span>
            </div>

            <button
              :disabled="readOnly || !liveReloadReady || actionLoading"
              class="btn btn-primary"
              @click="triggerLiveReload"
            >
              <span v-if="actionLoading === 'livereload'" class="spinner-border spinner-border-sm me-2"></span>
              <i v-else class="bi bi-broadcast me-1"></i>
              Trigger LiveReload
            </button>
          </div>
        </div>
      </div>

      <div class="col-lg-6">
        <div class="card h-100 action-card border-warning-subtle">
          <div class="card-body p-4">
            <div class="d-flex align-items-start justify-content-between gap-3 mb-3">
              <div>
                <div class="action-icon bg-warning-subtle text-warning-emphasis">
                  <i class="bi bi-arrow-clockwise"></i>
                </div>
                <h3 class="h5 fw-bold mt-3 mb-1">Restart application</h3>
                <p class="text-muted small mb-0">Schedules a DevTools restart after the API response is sent.</p>
              </div>
              <span :class="restartReady ? 'text-bg-success' : 'text-bg-secondary'" class="badge">
                {{ status.restartPending ? 'Pending' : restartReady ? 'Available' : 'Unavailable' }}
              </span>
            </div>

            <div class="small text-muted mb-3">
              {{ status.restartUnavailableReason || 'Spring Boot DevTools restart is initialized.' }}
            </div>

            <button
              :disabled="readOnly || !restartReady || actionLoading || restarting"
              class="btn btn-warning"
              @click="restart"
            >
              <span v-if="actionLoading === 'restart'" class="spinner-border spinner-border-sm me-2"></span>
              <i v-else class="bi bi-arrow-clockwise me-1"></i>
              Restart app
            </button>
          </div>
        </div>
      </div>

      <div class="col-12">
        <div class="alert alert-info small mb-0">
          <strong>Note:</strong>
          LiveReload notifies connected browser tooling; it does not force this BootUI tab to reload. Restart interrupts
          the current JVM context and is intended for local development only.
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.action-card {
  min-height: 17rem;
}

.action-icon {
  align-items: center;
  border-radius: 1rem;
  display: inline-flex;
  font-size: 1.5rem;
  height: 3rem;
  justify-content: center;
  width: 3rem;
}

.spin {
  animation: spin 900ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
