<script setup>
import {computed, ref, watch} from 'vue'
import {getJson} from '../api.js'
import {formatMillis, formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const activeSnippetId = ref(null)
const copyBlocked = ref(false)
const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

const STATE_DETAILS = {
  NOT_ATTACHED: {
    label: 'Not attached',
    icon: 'bi-plug',
    tone: 'neutral',
    reason:
      'This JVM runs without the BootUI agent. Attach it with one of the setup snippets below; BootUI works without it.'
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

const sensorsWithHooks = computed(() => (report.value?.sensors ?? []).filter((sensor) => sensor.hooks?.length))

function sensorCounters(sensor) {
  const definitions = sensor.id === 'threads' ? THREAD_COUNTERS : EXECUTOR_COUNTERS
  return definitions.map(([key, label, explanation]) => ({
    key,
    label,
    explanation,
    value: sensor.executors?.[key] ?? 0
  }))
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
  enabled: manifestAvailable,
  defaultEnabled: false
})

const snippets = computed(() => report.value?.setup?.snippets ?? [])
const activeSnippet = computed(() => snippets.value.find((snippet) => snippet.id === activeSnippetId.value) ?? null)
const copiedActiveSnippet = computed(() => copiedKey.value === copyKey(activeSnippet.value))
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
    {label: 'Retransformation time (summed)', value: `${formatMillis(value.durationMillis)} ms`},
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

watch(
  snippets,
  (items) => {
    if (!items.length) {
      activeSnippetId.value = null
      return
    }
    if (!items.some((snippet) => snippet.id === activeSnippetId.value)) {
      activeSnippetId.value = items[0].id
    }
  },
  {immediate: true}
)

function copyKey(snippet) {
  return snippet ? `java-agent-${snippet.id}` : null
}

async function copyActiveSnippet() {
  copyBlocked.value = false
  if (!activeSnippet.value) return
  const copied = await copyToClipboard(activeSnippet.value.text, copyKey(activeSnippet.value))
  copyBlocked.value = !copied
}

function selectSnippet(snippet) {
  activeSnippetId.value = snippet.id
  copyBlocked.value = false
}

function onSnippetKeydown(event, index) {
  if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const count = snippets.value.length
  if (!count) return
  let next = index
  if (event.key === 'ArrowRight') next = (index + 1) % count
  if (event.key === 'ArrowLeft') next = (index - 1 + count) % count
  if (event.key === 'Home') next = 0
  if (event.key === 'End') next = count - 1
  const snippet = snippets.value[next]
  activeSnippetId.value = snippet.id
  event.currentTarget.closest('[role="tablist"]')?.querySelectorAll('[role="tab"]')[next]?.focus()
}

function formatTimestamp(epochMillis) {
  if (!epochMillis) return '—'
  return new Date(epochMillis).toLocaleString()
}

function languageLabel(language) {
  return {xml: 'XML', kotlin: 'Kotlin', groovy: 'Groovy', shell: 'Shell', text: 'Text'}[language] ?? language ?? 'Text'
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
              <template v-if="sensor.executors">
                <p v-if="sensor.executors.disabledReason" class="alert alert-warning small mt-3 mb-0" role="note">
                  Propagation is disabled for this claim: {{ sensor.executors.disabledReason }}
                </p>
                <h4 class="h6 small text-muted mt-4 mb-2">Counters</h4>
                <dl class="row small mb-0 java-agent-counters">
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

      <section class="card java-agent-setup" aria-labelledby="java-agent-setup-title">
        <div class="card-body p-4">
          <div class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-3">
            <div>
              <h3 id="java-agent-setup-title" class="h6 fw-bold mb-2">
                <i class="bi bi-wrench-adjustable-circle me-2" aria-hidden="true"></i>Setup snippets
              </h3>
              <dl class="row small mb-0">
                <dt class="col-sm-4 text-muted fw-normal">Agent jar</dt>
                <dd class="col-sm-8">
                  <code class="bootui-break-anywhere">{{ report.setup?.jarPath ?? '—' }}</code>
                </dd>
                <dt class="col-sm-4 text-muted fw-normal">Jar found</dt>
                <dd class="col-sm-8">
                  <span :class="['badge', report.setup?.jarFound ? 'text-bg-success' : 'text-bg-secondary']">
                    {{ report.setup?.jarFound ? 'Found' : 'Not found' }}
                  </span>
                </dd>
                <dt class="col-sm-4 text-muted fw-normal">Build tool</dt>
                <dd class="col-sm-8">{{ report.setup?.buildTool ?? 'UNKNOWN' }}</dd>
              </dl>
            </div>
            <button
              type="button"
              class="btn btn-sm"
              :class="copiedActiveSnippet ? 'btn-success' : 'btn-outline-secondary'"
              :disabled="!activeSnippet"
              :title="copiedActiveSnippet ? 'Copied!' : 'Copy snippet'"
              @click="copyActiveSnippet"
            >
              <i :class="['bi', copiedActiveSnippet ? 'bi-check-lg' : 'bi-clipboard', 'me-1']" aria-hidden="true"></i>
              {{ copiedActiveSnippet ? 'Copied!' : 'Copy' }}
            </button>
            <span class="visually-hidden" aria-live="polite">
              {{ copiedActiveSnippet && activeSnippet ? `${activeSnippet.label} snippet copied` : '' }}
            </span>
          </div>

          <div v-if="snippets.length" class="java-agent-snippets">
            <div class="java-agent-tabs" role="tablist" aria-label="Java agent setup snippets">
              <button
                v-for="(snippet, index) in snippets"
                :id="`java-agent-tab-${snippet.id}`"
                :key="snippet.id"
                type="button"
                role="tab"
                class="java-agent-tab"
                :class="{'java-agent-tab--active': snippet.id === activeSnippetId}"
                :aria-controls="`java-agent-panel-${snippet.id}`"
                :aria-selected="snippet.id === activeSnippetId ? 'true' : 'false'"
                :tabindex="snippet.id === activeSnippetId ? 0 : -1"
                @click="selectSnippet(snippet)"
                @keydown="onSnippetKeydown($event, index)"
              >
                {{ snippet.label }}
              </button>
            </div>
            <div
              v-for="snippet in snippets"
              v-show="snippet.id === activeSnippetId"
              :id="`java-agent-panel-${snippet.id}`"
              :key="`${snippet.id}-panel`"
              role="tabpanel"
              class="java-agent-snippet-panel"
              :aria-labelledby="`java-agent-tab-${snippet.id}`"
              tabindex="0"
            >
              <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
                <h4 class="h6 mb-0">{{ snippet.label }}</h4>
                <span class="badge text-bg-secondary">{{ languageLabel(snippet.language) }}</span>
              </div>
              <pre class="java-agent-code rounded p-3 mb-0"><code>{{ snippet.text }}</code></pre>
            </div>
          </div>
          <p v-else class="text-muted small mb-0">No setup snippets were returned for this runtime.</p>
          <p v-if="copyBlocked" class="alert alert-warning small mt-3 mb-0">
            The browser blocked clipboard access, so nothing was copied. Select the snippet text and copy it manually.
          </p>
        </div>
      </section>
    </template>

    <UnavailableState v-else-if="!error" icon="bi-plug-fill" message="Java Agent status is unavailable." />
  </div>
</template>

<style scoped>
.java-agent-banner {
  align-items: flex-start;
  border: 1px solid var(--java-agent-banner-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: 0 0.25rem 0.75rem rgba(15, 23, 42, 0.05);
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

.java-agent-tabs {
  border-bottom: 1px solid var(--bootui-border-subtle);
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  margin-bottom: 1rem;
}

.java-agent-tab {
  background: transparent;
  border: 0;
  border-radius: var(--bootui-radius-sm) var(--bootui-radius-sm) 0 0;
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
  font-weight: 700;
  margin-bottom: -1px;
  padding: 0.55rem 0.75rem;
}

.java-agent-tab:hover {
  color: var(--bootui-green-dark);
}

.java-agent-tab:focus-visible {
  outline: 0.18rem solid color-mix(in srgb, var(--bootui-green) 38%, transparent);
  outline-offset: 0.12rem;
}

.java-agent-tab--active {
  background: linear-gradient(135deg, var(--bootui-green), var(--bootui-blue));
  color: #fff;
}

.java-agent-tab--active:hover {
  color: #fff;
}

.java-agent-snippet-panel:focus-visible {
  outline: 0.18rem solid color-mix(in srgb, var(--bootui-blue) 35%, transparent);
  outline-offset: 0.2rem;
}

.java-agent-code {
  background: var(--bs-dark);
  color: var(--bs-light);
  font-size: 0.85rem;
  max-height: 26rem;
  overflow: auto;
  white-space: pre;
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
