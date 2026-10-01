<script setup>
import {computed, onMounted, ref} from 'vue'
import {apiFetch} from '../../api.js'
import SpinnerButton from './SpinnerButton.vue'
import {formatBytes, formatClockTime, formatDuration, formatNumber} from '../../utils/format.js'
import {formatLoadError} from '../../utils/loadError.js'

// The run's CPU ledger and resource track (docs/PLAN-v2.md §5.11): where the process's CPU time went, inside and
// outside requests, and the heap and CPU over the last sweeps. Mounted only when the developer opens it, so opening
// Live Activity makes no extra request.
const resources = ref(null)
const loading = ref(false)
const error = ref('')

const INTERNAL = 'JVM internals (GC, JIT, VM)'
const REQUESTS = 'Requests'

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await apiFetch('api/activity/resources')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    resources.value = await res.json()
  } catch (err) {
    error.value = formatLoadError(err, 'Could not load the resource track')
  } finally {
    loading.value = false
  }
}

// Requests, each thread family, and the JVM's own work: together they are the process's CPU time.
const breakdown = computed(() => {
  const totals = resources.value?.totals
  if (!totals || totals.processCpuNanos <= 0) return []
  const parts = [
    {label: REQUESTS, nanos: totals.requestCpuNanos, kind: 'requests'},
    ...Object.entries(totals.familyCpuNanos ?? {}).map(([family, nanos]) => ({
      label: family === 'BootUI' ? 'BootUI itself' : family,
      nanos,
      kind: family === 'BootUI' ? 'bootui' : 'family'
    })),
    {label: INTERNAL, nanos: totals.internalCpuNanos, kind: 'internal'}
  ]
  return parts
    .filter((part) => part.nanos > 0)
    .map((part) => ({...part, percent: (100 * part.nanos) / totals.processCpuNanos}))
    .sort((a, b) => b.nanos - a.nanos)
})

const outsideRequestsPercent = computed(() =>
  breakdown.value.filter((part) => part.kind !== 'requests').reduce((sum, part) => sum + part.percent, 0)
)

const points = computed(() => resources.value?.points ?? [])
const latest = computed(() => points.value.at(-1) ?? null)
const trackWindow = computed(() =>
  points.value.length > 1
    ? `${formatClockTime(points.value[0].epochMillis)} to ${formatClockTime(latest.value.epochMillis)}`
    : ''
)

// One polyline per series over the kept points, scaled to the series' own maximum.
function lane(values) {
  const known = values.filter((value) => value != null && value >= 0)
  if (known.length < 2) return ''
  const max = Math.max(...known, 1)
  const step = 100 / (values.length - 1)
  return values
    .map((value, index) =>
      value == null || value < 0 ? null : `${(index * step).toFixed(2)},${(30 - (28 * value) / max).toFixed(2)}`
    )
    .filter(Boolean)
    .join(' ')
}

const heapLane = computed(() => lane(points.value.map((point) => point.heapUsedBytes)))
const cpuLane = computed(() =>
  lane(
    points.value.map((point) =>
      point.processCpuNanos < 0 ? null : point.processCpuNanos / Math.max(1, point.intervalNanos)
    )
  )
)
const peakCpuPercent = computed(() => {
  const values = points.value
    .filter((point) => point.processCpuNanos >= 0 && point.intervalNanos > 0)
    .map((point) => (100 * point.processCpuNanos) / point.intervalNanos)
  return values.length ? Math.max(...values) : null
})

function percent(value) {
  return `${value < 10 ? value.toFixed(1) : Math.round(value)}%`
}

onMounted(load)
</script>

