<script setup>
import {computed, ref} from 'vue'
import {getJson} from '../api.js'
import {formatMillis, formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import AgentSensorToggle from './components/AgentSensorToggle.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import JavaAgentAbout from './components/JavaAgentAbout.vue'
import JavaAgentSetup from './components/JavaAgentSetup.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)

const STATE_DETAILS = {
  NOT_ATTACHED: {
    label: 'Not attached',
    icon: 'bi-plug',
    tone: 'neutral',
    reason:
      'This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets below.'
  },
  DORMANT: {
    label: 'Dormant',
    icon: 'bi-pause-circle',
    tone: 'info',
    reason: 'The agent is attached, but this application has not claimed it.'
  },
  ARMED: {
    label: 'Armed',
    icon: 'bi-check-circle',
    tone: 'success',
    reason: 'This application holds the agent’s claim.'
  },
  HELD: {
    label: 'Held',
    icon: 'bi-lock',
    tone: 'warning',
    reason: 'Another application currently holds the BootUI agent claim.'
  },
  DISARMED: {
    label: 'Disarmed',
    icon: 'bi-shield-slash',
    tone: 'neutral',
    reason: 'This run ended its claim: the agent records nothing until the next run claims it.'
  },
  UNAVAILABLE: {
    label: 'Unavailable',
    icon: 'bi-exclamation-triangle',
    tone: 'warning',
    reason: 'The agent bridge is present but cannot be used by this JVM.'
  },
  FAILED: {
    label: 'Failed',
    icon: 'bi-x-octagon',
    tone: 'danger',
    reason: 'The agent failed this application’s claim.'
  },
  DISABLED: {
    label: 'Disabled',
    icon: 'bi-slash-circle',
    tone: 'neutral',
    reason: 'Java agent support is disabled for this runtime.'
  }
}

// The executors sensor's counters (docs/PLAN-v2.md M5-2), each with what it counts.
const EXECUTOR_COUNTERS = [
  ['pending', 'Pending', 'tasks received from owned work that have not run yet'],
  [
    'neverApplied',
    'Never applied',
    'received tasks that never reached an instrumented run point, such as tasks of pools whose workers started before the claim'
  ],
  ['ambiguous', 'Ambiguous', 'tasks submitted more than once by different owners, left unowned'],
  ['stale', 'Stale', 'tasks received under an earlier claim, never reopened after a restart'],
  ['refused', 'Refused', 'snapshots the bridge refused because they held more than strings and numbers'],
  ['overflow', 'Over the limit', 'tasks received from owned work while 32,768 were already pending, left unowned'],
  ['virtualSkipped', 'Virtual threads skipped', 'virtual-thread continuations, which keep their own context'],
  ['periodicSkipped', 'Periodic tasks skipped', 'repeating scheduled tasks, which are never propagated'],
  ['skippedTasks', 'Wrappers skipped', 'tasks already carrying their context (bootui.agent.executors.skip-tasks)'],
  [
    'skippedThreads',
    'Threads skipped',
    'workers whose executor propagates itself (bootui.agent.executors.skip-threads)'
  ],
  ['failures', 'Failed tasks', 'propagated tasks that ended with an exception']
]

// The threads sensor's counters (docs/PLAN-v2.md M5-2c), each with what it counts.
const THREAD_COUNTERS = [
  ['pending', 'Pending', 'threads started from owned work that have not run yet'],
  ['neverApplied', 'Never applied', 'started threads that never reached an instrumented run point'],
  ['ambiguous', 'Ambiguous', 'threads started more than once by different owners, left unowned'],
  ['stale', 'Stale', 'threads started under an earlier claim, never reopened after a restart'],
  ['refused', 'Refused', 'snapshots the bridge refused because they held more than strings and numbers'],
  ['overflow', 'Over the limit', 'threads started from owned work while 32,768 were already pending, left unowned'],
  [
    'libraryThreadsSkipped',
    'Library threads skipped',
    'threads a library or framework started inside owned work, such as a client’s I/O thread'
  ],
  [
    'poolWorkersSkipped',
    'Pool workers skipped',
    'pool worker threads, which never inherit the request they started under'
  ],
  [
    'skippedTasks',
    'Wrappers skipped',
    'threads whose task already carries its context (bootui.agent.executors.skip-tasks)'
  ],
  ['skippedThreads', 'Threads skipped', 'threads named in bootui.agent.executors.skip-threads'],
  ['failures', 'Failed threads', 'propagated threads that ended with an exception']
]

// The inventory sensor's counters (docs/PLAN-v2.md M5-3), each with what it counts.
const INVENTORY_COUNTERS = [
  ['methodsTracked', 'Methods tracked', 'application methods instrumented, whose executions the sensor sees'],
  ['executedThisRun', 'Executed this run', 'tracked methods that ran at least once since this run claimed the agent'],
  [
    'methodOverflow',
    'Over the method limit',
    'methods left uninstrumented because the agent’s method limit was reached'
  ],
  [
    'transformFailures',
    'Transform failures',
    'application classes that failed to transform, whose methods are not tracked'
  ],
  ['codeSources', 'Code sources', 'jars and class directories that defined at least one class'],
  ['ringDropped', 'Records dropped', 'records dropped because the agent’s ring was full (bootui.agent.ring-capacity)'],
  ['ringLost', 'Records lost', 'records whose writer never finished them'],
  ['internOverflow', 'Strings over the limit', 'routes recorded as unknown because the run’s string table was full']
]

// The code-paths sensor's counters (docs/PLAN-v2.md M5-4a), each with what it counts.
const CODE_PATHS_COUNTERS = [
  ['fragmentsFlushed', 'Fragments recorded', 'call trees of requests’ bean methods handed to BootUI, one per thread'],
  ['fragmentsDropped', 'Fragments dropped', 'fragments not recorded because every tree of the agent’s pool was in use'],
  ['queueDropped', 'Queue full', 'fragments dropped because the agent’s fragment queue was full'],
  [
    'callsDropped',
    'Calls in no node',
    'calls deeper than 32 levels or past a fragment’s node budget, whose time stays in their caller'
  ],
  ['queueBytes', 'Bytes waiting', 'fragments waiting for BootUI to read them'],
  [
    'excludedMethods',
    'Methods excluded',
    'methods called more than 50,000 times a second under 2 µs each, no longer timed in this run'
  ],
  ['errors', 'Internal errors', 'errors of the sensor itself; after 100 it switches itself off']
]

const sensorsWithHooks = computed(() => (report.value?.sensors ?? []).filter((sensor) => sensor.hooks?.length))

function sensorCounters(sensor) {
  if (sensor.codePaths) {
    return CODE_PATHS_COUNTERS.map(([key, label, explanation]) => ({
      key,
      label,
      explanation,
      value: sensor.codePaths[key] ?? 0
    }))
  }
  if (sensor.inventory) {
    return INVENTORY_COUNTERS.map(([key, label, explanation]) => ({
      key,
      label,
      explanation,
      value: sensor.inventory[key] ?? 0
    }))
  }
  const definitions = sensor.id === 'threads' ? THREAD_COUNTERS : EXECUTOR_COUNTERS
  return definitions.map(([key, label, explanation]) => ({
    key,
    label,
    explanation,
    value: sensor.executors?.[key] ?? 0
  }))
}

function sensorDisabledReason(sensor) {
  return (
    sensor.executors?.disabledReason ?? sensor.inventory?.disabledReason ?? sensor.codePaths?.disabledReason ?? null
  )
}

function sensorState(sensor) {
  if (sensor.state === 'installed' && sensor.durationMillis != null) {
    if (sensor.installMillis != null && sensor.selfTestMillis != null) {
      return `installed in ${formatMillis(sensor.durationMillis)} ms (install ${formatMillis(sensor.installMillis)} ms, self-test ${formatMillis(sensor.selfTestMillis)} ms)`
    }
    return `installed in ${formatMillis(sensor.durationMillis)} ms`
  }
  return sensor.state
}

function sensorRetransformation(sensor) {
  return `${formatNumber(sensor.retransformedTypes ?? 0)} in ${formatMillis(sensor.retransformMillis ?? 0)} ms`
}

function hookRole(sensor, hook) {
  if (hook.kind === 'record') {
    if (sensor.id === 'code-paths') return 'times bean methods per request'
    if (sensor.id === 'caught-exceptions') {
      return hook.id === 'exceptional exit' ? 'sees caught exceptions thrown again' : 'reports caught exceptions'
    }
    return hook.id === 'class load' ? 'counts loaded classes' : 'records first calls'
  }
  if (sensor.id === 'threads') return hook.kind === 'apply' ? 'runs threads' : 'starts threads'
  return hook.kind === 'apply' ? 'runs tasks' : 'receives tasks'
}

function hookInstalled(hook) {
  if (!hook.present) return 'unsupported on this JDK'
  return hook.transformed ? 'yes' : 'no'
}

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/java-agent')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Java Agent status')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport, {
  enabled: manifestAvailable
})

