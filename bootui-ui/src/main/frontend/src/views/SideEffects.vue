<script setup>
import {computed, onBeforeUnmount, ref} from 'vue'
import {getJson} from '../api.js'
import {formatMillis, formatNumber, formatRelative} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)

const PAGE = 50

const GROUPS = [
  {id: 'network', label: 'Network', icon: 'bi-hdd-network'},
  {id: 'files-processes', label: 'Files and processes', icon: 'bi-terminal'},
  {id: 'environment', label: 'Environment', icon: 'bi-sliders'},
  {id: 'threads-leaks', label: 'Threads and leaks', icon: 'bi-diagram-3'},
  {id: 'blocking', label: 'Blocking', icon: 'bi-sign-stop'},
  {id: 'security-sinks', label: 'Security sinks', icon: 'bi-shield-exclamation'}
]

const SENSOR_ORDER = [
  'network',
  'files',
  'processes',
  'environment',
  'thread-activity',
  'thread-locals',
  'resources',
  'blocking',
  'security-sinks'
]

const STATE = {
  recording: {label: 'Recording', badge: 'text-bg-success'},
  installing: {label: 'Installing', badge: 'text-bg-info'},
  'self-test-failed': {label: 'Self-test failed', badge: 'text-bg-danger'},
  disabled: {label: 'Disabled', badge: 'text-bg-secondary'},
  'not-claimed': {label: 'Not claimed', badge: 'text-bg-warning'},
  unavailable: {label: 'Unavailable', badge: 'text-bg-warning'},
  'not-available': {label: 'Not available', badge: 'text-bg-secondary'}
}

const SCOPE_LABELS = {
  route: 'Route',
  execution: 'Execution',
  startup: 'Startup',
  thread: 'Thread',
  unattributed: 'Unattributed',
  other: 'Other'
}

const SCOPE_ORDER = {
  route: 0,
  execution: 1,
  startup: 2,
  thread: 3,
  unattributed: 4,
  other: 99
}

const summary = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const activeTab = ref(GROUPS[0].id)
const sensorReports = ref({})
const sensorErrors = ref({})
const sensorLoading = ref({})
const now = ref(Date.now())

const available = computed(() => summary.value?.available === true)
const activeGroup = computed(() => GROUPS.find((group) => group.id === activeTab.value) ?? GROUPS[0])

const sensorsByGroup = computed(() => {
  const grouped = new Map(GROUPS.map((group) => [group.id, []]))
  for (const sensor of summary.value?.sensors ?? []) {
    const groupId = groupIdFor(sensor.group)
    if (!grouped.has(groupId)) grouped.set(groupId, [])
    grouped.get(groupId).push(sensor)
  }
  for (const group of grouped.values()) {
    group.sort((a, b) => sensorOrder(a.id) - sensorOrder(b.id))
  }
  return grouped
})

const recordingSensors = computed(
  () => (summary.value?.sensors ?? []).filter((sensor) => sensor.state === 'recording').length
)

const relativeTimer = setInterval(() => {
  now.value = Date.now()
}, 30_000)

onBeforeUnmount(() => clearInterval(relativeTimer))

function groupIdFor(label) {
  return (
    GROUPS.find((group) => group.label === label)?.id ??
    String(label || '')
      .toLowerCase()
      .replaceAll(' ', '-')
  )
}

function sensorsForGroup(id) {
  return sensorsByGroup.value.get(id) ?? []
}

function sensorOrder(id) {
  const index = SENSOR_ORDER.indexOf(id)
  return index < 0 ? SENSOR_ORDER.length : index
}

function canLoadSensor(sensor) {
  return sensor?.state !== 'not-available'
}

