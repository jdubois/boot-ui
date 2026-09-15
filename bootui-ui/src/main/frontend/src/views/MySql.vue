<script setup>
import {computed, onMounted, ref} from 'vue'
import {actionBusyMessage, getJson, isActionBusyError} from '../api.js'
import {formatClockTime} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {formatCounter} from '../utils/mysqlFormat.js'
import {mysqlColumns} from '../utils/mysqlColumns.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'
import MySqlTable from './components/MySqlTable.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const actionMessage = ref(null)
const loading = ref(false)
const initialLoading = ref(true)
const activeSections = ref({})

const dataSources = computed(() => report.value?.dataSources || [])
const hasRead = computed(() => report.value && report.value.status !== 'NOT_READ')
const diagnostics = computed(() => report.value?.diagnostics || [])
const limitations = computed(() => report.value?.limitations || [])

const scopes = {
  SERVER: 'Server-wide',
  SELECTED_SCHEMA: 'Selected schema',
  DEFAULT_SCHEMA_ASSOCIATED: 'Default-schema associated'
}
const statusLabels = {
  READ: 'Read',
  LIMITED: 'Limited',
  PARTIAL: 'Partly read',
  ERROR: 'Unreadable',
  DISABLED: 'Unavailable',
  AVAILABLE: 'Available',
  SKIPPED: 'Skipped',
  FAILED: 'Failed'
}

function statusClass(status) {
  if (status === 'READ' || status === 'AVAILABLE') return 'text-bg-success'
  if (status === 'PARTIAL') return 'text-bg-warning'
  if (status === 'ERROR' || status === 'FAILED') return 'text-bg-danger'
  return 'text-bg-secondary'
}

function sectionStatus(part) {
  if (part.status !== 'AVAILABLE') return part.status
  return part.reason ? 'PARTIAL' : part.truncated ? 'LIMITED' : 'AVAILABLE'
}

function completeSections(source) {
  return (
    !source.message &&
    source.sections?.length &&
    source.sections.every((part) => part.status === 'AVAILABLE' && !part.reason)
  )
}

function sourceStatus(source) {
  return source.status === 'PARTIAL' && source.truncated && completeSections(source) ? 'LIMITED' : source.status
}

function rowLimitMessage(part) {
  return part.id === 'statements' && !part.reason
    ? `Showing the top ${part.rowCount} statements by total execution time. Additional statements are not shown.`
    : `Showing ${part.rowCount} retained rows. Additional rows are not shown because this section reached BootUI's row limit.`
}

const cappedSections = computed(() =>
  dataSources.value.flatMap((source) =>
    (source.sections || [])
      .filter((part) => part.truncated)
      .map((part) => `${source.name} / ${part.title}: ${rowLimitMessage(part)}`)
  )
)
// The current contract carries prose limitations, not a typed limitation kind. Treat only
// a known row-cap note as such; an unrelated discovery/coverage warning stays incomplete.
const capLimitations = computed(() =>
  dataSources.value.flatMap((source) =>
    (source.sections || [])
      .filter((part) => part.truncated)
      .map(
        (part) =>
          `${source.name}: ${part.title} retained ${part.rowCount} rows; additional rows were omitted by BootUI caps.`
      )
  )
)
const onlyRowLimits = computed(
  () =>
    report.value?.status === 'PARTIAL' &&
    report.value?.truncated &&
    cappedSections.value.length > 0 &&
    limitations.value.every((item) => capLimitations.value.includes(item) || cappedSections.value.includes(item)) &&
    diagnostics.value.every((item) => item.level === 'INFO') &&
    dataSources.value.every(
      (source) =>
        (source.status === 'READ' || (source.status === 'PARTIAL' && source.truncated)) && completeSections(source)
    )
)
const incomplete = computed(() => !onlyRowLimits.value && ['PARTIAL', 'ERROR'].includes(report.value?.status))
const partialReasons = computed(() => [
  ...new Set(
    [
      ...(onlyRowLimits.value ? [] : [report.value?.message]),
      ...dataSources.value.flatMap((source) => [
        source.message ? `${source.name}: ${source.message}` : null,
        ...(source.sections || []).flatMap((part) => [
          part.reason ? `${source.name} / ${part.title}: ${part.reason}` : null,
          part.truncated ? `${source.name} / ${part.title}: ${rowLimitMessage(part)}` : null
        ])
      ]),
      ...limitations.value
    ].filter(Boolean)
  )
])

