<script setup>
import {ApiError} from '../api.js'
import {onMounted, ref, watch} from 'vue'
import {
  diagnosticActionError,
  getDiagnosticAcknowledgement,
  isLoggerAcknowledgement
} from '../utils/diagnosticAcknowledgement.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import FlashBanner from './components/FlashBanner.vue'
import ServerListFooter from './components/ServerListFooter.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const filter = ref('')
const changingLevel = ref(false)
const {message, flash, clear} = useFlashMessage(3000)

const {
  data,
  error,
  hasLoaded,
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
} = useServerPagedList(
  'api/loggers',
  'loggers',
  () => {
    return {q: filter.value.trim()}
  },
  {errorContext: 'Could not load loggers'}
)

async function changeLevel(logger, level) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (changingLevel.value) return
  changingLevel.value = true
  const body = level ? {level} : {}
  try {
    const updated = await getDiagnosticAcknowledgement(
      `api/loggers/${encodeURIComponent(logger.name)}`,
      {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body)
      },
      (value) => isLoggerAcknowledgement(value, logger.name)
    )
    const i = data.value?.loggers.findIndex((l) => l.name === logger.name) ?? -1
    if (i >= 0) data.value.loggers[i] = updated
    flash(`Level updated for ${logger.name}`, 'success')
    await load()
  } catch (e) {
    flash(diagnosticActionError(e, 'Could not change logger level'), 'danger')
    if (!(e instanceof ApiError)) await load()
  } finally {
    changingLevel.value = false
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
    <PanelHeader icon="bi-journal-text" title="Loggers" :error="error" :loading="loading" @refresh="load" />
    <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Logger levels are read-only.</ReadOnlyNotice>
    <FlashBanner :message="message" @dismiss="clear" />
    <input
      v-model="filter"
      aria-label="Filter loggers"
      class="form-control mb-3"
      placeholder="Filter loggers by name…"
    />
    <p v-if="data" class="small text-muted">{{ matchedCount }} of {{ totalCount }} loggers matched</p>
    <PanelSkeleton v-if="loading && !hasLoaded" :rows="6" />

    <div v-else class="table-responsive">
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
                  :disabled="readOnly || changingLevel"
                  :class="{active: l.configuredLevel === lvl}"
                  class="btn btn-outline-secondary"
                  @click="changeLevel(l, lvl)"
                >
                  {{ lvl }}
                </button>
                <button
                  :aria-label="`Reset ${l.name} logger level`"
                  :disabled="readOnly || changingLevel"
                  class="btn btn-outline-secondary"
                  title="Reset"
                  type="button"
                  @click="changeLevel(l, null)"
                >
                  <i aria-hidden="true" class="bi bi-arrow-counterclockwise"></i>
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
