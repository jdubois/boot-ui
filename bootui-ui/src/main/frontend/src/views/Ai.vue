<script setup>
import {computed, onMounted, ref} from 'vue'
import {formatDuration, formatNumber, formatTime} from '../utils/format.js'

const overview = ref(null)
const series = ref(null)
const detail = ref(null)
const loading = ref(true)
const error = ref(null)
const selectedSpanId = ref(null)
const detailLoading = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [ovRes, tsRes] = await Promise.all([
      fetch('api/ai/overview'),
      fetch('api/ai/tokens?minutes=' + windowMinutes.value)
    ])
    if (!ovRes.ok) throw new Error('HTTP ' + ovRes.status)
    overview.value = await ovRes.json()
    if (tsRes.ok) {
      series.value = await tsRes.json()
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function openChat(spanId) {
  selectedSpanId.value = spanId
  detail.value = null
  detailLoading.value = true
  try {
    const res = await fetch('api/ai/chats/' + spanId)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    detail.value = await res.json()
  } catch (e) {
    detail.value = {error: e.message}
  } finally {
    detailLoading.value = false
  }
}

function toggleChat(spanId) {
  if (spanId === selectedSpanId.value) {
    closeDrawer()
    return
  }
  openChat(spanId)
}

function closeDrawer() {
  selectedSpanId.value = null
  detail.value = null
}

const tokensByModelEntries = computed(() => {
  if (!overview.value || !overview.value.tokensByModel) return []
  return Object.entries(overview.value.tokensByModel).sort((a, b) => b[1] - a[1])
})

const callsByModelEntries = computed(() => {
  if (!overview.value || !overview.value.callsByModel) return []
  return Object.entries(overview.value.callsByModel).sort((a, b) => b[1] - a[1])
})

const windowMinutes = ref(60)
const tooltipData = ref(null)
const chartContainerRef = ref(null)

async function loadTokenSeries() {
  const res = await fetch('api/ai/tokens?minutes=' + windowMinutes.value)
  if (res.ok) series.value = await res.json()
}

async function onWindowChange() {
  await loadTokenSeries()
}

const chart = computed(() => {
  if (!series.value || !series.value.buckets || series.value.buckets.length === 0) return null
  const buckets = series.value.buckets
  const n = buckets.length
  const width = 600
  const height = 80
  const step = width / Math.max(n - 1, 1)

  const maxTokens = buckets.reduce((m, b) => {
    const t = (b.inputTokens || 0) + (b.outputTokens || 0)
    return t > m ? t : m
  }, 1)
  const maxCalls = buckets.reduce((m, b) => Math.max(m, b.callCount || 0), 1)
  const totalCalls = buckets.reduce((s, b) => s + (b.callCount || 0), 0)

  const xs = buckets.map((_, i) => i * step)
  const inputTops = buckets.map((b) => height - ((b.inputTokens || 0) / maxTokens) * height)
  const stackedTops = buckets.map((b) => height - (((b.inputTokens || 0) + (b.outputTokens || 0)) / maxTokens) * height)
  const callYs = buckets.map((b) => height - ((b.callCount || 0) / maxCalls) * height)

  // Build input area polygon (bottom area)
  const inputAreaPts = xs.map((x, i) => `${x},${inputTops[i]}`).join(' ') + ` ${xs[n - 1]},${height} ${xs[0]},${height}`

  // Build output area polygon (stacked on top of input)
  const outputAreaPts =
    xs.map((x, i) => `${x},${stackedTops[i]}`).join(' ') +
    ' ' +
    xs.map((x, i) => `${xs[n - 1 - i]},${inputTops[n - 1 - i]}`).join(' ')

  const inputLinePts = xs.map((x, i) => `${x},${inputTops[i]}`).join(' ')
  const outputLinePts = xs.map((x, i) => `${x},${stackedTops[i]}`).join(' ')
  const callLinePts = xs.map((x, i) => `${x},${callYs[i]}`).join(' ')

  const halfY = height / 2
  const firstTime = new Date(buckets[0].epochMinute * 60000).toLocaleTimeString()
  const midTime = new Date(buckets[Math.floor((n - 1) / 2)].epochMinute * 60000).toLocaleTimeString()
  const lastTime = new Date(buckets[n - 1].epochMinute * 60000).toLocaleTimeString()

  return {
    width,
    height,
    maxTokens,
    totalCalls,
    inputAreaPts,
    outputAreaPts,
    inputLinePts,
    outputLinePts,
    callLinePts,
    halfY,
    firstTime,
    midTime,
    lastTime,
    buckets,
    xs
  }
})

function onChartMousemove(event) {
  if (!chart.value) return
  const rect = event.currentTarget.getBoundingClientRect()
  const relX = ((event.clientX - rect.left) / rect.width) * chart.value.width
  const idx = chart.value.xs.reduce(
    (best, x, i) => (Math.abs(x - relX) < Math.abs(chart.value.xs[best] - relX) ? i : best),
    0
  )
  const b = chart.value.buckets[idx]
  tooltipData.value = {
    idx,
    x: (chart.value.xs[idx] / chart.value.width) * 100,
    time: new Date(b.epochMinute * 60000).toLocaleTimeString(),
    input: b.inputTokens || 0,
    output: b.outputTokens || 0,
    calls: b.callCount || 0
  }
}

function onChartMouseleave() {
  tooltipData.value = null
}

const avgLatency = computed(() => {
  const recent = overview.value && overview.value.recent
  if (!recent || recent.length === 0) return null
  const sum = recent.reduce((s, c) => s + (c.durationNanos || 0), 0)
  return sum / recent.length
})

const errorRate = computed(() => {
  const recent = overview.value && overview.value.recent
  if (!recent || recent.length === 0) return null
  const errors = recent.filter((c) => c.statusCode === 'ERROR').length
  return (errors / recent.length) * 100
})

const hasAnyData = computed(() => overview.value && overview.value.totalChats > 0)

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="mb-1"><i class="bi bi-stars me-2"></i>AI Usage</h2>
        <div v-if="overview" class="text-muted small">
          <span v-if="overview.springAiDetected" class="badge text-bg-success me-1">Spring AI detected</span>
          <span v-else class="badge text-bg-secondary me-1">Spring AI not on classpath</span>
          <span v-if="!overview.enabled" class="badge text-bg-warning me-1">Telemetry disabled</span>
        </div>
      </div>
      <button :disabled="loading" class="btn btn-sm btn-outline-secondary" @click="load">
        <i class="bi bi-arrow-clockwise"></i> Refresh
      </button>
    </div>

    <div v-if="loading" class="text-muted">Loading AI usage…</div>
    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-else-if="overview">
      <div v-if="overview.contentBanner" class="alert alert-info small">
        <i class="bi bi-info-circle me-1"></i>{{ overview.contentBanner }}
      </div>

      <div v-if="!overview.enabled" class="alert alert-info small">
        Telemetry receiver is disabled. Set <code>bootui.telemetry.enabled=true</code> and export OTLP spans to
        <code>/bootui/api/otlp/v1/traces</code> to populate AI usage.
      </div>

      <div v-else-if="!overview.springAiDetected" class="alert alert-secondary">
        Spring AI is not on the classpath for this application, so BootUI cannot collect AI usage spans.
      </div>

      <div v-else-if="!hasAnyData" class="alert alert-secondary">
        No AI chat completions recorded yet. Make sure your application uses Spring AI with OpenTelemetry tracing
        enabled and exports OTLP to <code>/bootui/api/otlp/v1/traces</code>, then exercise a chat endpoint to populate
        this panel.
      </div>

      <template v-else>
        <div class="row row-cols-2 row-cols-md-3 row-cols-xl-6 g-3 mb-3">
          <div class="col">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small"><i class="bi bi-chat-dots me-1"></i>Chats</div>
                <div class="fs-3 fw-semibold">{{ formatNumber(overview.totalChats) }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small"><i class="bi bi-coin me-1"></i>Total tokens</div>
                <div class="fs-3 fw-semibold">
                  {{ formatNumber((overview.totalInputTokens || 0) + (overview.totalOutputTokens || 0)) }}
                </div>
                <small class="text-muted"
                  >{{ formatNumber(overview.totalInputTokens) }} in ·
                  {{ formatNumber(overview.totalOutputTokens) }} out</small
                >
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small"><i class="bi bi-stopwatch me-1"></i>Avg latency</div>
                <div class="fs-3 fw-semibold">{{ avgLatency != null ? formatDuration(avgLatency) : '—' }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div :class="['card-body', errorRate > 0 ? 'text-danger' : '']">
                <div class="text-muted small"><i class="bi bi-exclamation-triangle me-1"></i>Error rate</div>
                <div class="fs-3 fw-semibold">{{ errorRate != null ? errorRate.toFixed(1) + '%' : '—' }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small"><i class="bi bi-tools me-1"></i>Tool calls</div>
                <div class="fs-3 fw-semibold">{{ formatNumber(overview.toolCallCount) }}</div>
              </div>
            </div>
          </div>
          <div class="col">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small"><i class="bi bi-database me-1"></i>Vector ops</div>
                <div class="fs-3 fw-semibold">{{ formatNumber(overview.vectorOperationCount) }}</div>
                <small class="text-muted">+ {{ formatNumber(overview.embeddingCount) }} embeddings</small>
              </div>
            </div>
          </div>
        </div>

        <div v-if="chart" class="card mb-3">
          <div class="card-body">
            <div class="d-flex justify-content-between align-items-center mb-2">
              <h6 class="mb-0">Token usage (last {{ series.minutes }} min)</h6>
              <select v-model="windowMinutes" class="form-select form-select-sm w-auto" @change="onWindowChange">
                <option :value="15">15 min</option>
                <option :value="60">60 min</option>
                <option :value="240">240 min</option>
              </select>
            </div>
            <div
              ref="chartContainerRef"
              class="position-relative"
              @mouseleave="onChartMouseleave"
              @mousemove="onChartMousemove"
            >
              <svg
                :aria-label="'Token usage over the last ' + windowMinutes + ' minutes'"
                :viewBox="'0 0 ' + chart.width + ' ' + chart.height"
                class="w-100"
                role="img"
                style="max-height: 100px"
              >
                <line :x1="0" :x2="chart.width" :y1="chart.halfY" :y2="chart.halfY" stroke="#dee2e6" stroke-width="1" />
                <text :x="2" :y="8" fill="#6c757d" font-size="9">{{ formatNumber(chart.maxTokens) }}</text>
                <text :x="2" :y="chart.halfY - 2" fill="#6c757d" font-size="9">
                  {{ formatNumber(Math.round(chart.maxTokens / 2)) }}
                </text>
                <text :x="2" :y="chart.height - 1" fill="#6c757d" font-size="9">0</text>
                <polygon :points="chart.inputAreaPts" fill="#0d6efd" fill-opacity="0.6" />
                <polygon :points="chart.outputAreaPts" fill="#6610f2" fill-opacity="0.6" />
                <polyline :points="chart.outputLinePts" fill="none" stroke="#6610f2" stroke-width="1.5" />
                <polyline
                  :points="chart.callLinePts"
                  fill="none"
                  stroke="#198754"
                  stroke-dasharray="4 2"
                  stroke-width="1.5"
                />
                <line
                  v-if="tooltipData"
                  :x1="(tooltipData.x / 100) * chart.width"
                  :x2="(tooltipData.x / 100) * chart.width"
                  :y1="0"
                  :y2="chart.height"
                  stroke="#adb5bd"
                  stroke-width="1"
                />
              </svg>
              <div
                v-if="tooltipData"
                :style="{left: tooltipData.x + '%'}"
                class="position-absolute bg-white border rounded p-1 small shadow-sm"
                style="top: 0; transform: translateX(-50%); pointer-events: none; white-space: nowrap; z-index: 10"
              >
                <div class="fw-semibold">{{ tooltipData.time }}</div>
                <div style="color: #0d6efd">In: {{ tooltipData.input }}</div>
                <div style="color: #6610f2">Out: {{ tooltipData.output }}</div>
                <div style="color: #198754">Calls: {{ tooltipData.calls }}</div>
              </div>
            </div>
            <div class="d-flex justify-content-between text-muted small mt-1 px-1">
              <span>{{ chart.firstTime }}</span>
              <span>{{ chart.midTime }}</span>
              <span>{{ chart.lastTime }}</span>
            </div>
            <div class="text-muted small mt-1">
              Peak {{ formatNumber(chart.maxTokens) }} tokens/min · {{ formatNumber(chart.totalCalls) }} calls
            </div>
          </div>
        </div>

        <div class="row g-3 mb-3">
          <div class="col-md-6">
            <div class="card h-100">
              <div class="card-body">
                <h6>Tokens by model</h6>
                <table class="table table-sm mb-0">
                  <tbody>
                    <tr v-for="[model, tokens] in tokensByModelEntries" :key="model">
                      <td>
                        <code>{{ model }}</code>
                      </td>
                      <td class="text-end">{{ formatNumber(tokens) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
          <div class="col-md-6">
            <div class="card h-100">
              <div class="card-body">
                <h6>Calls by model</h6>
                <table class="table table-sm mb-0">
                  <tbody>
                    <tr v-for="[model, calls] in callsByModelEntries" :key="model">
                      <td>
                        <code>{{ model }}</code>
                      </td>
                      <td class="text-end">{{ formatNumber(calls) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>

        <h5>Recent chats</h5>
        <div class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th>Started</th>
                <th>Provider</th>
                <th>Model</th>
                <th>Tokens in/out</th>
                <th>Duration</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="chat in overview.recent" :key="chat.spanId">
                <tr :class="{'table-active': chat.spanId === selectedSpanId}">
                  <td class="text-muted small">{{ formatTime(chat.startEpochNanos) }}</td>
                  <td>{{ chat.provider || '—' }}</td>
                  <td>
                    <code>{{ chat.requestModel || '—' }}</code>
                  </td>
                  <td>{{ formatNumber(chat.inputTokens) }} / {{ formatNumber(chat.outputTokens) }}</td>
                  <td>{{ formatDuration(chat.durationNanos) }}</td>
                  <td>
                    <span v-if="chat.statusCode === 'ERROR'" class="badge text-bg-danger">error</span>
                    <span v-else class="badge text-bg-success">{{ chat.finishReason || 'ok' }}</span>
                  </td>
                  <td class="text-end">
                    <button
                      :aria-expanded="chat.spanId === selectedSpanId"
                      class="btn btn-sm btn-outline-primary"
                      @click="toggleChat(chat.spanId)"
                    >
                      {{ chat.spanId === selectedSpanId ? 'Close' : 'Open' }}
                    </button>
                  </td>
                </tr>
                <tr v-if="chat.spanId === selectedSpanId" class="chat-detail-row">
                  <td class="p-0" colspan="7">
                    <div class="card m-2">
                      <div class="card-header d-flex justify-content-between align-items-center">
                        <div>
                          <i class="bi bi-stars me-2"></i>Chat <code>{{ selectedSpanId }}</code>
                        </div>
                        <button class="btn btn-sm btn-outline-secondary" @click="closeDrawer">Close</button>
                      </div>
                      <div class="card-body">
                        <div v-if="detailLoading" class="text-muted">Loading…</div>
                        <template v-else-if="detail && detail.summary">
                          <div v-if="detail.contentBanner && !detail.contentCaptured" class="alert alert-info small">
                            <i class="bi bi-info-circle me-1"></i>{{ detail.contentBanner }}
                          </div>
                          <dl class="row mb-3">
                            <dt class="col-sm-3">Provider</dt>
                            <dd class="col-sm-9">{{ detail.summary.provider || '—' }}</dd>
                            <dt class="col-sm-3">Request model</dt>
                            <dd class="col-sm-9">
                              <code>{{ detail.summary.requestModel || '—' }}</code>
                            </dd>
                            <dt class="col-sm-3">Response model</dt>
                            <dd class="col-sm-9">
                              <code>{{ detail.summary.responseModel || '—' }}</code>
                            </dd>
                            <dt class="col-sm-3">Tokens</dt>
                            <dd class="col-sm-9">
                              in {{ formatNumber(detail.summary.inputTokens) }} · out
                              {{ formatNumber(detail.summary.outputTokens) }} · total
                              {{ formatNumber(detail.summary.totalTokens) }}
                            </dd>
                            <dt class="col-sm-3">Duration</dt>
                            <dd class="col-sm-9">{{ formatDuration(detail.summary.durationNanos) }}</dd>
                            <dt class="col-sm-3">Finish reason</dt>
                            <dd class="col-sm-9">{{ detail.summary.finishReason || '—' }}</dd>
                          </dl>

                          <div v-if="detail.toolCalls && detail.toolCalls.length" class="mb-3">
                            <h6>Tool calls</h6>
                            <ul class="list-group">
                              <li
                                v-for="tc in detail.toolCalls"
                                :key="tc.spanId"
                                class="list-group-item d-flex justify-content-between"
                              >
                                <span
                                  ><i class="bi bi-tools me-1"></i><code>{{ tc.name || '(unnamed)' }}</code></span
                                >
                                <span class="text-muted small">{{ formatDuration(tc.durationNanos) }}</span>
                              </li>
                            </ul>
                          </div>

                          <div v-if="detail.vectorOperations && detail.vectorOperations.length" class="mb-3">
                            <h6>Vector operations</h6>
                            <ul class="list-group">
                              <li
                                v-for="vo in detail.vectorOperations"
                                :key="vo.spanId"
                                class="list-group-item d-flex justify-content-between"
                              >
                                <span
                                  ><i class="bi bi-database me-1"></i><code>{{ vo.collectionName || '?' }}</code> ·
                                  {{ vo.operation || '—' }}</span
                                >
                                <span class="text-muted small">{{ formatDuration(vo.durationNanos) }}</span>
                              </li>
                            </ul>
                          </div>

                          <details v-if="detail.attributes && detail.attributes.length">
                            <summary class="text-muted">Span attributes ({{ detail.attributes.length }})</summary>
                            <table class="table table-sm mt-2">
                              <tbody>
                                <tr v-for="a in detail.attributes" :key="a.key">
                                  <td>
                                    <code>{{ a.key }}</code>
                                  </td>
                                  <td>
                                    <code>{{ a.value }}</code>
                                  </td>
                                </tr>
                              </tbody>
                            </table>
                          </details>
                        </template>
                        <div v-else-if="detail && detail.error" class="alert alert-danger small">
                          {{ detail.error }}
                        </div>
                        <div v-else class="text-muted small">No detail available.</div>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
code {
  overflow-wrap: anywhere;
}
</style>
