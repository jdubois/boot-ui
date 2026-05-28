<script setup>
import {onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

const filter = ref('')
const message = ref(null)

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
    message.value = 'Level updated for ' + logger.name
    setTimeout(() => {
      message.value = null
    }, 3000)
  }
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
    <h2><i class="bi bi-journal-text me-2"></i>Loggers</h2>
    <div v-if="message" class="alert alert-success">{{ message }}</div>
    <div v-if="error" class="alert alert-danger">Could not load loggers: {{ error }}</div>
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
                  :class="{active: l.configuredLevel === lvl}"
                  class="btn btn-outline-secondary"
                  @click="changeLevel(l, lvl)"
                >
                  {{ lvl }}
                </button>
                <button class="btn btn-outline-secondary" title="Reset" @click="changeLevel(l, null)">↺</button>
              </div>
            </td>
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