const toggles = computed(() => report.value?.toggles ?? [])

function onSensorSwitched(updated) {
  report.value = updated
  lastFetched.value = Date.now()
}

// Without an attached agent, what the agent adds and the setup open the panel, and the sections that describe an
// attached agent are left out. Every other state keeps the agent's own diagnosis first and the setup last.
const notAttached = computed(() => report.value?.state === 'NOT_ATTACHED')
const stateDetail = computed(() => STATE_DETAILS[report.value?.state] ?? STATE_DETAILS.UNAVAILABLE)
const stateLabel = computed(() => {
  if (report.value?.state === 'HELD') {
    return report.value.heldBy ? `Held by ${report.value.heldBy}` : 'Held'
  }
  return stateDetail.value.label
})
const stateReason = computed(() => report.value?.reason || stateDetail.value.reason)
const protocolText = computed(() => {
  if (!report.value) return '—'
  return `${report.value.protocol ?? '—'} / ${report.value.expectedProtocol}`
})
const startupMillis = computed(() => {
  if (report.value?.startupMicros == null) return '—'
  return `${formatMillis(Number(report.value.startupMicros) / 1000)} ms`
})
const facts = computed(() => {
  const value = report.value
  if (!value) return []
  return [
    {label: 'Agent version', value: value.agentVersion ?? '—'},
    {label: 'BootUI version', value: value.bootUiVersion ?? '—'},
    {label: 'Protocol', value: protocolText.value},
    {label: 'JDK', value: value.jdk ?? '—'},
    {label: 'Load mode', value: value.loadMode ?? '—'},
    {label: 'Agent jar', value: value.jarPath ?? '—', code: true, wide: true},
    {label: 'Premain startup', value: startupMillis.value}
  ]
})
const retransformationStats = computed(() => {
  const value = report.value?.retransformation
  if (!value) return []
  return [
    {label: 'State', value: value.state ?? '—'},
    {label: 'Transformed', value: formatNumber(value.transformed)},
    {label: 'Retransformed', value: formatNumber(value.retransformed)},
    {label: 'Failed', value: formatNumber(value.failed)},
    {label: 'Skipped', value: formatNumber(value.skipped)},
    {label: 'Install and release time (summed)', value: `${formatMillis(value.durationMillis)} ms`},
    {label: 'Running', value: value.running ? 'Yes' : 'No'}
  ]
})
const counterStats = computed(() => {
  const value = report.value?.counters
  if (!value) return []
  return [
    {label: 'Claims', value: formatNumber(value.claims)},
    {label: 'Takeovers', value: formatNumber(value.takeovers)},
    {label: 'Holds', value: formatNumber(value.holds)},
    {label: 'Stale tokens', value: formatNumber(value.staleTokens)},
    {label: 'Errors', value: formatNumber(value.errors)}
  ]
})