<template>
  <section class="card mb-3 runtime-resources" aria-labelledby="runtime-resources-title">
    <div class="card-body py-2">
      <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
        <h2 id="runtime-resources-title" class="h6 mb-0">
          <i class="bi bi-cpu me-1" aria-hidden="true"></i>Work outside requests
        </h2>
        <span v-if="resources?.totals?.sweeps" class="text-muted small"
          >{{ formatNumber(resources.totals.sweeps) }} sweeps this run</span
        >
        <div class="ms-auto">
          <SpinnerButton
            :loading="loading"
            :disabled="loading"
            class="btn btn-sm btn-outline-secondary"
            icon="bi-arrow-clockwise"
            label="Refresh"
            @click="load"
          />
        </div>
      </div>
      <p v-if="error" class="small text-danger mb-0" role="alert">{{ error }}</p>
      <p v-else-if="!resources" class="small text-muted mb-0">Loading the resource track…</p>
      <p v-else-if="!resources.available" class="small text-muted mb-0">{{ resources.unavailableReason }}</p>
      <p v-else-if="!breakdown.length" class="small text-muted mb-0">
        No CPU time measured yet: the sampler needs two sweeps, or this JVM does not report process CPU time.
      </p>
      <template v-else>
        <p class="small mb-2">
          {{ percent(outsideRequestsPercent) }} of this run's {{ formatDuration(resources.totals.processCpuNanos) }} of
          CPU time went to work outside requests. Requests on virtual threads count in their carrier threads' family,
          because the JVM does not measure virtual threads.
        </p>
        <div class="runtime-resources__stack mb-2" aria-hidden="true">
          <span
            v-for="part in breakdown"
            :key="part.label"
            class="runtime-resources__part"
            :class="`runtime-resources__part--${part.kind}`"
            :style="{width: `${part.percent}%`}"
          ></span>
        </div>
        <div class="table-responsive">
          <table class="table table-sm small mb-2 runtime-resources__table">
            <caption class="visually-hidden">
              Process CPU time by where it went
            </caption>
            <thead>
              <tr>
                <th scope="col">Where</th>
                <th scope="col" class="text-end">CPU time</th>
                <th scope="col" class="text-end">Share</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="part in breakdown" :key="part.label">
                <th scope="row" class="fw-normal">
                  <span
                    class="runtime-resources__swatch"
                    :class="`runtime-resources__part--${part.kind}`"
                    aria-hidden="true"
                  ></span>
                  <code v-if="part.kind === 'family'">{{ part.label }}</code>
                  <template v-else>{{ part.label }}</template>
                </th>
                <td class="text-end">{{ formatDuration(part.nanos) }}</td>
                <td class="text-end">{{ percent(part.percent) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <figure v-if="heapLane || cpuLane" class="mb-0">
          <figcaption class="small text-muted mb-1">
            Resource lane, {{ trackWindow }}: heap used (now {{ formatBytes(latest?.heapUsedBytes) }} of
            {{ formatBytes(latest?.heapCommittedBytes) }} committed)<template v-if="peakCpuPercent != null"
              >, process CPU (peak {{ percent(peakCpuPercent) }} of one core)</template
            >
          </figcaption>
          <svg viewBox="0 0 100 32" preserveAspectRatio="none" class="w-100 runtime-resources__lane" aria-hidden="true">
            <polyline v-if="heapLane" :points="heapLane" class="runtime-resources__heap" />
            <polyline v-if="cpuLane" :points="cpuLane" class="runtime-resources__cpu" />
          </svg>
        </figure>
      </template>
    </div>
  </section>
</template>

<style scoped>
.runtime-resources__stack {
  display: flex;
  height: 0.75rem;
  overflow: hidden;
  border-radius: var(--bootui-radius-xs);
  background: var(--bootui-surface-alt);
}

.runtime-resources__swatch {
  display: inline-block;
  width: 0.65rem;
  height: 0.65rem;
  margin-inline-end: 0.4rem;
  border-radius: var(--bootui-radius-xs);
}

.runtime-resources__part--requests {
  background: var(--bootui-green-dark);
}

.runtime-resources__part--family {
  background: var(--bootui-border-alt);
}

.runtime-resources__part--bootui {
  background: var(--bootui-text-muted);
}

.runtime-resources__part--internal {
  background: var(--bootui-warning);
}

.runtime-resources__table td,
.runtime-resources__table th {
  font-variant-numeric: tabular-nums;
}

.runtime-resources__lane {
  height: 3rem;
  border-bottom: 1px solid var(--bootui-border);
}

.runtime-resources__lane polyline {
  fill: none;
  stroke-width: 1.2;
  vector-effect: non-scaling-stroke;
}

.runtime-resources__heap {
  stroke: var(--bootui-green-dark);
}

.runtime-resources__cpu {
  stroke: var(--bootui-warning);
}
</style>
