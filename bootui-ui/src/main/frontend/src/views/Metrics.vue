<script setup>
import {getJson} from '../api.js'
import {computed, ref} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'

const data = ref(null)
const detail = ref(null)
const error = ref(null)
const detailError = ref(null)
const search = ref('')
const typeFilter = ref('')
const selectedName = ref('')
const selectedTags = ref([])
const selectedStatistic = ref('')
const history = ref([])
const lastUpdated = ref(null)
let loadingDetail = false

const filteredMeters = computed(() => {
  if (!data.value) return []
  const q = search.value.trim().toLowerCase()
  return data.value.meters.filter((meter) => {
    const matchesSearch =
      !q || meter.name.toLowerCase().includes(q) || (meter.description || '').toLowerCase().includes(q)
    const matchesType = !typeFilter.value || meter.type === typeFilter.value
    return matchesSearch && matchesType
  })
})

const metricTypes = computed(() => {
  if (!data.value) return []
  return [...new Set(data.value.meters.map((meter) => meter.type).filter(Boolean))].sort()
})

const selectedMeasurement = computed(() => {
  if (!detail.value?.measurements?.length) return null
  return (
    detail.value.measurements.find((measurement) => measurement.statistic === selectedStatistic.value) ||
    detail.value.measurements[0]
  )
})

