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
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, show, clear} = useFlashMessage(4000)
const filter = ref('')
const directionFilter = ref('')
const busy = ref(false)
const lastFetched = ref(null)

async function fetchKafka() {
  error.value = null
  try {
    report.value = await getJson('api/kafka')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load captured Kafka activity')
  }
}

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const {autoRefresh, loading, load} = useAutoRefresh(fetchKafka)

const messages = computed(() => report.value?.messages ?? [])

const filteredMessages = computed(() => {
  const direction = directionFilter.value
  const v = filter.value.trim().toLowerCase()
  return messages.value.filter((m) => {
    if (direction && m.direction !== direction) return false
    if (!v) return true
    return [m.topic, m.key, m.groupId, m.listenerId, m.errorMessage].join(' ').toLowerCase().includes(v)
  })
})

const subtitle = computed(() => {
  if (!report.value || !report.value.available) return null
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
      title: 'Clear captured Kafka activity?',
      message: 'Clear every captured producer/consumer message from the in-memory buffer.',
      confirmLabel: 'Clear all',
      danger: true,
      irreversible: true
    }))
  )
    return
  busy.value = true
  try {
    const res = await apiFetch('api/kafka', {method: 'DELETE'})
    if (!res.ok && res.status !== 204) throw new Error(`HTTP ${res.status}`)
    await load()
    flash('Cleared captured Kafka activity.', 'success')
  } catch (e) {
    show(formatLoadError(e, 'Could not clear captured Kafka activity'), 'danger')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-inboxes"
      title="Kafka"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
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

    <PanelSkeleton v-if="loading && !report" />

    <template v-else-if="report">
      <div v-if="!report.available" class="alert alert-warning">
        <strong>Kafka capture is unavailable.</strong>
        <span class="d-block small">{{ report.unavailableReason }}</span>
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason"
          >Clearing captured Kafka activity is read-only.</ReadOnlyNotice
        >

        <div v-if="!report.capturing" class="alert alert-secondary small py-2">
          Kafka capture is currently disabled (<code>bootui.kafka.enabled=false</code>); messages captured before it was
          disabled remain below.
        </div>

        <div v-if="!report.captureKeyEnabled" class="alert alert-secondary small py-2">
          Key hashes are not being captured. Set <code>bootui.kafka.capture-key=true</code> to include a short hash of
          each record's key.
        </div>

        <div v-if="report.total === 0" class="alert alert-secondary">
          No Kafka activity captured yet. Send or consume a message through the application's
          <code>KafkaTemplate</code>/<code>@KafkaListener</code> integration and refresh this panel.
        </div>

        <template v-else>
          <div class="mb-3 d-flex gap-2 flex-wrap">
            <input
              v-model="filter"
              class="form-control form-control-sm kafka-filter-input"
              placeholder="Filter by topic, key, group, or listener…"
            />
            <select v-model="directionFilter" class="form-select form-select-sm kafka-direction-select">
              <option value="">All directions</option>
              <option value="PRODUCE">Produced</option>
              <option value="CONSUME">Consumed</option>
            </select>
          </div>

          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Time</th>
                  <th></th>
                  <th>Topic</th>
                  <th>Partition</th>
                  <th>Offset</th>
                  <th>Key hash</th>
                  <th>Duration</th>
                  <th>Status</th>
                  <th>Group / Listener</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="m in filteredMessages" :key="m.id">
                  <td class="text-muted small text-nowrap">{{ formatTimestamp(m.timestamp) }}</td>
                  <td class="text-center" :title="directionLabel(m.direction)">
                    <i class="bi" :class="directionIcon(m.direction)"></i>
                  </td>
                  <td class="text-truncate kafka-topic-cell fw-semibold font-monospace">{{ m.topic }}</td>
                  <td>{{ m.partition ?? '—' }}</td>
                  <td>{{ m.offset ?? '—' }}</td>
                  <td class="text-truncate kafka-key-cell font-monospace small">{{ m.key || '—' }}</td>
                  <td class="text-nowrap">
                    {{ m.durationMillis != null ? `${formatNumber(m.durationMillis)} ms` : '—' }}
                  </td>
                  <td>
                    <span v-if="m.success" class="badge text-bg-success">ok</span>
                    <span v-else class="badge text-bg-danger" :title="m.errorMessage || 'Failed'">error</span>
                  </td>
                  <td class="text-truncate small">
                    <template v-if="m.groupId || m.listenerId">
                      <span v-if="m.groupId">{{ m.groupId }}</span>
                      <span v-if="m.groupId && m.listenerId"> / </span>
                      <span v-if="m.listenerId">{{ m.listenerId }}</span>
                    </template>
                    <span v-else class="text-muted">—</span>
                  </td>
                </tr>
                <tr v-if="!filteredMessages.length">
                  <td class="text-center text-muted py-4" colspan="9">
                    No captured Kafka activity matches your filter.
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
.kafka-topic-cell {
  max-width: 260px;
}

.kafka-key-cell {
  max-width: 160px;
}

.kafka-filter-input {
  max-width: 320px;
}

.kafka-direction-select {
  max-width: 160px;
}
</style>
