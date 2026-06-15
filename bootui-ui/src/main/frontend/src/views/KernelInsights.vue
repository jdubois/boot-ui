<script setup>
import {apiFetch} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {formatClockTime} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)

const categoryClasses = {
  NETWORK: 'text-bg-primary',
  DNS: 'text-bg-info',
  PROCESS: 'text-bg-success',
  SOCKET: 'text-bg-warning'
}

const statusClasses = {
  OK: 'text-bg-success',
  ERROR: 'text-bg-danger',
  SKIPPED: 'text-bg-secondary'
}

const available = computed(() => report.value?.available === true)
const status = computed(() => report.value?.status || 'NOT_SCANNED')
const scanned = computed(() => status.value === 'SCANNED')
const gadgets = computed(() => report.value?.gadgets || [])
const totalEvents = computed(() => gadgets.value.reduce((sum, gadget) => sum + (gadget.eventCount || 0), 0))

function categoryClass(category) {
  return categoryClasses[category] || 'text-bg-light'
}

function statusClass(value) {
  return statusClasses[value] || 'text-bg-secondary'
}

function fieldEntries(fields) {
  return Object.entries(fields || {})
}

function scanTime() {
  if (!report.value?.scannedAt) return ''
  return formatClockTime(report.value.scannedAt)
}

async function loadStatus() {
  try {
    const res = await apiFetch('api/kernel-insights')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Kernel Insights')
  }
}

async function capture() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/kernel-insights/scan', {method: 'POST'})
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to capture kernel activity')
  } finally {
    loading.value = false
  }
}

function showReadOnlyMessage() {
  actionMessage.value = readOnlyReason.value
  setTimeout(() => {
    actionMessage.value = null
  }, 6000)
}

onMounted(loadStatus)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-motherboard"
      title="Kernel Insights"
      subtitle="Capture kernel-level activity (process exec, TCP, DNS, sockets) with Inspektor Gadget."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <SpinnerButton
          :loading="loading"
          :disabled="loading || readOnly || !available"
          class="btn btn-primary"
          icon="bi-soundwave"
          label="Capture"
          loading-label="Capturing…"
          @click="capture"
        />
      </template>
    </PanelHeader>

    <div v-if="actionMessage" class="alert alert-warning">{{ actionMessage }}</div>
    <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Captures are disabled.</ReadOnlyNotice>

    <template v-if="report">
      <UnavailableState v-if="status === 'UNAVAILABLE'" icon="bi-motherboard" variant="secondary">
        {{ report.message }} Kernel Insights needs a Linux host with the Inspektor Gadget <code>ig</code> binary
        installed and elevated privileges (eBPF).
      </UnavailableState>
      <UnavailableState v-else-if="status === 'DISABLED'" icon="bi-slash-circle" variant="warning">
        {{ report.message }}
      </UnavailableState>

      <div v-else>
        <div class="alert alert-info">
          <strong>On-demand kernel capture.</strong>
          BootUI runs the local <code>ig</code> binary for a few seconds only when you click Capture, then normalizes
          the events below. Nothing is captured on page load and nothing leaves this machine.
        </div>

        <div class="row g-3 mb-3">
          <div class="col-md-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Status</div>
                <div class="mt-2">
                  <span :class="scanned ? 'text-bg-success' : 'text-bg-secondary'" class="badge fs-6">{{
                    status
                  }}</span>
                </div>
                <div v-if="scanTime()" class="small text-muted mt-1">Captured at {{ scanTime() }}</div>
              </div>
            </div>
          </div>
          <div class="col-md-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Events captured</div>
                <div class="display-6">{{ totalEvents }}</div>
              </div>
            </div>
          </div>
          <div class="col-md-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Application PID</div>
                <div class="display-6">{{ report.currentPid }}</div>
              </div>
            </div>
          </div>
          <div class="col-md-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Inspektor Gadget</div>
                <div class="fw-semibold text-truncate" :title="report.igVersion || report.igPath">
                  {{ report.igVersion || report.igPath }}
                </div>
                <div class="small text-muted">{{ report.captureSeconds }}s capture window</div>
              </div>
            </div>
          </div>
        </div>

        <UnavailableState v-if="status === 'ERROR'" icon="bi-exclamation-triangle" variant="danger">
          {{ report.message }}
        </UnavailableState>

        <div v-if="!scanned" class="text-center text-muted py-5">
          <i class="bi bi-motherboard fs-1 d-block mb-2"></i>
          <div class="fw-semibold text-body">No capture yet</div>
          <div>Click <strong>Capture</strong> to snapshot a few seconds of kernel activity for this host.</div>
        </div>

        <div v-for="gadget in gadgets" v-else :key="gadget.gadget" class="card mb-3">
          <div class="card-header d-flex flex-wrap align-items-center gap-2">
            <span class="fw-semibold">{{ gadget.title }}</span>
            <span :class="categoryClass(gadget.category)" class="badge">{{ gadget.category }}</span>
            <code class="small text-muted">{{ gadget.gadget }}</code>
            <span :class="statusClass(gadget.status)" class="badge ms-auto">{{ gadget.status }}</span>
          </div>
          <div class="card-body py-2">
            <div class="text-muted small">{{ gadget.message }}</div>
          </div>
          <div v-if="gadget.events.length" class="table-responsive">
            <table class="table table-sm align-middle mb-0">
              <thead>
                <tr>
                  <th>Process</th>
                  <th>PID</th>
                  <th>Container</th>
                  <th>Summary</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(event, index) in gadget.events" :key="index">
                  <td>
                    <code v-if="event.comm">{{ event.comm }}</code>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td>{{ event.pid ?? '—' }}</td>
                  <td>
                    <span v-if="event.container">{{ event.container }}</span>
                    <span v-else class="text-muted">—</span>
                  </td>
                  <td>
                    <div>{{ event.summary || '—' }}</div>
                    <details v-if="fieldEntries(event.fields).length" class="small text-muted mt-1">
                      <summary>Details</summary>
                      <div class="kernel-fields mt-1">
                        <div v-for="[key, value] in fieldEntries(event.fields)" :key="key">
                          <code>{{ key }}</code> = {{ value }}
                        </div>
                      </div>
                    </details>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.kernel-fields {
  word-break: break-word;
}
</style>