function section(source, id) {
  return (source.sections || []).find((part) => part.id === id)
}
function scopedRows(rows) {
  return (rows || []).map((row) => ({...row, scope: scopes[row.scope] || row.scope}))
}
function lockRows(source, kind) {
  return (source.lockWaits || []).filter((row) => row.kind === kind)
}
function sectionRows(source, id) {
  return id === 'innodb' || id === 'settings' ? scopedRows(source[id]) : source[id] || []
}
const sectionExplanations = {
  sessions:
    'Default schema is an association, not proof of every schema a session touches. State age is not query age. A sleeping session can hold an open transaction.',
  statements:
    'Server-normalized default-schema digests, not SQL Trace from this JVM. Raw SQL and sample text are never shown.',
  indexes:
    'Handler operations, not query counts or physical disk reads. No recorded reads does not mean an index is safe to drop. The no-index / insert bucket is not an index verdict.',
  tables:
    'Row counts and storage sizes are estimates with normal MySQL statistics freshness. BootUI does not read application rows or force a statistics refresh.',
  innodb:
    'Server-wide InnoDB counters. Collection and timing can be disabled independently; BootUI never enables instrumentation. This is not PostgreSQL vacuum evidence.',
  replication:
    'Local receiver/applier channel observations only, not a downstream topology or an end-to-end replication lag measurement.',
  settings:
    'Fixed allow-listed operational settings with explicit provenance. No arbitrary variable dump or values from BootUI’s modified inspection session.'
}
function emptyMessage(part) {
  if (part.reason) return 'No rows were retained; this incomplete read does not establish absence.'
  if (part.id === 'replication')
    return 'No local replication channels were observed in this successful read. Downstream topology was not inspected.'
  return 'No rows were observed within this read’s scope.'
}
function tabSections(source) {
  return (source.sections || []).filter((part) => part.id !== 'vital-signs')
}
function activeSection(source) {
  const parts = tabSections(source)
  return parts.find((part) => part.id === activeSections.value[source.name]) || parts[0]
}
function selectSection(source, id) {
  activeSections.value = {...activeSections.value, [source.name]: id}
}
function handleTabKeydown(event, source, index) {
  const parts = tabSections(source)
  const next = {
    ArrowRight: (index + 1) % parts.length,
    ArrowLeft: (index - 1 + parts.length) % parts.length,
    Home: 0,
    End: parts.length - 1
  }[event.key]
  if (next === undefined) return
  event.preventDefault()
  selectSection(source, parts[next].id)
  event.currentTarget.closest('[role="tablist"]')?.querySelectorAll('[role="tab"]')[next]?.focus()
}

async function runRead() {
  if (
    initialLoading.value ||
    loading.value ||
    readOnly.value ||
    !manifestAvailable.value ||
    report.value?.status === 'DISABLED'
  )
    return
  loading.value = true
  actionMessage.value = null
  try {
    report.value = await getJson('api/mysql/read', {method: 'POST'})
    error.value = null
  } catch (failure) {
    if (isActionBusyError(failure)) {
      actionMessage.value = actionBusyMessage(failure)
      error.value = null
    } else {
      error.value = describeLoadError(failure, 'Unable to run the MySQL read')
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    if (manifestAvailable.value) report.value = await getJson('api/mysql')
  } catch (failure) {
    error.value = describeLoadError(failure, 'Unable to load the MySQL report')
  } finally {
    initialLoading.value = false
  }
})
</script>