const chartPath = computed(() => {
  const points = history.value
  if (points.length < 2) return ''
  const values = points.map((point) => point.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  return points
    .map((point, index) => {
      const x = (index / (points.length - 1)) * 100
      const y = 44 - ((point.value - min) / span) * 36
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`
    })
    .join(' ')
})

function formatNumber(value) {
  if (value == null || Number.isNaN(value)) return 'N/A'
  if (Math.abs(value) >= 1000) return value.toLocaleString(undefined, {maximumFractionDigits: 1})
  if (Math.abs(value) >= 1) return value.toLocaleString(undefined, {maximumFractionDigits: 3})
  return value.toLocaleString(undefined, {maximumSignificantDigits: 4})
}

function tagLabel(tag) {
  return `${tag.key}:${tag.value}`
}

function sampleKey(sample) {
  return sample.tags.length ? sample.tags.map(tagLabel).join('|') : 'no-tags'
}

function resetHistory() {
  history.value = []
}

function resetSelection(name) {
  selectedName.value = name
  selectedTags.value = []
  selectedStatistic.value = ''
  detail.value = null
  resetHistory()
}

function selectMeter(name) {
  if (selectedName.value === name) return
  resetSelection(name)
  loadDetail()
}

function toggleTag(key, value) {
  const label = `${key}:${value}`
  const exists = selectedTags.value.includes(label)
  selectedTags.value = exists
    ? selectedTags.value.filter((tag) => tag !== label)
    : [...selectedTags.value.filter((tag) => !tag.startsWith(`${key}:`)), label]
  resetHistory()
  loadDetail()
}

function isTagSelected(key, value) {
  return selectedTags.value.includes(`${key}:${value}`)
}

function clearTags() {
  selectedTags.value = []
  resetHistory()
  loadDetail()
}

function changeStatistic(event) {
  selectedStatistic.value = event.target.value
  resetHistory()
  appendHistoryPoint()
}

async function fetchMetrics() {
  try {
    data.value = await getJson('api/metrics')
    error.value = null
    if (!selectedName.value && data.value.meters.length) {
      resetSelection(preferredInitialMeter(data.value.meters))
    }
    return true
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load metrics')
    return false
  }
}

async function refreshMetrics() {
  if (await fetchMetrics()) {
    await loadDetail()
  }
}

function preferredInitialMeter(meters) {
  return (
    meters.find((meter) => meter.name === 'jvm.memory.used')?.name ||
    meters.find((meter) => meter.name === 'process.uptime')?.name ||
    meters[0].name
  )
}

async function loadDetail() {
  if (!selectedName.value || loadingDetail) return
  loadingDetail = true
  try {
    const params = new URLSearchParams({name: selectedName.value})
    for (const tag of selectedTags.value) {
      params.append('tag', tag)
    }
    detail.value = await getJson(`api/metrics/detail?${params}`)
    if (!detail.value.measurements.some((measurement) => measurement.statistic === selectedStatistic.value)) {
      selectedStatistic.value = detail.value.measurements[0]?.statistic || ''
      resetHistory()
    }
    appendHistoryPoint()
    lastUpdated.value = new Date()
    detailError.value = null
  } catch (e) {
    detailError.value = formatLoadError(e, 'Unable to load metric details')
  } finally {
    loadingDetail = false
  }
}

function appendHistoryPoint() {
  if (!selectedMeasurement.value) return
  history.value = [...history.value, {timestamp: Date.now(), value: selectedMeasurement.value.value}].slice(-60)
}

const {autoRefresh, loading, initialLoading, load: loadMetrics} = useAutoRefresh(refreshMetrics)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-activity"
      title="Metrics"
      subtitle="Browse meters, filter tag sets, and watch live values update automatically."
      :loading="loading"
      :error="error"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      v-model:auto-refresh="autoRefresh"
      @refresh="loadMetrics"
    />

    <PanelSkeleton v-if="initialLoading" />

    <div v-else-if="data && !data.metricsAvailable" class="alert alert-warning">
      Micrometer metrics are not available. Add Actuator or a MeterRegistry to browse live metrics.
    </div>

    <template v-else-if="data">
      <div class="row g-3">
        <div class="col-lg-4">
          <div class="card h-100">
            <div class="card-header">
              <div class="fw-semibold">Meters</div>
              <div class="text-muted small">{{ filteredMeters.length }} of {{ data.total }} meters</div>
            </div>
            <div class="card-body border-bottom">
              <input v-model="search" class="form-control form-control-sm mb-2" placeholder="Search meters" />
              <select v-model="typeFilter" class="form-select form-select-sm">
                <option value="">All meter types</option>
                <option v-for="type in metricTypes" :key="type" :value="type">{{ type }}</option>
              </select>
            </div>
            <div class="list-group list-group-flush meter-list">
              <button
                v-for="meter in filteredMeters"
                :key="meter.name"
                :class="{active: meter.name === selectedName}"
                class="list-group-item list-group-item-action"
                type="button"
                @click="selectMeter(meter.name)"
              >
                <div class="d-flex justify-content-between align-items-start gap-2">
                  <code class="meter-name">{{ meter.name }}</code>
                  <span class="badge text-bg-light">{{ meter.type }}</span>
                </div>
                <div v-if="meter.description" class="small text-muted mt-1">{{ meter.description }}</div>
              </button>
            </div>
          </div>
        </div>

        <div class="col-lg-8">
          <div v-if="detailError" class="alert alert-danger">{{ detailError }}</div>

          <div v-if="detail" class="card mb-3">
            <div class="card-header d-flex flex-wrap justify-content-between align-items-start gap-2">
              <div>
                <code class="fs-6">{{ detail.name }}</code>
                <div v-if="detail.description" class="text-muted small">{{ detail.description }}</div>
              </div>
              <div class="d-flex gap-2">
                <span class="badge text-bg-secondary">{{ detail.type || 'UNKNOWN' }}</span>
                <span v-if="detail.baseUnit" class="badge text-bg-info">{{ detail.baseUnit }}</span>
              </div>
            </div>
            <div class="card-body">
              <div class="row g-3 align-items-stretch">
                <div class="col-md-4">
                  <label class="form-label small text-muted">Statistic</label>
                  <select :value="selectedStatistic" class="form-select" @change="changeStatistic">
                    <option
                      v-for="measurement in detail.measurements"
                      :key="measurement.statistic"
                      :value="measurement.statistic"
                    >
                      {{ measurement.statistic }}
                    </option>
                  </select>
                  <div class="display-6 mt-3">{{ formatNumber(selectedMeasurement?.value) }}</div>
                  <div class="text-muted small">Current {{ selectedStatistic || 'value' }}</div>
                </div>
                <div class="col-md-8">
                  <div class="chart-box">
                    <svg
                      aria-label="Live metric value graph"
                      preserveAspectRatio="none"
                      role="img"
                      viewBox="0 0 100 48"
                    >
                      <line class="chart-axis" x1="0" x2="100" y1="44" y2="44" />
                      <path v-if="chartPath" :d="chartPath" class="chart-line" />
                    </svg>
                    <div v-if="history.length < 2" class="chart-empty text-muted small">
                      Waiting for another sample…
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="detail" class="card mb-3">
            <div class="card-header d-flex justify-content-between align-items-center">
              <span>Tag filters</span>
              <button v-if="selectedTags.length" class="btn btn-sm btn-outline-secondary" @click="clearTags">
                Clear filters
              </button>
            </div>
            <div class="card-body">
              <div v-if="!detail.availableTags.length" class="text-muted small">This meter has no tags.</div>
              <div v-for="tag in detail.availableTags" :key="tag.key" class="mb-3">
                <div class="fw-semibold small mb-2">{{ tag.key }}</div>
                <div class="d-flex flex-wrap gap-2">
                  <button
                    v-for="value in tag.values"
                    :key="value"
                    :class="isTagSelected(tag.key, value) ? 'btn-primary' : 'btn-outline-primary'"
                    class="btn btn-sm"
                    type="button"
                    @click="toggleTag(tag.key, value)"
                  >
                    {{ value || '(empty)' }}
                  </button>
                  <span v-if="tag.truncated" class="badge text-bg-warning">first 100 shown</span>
                </div>
              </div>
              <div v-if="selectedTags.length" class="small text-muted">
                Active filters: <code v-for="tag in selectedTags" :key="tag" class="me-2">{{ tag }}</code>
              </div>
            </div>
          </div>

          <div v-if="detail" class="card">
            <div class="card-header">Samples</div>
            <div class="table-responsive">
              <table class="table table-sm table-hover mb-0">
                <thead class="table-light">
                  <tr>
                    <th>Tags</th>
                    <th>Measurements</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="sample in detail.samples" :key="sampleKey(sample)">
                    <td>
                      <span v-if="!sample.tags.length" class="text-muted">none</span>
                      <span v-for="tag in sample.tags" :key="tagLabel(tag)" class="badge text-bg-light me-1">
                        {{ tag.key }}={{ tag.value || '(empty)' }}
                      </span>
                    </td>
                    <td>
                      <span v-for="measurement in sample.measurements" :key="measurement.statistic" class="me-3">
                        <span class="text-muted me-1">{{ measurement.statistic }}</span>
                        <code>{{ formatNumber(measurement.value) }}</code>
                      </span>
                    </td>
                  </tr>
                  <tr v-if="!detail.samples.length">
                    <td class="text-muted" colspan="2">No samples match the selected tag filters.</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-else class="text-muted">Select a meter to inspect live values.</div>
        </div>
      </div>
    </template>

    <div v-else class="text-muted">Loading metrics…</div>
  </div>
</template>

<style scoped>
.meter-list {
  max-height: 44rem;
  overflow: auto;
}

.meter-name {
  overflow-wrap: anywhere;
}

.chart-box {
  background: linear-gradient(180deg, rgba(13, 110, 253, 0.08), rgba(25, 135, 84, 0.06));
  border: 1px solid rgba(13, 110, 253, 0.12);
  border-radius: 1rem;
  min-height: 12rem;
  padding: 1rem;
  position: relative;
}

.chart-box svg {
  height: 10rem;
  width: 100%;
}

.chart-axis {
  stroke: rgba(100, 116, 139, 0.35);
  stroke-width: 0.5;
}

.chart-line {
  fill: none;
  stroke: #0d6efd;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}

.chart-empty {
  left: 1rem;
  position: absolute;
  top: 1rem;
}
</style>
