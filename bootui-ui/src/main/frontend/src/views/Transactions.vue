<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, ref} from 'vue'
import {formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
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
const {message: banner, flash, clear: clearBanner} = useFlashMessage()
const filter = ref('')
const statusFilter = ref('')
const slowOnly = ref(false)
const busy = ref(null)
const lastFetched = ref(null)
const expanded = ref(new Set())
const collapsedNodes = ref(new Set())

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/transactions')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load transactions')
  }
}

const {autoRefresh, loading, initialLoading, load, retryConnection, connectionState} = useEventStreamRefresh(
  'api/transactions/stream',
  fetchReport
)

const stats = computed(() => report.value?.stats ?? null)
const entries = computed(() => report.value?.entries ?? [])

const filteredEntries = computed(() => {
  const value = filter.value.trim().toLowerCase()
  const status = statusFilter.value
  return entries.value.filter((entry) => {
    if (status && entry.status !== status) return false
    if (slowOnly.value && !entry.slow) return false
    if (!value) return true
    return [
      entry.methodName,
      entry.propagation,
      entry.isolation,
      entry.status,
      entry.thread,
      entry.traceId,
      entry.errorMessage
    ]
      .join(' ')
      .toLowerCase()
      .includes(value)
  })
})

// Build a parent/child tree from the flat, most-recent-first entry list so nested transactions
// render under the boundary that was active when they began. An entry whose parentId is not
// present in the current buffer (its parent was already evicted) is treated as a root so nothing
// is silently dropped from the tree.
const tree = computed(() => {
  const byId = new Map(filteredEntries.value.map((entry) => [entry.id, entry]))
  const childrenByParent = new Map()
  const roots = []
  for (const entry of filteredEntries.value) {
    if (entry.parentId != null && byId.has(entry.parentId)) {
      const siblings = childrenByParent.get(entry.parentId) ?? []
      siblings.push(entry)
      childrenByParent.set(entry.parentId, siblings)
    } else {
      roots.push(entry)
    }
  }
  return {roots, childrenByParent}
})

function children(entry) {
  return tree.value.childrenByParent.get(entry.id) ?? []
}

function statusClass(status) {
  return (
    {
      COMMITTED: 'text-bg-success',
      ROLLED_BACK: 'text-bg-danger',
      UNKNOWN: 'text-bg-secondary'
    }[status] || 'text-bg-secondary'
  )
}

function toggleRow(entry) {
  const next = new Set(expanded.value)
  if (next.has(entry.id)) next.delete(entry.id)
  else next.add(entry.id)
  expanded.value = next
}

function isExpanded(entry) {
  return expanded.value.has(entry.id)
}

function toggleNode(entry) {
  const next = new Set(collapsedNodes.value)
  if (next.has(entry.id)) next.delete(entry.id)
  else next.add(entry.id)
  collapsedNodes.value = next
}

function isNodeCollapsed(entry) {
  return collapsedNodes.value.has(entry.id)
}

const subtitle = computed(() => {
  if (!report.value || !report.value.available) return null
  const s = stats.value
  const parts = [
    `${formatNumber(s.totalTransactions)} retained`,
    `${formatNumber(report.value.totalCaptured)} captured since startup`
  ]
  if (s.slowTransactions) parts.push(`${formatNumber(s.slowTransactions)} slow`)
  if (s.rolledBackCount) parts.push(`${formatNumber(s.rolledBackCount)} rolled back`)
  parts.push(report.value.capturing ? 'recording' : 'paused')
  return parts.join(' · ')
})

async function applyAction(action, options) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (options.confirm && !(await confirm(options.confirm))) return
  busy.value = action
  clearBanner()
  try {
    const res = await apiFetch(options.url, options.init)
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    report.value = result
    lastFetched.value = Date.now()
    if (options.onSuccess) options.onSuccess(result)
    flash(options.success(result), 'success')
  } catch (e) {
    flash(formatLoadError(e, options.failure), 'danger')
  } finally {
    busy.value = null
  }
}

function toggleRecording() {
  const next = !report.value?.capturing
  applyAction('recording', {
    url: 'api/transactions/recording',
    init: {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({enabled: next})},
    success: () => (next ? 'Recording resumed.' : 'Recording paused; existing transactions are kept.'),
    failure: 'Could not change recording state'
  })
}