<template>
  <div class="mysql-panel">
    <PanelHeader
      icon="bi-database-check"
      title="MySQL"
      subtitle="Bounded observations from MySQL's own statistics and catalogs, through the application's JDBC datasources. Read on demand, never in the background."
      :loading="loading"
      :error="error"
    >
      <template #actions>
        <SpinnerButton
          :loading="loading"
          :disabled="initialLoading || loading || readOnly || !manifestAvailable || report?.status === 'DISABLED'"
          class="btn btn-primary"
          type="button"
          icon="bi-play-circle"
          label="Run MySQL read"
          loading-label="Reading..."
          :aria-label="loading ? 'Reading MySQL' : 'Run MySQL read'"
          @click="runRead"
        />
      </template>
    </PanelHeader>
    <div v-if="actionMessage" class="alert alert-warning" role="status">{{ actionMessage }}</div>
    <p v-if="(error || actionMessage) && report?.readAt" class="small text-muted">
      The last completed report is still shown below. No retry runs automatically.
    </p>
    <PanelSkeleton v-if="initialLoading" />
    <UnavailableState v-else-if="!manifestAvailable" variant="info" role="note">{{
      manifestUnavailableReason
    }}</UnavailableState>
    <UnavailableState v-else-if="report?.status === 'DISABLED'" variant="info" icon="bi-database-slash">
      {{ report.message || 'No supported MySQL JDBC datasource is available.' }}
    </UnavailableState>
    <template v-else-if="report">
      <div class="alert alert-info small">
        <strong>Read-only observations, not an assessment.</strong>
        {{ report.disclaimer }}
        <span v-if="readOnly">{{ readOnlyReason }}</span>
      </div>
      <div v-if="!hasRead" class="card">
        <div class="card-body text-center py-5">
          <i class="bi bi-database-check fs-2 text-muted" aria-hidden="true"></i>
          <h3 class="fs-6 mt-2">No MySQL data yet</h3>
          <p class="text-muted mb-0">
            Run the MySQL read to inspect the configured server. MySQL 8.4 is the first tested server line; MariaDB is
            not supported.
          </p>
          <p v-if="report.message" class="small text-muted mt-2 mb-0">{{ report.message }}</p>
        </div>
      </div>
      <template v-else>
        <div
          class="d-flex flex-wrap align-items-center gap-2 small text-muted mb-3"
          :role="!incomplete && !error && !actionMessage && !readOnly ? 'status' : undefined"
        >
          <span class="badge" :class="statusClass(onlyRowLimits ? 'LIMITED' : report.status)">{{
            onlyRowLimits ? 'Limited results' : statusLabels[report.status] || report.status
          }}</span>
          <span v-if="report.readAt != null">Read at {{ formatClockTime(report.readAt) }}</span>
          <span v-if="report.readStartedAt != null">Collection began {{ formatClockTime(report.readStartedAt) }}</span>
          <span>{{ report.dataSourcesRead }} datasource{{ report.dataSourcesRead === 1 ? '' : 's' }} read</span>
        </div>
        <div
          v-if="incomplete"
          class="alert"
          :class="report.status === 'ERROR' ? 'alert-danger' : 'alert-warning'"
          role="status"
        >
          <strong>{{ report.status === 'ERROR' ? 'Read failed.' : 'Incomplete read.' }}</strong>
          <ul v-if="partialReasons.length" class="small mb-0 mt-2">
            <li v-for="reason in partialReasons" :key="reason">{{ reason }}</li>
          </ul>
          <span v-else>Some requested observations could not be read. See the section details and diagnostics.</span>
        </div>
        <details v-if="!incomplete && !onlyRowLimits && limitations.length" class="alert alert-info small">
          <summary>What this read does not cover ({{ limitations.length }})</summary>
          <ul class="mb-0 mt-2">
            <li v-for="reason in limitations" :key="reason">{{ reason }}</li>
          </ul>
        </details>

        <article
          v-for="(source, sourceIndex) in dataSources"
          :key="source.name"
          class="card mb-3"
          :aria-label="`${source.name} datasource`"
        >
          <div class="card-header d-flex flex-wrap align-items-center gap-2">
            <span class="badge" :class="statusClass(sourceStatus(source))">{{
              statusLabels[sourceStatus(source)] || source.status
            }}</span>
            <h3 class="fs-6 font-monospace mb-0">{{ source.name }}</h3>
            <span v-if="source.serverVersion" class="small font-monospace text-muted">{{ source.serverVersion }}</span>
            <span v-if="source.serverFlavor" class="small font-monospace text-muted">{{ source.serverFlavor }}</span>
            <span class="small text-muted"
              >Schema: <span class="font-monospace">{{ source.schemaName || 'not selected' }}</span></span
            >
            <span v-if="source.account" class="small text-muted"
              >Account: <span class="font-monospace">{{ source.account }}</span></span
            >
            <span v-if="source.readAt != null" class="small text-muted"
              >Observed {{ formatClockTime(source.readAt) }}</span
            >
          </div>
          <p v-if="source.message" class="card-body border-bottom small mb-0">{{ source.message }}</p>
          <div class="card-body border-bottom py-3">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <h4 class="fs-6 mb-0">Vital signs</h4>
              <span class="small text-muted">Server-wide unless labeled otherwise · do not sum across datasources</span>
              <span
                v-if="section(source, 'vital-signs')"
                class="badge"
                :class="statusClass(sectionStatus(section(source, 'vital-signs')))"
                >{{ statusLabels[sectionStatus(section(source, 'vital-signs'))] }}</span
              >
            </div>
            <p v-if="section(source, 'vital-signs')?.reason" class="small text-muted">
              {{ section(source, 'vital-signs').reason }}
            </p>
            <p v-if="section(source, 'vital-signs')?.hint" class="small text-muted">
              {{ section(source, 'vital-signs').hint }}
            </p>
            <dl v-if="source.vitalSigns?.length" class="row g-2 mb-2">
              <div v-for="metric in source.vitalSigns" :key="metric.id" class="col-6 col-md-3">
                <dt class="small text-muted fw-normal">{{ metric.label }}</dt>
                <dd class="font-monospace fw-semibold mb-0">
                  {{ formatCounter(metric.value)
                  }}<span v-if="metric.value != null && metric.unit" class="small fw-normal">{{
                    ` ${metric.unit}`
                  }}</span>
                </dd>
                <div v-if="metric.scope !== 'SERVER'" class="mysql-metric-scope text-muted">
                  {{ scopes[metric.scope] || metric.scope }}
                </div>
              </div>
            </dl>
            <p class="small text-muted mb-0">
              — means unknown or unavailable, not zero. Statistics describe a collection interval, not an atomic
              snapshot.
            </p>
          </div>
          <div v-if="tabSections(source).length" class="card-body pb-0">
            <div class="mysql-tabs" role="tablist" :aria-label="`${source.name} sections`">
              <button
                v-for="(part, index) in tabSections(source)"
                :id="`mysql-${sourceIndex}-tab-${part.id}`"
                :key="part.id"
                class="mysql-tabs__button"
                :class="{active: activeSection(source)?.id === part.id}"
                type="button"
                role="tab"
                :aria-selected="activeSection(source)?.id === part.id"
                :aria-controls="`mysql-${sourceIndex}-panel-${part.id}`"
                :tabindex="activeSection(source)?.id === part.id ? 0 : -1"
                @click="selectSection(source, part.id)"
                @keydown="handleTabKeydown($event, source, index)"
              >
                {{ part.title }}
                <span
                  v-if="part.status === 'AVAILABLE'"
                  class="mysql-tabs__count"
                  :title="part.id === 'sessions' ? 'Retained sessions and lock waits' : 'Retained rows'"
                  :aria-label="
                    part.id === 'sessions'
                      ? `${part.rowCount} retained sessions and lock waits`
                      : `${part.rowCount} retained rows`
                  "
                  >{{ part.rowCount }}</span
                >
                <span v-if="sectionStatus(part) !== 'AVAILABLE'" class="small">{{
                  statusLabels[sectionStatus(part)] || part.status
                }}</span>
              </button>
            </div>
          </div>
          <div
            v-if="activeSection(source)"
            :id="`mysql-${sourceIndex}-panel-${activeSection(source).id}`"
            :key="activeSection(source).id"
            class="card-body"
            role="tabpanel"
            tabindex="0"
            :aria-labelledby="`mysql-${sourceIndex}-tab-${activeSection(source).id}`"
          >
            <div class="d-flex flex-wrap gap-2 align-items-center mb-2">
              <h4 class="fs-6 mb-0">{{ activeSection(source).title }}</h4>
              <span class="badge" :class="statusClass(sectionStatus(activeSection(source)))">{{
                statusLabels[sectionStatus(activeSection(source))] || activeSection(source).status
              }}</span>
              <span class="small text-muted">{{ scopes[activeSection(source).scope] || 'Scope not reported' }}</span>
            </div>
            <p v-if="activeSection(source).reason" class="small mb-2">{{ activeSection(source).reason }}</p>
            <p v-if="activeSection(source).hint" class="small text-muted mb-2">{{ activeSection(source).hint }}</p>
            <p v-if="activeSection(source).truncated" class="small text-muted mb-2">
              {{ rowLimitMessage(activeSection(source)) }}
            </p>
            <p v-if="activeSection(source).status !== 'AVAILABLE'" class="small text-muted mb-0">
              This section was not read. Missing observations do not establish absence.
            </p>
            <template v-else>
              <p class="small text-muted mb-3">{{ sectionExplanations[activeSection(source).id] }}</p>
              <MySqlTable
                v-if="mysqlColumns[activeSection(source).id]"
                :rows="sectionRows(source, activeSection(source).id)"
                :columns="mysqlColumns[activeSection(source).id]"
                :label="`${source.name} ${activeSection(source).title.toLowerCase()}`"
                :empty-message="emptyMessage(activeSection(source))"
              />
              <p v-if="activeSection(source).id === 'statements'" class="small text-muted mt-2 mb-0">
                Missing text or timing means unavailable or withheld, not zero.
              </p>
              <template v-if="activeSection(source).id === 'sessions'">
                <h5 class="fs-6 mt-4">Row-lock waits</h5>
                <p class="small text-muted">
                  Selected-schema lock evidence: bounded waiting → blocking thread relationships. A blocking thread may
                  be outside the displayed session set. Lock key values are never read.
                </p>
                <MySqlTable
                  :rows="lockRows(source, 'ROW')"
                  :columns="mysqlColumns.rowLocks"
                  :label="`${source.name} row-lock waits`"
                  :empty-message="emptyMessage(activeSection(source))"
                />
                <h5 class="fs-6 mt-4">Pending metadata locks</h5>
                <p class="small text-muted">
                  Selected-schema pending requests only. Metadata-lock blockers are unknown; this is not an inferred
                  blocker graph.
                </p>
                <MySqlTable
                  :rows="lockRows(source, 'METADATA')"
                  :columns="mysqlColumns.metadataLocks"
                  :label="`${source.name} metadata locks`"
                  :empty-message="emptyMessage(activeSection(source))"
                />
              </template>
            </template>
          </div>
          <div v-if="source.capabilities?.length || source.changes?.length" class="card-body border-top">
            <details v-if="source.capabilities?.length" class="small">
              <summary>Collection capabilities and provenance ({{ source.capabilities.length }})</summary>
              <div class="mt-2">
                <MySqlTable
                  :rows="scopedRows(source.capabilities)"
                  :columns="mysqlColumns.capabilities"
                  :label="`${source.name} capabilities`"
                />
                <ul class="small text-muted mt-2 mb-0">
                  <li v-for="metric in source.vitalSigns || []" :key="metric.id">
                    {{ metric.label }}: <span class="font-monospace">{{ metric.source || 'Source not reported' }}</span>
                  </li>
                </ul>
              </div>
            </details>
            <details v-if="source.changes?.length" class="small mt-2">
              <summary>Changes between compatible observations ({{ source.changes.length }})</summary>
              <ul class="mt-2 mb-0">
                <li v-for="(change, index) in source.changes" :key="index">
                  <span class="font-monospace">{{ change.metric }}</span
                  >:
                  <span class="font-monospace">{{ formatCounter(change.delta) }} {{ change.unit }}</span>
                  · {{ scopes[change.scope] || change.scope }} · {{ formatClockTime(change.previousReadAt) }}–{{
                    formatClockTime(change.readAt)
                  }}
                  <span v-if="change.qualification"> · {{ change.qualification }}</span>
                </li>
              </ul>
            </details>
          </div>
        </article>
        <details v-if="diagnostics.length" class="card mb-3" open>
          <summary class="card-header">Read diagnostics ({{ diagnostics.length }})</summary>
          <ul class="list-group list-group-flush">
            <li v-for="(item, index) in diagnostics" :key="index" class="list-group-item small">
              <span class="badge text-bg-secondary me-2">{{ item.level }}</span>
              <span class="font-monospace">{{ item.source }}</span> {{ item.message }}
            </li>
          </ul>
        </details>
      </template>
    </template>
  </div>
