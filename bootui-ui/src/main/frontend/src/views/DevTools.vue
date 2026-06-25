<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, onUnmounted, ref} from 'vue'
import {formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const status = ref(null)
const actionLoading = ref(null)
const {message: banner, flash, clear} = useFlashMessage(8000)
const restarting = ref(false)
const lastFetched = ref(null)
let reconnectTimer = null

const restartReady = computed(() => status.value?.restartAvailable && !status.value?.restartPending)
const liveReloadReady = computed(() => status.value?.liveReloadAvailable)
const liveReloadConnections = computed(() => status.value?.liveReloadConnections ?? 0)
const liveReloadDisabled = computed(
  () =>
    !!status.value &&
    !status.value.liveReloadAvailable &&
    !/classpath/i.test(status.value.liveReloadUnavailableReason || '')
)
const autoRefreshEnabled = computed(() => !restarting.value)

async function fetchStatus() {
  try {
    status.value = await getJson('api/devtools')
    lastFetched.value = Date.now()
  } catch (e) {
    flash(formatLoadError(e, 'Could not load DevTools status'), 'danger')
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
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      await load()
      return
    }
    flash(result.message || 'LiveReload triggered.', result.status === 'triggered' ? 'success' : 'warning')
    await load()
  } catch (e) {
    flash(formatLoadError(e, 'Could not trigger LiveReload'), 'danger')
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
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      await load()
      return
    }
    restarting.value = true
    flash(result.message || 'Restart scheduled.', 'success')
    pollUntilOnline()
  } catch (e) {
    flash(formatLoadError(e, 'Could not schedule restart'), 'danger')
  } finally {
    actionLoading.value = null
  }
}

function pollUntilOnline() {
  clearReconnectTimer()
  reconnectTimer = setTimeout(async () => {
    try {
      const res = await apiFetch('api/devtools', {cache: 'no-store'})
      if (res.ok) {
        status.value = await res.json()
        lastFetched.value = Date.now()
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

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

const {autoRefresh, loading, load} = useAutoRefresh(fetchStatus, {enabled: autoRefreshEnabled})

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
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <FlashBanner :message="banner" with-icon @dismiss="clear" />

    <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">DevTools actions are read-only.</ReadOnlyNotice>

    <div v-if="restarting" class="alert alert-primary d-flex align-items-start gap-3">
      <div class="spinner-border spinner-border-sm mt-1" role="status"></div>
      <div>
        <strong>Restarting application…</strong>
        <div class="small">BootUI is polling until the local server responds again.</div>
      </div>
    </div>

    <div v-if="loading && !status" class="card">
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
              <span v-if="liveReloadReady"
                >&nbsp;&middot; Connected clients: <code>{{ liveReloadConnections }}</code></span
              >
            </div>

            <div
              v-if="liveReloadReady && liveReloadConnections === 0"
              class="alert alert-warning small d-flex align-items-start gap-2 py-2 px-3 mb-3"
              role="note"
            >
              <i class="bi bi-exclamation-triangle-fill mt-1"></i>
              <div>
                No browsers are connected to the LiveReload server, so triggering a reload has no visible effect. Spring
                Boot does not inject <code>livereload.js</code>: install the LiveReload browser extension and reload the
                page you want to refresh so it connects, then trigger again.
              </div>
            </div>

            <div
              v-if="liveReloadDisabled"
              class="alert alert-info small d-flex align-items-start gap-2 py-2 px-3 mb-3"
              role="note"
            >
              <i class="bi bi-info-circle-fill mt-1"></i>
              <div>
                Spring Boot 4 disables the DevTools LiveReload server by default. Set
                <code>spring.devtools.livereload.enabled=true</code> in your application properties and restart the app
                to enable it.
              </div>
            </div>

            <SpinnerButton
              :loading="actionLoading === 'livereload'"
              :disabled="readOnly || !liveReloadReady || actionLoading"
              class="btn btn-primary"
              icon="bi-broadcast"
              label="Trigger LiveReload"
              spinner-class="me-2"
              @click="triggerLiveReload"
            />
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

            <SpinnerButton
              :loading="actionLoading === 'restart'"
              :disabled="readOnly || !restartReady || actionLoading || restarting"
              class="btn btn-warning"
              icon="bi-arrow-clockwise"
              label="Restart app"
              spinner-class="me-2"
              @click="restart"
            />
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

    <UnavailableState
      v-else
      message="DevTools status is unavailable. The app may be restarting or unreachable — retry or refresh this panel."
    />
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