function clearTransactions() {
  applyAction('clear', {
    url: 'api/transactions/clear',
    init: {method: 'POST'},
    confirm: {
      title: 'Clear transaction trace?',
      message: 'Clear all captured transaction boundaries from the in-memory trace buffer.',
      confirmLabel: 'Clear',
      danger: true
    },
    onSuccess: () => {
      expanded.value = new Set()
      collapsedNodes.value = new Set()
    },
    success: () => 'Transaction trace cleared.',
    failure: 'Could not clear transaction trace'
  })
}

// useEventStreamRefresh automatically loads on mount unless configured otherwise
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-diagram-3-fill"
      title="Transactions"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      :auto-refresh-state="connectionState"
      @refresh="load"
      @retry-auto-refresh="retryConnection"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy === 'recording'"
          :disabled="!report || !report.available || readOnly || busy"
          class="ms-2"
          :class="report && report.capturing ? 'btn btn-sm btn-outline-warning' : 'btn btn-sm btn-outline-success'"
          :icon="report && report.capturing ? 'bi-pause-fill' : 'bi-record-fill'"
          :label="report && report.capturing ? 'Pause' : 'Resume'"
          @click="toggleRecording"
        />
        <SpinnerButton
          :loading="busy === 'clear'"
          :disabled="!report || !report.available || readOnly || busy || !stats || stats.totalTransactions === 0"
          class="btn btn-sm btn-outline-danger ms-2"
          icon="bi-trash"
          label="Clear"
          @click="clearTransactions"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clearBanner" />

    <PanelSkeleton v-if="initialLoading && !report" />

    <template v-else-if="report">
      <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small py-2">
        {{ warning }}
      </div>

      <div v-if="!report.available" class="alert alert-secondary">
        {{ report.unavailableReason || 'Transaction boundary capture is not available.' }}
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Recording controls are read-only.</ReadOnlyNotice>

        <section class="mb-4">
          <div class="row g-2 stat-cards">
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Retained</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(stats.totalTransactions) }}</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Avg time</div>
                  <div class="fs-5 fw-semibold">{{ stats.avgDurationMillis.toFixed(1) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Longest</div>
                  <div class="fs-5 fw-semibold">{{ formatNumber(stats.maxDurationMillis) }} ms</div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">
                    Slow (&ge;{{ formatNumber(report.slowTransactionThresholdMillis) }} ms)
                  </div>
                  <div class="fs-5 fw-semibold" :class="{'text-warning': stats.slowTransactions > 0}">
                    {{ formatNumber(stats.slowTransactions) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Rolled back</div>
                  <div class="fs-5 fw-semibold" :class="{'text-danger': stats.rolledBackCount > 0}">
                    {{ formatNumber(stats.rolledBackCount) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="col-6 col-md-3 col-xl-2">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="text-muted small">Outcomes</div>
                  <div class="tx-status-counts">
                    <span class="badge text-bg-success">C {{ formatNumber(stats.committedCount) }}</span>
                    <span class="badge text-bg-danger">R {{ formatNumber(stats.rolledBackCount) }}</span>
                    <span class="badge text-bg-secondary">? {{ formatNumber(stats.unknownCount) }}</span>
                    <span class="badge text-bg-info" title="Transactions nested inside another transaction"
                      >N {{ formatNumber(stats.nestedCount) }}</span
                    >
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section>
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h5 class="mb-0">
              Recent transactions <span class="badge bg-secondary">{{ filteredEntries.length }}</span>
            </h5>
            <div class="d-flex flex-wrap gap-2">
              <select
                v-model="statusFilter"
                aria-label="Filter transactions by outcome"
                class="form-select form-select-sm tx-filter-select"
              >
                <option value="">All outcomes</option>
                <option value="COMMITTED">Committed</option>
                <option value="ROLLED_BACK">Rolled back</option>
                <option value="UNKNOWN">Unknown</option>
              </select>
              <div class="form-check form-switch d-flex align-items-center">
                <input
                  id="tx-slow-only"
                  v-model="slowOnly"
                  class="form-check-input me-1"
                  type="checkbox"
                  role="switch"
                />
                <label class="form-check-label small" for="tx-slow-only">Slow only</label>
              </div>
              <input
                v-model="filter"
                aria-label="Filter transactions"
                class="form-control form-control-sm trace-filter"
                placeholder="Filter by method, propagation, isolation, thread, or trace id…"
              />
            </div>
          </div>

          <div v-if="entries.length === 0" class="alert alert-secondary small">
            No transactions have been captured yet. Exercise a <code>@Transactional</code> method and refresh to see
            boundaries.
          </div>

          <div v-else-if="tree.roots.length" class="table-responsive">
            <table class="table table-sm table-hover align-middle tx-table">
              <thead>
                <tr>
                  <th style="width: 2rem"></th>
                  <th>Method</th>
                  <th>Time</th>
                  <th class="text-end">Duration</th>
                  <th>Propagation</th>
                  <th>Isolation</th>
                  <th class="text-end">SQL / Conn</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="root in tree.roots" :key="root.id">
                  <tr
                    class="tx-row"
                    data-keyboard-delegate="toggleRow(root)"
                    :style="{'--tx-depth': 0}"
                    @click="toggleRow(root)"
                  >
                    <td class="text-muted">
                      <button
                        class="btn btn-sm btn-link p-0 bootui-keyboard-target tx-row-toggle"
                        type="button"
                        :aria-controls="`tx-details-${root.id}`"
                        :aria-expanded="isExpanded(root)"
                        :aria-label="`${isExpanded(root) ? 'Collapse' : 'Expand'} details for ${root.methodName}`"
                        @click.stop="toggleRow(root)"
                      >
                        <i
                          aria-hidden="true"
                          class="bi"
                          :class="isExpanded(root) ? 'bi-chevron-down' : 'bi-chevron-right'"
                        ></i>
                      </button>
                      <button
                        v-if="children(root).length"
                        class="btn btn-sm btn-link p-0 ms-1 bootui-keyboard-target tx-node-toggle"
                        type="button"
                        :aria-expanded="!isNodeCollapsed(root)"
                        :aria-label="`${isNodeCollapsed(root) ? 'Expand' : 'Collapse'} nested transactions of ${root.methodName}`"
                        @click.stop="toggleNode(root)"
                      >
                        <i
                          aria-hidden="true"
                          class="bi"
                          :class="isNodeCollapsed(root) ? 'bi-plus-square' : 'bi-dash-square'"
                        ></i>
                      </button>
                    </td>
                    <td class="tx-method">
                      <code>{{ root.methodName }}</code>
                      <span v-if="children(root).length" class="badge text-bg-secondary ms-1"
                        >{{ children(root).length }} nested</span
                      >
                    </td>
                    <td class="text-nowrap font-monospace small">{{ formatClockTime(root.startTimestamp) }}</td>
                    <td class="text-end text-nowrap" :class="{'text-warning fw-semibold': root.slow}">
                      {{ formatNumber(root.durationMillis) }} ms
                    </td>
                    <td>
                      <span class="badge text-bg-light border text-dark">{{ root.propagation }}</span>
                    </td>
                    <td>
                      <span class="badge text-bg-light border text-dark">{{ root.isolation }}</span>
                    </td>
                    <td class="text-end text-nowrap">{{ root.sqlStatementCount }} / {{ root.connectionCount }}</td>
                    <td>
                      <span :class="statusClass(root.status)" class="badge">{{ root.status }}</span>
                      <span v-if="root.slow" class="badge text-bg-warning ms-1">slow</span>
                      <span
                        v-if="root.connectionHeld"
                        class="badge text-bg-danger ms-1"
                        title="Held a connection too long"
                        >held</span
                      >
                    </td>
                  </tr>
                  <tr v-if="isExpanded(root)" :id="`tx-details-${root.id}`" class="tx-detail-row">
                    <td></td>
                    <td colspan="7">
                      <dl class="row mb-0 small">
                        <dt class="col-sm-2">Thread</dt>
                        <dd class="col-sm-10">
                          <code>{{ root.thread || '—' }}</code>
                        </dd>
                        <dt class="col-sm-2">Trace id</dt>
                        <dd class="col-sm-10">
                          <code>{{ root.traceId || '—' }}</code>
                        </dd>
                        <dt class="col-sm-2">Read-only</dt>
                        <dd class="col-sm-10">{{ root.readOnly ? 'Yes' : 'No' }}</dd>
                        <template v-if="root.errorMessage">
                          <dt class="col-sm-2 text-danger">Error</dt>
                          <dd class="col-sm-10 text-danger">{{ root.errorMessage }}</dd>
                        </template>
                      </dl>
                    </td>
                  </tr>
                  <template v-if="!isNodeCollapsed(root)">
                    <template v-for="child in children(root)" :key="child.id">
                      <tr
                        class="tx-row tx-row-nested"
                        data-keyboard-delegate="toggleRow(child)"
                        @click="toggleRow(child)"
                      >
                        <td class="text-muted ps-4">
                          <button
                            class="btn btn-sm btn-link p-0 bootui-keyboard-target tx-row-toggle"
                            type="button"
                            :aria-controls="`tx-details-${child.id}`"
                            :aria-expanded="isExpanded(child)"
                            :aria-label="`${isExpanded(child) ? 'Collapse' : 'Expand'} details for ${child.methodName}`"
                            @click.stop="toggleRow(child)"
                          >
                            <i
                              aria-hidden="true"
                              class="bi"
                              :class="isExpanded(child) ? 'bi-chevron-down' : 'bi-chevron-right'"
                            ></i>
                          </button>
                        </td>
                        <td class="tx-method ps-3">
                          <i aria-hidden="true" class="bi bi-arrow-return-right text-muted me-1"></i>
                          <code>{{ child.methodName }}</code>
                        </td>
                        <td class="text-nowrap font-monospace small">{{ formatClockTime(child.startTimestamp) }}</td>
                        <td class="text-end text-nowrap" :class="{'text-warning fw-semibold': child.slow}">
                          {{ formatNumber(child.durationMillis) }} ms
                        </td>
                        <td>
                          <span class="badge text-bg-light border text-dark">{{ child.propagation }}</span>
                        </td>
                        <td>
                          <span class="badge text-bg-light border text-dark">{{ child.isolation }}</span>
                        </td>
                        <td class="text-end text-nowrap">
                          {{ child.sqlStatementCount }} / {{ child.connectionCount }}
                        </td>
                        <td>
                          <span :class="statusClass(child.status)" class="badge">{{ child.status }}</span>
                          <span v-if="child.slow" class="badge text-bg-warning ms-1">slow</span>
                        </td>
                      </tr>
                      <tr v-if="isExpanded(child)" :id="`tx-details-${child.id}`" class="tx-detail-row">
                        <td></td>
                        <td colspan="7">
                          <dl class="row mb-0 small">
                            <dt class="col-sm-2">Thread</dt>
                            <dd class="col-sm-10">
                              <code>{{ child.thread || '—' }}</code>
                            </dd>
                            <dt class="col-sm-2">Trace id</dt>
                            <dd class="col-sm-10">
                              <code>{{ child.traceId || '—' }}</code>
                            </dd>
                            <dt class="col-sm-2">Read-only</dt>
                            <dd class="col-sm-10">{{ child.readOnly ? 'Yes' : 'No' }}</dd>
                            <template v-if="child.errorMessage">
                              <dt class="col-sm-2 text-danger">Error</dt>
                              <dd class="col-sm-10 text-danger">{{ child.errorMessage }}</dd>
                            </template>
                          </dl>
                        </td>
                      </tr>
                    </template>
                  </template>
                </template>
              </tbody>
            </table>
          </div>

          <div v-else class="text-muted small">No transactions match the current filter.</div>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.trace-filter {
  max-width: 24rem;
}

.tx-filter-select {
  max-width: 12rem;
}

.tx-status-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
}

.tx-row {
  cursor: pointer;
}

.tx-row-nested td {
  background-color: var(--bs-tertiary-bg);
}

.tx-detail-row > td {
  background-color: var(--bs-tertiary-bg);
}

.tx-method code {
  word-break: break-word;
}
</style>
