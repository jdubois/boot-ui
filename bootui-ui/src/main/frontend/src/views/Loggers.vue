<script setup>
import {onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const filter = ref('')
const message = ref(null)
const messageType = ref('success')

const {
  data,
  error,
  items: visibleLoggers,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList('api/loggers', 'loggers', () => {
  return {q: filter.value.trim()}
})

async function changeLevel(logger, level) {
  if (readOnly.value) {
    showMessage(readOnlyReason.value, 'warning')
    return
  }
  const body = level ? {level} : {}
  const res = await apiFetch('api/loggers/' + encodeURIComponent(logger.name), {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body)
  })
  if (res.ok) {
    const updated = await res.json()
    const i = data.value.loggers.findIndex((l) => l.name === logger.name)
    if (i >= 0) data.value.loggers[i] = updated
    showMessage('Level updated for ' + logger.name)
  }
}

function showMessage(text, type = 'success') {
  message.value = text
  messageType.value = type
  setTimeout(() => {
    message.value = null
  }, 3000)
}

const levelClass = (l) =>
  ({
    TRACE: 'text-secondary',
    DEBUG: 'text-info',
    INFO: 'text-success',
    WARN: 'text-warning',
    ERROR: 'text-danger',
    FATAL: 'text-danger fw-bold',
    OFF: 'text-muted'
  })[l] || 'text-secondary'

onMounted(load)
watch(filter, scheduleReload)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-journal-text"
      title="Loggers"
      :error="error ? `Could not load loggers: ${error}` : null"
    />
    <div v-if="readOnly" class="alert alert-warning small">
      <i class="bi bi-lock me-1"></i>
      Logger levels are read-only. {{ readOnlyReason }}
    </div>
    <div v-if="message" :class="'alert-' + messageType" class="alert">{{ message }}</div>
    <input v-model="filter" class="form-control mb-3" placeholder="Filter loggers by name…" />
    <p v-if="data" class="small text-muted">{{ matchedCount }} of {{ totalCount }} loggers matched</p>
    <div class="table-responsive">
      <table class="table table-sm table-hover loggers-table">
        <colgroup>
          <col class="loggers-table-name" />
          <col class="loggers-table-level" />
          <col class="loggers-table-level" />
          <col class="loggers-table-actions" />
        </colgroup>
        <thead>
          <tr>
            <th>Logger</th>
            <th>Configured</th>
            <th>Effective</th>
            <th>Set level</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in visibleLoggers" :key="l.name">
            <td>
              <code :title="l.name" class="text-truncate d-block">{{ l.name }}</code>
            </td>
            <td :class="levelClass(l.configuredLevel)">{{ l.configuredLevel || '—' }}</td>
            <td :class="levelClass(l.effectiveLevel)">{{ l.effectiveLevel || '—' }}</td>
            <td>
              <div class="btn-group btn-group-sm">
                <button
                  v-for="lvl in data.availableLevels"
                  :key="lvl"
                  :disabled="readOnly"
                  :class="{active: l.configuredLevel === lvl}"
                  class="btn btn-outline-secondary"
                  @click="changeLevel(l, lvl)"
                >
                  {{ lvl }}
                </button>
                <button
                  :disabled="readOnly"
                  class="btn btn-outline-secondary"
                  title="Reset"
                  @click="changeLevel(l, null)"
                >
                  ↺
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!loading && matchedCount === 0">
            <td class="text-center text-muted py-4" colspan="4">No loggers match your filters.</td>
          </tr>
        </tbody>
      </table>
    </div>
    <ServerListFooter
      v-if="!loading"
      :loading="loadingMore"
      :matched="matchedCount"
      :page-size="pageSize"
      :shown="shownCount"
      :total="totalCount"
      item-label="loggers"
      @load-more="loadMore"
    />
  </div>
</template>

<style scoped>
.loggers-table {
  table-layout: fixed;
  min-width: 760px;
}

.loggers-table-name {
  width: 34%;
}

.loggers-table-level {
  width: 15%;
}

.loggers-table-actions {
  width: 36%;
}
</style>