function formatTimestamp(epochMillis) {
  if (!epochMillis) return '—'
  return new Date(epochMillis).toLocaleString()
}

function badgeClass(flag, positiveLabel = 'Armed', negativeLabel = 'Disarmed') {
  return {
    class: flag ? 'text-bg-success' : 'text-bg-secondary',
    label: flag ? positiveLabel : negativeLabel
  }
}
</script>

<template>
  <div class="java-agent-panel">
    <PanelHeader
      icon="bi-plug-fill"
      title="Java Agent"
      subtitle="Whether the optional BootUI agent is attached, who holds its claim, and how to attach it."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <UnavailableState v-if="!manifestAvailable" icon="bi-plug-fill" :message="manifestUnavailableReason" />

    <PanelSkeleton v-else-if="initialLoading" />

    <template v-else-if="report">
      <section
        class="java-agent-banner mb-4"
        :class="`java-agent-banner--${stateDetail.tone}`"
        aria-labelledby="java-agent-state-title"
      >
        <div class="java-agent-banner__icon" aria-hidden="true">
          <i :class="['bi', stateDetail.icon]"></i>
        </div>
        <div class="min-width-0">
          <h3 id="java-agent-state-title" class="h5 fw-bold mb-1">{{ stateLabel }}</h3>
          <p class="mb-0 small">{{ stateReason }}</p>
        </div>
      </section>

      <template v-if="notAttached">
        <JavaAgentAbout class="mb-4" />
        <JavaAgentSetup class="mb-4" :setup="report.setup" :attached="false" />
      </template>

      <template v-else>
        <div class="row g-4 mb-4">
          <div class="col-xl-7">
            <section class="card h-100" aria-labelledby="java-agent-facts-title">
              <div class="card-body p-4">
                <h3 id="java-agent-facts-title" class="h6 fw-bold mb-3">
                  <i class="bi bi-info-circle me-2" aria-hidden="true"></i>Versions & runtime facts
                </h3>
                <dl class="java-agent-facts mb-0">
                  <template v-for="fact in facts" :key="fact.label">
                    <dt :class="{'java-agent-facts__wide': fact.wide}">{{ fact.label }}</dt>
                    <dd :class="{'java-agent-facts__wide': fact.wide}">
                      <code v-if="fact.code" class="bootui-break-anywhere">{{ fact.value }}</code>
                      <span v-else>{{ fact.value }}</span>
                    </dd>
                  </template>
                </dl>
              </div>
            </section>
          </div>

          <div class="col-xl-5">
            <section v-if="report.claim" class="card h-100" aria-labelledby="java-agent-claim-title">
              <div class="card-body p-4">
                <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
                  <h3 id="java-agent-claim-title" class="h6 fw-bold mb-0">
                    <i class="bi bi-person-lock me-2" aria-hidden="true"></i>Current claim
                  </h3>
                  <div class="d-flex flex-wrap gap-1">
                    <span :class="['badge', badgeClass(report.claim.armed).class]">
                      {{ badgeClass(report.claim.armed).label }}
                    </span>
                    <span
                      v-if="report.claim.armed"
                      :class="['badge', badgeClass(!report.claim.abandoned, 'Active', 'Abandoned').class]"
                    >
                      {{ badgeClass(!report.claim.abandoned, 'Active', 'Abandoned').label }}
                    </span>
                  </div>
                </div>
                <dl class="row small mb-3">
                  <dt class="col-5 text-muted fw-normal">Generation</dt>
                  <dd class="col-7">{{ formatNumber(report.claim.generation) }}</dd>
                  <dt class="col-5 text-muted fw-normal">Owner</dt>
                  <dd class="col-7">
                    <code class="bootui-break-anywhere">{{ report.claim.owner }}</code>
                  </dd>
                  <dt class="col-5 text-muted fw-normal">Application</dt>
                  <dd class="col-7">
                    <code class="bootui-break-anywhere">{{ report.claim.application }}</code>
                  </dd>
                  <dt class="col-5 text-muted fw-normal">Mode</dt>
                  <dd class="col-7">{{ report.claim.mode }}</dd>
                  <dt class="col-5 text-muted fw-normal">Armed since</dt>
                  <dd class="col-7">{{ formatTimestamp(report.claim.armedAt) }}</dd>
                </dl>
                <div>
                  <div class="text-muted small mb-2">Packages</div>
                  <ul v-if="report.claim.packages?.length" class="list-inline mb-0">
                    <li v-for="pkg in report.claim.packages" :key="pkg" class="list-inline-item mb-1">
                      <code class="java-agent-package">{{ pkg }}</code>
                    </li>
                  </ul>
                  <p v-else class="text-muted small mb-0">No package prefixes.</p>
                </div>
              </div>
            </section>

            <section v-else class="card h-100" aria-labelledby="java-agent-no-claim-title">
              <div class="card-body p-4 text-muted small">
                <h3 id="java-agent-no-claim-title" class="h6 fw-bold text-body mb-2">
                  <i class="bi bi-person-dash me-2" aria-hidden="true"></i>No active claim
                </h3>
                No application in this JVM holds the agent’s claim.
              </div>
            </section>
          </div>
        </div>

        <section class="card mb-4" aria-labelledby="java-agent-opt-in-title">
          <div class="card-body p-4">
            <h3 id="java-agent-opt-in-title" class="h6 fw-bold mb-1">
              <i class="bi bi-toggles me-2" aria-hidden="true"></i>Runtime switches
            </h3>
            <p class="small text-muted mb-3">
              Sensors switched on or off here for this application without a restart, each with why it is on or off by
              default. A switch overrides
              <code>bootui.agent.sensors</code> until this JVM ends, across DevTools restarts and Quarkus live reloads.
            </p>
            <ul v-if="toggles.length" class="list-unstyled mb-0 java-agent-toggles">
              <li v-for="toggle in toggles" :key="toggle.id">
                <AgentSensorToggle :toggle="toggle" @switched="onSensorSwitched" @stale="load" />
              </li>
            </ul>
            <p v-else class="small text-muted mb-0" data-testid="java-agent-toggles-unavailable">
              These sensors can be switched once the BootUI agent is attached and this application holds its claim.
            </p>
          </div>
        </section>

        <section class="card mb-4" aria-labelledby="java-agent-sensors-title">
          <div class="card-body p-4">
            <h3 id="java-agent-sensors-title" class="h6 fw-bold mb-3">
              <i class="bi bi-broadcast-pin me-2" aria-hidden="true"></i>Sensors
            </h3>
            <template v-if="report.sensors?.length">
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-0">
                  <thead>
                    <tr>
                      <th scope="col">Sensor</th>
                      <th scope="col">State</th>
                      <th scope="col">This claim</th>
                      <th scope="col">Self-test</th>
                      <th scope="col" class="text-end">Instrumented types</th>
                      <th scope="col" class="text-end">Retransformed</th>
                      <th scope="col">Failures</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="sensor in report.sensors" :key="sensor.id">
                      <td>
                        <code>{{ sensor.id }}</code>
                      </td>
                      <td>{{ sensorState(sensor) }}</td>
                      <td>
                        <span v-if="sensor.active" class="text-success-emphasis">active</span>
                        <span v-else class="text-muted">inactive</span>
                      </td>
                      <td>
                        <span v-if="sensor.selfTestPassed" class="text-success-emphasis">passed</span>
                        <span v-else-if="sensor.selfTestError" class="text-danger-emphasis">{{
                          sensor.selfTestError
                        }}</span>
                        <span v-else class="text-muted">not run</span>
                      </td>
                      <td class="text-end">{{ formatNumber(sensor.instrumentedTypes) }}</td>
                      <td class="text-end">{{ sensorRetransformation(sensor) }}</td>
                      <td>
                        <span v-if="!sensor.failures?.length" class="text-muted">—</span>
                        <ul v-else class="mb-0 ps-3">
                          <li v-for="failure in sensor.failures" :key="failure">{{ failure }}</li>
                        </ul>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <template v-for="sensor in sensorsWithHooks" :key="`hooks-${sensor.id}`">
                <h4 :id="`java-agent-hooks-${sensor.id}`" class="h6 small text-muted mt-4 mb-2">
                  <code>{{ sensor.id }}</code> hooks
                </h4>
                <div class="table-responsive">
                  <table class="table table-sm align-middle mb-0" :aria-labelledby="`java-agent-hooks-${sensor.id}`">
                    <thead>
                      <tr>
                        <th scope="col">Hook</th>
                        <th scope="col">Role</th>
                        <th scope="col">Installed</th>
                        <th scope="col">Self-test</th>
                        <th scope="col" class="text-end">Fired</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="hook in sensor.hooks" :key="hook.id">
                        <td>
                          <code :title="hook.type">{{ hook.id }}</code>
                        </td>
                        <td>{{ hookRole(sensor, hook) }}</td>
                        <td>{{ hookInstalled(hook) }}</td>
                        <td>{{ hook.selfTest }}</td>
                        <td class="text-end">{{ formatNumber(hook.fired) }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <template v-if="sensor.executors || sensor.inventory || sensor.codePaths">
                  <p v-if="sensorDisabledReason(sensor)" class="alert alert-warning small mt-3 mb-0" role="note">
                    {{ sensor.inventory || sensor.codePaths ? 'Recording' : 'Propagation' }} is disabled for this claim:
                    {{ sensorDisabledReason(sensor) }}
                  </p>
                  <h4 :id="`java-agent-counters-${sensor.id}`" class="h6 small text-muted mt-4 mb-2">Counters</h4>
                  <dl
                    class="row small mb-0 java-agent-counters"
                    :data-sensor="sensor.id"
                    :aria-labelledby="`java-agent-counters-${sensor.id}`"
                  >
                    <template v-for="counter in sensorCounters(sensor)" :key="counter.key">
                      <dt class="col-sm-4 col-lg-3">
                        {{ counter.label }} <span class="fw-normal">{{ formatNumber(counter.value) }}</span>
                      </dt>
                      <dd class="col-sm-8 col-lg-9 text-muted">{{ counter.explanation }}</dd>
                    </template>
                  </dl>
                </template>
              </template>
            </template>
            <p v-else class="text-muted small mb-0">
              No sensor installed: the agent installs the sensors this application asks for when it claims the agent
              (bootui.agent.sensors).
            </p>
          </div>
        </section>

        <div v-if="report.retransformation || report.counters" class="row g-4 mb-4">
          <div v-if="report.retransformation" class="col-lg-7">
            <section class="card h-100" aria-labelledby="java-agent-retransform-title">
              <div class="card-body p-4">
                <h3 id="java-agent-retransform-title" class="h6 fw-bold mb-3">
                  <i class="bi bi-arrow-repeat me-2" aria-hidden="true"></i>Class transformation
                </h3>
                <p class="small text-muted mb-3">
                  All sensors since the JVM started, summed: transformers stay installed across claims, and sensors
                  install one after another, so the time is aggregate work rather than a wall-clock interval.
                </p>
                <div class="java-agent-strip">
                  <div v-for="stat in retransformationStats" :key="stat.label" class="java-agent-stat">
                    <div class="java-agent-stat__value">{{ stat.value }}</div>
                    <div class="java-agent-stat__label">{{ stat.label }}</div>
                  </div>
                </div>
              </div>
            </section>
          </div>
          <div v-if="report.counters" class="col-lg-5">
            <section class="card h-100" aria-labelledby="java-agent-counters-title">
              <div class="card-body p-4">
                <h3 id="java-agent-counters-title" class="h6 fw-bold mb-3">
                  <i class="bi bi-speedometer2 me-2" aria-hidden="true"></i>Counters
                </h3>
                <div class="java-agent-strip java-agent-strip--compact">
                  <div v-for="stat in counterStats" :key="stat.label" class="java-agent-stat">
                    <div class="java-agent-stat__value">{{ stat.value }}</div>
                    <div class="java-agent-stat__label">{{ stat.label }}</div>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </div>
      </template>

      <div v-if="report.warnings?.length || report.messages?.length" class="row g-4 mb-4">
        <div v-if="report.warnings?.length" class="col-lg-6">
          <section class="alert alert-warning h-100 mb-0" aria-labelledby="java-agent-warnings-title">
            <h3 id="java-agent-warnings-title" class="h6 fw-bold mb-2">
              <i class="bi bi-exclamation-triangle me-2" aria-hidden="true"></i>Warnings
            </h3>
            <ul class="mb-0 ps-3 small">
              <li v-for="(warning, index) in report.warnings" :key="index">{{ warning }}</li>
            </ul>
          </section>
        </div>
        <div v-if="report.messages?.length" class="col-lg-6">
          <section class="card h-100" aria-labelledby="java-agent-messages-title">
            <div class="card-body p-4">
              <h3 id="java-agent-messages-title" class="h6 fw-bold mb-2">
                <i class="bi bi-chat-left-text me-2" aria-hidden="true"></i>Messages
              </h3>
              <ol class="mb-0 ps-3 small">
                <li v-for="(message, index) in report.messages" :key="index" class="mb-1">{{ message }}</li>
              </ol>
            </div>
          </section>
        </div>
      </div>

      <JavaAgentSetup v-if="!notAttached" :setup="report.setup" />
    </template>

    <UnavailableState v-else-if="!error" icon="bi-plug-fill" message="Java Agent status is unavailable." />
  </div>
</template>

<style scoped>
.java-agent-toggles {
  display: grid;
  gap: 1rem;
}

.java-agent-banner {
  align-items: flex-start;
  border: 1px solid var(--java-agent-banner-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  color: var(--java-agent-banner-text);
  display: flex;
  gap: 1rem;
  padding: 1.15rem 1.25rem;
  background: var(--java-agent-banner-bg);
}

.java-agent-banner__icon {
  align-items: center;
  background: color-mix(in srgb, var(--java-agent-banner-accent) 14%, var(--bootui-surface-solid));
  border: 1px solid color-mix(in srgb, var(--java-agent-banner-accent) 28%, transparent);
  border-radius: var(--bootui-radius-md);
  color: var(--java-agent-banner-accent);
  display: inline-flex;
  flex: 0 0 auto;
  font-size: 1.25rem;
  height: 2.8rem;
  justify-content: center;
  width: 2.8rem;
}

.java-agent-banner--neutral {
  --java-agent-banner-accent: var(--bootui-text-muted);
  --java-agent-banner-bg: color-mix(in srgb, var(--bootui-text-muted) 8%, var(--bootui-surface-solid));
  --java-agent-banner-border: color-mix(in srgb, var(--bootui-text-muted) 18%, transparent);
  --java-agent-banner-text: var(--bootui-body-color);
}

.java-agent-banner--info {
  --java-agent-banner-accent: var(--bootui-info-text);
  --java-agent-banner-bg: color-mix(in srgb, var(--bootui-info-text) 8%, var(--bootui-surface-solid));
  --java-agent-banner-border: color-mix(in srgb, var(--bootui-info-text) 22%, transparent);
  --java-agent-banner-text: var(--bootui-body-color);
}

.java-agent-banner--success {
  --java-agent-banner-accent: var(--bootui-green-dark);
  --java-agent-banner-bg: color-mix(in srgb, var(--bootui-green) 8%, var(--bootui-surface-solid));
  --java-agent-banner-border: color-mix(in srgb, var(--bootui-green) 24%, transparent);
  --java-agent-banner-text: var(--bootui-body-color);
}

.java-agent-banner--warning {
  --java-agent-banner-accent: var(--bootui-warning-text-strong);
  --java-agent-banner-bg: color-mix(in srgb, var(--bootui-warning-text-strong) 10%, var(--bootui-surface-solid));
  --java-agent-banner-border: color-mix(in srgb, var(--bootui-warning-text-strong) 24%, transparent);
  --java-agent-banner-text: var(--bootui-body-color);
}

.java-agent-banner--danger {
  --java-agent-banner-accent: var(--bootui-danger-text);
  --java-agent-banner-bg: color-mix(in srgb, var(--bootui-danger-text) 8%, var(--bootui-surface-solid));
  --java-agent-banner-border: color-mix(in srgb, var(--bootui-danger-text) 22%, transparent);
  --java-agent-banner-text: var(--bootui-body-color);
}

.java-agent-facts {
  display: grid;
  gap: 0.75rem 1rem;
  grid-template-columns: minmax(8rem, max-content) 1fr;
}

.java-agent-facts dt {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
  font-weight: 500;
}

.java-agent-facts dd {
  margin-bottom: 0;
  min-width: 0;
}

.java-agent-facts__wide {
  grid-column: 1 / -1;
}

.java-agent-package {
  background: color-mix(in srgb, var(--bootui-green) 8%, var(--bootui-surface-solid));
  border: 1px solid color-mix(in srgb, var(--bootui-green) 16%, transparent);
  border-radius: var(--bootui-radius-pill);
  display: inline-block;
  padding: 0.18rem 0.5rem;
}

.java-agent-strip {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr));
}

.java-agent-strip--compact {
  grid-template-columns: repeat(auto-fit, minmax(6rem, 1fr));
}

.java-agent-stat {
  background: color-mix(in srgb, var(--bootui-green) 5%, var(--bootui-surface-solid));
  border: 1px solid var(--bootui-border-subtle);
  border-radius: var(--bootui-radius-md);
  padding: 0.8rem;
}

.java-agent-stat__value {
  font-weight: 700;
  overflow-wrap: anywhere;
}

.java-agent-stat__label {
  color: var(--bootui-text-muted);
  font-size: 0.78rem;
}

.min-width-0 {
  min-width: 0;
}

@media (max-width: 575.98px) {
  .java-agent-banner {
    flex-direction: column;
  }

  .java-agent-facts {
    grid-template-columns: 1fr;
  }
}
</style>
