<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, show, clear} = useFlashMessage(4000)
const filter = ref('')
const directionFilter = ref('')
const busy = ref(false)
const lastFetched = ref(null)

async function fetchJms() {
  error.value = null
  try {
    report.value = await getJson('api/jms')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load captured JMS activity')
  }
}

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchJms, {
  enabled: manifestAvailable,
  initialLoading: false
})

const messages = computed(() => report.value?.messages ?? [])

const filteredMessages = computed(() => {
  const direction = directionFilter.value
  const value = filter.value.trim().toLowerCase()
  return messages.value.filter((message) => {
    if (direction && message.direction !== direction) return false
    if (!value) return true
    return [message.destination, message.messageId, message.subscriptionName, message.listenerId, message.failureType]
      .join(' ')
      .toLowerCase()
      .includes(value)
  })
})

const available = computed(() => manifestAvailable.value && report.value?.available !== false)
const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'JMS capture is unavailable.'
})

const subtitle = computed(() => {
  if (!available.value || !report.value) return null
  const parts = [
    `${formatNumber(report.value.total)} retained`,
    `${formatNumber(report.value.totalCaptured)} captured since startup`
  ]
  parts.push(report.value.capturing ? 'capturing' : 'capture disabled')
  return parts.join(' · ')
})

function formatTimestamp(timestamp) {
  if (!timestamp) return '—'
  return formatClockTime(timestamp)
}

function directionIcon(direction) {
  return direction === 'PRODUCE' ? 'bi-arrow-up-right text-primary' : 'bi-arrow-down-left text-success'
}

function directionLabel(direction) {
  return direction === 'PRODUCE' ? 'Produced' : 'Consumed'
}

async function clearAll() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (
    !(await confirm({
      title: 'Clear captured JMS activity?',
      message: 'Clear every captured producer/consumer message from the in-memory buffer.',
      confirmLabel: 'Clear all',
      danger: true,
      irreversible: true
    }))
  )
    return
  busy.value = true
  try {
    const response = await apiFetch('api/jms', {method: 'DELETE'})
    if (!response.ok && response.status !== 204) throw new Error(`HTTP ${response.status}`)
    await load()
    flash('Cleared captured JMS activity.', 'success')
  } catch (e) {
    show(formatLoadError(e, 'Could not clear captured JMS activity'), 'danger')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-mailbox"
      title="JMS"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      :auto-refreshable="manifestAvailable"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy"
          :disabled="!report || !report.available || readOnly || !report.total || busy"
          class="btn btn-sm btn-outline-danger"
          icon="bi-trash"
          label="Clear"
          @click="clearAll"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />

    <template v-else-if="!manifestAvailable || report">
      <div v-if="!available" class="alert alert-warning">
        <strong>JMS capture is unavailable.</strong>
        <span class="d-block small">{{ unavailableReason }}</span>
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason"
          >Clearing captured JMS activity is read-only.</ReadOnlyNotice
        >

        <div v-if="!report.capturing" class="alert alert-secondary small py-2">
          JMS capture is currently disabled (<code>bootui.jms.enabled=false</code>); messages captured before it was
          disabled remain below.
        </div>

        <div v-if="!report.captureMessageIdEnabled" class="alert alert-secondary small py-2">
          Message ID hashes are not being captured. Set <code>bootui.jms.capture-message-id=true</code> to include a
          short hash of each provider-assigned message ID.
        </div>

        <div v-if="report.total === 0" class="alert alert-secondary">
          No JMS activity captured yet. Send or consume a message through the application's
          <code>JmsTemplate</code>/<code>@JmsListener</code> integration and refresh this panel.
        </div>

        <template v-else>
          <div class="mb-3 d-flex gap-2 flex-wrap">
            <input
              v-model="filter"
              class="form-control form-control-sm jms-filter-input"
              aria-label="Filter JMS activity"
              placeholder="Filter by destination, message ID, subscription, or listener…"
            />
            <select
              v-model="directionFilter"
              class="form-select form-select-sm jms-direction-select"
              aria-label="Filter JMS activity by direction"
            >
              <option value="">All directions</option>
              <option value="PRODUCE">Produced</option>
              <option value="CONSUME">Consumed</option>
            </select>
          </div>

          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col"><span class="visually-hidden">Direction</span></th>
                  <th scope="col">Destination</th>
                  <th scope="col">Message ID</th>
                  <th scope="col">Duration</th>
                  <th scope="col">Status</th>
                  <th scope="col">Subscription / Listener</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="message in filteredMessages" :key="message.id">
                  <td class="text-muted small text-nowrap">{{ formatTimestamp(message.timestamp) }}</td>
                  <td class="text-center" :title="directionLabel(message.direction)">
                    <i class="bi" :class="directionIcon(message.direction)" aria-hidden="true"></i>
                    <span class="visually-hidden">{{ directionLabel(message.direction) }}</span>
                  </td>
                  <td class="text-truncate jms-destination-cell fw-semibold font-monospace">
                    {{ message.destination || '—' }}
                  </td>
                  <td class="text-truncate jms-message-id-cell font-monospace small">{{ message.messageId || '—' }}</td>
                  <td class="text-nowrap">
                    {{ message.durationMillis != null ? `${formatNumber(message.durationMillis)} ms` : '—' }}
                  </td>
                  <td>
                    <span v-if="message.success" class="badge text-bg-success">ok</span>
                    <span v-else class="badge text-bg-danger" :title="message.failureType || 'Failed'">error</span>
                  </td>
                  <td class="text-truncate jms-listener-cell small">
                    <template v-if="message.subscriptionName || message.listenerId">
                      <span v-if="message.subscriptionName" class="font-monospace">{{ message.subscriptionName }}</span>
                      <span v-if="message.subscriptionName && message.listenerId"> / </span>
                      <span v-if="message.listenerId" class="font-monospace">{{ message.listenerId }}</span>
                    </template>
                    <span v-else class="text-muted">—</span>
                  </td>
                </tr>
                <tr v-if="!filteredMessages.length">
                  <td class="text-center text-muted py-4" colspan="7">No captured JMS activity matches your filter.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>
    </template>
  </div>
</template>

<style scoped>
.jms-destination-cell {
  max-width: 260px;
}

.jms-message-id-cell {
  max-width: 180px;
}

.jms-listener-cell {
  max-width: 240px;
}

.jms-filter-input {
  max-width: 360px;
}

.jms-direction-select {
  max-width: 160px;
}
</style>
