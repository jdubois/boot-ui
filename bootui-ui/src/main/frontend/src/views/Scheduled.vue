<script setup>
import {getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {describeLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const report = ref(null)
const error = ref(null)
const filter = ref('')
const lastFetched = ref(null)

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/scheduled')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load scheduled tasks')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport)

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const filtered = computed(() => {
  if (!report.value) return []
  const value = filter.value.trim().toLowerCase()
  if (!value) return report.value.tasks
  return report.value.tasks.filter(
    (task) =>
      (task.runnable || '').toLowerCase().includes(value) || (task.expression || '').toLowerCase().includes(value)
  )
})

const triggerBadgeClass = (triggerType) =>
  ({
    CRON: 'bg-info text-dark',
    FIXED_RATE: 'bg-success',
    FIXED_DELAY: 'bg-warning text-dark',
    ONE_SHOT: 'bg-secondary'
  })[triggerType] || 'bg-secondary'

function formatDuration(value, timeUnit) {
  if (value === null || value === undefined) return '—'
  if (timeUnit === 's') return `${value / 1000} s`
  return `${value} ms`
}

function formatExpression(task) {
  if (!task.expression) return '—'
  if (task.triggerType === 'CRON') return task.expression
  const numericValue = Number(task.expression)
  if (Number.isNaN(numericValue)) return task.expression
  if (task.timeUnit === 's') return `${numericValue / 1000} s`
  return `${numericValue} ms`
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-clock-history"
      title="Scheduled Tasks"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading && !report" />
    <div v-else-if="report && !report.schedulingPresent" class="alert alert-info">
      No scheduling detected on the classpath.
    </div>
    <div v-else-if="report && report.total === 0" class="alert alert-secondary">No scheduled tasks registered.</div>
    <template v-else-if="report">
      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input v-model="filter" class="form-control" placeholder="Filter by runnable name or expression…" />
        </div>
        <div class="col-md-4 text-end small text-muted align-self-center">
          {{ filtered.length }} / {{ report.total }} tasks
        </div>
      </div>

      <div class="table-responsive">
        <table class="table table-sm table-hover align-middle">
          <thead>
            <tr>
              <th>Runnable</th>
              <th style="width: 150px">Trigger Type</th>
              <th style="width: 180px">Expression/Interval</th>
              <th style="width: 160px">Initial Delay</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(task, index) in filtered"
              :key="`${task.runnable}-${task.triggerType}-${task.expression}-${index}`"
            >
              <td>
                <code>{{ task.runnable }}</code>
              </td>
              <td>
                <span :class="triggerBadgeClass(task.triggerType)" class="badge">{{ task.triggerType }}</span>
              </td>
              <td>{{ formatExpression(task) }}</td>
              <td>{{ formatDuration(task.initialDelayMs, task.timeUnit) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
