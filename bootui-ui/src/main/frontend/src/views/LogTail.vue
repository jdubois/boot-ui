<script setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {formatClockTime} from '../utils/format.js'

const MAX_LINES = 2000

const lines = ref([])
const textFilter = ref('')
const levelFilter = ref('ALL')
const autoScroll = ref(true)
const status = ref('Paused')
const pane = ref(null)
let eventSource = null

const levelRank = {
  TRACE: 0,
  DEBUG: 1,
  INFO: 2,
  WARN: 3,
  ERROR: 4
}

const levelThreshold = {
  ALL: -1,
  'INFO+': levelRank.INFO,
  'WARN+': levelRank.WARN,
  ERROR: levelRank.ERROR
}

const visibleLines = computed(() => {
  const filter = textFilter.value.trim().toLowerCase()
  const threshold = levelThreshold[levelFilter.value] ?? -1

  return lines.value.filter((line) => {
    const rank = levelRank[line.level] ?? -1
    const logger = (line.logger || '').toLowerCase()
    const message = (line.message || '').toLowerCase()
    const matchesLevel = threshold < 0 || rank >= threshold
    const matchesText = !filter || logger.startsWith(filter) || message.includes(filter)

    return matchesLevel && matchesText
  })
})

const statusClass = computed(
  () =>
    ({
      Connected: 'text-bg-success',
      Paused: 'text-bg-secondary',
      Disconnected: 'text-bg-danger'
    })[status.value] || 'text-bg-secondary'
)

function connect() {
  disconnect(false)
  status.value = 'Disconnected'
  eventSource = new EventSource('api/logs/stream')
  eventSource.onopen = () => {
    status.value = 'Connected'
  }
  eventSource.addEventListener('log', (event) => {
    const line = JSON.parse(event.data)
    lines.value.push(line)
    if (lines.value.length > MAX_LINES) {
      lines.value.splice(0, lines.value.length - MAX_LINES)
    }
  })
  eventSource.onerror = () => {
    status.value = 'Disconnected'
  }
}

function disconnect(paused = true) {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (paused) {
    status.value = 'Paused'
  }
}

function toggleStreaming() {
  if (status.value === 'Connected') {
    disconnect(true)
    return
  }
  connect()
}

function clearLines() {
  lines.value = []
}

function levelClass(level) {
  return (
    {
      ERROR: 'text-danger',
      WARN: 'text-warning',
      INFO: 'text-success',
      DEBUG: 'text-info',
      TRACE: 'text-secondary'
    }[level] || 'text-light'
  )
}

async function scrollToBottom() {
  if (!autoScroll.value || !pane.value) {
    return
  }
  await nextTick()
  pane.value.scrollTop = pane.value.scrollHeight
}

watch(visibleLines, scrollToBottom)
watch(autoScroll, (enabled) => {
  if (enabled) {
    scrollToBottom()
  }
})

onMounted(connect)
onBeforeUnmount(() => disconnect(false))
</script>

<template>
  <div>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-3 mb-3">
      <h2 class="mb-0"><i class="bi bi-terminal me-2"></i>Log Tail</h2>
      <span :class="statusClass" class="badge">{{ status }}</span>
    </div>

    <div class="row g-3 mb-3 align-items-end">
      <div class="col-lg-4">
        <label class="form-label">Filter</label>
        <input v-model="textFilter" class="form-control" placeholder="Logger prefix or message text" />
      </div>
      <div class="col-sm-4 col-lg-2">
        <label class="form-label">Level</label>
        <select v-model="levelFilter" class="form-select">
          <option value="ALL">All</option>
          <option value="INFO+">Info+</option>
          <option value="WARN+">Warn+</option>
          <option value="ERROR">Error</option>
        </select>
      </div>
      <div class="col-sm-4 col-lg-2">
        <div class="form-check pt-sm-4">
          <input id="auto-scroll" v-model="autoScroll" class="form-check-input" type="checkbox" />
          <label class="form-check-label" for="auto-scroll">Auto-scroll</label>
        </div>
      </div>
      <div class="col-sm-4 col-lg-4 d-flex gap-2 justify-content-sm-end">
        <button class="btn btn-outline-secondary" @click="clearLines"><i class="bi bi-trash me-1"></i>Clear</button>
        <button class="btn btn-outline-primary" @click="toggleStreaming">
          <i :class="['bi', status === 'Connected' ? 'bi-pause-circle' : 'bi-play-circle', 'me-1']"></i>
          {{ status === 'Connected' ? 'Pause' : 'Resume' }}
        </button>
      </div>
    </div>

    <pre ref="pane" class="log-pane rounded border p-3 mb-0"><code v-if="visibleLines.length"><span
      v-for="(line, index) in visibleLines"
      :key="`${line.timestamp}-${index}`"
      class="d-block"
    ><span class="text-secondary">[{{ formatClockTime(line.timestamp) }}]</span> <span
      :class="levelClass(line.level)">{{ line.level }}</span> <span class="text-info-emphasis">{{ line.logger }}</span> <span
      class="text-secondary">-</span> <span class="text-light">{{ line.message }}</span></span></code><span v-else
                                                                                                            class="text-secondary">No log lines to display.</span></pre>
  </div>
</template>

<style scoped>
.log-pane {
  background: #111827;
  color: #f8f9fa;
  font-family: var(--bs-font-monospace);
  font-size: 0.875rem;
  height: 70vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
