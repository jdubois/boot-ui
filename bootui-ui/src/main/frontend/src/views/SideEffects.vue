<script setup>
import {computed, inject, onBeforeUnmount, ref} from 'vue'
import {getJson} from '../api.js'
import {formatMillis, formatNumber, formatRelative} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import AgentSensorToggle from './components/AgentSensorToggle.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import PanelTabs from './components/PanelTabs.vue'
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

/** The opt-in sensors, with what each records once added to bootui.agent.sensors or switched on (M5-14). */
const OPT_IN = {
  files: 'the path patterns of the files the application opens, deletes, moves, and copies',
  environment: 'the names read',
  'thread-activity':
    'the threads the application starts and the executors it creates per route, and those a request left running',
  resources: 'the streams, channels, and sockets a request left open or never closed, files only while files is on'
}

const STATE = {
  recording: {label: 'Recording', badge: 'text-bg-success'},
  installing: {label: 'Installing', badge: 'text-bg-info'},
  'self-test-failed': {label: 'Self-test failed', badge: 'text-bg-danger'},
  disabled: {label: 'Disabled', badge: 'text-bg-secondary'},
  'not-claimed': {label: 'Not claimed', badge: 'text-bg-warning'},
  unavailable: {label: 'Unavailable', badge: 'text-bg-warning'},
  'not-available': {label: 'Not available', badge: 'text-bg-secondary'},
  'not-applicable': {label: 'Not applicable', badge: 'text-bg-secondary'}
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

const ORIGIN_LABELS = {
  application: 'Application',
  library: 'Library',
  'class-path': 'Class path',
  jdk: 'JDK',
  logging: 'Logging',
  unknown: 'Unknown'
}

const LOCATION_LABELS = {
  'working-directory': 'Working directory',
  'temporary-directory': 'Temporary directory',
  home: 'Home',
  system: 'System',
  'java-home': 'Java home',
  elsewhere: 'Elsewhere'
}

const GROUPED_APART = new Set(['class-path', 'jdk', 'logging'])

/** Thread activity's rows of libraries' pools and the JDK's own threads, grouped apart from the application's. */
const THREADS_APART = new Set(['library', 'jdk'])

const SENSOR_COLUMNS = {
  processes: {target: 'Command', count: 'Starts', failed: true, exits: true, time: 'Lifetime (total / max ms)'},
  network: {target: 'Host / name', count: 'Count', failed: true, network: true, time: 'Time (total / max ms)'},
  files: {target: 'Path pattern', count: 'Operations', failed: true, origin: true, time: 'Time (total / max ms)'},
  environment: {target: 'Name', count: 'Reads', origin: true},
  'thread-activity': {
    target: 'Thread / executor',
    count: 'Started',
    origin: true,
    threads: true,
    failed: true,
    failedLabel: 'Reclaimed without shutdown',
    time: 'Executor lifetime (total / max ms)'
  },
  resources: {
    target: 'Resource',
    count: 'Resources',
    origin: true,
    resources: true,
    failed: true,
    failedLabel: 'Reclaimed without close()'
  },
  blocking: {
    target: 'Event loop / operation',
    count: 'Calls',
    failed: true,
    failedLabel: 'Interrupted or failed',
    time: 'Blocked (total / max ms)'
  }
}

const EMPTY_TEXT = {
  processes: 'No process started yet in this run.',
  network: 'No connection, datagram, or name lookup recorded yet in this run.',
  files: 'No file has been opened yet in this run.',
  environment: 'No environment variable or system property has been read yet in this run.',
  blocking: 'No blocking call has started on an event loop yet in this run.',
  'thread-activity': 'No thread has been started and no executor created yet in this run.',
  resources: 'No resource has been left open after its request or reclaimed without close() in this run.'
}

/** Who opened a resources row's resource: the application's code, or a library the application called. */
const RESOURCE_ORIGINS = {
  application: 'Opened by the application',
  library: 'Opened by a library the application called'
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
// The opt-in sensors' switches belong to the Java Agent panel, shown only while it is enabled and available (M5-14).
const panels = inject('panels', ref(null))
const canSwitch = computed(() => {
  const owner = (panels.value?.panels ?? []).find((panel) => panel.id === 'java-agent')
  return Boolean(owner) && owner.enabled !== false && owner.available !== false && owner.readOnly !== true
})

/** The switch answered the Java Agent report: its toggles show at once, then the summary is read again. */
function onSensorSwitched(report) {
  const toggles = new Map((report?.toggles ?? []).map((toggle) => [toggle.id, toggle]))
  if (summary.value?.sensors) {
    summary.value = {
      ...summary.value,
      sensors: summary.value.sensors.map((sensor) =>
        toggles.has(sensor.id) ? {...sensor, toggle: toggles.get(sensor.id)} : sensor
      )
    }
  }
  refreshAfterSwitch()
}

/** Reads the summary again once any refresh in flight ended, so a switch is never skipped by one. */
async function refreshAfterSwitch() {
  for (let attempt = 0; attempt < 20 && loading.value; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  await load()
}
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
    // Keep the active tab's rows on screen while they refresh, so an auto-refresh updates them in place rather than
    // blanking every table to "Loading…"; other tabs' rows are dropped and fetched again when their tab is opened.
    const active = new Set(sensorsForGroup(activeTab.value).map((sensor) => sensor.id))
    sensorReports.value = Object.fromEntries(Object.entries(sensorReports.value).filter(([id]) => active.has(id)))
    sensorErrors.value = Object.fromEntries(Object.entries(sensorErrors.value).filter(([id]) => active.has(id)))
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
  const shown = existing?.rows?.length || 0
  const offset = append ? shown : 0
  // A refresh asks for as many rows as are already shown, so rows loaded with "Load more" stay on screen.
  const limit = append ? PAGE : Math.max(PAGE, shown)
  sensorLoading.value = {...sensorLoading.value, [sensor.id]: true}
  try {
    const report = await getJson(
      `api/side-effects/sensor?sensor=${encodeURIComponent(sensor.id)}&offset=${offset}&limit=${limit}`
    )
    sensorErrors.value = {...sensorErrors.value, [sensor.id]: null}
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
    row.origin,
    row.location,
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

function columnsOf(sensor) {
  return SENSOR_COLUMNS[sensor.id] ?? {target: 'Target', count: 'Count', failed: true}
}

function columnCount(sensor) {
  const columns = columnsOf(sensor)
  return (
    5 +
    (columns.failed ? 1 : 0) +
    (columns.exits ? 1 : 0) +
    (columns.time ? 1 : 0) +
    (columns.origin ? 1 : 0) +
    (columns.network ? 3 : 0) +
    (columns.threads ? 3 : 0) +
    (columns.resources ? 2 : 0)
  )
}

function groupedApart(row) {
  return row.sensor === 'thread-activity' ? THREADS_APART.has(row.origin) : GROUPED_APART.has(row.origin)
}

/** What a sensor's rows grouped apart are. */
function apartLabel(sensor) {
  return sensor.id === 'thread-activity' ? 'Libraries and the JDK' : 'Class path, JDK, and logging'
}

/** How many a request started or created, on average, of a thread-activity row. */
function perRequest(row) {
  if (!row.requests) return '—'
  const average = row.count / row.requests
  return Number.isInteger(average) ? formatNumber(average) : average.toFixed(1)
}

/**
 * The application's rows, then, collapsed, those grouped apart: class loading, the JDK, and logging, or for thread
 * activity libraries' pools and the JDK's own threads.
 */
function sections(report) {
  const rows = sensorRows(report)
  const own = rows.filter((row) => !groupedApart(row))
  const apart = rows.filter(groupedApart)
  const list = []
  if (own.length || !apart.length) list.push({id: 'own', apart: false, rows: own})
  if (apart.length) list.push({id: 'apart', apart: true, rows: apart})
  return list
}

function originLabel(origin, sensor) {
  if (sensor === 'resources' && RESOURCE_ORIGINS[origin]) return RESOURCE_ORIGINS[origin]
  return ORIGIN_LABELS[origin] ?? origin
}

function locationLabel(location) {
  return LOCATION_LABELS[location] ?? location
}

function emptyText(sensor) {
  return EMPTY_TEXT[sensor.id] ?? `No ${sensor.label.toLowerCase()} row has been recorded yet.`
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
            executable name, network rows a host and port, file rows a path pattern, and environment rows a name:
            arguments, bytes sent or received, file contents, and values are never recorded. Blocking rows show calls
            that blocked an event loop, reported, never refused. Thread activity rows show the threads and executors a
            route started, and those still running when its request ended; never what a thread holds.
          </p>
          <details v-if="summary.limitations?.length" class="mt-3 small side-effects-limitations">
            <summary>What these sensors cannot see ({{ summary.limitations.length }})</summary>
            <ul class="mb-0 mt-2">
              <li v-for="limitation in summary.limitations" :key="limitation">{{ limitation }}</li>
            </ul>
          </details>
        </div>
      </section>

      <PanelTabs
        class="mb-3"
        :tabs="GROUPS"
        :selected="activeTab"
        id-prefix="side-effects"
        label="Side Effects sensor groups"
        @select="selectTab"
      />

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

            <AgentSensorToggle
              v-if="sensor.toggle"
              :toggle="sensor.toggle"
              class="side-effects-toggle mb-2"
              @switched="onSensorSwitched"
              @stale="refreshAfterSwitch"
            />

            <div v-if="sensor.state !== 'recording'" class="alert alert-secondary small py-2 side-effects-state-note">
              <strong>{{ stateOf(sensor).label }}.</strong>
              {{ sensor.reason || 'This sensor is not recording rows right now.' }}
              <template v-if="OPT_IN[sensor.id] && sensor.state === 'not-claimed'">
                It is opt-in: add <code>{{ sensor.id }}</code> to <code>bootui.agent.sensors</code> to record
                {{ OPT_IN[sensor.id]
                }}<template v-if="sensor.toggle && canSwitch">, or switch it on above for this JVM</template>.
              </template>
            </div>

            <p v-if="sensor.id === 'resources'" class="small text-muted side-effects-resources-note">
              <strong>Reclaimed without close()</strong> counts resources the garbage collector found unreachable while
              still open: a leak. <strong>Open after request</strong> and <strong>Closed after request</strong> are
              resources handed off past their request, as a connection pool's sockets or a cache's file, which is often
              intended.
            </p>

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
                {{ emptyText(sensor) }}
              </UnavailableState>

              <template v-else-if="sensorRows(sensorReports[sensor.id]).length">
                <component
                  :is="section.apart ? 'details' : 'div'"
                  v-for="section in sections(sensorReports[sensor.id])"
                  :key="section.id"
                  :class="section.apart ? 'side-effects-apart mb-3' : ''"
                >
                  <summary v-if="section.apart" class="small fw-semibold mb-2">
                    {{ apartLabel(sensor) }} ({{ formatNumber(section.rows.length) }}), grouped apart
                  </summary>
                  <div class="table-responsive">
                    <table class="table table-sm align-middle side-effects-table">
                      <caption class="visually-hidden">
                        Side effects recorded by
                        {{
                          sensor.label
                        }}{{
                          section.apart ? `: ${apartLabel(sensor).toLowerCase()}` : ''
                        }}
                      </caption>
                      <thead>
                        <tr>
                          <th scope="col">Attribution</th>
                          <th scope="col">{{ columnsOf(sensor).target }}</th>
                          <template v-if="columnsOf(sensor).network">
                            <th scope="col">Client</th>
                            <th scope="col">Captured</th>
                          </template>
                          <th scope="col">Call site</th>
                          <th v-if="columnsOf(sensor).origin" scope="col">Origin</th>
                          <th scope="col" class="text-end">{{ columnsOf(sensor).count }}</th>
                          <template v-if="columnsOf(sensor).threads">
                            <th scope="col" class="text-end">Per request</th>
                            <th scope="col" class="text-end">Left running</th>
                            <th scope="col" class="text-end">Shut down</th>
                          </template>
                          <template v-if="columnsOf(sensor).resources">
                            <th scope="col" class="text-end">Open after request</th>
                            <th scope="col" class="text-end">Closed after request</th>
                          </template>
                          <th v-if="columnsOf(sensor).failed" scope="col" class="text-end">
                            {{ columnsOf(sensor).failedLabel || 'Failed' }}
                          </th>
                          <th v-if="columnsOf(sensor).exits" scope="col" class="text-end">Exits</th>
                          <th v-if="columnsOf(sensor).network" scope="col" class="text-end">Connected</th>
                          <th v-if="columnsOf(sensor).time" scope="col" class="text-end">
                            {{ columnsOf(sensor).time }}
                          </th>
                          <th scope="col">Last seen</th>
                          <th scope="col">Requests</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-if="!section.rows.length">
                          <td :colspan="columnCount(sensor)" class="small text-muted">
                            {{
                              sensor.id === 'thread-activity'
                                ? "Only libraries' and the JDK's threads and executors so far."
                                : 'Only class loading, the JDK, and logging so far.'
                            }}
                          </td>
                        </tr>
                        <tr
                          v-for="row in section.rows"
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
                            <div class="small text-muted">
                              <span v-if="row.kind">{{ row.kind }}</span>
                              <span v-if="row.location" class="badge text-bg-light border ms-1 side-effects-location">{{
                                locationLabel(row.location)
                              }}</span>
                            </div>
                          </td>
                          <template v-if="columnsOf(sensor).network">
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
                          <td v-if="columnsOf(sensor).origin">
                            <span v-if="row.origin" class="badge text-bg-light border side-effects-origin">{{
                              originLabel(row.origin, row.sensor)
                            }}</span>
                            <span v-else class="text-muted">—</span>
                          </td>
                          <td class="text-end">{{ formatNumber(row.count) }}</td>
                          <template v-if="columnsOf(sensor).threads">
                            <td class="text-end">{{ perRequest(row) }}</td>
                            <td class="text-end">
                              <span
                                v-if="row.leftRunning > 0"
                                class="badge text-bg-warning side-effects-left-running"
                                :title="`Still running when ${row.leftRunning === 1 ? 'its request' : 'their requests'} ended`"
                                >{{ formatNumber(row.leftRunning) }}</span
                              >
                              <span v-else>0</span>
                            </td>
                            <td class="text-end">
                              {{ row.kind === 'executor' ? formatNumber(row.completed) : '—' }}
                            </td>
                          </template>
                          <template v-if="columnsOf(sensor).resources">
                            <td class="text-end">
                              <span
                                v-if="row.leftRunning > 0"
                                class="badge text-bg-light border side-effects-left-open"
                                title="Still open 250 ms after its request's response completed: handed off, as a pool's connection, or not closed yet"
                                >{{ formatNumber(row.leftRunning) }}</span
                              >
                              <span v-else>0</span>
                            </td>
                            <td class="text-end">{{ formatNumber(row.completed) }}</td>
                          </template>
                          <td v-if="columnsOf(sensor).failed" class="text-end">
                            <span
                              v-if="columnsOf(sensor).resources && row.failed > 0"
                              class="badge text-bg-danger side-effects-reclaimed"
                              title="Reclaimed by the garbage collector while still open: never closed"
                              >{{ formatNumber(row.failed) }}</span
                            >
                            <template v-else>{{
                              columnsOf(sensor).threads && row.kind !== 'executor' ? '—' : formatNumber(row.failed)
                            }}</template>
                          </td>
                          <td v-if="columnsOf(sensor).exits" class="text-end">
                            {{ formatNumber(row.completed) }}
                            <div v-if="row.nonZeroExits > 0" class="small">
                              <span class="badge text-bg-warning">{{ formatNumber(row.nonZeroExits) }} non-zero</span>
                              <span v-if="row.lastExitStatus != null" class="text-muted ms-1">
                                last {{ row.lastExitStatus }}</span
                              >
                            </div>
                          </td>
                          <td v-if="columnsOf(sensor).network" class="text-end">
                            {{ row.kind === 'connect' ? formatNumber(row.completed) : '—' }}
                          </td>
                          <td v-if="columnsOf(sensor).time" class="text-end">
                            {{ columnsOf(sensor).threads && row.kind !== 'executor' ? '—' : formatLifetime(row) }}
                          </td>
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
                </component>

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

.side-effects-toggle {
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bootui-radius-md);
  padding: 0.75rem 1rem;
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

.side-effects-limitations summary,
.side-effects-apart summary {
  cursor: pointer;
}
</style>