async function fetchSummary() {
  error.value = null
  try {
    summary.value = await getJson('api/side-effects')
    lastFetched.value = Date.now()
    sensorReports.value = {}
    sensorErrors.value = {}
    sensorLoading.value = {}
    if (summary.value?.available) {
      await loadGroupSensors(activeTab.value, {force: true})
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Side Effects')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchSummary, {
  enabled: manifestAvailable
})

async function loadGroupSensors(groupId, {force = false} = {}) {
  const sensors = sensorsForGroup(groupId).filter(canLoadSensor)
  await Promise.all(
    sensors.map((sensor) => {
      if (!force && (sensorReports.value[sensor.id] || sensorLoading.value[sensor.id])) return null
      return loadSensor(sensor, {append: false})
    })
  )
}

async function loadSensor(sensor, {append = false} = {}) {
  const existing = sensorReports.value[sensor.id]
  const offset = append ? existing?.rows?.length || 0 : 0
  sensorLoading.value = {...sensorLoading.value, [sensor.id]: true}
  sensorErrors.value = {...sensorErrors.value, [sensor.id]: null}
  try {
    const report = await getJson(
      `api/side-effects/sensor?sensor=${encodeURIComponent(sensor.id)}&offset=${offset}&limit=${PAGE}`
    )
    sensorReports.value = {
      ...sensorReports.value,
      [sensor.id]: {
        ...report,
        rows: append ? [...(existing?.rows ?? []), ...(report.rows ?? [])] : (report.rows ?? [])
      }
    }
  } catch (e) {
    sensorErrors.value = {
      ...sensorErrors.value,
      [sensor.id]: formatLoadError(e, `Unable to load ${sensor.label}`)
    }
  } finally {
    sensorLoading.value = {...sensorLoading.value, [sensor.id]: false}
  }
}

function selectTab(id) {
  if (activeTab.value === id) return
  activeTab.value = id
  loadGroupSensors(id)
}

function onTabKeydown(event, index) {
  if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const count = GROUPS.length
  let next = index
  if (event.key === 'ArrowRight') next = (index + 1) % count
  if (event.key === 'ArrowLeft') next = (index - 1 + count) % count
  if (event.key === 'Home') next = 0
  if (event.key === 'End') next = count - 1
  selectTab(GROUPS[next].id)
  event.currentTarget.closest('[role="tablist"]')?.querySelectorAll('[role="tab"]')[next]?.focus()
}

function stateOf(sensor) {
  return STATE[sensor.state] ?? {label: sensor.state || 'Unknown', badge: 'text-bg-secondary'}
}

function scopeLabel(scope) {
  return SCOPE_LABELS[scope] ?? scope ?? 'Unknown'
}

function attribution(row) {
  return row.attribution || (row.scope === 'other' ? 'Other activity' : '—')
}

function rowKey(row) {
  return [
    row.scope,
    row.attribution,
    row.sensor,
    row.kind,
    row.target,
    row.callSite,
    row.insideMethod,
    row.client,
    row.capture,
    row.firstSeen
  ].join('|')
}

function sensorRows(report) {
  return [...(report?.rows ?? [])].sort((a, b) => {
    const byScope = (SCOPE_ORDER[a.scope] ?? 50) - (SCOPE_ORDER[b.scope] ?? 50)
    if (byScope !== 0) return byScope
    return [a.attribution, a.target, a.callSite]
      .join('|')
      .localeCompare([b.attribution, b.target, b.callSite].join('|'))
  })
}

function moreRows(report) {
  const page = report?.page
  if (!page?.hasMore) return 0
  return Math.max(0, page.matched - (report.rows?.length ?? 0))
}

function formatTimestamp(epochMillis) {
  if (!epochMillis) return '—'
  return new Date(epochMillis).toLocaleString()
}

function formatSeen(epochMillis) {
  if (!epochMillis) return '—'
  return formatRelative(epochMillis, now.value)
}

function formatLifetime(row) {
  return `${formatMillis(row.totalMillis)} / ${formatMillis(row.maxMillis)}`
}

const CAPTURE = {
  captured: {label: 'Captured', badge: 'text-bg-success'},
  'not-captured': {label: 'Not captured by any panel', badge: 'text-bg-warning'},
  infrastructure: {label: 'Infrastructure', badge: 'text-bg-secondary'}
}

const PANEL_ROUTES = {
  'rest-client-trace': {path: '/rest-client-trace', label: 'REST Client Trace'},
  'sql-trace': {path: '/sql-trace', label: 'SQL Trace'},
  kafka: {path: '/kafka', label: 'Kafka'},
  rabbitmq: {path: '/rabbitmq', label: 'RabbitMQ'},
  jms: {path: '/jms', label: 'JMS'},
  email: {path: '/email', label: 'Email'}
}

function captureOf(row) {
  return CAPTURE[row.capture] ?? null
}

function capturingPanel(row) {
  return PANEL_ROUTES[row.capturedBy] ?? (row.capturedBy ? {path: null, label: row.capturedBy} : null)
}

function isNetwork(sensor) {
  return sensor?.id === 'network'
}

function hookStatus(value, label) {
  if (value === true) return `${label} yes`
  if (value === false) return `${label} no`
  if (value == null) return `${label} n/a`
  return `${label} ${value}`
}
</script>

<template>
  <div class="side-effects-panel">
    <PanelHeader
      icon="bi-box-arrow-up-right"
      title="Side Effects"
      subtitle="What the application does outside the JVM, per route and call site, from the BootUI agent."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <UnavailableState v-if="!manifestAvailable" icon="bi-box-arrow-up-right">
      {{ manifestUnavailableReason }}
      <div class="mt-2">
        <router-link to="/java-agent" class="side-effects-agent-link">Open the Java Agent panel</router-link>
        to attach the BootUI agent.
      </div>
    </UnavailableState>

    <PanelSkeleton v-else-if="initialLoading" />

    <template v-else-if="summary && !available">
      <UnavailableState icon="bi-box-arrow-up-right" class="side-effects-unavailable">
        {{ summary.unavailableReason }}
        <div class="mt-2">
          <router-link to="/java-agent" class="side-effects-agent-link">Open the Java Agent panel</router-link>
          to attach the BootUI agent.
        </div>
      </UnavailableState>
    </template>

    <template v-else-if="summary">
      <section class="card mb-4" aria-labelledby="side-effects-headline">
        <div class="card-body p-4">
          <h3 id="side-effects-headline" class="h4 fw-bold mb-1">
            {{ formatNumber(recordingSensors) }}
            {{ recordingSensors === 1 ? 'sensor is' : 'sensors are' }} recording side effects
          </h3>
          <p class="text-muted small mb-0">
            BootUI groups observations by route, thread family, target, and call site. Process rows show only the
            executable name; arguments and environment are never recorded. Network rows show only a host and port, never
            a byte sent or received.
          </p>
          <details v-if="summary.limitations?.length" class="mt-3 small side-effects-limitations">
            <summary>What these sensors cannot see ({{ summary.limitations.length }})</summary>
            <ul class="mb-0 mt-2">
              <li v-for="limitation in summary.limitations" :key="limitation">{{ limitation }}</li>
            </ul>
          </details>
        </div>
      </section>

      <ul class="nav nav-tabs mb-3" role="tablist" aria-label="Side Effects sensor groups">
        <li v-for="(group, index) in GROUPS" :key="group.id" class="nav-item" role="presentation">
          <button
            :id="`side-effects-tab-${group.id}`"
            :aria-controls="`side-effects-panel-${group.id}`"
            :aria-selected="activeTab === group.id"
            :class="{active: activeTab === group.id}"
            :tabindex="activeTab === group.id ? 0 : -1"
            class="nav-link"
            role="tab"
            type="button"
            @click="selectTab(group.id)"
            @keydown="onTabKeydown($event, index)"
          >
            <i :class="['bi', group.icon, 'me-1']" aria-hidden="true"></i>{{ group.label }}
          </button>
        </li>
      </ul>

      <section
        :id="`side-effects-panel-${activeGroup.id}`"
        :aria-labelledby="`side-effects-tab-${activeGroup.id}`"
        role="tabpanel"
        tabindex="0"
      >
        <section
          v-for="sensor in sensorsForGroup(activeGroup.id)"
          :key="sensor.id"
          class="side-effects-sensor mb-4"
          :aria-labelledby="`side-effects-sensor-${sensor.id}`"
        >
          <div class="d-flex flex-wrap align-items-start justify-content-between gap-2 mb-2">
            <div>
              <h3 :id="`side-effects-sensor-${sensor.id}`" class="h5 fw-semibold mb-1">{{ sensor.label }}</h3>
              <p v-if="sensor.reason && sensor.state !== 'not-available'" class="small text-muted mb-0">
                {{ sensor.reason }}
              </p>
            </div>
            <span :class="['badge', stateOf(sensor).badge, 'side-effects-state']">{{ stateOf(sensor).label }}</span>
          </div>

          <UnavailableState v-if="sensor.state === 'not-available'" icon="bi-slash-circle" class="side-effects-empty">
            {{ sensor.reason || 'Not available in this version.' }}
          </UnavailableState>

          <template v-else>
            <ul v-if="sensor.hooks?.length" class="list-inline small side-effects-hooks mb-2">
              <li v-for="hook in sensor.hooks" :key="hook.id" class="list-inline-item">
                <span class="badge text-bg-light border">
                  <code>{{ hook.id }}</code>
                  <span class="text-muted"> · {{ hook.type }}</span>
                  <span> · {{ hookStatus(hook.transformed, 'transformed') }}</span>
                  <span> · {{ hookStatus(hook.selfTest, 'self-test') }}</span>
                  <span v-if="hook.recorded != null"> · recorded {{ formatNumber(hook.recorded) }}</span>
                </span>
              </li>
            </ul>

            <div v-if="sensor.state !== 'recording'" class="alert alert-secondary small py-2 side-effects-state-note">
              <strong>{{ stateOf(sensor).label }}.</strong>
              {{ sensor.reason || 'This sensor is not recording rows right now.' }}
            </div>

            <div v-if="sensorErrors[sensor.id]" class="alert alert-danger" role="alert">
              {{ sensorErrors[sensor.id] }}
            </div>
            <p v-else-if="sensorLoading[sensor.id] && !sensorReports[sensor.id]" class="small text-muted">Loading…</p>
            <UnavailableState
              v-else-if="sensorReports[sensor.id] && !sensorReports[sensor.id].available"
              icon="bi-exclamation-triangle"
              variant="warning"
              class="side-effects-empty"
            >
              {{ sensorReports[sensor.id].unavailableReason }}
            </UnavailableState>
            <template v-else-if="sensorReports[sensor.id]">
              <UnavailableState
                v-if="!sensorRows(sensorReports[sensor.id]).length && sensor.state === 'recording'"
                icon="bi-record-circle"
                class="side-effects-empty"
              >
                {{
                  sensor.id === 'processes'
                    ? 'No process started yet in this run.'
                    : isNetwork(sensor)
                      ? 'No connection, datagram, or name lookup recorded yet in this run.'
                      : `No ${sensor.label.toLowerCase()} row has been recorded yet.`
                }}
              </UnavailableState>

              <template v-else-if="sensorRows(sensorReports[sensor.id]).length">
                <div class="table-responsive">
                  <table class="table table-sm align-middle side-effects-table">
                    <caption class="visually-hidden">
                      Side effects recorded by
                      {{
                        sensor.label
                      }}
                    </caption>
                    <thead v-if="isNetwork(sensor)">
                      <tr>
                        <th scope="col">Attribution</th>
                        <th scope="col">Host / name</th>
                        <th scope="col">Client</th>
                        <th scope="col">Captured</th>
                        <th scope="col">Call site</th>
                        <th scope="col" class="text-end">Count</th>
                        <th scope="col" class="text-end">Failed</th>
                        <th scope="col" class="text-end">Connected</th>
                        <th scope="col" class="text-end">Time (total / max ms)</th>
                        <th scope="col">Last seen</th>
                        <th scope="col">Requests</th>
                      </tr>
                    </thead>
                    <thead v-else>
                      <tr>
                        <th scope="col">Attribution</th>
                        <th scope="col">Command / target</th>
                        <th scope="col">Call site</th>
                        <th scope="col" class="text-end">Starts</th>
                        <th scope="col" class="text-end">Failed</th>
                        <th scope="col" class="text-end">Exits</th>
                        <th scope="col" class="text-end">Lifetime (total / max ms)</th>
                        <th scope="col">Last seen</th>
                        <th scope="col">Requests</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="row in sensorRows(sensorReports[sensor.id])"
                        :key="rowKey(row)"
                        :class="{'side-effects-row-other': row.scope === 'other'}"
                      >
                        <td>
                          <span
                            :class="['badge', row.scope === 'other' ? 'text-bg-secondary' : 'text-bg-light border']"
                            >{{ scopeLabel(row.scope) }}</span
                          >
                          <div class="small bootui-break-anywhere mt-1">{{ attribution(row) }}</div>
                        </td>
                        <td>
                          <code class="bootui-break-anywhere">{{ row.target || '—' }}</code>
                          <div v-if="row.kind" class="small text-muted">{{ row.kind }}</div>
                        </td>
                        <template v-if="isNetwork(sensor)">
                          <td>
                            <span v-if="row.client">{{ row.client }}</span>
                            <span v-else class="text-muted">unrecognized</span>
                          </td>
                          <td>
                            <template v-if="captureOf(row)">
                              <span :class="['badge', captureOf(row).badge, 'side-effects-capture']">{{
                                captureOf(row).label
                              }}</span>
                              <div v-if="capturingPanel(row)" class="small mt-1">
                                <router-link v-if="capturingPanel(row).path" :to="capturingPanel(row).path">{{
                                  capturingPanel(row).label
                                }}</router-link>
                                <span v-else>{{ capturingPanel(row).label }}</span>
                              </div>
                            </template>
                            <span v-else class="text-muted">—</span>
                          </td>
                        </template>
                        <td>
                          <code v-if="row.callSite" class="bootui-break-anywhere">{{ row.callSite }}</code>
                          <span v-else class="text-muted">—</span>
                          <div v-if="row.insideMethod" class="small text-muted bootui-break-anywhere">
                            inside {{ row.insideMethod }}
                          </div>
                        </td>
                        <td class="text-end">{{ formatNumber(row.count) }}</td>
                        <td class="text-end">{{ formatNumber(row.failed) }}</td>
                        <td v-if="isNetwork(sensor)" class="text-end">
                          {{ row.kind === 'connect' ? formatNumber(row.completed) : '—' }}
                        </td>
                        <td v-else class="text-end">
                          {{ formatNumber(row.completed) }}
                          <div v-if="row.nonZeroExits > 0" class="small">
                            <span class="badge text-bg-warning">{{ formatNumber(row.nonZeroExits) }} non-zero</span>
                            <span v-if="row.lastExitStatus != null" class="text-muted ms-1">
                              last {{ row.lastExitStatus }}</span
                            >
                          </div>
                        </td>
                        <td class="text-end">{{ formatLifetime(row) }}</td>
                        <td>
                          <span>{{ formatSeen(row.lastSeen) }}</span>
                          <div class="small text-muted">{{ formatTimestamp(row.lastSeen) }}</div>
                        </td>
                        <td>
                          <template v-if="row.exemplarRequestIds?.length">
                            <template v-for="(id, index) in row.exemplarRequestIds" :key="id">
                              <router-link :to="{path: '/activity', query: {request: id}}">
                                <code>{{ id }}</code> </router-link
                              ><template v-if="Number(index) < row.exemplarRequestIds.length - 1">, </template>
                            </template>
                          </template>
                          <span v-else class="text-muted">—</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>

                <button
                  v-if="moreRows(sensorReports[sensor.id])"
                  class="btn btn-outline-secondary btn-sm side-effects-load-more"
                  type="button"
                  :disabled="sensorLoading[sensor.id]"
                  @click="loadSensor(sensor, {append: true})"
                >
                  <i class="bi bi-plus-circle me-1" aria-hidden="true"></i>
                  Load {{ formatNumber(Math.min(PAGE, moreRows(sensorReports[sensor.id]))) }} more
                </button>
              </template>

              <details v-if="sensorReports[sensor.id].limitations?.length" class="mt-2 small side-effects-limitations">
                <summary>What this sensor cannot see ({{ sensorReports[sensor.id].limitations.length }})</summary>
                <ul class="mb-0 mt-2">
                  <li v-for="limitation in sensorReports[sensor.id].limitations" :key="limitation">
                    {{ limitation }}
                  </li>
                </ul>
              </details>
            </template>
          </template>
        </section>
      </section>
    </template>
  </div>
</template>

<style scoped>
.side-effects-table td,
.side-effects-table th {
  font-variant-numeric: tabular-nums;
}

.side-effects-state {
  white-space: nowrap;
}

.side-effects-hooks .badge {
  color: var(--bs-body-color);
  font-weight: 500;
}

.side-effects-row-other td {
  background: var(--bs-tertiary-bg);
}

.side-effects-row-other td:first-child {
  font-weight: 600;
}

.side-effects-limitations summary {
  cursor: pointer;
}
</style>
