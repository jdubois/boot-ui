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

async function fetchRabbit() {
  error.value = null
  try {
    report.value = await getJson('api/rabbitmq')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load captured RabbitMQ activity')
  }
}

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchRabbit, {
  enabled: manifestAvailable,
  initialLoading: false
})

const messages = computed(() => report.value?.messages ?? [])

const filteredMessages = computed(() => {
  const direction = directionFilter.value
  const v = filter.value.trim().toLowerCase()
  return messages.value.filter((m) => {
    if (direction && m.direction !== direction) return false
    if (!v) return true
    return [m.exchange, m.routingKey, m.queue, m.correlationId, m.errorMessage].join(' ').toLowerCase().includes(v)
  })
})

// Whether the panel manifest already knows RabbitMQ can't be used (no RabbitTemplate bean / no
// quarkus-messaging-rabbitmq, or explicitly disabled): the backing endpoint may not even be wired
// in that case, so fetchRabbit() is gated on manifestAvailable via useAutoRefresh and never runs —
// avoiding a 404 in favor of this manifest-driven explanation.
const available = computed(() => manifestAvailable.value && report.value?.available !== false)
const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'RabbitMQ capture is unavailable.'
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
  return direction === 'PUBLISH' ? 'bi-arrow-up-right text-primary' : 'bi-arrow-down-left text-success'
}

function directionLabel(direction) {
  return direction === 'PUBLISH' ? 'Published' : 'Consumed'
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

async function clearAll() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (
    !(await confirm({
      title: 'Clear captured RabbitMQ activity?',
      message: 'Clear every captured publisher/consumer message from the in-memory buffer.',
      confirmLabel: 'Clear all',
      danger: true,
      irreversible: true
    }))
  )
    return
  busy.value = true
  try {
    const res = await apiFetch('api/rabbitmq', {method: 'DELETE'})
    if (!res.ok && res.status !== 204) throw new Error(`HTTP ${res.status}`)
    await load()
    flash('Cleared captured RabbitMQ activity.', 'success')
  } catch (e) {
    show(formatLoadError(e, 'Could not clear captured RabbitMQ activity'), 'danger')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-envelope-paper"
      title="RabbitMQ"
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
        <strong>RabbitMQ capture is unavailable.</strong>
        <span class="d-block small">{{ unavailableReason }}</span>
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason"
          >Clearing captured RabbitMQ activity is read-only.</ReadOnlyNotice
        >

        <div v-if="!report.capturing" class="alert alert-secondary small py-2">
          RabbitMQ capture is currently disabled (<code>bootui.rabbitmq.enabled=false</code>); messages captured before
          it was disabled remain below.
        </div>

        <div v-if="!report.captureCorrelationIdEnabled" class="alert alert-secondary small py-2">
          Correlation ID hashes are not being captured. Set <code>bootui.rabbitmq.capture-correlation-id=true</code> to
          include a short hash of each message's AMQP correlation ID.
        </div>

        <div v-if="report.total === 0" class="alert alert-secondary">
          No RabbitMQ activity captured yet. Send or consume a message through the application's
          <code>RabbitTemplate</code>/<code>@RabbitListener</code> or SmallRye <code>@Outgoing</code>/<code
            >@Incoming</code
          >
          integration and refresh this panel.
        </div>

        <template v-else>
          <div class="mb-3 d-flex gap-2 flex-wrap">
            <input
              v-model="filter"
              class="form-control form-control-sm rabbit-filter-input"
              aria-label="Filter RabbitMQ activity"
              placeholder="Filter by exchange, routing key, queue, or correlation ID…"
            />
            <select
              v-model="directionFilter"
              class="form-select form-select-sm rabbit-direction-select"
              aria-label="Filter RabbitMQ activity by direction"
            >
              <option value="">All directions</option>
              <option value="PUBLISH">Published</option>
              <option value="CONSUME">Consumed</option>
            </select>
          </div>

          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col"><span class="visually-hidden">Direction</span></th>
                  <th scope="col">Exchange</th>
                  <th scope="col">Routing Key</th>
                  <th scope="col">Queue</th>
                  <th scope="col">Correlation ID</th>
                  <th scope="col">Duration</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="m in filteredMessages" :key="m.id">
                  <td class="text-muted small text-nowrap">{{ formatTimestamp(m.timestamp) }}</td>
                  <td class="text-center" :title="directionLabel(m.direction)">
                    <i class="bi" :class="directionIcon(m.direction)" aria-hidden="true"></i>
                    <span class="visually-hidden">{{ directionLabel(m.direction) }}</span>
                  </td>
                  <td class="text-truncate rabbit-cell fw-semibold font-monospace">{{ m.exchange || '—' }}</td>
                  <td class="text-truncate rabbit-cell font-monospace">{{ m.routingKey || '—' }}</td>
                  <td class="text-truncate rabbit-cell font-monospace small">{{ m.queue || '—' }}</td>
                  <td class="text-truncate rabbit-key-cell font-monospace small">{{ m.correlationId || '—' }}</td>
                  <td class="text-nowrap">
                    {{ m.durationMillis != null ? `${formatNumber(m.durationMillis)} ms` : '—' }}
                  </td>
                  <td>
                    <span v-if="m.success" class="badge text-bg-success">ok</span>
                    <span v-else class="badge text-bg-danger" :title="m.errorMessage || 'Failed'">error</span>
                  </td>
                </tr>
                <tr v-if="!filteredMessages.length">
                  <td class="text-center text-muted py-4" colspan="8">
                    No captured RabbitMQ activity matches your filter.
                  </td>
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
.rabbit-cell {
  max-width: 200px;
}

.rabbit-key-cell {
  max-width: 140px;
}

.rabbit-filter-input {
  max-width: 320px;
}

.rabbit-direction-select {
  max-width: 160px;
}
</style>