</template>

<style scoped>
.mysql-tabs {
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
  padding: 0.22rem;
}
.mysql-tabs__button {
  align-items: center;
  background: transparent;
  border: 0;
  border-radius: var(--bootui-radius-sm);
  color: var(--bootui-text-muted);
  display: inline-flex;
  flex-wrap: wrap;
  font-size: 0.875rem;
  font-weight: 700;
  gap: 0.4rem;
  min-height: 2.25rem;
  padding: 0.4rem 0.75rem;
}
.mysql-tabs__button:hover:not(.active) {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-nav-hover-color);
}
.mysql-tabs__button.active {
  background: var(--bootui-nav-active-bg);
  color: var(--bootui-nav-active-color);
}
.mysql-tabs__button:focus-visible,
[role='tabpanel']:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}
.mysql-tabs__count {
  border: 1px solid currentColor;
  border-radius: var(--bootui-radius-pill);
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
  min-width: 1.4rem;
  padding: 0 0.3rem;
  text-align: center;
}
.mysql-metric-scope {
  font-size: 0.75rem;
}
dd {
  overflow-wrap: anywhere;
}
@media (max-width: 575.98px) {
  .mysql-tabs {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .mysql-tabs__button {
    justify-content: center;
    min-width: 0;
  }
}
</style>
